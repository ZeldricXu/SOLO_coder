package config

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/spf13/viper"
)

type SourceType string

const (
	SourceFile     SourceType = "file"
	SourceEnv      SourceType = "env"
	SourceRemote   SourceType = "remote"
	SourceDefaults SourceType = "defaults"
)

type ConfigChange struct {
	Key       string
	OldValue  interface{}
	NewValue  interface{}
	Source    SourceType
	Timestamp time.Time
}

type ChangeCallback func(change ConfigChange)

type Manager struct {
	mu           sync.RWMutex
	viper        *viper.Viper
	configPaths  []string
	configFile   string
	envPrefix    string
	watchers     []string
	callbacks    map[string][]ChangeCallback
	changeChan   chan ConfigChange
	ctx          context.Context
	cancel       context.CancelFunc
	defaults     map[string]interface{}
	remoteConfig *RemoteConfig
}

type RemoteConfig struct {
	Provider string
	Endpoint string
	Path     string
	Interval time.Duration
}

type Option func(*Manager)

func WithConfigFile(path string) Option {
	return func(m *Manager) {
		m.configFile = path
	}
}

func WithConfigPaths(paths []string) Option {
	return func(m *Manager) {
		m.configPaths = paths
	}
}

func WithEnvPrefix(prefix string) Option {
	return func(m *Manager) {
		m.envPrefix = prefix
	}
}

func WithRemoteConfig(rc *RemoteConfig) Option {
	return func(m *Manager) {
		m.remoteConfig = rc
	}
}

func New(opts ...Option) (*Manager, error) {
	ctx, cancel := context.WithCancel(context.Background())
	m := &Manager{
		viper:      viper.New(),
		callbacks:  make(map[string][]ChangeCallback),
		changeChan: make(chan ConfigChange, 100),
		defaults:   make(map[string]interface{}),
		ctx:        ctx,
		cancel:     cancel,
	}

	for _, opt := range opts {
		opt(m)
	}

	m.viper.SetEnvPrefix(m.envPrefix)
	m.viper.AutomaticEnv()
	m.viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_", "-", "_"))

	if err := m.loadDefaults(); err != nil {
		return nil, err
	}

	if err := m.loadFromFile(); err != nil {
		return nil, err
	}

	if m.remoteConfig != nil {
		if err := m.loadFromRemote(); err != nil {
			return nil, err
		}
	}

	return m, nil
}

func (m *Manager) loadDefaults() error {
	m.defaults = map[string]interface{}{
		"server.host":         "0.0.0.0",
		"server.port":         8080,
		"server.mode":         "release",
		"log.level":           "info",
		"log.path":            "./logs/app.log",
		"log.max_size":        100,
		"log.max_backups":     5,
		"log.max_age":         30,
		"log.compress":        true,
		"log.enable_console":  true,
		"log.enable_file":     true,
		"monitoring.interval": 30,
	}

	for k, v := range m.defaults {
		m.viper.SetDefault(k, v)
	}
	return nil
}

func (m *Manager) loadFromFile() error {
	if m.configFile != "" {
		m.viper.SetConfigFile(m.configFile)
	} else {
		m.viper.SetConfigName("config")
		m.viper.SetConfigType("yaml")
		for _, path := range m.configPaths {
			m.viper.AddConfigPath(path)
		}
		m.viper.AddConfigPath(".")
		m.viper.AddConfigPath("./configs")
	}

	if err := m.viper.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); ok {
			return nil
		}
		return fmt.Errorf("read config file: %w", err)
	}

	m.viper.WatchConfig()
	m.viper.OnConfigChange(func(e fsnotify.Event) {
		m.handleConfigChange(e)
	})

	return nil
}

func (m *Manager) loadFromRemote() error {
	if m.remoteConfig == nil {
		return nil
	}

	go m.startRemotePoller()
	return nil
}

func (m *Manager) startRemotePoller() {
	if m.remoteConfig.Interval <= 0 {
		m.remoteConfig.Interval = 30 * time.Second
	}

	ticker := time.NewTicker(m.remoteConfig.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-m.ctx.Done():
			return
		case <-ticker.C:
			m.pollRemoteConfig()
		}
	}
}

func (m *Manager) pollRemoteConfig() {
	// 模拟远程配置拉取
	// 实际项目中可以接入 etcd, consul, nacos 等
}

func (m *Manager) handleConfigChange(e fsnotify.Event) {
	select {
	case m.changeChan <- ConfigChange{
		Key:       e.Name,
		Source:    SourceFile,
		Timestamp: time.Now(),
	}:
	default:
	}
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

func (m *Manager) GetFloat64(key string) float64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetFloat64(key)
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

func (m *Manager) GetStringSlice(key string) []string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetStringSlice(key)
}

func (m *Manager) GetStringMap(key string) map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetStringMap(key)
}

func (m *Manager) GetStringMapString(key string) map[string]string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetStringMapString(key)
}

func (m *Manager) UnmarshalKey(key string, rawVal interface{}) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.UnmarshalKey(key, rawVal)
}

func (m *Manager) Unmarshal(rawVal interface{}) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.Unmarshal(rawVal)
}

func (m *Manager) Set(key string, value interface{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	oldValue := m.viper.Get(key)
	m.viper.Set(key, value)

	select {
	case m.changeChan <- ConfigChange{
		Key:       key,
		OldValue:  oldValue,
		NewValue:  value,
		Source:    SourceDefaults,
		Timestamp: time.Now(),
	}:
	default:
	}

	m.notifyCallbacks(key, oldValue, value, SourceDefaults)
}

func (m *Manager) SetDefault(key string, value interface{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.viper.SetDefault(key, value)
	m.defaults[key] = value
}

func (m *Manager) Watch(key string, callback ChangeCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callbacks[key] = append(m.callbacks[key], callback)
}

func (m *Manager) WatchAll(callback ChangeCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callbacks["*"] = append(m.callbacks["*"], callback)
}

func (m *Manager) notifyCallbacks(key string, oldVal, newVal interface{}, source SourceType) {
	change := ConfigChange{
		Key:       key,
		OldValue:  oldVal,
		NewValue:  newVal,
		Source:    source,
		Timestamp: time.Now(),
	}

	for pattern, callbacks := range m.callbacks {
		if pattern == "*" || strings.HasPrefix(key, pattern) {
			for _, cb := range callbacks {
				go cb(change)
			}
		}
	}
}

func (m *Manager) Changes() <-chan ConfigChange {
	return m.changeChan
}

func (m *Manager) AllSettings() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.AllSettings()
}

func (m *Manager) IsSet(key string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.IsSet(key)
}

func (m *Manager) SaveConfig() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.configFile != "" {
		if err := os.MkdirAll(filepath.Dir(m.configFile), 0755); err != nil {
			return err
		}
		return m.viper.WriteConfigAs(m.configFile)
	}
	return m.viper.WriteConfig()
}

func (m *Manager) Close() {
	m.cancel()
	close(m.changeChan)
}
