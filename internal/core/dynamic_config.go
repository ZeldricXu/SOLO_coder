package core

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"gorm.io/gorm"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/models"
)

type DynamicConfigLoader struct {
	db             *database.Database
	configCache    map[string]*models.ConfigDefinition
	sceneConfigs   map[string]map[contracts.SceneStrategy]*contracts.SceneConfig
	currentScenes  map[string]contracts.SceneStrategy
	listeners      []contracts.ConfigChangeListener
	cacheMu        sync.RWMutex
	listenerMu     sync.RWMutex
}

func NewDynamicConfigLoader(db *database.Database) *DynamicConfigLoader {
	return &DynamicConfigLoader{
		db:            db,
		configCache:   make(map[string]*models.ConfigDefinition),
		sceneConfigs:  make(map[string]map[contracts.SceneStrategy]*contracts.SceneConfig),
		currentScenes: make(map[string]contracts.SceneStrategy),
		listeners:     make([]contracts.ConfigChangeListener, 0),
	}
}

func (l *DynamicConfigLoader) LoadConfig(ctx context.Context, namespace string) (*models.ConfigDefinition, error) {
	l.cacheMu.RLock()
	if cfg, ok := l.configCache[namespace]; ok {
		l.cacheMu.RUnlock()
		return cfg, nil
	}
	l.cacheMu.RUnlock()

	var config models.ConfigDefinition
	err := l.db.DB.WithContext(ctx).
		Where("namespace = ? AND enabled = ?", namespace, true).
		Order("version DESC").
		First(&config).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			defaultCfg := l.createDefaultConfig(namespace)
			l.cacheMu.Lock()
			l.configCache[namespace] = defaultCfg
			l.initSceneConfigs(namespace)
			l.currentScenes[namespace] = contracts.SceneDefault
			l.cacheMu.Unlock()
			return defaultCfg, nil
		}
		return nil, err
	}

	l.cacheMu.Lock()
	l.configCache[namespace] = &config
	l.initSceneConfigs(namespace)
	if _, exists := l.currentScenes[namespace]; !exists {
		l.currentScenes[namespace] = contracts.SceneDefault
	}
	l.cacheMu.Unlock()

	return &config, nil
}

func (l *DynamicConfigLoader) createDefaultConfig(namespace string) *models.ConfigDefinition {
	return &models.ConfigDefinition{
		ConfigID:  "cfg_default_" + namespace,
		Namespace: namespace,
		Version:   1,
		Parameters: map[string]interface{}{
			"timeout": 30,
			"retries": 3,
		},
		Enabled:   true,
		AppliedAt: time.Now(),
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func (l *DynamicConfigLoader) initSceneConfigs(namespace string) {
	if _, exists := l.sceneConfigs[namespace]; !exists {
		l.sceneConfigs[namespace] = make(map[contracts.SceneStrategy]*contracts.SceneConfig)
		l.sceneConfigs[namespace][contracts.SceneDefault] = &contracts.SceneConfig{
			Scene:        contracts.SceneDefault,
			Timeout:      30 * time.Second,
			MaxRetries:   3,
			PoolSize:     10,
			Priority:     5,
			RateLimit:    100,
			EnableRetry:  true,
			EnableFallback: true,
			CustomRules:  make(map[string]interface{}),
		}
		l.sceneConfigs[namespace][contracts.SceneHighPrio] = &contracts.SceneConfig{
			Scene:        contracts.SceneHighPrio,
			Timeout:      10 * time.Second,
			MaxRetries:   5,
			PoolSize:     20,
			Priority:     10,
			RateLimit:    500,
			EnableRetry:  true,
			EnableFallback: true,
			CustomRules:  make(map[string]interface{}),
		}
		l.sceneConfigs[namespace][contracts.SceneBatch] = &contracts.SceneConfig{
			Scene:        contracts.SceneBatch,
			Timeout:      300 * time.Second,
			MaxRetries:   2,
			PoolSize:     5,
			Priority:     3,
			RateLimit:    10,
			EnableRetry:  false,
			EnableFallback: false,
			CustomRules:  make(map[string]interface{}),
		}
		l.sceneConfigs[namespace][contracts.SceneRealTime] = &contracts.SceneConfig{
			Scene:        contracts.SceneRealTime,
			Timeout:      5 * time.Second,
			MaxRetries:   1,
			PoolSize:     50,
			Priority:     10,
			RateLimit:    1000,
			EnableRetry:  false,
			EnableFallback: true,
			CustomRules:  make(map[string]interface{}),
		}
		l.sceneConfigs[namespace][contracts.SceneBackground] = &contracts.SceneConfig{
			Scene:        contracts.SceneBackground,
			Timeout:      600 * time.Second,
			MaxRetries:   10,
			PoolSize:     3,
			Priority:     1,
			RateLimit:    5,
			EnableRetry:  true,
			EnableFallback: true,
			CustomRules:  make(map[string]interface{}),
		}
	}
}

func (l *DynamicConfigLoader) UpdateConfig(ctx context.Context, namespace string, config *models.ConfigDefinition) error {
	l.cacheMu.RLock()
	oldConfig := l.configCache[namespace]
	l.cacheMu.RUnlock()

	if err := l.db.DB.WithContext(ctx).Save(config).Error; err != nil {
		return err
	}

	l.cacheMu.Lock()
	l.configCache[namespace] = config
	l.cacheMu.Unlock()

	l.notifyConfigChanged(namespace, oldConfig, config)
	return nil
}

func (l *DynamicConfigLoader) GetSceneConfig(ctx context.Context, namespace string, scene contracts.SceneStrategy) (*contracts.SceneConfig, error) {
	l.cacheMu.RLock()
	defer l.cacheMu.RUnlock()

	if scenes, exists := l.sceneConfigs[namespace]; exists {
		if cfg, ok := scenes[scene]; ok {
			return cfg, nil
		}
	}
	return nil, fmt.Errorf("scene config not found for namespace=%s, scene=%s", namespace, scene)
}

func (l *DynamicConfigLoader) SetSceneConfig(ctx context.Context, namespace string, scene contracts.SceneStrategy, config *contracts.SceneConfig) error {
	l.cacheMu.Lock()
	if _, exists := l.sceneConfigs[namespace]; !exists {
		l.sceneConfigs[namespace] = make(map[contracts.SceneStrategy]*contracts.SceneConfig)
	}
	config.Scene = scene
	l.sceneConfigs[namespace][scene] = config
	l.cacheMu.Unlock()

	return nil
}

func (l *DynamicConfigLoader) GetCurrentScene(ctx context.Context, namespace string) contracts.SceneStrategy {
	l.cacheMu.RLock()
	defer l.cacheMu.RUnlock()

	if scene, exists := l.currentScenes[namespace]; exists {
		return scene
	}
	return contracts.SceneDefault
}

func (l *DynamicConfigLoader) SetCurrentScene(ctx context.Context, namespace string, scene contracts.SceneStrategy) error {
	l.cacheMu.RLock()
	oldScene := l.currentScenes[namespace]
	l.cacheMu.RUnlock()

	l.cacheMu.Lock()
	l.currentScenes[namespace] = scene
	l.cacheMu.Unlock()

	l.notifySceneChanged(namespace, oldScene, scene)
	return nil
}

func (l *DynamicConfigLoader) AddChangeListener(listener contracts.ConfigChangeListener) {
	l.listenerMu.Lock()
	defer l.listenerMu.Unlock()
	l.listeners = append(l.listeners, listener)
}

func (l *DynamicConfigLoader) RemoveChangeListener(listener contracts.ConfigChangeListener) {
	l.listenerMu.Lock()
	defer l.listenerMu.Unlock()

	for i, lsn := range l.listeners {
		if lsn == listener {
			l.listeners = append(l.listeners[:i], l.listeners[i+1:]...)
			break
		}
	}
}

func (l *DynamicConfigLoader) ReloadConfig(ctx context.Context, namespace string) error {
	l.cacheMu.Lock()
	delete(l.configCache, namespace)
	l.cacheMu.Unlock()

	_, err := l.LoadConfig(ctx, namespace)
	return err
}

func (l *DynamicConfigLoader) notifyConfigChanged(namespace string, oldConfig, newConfig *models.ConfigDefinition) {
	l.listenerMu.RLock()
	defer l.listenerMu.RUnlock()

	for _, listener := range l.listeners {
		listener.OnConfigChanged(namespace, oldConfig, newConfig)
	}
}

func (l *DynamicConfigLoader) notifySceneChanged(namespace string, oldScene, newScene contracts.SceneStrategy) {
	l.listenerMu.RLock()
	defer l.listenerMu.RUnlock()

	for _, listener := range l.listeners {
		listener.OnSceneChanged(namespace, oldScene, newScene)
	}
}

func (l *DynamicConfigLoader) ListScenes(ctx context.Context, namespace string) []contracts.SceneStrategy {
	l.cacheMu.RLock()
	defer l.cacheMu.RUnlock()

	scenes := make([]contracts.SceneStrategy, 0)
	if sceneMap, exists := l.sceneConfigs[namespace]; exists {
		for scene := range sceneMap {
			scenes = append(scenes, scene)
		}
	}
	return scenes
}
