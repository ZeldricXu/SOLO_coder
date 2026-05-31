package config

import (
	"context"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/spf13/viper"
	"session172/internal/logger"
	"session172/pkg/models"
	"session172/pkg/utils"
)

type Manager struct {
	mu           sync.RWMutex
	viper        *viper.Viper
	configs      map[string]*models.Config
	watchers     []*fsnotify.Watcher
	callbacks    map[string][]func(*models.Config)
	configDirs   []string
	namespace    string
}

type Source struct {
	Type     string `json:"type"`
	Path     string `json:"path,omitempty"`
	URL      string `json:"url,omitempty"`
	Format   string `json:"format"`
	Priority int    `json:"priority"`
}

var (
	instance *Manager
	once     sync.Once
)

func NewManager(namespace string, sources []Source) *Manager {
	once.Do(func() {
		instance = &Manager{
			viper:      viper.New(),
			configs:    make(map[string]*models.Config),
			callbacks:  make(map[string][]func(*models.Config)),
			configDirs: make([]string, 0),
			namespace:  namespace,
		}
		instance.init(sources)
	})
	return instance
}

func GetManager() *Manager {
	if instance == nil {
		return NewManager("default", nil)
	}
	return instance
}

func (m *Manager) init(sources []Source) {
	m.viper.SetEnvPrefix("APP")
	m.viper.AutomaticEnv()
	m.viper.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	if len(sources) == 0 {
		sources = []Source{
			{Type: "file", Path: "config.yaml", Format: "yaml", Priority: 1},
			{Type: "file", Path: "config.json", Format: "json", Priority: 2},
			{Type: "env", Format: "env", Priority: 3},
		}
	}

	for _, src := range sources {
		m.loadSource(src)
	}

	m.watchConfigFiles()
}

func (m *Manager) loadSource(src Source) {
	switch src.Type {
	case "file":
		m.loadFile(src)
	case "env":
		logger.Info("Environment variables loaded")
	}
}

func (m *Manager) loadFile(src Source) {
	if _, err := os.Stat(src.Path); os.IsNotExist(err) {
		logger.Warnf("Config file not found: %s", src.Path)
		return
	}

	m.viper.SetConfigFile(src.Path)
	m.viper.SetConfigType(src.Format)

	if err := m.viper.MergeInConfig(); err != nil {
		logger.Errorf("Failed to load config %s: %v", src.Path, err)
		return
	}

	dir := filepath.Dir(src.Path)
	if !utils.ContainsString(m.configDirs, dir) {
		m.configDirs = append(m.configDirs, dir)
	}

	cfg := &models.Config{
		ConfigID:  utils.GenerateID("cfg"),
		Namespace: m.namespace,
		Version:   time.Now().Unix(),
		Parameters: m.getAllSettings(),
		Enabled:   true,
		AppliedAt: utils.NowPtr(),
		CreatedAt: time.Now(),
	}

	m.mu.Lock()
	m.configs[src.Path] = cfg
	m.mu.Unlock()

	logger.Infof("Config loaded: %s (version: %d)", src.Path, cfg.Version)
}

func (m *Manager) getAllSettings() map[string]interface{} {
	settings := m.viper.AllSettings()
	result := make(map[string]interface{})
	for k, v := range settings {
		result[k] = v
	}
	return result
}

func (m *Manager) watchConfigFiles() {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		logger.Errorf("Failed to create watcher: %v", err)
		return
	}

	m.watchers = append(m.watchers, watcher)

	go func() {
		for {
			select {
			case event, ok := <-watcher.Events:
				if !ok {
					return
				}
				if event.Op&fsnotify.Write == fsnotify.Write {
					logger.Infof("Config file modified: %s", event.Name)
					m.reloadConfig(event.Name)
				}
			case err, ok := <-watcher.Errors:
				if !ok {
					return
				}
				logger.Errorf("Watcher error: %v", err)
			}
		}
	}()

	for _, dir := range m.configDirs {
		if err := watcher.Add(dir); err != nil {
			logger.Errorf("Failed to watch directory %s: %v", dir, err)
		}
	}
}

func (m *Manager) reloadConfig(path string) {
	m.viper.SetConfigFile(path)
	if err := m.viper.MergeInConfig(); err != nil {
		logger.Errorf("Failed to reload config %s: %v", path, err)
		return
	}

	cfg := &models.Config{
		ConfigID:   utils.GenerateID("cfg"),
		Namespace:  m.namespace,
		Version:    time.Now().Unix(),
		Parameters: m.getAllSettings(),
		Enabled:    true,
		AppliedAt:  utils.NowPtr(),
		CreatedAt:  time.Now(),
	}

	m.mu.Lock()
	m.configs[path] = cfg
	m.mu.Unlock()

	m.notifyCallbacks(cfg)
	logger.Infof("Config reloaded: %s (version: %d)", path, cfg.Version)
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

func (m *Manager) GetFloat64(key string) float64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.viper.GetFloat64(key)
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

func (m *Manager) Set(key string, value interface{}) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.viper.Set(key, value)
}

func (m *Manager) OnChange(callback func(*models.Config)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callbacks["*"] = append(m.callbacks["*"], callback)
}

func (m *Manager) OnChangeFor(path string, callback func(*models.Config)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callbacks[path] = append(m.callbacks[path], callback)
}

func (m *Manager) notifyCallbacks(cfg *models.Config) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, cb := range m.callbacks["*"] {
		go cb(cfg)
	}

	for path, cbs := range m.callbacks {
		if path != "*" {
			for _, cb := range cbs {
				go cb(cfg)
			}
		}
	}
}

func (m *Manager) GetConfig(path string) (*models.Config, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	cfg, ok := m.configs[path]
	return cfg, ok
}

func (m *Manager) GetAllConfigs() map[string]*models.Config {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make(map[string]*models.Config)
	for k, v := range m.configs {
		result[k] = v
	}
	return result
}

func (m *Manager) LoadFromReader(data []byte, format string) error {
	m.viper.SetConfigType(format)
	return m.viper.MergeConfig(strings.NewReader(string(data)))
}

func (m *Manager) LoadFromURL(ctx context.Context, url string, format string) error {
	return fmt.Errorf("not implemented")
}

func (m *Manager) SaveToFile(path string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	settings := m.getAllSettings()
	data, err := ioutil.ReadFile(path)
	if err == nil && len(data) > 0 {
		var existing map[string]interface{}
		if err := utils.FromJSON(string(data), &existing); err == nil {
			for k, v := range existing {
				if _, ok := settings[k]; !ok {
					settings[k] = v
				}
			}
		}
	}

	return ioutil.WriteFile(path, []byte(utils.ToJSON(settings)), 0644)
}

func (m *Manager) Close() {
	for _, w := range m.watchers {
		_ = w.Close()
	}
}
