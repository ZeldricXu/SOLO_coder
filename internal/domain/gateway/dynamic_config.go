package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/fsnotify/fsnotify"
)

type GatewayConfig struct {
	Version    int64                    `json:"version"`
	Providers  []*ProviderConfig        `json:"providers"`
	Global     *GatewayGlobalConfig     `json:"global"`
	CreatedAt  time.Time                `json:"created_at"`
	UpdatedAt  time.Time                `json:"updated_at"`
}

type GatewayGlobalConfig struct {
	DefaultTimeoutMs  int `json:"default_timeout_ms"`
	DefaultMaxRetries int `json:"default_max_retries"`
	CircuitThreshold  int `json:"circuit_threshold"`
	CircuitTimeoutMs  int `json:"circuit_timeout_ms"`
}

type ConfigChangeType string

const (
	ConfigChangeProviderAdded   ConfigChangeType = "provider_added"
	ConfigChangeProviderRemoved ConfigChangeType = "provider_removed"
	ConfigChangeProviderUpdated ConfigChangeType = "provider_updated"
	ConfigChangeGlobalUpdated   ConfigChangeType = "global_updated"
)

type ConfigChangeEvent struct {
	Type      ConfigChangeType `json:"type"`
	Provider  string           `json:"provider,omitempty"`
	Timestamp time.Time        `json:"timestamp"`
}

type ConfigChangeListener func(event *ConfigChangeEvent)

type ConfigVersion struct {
	Version   int64         `json:"version"`
	AppliedAt time.Time     `json:"applied_at"`
	Config    *GatewayConfig `json:"config"`
}

type DynamicConfigManager struct {
	configPath    string
	currentConfig atomic.Value
	versions      []*ConfigVersion
	maxVersions   int
	watcher       *fsnotify.Watcher
	listeners     []ConfigChangeListener
	mu            sync.RWMutex
	logger        domain.Logger
	stopCh        chan struct{}
}

func NewDynamicConfigManager(configPath string, maxVersions int, logger domain.Logger) (*DynamicConfigManager, error) {
	m := &DynamicConfigManager{
		configPath:  configPath,
		maxVersions: maxVersions,
		versions:    make([]*ConfigVersion, 0, maxVersions),
		logger:      logger,
		stopCh:      make(chan struct{}),
	}

	if err := m.loadInitialConfig(); err != nil {
		return nil, err
	}

	if err := m.startWatcher(); err != nil {
		m.logger.Warn("Failed to start file watcher, config hot-reload disabled",
			domain.Error(err),
		)
	}

	return m, nil
}

func (m *DynamicConfigManager) loadInitialConfig() error {
	config, err := m.loadConfigFromFile()
	if err != nil {
		if os.IsNotExist(err) {
			m.logger.Info("Config file not found, creating default config")
			config = m.createDefaultConfig()
			if err := m.saveConfigToFile(config); err != nil {
				return err
			}
		} else {
			return err
		}
	}

	m.applyConfig(config)
	return nil
}

func (m *DynamicConfigManager) createDefaultConfig() *GatewayConfig {
	return &GatewayConfig{
		Version:   1,
		Providers: make([]*ProviderConfig, 0),
		Global: &GatewayGlobalConfig{
			DefaultTimeoutMs:  30000,
			DefaultMaxRetries: 2,
			CircuitThreshold:  5,
			CircuitTimeoutMs:  30000,
		},
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func (m *DynamicConfigManager) loadConfigFromFile() (*GatewayConfig, error) {
	data, err := ioutil.ReadFile(m.configPath)
	if err != nil {
		return nil, err
	}

	var config GatewayConfig
	if err := json.Unmarshal(data, &config); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeValidation,
			"invalid config file format")
	}

	return &config, nil
}

func (m *DynamicConfigManager) saveConfigToFile(config *GatewayConfig) error {
	dir := filepath.Dir(m.configPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to create config directory")
	}

	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to marshal config")
	}

	if err := ioutil.WriteFile(m.configPath, data, 0644); err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to write config file")
	}

	return nil
}

func (m *DynamicConfigManager) startWatcher() error {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}

	m.watcher = watcher

	dir := filepath.Dir(m.configPath)
	if err := watcher.Add(dir); err != nil {
		watcher.Close()
		return err
	}

	go m.watchLoop()
	return nil
}

func (m *DynamicConfigManager) watchLoop() {
	defer m.watcher.Close()

	var debounceTimer *time.Timer
	debounceDuration := 500 * time.Millisecond

	for {
		select {
		case event, ok := <-m.watcher.Events:
			if !ok {
				return
			}

			if event.Op&fsnotify.Write == fsnotify.Write || event.Op&fsnotify.Create == fsnotify.Create {
				if filepath.Clean(event.Name) == filepath.Clean(m.configPath) {
					if debounceTimer != nil {
						debounceTimer.Stop()
					}
					debounceTimer = time.AfterFunc(debounceDuration, func() {
						m.reloadConfig()
					})
				}
			}

		case err, ok := <-m.watcher.Errors:
			if !ok {
				return
			}
			m.logger.Warn("Config watcher error", domain.Error(err))

		case <-m.stopCh:
			return
		}
	}
}

func (m *DynamicConfigManager) reloadConfig() {
	m.logger.Info("Detected config file change, reloading...")

	newConfig, err := m.loadConfigFromFile()
	if err != nil {
		m.logger.Error("Failed to reload config", domain.Error(err))
		return
	}

	m.applyConfig(newConfig)
	m.logger.Info("Config reloaded successfully",
		domain.Int64("new_version", newConfig.Version),
	)
}

func (m *DynamicConfigManager) applyConfig(config *GatewayConfig) {
	oldConfig := m.GetConfig()

	m.currentConfig.Store(config)

	version := &ConfigVersion{
		Version:   config.Version,
		AppliedAt: time.Now(),
		Config:    config,
	}

	m.mu.Lock()
	m.versions = append(m.versions, version)
	if len(m.versions) > m.maxVersions {
		m.versions = m.versions[1:]
	}
	m.mu.Unlock()

	if oldConfig != nil {
		m.notifyChanges(oldConfig, config)
	}
}

func (m *DynamicConfigManager) notifyChanges(oldConfig, newConfig *GatewayConfig) {
	oldProviders := make(map[string]*ProviderConfig)
	for _, p := range oldConfig.Providers {
		oldProviders[p.Name] = p
	}

	newProviders := make(map[string]*ProviderConfig)
	for _, p := range newConfig.Providers {
		newProviders[p.Name] = p
	}

	for name, newP := range newProviders {
		if oldP, exists := oldProviders[name]; !exists {
			m.notifyListeners(&ConfigChangeEvent{
				Type:      ConfigChangeProviderAdded,
				Provider:  name,
				Timestamp: time.Now(),
			})
		} else if !providerConfigEqual(oldP, newP) {
			m.notifyListeners(&ConfigChangeEvent{
				Type:      ConfigChangeProviderUpdated,
				Provider:  name,
				Timestamp: time.Now(),
			})
		}
	}

	for name := range oldProviders {
		if _, exists := newProviders[name]; !exists {
			m.notifyListeners(&ConfigChangeEvent{
				Type:      ConfigChangeProviderRemoved,
				Provider:  name,
				Timestamp: time.Now(),
			})
		}
	}

	if !globalConfigEqual(oldConfig.Global, newConfig.Global) {
		m.notifyListeners(&ConfigChangeEvent{
			Type:      ConfigChangeGlobalUpdated,
			Timestamp: time.Now(),
		})
	}
}

func (m *DynamicConfigManager) notifyListeners(event *ConfigChangeEvent) {
	m.mu.RLock()
	listeners := make([]ConfigChangeListener, len(m.listeners))
	copy(listeners, m.listeners)
	m.mu.RUnlock()

	for _, listener := range listeners {
		listener(event)
	}
}

func (m *DynamicConfigManager) GetConfig() *GatewayConfig {
	config := m.currentConfig.Load()
	if config == nil {
		return nil
	}
	return config.(*GatewayConfig)
}

func (m *DynamicConfigManager) UpdateConfig(ctx context.Context, config *GatewayConfig) error {
	oldConfig := m.GetConfig()
	if oldConfig != nil {
		config.Version = oldConfig.Version + 1
	} else {
		config.Version = 1
	}
	config.UpdatedAt = time.Now()
	if config.CreatedAt.IsZero() {
		config.CreatedAt = time.Now()
	}

	if err := m.saveConfigToFile(config); err != nil {
		return err
	}

	m.applyConfig(config)
	return nil
}

func (m *DynamicConfigManager) AddProvider(ctx context.Context, provider *ProviderConfig) error {
	config := m.GetConfig()
	if config == nil {
		config = m.createDefaultConfig()
	}

	for _, p := range config.Providers {
		if p.Name == provider.Name {
			return errors.New(errors.ErrCodeConflict,
				fmt.Sprintf("provider %s already exists", provider.Name))
		}
	}

	newConfig := *config
	newConfig.Providers = append([]*ProviderConfig{}, config.Providers...)
	newConfig.Providers = append(newConfig.Providers, provider)
	newConfig.Version = config.Version + 1
	newConfig.UpdatedAt = time.Now()

	if err := m.saveConfigToFile(&newConfig); err != nil {
		return err
	}

	m.applyConfig(&newConfig)
	return nil
}

func (m *DynamicConfigManager) RemoveProvider(ctx context.Context, providerName string) error {
	config := m.GetConfig()
	if config == nil {
		return errors.New(errors.ErrCodeNotFound, "config not found")
	}

	found := false
	newProviders := make([]*ProviderConfig, 0, len(config.Providers))
	for _, p := range config.Providers {
		if p.Name == providerName {
			found = true
			continue
		}
		newProviders = append(newProviders, p)
	}

	if !found {
		return errors.New(errors.ErrCodeNotFound,
			fmt.Sprintf("provider %s not found", providerName))
	}

	newConfig := *config
	newConfig.Providers = newProviders
	newConfig.Version = config.Version + 1
	newConfig.UpdatedAt = time.Now()

	if err := m.saveConfigToFile(&newConfig); err != nil {
		return err
	}

	m.applyConfig(&newConfig)
	return nil
}

func (m *DynamicConfigManager) UpdateProvider(ctx context.Context, provider *ProviderConfig) error {
	config := m.GetConfig()
	if config == nil {
		return errors.New(errors.ErrCodeNotFound, "config not found")
	}

	found := false
	newProviders := make([]*ProviderConfig, 0, len(config.Providers))
	for _, p := range config.Providers {
		if p.Name == provider.Name {
			found = true
			newProviders = append(newProviders, provider)
		} else {
			newProviders = append(newProviders, p)
		}
	}

	if !found {
		return errors.New(errors.ErrCodeNotFound,
			fmt.Sprintf("provider %s not found", provider.Name))
	}

	newConfig := *config
	newConfig.Providers = newProviders
	newConfig.Version = config.Version + 1
	newConfig.UpdatedAt = time.Now()

	if err := m.saveConfigToFile(&newConfig); err != nil {
		return err
	}

	m.applyConfig(&newConfig)
	return nil
}

func (m *DynamicConfigManager) AddChangeListener(listener ConfigChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners = append(m.listeners, listener)
}

func (m *DynamicConfigManager) GetVersions() []*ConfigVersion {
	m.mu.RLock()
	defer m.mu.RUnlock()

	versions := make([]*ConfigVersion, len(m.versions))
	copy(versions, m.versions)
	return versions
}

func (m *DynamicConfigManager) RollbackToVersion(ctx context.Context, version int64) error {
	m.mu.RLock()
	var targetVersion *ConfigVersion
	for _, v := range m.versions {
		if v.Version == version {
			targetVersion = v
			break
		}
	}
	m.mu.RUnlock()

	if targetVersion == nil {
		return errors.New(errors.ErrCodeNotFound,
			fmt.Sprintf("version %d not found", version))
	}

	return m.UpdateConfig(ctx, targetVersion.Config)
}

func (m *DynamicConfigManager) Close() {
	close(m.stopCh)
	if m.watcher != nil {
		m.watcher.Close()
	}
}

func providerConfigEqual(a, b *ProviderConfig) bool {
	return a.Name == b.Name &&
		a.BaseURL == b.BaseURL &&
		a.APIKey == b.APIKey &&
		a.Priority == b.Priority &&
		a.Weight == b.Weight &&
		a.TimeoutMs == b.TimeoutMs &&
		a.MaxRetries == b.MaxRetries
}

func globalConfigEqual(a, b *GatewayGlobalConfig) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}
	return a.DefaultTimeoutMs == b.DefaultTimeoutMs &&
		a.DefaultMaxRetries == b.DefaultMaxRetries &&
		a.CircuitThreshold == b.CircuitThreshold &&
		a.CircuitTimeoutMs == b.CircuitTimeoutMs
}

type DynamicGateway struct {
	*InferenceGatewayImpl
	configManager *DynamicConfigManager
}

func NewDynamicGateway(
	configManager *DynamicConfigManager,
	loadBalancer domain.LoadBalancer,
	logger domain.Logger,
) (*DynamicGateway, error) {
	config := configManager.GetConfig()
	global := config.Global

	gateway := NewInferenceGatewayImpl(
		loadBalancer,
		global.CircuitThreshold,
		time.Duration(global.CircuitTimeoutMs)*time.Millisecond,
		logger,
	)

	dg := &DynamicGateway{
		InferenceGatewayImpl: gateway,
		configManager:        configManager,
	}

	for _, providerCfg := range config.Providers {
		provider := NewHTTPModelProvider(providerCfg)
		if err := gateway.RegisterProvider(provider); err != nil {
			logger.Warn("Failed to register provider",
				domain.String("provider", providerCfg.Name),
				domain.Error(err),
			)
		}
	}

	configManager.AddChangeListener(dg.onConfigChange)

	return dg, nil
}

func (dg *DynamicGateway) onConfigChange(event *ConfigChangeEvent) {
	dg.logger.Info("Config change detected",
		domain.String("change_type", string(event.Type)),
		domain.String("provider", event.Provider),
	)

	switch event.Type {
	case ConfigChangeProviderAdded:
		config := dg.configManager.GetConfig()
		for _, p := range config.Providers {
			if p.Name == event.Provider {
				provider := NewHTTPModelProvider(p)
				dg.RegisterProvider(provider)
				break
			}
		}

	case ConfigChangeProviderRemoved:
		dg.RemoveProvider(event.Provider)

	case ConfigChangeProviderUpdated:
		dg.RemoveProvider(event.Provider)
		config := dg.configManager.GetConfig()
		for _, p := range config.Providers {
			if p.Name == event.Provider {
				provider := NewHTTPModelProvider(p)
				dg.RegisterProvider(provider)
				break
			}
		}

	case ConfigChangeGlobalUpdated:
		config := dg.configManager.GetConfig()
		global := config.Global
		dg.circuitThreshold = global.CircuitThreshold
		dg.circuitTimeout = time.Duration(global.CircuitTimeoutMs) * time.Millisecond
	}
}

func (dg *DynamicGateway) GetConfigManager() *DynamicConfigManager {
	return dg.configManager
}

func (dg *DynamicGateway) Shutdown() {
	dg.configManager.Close()
}
