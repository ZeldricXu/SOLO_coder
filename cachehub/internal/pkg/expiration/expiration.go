package expiration

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/sirupsen/logrus"
)

type ExpirationManager struct {
	cm            *cache_manager.CacheManager
	logger        *logrus.Logger
	expireRecords map[string][]*models.ExpireRecord
	mu            sync.RWMutex
	stopCh        chan struct{}
}

func NewExpirationManager(cm *cache_manager.CacheManager, logger *logrus.Logger) *ExpirationManager {
	return &ExpirationManager{
		cm:            cm,
		logger:        logger,
		expireRecords: make(map[string][]*models.ExpireRecord),
		stopCh:        make(chan struct{}),
	}
}

func (em *ExpirationManager) Start(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	em.logger.Infof("Expiration manager started, interval: %v", interval)

	for {
		select {
		case <-ctx.Done():
			em.logger.Info("Expiration manager stopped")
			return
		case <-em.stopCh:
			em.logger.Info("Expiration manager stopped via stop channel")
			return
		case <-ticker.C:
			em.CleanupExpired()
		}
	}
}

func (em *ExpirationManager) Stop() {
	close(em.stopCh)
}

func (em *ExpirationManager) CleanupExpired() {
	cacheIDs := em.cm.GetAllCacheIDs()

	for _, cacheID := range cacheIDs {
		cache, err := em.cm.GetCache(cacheID)
		if err != nil {
			continue
		}

		expiredKeys := cache.GetExpiredKeys()
		if len(expiredKeys) == 0 {
			continue
		}

		em.logger.Infof("Found %d expired keys in cache %s", len(expiredKeys), cacheID)

		for _, key := range expiredKeys {
			if cache.Delete(key) {
				em.logger.Debugf("Removed expired key: %s from cache: %s", key, cacheID)
				em.recordExpiration(cacheID, key, "ttl_expired")
			}
		}
	}
}

func (em *ExpirationManager) recordExpiration(cacheID, key, reason string) {
	record := &models.ExpireRecord{
		ExpireID:     fmt.Sprintf("expire_%s_%d", cacheID, time.Now().UnixNano()),
		CacheID:      cacheID,
		Key:          key,
		ExpireTime:   time.Now(),
		ExpireReason: reason,
		Status:       "expired",
	}

	em.mu.Lock()
	defer em.mu.Unlock()

	records, exists := em.expireRecords[cacheID]
	if !exists {
		records = make([]*models.ExpireRecord, 0)
	}
	records = append(records, record)
	if len(records) > 1000 {
		records = records[len(records)-1000:]
	}
	em.expireRecords[cacheID] = records
}

func (em *ExpirationManager) InvalidateKey(cacheID, key string, reason string) bool {
	cache, err := em.cm.GetCache(cacheID)
	if err != nil {
		return false
	}

	if cache.Delete(key) {
		em.recordExpiration(cacheID, key, reason)
		em.logger.Infof("Invalidated key: %s from cache: %s, reason: %s", key, cacheID, reason)
		return true
	}

	return false
}

func (em *ExpirationManager) InvalidateByPattern(cacheID, pattern string) int {
	cache, err := em.cm.GetCache(cacheID)
	if err != nil {
		return 0
	}

	keys := cache.GetKeys()
	count := 0

	for _, key := range keys {
		if matchesPattern(key, pattern) {
			if cache.Delete(key) {
				em.recordExpiration(cacheID, key, "pattern_invalidation")
				count++
			}
		}
	}

	em.logger.Infof("Invalidated %d keys by pattern: %s from cache: %s", count, pattern, cacheID)
	return count
}

func matchesPattern(key, pattern string) bool {
	if pattern == "*" {
		return true
	}
	if len(pattern) == 0 {
		return false
	}

	if pattern[len(pattern)-1] == '*' {
		prefix := pattern[:len(pattern)-1]
		return len(key) >= len(prefix) && key[:len(prefix)] == prefix
	}

	if pattern[0] == '*' {
		suffix := pattern[1:]
		return len(key) >= len(suffix) && key[len(key)-len(suffix):] == suffix
	}

	return key == pattern
}

func (em *ExpirationManager) GetExpireRecords(cacheID string, limit int) ([]*models.ExpireRecord, error) {
	em.mu.RLock()
	defer em.mu.RUnlock()

	records, exists := em.expireRecords[cacheID]
	if !exists {
		return []*models.ExpireRecord{}, nil
	}

	if limit <= 0 || limit > len(records) {
		limit = len(records)
	}

	result := make([]*models.ExpireRecord, limit)
	copy(result, records[len(records)-limit:])
	return result, nil
}

func (em *ExpirationManager) CheckExpiration(cacheID, key string) bool {
	cache, err := em.cm.GetCache(cacheID)
	if err != nil {
		return true
	}
	return cache.IsExpired(key)
}
