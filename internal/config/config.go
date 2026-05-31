package config

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"

	"github.com/fsnotify/fsnotify"
	"github.com/spf13/viper"
	"gopkg.in/yaml.v3"
)

type ConfigSource string

const (
	SourceFile   ConfigSource = "file"
	SourceEnv    ConfigSource = "env"
	SourceRemote ConfigSource = "remote"
	SourceDefault ConfigSource = "default"
)

type ConfigChangeEvent struct {
	Key      string
	OldValue interface{}
	NewValue interface{}
	Source   ConfigSource
	Time     time.Time
}

type ConfigChangeListener func(event ConfigChangeEvent)

type ConfigItem struct {
	Key         string      `json:"key"`
	Value       interface{} `json:"value"`
	Description string      `json:"description"`
	Source      ConfigSource `json:"source"`
	UpdatedAt   time.Time   `json:"updated_at"`
	Version     int         `json:"version"`
}

type AppConfig struct {
	Server   ServerConfig   `mapstructure:"server"`
	Database DatabaseConfig `mapstructure:"database"`
	Cache    CacheConfig    `mapstructure:"cache"`
	Security SecurityConfig `mapstructure:"security"`
	Logging  LoggingConfig  `mapstructure:"logging"`
	Modules  ModulesConfig  `mapstructure:"modules"`
}

type ServerConfig struct {
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
	Mode string `mapstructure:"mode"`
}

type DatabaseConfig struct {
	Path     string `mapstructure:"path"`
	MaxConns int    `mapstructure:"max_conns"`
}

type CacheConfig struct {
	Type         string        `mapstructure:"type"`
	RedisAddr    string        `mapstructure:"redis_addr"`
	RedisPass    string        `mapstructure:"redis_pass"`
	RedisDB      int           `mapstructure:"redis_db"`
	Strategy     string        `mapstructure:"strategy"`
	TTL          time.Duration `mapstructure:"ttl"`
	MaxSize      int           `mapstructure:"max_size"`
}

type SecurityConfig struct {
	Secret     string `mapstructure:"secret"`
	JWTExpire  int    `mapstructure:"jwt_expire"`
	EnableAuth bool   `mapstructure:"enable_auth"`
}

type LoggingConfig struct {
	Level string `mapstructure:"level"`
	Path  string `mapstructure:"path"`
}

type ModulesConfig struct {
	DocIndex     DocIndexConfig     `mapstructure:"doc_index"`
	Scheduler    SchedulerConfig    `mapstructure:"scheduler"`
	Notification NotificationConfig `mapstructure:"notification"`
	Environment  EnvironmentConfig  `mapstructure:"environment"`
	Scaffold     ScaffoldConfig     `mapstructure:"scaffold"`
	Vulnerability VulnerabilityConfig `mapstructure:"vulnerability"`
}

type DocIndexConfig struct {
	Enabled     bool              `mapstructure:"enabled"`
	Sources     []DocSourceConfig `mapstructure:"sources"`
	IndexPath   string            `mapstructure:"index_path"`
	SyncCron    string            `mapstructure:"sync_cron"`
}

type DocSourceConfig struct {
	Type     string `mapstructure:"type"`
	URL      string `mapstructure:"url"`
	Token    string `mapstructure:"token"`
	Path     string `mapstructure:"path"`
	Enabled  bool   `mapstructure:"enabled"`
}

type SchedulerConfig struct {
	Enabled      bool   `mapstructure:"enabled"`
	WorkerCount  int    `mapstructure:"worker_count"`
	QueueSize    int    `mapstructure:"queue_size"`
}

type NotificationConfig struct {
	Enabled      bool   `mapstructure:"enabled"`
	SMTPHost     string `mapstructure:"smtp_host"`
	SMTPPort     int    `mapstructure:"smtp_port"`
	SMTPUser     string `mapstructure:"smtp_user"`
	SMTPPass     string `mapstructure:"smtp_pass"`
	WebhookURL   string `mapstructure:"webhook_url"`
}

type EnvironmentConfig struct {
	Enabled         bool          `mapstructure:"enabled"`
	DefaultTTL      time.Duration `mapstructure:"default_ttl"`
	MaxEnvironments int           `mapstructure:"max_environments"`
	ResourceLimit   ResourceLimit `mapstructure:"resource_limit"`
}

type ResourceLimit struct {
	CPU    float64 `mapstructure:"cpu"`
	Memory int64   `mapstructure:"memory"`
}

type ScaffoldConfig struct {
	Enabled      bool   `mapstructure:"enabled"`
	TemplatePath string `mapstructure:"template_path"`
	OutputPath   string `mapstructure:"output_path"`
}

type VulnerabilityConfig struct {
	Enabled         bool   `mapstructure:"enabled"`
	CVEDataURL      string `mapstructure:"cve_data_url"`
	SyncInterval    int    `mapstructure:"sync_interval"`
	NVDAPIKey       string `mapstructure:"nvd_api_key"`
}

type Manager struct {
	mu              sync.RWMutex
	viper           *viper.Viper
	config          *AppConfig
	sources         []ConfigSource
	listeners       map[string][]ConfigChangeListener
	items           map[string]*ConfigItem
	watcher         *fsnotify.Watcher
	configFiles     []string
	autoRefresh     bool
	refreshInterval time.Duration
	ctx             context.Context
	cancel          context.CancelFunc
}

func NewManager() *Manager {
	ctx, cancel := context.WithCancel(context.Background())
	return &Manager{
		viper:           viper.New(),
		config:          &AppConfig{},
		sources:         []ConfigSource{},
		listeners:       make(map[string][]ConfigChangeListener),
		items:           make(map[string]*ConfigItem),
		configFiles:     []string{},
		autoRefresh:     true,
		refreshInterval: 5 * time.Minute,
		ctx:             ctx,
		cancel:          cancel,
	}
}

func (m *Manager) Load(configPath string) error {
	if configPath != "" {
		if err := m.loadFromFile(configPath); err != nil {
			logger.Warn("Failed to load from file: %v, using defaults", err)
		}
	}

	m.loadFromEnv()
	m.loadDefaults()

	if err := m.viper.Unmarshal(m.config); err != nil {
		return fmt.Errorf("failed to unmarshal config: %w", err)
	}

	if m.autoRefresh {
		go m.startAutoRefresh()
	}

	logger.Info("Configuration loaded successfully")
	return nil
}

func (m *Manager) loadFromFile(path string) error {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return err
	}

	m.viper.SetConfigFile(absPath)
	m.viper.SetConfigType("yaml")

	if err := m.viper.ReadInConfig(); err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	m.configFiles = append(m.configFiles, absPath)
	m.sources = append(m.sources, SourceFile)

	if err := m.setupWatcher(absPath); err != nil {
		logger.Warn("Failed to setup config watcher: %v", err)
	}

	logger.Info("Loaded config from file: %s", absPath)
	return nil
}

func (m *Manager) loadFromEnv() {
	m.viper.SetEnvPrefix("TECH")
	m.viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	m.viper.AutomaticEnv()

	envKeys := []string{
		"SERVER_HOST", "SERVER_PORT", "SERVER_MODE",
		"DATABASE_PATH",
		"CACHE_TYPE", "CACHE_REDIS_ADDR",
		"SECURITY_SECRET",
	}

	for _, key := range envKeys {
		if val := os.Getenv("TECH_" + key); val != "" {
			viperKey := strings.ToLower(strings.ReplaceAll(key, "_", "."))
			m.viper.Set(viperKey, val)
			m.trackConfigItem(viperKey, val, "from environment", SourceEnv)
		}
	}

	m.sources = append(m.sources, SourceEnv)
	logger.Info("Loaded config from environment variables")
}

func (m *Manager) loadDefaults() {
	defaults := map[string]interface{}{
		"server.host":                    "0.0.0.0",
		"server.port":                    8080,
		"server.mode":                    "debug",
		"database.path":                  "techplatform.db",
		"database.max_conns":             100,
		"cache.type":                     "memory",
		"cache.strategy":                 "cache_aside",
		"cache.ttl":                      time.Hour,
		"cache.max_size":                 10000,
		"security.secret":                "default-secret-change-in-production",
		"security.jwt_expire":            3600,
		"security.enable_auth":           true,
		"logging.level":                  "info",
		"logging.path":                   "logs",
		"modules.doc_index.enabled":      true,
		"modules.doc_index.sync_cron":    "0 */5 * * *",
		"modules.scheduler.enabled":      true,
		"modules.scheduler.worker_count": 5,
		"modules.scheduler.queue_size":   1000,
		"modules.notification.enabled":   true,
		"modules.environment.enabled":    true,
		"modules.environment.default_ttl": 24 * time.Hour,
		"modules.environment.max_environments": 10,
		"modules.scaffold.enabled":       true,
		"modules.scaffold.template_path": "templates",
		"modules.scaffold.output_path":   "output",
		"modules.vulnerability.enabled":  true,
		"modules.vulnerability.sync_interval": 24,
	}

	for key, value := range defaults {
		if !m.viper.IsSet(key) {
			m.viper.Set(key, value)
			m.trackConfigItem(key, value, "default value", SourceDefault)
		}
	}

	m.sources = append(m.sources, SourceDefault)
	logger.Info("Loaded default configuration values")
}

func (m *Manager) trackConfigItem(key string, value interface{}, description string, source ConfigSource) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.trackConfigItemLocked(key, value, description, source)
}

func (m *Manager) trackConfigItemLocked(key string, value interface{}, description string, source ConfigSource) {
	if item, exists := m.items[key]; exists {
		oldValue := item.Value
		item.Value = value
		item.UpdatedAt = time.Now()
		item.Version++
		item.Source = source

		go m.notifyListeners(ConfigChangeEvent{
			Key:      key,
			OldValue: oldValue,
			NewValue: value,
			Source:   source,
			Time:     time.Now(),
		})
	} else {
		m.items[key] = &ConfigItem{
			Key:         key,
			Value:       value,
			Description: description,
			Source:      source,
			UpdatedAt:   time.Now(),
			Version:     1,
		}
	}
}

func (m *Manager) setupWatcher(path string) error {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}

	m.watcher = watcher
	dir := filepath.Dir(path)
	if err := watcher.Add(dir); err != nil {
		return err
	}

	go func() {
		for {
			select {
			case event, ok := <-watcher.Events:
				if !ok {
					return
				}
				if event.Op&fsnotify.Write == fsnotify.Write && filepath.Base(event.Name) == filepath.Base(path) {
					logger.Info("Config file changed, reloading...")
					if err := m.viper.ReadInConfig(); err != nil {
						logger.Error("Failed to reload config: %v", err)
					} else {
						m.viper.Unmarshal(m.config)
						logger.Info("Config reloaded successfully")
					}
				}
			case err, ok := <-watcher.Errors:
				if !ok {
					return
				}
				logger.Error("Config watcher error: %v", err)
			}
		}
	}()

	return nil
}

func (m *Manager) startAutoRefresh() {
	ticker := time.NewTicker(m.refreshInterval)
	defer ticker.Stop()

	for {
		select {
		case <-m.ctx.Done():
			return
		case <-ticker.C:
			m.refreshRemoteConfig()
		}
	}
}

func (m *Manager) refreshRemoteConfig() {
	logger.Debug("Refreshing remote configuration")
}

func (m *Manager) GetConfig() *AppConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.config
}

func (m *Manager) Get(key string) interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.Get(key)
}

func (m *Manager) GetString(key string) string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetString(key)
}

func (m *Manager) GetInt(key string) int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetInt(key)
}

func (m *Manager) GetBool(key string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetBool(key)
}

func (m *Manager) GetDuration(key string) time.Duration {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetDuration(key)
}

func (m *Manager) Set(key string, value interface{}, source ConfigSource) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	oldValue := m.viper.Get(key)
	m.viper.Set(key, value)
	m.trackConfigItemLocked(key, value, "dynamic update", source)

	event := ConfigChangeEvent{
		Key:      key,
		OldValue: oldValue,
		NewValue: value,
		Source:   source,
		Time:     time.Now(),
	}
	go m.notifyListeners(event)

	if source == SourceFile && len(m.configFiles) > 0 {
		go m.persistToFile()
	}

	logger.Info("Config updated: %s = %v (source: %s)", key, value, source)
	return nil
}

func (m *Manager) persistToFile() {
	if len(m.configFiles) == 0 {
		return
	}

	settings := m.viper.AllSettings()
	data, err := yaml.Marshal(settings)
	if err != nil {
		logger.Error("Failed to marshal config for persistence: %v", err)
		return
	}

	for _, file := range m.configFiles {
		if err := os.WriteFile(file, data, 0644); err != nil {
			logger.Warn("Failed to persist config to %s: %v", file, err)
		} else {
			logger.Info("Config persisted to %s", file)
			break
		}
	}
}

func (m *Manager) OnChange(keyPattern string, listener ConfigChangeListener) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners[keyPattern] = append(m.listeners[keyPattern], listener)
}

func (m *Manager) notifyListeners(event ConfigChangeEvent) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for pattern, listeners := range m.listeners {
		if matchPattern(pattern, event.Key) {
			for _, l := range listeners {
				go func(listener ConfigChangeListener) {
					defer func() {
						if r := recover(); r != nil {
							logger.Error("Config listener panic: %v", r)
						}
					}()
					listener(event)
				}(l)
			}
		}
	}
}

func matchPattern(pattern, key string) bool {
	if pattern == "*" {
		return true
	}
	if pattern == key {
		return true
	}
	if strings.HasSuffix(pattern, "*") {
		prefix := strings.TrimSuffix(pattern, "*")
		return strings.HasPrefix(key, prefix)
	}
	return false
}

func (m *Manager) GetAllItems() []ConfigItem {
	m.mu.RLock()
	defer m.mu.RUnlock()

	items := make([]ConfigItem, 0, len(m.items))
	for _, item := range m.items {
		items = append(items, *item)
	}
	return items
}

func (m *Manager) GetItem(key string) (*ConfigItem, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	item, exists := m.items[key]
	if !exists {
		return nil, common.ErrNotFound
	}
	return item, nil
}

func (m *Manager) GetSources() []ConfigSource {
	return m.sources
}

func (m *Manager) Export() (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	settings := m.viper.AllSettings()
	data, err := yaml.Marshal(settings)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (m *Manager) Validate() error {
	config := m.GetConfig()
	if config.Server.Port < 1 || config.Server.Port > 65535 {
		return fmt.Errorf("%w: invalid server port", common.ErrConfigInvalid)
	}
	if config.Security.Secret == "default-secret-change-in-production" && config.Server.Mode == "release" {
		logger.Warn("Using default security secret in production mode!")
	}
	return nil
}

func (m *Manager) Close() {
	m.cancel()
	if m.watcher != nil {
		m.watcher.Close()
	}
	logger.Info("Config manager closed")
}

func LoadConfig(path string) (*Manager, error) {
	mgr := NewManager()
	if err := mgr.Load(path); err != nil {
		return nil, err
	}
	if err := mgr.Validate(); err != nil {
		return nil, err
	}
	return mgr, nil
}

func GenerateDefaultConfig() string {
	config := AppConfig{
		Server: ServerConfig{
			Host: "0.0.0.0",
			Port: 8080,
			Mode: "debug",
		},
		Database: DatabaseConfig{
			Path:     "techplatform.db",
			MaxConns: 100,
		},
		Cache: CacheConfig{
			Type:     "memory",
			Strategy: "cache_aside",
			TTL:      time.Hour,
			MaxSize:  10000,
		},
		Security: SecurityConfig{
			Secret:     "change-me-please",
			JWTExpire:  3600,
			EnableAuth: true,
		},
		Logging: LoggingConfig{
			Level: "info",
			Path:  "logs",
		},
		Modules: ModulesConfig{
			DocIndex: DocIndexConfig{
				Enabled:  true,
				SyncCron: "0 */5 * * *",
				Sources: []DocSourceConfig{
					{Type: "local", Path: "./docs", Enabled: true},
				},
				IndexPath: "./index",
			},
			Scheduler: SchedulerConfig{
				Enabled:     true,
				WorkerCount: 5,
				QueueSize:   1000,
			},
			Notification: NotificationConfig{
				Enabled: true,
			},
			Environment: EnvironmentConfig{
				Enabled:         true,
				DefaultTTL:      24 * time.Hour,
				MaxEnvironments: 10,
				ResourceLimit: ResourceLimit{
					CPU:    2.0,
					Memory: 2048,
				},
			},
			Scaffold: ScaffoldConfig{
				Enabled:      true,
				TemplatePath: "./templates",
				OutputPath:   "./output",
			},
			Vulnerability: VulnerabilityConfig{
				Enabled:      true,
				SyncInterval: 24,
			},
		},
	}

	data, _ := yaml.Marshal(config)
	return string(data)
}
