package preheating

import (
	"errors"
	"sync"
	"time"

	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/models"
	"github.com/sirupsen/logrus"
)

type PreheatTask struct {
	CacheID   string
	Keys      []string
	Loader    func(key string) (interface{}, error)
	TTL       int
}

type PreheatingManager struct {
	cm        *cache_manager.CacheManager
	logger    *logrus.Logger
	tasks     map[string]*PreheatTask
	results   map[string]PreheatResult
	mu        sync.RWMutex
}

type PreheatResult struct {
	CacheID     string
	TotalKeys   int
	LoadedKeys  int
	FailedKeys  int
	StartTime   time.Time
	EndTime     time.Time
	Errors      []string
}

func NewPreheatingManager(cm *cache_manager.CacheManager, logger *logrus.Logger) *PreheatingManager {
	return &PreheatingManager{
		cm:      cm,
		logger:  logger,
		tasks:   make(map[string]*PreheatTask),
		results: make(map[string]PreheatResult),
	}
}

func (pm *PreheatingManager) RegisterTask(taskID string, task *PreheatTask) error {
	if taskID == "" {
		return errors.New("task_id is required")
	}
	if task.CacheID == "" {
		return errors.New("cache_id is required")
	}
	if task.Loader == nil {
		return errors.New("loader function is required")
	}

	pm.mu.Lock()
	defer pm.mu.Unlock()

	pm.tasks[taskID] = task
	pm.logger.Infof("Preheat task registered: %s for cache: %s, keys: %d", taskID, task.CacheID, len(task.Keys))
	return nil
}

func (pm *PreheatingManager) ExecuteTask(taskID string) (PreheatResult, error) {
	pm.mu.RLock()
	task, exists := pm.tasks[taskID]
	pm.mu.RUnlock()

	if !exists {
		return PreheatResult{}, errors.New("task not found")
	}

	instance, err := pm.cm.GetInstance(task.CacheID)
	if err != nil {
		return PreheatResult{}, err
	}

	if instance.Status != "online" {
		return PreheatResult{}, errors.New("cache instance is not online")
	}

	cache, err := pm.cm.GetCache(task.CacheID)
	if err != nil {
		return PreheatResult{}, err
	}

	result := PreheatResult{
		CacheID:   task.CacheID,
		TotalKeys: len(task.Keys),
		StartTime: time.Now(),
		Errors:    make([]string, 0),
	}

	pm.logger.Infof("Executing preheat task: %s, total keys: %d", taskID, len(task.Keys))

	for _, key := range task.Keys {
		value, err := task.Loader(key)
		if err != nil {
			result.FailedKeys++
			result.Errors = append(result.Errors, key+": "+err.Error())
			pm.logger.Warnf("Failed to load key %s: %v", key, err)
			continue
		}

		ttl := task.TTL
		if ttl <= 0 {
			ttl = instance.DefaultTTL
		}

		cache.Set(key, value, ttl)
		result.LoadedKeys++
	}

	result.EndTime = time.Now()

	pm.mu.Lock()
	pm.results[taskID] = result
	pm.mu.Unlock()

	pm.logger.Infof("Preheat task completed: %s, loaded: %d, failed: %d, duration: %v",
		taskID, result.LoadedKeys, result.FailedKeys, result.EndTime.Sub(result.StartTime))

	return result, nil
}

func (pm *PreheatingManager) PreheatKeys(cacheID string, keys []string, loader func(string) (interface{}, error), ttl int) (int, error) {
	taskID := "manual_" + cacheID + "_" + time.Now().Format("20060102150405")

	task := &PreheatTask{
		CacheID: cacheID,
		Keys:    keys,
		Loader:  loader,
		TTL:     ttl,
	}

	err := pm.RegisterTask(taskID, task)
	if err != nil {
		return 0, err
	}

	result, err := pm.ExecuteTask(taskID)
	if err != nil {
		return 0, err
	}

	return result.LoadedKeys, nil
}

func (pm *PreheatingManager) PreheatHotKeys(cacheID string, count int, loader func(string) (interface{}, error)) (int, error) {
	cache, err := pm.cm.GetCache(cacheID)
	if err != nil {
		return 0, err
	}

	items := cache.GetAll()
	if len(items) == 0 {
		return 0, nil
	}

	itemList := make([]*models.CacheData, 0, len(items))
	for _, item := range items {
		itemList = append(itemList, item)
	}

	for i := 0; i < len(itemList)-1; i++ {
		for j := i + 1; j < len(itemList); j++ {
			if itemList[i].HitCount < itemList[j].HitCount {
				itemList[i], itemList[j] = itemList[j], itemList[i]
			}
		}
	}

	if count > len(itemList) {
		count = len(itemList)
	}

	hotKeys := make([]string, 0, count)
	for i := 0; i < count; i++ {
		hotKeys = append(hotKeys, itemList[i].Key)
	}

	return pm.PreheatKeys(cacheID, hotKeys, loader, 0)
}

func (pm *PreheatingManager) GetTaskResult(taskID string) (PreheatResult, error) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	result, exists := pm.results[taskID]
	if !exists {
		return PreheatResult{}, errors.New("result not found")
	}
	return result, nil
}

func (pm *PreheatingManager) RemoveTask(taskID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	if _, exists := pm.tasks[taskID]; !exists {
		return errors.New("task not found")
	}

	delete(pm.tasks, taskID)
	delete(pm.results, taskID)
	pm.logger.Infof("Preheat task removed: %s", taskID)
	return nil
}
