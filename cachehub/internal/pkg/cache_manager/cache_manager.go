package cache_manager

import (
	"errors"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/models"
	"github.com/sirupsen/logrus"
)

type CacheManager struct {
	instances map[string]*models.CacheInstance
	caches    map[string]*models.InMemoryCache
	mu        sync.RWMutex
	logger    *logrus.Logger
}

func NewCacheManager(logger *logrus.Logger) *CacheManager {
	return &CacheManager{
		instances: make(map[string]*models.CacheInstance),
		caches:    make(map[string]*models.InMemoryCache),
		logger:    logger,
	}
}

func (cm *CacheManager) RegisterInstance(instance *models.CacheInstance) error {
	if instance.CacheID == "" {
		return errors.New("cache_id is required")
	}

	cm.mu.Lock()
	defer cm.mu.Unlock()

	if _, exists := cm.instances[instance.CacheID]; exists {
		return errors.New("cache instance already exists")
	}

	instance.RegisteredAt = time.Now()
	if instance.Status == "" {
		instance.Status = "online"
	}
	if instance.DefaultTTL == 0 {
		instance.DefaultTTL = 3600
	}
	if instance.EvictionPolicy == "" {
		instance.EvictionPolicy = "lru"
	}

	cm.instances[instance.CacheID] = instance
	cm.caches[instance.CacheID] = models.NewInMemoryCache(instance.CacheID, instance.MaxCapacity)

	cm.logger.Infof("Cache instance registered: %s", instance.CacheID)
	return nil
}

func (cm *CacheManager) GetInstance(cacheID string) (*models.CacheInstance, error) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	instance, exists := cm.instances[cacheID]
	if !exists {
		return nil, errors.New("cache instance not found")
	}
	return instance, nil
}

func (cm *CacheManager) GetCache(cacheID string) (*models.InMemoryCache, error) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	cache, exists := cm.caches[cacheID]
	if !exists {
		return nil, errors.New("cache not found")
	}
	return cache, nil
}

func (cm *CacheManager) UpdateInstance(cacheID string, updates *models.CacheInstance) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	instance, exists := cm.instances[cacheID]
	if !exists {
		return errors.New("cache instance not found")
	}

	if updates.CacheName != "" {
		instance.CacheName = updates.CacheName
	}
	if updates.Status != "" {
		instance.Status = updates.Status
	}
	if updates.MaxCapacity > 0 {
		instance.MaxCapacity = updates.MaxCapacity
		if cache, exists := cm.caches[cacheID]; exists {
			cache.UpdateCapacity(updates.MaxCapacity)
		}
	}
	if updates.DefaultTTL > 0 {
		instance.DefaultTTL = updates.DefaultTTL
	}
	if updates.EvictionPolicy != "" {
		instance.EvictionPolicy = updates.EvictionPolicy
	}

	cm.logger.Infof("Cache instance updated: %s", cacheID)
	return nil
}

func (cm *CacheManager) RemoveInstance(cacheID string) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	if _, exists := cm.instances[cacheID]; !exists {
		return errors.New("cache instance not found")
	}

	delete(cm.instances, cacheID)
	delete(cm.caches, cacheID)

	cm.logger.Infof("Cache instance removed: %s", cacheID)
	return nil
}

func (cm *CacheManager) ListInstances() []*models.CacheInstance {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	instances := make([]*models.CacheInstance, 0, len(cm.instances))
	for _, instance := range cm.instances {
		instances = append(instances, instance)
	}
	return instances
}

func (cm *CacheManager) GetAllCacheIDs() []string {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	ids := make([]string, 0, len(cm.instances))
	for id := range cm.instances {
		ids = append(ids, id)
	}
	return ids
}
