package ota

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/domain/ota"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/logger"
	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
)

type ConfigManager struct {
	cache        *cache.Cache
	configCache  map[string]*ota.OTAConfig
	watchers     map[string][]chan *ota.OTAConfig
	mu           sync.RWMutex
	defaultConfig *ota.OTAConfig
}

func NewConfigManager(cacheInst *cache.Cache) *ConfigManager {
	cm := &ConfigManager{
		cache:        cacheInst,
		configCache:  make(map[string]*ota.OTAConfig),
		watchers:     make(map[string][]chan *ota.OTAConfig),
		defaultConfig: ota.DefaultConfig(),
	}

	go cm.watchConfigChanges()
	return cm
}

func (cm *ConfigManager) GetConfig(ctx context.Context, profile string) (*ota.OTAConfig, error) {
	cm.mu.RLock()
	if cfg, ok := cm.configCache[profile]; ok {
		cm.mu.RUnlock()
		return cfg.Clone(), nil
	}
	cm.mu.RUnlock()

	if cm.cache != nil {
		key := fmt.Sprintf("%s:%s", ota.ConfigKeyPrefix, profile)
		data, err := cm.cache.Get(ctx, key)
		if err == nil && data != "" {
			var cfg ota.OTAConfig
			if err := json.Unmarshal([]byte(data), &cfg); err == nil {
				cm.mu.Lock()
				cm.configCache[profile] = &cfg
				cm.mu.Unlock()
				return cfg.Clone(), nil
			}
		}
	}

	if profile == ota.DefaultProfile {
		defaultCfg := cm.defaultConfig.Clone()
		cm.mu.Lock()
		cm.configCache[profile] = defaultCfg
		cm.mu.Unlock()
		return defaultCfg.Clone(), nil
	}

	return cm.GetConfig(ctx, ota.DefaultProfile)
}

func (cm *ConfigManager) SaveConfig(ctx context.Context, profile string, config *ota.OTAConfig) error {
	config.Profile = profile
	config.UpdatedAt = time.Now()

	cm.mu.Lock()
	cm.configCache[profile] = config.Clone()
	cm.mu.Unlock()

	if cm.cache != nil {
		key := fmt.Sprintf("%s:%s", ota.ConfigKeyPrefix, profile)
		data, _ := json.Marshal(config)
		if err := cm.cache.Set(ctx, key, string(data), 0); err != nil {
			logger.Get().Warn("Failed to save OTA config to cache", zap.Error(err))
		}
	}

	cm.notifyWatchers(profile, config.Clone())

	logger.Get().Info("OTA config saved", zap.String("profile", profile))
	return nil
}

func (cm *ConfigManager) UpdateConfig(ctx context.Context, profile string, updates map[string]interface{}) (*ota.OTAConfig, error) {
	cfg, err := cm.GetConfig(ctx, profile)
	if err != nil {
		cfg = ota.DefaultConfig()
	}

	v := reflect.ValueOf(cfg).Elem()
	for key, value := range updates {
		field := v.FieldByName(key)
		if field.IsValid() && field.CanSet() {
			field.Set(reflect.ValueOf(value))
		}
	}

	cfg.UpdatedAt = time.Now()

	if err := cm.SaveConfig(ctx, profile, cfg); err != nil {
		return nil, err
	}

	return cfg, nil
}

func (cm *ConfigManager) ListProfiles(ctx context.Context) ([]string, error) {
	if cm.cache == nil {
		return []string{ota.DefaultProfile}, nil
	}

	keys, err := cm.cache.Client.Keys(ctx, fmt.Sprintf("%s:*", ota.ConfigKeyPrefix)).Result()
	if err != nil {
		return nil, err
	}

	profiles := make([]string, 0, len(keys))
	for _, key := range keys {
		profile := key[len(ota.ConfigKeyPrefix)+1:]
		profiles = append(profiles, profile)
	}

	return profiles, nil
}

func (cm *ConfigManager) DeleteProfile(ctx context.Context, profile string) error {
	if profile == ota.DefaultProfile {
		return fmt.Errorf("cannot delete default profile")
	}

	cm.mu.Lock()
	delete(cm.configCache, profile)
	cm.mu.Unlock()

	if cm.cache != nil {
		key := fmt.Sprintf("%s:%s", ota.ConfigKeyPrefix, profile)
		_ = cm.cache.Del(ctx, key)
	}

	return nil
}

func (cm *ConfigManager) GetEffectiveConfig(ctx context.Context, profile string, deviceType string) *ota.OTAConfig {
	cfg, err := cm.GetConfig(ctx, profile)
	if err != nil {
		cfg = cm.defaultConfig.Clone()
	}

	if deviceType != "" {
		if override, ok := cfg.DeviceTypeOverrides[deviceType]; ok {
			if override.DefaultBatchSize > 0 {
				cfg.DefaultBatchSize = override.DefaultBatchSize
			}
			if override.DownloadTimeout > 0 {
				cfg.DownloadTimeout = override.DownloadTimeout
			}
			if override.InstallTimeout > 0 {
				cfg.InstallTimeout = override.InstallTimeout
			}
			if override.MaxRetries > 0 {
				cfg.MaxRetries = override.MaxRetries
			}
		}
	}

	return cfg
}

func (cm *ConfigManager) Watch(ctx context.Context, profile string) <-chan *ota.OTAConfig {
	ch := make(chan *ota.OTAConfig, 1)

	cm.mu.Lock()
	cm.watchers[profile] = append(cm.watchers[profile], ch)
	cm.mu.Unlock()

	if cfg, err := cm.GetConfig(ctx, profile); err == nil {
		ch <- cfg
	}

	go func() {
		<-ctx.Done()
		cm.mu.Lock()
		watchers := cm.watchers[profile]
		for i, w := range watchers {
			if w == ch {
				cm.watchers[profile] = append(watchers[:i], watchers[i+1:]...)
				break
			}
		}
		cm.mu.Unlock()
		close(ch)
	}()

	return ch
}

func (cm *ConfigManager) notifyWatchers(profile string, config *ota.OTAConfig) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	for _, ch := range cm.watchers[profile] {
		select {
		case ch <- config.Clone():
		default:
		}
	}
}

func (cm *ConfigManager) watchConfigChanges() {
	if cm.cache == nil {
		return
	}

	ctx := context.Background()
	pubsub := cm.cache.Subscribe(ctx, fmt.Sprintf("%s:changes", ota.ConfigKeyPrefix))
	defer pubsub.Close()

	ch := pubsub.Channel()
	for msg := range ch {
		var update struct {
			Profile string `json:"profile"`
		}
		if err := json.Unmarshal([]byte(msg.Payload), &update); err == nil {
			cm.mu.Lock()
			delete(cm.configCache, update.Profile)
			cm.mu.Unlock()

			logger.Get().Info("OTA config changed, invalidating cache", zap.String("profile", update.Profile))
		}
	}
}

type configRepository struct {
	cache *cache.Cache
}

func NewConfigRepository(cacheInst *cache.Cache) ota.ConfigRepository {
	return &configRepository{cache: cacheInst}
}

func (r *configRepository) Save(ctx context.Context, profile string, config *ota.OTAConfig) error {
	if r.cache == nil {
		return nil
	}
	key := fmt.Sprintf("%s:%s", ota.ConfigKeyPrefix, profile)
	data, _ := json.Marshal(config)
	return r.cache.Set(ctx, key, string(data), 0)
}

func (r *configRepository) Get(ctx context.Context, profile string) (*ota.OTAConfig, error) {
	if r.cache == nil {
		return nil, redis.Nil
	}
	key := fmt.Sprintf("%s:%s", ota.ConfigKeyPrefix, profile)
	data, err := r.cache.Get(ctx, key)
	if err != nil {
		return nil, err
	}

	var cfg ota.OTAConfig
	if err := json.Unmarshal([]byte(data), &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func (r *configRepository) List(ctx context.Context) ([]string, error) {
	if r.cache == nil {
		return []string{ota.DefaultProfile}, nil
	}
	keys, err := r.cache.Client.Keys(ctx, fmt.Sprintf("%s:*", ota.ConfigKeyPrefix)).Result()
	if err != nil {
		return nil, err
	}

	profiles := make([]string, 0, len(keys))
	for _, key := range keys {
		profile := key[len(ota.ConfigKeyPrefix)+1:]
		profiles = append(profiles, profile)
	}
	return profiles, nil
}

func (r *configRepository) Delete(ctx context.Context, profile string) error {
	if r.cache == nil {
		return nil
	}
	key := fmt.Sprintf("%s:%s", ota.ConfigKeyPrefix, profile)
	return r.cache.Del(ctx, key)
}
