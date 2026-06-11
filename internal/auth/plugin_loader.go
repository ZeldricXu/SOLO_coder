package auth

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"

	"DF1-56/internal/models"
)

type PluginLoader struct {
	configPath string
	registry   *ProviderRegistry
	mu         sync.RWMutex
	loaded     map[string]bool
}

func NewPluginLoader(configPath string) *PluginLoader {
	return &PluginLoader{
		configPath: configPath,
		registry:   GetDefaultRegistry(),
		loaded:     make(map[string]bool),
	}
}

type PluginConfig struct {
	Providers []PluginProviderConfig `json:"providers"`
}

type PluginProviderConfig struct {
	Name       string                 `json:"name"`
	Type       string                 `json:"type"`
	Config     map[string]interface{} `json:"config"`
	PluginPath string                 `json:"plugin_path,omitempty"`
	Priority   int                    `json:"priority"`
	Enabled    bool                   `json:"enabled"`
}

func (pl *PluginLoader) LoadPlugins() (*PluginConfig, error) {
	pl.mu.Lock()
	defer pl.mu.Unlock()

	data, err := pl.readConfigFile()
	if err != nil {
		return nil, fmt.Errorf("failed to read plugin config: %w", err)
	}

	var pluginConfig PluginConfig
	if err := json.Unmarshal(data, &pluginConfig); err != nil {
		return nil, fmt.Errorf("failed to parse plugin config: %w", err)
	}

	for _, providerCfg := range pluginConfig.Providers {
		if !providerCfg.Enabled {
			continue
		}

		providerType := providerCfg.Type
		if providerType == "" {
			providerType = providerCfg.Name
		}

		if pl.loaded[providerType] {
			continue
		}

		factory := func() AuthProvider {
			provider, _ := CreateProvider(providerType, nil)
			return provider
		}

		pl.registry.RegisterFactory(providerType, factory)

		if providerCfg.Config != nil {
			provider, err := CreateProvider(providerType, providerCfg.Config)
			if err != nil {
				return nil, fmt.Errorf("failed to configure provider %s: %w", providerCfg.Name, err)
			}
			pl.registry.Register(providerType, provider)
		}

		pl.loaded[providerType] = true
	}

	return &pluginConfig, nil
}

func (pl *PluginLoader) LoadProviderConfig(providerType string) (map[string]interface{}, error) {
	data, err := pl.readConfigFile()
	if err != nil {
		return nil, err
	}

	var pluginConfig PluginConfig
	if err := json.Unmarshal(data, &pluginConfig); err != nil {
		return nil, err
	}

	for _, providerCfg := range pluginConfig.Providers {
		cfgType := providerCfg.Type
		if cfgType == "" {
			cfgType = providerCfg.Name
		}
		if cfgType == providerType {
			return providerCfg.Config, nil
		}
	}

	return nil, fmt.Errorf("provider %s not found in config", providerType)
}

func (pl *PluginLoader) readConfigFile() ([]byte, error) {
	if pl.configPath == "" {
		return nil, errors.New("plugin config path not set")
	}

	absPath, err := filepath.Abs(pl.configPath)
	if err != nil {
		return nil, fmt.Errorf("invalid config path: %w", err)
	}

	file, err := os.Open(absPath)
	if err != nil {
		return nil, fmt.Errorf("failed to open config file: %w", err)
	}
	defer file.Close()

	data, err := io.ReadAll(file)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	return data, nil
}

func (pl *PluginLoader) GetLoadedProviders() []string {
	pl.mu.RLock()
	defer pl.mu.RUnlock()

	providers := make([]string, 0, len(pl.loaded))
	for name := range pl.loaded {
		providers = append(providers, name)
	}
	return providers
}

func (pl *PluginLoader) Reload() error {
	pl.mu.Lock()
	pl.loaded = make(map[string]bool)
	pl.mu.Unlock()

	_, err := pl.LoadPlugins()
	return err
}

func LoadAuthConfigFromFile(configPath string) (*models.AuthPolicy, map[string]interface{}, error) {
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to read auth config: %w", err)
	}

	type AuthConfigFile struct {
		AuthPolicy       *models.AuthPolicy      `json:"auth_policy"`
		ValidAPIKeys     map[string]string       `json:"valid_api_keys"`
		ProviderConfigs  map[string]interface{}  `json:"provider_configs"`
	}

	var cfg AuthConfigFile
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, nil, fmt.Errorf("failed to parse auth config: %w", err)
	}

	if cfg.AuthPolicy == nil {
		return nil, nil, errors.New("auth_policy is required in config")
	}

	return cfg.AuthPolicy, cfg.ProviderConfigs, nil
}

func BuildMiddlewareChainFromConfigFile(configPath string) (*MiddlewareChain, map[string]string, error) {
	authPolicy, providerConfigs, err := LoadAuthConfigFromFile(configPath)
	if err != nil {
		return nil, nil, err
	}

	type AuthConfigFile struct {
		ValidAPIKeys map[string]string `json:"valid_api_keys"`
	}

	data, _ := os.ReadFile(configPath)
	var cfg AuthConfigFile
	_ = json.Unmarshal(data, &cfg)

	validKeys := cfg.ValidAPIKeys

	chain, err := BuildMiddlewareChainWithCustomProviders(authPolicy, validKeys, providerConfigs)
	if err != nil {
		return nil, nil, err
	}

	return chain, validKeys, nil
}
