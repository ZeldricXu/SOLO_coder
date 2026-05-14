package cache_readwrite

import (
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/cachehub/internal/pkg/strategy"
	"github.com/sirupsen/logrus"
)

type NullValueMarker struct {
	IsNull   bool      `json:"is_null"`
	MarkedAt time.Time `json:"marked_at"`
}

type CacheReadWrite struct {
	cm              *cache_manager.CacheManager
	strategyM       *strategy.StrategyManager
	logger          *logrus.Logger
	mu              sync.RWMutex
	nullCacheConfig models.NullCacheConfig
}

func NewCacheReadWrite(cm *cache_manager.CacheManager, strategyM *strategy.StrategyManager, logger *logrus.Logger) *CacheReadWrite {
	return &CacheReadWrite{
		cm:              cm,
		strategyM:       strategyM,
		logger:          logger,
		nullCacheConfig: *models.NewNullCacheConfig(),
	}
}

func NewCacheReadWriteWithOptions(cm *cache_manager.CacheManager, strategyM *strategy.StrategyManager, logger *logrus.Logger, nullCacheEnabled bool, nullCacheTTL int) *CacheReadWrite {
	config := models.NewNullCacheConfig()
	config.Enabled = nullCacheEnabled
	if nullCacheTTL > 0 {
		config.DefaultTTL = nullCacheTTL
	}
	return &CacheReadWrite{
		cm:              cm,
		strategyM:       strategyM,
		logger:          logger,
		nullCacheConfig: *config,
	}
}

func NewCacheReadWriteWithConfig(cm *cache_manager.CacheManager, strategyM *strategy.StrategyManager, logger *logrus.Logger, config models.NullCacheConfig) *CacheReadWrite {
	return &CacheReadWrite{
		cm:              cm,
		strategyM:       strategyM,
		logger:          logger,
		nullCacheConfig: config,
	}
}

func (rw *CacheReadWrite) EnableNullCache() {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	rw.nullCacheConfig.Enabled = true
}

func (rw *CacheReadWrite) DisableNullCache() {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	rw.nullCacheConfig.Enabled = false
}

func (rw *CacheReadWrite) SetNullCacheTTL(ttl int) {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	if ttl > 0 {
		rw.nullCacheConfig.DefaultTTL = ttl
	}
}

func (rw *CacheReadWrite) IsNullCacheEnabled() bool {
	rw.mu.RLock()
	defer rw.mu.RUnlock()
	return rw.nullCacheConfig.Enabled
}

func (rw *CacheReadWrite) GetNullCacheTTL() int {
	rw.mu.RLock()
	defer rw.mu.RUnlock()
	return rw.nullCacheConfig.DefaultTTL
}

func (rw *CacheReadWrite) SetNullCacheConfig(config models.NullCacheConfig) {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	rw.nullCacheConfig = config
}

func (rw *CacheReadWrite) GetNullCacheConfig() models.NullCacheConfig {
	rw.mu.RLock()
	defer rw.mu.RUnlock()
	return rw.nullCacheConfig
}

func (rw *CacheReadWrite) SetQueryTypeTTL(queryType models.QueryType, ttl int) {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	if rw.nullCacheConfig.QueryTypeTTLs == nil {
		rw.nullCacheConfig.QueryTypeTTLs = make(map[models.QueryType]int)
	}
	rw.nullCacheConfig.QueryTypeTTLs[queryType] = ttl
}

func (rw *CacheReadWrite) GetQueryTypeTTL(queryType models.QueryType) int {
	rw.mu.RLock()
	defer rw.mu.RUnlock()
	if ttl, ok := rw.nullCacheConfig.QueryTypeTTLs[queryType]; ok {
		return ttl
	}
	return rw.nullCacheConfig.DefaultTTL
}

func (rw *CacheReadWrite) AddQueryTypePattern(pattern string, queryType models.QueryType) {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	rw.nullCacheConfig.QueryTypePattern[pattern] = queryType
}

func (rw *CacheReadWrite) RemoveQueryTypePattern(pattern string) {
	rw.mu.Lock()
	defer rw.mu.Unlock()
	delete(rw.nullCacheConfig.QueryTypePattern, pattern)
}

func (rw *CacheReadWrite) DetectQueryType(key string) models.QueryType {
	rw.mu.RLock()
	defer rw.mu.RUnlock()

	for pattern, qt := range rw.nullCacheConfig.QueryTypePattern {
		if matchesKeyPattern(key, pattern) {
			return qt
		}
	}

	if strings.HasPrefix(key, "realtime:") || strings.HasPrefix(key, "rt:") {
		return models.QueryTypeRealtime
	}
	if strings.HasPrefix(key, "session:") || strings.HasPrefix(key, "user:") {
		return models.QueryTypeUserSession
	}
	if strings.HasPrefix(key, "config:") || strings.HasPrefix(key, "static:") {
		return models.QueryTypeStaticData
	}
	if strings.HasPrefix(key, "report:") {
		return models.QueryTypeReport
	}

	return models.QueryTypeDefault
}

func (rw *CacheReadWrite) GetNullTTLForKey(key string) int {
	queryType := rw.DetectQueryType(key)
	return rw.GetQueryTypeTTL(queryType)
}

func matchesKeyPattern(key, pattern string) bool {
	if pattern == "*" {
		return true
	}
	if len(pattern) == 0 {
		return false
	}

	if pattern[len(pattern)-1] == '*' {
		prefix := pattern[:len(pattern)-1]
		return len(key) >= len(prefix) && strings.HasPrefix(key, prefix)
	}

	if pattern[0] == '*' {
		suffix := pattern[1:]
		return len(key) >= len(suffix) && strings.HasSuffix(key, suffix)
	}

	return key == pattern
}

func (rw *CacheReadWrite) MarkNull(cacheID, key string) error {
	rw.mu.RLock()
	enabled := rw.nullCacheConfig.Enabled
	rw.mu.RUnlock()

	if !enabled {
		return nil
	}

	ttl := rw.GetNullTTLForKey(key)

	nullMarker := &NullValueMarker{
		IsNull:   true,
		MarkedAt: time.Now(),
	}

	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return err
	}

	nullKey := rw.buildNullKey(key)
	cache.Set(nullKey, nullMarker, ttl)
	rw.logger.Debugf("Marked null value for key: %s (cache_id: %s, ttl: %ds, query_type: %s)", 
		key, cacheID, ttl, rw.DetectQueryType(key))
	return nil
}

func (rw *CacheReadWrite) MarkNullWithQueryType(cacheID, key string, queryType models.QueryType) error {
	rw.mu.RLock()
	enabled := rw.nullCacheConfig.Enabled
	rw.mu.RUnlock()

	if !enabled {
		return nil
	}

	ttl := rw.GetQueryTypeTTL(queryType)

	nullMarker := &NullValueMarker{
		IsNull:   true,
		MarkedAt: time.Now(),
	}

	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return err
	}

	nullKey := rw.buildNullKey(key)
	cache.Set(nullKey, nullMarker, ttl)
	rw.logger.Debugf("Marked null value for key: %s (cache_id: %s, ttl: %ds, query_type: %s)", 
		key, cacheID, ttl, queryType)
	return nil
}

func (rw *CacheReadWrite) IsNullMarked(cacheID, key string) (bool, error) {
	rw.mu.RLock()
	enabled := rw.nullCacheConfig.Enabled
	rw.mu.RUnlock()

	if !enabled {
		return false, nil
	}

	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return false, err
	}

	nullKey := rw.buildNullKey(key)
	data, found := cache.Get(nullKey)
	if !found {
		return false, nil
	}

	if marker, ok := data.Value.(*NullValueMarker); ok {
		return marker.IsNull, nil
	}

	return false, nil
}

func (rw *CacheReadWrite) ClearNullMark(cacheID, key string) error {
	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return err
	}

	nullKey := rw.buildNullKey(key)
	cache.Delete(nullKey)
	return nil
}

func (rw *CacheReadWrite) buildNullKey(key string) string {
	return "__null__:" + key
}

func (rw *CacheReadWrite) IsNullMarker(value interface{}) bool {
	if marker, ok := value.(*NullValueMarker); ok {
		return marker.IsNull
	}
	return false
}

func (rw *CacheReadWrite) Get(cacheID, key string) (interface{}, bool, error) {
	instance, err := rw.cm.GetInstance(cacheID)
	if err != nil {
		return nil, false, err
	}

	if instance.Status != "online" {
		return nil, false, errors.New("cache instance is not online")
	}

	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return nil, false, err
	}

	data, found := cache.Get(key)
	if !found {
		rw.logger.Debugf("Cache miss for key: %s", key)
		return nil, false, nil
	}

	rw.logger.Debugf("Cache hit for key: %s", key)
	return data.Value, true, nil
}

func (rw *CacheReadWrite) Set(cacheID, key string, value interface{}, ttl int) error {
	instance, err := rw.cm.GetInstance(cacheID)
	if err != nil {
		return err
	}

	if instance.Status != "online" {
		return errors.New("cache instance is not online")
	}

	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return err
	}

	if ttl <= 0 {
		ttl = rw.strategyM.GetTTL(cacheID, key)
	}

	ttl = rw.strategyM.ApplyTTLJitterWithCacheSize(cacheID, ttl)

	cache.Set(key, value, ttl)

	rw.logger.Debugf("Cache set for key: %s, ttl: %d", key, ttl)

	_, err = rw.strategyM.ApplyEviction(cacheID, cache)
	if err != nil {
		rw.logger.Warnf("Eviction error: %v", err)
	}

	return nil
}

func (rw *CacheReadWrite) Delete(cacheID, key string) (bool, error) {
	instance, err := rw.cm.GetInstance(cacheID)
	if err != nil {
		return false, err
	}

	if instance.Status != "online" {
		return false, errors.New("cache instance is not online")
	}

	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return false, err
	}

	deleted := cache.Delete(key)
	rw.logger.Debugf("Cache delete for key: %s, deleted: %v", key, deleted)
	return deleted, nil
}

func (rw *CacheReadWrite) MGet(cacheID string, keys []string) (map[string]interface{}, error) {
	result := make(map[string]interface{})

	for _, key := range keys {
		value, found, err := rw.Get(cacheID, key)
		if err != nil {
			continue
		}
		if found {
			result[key] = value
		}
	}

	return result, nil
}

func (rw *CacheReadWrite) MSet(cacheID string, items map[string]interface{}) error {
	for key, value := range items {
		err := rw.Set(cacheID, key, value, 0)
		if err != nil {
			rw.logger.Warnf("MSet error for key %s: %v", key, err)
		}
	}
	return nil
}

func (rw *CacheReadWrite) Exists(cacheID, key string) (bool, error) {
	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return false, err
	}

	_, found := cache.Get(key)
	return found, nil
}

func (rw *CacheReadWrite) Keys(cacheID string) ([]string, error) {
	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return nil, err
	}
	return cache.GetKeys(), nil
}

func (rw *CacheReadWrite) Flush(cacheID string) (int, error) {
	cache, err := rw.cm.GetCache(cacheID)
	if err != nil {
		return 0, err
	}

	keys := cache.GetKeys()
	count := 0
	for _, key := range keys {
		if cache.Delete(key) {
			count++
		}
	}

	rw.logger.Infof("Flushed cache %s, deleted %d keys", cacheID, count)
	return count, nil
}

func (rw *CacheReadWrite) GetOrLoad(cacheID, key string, loader func() (interface{}, error)) (interface{}, error) {
	value, found, err := rw.Get(cacheID, key)
	if err != nil {
		return nil, err
	}

	if found {
		return value, nil
	}

	queryType := rw.DetectQueryType(key)

	loadedValue, err := loader()
	if err != nil {
		rw.logger.Debugf("Loader returned error for key: %s", key)
		markErr := rw.MarkNullWithQueryType(cacheID, key, queryType)
		if markErr != nil {
			rw.logger.Warnf("Failed to mark null for key: %s, err: %v", key, markErr)
		}
		return nil, err
	}

	ttl := rw.strategyM.GetTTL(cacheID, key)
	ttl = rw.strategyM.ApplyTTLJitterWithCacheSize(cacheID, ttl)
	rw.Set(cacheID, key, loadedValue, ttl)

	return loadedValue, nil
}
