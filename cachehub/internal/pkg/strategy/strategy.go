package strategy

import (
	"errors"
	"math"
	"math/rand"
	"sort"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/sirupsen/logrus"
)

type TTLRandomizationConfig struct {
	Enabled     bool
	MaxOffset   int
	MinOffset   int
	JitterRange float64
}

type StrategyManager struct {
	policies                 map[string]*models.CachePolicy
	mu                       sync.RWMutex
	cm                       *cache_manager.CacheManager
	logger                   *logrus.Logger
	ttlRandomization         TTLRandomizationConfig
	randSource               *rand.Rand
	randMu                   sync.Mutex
	evictionStrategies        map[string]models.EvictionStrategy
	evictionStrategiesMu         sync.RWMutex
	dynamicJitterConfig      models.DynamicJitterConfig
}

func NewStrategyManager(cm *cache_manager.CacheManager, logger *logrus.Logger) *StrategyManager {
	sm := &StrategyManager{
		policies:         make(map[string]*models.CachePolicy),
		cm:               cm,
		logger:           logger,
		ttlRandomization: TTLRandomizationConfig{
			Enabled:     true,
			MaxOffset:   300,
			MinOffset:   0,
			JitterRange: 0.1,
		},
		randSource:       rand.New(rand.NewSource(time.Now().UnixNano())),
		evictionStrategies: make(map[string]models.EvictionStrategy),
		dynamicJitterConfig: *models.NewDynamicJitterConfig(),
	}

	sm.registerDefaultStrategies()
	return sm
}

func NewStrategyManagerWithOptions(cm *cache_manager.CacheManager, logger *logrus.Logger, randomization TTLRandomizationConfig) *StrategyManager {
	if randomization.JitterRange <= 0 {
		randomization.JitterRange = 0.1
	}
	if randomization.MaxOffset <= 0 {
		randomization.MaxOffset = 300
	}
	sm := &StrategyManager{
		policies:         make(map[string]*models.CachePolicy),
		cm:               cm,
		logger:           logger,
		ttlRandomization: randomization,
		randSource:       rand.New(rand.NewSource(time.Now().UnixNano())),
		evictionStrategies: make(map[string]models.EvictionStrategy),
		dynamicJitterConfig: *models.NewDynamicJitterConfig(),
	}

	sm.registerDefaultStrategies()
	return sm
}

func (sm *StrategyManager) registerDefaultStrategies() {
	sm.evictionStrategiesMu.Lock()
	defer sm.evictionStrategiesMu.Unlock()

	sm.evictionStrategies["lru"] = &models.LRUStrategy{}
	sm.evictionStrategies["lfu"] = &models.LFUStrategy{}
	sm.evictionStrategies["fifo"] = &models.FIFOStrategy{}
	sm.evictionStrategies["ttl"] = &models.TTLStrategy{}
	sm.evictionStrategies["random"] = &models.RandomStrategy{}
}

func (sm *StrategyManager) RegisterEvictionStrategy(strategy models.EvictionStrategy) error {
	if strategy == nil {
		return errors.New("strategy cannot be nil")
	}
	if strategy.Name() == "" {
		return errors.New("strategy name cannot be empty")
	}

	sm.evictionStrategiesMu.Lock()
	defer sm.evictionStrategiesMu.Unlock()

	sm.evictionStrategies[strategy.Name()] = strategy
	sm.logger.Infof("Registered eviction strategy: %s", strategy.Name())
	return nil
}

func (sm *StrategyManager) UnregisterEvictionStrategy(name string) {
	sm.evictionStrategiesMu.Lock()
	defer sm.evictionStrategiesMu.Unlock()

	delete(sm.evictionStrategies, name)
}

func (sm *StrategyManager) GetEvictionStrategy(name string) (models.EvictionStrategy, bool) {
	sm.evictionStrategiesMu.RLock()
	defer sm.evictionStrategiesMu.RUnlock()

	strategy, exists := sm.evictionStrategies[name]
	return strategy, exists
}

func (sm *StrategyManager) ListEvictionStrategies() []string {
	sm.evictionStrategiesMu.RLock()
	defer sm.evictionStrategiesMu.RUnlock()

	names := make([]string, 0, len(sm.evictionStrategies))
	for name := range sm.evictionStrategies {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func (sm *StrategyManager) SetDynamicJitterConfig(config models.DynamicJitterConfig) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.dynamicJitterConfig = config
}

func (sm *StrategyManager) GetDynamicJitterConfig() models.DynamicJitterConfig {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.dynamicJitterConfig
}

func (sm *StrategyManager) EnableDynamicJitter() {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.dynamicJitterConfig.Enabled = true
}

func (sm *StrategyManager) DisableDynamicJitter() {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.dynamicJitterConfig.Enabled = false
}

func (sm *StrategyManager) getCacheSize(cacheID string) int64 {
	cache, err := sm.cm.GetCache(cacheID)
	if err != nil {
		return 0
	}
	return cache.GetUsage()
}

func (sm *StrategyManager) calculateJitterRangeForCache(cacheID string) float64 {
	sm.mu.RLock()
	config := sm.dynamicJitterConfig
	sm.mu.RUnlock()

	if !config.Enabled {
		return config.BaseJitterRange
	}

	cacheSize := sm.getCacheSize(cacheID)
	baseRange := config.BaseJitterRange

	if cacheSize <= config.SmallCacheThreshold {
		smallRange := baseRange * config.SmallScaleFactor
		if smallRange < config.MinJitterRange {
			smallRange = config.MinJitterRange
		}
		return smallRange
	}

	if cacheSize >= config.LargeCacheThreshold {
		largeRange := baseRange * config.LargeScaleFactor
		if largeRange > config.MaxJitterRange {
			largeRange = config.MaxJitterRange
		}
		return largeRange
	}

	smallThresh := float64(config.SmallCacheThreshold)
	largeThresh := float64(config.LargeCacheThreshold)
	cacheSizeF := float64(cacheSize)

	progress := (cacheSizeF - smallThresh) / (largeThresh - smallThresh)
	if progress < 0 {
		progress = 0
	}
	if progress > 1 {
		progress = 1
	}

	minRange := baseRange * config.SmallScaleFactor
	maxRange := baseRange * config.LargeScaleFactor

	if minRange < config.MinJitterRange {
		minRange = config.MinJitterRange
	}
	if maxRange > config.MaxJitterRange {
		maxRange = config.MaxJitterRange
	}

	dynamicRange := minRange + progress*(maxRange-minRange)

	return dynamicRange
}

func (sm *StrategyManager) EnableTTLRandomization() {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.ttlRandomization.Enabled = true
}

func (sm *StrategyManager) DisableTTLRandomization() {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.ttlRandomization.Enabled = false
}

func (sm *StrategyManager) SetTTLRandomizationConfig(config TTLRandomizationConfig) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	if config.JitterRange > 0 {
		sm.ttlRandomization.JitterRange = config.JitterRange
	}
	if config.MaxOffset > 0 {
		sm.ttlRandomization.MaxOffset = config.MaxOffset
	}
	if config.MinOffset >= 0 {
		sm.ttlRandomization.MinOffset = config.MinOffset
	}
	sm.ttlRandomization.Enabled = config.Enabled
}

func (sm *StrategyManager) GetTTLRandomizationConfig() TTLRandomizationConfig {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.ttlRandomization
}

func (sm *StrategyManager) ApplyTTLJitter(baseTTL int) int {
	sm.mu.RLock()
	config := sm.ttlRandomization
	sm.mu.RUnlock()

	if !config.Enabled || baseTTL <= 0 {
		return baseTTL
	}

	sm.randMu.Lock()
	defer sm.randMu.Unlock()

	jitterRange := config.JitterRange
	if jitterRange > 1.0 {
		jitterRange = 1.0
	}

	jitter := float64(baseTTL) * jitterRange
	minOffset := int(-jitter)
	maxOffset := int(jitter)

	if config.MinOffset > minOffset {
		minOffset = config.MinOffset
	}
	if config.MaxOffset < maxOffset && config.MaxOffset > 0 {
		maxOffset = config.MaxOffset
	}

	offsetRange := maxOffset - minOffset
	if offsetRange <= 0 {
		return baseTTL
	}

	offset := sm.randSource.Intn(offsetRange) + minOffset
	result := baseTTL + offset

	if result < 1 {
		result = 1
	}

	return result
}

func (sm *StrategyManager) ApplyTTLJitterWithCacheSize(cacheID string, baseTTL int) int {
	if baseTTL <= 0 {
		return baseTTL
	}

	jitterRange := sm.calculateJitterRange(cacheID)

	sm.mu.RLock()
	config := sm.ttlRandomization
	enabled := sm.dynamicJitterConfig.Enabled || config.Enabled
	sm.mu.RUnlock()

	if !enabled {
		return baseTTL
	}

	sm.randMu.Lock()
	defer sm.randMu.Unlock()

	effectiveRange := jitterRange
	if jitterRange <= 0 {
		effectiveRange = config.JitterRange
	}

	if effectiveRange > 1.0 {
		effectiveRange = 1.0
	}

	jitter := float64(baseTTL) * effectiveRange
	minOffset := int(-jitter)
	maxOffset := int(jitter)

	if config.MinOffset > minOffset {
		minOffset = config.MinOffset
	}
	if config.MaxOffset < maxOffset && config.MaxOffset > 0 {
		maxOffset = config.MaxOffset
	}

	offsetRange := maxOffset - minOffset
	if offsetRange <= 0 {
		return baseTTL
	}

	offset := sm.randSource.Intn(offsetRange) + minOffset
	result := baseTTL + offset

	if result < 1 {
		result = 1
	}

	return result
}

func (sm *StrategyManager) GenerateBatchTTLs(baseTTL int, count int) []int {
	ttls := make([]int, count)
	for i := 0; i < count; i++ {
		ttls[i] = sm.ApplyTTLJitter(baseTTL)
	}
	return ttls
}

func (sm *StrategyManager) GenerateBatchTTLsForCache(cacheID string, baseTTL int, count int) []int {
	ttls := make([]int, count)
	for i := 0; i < count; i++ {
		ttls[i] = sm.ApplyTTLJitterWithCacheSize(cacheID, baseTTL)
	}
	return ttls
}

func (sm *StrategyManager) IsBatchExpiringTogether(ttls []int, thresholdPercent float64) bool {
	if len(ttls) < 2 {
		return false
	}

	minTTL := ttls[0]
	maxTTL := ttls[0]
	for _, ttl := range ttls {
		if ttl < minTTL {
			minTTL = ttl
		}
		if ttl > maxTTL {
			maxTTL = ttl
		}
	}

	if maxTTL == 0 {
		return true
	}

	spread := float64(maxTTL-minTTL) / float64(maxTTL)
	return spread < thresholdPercent
}

func (sm *StrategyManager) CalculateTTLSpread(ttls []int) float64 {
	if len(ttls) < 2 {
		return 0
	}

	minTTL := ttls[0]
	maxTTL := ttls[0]
	for _, ttl := range ttls {
		if ttl < minTTL {
			minTTL = ttl
		}
		if ttl > maxTTL {
			maxTTL = ttl
		}
	}

	if maxTTL == 0 {
		return 0
	}

	return float64(maxTTL-minTTL) / float64(maxTTL) * 100
}

func (sm *StrategyManager) SetPolicy(policy *models.CachePolicy) error {
	if policy.PolicyID == "" {
		return errors.New("policy_id is required")
	}
	if policy.CacheID == "" {
		return errors.New("cache_id is required")
	}

	_, err := sm.cm.GetInstance(policy.CacheID)
	if err != nil {
		return err
	}

	sm.mu.Lock()
	defer sm.mu.Unlock()

	policy.CreatedAt = time.Now()
	sm.policies[policy.CacheID] = policy

	if policy.DynamicJitterConfig.Enabled {
		sm.dynamicJitterConfig = policy.DynamicJitterConfig
	}

	sm.logger.Infof("Cache policy set for cache: %s", policy.CacheID)
	return nil
}

func (sm *StrategyManager) GetPolicy(cacheID string) (*models.CachePolicy, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	policy, exists := sm.policies[cacheID]
	if !exists {
		return nil, errors.New("policy not found for cache")
	}
	return policy, nil
}

func (sm *StrategyManager) RemovePolicy(cacheID string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	if _, exists := sm.policies[cacheID]; !exists {
		return errors.New("policy not found")
	}

	delete(sm.policies, cacheID)
	sm.logger.Infof("Cache policy removed for cache: %s", cacheID)
	return nil
}

func (sm *StrategyManager) GetTTL(cacheID, key string) int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	if policy, exists := sm.policies[cacheID]; exists {
		if ttl, ok := policy.TTLPolicy.TTLKeys[key]; ok {
			return ttl
		}
		if policy.TTLPolicy.DefaultTTL > 0 {
			return policy.TTLPolicy.DefaultTTL
		}
	}

	instance, err := sm.cm.GetInstance(cacheID)
	if err == nil {
		return instance.DefaultTTL
	}

	return 3600
}

func (sm *StrategyManager) ApplyEviction(cacheID string, cache *models.InMemoryCache) ([]string, error) {
	evictedKeys := make([]string, 0)

	usage := cache.GetUsage()
	capacity := cache.GetCapacity()

	if capacity <= 0 {
		return evictedKeys, nil
	}

	usagePercent := float64(usage) / float64(capacity) * 100

	sm.mu.RLock()
	policy, hasPolicy := sm.policies[cacheID]
	sm.mu.RUnlock()

	var threshold float64 = 80.0
	var evictionType string = "lru"

	if hasPolicy {
		threshold = policy.EvictionPolicy.EvictionThreshold
		evictionType = policy.EvictionPolicy.Type
	}

	if usagePercent < threshold {
		return evictedKeys, nil
	}

	targetUsage := int64(float64(capacity) * threshold / 100 * 0.9)
	needToFree := usage - targetUsage

	if needToFree <= 0 {
		return evictedKeys, nil
	}

	sm.logger.Infof("Applying eviction for cache %s, need to free: %d bytes", cacheID, needToFree)

	items := cache.GetAll()
	itemList := make([]*models.CacheData, 0, len(items))
	for _, item := range items {
		itemList = append(itemList, item)
	}

	evictionStrategy, strategyExists := sm.GetEvictionStrategy(evictionType)

	if strategyExists {
		evictionStrategy.Sort(itemList)
	} else {
		sm.logger.Warnf("Eviction strategy '%s' not found, using default LRU", evictionType)
		defaultStrategy := &models.LRUStrategy{}
		defaultStrategy.Sort(itemList)
	}

	freed := int64(0)
	for _, item := range itemList {
		if freed >= needToFree {
			break
		}
		if cache.Delete(item.Key) {
			evictedKeys = append(evictedKeys, item.Key)
			freed += int64(item.Size)
			cache.IncEvictCount()
			sm.logger.Debugf("Evicted key: %s, size: %d", item.Key, item.Size)
		}
	}

	sm.logger.Infof("Evicted %d keys, freed %d bytes", len(evictedKeys), freed)
	return evictedKeys, nil
}

func (sm *StrategyManager) GetEvictionStats(cacheID string) (map[string]interface{}, error) {
	sm.mu.RLock()
	policy, exists := sm.policies[cacheID]
	sm.mu.RUnlock()

	if !exists {
		return nil, errors.New("policy not found")
	}

	cache, err := sm.cm.GetCache(cacheID)
	if err != nil {
		return nil, err
	}

	usage := cache.GetUsage()
	capacity := cache.GetCapacity()
	usagePercent := float64(usage) / float64(capacity) * 100

	stats := map[string]interface{}{
		"eviction_type":       policy.EvictionPolicy.Type,
		"eviction_threshold": policy.EvictionPolicy.EvictionThreshold,
		"current_usage":      usage,
		"capacity":           capacity,
		"usage_percent":      usagePercent,
		"evict_count":        cache.GetEvictCount(),
		"strategies_available": sm.ListEvictionStrategies(),
	}

	return stats, nil
}

func (sm *StrategyManager) GetDynamicJitterStats(cacheID string) map[string]interface{} {
	config := sm.GetDynamicJitterConfig()
	cacheSize := sm.getCacheSize(cacheID)
	jitterRange := sm.calculateJitterRangeForCache(cacheID)

	return map[string]interface{}{
		"enabled":              config.Enabled,
		"base_jitter_range":    config.BaseJitterRange,
		"current_jitter_range": jitterRange,
		"cache_size":          cacheSize,
		"small_threshold":     config.SmallCacheThreshold,
		"large_threshold":     config.LargeCacheThreshold,
		"small_scale_factor":  config.SmallScaleFactor,
		"large_scale_factor":  config.LargeScaleFactor,
		"min_jitter_range":   config.MinJitterRange,
		"max_jitter_range":   config.MaxJitterRange,
	}
}

func (sm *StrategyManager) CalculateOptimalJitterForCache(cacheID string, baseTTL int) (int, float64) {
	jitterRange := sm.calculateJitterRangeForCache(cacheID)
	actualTTL := sm.ApplyTTLJitterWithCacheSize(cacheID, baseTTL)
	return actualTTL, jitterRange
}

func (sm *StrategyManager) SimulateAvalancheRisk(cacheID string, baseTTL int, itemCount int) float64 {
	ttls := sm.GenerateBatchTTLsForCache(cacheID, baseTTL, itemCount)

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

	if itemCount == 0 {
		return 0
	}

	bucketCount := int(math.Ceil(float64(baseTTL) / 30.0))
	if bucketCount == 0 {
		bucketCount = 1
	}

	expectedAvg := itemCount / bucketCount
	if expectedAvg == 0 {
		expectedAvg = 1
	}

	ratio := float64(maxInBucket) / float64(expectedAvg)
	return ratio
}
