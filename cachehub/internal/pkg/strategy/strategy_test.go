package strategy

import (
	"math"
	"testing"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/testfixtures"
	"github.com/sirupsen/logrus"
)

func setupStrategyTestEnvironment(t *testing.T) (*cache_manager.CacheManager, *StrategyManager, *testfixtures.TestDataBuilder) {
	logger := logrus.New()
	logger.SetLevel(logrus.WarnLevel)

	cm := cache_manager.NewCacheManager(logger)
	sm := NewStrategyManager(cm, logger)
	builder := testfixtures.NewTestDataBuilder()

	instance := builder.BuildDefaultCacheInstance()
	err := cm.RegisterInstance(instance)
	if err != nil {
		t.Fatalf("Failed to register cache instance: %v", err)
	}

	return cm, sm, builder
}

func TestTTLRandomizationDefaultConfig(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	config := sm.GetTTLRandomizationConfig()
	if !config.Enabled {
		t.Error("Expected TTL randomization to be enabled by default")
	}
	if config.JitterRange != 0.1 {
		t.Errorf("Expected default jitter range to be 0.1, got %f", config.JitterRange)
	}
	if config.MaxOffset != 300 {
		t.Errorf("Expected default max offset to be 300, got %d", config.MaxOffset)
	}
}

func TestTTLJitterApplication(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	baseTTL := 3600
	iterations := 1000
	jitterRange := 0.1

	results := make([]int, iterations)
	for i := 0; i < iterations; i++ {
		results[i] = sm.ApplyTTLJitter(baseTTL)
	}

	minTTL := baseTTL - int(float64(baseTTL)*jitterRange)
	maxTTL := baseTTL + int(float64(baseTTL)*jitterRange)

	for i, ttl := range results {
		if ttl < 1 {
			t.Errorf("Iteration %d: Expected TTL >= 1, got %d", i, ttl)
		}
		if ttl > maxTTL {
			t.Errorf("Iteration %d: Expected TTL <= %d, got %d", i, maxTTL, ttl)
		}
		if ttl < minTTL {
			t.Errorf("Iteration %d: Expected TTL >= %d, got %d", i, minTTL, ttl)
		}
	}

	hasDifferentValues := false
	firstValue := results[0]
	for _, ttl := range results[1:] {
		if ttl != firstValue {
			hasDifferentValues = true
			break
		}
	}
	if !hasDifferentValues {
		t.Warning("All TTL values are the same - randomization may not be working")
	}
}

func TestTTLJitterWithCustomConfig(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	customConfig := TTLRandomizationConfig{
		Enabled:     true,
		MaxOffset:   100,
		MinOffset:   0,
		JitterRange: 0.2,
	}
	sm.SetTTLRandomizationConfig(customConfig)

	config := sm.GetTTLRandomizationConfig()
	if config.JitterRange != 0.2 {
		t.Errorf("Expected jitter range to be 0.2, got %f", config.JitterRange)
	}

	baseTTL := 1000
	iterations := 500
	jitterRange := 0.2

	results := make([]int, iterations)
	for i := 0; i < iterations; i++ {
		results[i] = sm.ApplyTTLJitter(baseTTL)
	}

	maxExpectedOffset := int(float64(baseTTL) * jitterRange)
	if maxExpectedOffset > 100 {
		maxExpectedOffset = 100
	}

	for i, ttl := range results {
		expectedMin := baseTTL - maxExpectedOffset
		expectedMax := baseTTL + maxExpectedOffset

		if ttl < expectedMin || ttl > expectedMax {
			t.Errorf("Iteration %d: Expected TTL between %d and %d, got %d",
				i, expectedMin, expectedMax, ttl)
		}
	}
}

func TestTTLJitterDisabled(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	sm.DisableTTLRandomization()

	baseTTL := 3600
	iterations := 100

	for i := 0; i < iterations; i++ {
		result := sm.ApplyTTLJitter(baseTTL)
		if result != baseTTL {
			t.Errorf("Iteration %d: Expected TTL to be %d when disabled, got %d",
				i, baseTTL, result)
		}
	}

	sm.EnableTTLRandomization()
	if !sm.GetTTLRandomizationConfig().Enabled {
		t.Error("Expected TTL randomization to be re-enabled")
	}
}

func TestGenerateBatchTTLs(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	baseTTL := 3600
	count := 100

	ttls := sm.GenerateBatchTTLs(baseTTL, count)

	if len(ttls) != count {
		t.Fatalf("Expected %d TTLs, got %d", count, len(ttls))
	}

	for i, ttl := range ttls {
		if ttl < 1 {
			t.Errorf("TTL %d: Expected value >= 1, got %d", i, ttl)
		}
	}
}

func TestBatchTTLSpreadCalculation(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	baseTTL := 3600
	count := 100

	ttls := sm.GenerateBatchTTLs(baseTTL, count)

	spread := sm.CalculateTTLSpread(ttls)

	if spread < 0 {
		t.Errorf("Expected spread >= 0, got %f", spread)
	}

	expectedMinSpread := 5.0
	if spread < expectedMinSpread {
		t.Logf("Warning: TTL spread is low (%f%%), batch may expire together", spread)
	}
}

func TestIsBatchExpiringTogether(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	ttlsTogether := []int{3600, 3601, 3602, 3603, 3604}
	threshold := 0.05

	if !sm.IsBatchExpiringTogether(ttlsTogether, threshold) {
		t.Error("Expected similar TTLs to be detected as expiring together")
	}

	ttlsSpread := []int{1000, 2000, 3000, 4000, 5000}
	if sm.IsBatchExpiringTogether(ttlsSpread, threshold) {
		t.Error("Expected spread TTLs to not be detected as expiring together")
	}
}

func TestBatchTTLProtectionAgainstAvalanche(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	sm.SetTTLRandomizationConfig(TTLRandomizationConfig{
		Enabled:     true,
		MaxOffset:   600,
		MinOffset:   0,
		JitterRange: 0.3,
	})

	baseTTL := 3600
	batchSize := 500
	testRuns := 10

	for run := 0; run < testRuns; run++ {
		ttls := sm.GenerateBatchTTLs(baseTTL, batchSize)

		threshold := 0.1
		if sm.IsBatchExpiringTogether(ttls, threshold) {
			t.Errorf("Run %d: Batch detected as expiring together, spread=%f%%",
				run, sm.CalculateTTLSpread(ttls))
		}

		spread := sm.CalculateTTLSpread(ttls)
		if spread < 10.0 {
			t.Logf("Run %d: Low TTL spread detected (%f%%)", run, spread)
		}
	}
}

func TestCacheAvalancheSimulation(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	sm.SetTTLRandomizationConfig(TTLRandomizationConfig{
		Enabled:     true,
		MaxOffset:   300,
		MinOffset:   0,
		JitterRange: 0.15,
	})

	baseTTL := 1800
	cacheItemCount := 1000

	ttls := sm.GenerateBatchTTLs(baseTTL, cacheItemCount)

	expireBucket := make(map[int]int)
	for _, ttl := range ttls {
		bucket := ttl / 30
		expireBucket[bucket]++
	}

	maxInBucket := 0
	for _, count := range expireBucket {
		if count > maxInBucket {
			maxInBucket = count
		}
	}

	expectedAvg := cacheItemCount / (baseTTL / 30)
	if expectedAvg == 0 {
		expectedAvg = 1
	}

	ratio := float64(maxInBucket) / float64(expectedAvg)
	maxAllowedRatio := 5.0

	if ratio > maxAllowedRatio {
		t.Errorf("Cache avalanche risk detected: max bucket count %d, avg %d, ratio %.2f (allowed %.2f)",
			maxInBucket, expectedAvg, ratio, maxAllowedRatio)
	}

	t.Logf("TTL distribution: max bucket=%d, avg=%d, ratio=%.2f",
		maxInBucket, expectedAvg, ratio)
}

func TestTTLJitterStatistics(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	baseTTL := 3600
	iterations := 10000

	results := make([]int, iterations)
	for i := 0; i < iterations; i++ {
		results[i] = sm.ApplyTTLJitter(baseTTL)
	}

	sum := 0
	minResult := math.MaxInt32
	maxResult := 0
	for _, v := range results {
		sum += v
		if v < minResult {
			minResult = v
		}
		if v > maxResult {
			maxResult = v
		}
	}

	avg := float64(sum) / float64(iterations)

	expectedMin := baseTTL - int(float64(baseTTL)*0.1)
	expectedMax := baseTTL + int(float64(baseTTL)*0.1)

	t.Logf("TTL Jitter Stats:")
	t.Logf("  Base TTL: %d", baseTTL)
	t.Logf("  Min result: %d", minResult)
	t.Logf("  Max result: %d", maxResult)
	t.Logf("  Average: %.2f", avg)
	t.Logf("  Expected range: [%d, %d]", expectedMin, expectedMax)

	if minResult < expectedMin {
		t.Errorf("Min result %d is below expected minimum %d", minResult, expectedMin)
	}
	if maxResult > expectedMax {
		t.Errorf("Max result %d is above expected maximum %d", maxResult, expectedMax)
	}

	tolerance := 50.0
	if math.Abs(avg-float64(baseTTL)) > tolerance {
		t.Logf("Warning: Average TTL (%.2f) deviates from base (%d)", avg, baseTTL)
	}
}

func TestTTLRandomizationWithZeroBase(t *testing.T) {
	_, sm, _ := setupStrategyTestEnvironment(t)

	result := sm.ApplyTTLJitter(0)
	if result != 0 {
		t.Errorf("Expected 0 for base TTL 0, got %d", result)
	}

	result = sm.ApplyTTLJitter(-100)
	if result >= 0 {
		t.Errorf("Expected negative result for negative base TTL, got %d", result)
	}
}

func TestPolicyBasedTTL(t *testing.T) {
	cm, sm, builder := setupStrategyTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	defaultTTL := sm.GetTTL(cacheID, "unknown_key")
	if defaultTTL == 0 {
		t.Error("Expected non-zero default TTL")
	}

	policy := builder.BuildDefaultPolicy()
	policy.CacheID = cacheID
	err := sm.SetPolicy(policy)
	if err != nil {
		t.Fatalf("SetPolicy failed: %v", err)
	}

	sessionTTL := sm.GetTTL(cacheID, "session")
	if sessionTTL != 1800 {
		t.Errorf("Expected session TTL to be 1800, got %d", sessionTTL)
	}

	tokenTTL := sm.GetTTL(cacheID, "token")
	if tokenTTL != 300 {
		t.Errorf("Expected token TTL to be 300, got %d", tokenTTL)
	}

	otherTTL := sm.GetTTL(cacheID, "other_key")
	if otherTTL != 3600 {
		t.Errorf("Expected default policy TTL to be 3600, got %d", otherTTL)
	}

	_ = cm
}

func TestStrategyManagerWithOptions(t *testing.T) {
	logger := logrus.New()
	cm := cache_manager.NewCacheManager(logger)

	customConfig := TTLRandomizationConfig{
		Enabled:     true,
		MaxOffset:   500,
		MinOffset:   10,
		JitterRange: 0.25,
	}

	sm := NewStrategyManagerWithOptions(cm, logger, customConfig)

	config := sm.GetTTLRandomizationConfig()
	if config.JitterRange != 0.25 {
		t.Errorf("Expected jitter range 0.25, got %f", config.JitterRange)
	}
	if config.MaxOffset != 500 {
		t.Errorf("Expected max offset 500, got %d", config.MaxOffset)
	}
}

func TestInvalidTTLRandomizationConfig(t *testing.T) {
	logger := logrus.New()
	cm := cache_manager.NewCacheManager(logger)

	invalidConfig := TTLRandomizationConfig{
		Enabled:     true,
		MaxOffset:   0,
		MinOffset:   0,
		JitterRange: 0,
	}

	sm := NewStrategyManagerWithOptions(cm, logger, invalidConfig)

	config := sm.GetTTLRandomizationConfig()
	if config.JitterRange <= 0 {
		t.Error("Expected jitter range to be set to default for invalid input")
	}
}
