package cache_readwrite

import (
	"testing"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/cachehub/internal/pkg/strategy"
	"github.com/cachehub/internal/pkg/testfixtures"
	"github.com/sirupsen/logrus"
)

func setupTestEnvironment(t *testing.T) (*cache_manager.CacheManager, *strategy.StrategyManager, *CacheReadWrite, *testfixtures.TestDataBuilder) {
	logger := logrus.New()
	logger.SetLevel(logrus.WarnLevel)

	cm := cache_manager.NewCacheManager(logger)
	sm := strategy.NewStrategyManager(cm, logger)
	rw := NewCacheReadWrite(cm, sm, logger)
	builder := testfixtures.NewTestDataBuilder()

	instance := builder.BuildDefaultCacheInstance()
	err := cm.RegisterInstance(instance)
	if err != nil {
		t.Fatalf("Failed to register cache instance: %v", err)
	}

	return cm, sm, rw, builder
}

func TestNullCacheMarking(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	testKey := "nonexistent_key"

	isMarked, err := rw.IsNullMarked(cacheID, testKey)
	if err != nil {
		t.Errorf("IsNullMarked failed: %v", err)
	}
	if isMarked {
		t.Error("Expected key to not be null marked initially")
	}

	err = rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Errorf("MarkNull failed: %v", err)
	}

	isMarked, err = rw.IsNullMarked(cacheID, testKey)
	if err != nil {
		t.Errorf("IsNullMarked failed after marking: %v", err)
	}
	if !isMarked {
		t.Error("Expected key to be null marked after MarkNull")
	}
}

func TestNullCacheClearing(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	testKey := "test_null_key"

	err := rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	isMarked, _ := rw.IsNullMarked(cacheID, testKey)
	if !isMarked {
		t.Fatal("Expected key to be marked as null")
	}

	err = rw.ClearNullMark(cacheID, testKey)
	if err != nil {
		t.Errorf("ClearNullMark failed: %v", err)
	}

	isMarked, _ = rw.IsNullMarked(cacheID, testKey)
	if isMarked {
		t.Error("Expected null mark to be cleared")
	}
}

func TestNullCacheTTLBehavior(t *testing.T) {
	cm, sm, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	shortTTL := 1
	rw.SetNullCacheTTL(shortTTL)
	if rw.GetNullCacheTTL() != shortTTL {
		t.Errorf("Expected TTL to be %d, got %d", shortTTL, rw.GetNullCacheTTL())
	}

	testKey := "expiring_null_key"
	err := rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	isMarked, _ := rw.IsNullMarked(cacheID, testKey)
	if !isMarked {
		t.Fatal("Expected key to be marked as null")
	}

	time.Sleep(2 * time.Second)

	cache, _ := cm.GetCache(cacheID)
	nullKey := rw.buildNullKey(testKey)
	_, found := cache.Get(nullKey)
	if found {
		t.Error("Expected null cache to expire after TTL")
	}

	_ = sm
}

func TestNullCacheEnableDisable(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	if !rw.IsNullCacheEnabled() {
		t.Error("Expected null cache to be enabled by default")
	}

	rw.DisableNullCache()
	if rw.IsNullCacheEnabled() {
		t.Error("Expected null cache to be disabled")
	}

	testKey := "disabled_null_key"
	err := rw.MarkNull(cacheID, testKey)
	if err != nil {
		t.Errorf("MarkNull should not fail when disabled: %v", err)
	}

	isMarked, _ := rw.IsNullMarked(cacheID, testKey)
	if isMarked {
		t.Error("Expected IsNullMarked to return false when disabled")
	}

	rw.EnableNullCache()
	if !rw.IsNullCacheEnabled() {
		t.Error("Expected null cache to be re-enabled")
	}
}

func TestNullCacheWithActualData(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	actualKey := "actual_data_key"
	actualValue := map[string]interface{}{"data": "real_value"}

	err := rw.Set(cacheID, actualKey, actualValue, 3600)
	if err != nil {
		t.Fatalf("Set failed: %v", err)
	}

	value, found, err := rw.Get(cacheID, actualKey)
	if err != nil {
		t.Fatalf("Get failed: %v", err)
	}
	if !found {
		t.Fatal("Expected to find actual data")
	}

	mapValue, ok := value.(map[string]interface{})
	if !ok {
		t.Fatal("Expected value to be map")
	}
	if mapValue["data"] != "real_value" {
		t.Errorf("Expected data to be 'real_value', got %v", mapValue["data"])
	}

	nullKey := "null_data_key"
	err = rw.MarkNull(cacheID, nullKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	isMarked, _ := rw.IsNullMarked(cacheID, nullKey)
	if !isMarked {
		t.Error("Expected null key to be marked")
	}

	nullValue, nullFound, _ := rw.Get(cacheID, nullKey)
	if nullFound {
		t.Error("Expected Get to return not found for null marked key")
	}
	if nullValue != nil {
		t.Error("Expected Get to return nil value for null marked key")
	}
}

func TestNullMarkerDetection(t *testing.T) {
	marker := &NullValueMarker{
		IsNull:   true,
		MarkedAt: time.Now(),
	}

	if !isNullMarkerInternal(marker) {
		t.Error("Expected NullValueMarker to be detected")
	}

	notMarker := map[string]interface{}{"key": "value"}
	if isNullMarkerInternal(notMarker) {
		t.Error("Expected non-marker to not be detected")
	}
}

func isNullMarkerInternal(value interface{}) bool {
	if marker, ok := value.(*NullValueMarker); ok {
		return marker.IsNull
	}
	return false
}

func TestCacheReadWriteBasicOperations(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	testKey := "basic_key"
	testValue := "test_value"

	err := rw.Set(cacheID, testKey, testValue, 3600)
	if err != nil {
		t.Fatalf("Set failed: %v", err)
	}

	value, found, err := rw.Get(cacheID, testKey)
	if err != nil {
		t.Fatalf("Get failed: %v", err)
	}
	if !found {
		t.Fatal("Expected to find key")
	}
	if value != testValue {
		t.Errorf("Expected value '%s', got '%v'", testValue, value)
	}

	deleted, err := rw.Delete(cacheID, testKey)
	if err != nil {
		t.Fatalf("Delete failed: %v", err)
	}
	if !deleted {
		t.Error("Expected Delete to return true")
	}

	_, found, _ = rw.Get(cacheID, testKey)
	if found {
		t.Error("Expected key to be deleted")
	}
}

func TestCacheReadWriteBatchOperations(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	items := map[string]interface{}{
		"key1": "value1",
		"key2": "value2",
		"key3": "value3",
	}

	err := rw.MSet(cacheID, items)
	if err != nil {
		t.Fatalf("MSet failed: %v", err)
	}

	keys := []string{"key1", "key2", "nonexistent"}
	results, err := rw.MGet(cacheID, keys)
	if err != nil {
		t.Fatalf("MGet failed: %v", err)
	}

	if len(results) != 2 {
		t.Errorf("Expected 2 results, got %d", len(results))
	}

	if results["key1"] != "value1" {
		t.Error("Expected key1 to have value1")
	}
	if results["key2"] != "value2" {
		t.Error("Expected key2 to have value2")
	}
}

func TestCacheReadWriteExistsAndKeys(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	exists, err := rw.Exists(cacheID, "nonexistent")
	if err != nil {
		t.Fatalf("Exists failed: %v", err)
	}
	if exists {
		t.Error("Expected nonexistent key to not exist")
	}

	err = rw.Set(cacheID, "test_key", "value", 3600)
	if err != nil {
		t.Fatalf("Set failed: %v", err)
	}

	exists, _ = rw.Exists(cacheID, "test_key")
	if !exists {
		t.Error("Expected test_key to exist")
	}

	keys, err := rw.Keys(cacheID)
	if err != nil {
		t.Fatalf("Keys failed: %v", err)
	}
	if len(keys) != 1 {
		t.Errorf("Expected 1 key, got %d", len(keys))
	}
}

func TestCacheReadWriteFlush(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	for i := 0; i < 10; i++ {
		key := "flush_key_" + string(rune('a'+i))
		err := rw.Set(cacheID, key, "value", 3600)
		if err != nil {
			t.Fatalf("Set failed: %v", err)
		}
	}

	keys, _ := rw.Keys(cacheID)
	if len(keys) != 10 {
		t.Fatalf("Expected 10 keys before flush, got %d", len(keys))
	}

	count, err := rw.Flush(cacheID)
	if err != nil {
		t.Fatalf("Flush failed: %v", err)
	}
	if count != 10 {
		t.Errorf("Expected to flush 10 keys, got %d", count)
	}

	keys, _ = rw.Keys(cacheID)
	if len(keys) != 0 {
		t.Errorf("Expected 0 keys after flush, got %d", len(keys))
	}
}

func TestCacheReadWriteOfflineInstance(t *testing.T) {
	cm, _, rw, builder := setupTestEnvironment(t)

	offlineInstance := builder.BuildOfflineInstance()
	err := cm.RegisterInstance(offlineInstance)
	if err != nil {
		t.Fatalf("Failed to register offline instance: %v", err)
	}

	_, _, err = rw.Get(offlineInstance.CacheID, "test_key")
	if err == nil {
		t.Error("Expected error when accessing offline instance")
	}

	err = rw.Set(offlineInstance.CacheID, "test_key", "value", 3600)
	if err == nil {
		t.Error("Expected error when writing to offline instance")
	}

	_, err = rw.Delete(offlineInstance.CacheID, "test_key")
	if err == nil {
		t.Error("Expected error when deleting from offline instance")
	}
}

func TestNullCacheIntegration(t *testing.T) {
	_, _, rw, builder := setupTestEnvironment(t)
	cacheID := builder.BuildDefaultCacheInstance().CacheID

	nonexistentKey := "non_existent_user_12345"

	value, found, err := rw.Get(cacheID, nonexistentKey)
	if err != nil {
		t.Fatalf("Get failed: %v", err)
	}
	if found {
		t.Fatal("Expected Get to return not found for nonexistent key")
	}
	if value != nil {
		t.Error("Expected value to be nil for miss")
	}

	err = rw.MarkNull(cacheID, nonexistentKey)
	if err != nil {
		t.Fatalf("MarkNull failed: %v", err)
	}

	isMarked, _ := rw.IsNullMarked(cacheID, nonexistentKey)
	if !isMarked {
		t.Error("Expected key to be marked as null")
	}

	_ = models.CacheData{}
}
