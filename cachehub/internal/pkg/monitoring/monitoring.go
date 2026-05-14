package monitoring

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/sirupsen/logrus"
)

type MonitoringManager struct {
	cm          *cache_manager.CacheManager
	logger      *logrus.Logger
	stats       map[string]*models.CacheStats
	statsHistory map[string][]*models.CacheStats
	mu          sync.RWMutex
	stopCh      chan struct{}
}

func NewMonitoringManager(cm *cache_manager.CacheManager, logger *logrus.Logger) *MonitoringManager {
	return &MonitoringManager{
		cm:           cm,
		logger:       logger,
		stats:        make(map[string]*models.CacheStats),
		statsHistory: make(map[string][]*models.CacheStats),
		stopCh:       make(chan struct{}),
	}
}

func (mm *MonitoringManager) Start(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	mm.logger.Infof("Monitoring manager started, interval: %v", interval)

	for {
		select {
		case <-ctx.Done():
			mm.logger.Info("Monitoring manager stopped")
			return
		case <-mm.stopCh:
			mm.logger.Info("Monitoring manager stopped via stop channel")
			return
		case <-ticker.C:
			mm.CollectStats()
		}
	}
}

func (mm *MonitoringManager) Stop() {
	close(mm.stopCh)
}

func (mm *MonitoringManager) CollectStats() {
	cacheIDs := mm.cm.GetAllCacheIDs()

	for _, cacheID := range cacheIDs {
		cache, err := mm.cm.GetCache(cacheID)
		if err != nil {
			continue
		}

		instance, err := mm.cm.GetInstance(cacheID)
		if err != nil {
			continue
		}

		hitCount := cache.GetHitCount()
		missCount := cache.GetMissCount()
		totalRequests := hitCount + missCount
		hitRate := 0.0
		if totalRequests > 0 {
			hitRate = float64(hitCount) / float64(totalRequests) * 100
		}

		usage := cache.GetUsage()
		capacity := cache.GetCapacity()
		capacityUsage := 0.0
		if capacity > 0 {
			capacityUsage = float64(usage) / float64(capacity) * 100
		}

		stats := &models.CacheStats{
			StatID:        fmt.Sprintf("stat_%s_%d", cacheID, time.Now().Unix()),
			CacheID:       cacheID,
			StatTime:      time.Now(),
			TotalKeys:     cache.GetCount(),
			HitRate:       hitRate,
			HitCount:      hitCount,
			MissCount:     missCount,
			CapacityUsage: capacityUsage,
			CurrentUsage:  usage,
			EvictionCount: cache.GetEvictCount(),
		}

		mm.mu.Lock()
		mm.stats[cacheID] = stats

		history, exists := mm.statsHistory[cacheID]
		if !exists {
			history = make([]*models.CacheStats, 0)
		}
		history = append(history, stats)
		if len(history) > 100 {
			history = history[len(history)-100:]
		}
		mm.statsHistory[cacheID] = history
		mm.mu.Unlock()

		mm.logger.Debugf("Collected stats for cache %s: hit_rate=%.2f%%, capacity_usage=%.2f%%",
			cacheID, hitRate, capacityUsage)

		if instance != nil && instance.MaxCapacity > 0 {
			_ = instance
		}
	}
}

func (mm *MonitoringManager) GetStats(cacheID string) (*models.CacheStats, error) {
	mm.mu.RLock()
	defer mm.mu.RUnlock()

	stats, exists := mm.stats[cacheID]
	if !exists {
		return nil, nil
	}
	return stats, nil
}

func (mm *MonitoringManager) GetAllStats() map[string]*models.CacheStats {
	mm.mu.RLock()
	defer mm.mu.RUnlock()

	result := make(map[string]*models.CacheStats)
	for k, v := range mm.stats {
		result[k] = v
	}
	return result
}

func (mm *MonitoringManager) GetStatsHistory(cacheID string, limit int) ([]*models.CacheStats, error) {
	mm.mu.RLock()
	defer mm.mu.RUnlock()

	history, exists := mm.statsHistory[cacheID]
	if !exists {
		return []*models.CacheStats{}, nil
	}

	if limit <= 0 || limit > len(history) {
		limit = len(history)
	}

	result := make([]*models.CacheStats, limit)
	copy(result, history[len(history)-limit:])
	return result, nil
}

func (mm *MonitoringManager) GetHitRate(cacheID string) float64 {
	stats, err := mm.GetStats(cacheID)
	if err != nil || stats == nil {
		return 0
	}
	return stats.HitRate
}

func (mm *MonitoringManager) GetCapacityUsage(cacheID string) float64 {
	stats, err := mm.GetStats(cacheID)
	if err != nil || stats == nil {
		return 0
	}
	return stats.CapacityUsage
}

func (mm *MonitoringManager) GetCurrentUsage(cacheID string) int64 {
	stats, err := mm.GetStats(cacheID)
	if err != nil || stats == nil {
		return 0
	}
	return stats.CurrentUsage
}

func (mm *MonitoringManager) GetTotalKeys(cacheID string) int {
	stats, err := mm.GetStats(cacheID)
	if err != nil || stats == nil {
		return 0
	}
	return stats.TotalKeys
}
