package storage

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"sync"
	"time"

	"github.com/parking-platform/platform/pkg/models"
)

type StorageConfig struct {
	BackendType      string                 `json:"backend_type"`
	Endpoint         string                 `json:"endpoint"`
	AccessKey        string                 `json:"access_key"`
	SecretKey        string                 `json:"secret_key"`
	Bucket           string                 `json:"bucket"`
	Region           string                 `json:"region"`
	CacheEnabled     bool                   `json:"cache_enabled"`
	CacheTTLSeconds  int                    `json:"cache_ttl_seconds"`
	MaxConnections   int                    `json:"max_connections"`
	ExtraParameters  map[string]interface{} `json:"extra_parameters"`
}

type ConfigChangeListener interface {
	OnConfigChange(oldConfig, newConfig *StorageConfig)
}

type SimpleListener func(oldConfig, newConfig *StorageConfig)

func (f SimpleListener) OnConfigChange(oldConfig, newConfig *StorageConfig) {
	f(oldConfig, newConfig)
}

type ConfigWatcher struct {
	mu           sync.RWMutex
	configPath   string
	current      *StorageConfig
	listeners    []ConfigChangeListener
	pollInterval time.Duration
	stopChan     chan struct{}
	stopped      bool
}

func NewConfigWatcher(configPath string, pollInterval time.Duration) *ConfigWatcher {
	if pollInterval <= 0 {
		pollInterval = 5 * time.Second
	}
	return &ConfigWatcher{
		configPath:   configPath,
		listeners:    make([]ConfigChangeListener, 0),
		pollInterval: pollInterval,
		stopChan:     make(chan struct{}),
	}
}

func (w *ConfigWatcher) LoadConfig() (*StorageConfig, error) {
	data, err := os.ReadFile(w.configPath)
	if err != nil {
		return nil, err
	}
	var config StorageConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, err
	}
	return &config, nil
}

func (w *ConfigWatcher) CurrentConfig() *StorageConfig {
	w.mu.RLock()
	defer w.mu.RUnlock()
	if w.current == nil {
		return &StorageConfig{}
	}
	configCopy := *w.current
	return &configCopy
}

func (w *ConfigWatcher) AddListener(listener ConfigChangeListener) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.listeners = append(w.listeners, listener)
}

func (w *ConfigWatcher) notifyListeners(old, new *StorageConfig) {
	w.mu.RLock()
	listeners := make([]ConfigChangeListener, len(w.listeners))
	copy(listeners, w.listeners)
	w.mu.RUnlock()

	for _, l := range listeners {
		l.OnConfigChange(old, new)
	}
}

func (w *ConfigWatcher) Start(ctx context.Context) error {
	config, err := w.LoadConfig()
	if err != nil {
		return err
	}

	w.mu.Lock()
	w.current = config
	w.stopped = false
	w.mu.Unlock()

	go w.watchLoop(ctx)
	return nil
}

func (w *ConfigWatcher) watchLoop(ctx context.Context) {
	ticker := time.NewTicker(w.pollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-w.stopChan:
			return
		case <-ticker.C:
			w.checkForChanges()
		}
	}
}

func (w *ConfigWatcher) checkForChanges() {
	newConfig, err := w.LoadConfig()
	if err != nil {
		return
	}

	w.mu.Lock()
	oldConfig := w.current
	w.mu.Unlock()

	if !configsEqual(oldConfig, newConfig) {
		w.mu.Lock()
		w.current = newConfig
		w.mu.Unlock()
		w.notifyListeners(oldConfig, newConfig)
	}
}

func configsEqual(a, b *StorageConfig) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	if a.BackendType != b.BackendType ||
		a.Endpoint != b.Endpoint ||
		a.AccessKey != b.AccessKey ||
		a.SecretKey != b.SecretKey ||
		a.Bucket != b.Bucket ||
		a.Region != b.Region ||
		a.CacheEnabled != b.CacheEnabled ||
		a.CacheTTLSeconds != b.CacheTTLSeconds ||
		a.MaxConnections != b.MaxConnections {
		return false
	}
	if len(a.ExtraParameters) != len(b.ExtraParameters) {
		return false
	}
	for k, v := range a.ExtraParameters {
		if bv, ok := b.ExtraParameters[k]; !ok || v != bv {
			return false
		}
	}
	return true
}

func (w *ConfigWatcher) Stop() {
	w.mu.Lock()
	if !w.stopped {
		w.stopped = true
		close(w.stopChan)
	}
	w.mu.Unlock()
}

func (w *ConfigWatcher) ReloadNow() error {
	newConfig, err := w.LoadConfig()
	if err != nil {
		return err
	}

	w.mu.Lock()
	oldConfig := w.current
	w.current = newConfig
	w.mu.Unlock()

	if !configsEqual(oldConfig, newConfig) {
		w.notifyListeners(oldConfig, newConfig)
	}
	return nil
}

type StorageManagerWithConfig struct {
	*StorageManager
	watcher     *ConfigWatcher
	config      *StorageConfig
	configMutex sync.RWMutex
}

func NewStorageManagerWithConfig(adapter StorageAdapter, watcher *ConfigWatcher) *StorageManagerWithConfig {
	mgr := &StorageManagerWithConfig{
		StorageManager: NewStorageManager(adapter),
		watcher:        watcher,
	}

	if watcher != nil {
		mgr.config = watcher.CurrentConfig()
		watcher.AddListener(SimpleListener(func(old, new *StorageConfig) {
			mgr.configMutex.Lock()
			mgr.config = new
			mgr.configMutex.Unlock()
			mgr.applyConfig(new)
		}))
	}

	return mgr
}

func (m *StorageManagerWithConfig) CurrentConfig() *StorageConfig {
	m.configMutex.RLock()
	defer m.configMutex.RUnlock()
	if m.config == nil {
		return &StorageConfig{}
	}
	configCopy := *m.config
	return &configCopy
}

func (m *StorageManagerWithConfig) applyConfig(config *StorageConfig) {
	if config == nil {
		return
	}
	if !config.CacheEnabled {
		m.StorageManager.mu.Lock()
		m.StorageManager.cache = make(map[string][]byte)
		m.StorageManager.mu.Unlock()
	}
}

func (m *StorageManagerWithConfig) Store(bucket, key string, data []byte, contentType string, tags map[string]string) (*models.ObjectMetadata, error) {
	if bucket == "" {
		cfg := m.CurrentConfig()
		if cfg.Bucket != "" {
			bucket = cfg.Bucket
		}
	}
	return m.StorageManager.Store(bucket, key, data, contentType, tags)
}

func (m *StorageManagerWithConfig) Retrieve(bucket, key string) ([]byte, error) {
	if bucket == "" {
		cfg := m.CurrentConfig()
		if cfg.Bucket != "" {
			bucket = cfg.Bucket
		}
	}
	return m.StorageManager.Retrieve(bucket, key)
}

func (m *StorageManagerWithConfig) Remove(bucket, key string) error {
	if bucket == "" {
		cfg := m.CurrentConfig()
		if cfg.Bucket != "" {
			bucket = cfg.Bucket
		}
	}
	return m.StorageManager.Remove(bucket, key)
}

func (m *StorageManagerWithConfig) ListObjects(bucket string) ([]*models.ObjectMetadata, error) {
	if bucket == "" {
		cfg := m.CurrentConfig()
		if cfg.Bucket != "" {
			bucket = cfg.Bucket
		}
	}
	return m.StorageManager.ListObjects(bucket)
}

var (
	ErrConfigNotFound = errors.New("storage config not found")
	ErrWatcherStopped = errors.New("config watcher already stopped")
)
