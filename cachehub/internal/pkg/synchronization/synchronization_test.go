package synchronization

import (
	"testing"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/testfixtures"
	"github.com/sirupsen/logrus"
)

func setupSyncTestEnvironment(t *testing.T) (*cache_manager.CacheManager, *SyncManager, *testfixtures.TestDataBuilder) {
	logger := logrus.New()
	logger.SetLevel(logrus.WarnLevel)

	cm := cache_manager.NewCacheManager(logger)
	sm := NewSyncManager(cm, logger)
	builder := testfixtures.NewTestDataBuilder()

	sourceInstance := builder.BuildCacheInstanceWithID("source_cache")
	err := cm.RegisterInstance(sourceInstance)
	if err != nil {
		t.Fatalf("Failed to register source cache instance: %v", err)
	}

	targetInstance1 := builder.BuildCacheInstanceWithID("target_cache_1")
	err = cm.RegisterInstance(targetInstance1)
	if err != nil {
		t.Fatalf("Failed to register target cache 1 instance: %v", err)
	}

	targetInstance2 := builder.BuildCacheInstanceWithID("target_cache_2")
	err = cm.RegisterInstance(targetInstance2)
	if err != nil {
		t.Fatalf("Failed to register target cache 2 instance: %v", err)
	}

	return cm, sm, builder
}

func TestSyncConfigRegistration(t *testing.T) {
	_, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1", "target_cache_2"},
		SyncMode:       "async",
		Enabled:        true,
		RetryMax:       3,
		RetryDelay:     1 * time.Second,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	retrievedConfig, err := sm.GetSyncConfig("source_cache")
	if err != nil {
		t.Fatalf("GetSyncConfig failed: %v", err)
	}

	if retrievedConfig.SourceCacheID != "source_cache" {
		t.Errorf("Expected source cache ID 'source_cache', got '%s'", retrievedConfig.SourceCacheID)
	}

	if len(retrievedConfig.TargetCacheIDs) != 2 {
		t.Errorf("Expected 2 target cache IDs, got %d", len(retrievedConfig.TargetCacheIDs))
	}
}

func TestSyncConfigRemoval(t *testing.T) {
	_, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	err = sm.RemoveSyncConfig("source_cache")
	if err != nil {
		t.Fatalf("RemoveSyncConfig failed: %v", err)
	}

	_, err = sm.GetSyncConfig("source_cache")
	if err == nil {
		t.Error("Expected error when getting removed config")
	}
}

func TestSyncConfigValidation(t *testing.T) {
	_, sm, _ := setupSyncTestEnvironment(t)

	invalidConfig1 := &SyncConfig{
		SourceCacheID:  "",
		TargetCacheIDs: []string{"target_cache_1"},
	}

	err := sm.RegisterSyncConfig(invalidConfig1)
	if err == nil {
		t.Error("Expected error for config with empty source cache ID")
	}

	invalidConfig2 := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{},
	}

	err = sm.RegisterSyncConfig(invalidConfig2)
	if err == nil {
		t.Error("Expected error for config with no target caches")
	}
}

func TestAsyncSyncQueuing(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1", "target_cache_2"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	startTime := time.Now()

	for i := 0; i < 10; i++ {
		key := "async_key_" + string(rune('a'+i))
		value := map[string]interface{}{"index": i}
		sm.SyncSet("source_cache", key, value, 3600)
	}

	queueTime := time.Since(startTime)
	if queueTime > 100*time.Millisecond {
		t.Errorf("Async queuing took too long: %v", queueTime)
	}

	targetCache1, _ := cm.GetCache("target_cache_1")
	keysBefore := targetCache1.GetKeys()
	if len(keysBefore) != 0 {
		t.Error("Expected no keys in target before processing")
	}

	sm.ProcessPendingOps()

	targetCache1, _ = cm.GetCache("target_cache_1")
	keysAfter := targetCache1.GetKeys()
	if len(keysAfter) != 10 {
		t.Errorf("Expected 10 keys in target after processing, got %d", len(keysAfter))
	}

	targetCache2, _ := cm.GetCache("target_cache_2")
	keysTarget2 := targetCache2.GetKeys()
	if len(keysTarget2) != 10 {
		t.Errorf("Expected 10 keys in target 2, got %d", len(keysTarget2))
	}
}

func TestRealtimeSync(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "realtime",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	testKey := "realtime_test_key"
	testValue := "test_value"

	startTime := time.Now()
	sm.SyncSet("source_cache", testKey, testValue, 3600)
	syncTime := time.Since(startTime)

	targetCache, _ := cm.GetCache("target_cache_1")
	_, found := targetCache.Get(testKey)
	if !found {
		t.Error("Expected key to be synced immediately in realtime mode")
	}

	if syncTime > 50*time.Millisecond {
		t.Logf("Realtime sync took: %v", syncTime)
	}
}

func TestSyncDeleteOperation(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	testKey := "delete_test_key"

	targetCache, _ := cm.GetCache("target_cache_1")
	targetCache.Set(testKey, "existing_value", 3600)

	sm.SyncDelete("source_cache", testKey)
	sm.ProcessPendingOps()

	_, found := targetCache.Get(testKey)
	if found {
		t.Error("Expected key to be deleted from target")
	}
}

func TestSyncDisabledConfig(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "async",
		Enabled:        false,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	sm.SyncSet("source_cache", "disabled_key", "value", 3600)
	sm.ProcessPendingOps()

	targetCache, _ := cm.GetCache("target_cache_1")
	_, found := targetCache.Get("disabled_key")
	if found {
		t.Error("Expected no sync when config is disabled")
	}
}

func TestSyncStatsTracking(t *testing.T) {
	_, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1", "target_cache_2"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	for i := 0; i < 5; i++ {
		key := "stat_key_" + string(rune('a'+i))
		sm.SyncSet("source_cache", key, "value", 3600)
	}

	sm.ProcessPendingOps()

	stats := sm.GetStats("source_cache")

	if stats.TotalOps != 5 {
		t.Errorf("Expected 5 total ops, got %d", stats.TotalOps)
	}

	if stats.SuccessOps != 10 {
		t.Errorf("Expected 10 success ops (5 keys * 2 targets), got %d", stats.SuccessOps)
	}

	if stats.FailedOps != 0 {
		t.Errorf("Expected 0 failed ops, got %d", stats.FailedOps)
	}

	sm.ResetStats("source_cache")
	resetStats := sm.GetStats("source_cache")

	if resetStats.TotalOps != 0 {
		t.Errorf("Expected 0 total ops after reset, got %d", resetStats.TotalOps)
	}
}

func TestSyncConsistencyCheck(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1", "target_cache_2"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	for i := 0; i < 3; i++ {
		key := "consist_key_" + string(rune('a'+i))
		sm.SyncSet("source_cache", key, "value", 3600)
	}

	sm.ProcessPendingOps()

	consistency, err := sm.CheckConsistency("source_cache")
	if err != nil {
		t.Fatalf("CheckConsistency failed: %v", err)
	}

	if !consistency["target_cache_1"] {
		t.Error("Expected target_cache_1 to be consistent")
	}

	if !consistency["target_cache_2"] {
		t.Error("Expected target_cache_2 to be consistent")
	}

	targetCache2, _ := cm.GetCache("target_cache_2")
	targetCache2.Set("extra_key", "value", 3600)

	consistency, _ = sm.CheckConsistency("source_cache")
	if consistency["target_cache_2"] {
		t.Error("Expected target_cache_2 to be inconsistent after adding extra key")
	}
}

func TestFullSync(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	sourceCache, _ := cm.GetCache("source_cache")
	for i := 0; i < 20; i++ {
		key := "fullsync_key_" + string(rune('a'+i%26)) + string(rune('a'+(i+1)%26))
		sourceCache.Set(key, map[string]interface{}{"index": i}, 3600)
	}

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	count, err := sm.FullSync("source_cache")
	if err != nil {
		t.Fatalf("FullSync failed: %v", err)
	}

	if count != 20 {
		t.Errorf("Expected to sync 20 items, got %d", count)
	}

	targetCache, _ := cm.GetCache("target_cache_1")
	keys := targetCache.GetKeys()
	if len(keys) != 20 {
		t.Errorf("Expected 20 keys in target after full sync, got %d", len(keys))
	}
}

func TestMultipleTargetsSync(t *testing.T) {
	cm, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1", "target_cache_2"},
		SyncMode:       "async",
		Enabled:        true,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	testKey := "multi_target_key"
	sm.SyncSet("source_cache", testKey, "test_value", 3600)
	sm.ProcessPendingOps()

	target1, _ := cm.GetCache("target_cache_1")
	target2, _ := cm.GetCache("target_cache_2")

	_, found1 := target1.Get(testKey)
	_, found2 := target2.Get(testKey)

	if !found1 {
		t.Error("Expected key in target_cache_1")
	}
	if !found2 {
		t.Error("Expected key in target_cache_2")
	}
}

func TestRetryQueueManagement(t *testing.T) {
	_, sm, _ := setupSyncTestEnvironment(t)

	config := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "async",
		Enabled:        true,
		RetryMax:       3,
		RetryDelay:     100 * time.Millisecond,
	}

	err := sm.RegisterSyncConfig(config)
	if err != nil {
		t.Fatalf("RegisterSyncConfig failed: %v", err)
	}

	initialSize := sm.GetRetryQueueSize("source_cache")
	if initialSize != 0 {
		t.Errorf("Expected empty retry queue initially, got size %d", initialSize)
	}

	sm.SyncSet("source_cache", "retry_test_key", "value", 3600)
	sm.ProcessPendingOps()

	retrySize := sm.GetRetryQueueSize("source_cache")
	if retrySize != 0 {
		t.Logf("Retry queue size after first processing: %d", retrySize)
	}

	sm.ClearRetryQueue("source_cache")
	finalSize := sm.GetRetryQueueSize("source_cache")
	if finalSize != 0 {
		t.Errorf("Expected empty retry queue after clear, got size %d", finalSize)
	}
}

func TestAllStats(t *testing.T) {
	_, sm, _ := setupSyncTestEnvironment(t)

	config1 := &SyncConfig{
		SourceCacheID:  "source_cache",
		TargetCacheIDs: []string{"target_cache_1"},
		SyncMode:       "async",
		Enabled:        true,
	}

	sm.RegisterSyncConfig(config1)

	for i := 0; i < 5; i++ {
		sm.SyncSet("source_cache", "key_"+string(rune('a'+i)), "value", 3600)
	}
	sm.ProcessPendingOps()

	allStats := sm.GetAllStats()

	if len(allStats) != 1 {
		t.Errorf("Expected stats for 1 source, got %d", len(allStats))
	}

	stats := allStats["source_cache"]
	if stats.TotalOps != 5 {
		t.Errorf("Expected 5 total ops, got %d", stats.TotalOps)
	}
	if stats.SuccessOps != 5 {
		t.Errorf("Expected 5 success ops, got %d", stats.SuccessOps)
	}
}

func TestSyncManagerWithWorkers(t *testing.T) {
	logger := logrus.New()
	cm := cache_manager.NewCacheManager(logger)

	sm := NewSyncManagerWithWorkers(cm, logger, 5)

	if sm.workerCount != 5 {
		t.Errorf("Expected 5 workers, got %d", sm.workerCount)
	}

	sm2 := NewSyncManagerWithWorkers(cm, logger, 0)
	if sm2.workerCount != 1 {
		t.Errorf("Expected default 1 worker for invalid count, got %d", sm2.workerCount)
	}
}
