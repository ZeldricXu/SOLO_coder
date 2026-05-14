package config

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sync"

	"gopkg.in/yaml.v3"
)

type ServerConfig struct {
	HTTPAddress    string `yaml:"http_address"`
	HTTPSAddress   string `yaml:"https_address"`
	SOCKS5Address  string `yaml:"socks5_address"`
	APIAddress     string `yaml:"api_address"`
	MaxConnections int    `yaml:"max_connections"`
	ConnectTimeout int    `yaml:"connect_timeout"`
	ReadTimeout    int    `yaml:"read_timeout"`
	WriteTimeout   int    `yaml:"write_timeout"`
}

type TargetTimeoutConfig struct {
	TargetPattern      string `yaml:"target_pattern" json:"target_pattern"`
	ConnectTimeout     int    `yaml:"connect_timeout" json:"connect_timeout"`
	ReadTimeout        int    `yaml:"read_timeout" json:"read_timeout"`
	WriteTimeout       int    `yaml:"write_timeout" json:"write_timeout"`
	IdleTimeout        int    `yaml:"idle_timeout" json:"idle_timeout"`
	HoldTimeout        int    `yaml:"hold_timeout" json:"hold_timeout"`
	MaxConnections     int    `yaml:"max_connections" json:"max_connections"`
	regexp             *regexp.Regexp
}

type ForwardRule struct {
	RuleID           string   `yaml:"rule_id" json:"rule_id"`
	TargetPattern    string   `yaml:"target_pattern" json:"target_pattern"`
	AllowedProtocols []string `yaml:"allowed_protocols" json:"allowed_protocols"`
	ForwardMode      string   `yaml:"forward_mode" json:"forward_mode"`
	RateLimit        int      `yaml:"rate_limit" json:"rate_limit"`
	Enabled          bool     `yaml:"enabled" json:"enabled"`
	regexp           *regexp.Regexp
}

type PoolConfig struct {
	MaxConnections      int `yaml:"max_connections"`
	MinIdleConnections  int `yaml:"min_idle_connections"`
	IdleTimeout         int `yaml:"idle_timeout"`
	ConnectionTimeout   int `yaml:"connection_timeout"`
	HoldTimeout         int `yaml:"hold_timeout"`
	ReclaimInterval     int `yaml:"reclaim_interval"`
	MaxReconnectRate    int `yaml:"max_reconnect_rate"`
	ReconnectWindow     int `yaml:"reconnect_window"`
}

type HealthConfig struct {
	Enabled          bool `yaml:"enabled"`
	Interval         int  `yaml:"interval"`
	Timeout          int  `yaml:"timeout"`
	RetryCount       int  `yaml:"retry_count"`
	FailureThreshold int  `yaml:"failure_threshold"`
	AutoClean        bool `yaml:"auto_clean"`
	BatchCleanSize   int  `yaml:"batch_clean_size"`
}

type StatsConfig struct {
	Enabled        bool   `yaml:"enabled"`
	FilePath       string `yaml:"file_path"`
	Interval       int    `yaml:"interval_seconds"`
	MaxRecords     int    `yaml:"max_records"`
	BufferSize     int    `yaml:"buffer_size"`
	FlushInterval  int    `yaml:"flush_interval"`
}

type LogConfig struct {
	Level      string `yaml:"level"`
	FilePath   string `yaml:"file_path"`
	MaxSize    int    `yaml:"max_size"`
	MaxBackups int    `yaml:"max_backups"`
	MaxAge     int    `yaml:"max_age"`
}

type AppConfig struct {
	Server         ServerConfig           `yaml:"server"`
	Rules          []ForwardRule          `yaml:"rules"`
	TargetTimeouts []TargetTimeoutConfig  `yaml:"target_timeouts"`
	Pool           PoolConfig             `yaml:"pool"`
	Health         HealthConfig           `yaml:"health"`
	Stats          StatsConfig            `yaml:"stats"`
	Log            LogConfig              `yaml:"log"`
}

type ConfigManager struct {
	config         *AppConfig
	rules          map[string]*ForwardRule
	targetTimeouts map[string]*TargetTimeoutConfig
	mu             sync.RWMutex
}

func NewConfigManager() *ConfigManager {
	return &ConfigManager{
		rules:          make(map[string]*ForwardRule),
		targetTimeouts: make(map[string]*TargetTimeoutConfig),
	}
}

func (cm *ConfigManager) LoadConfig(configPath string) error {
	absPath, err := filepath.Abs(configPath)
	if err != nil {
		return fmt.Errorf("failed to get absolute path: %w", err)
	}

	data, err := os.ReadFile(absPath)
	if err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	var config AppConfig
	if err := yaml.Unmarshal(data, &config); err != nil {
		return fmt.Errorf("failed to parse config file: %w", err)
	}

	if err := cm.validateConfig(&config); err != nil {
		return fmt.Errorf("config validation failed: %w", err)
	}

	cm.mu.Lock()
	defer cm.mu.Unlock()

	cm.config = &config
	cm.rules = make(map[string]*ForwardRule)
	cm.targetTimeouts = make(map[string]*TargetTimeoutConfig)

	for i := range config.Rules {
		rule := &config.Rules[i]
		if err := cm.compileRulePattern(rule); err != nil {
			return fmt.Errorf("failed to compile rule pattern %s: %w", rule.TargetPattern, err)
		}
		cm.rules[rule.RuleID] = rule
	}

	for i := range config.TargetTimeouts {
		timeoutCfg := &config.TargetTimeouts[i]
		if err := cm.compileTimeoutPattern(timeoutCfg); err != nil {
			return fmt.Errorf("failed to compile timeout pattern %s: %w", timeoutCfg.TargetPattern, err)
		}
		cm.targetTimeouts[timeoutCfg.TargetPattern] = timeoutCfg
	}

	return nil
}

func (cm *ConfigManager) validateConfig(config *AppConfig) error {
	if config.Server.HTTPAddress == "" && config.Server.HTTPSAddress == "" && config.Server.SOCKS5Address == "" {
		return fmt.Errorf("at least one proxy server address must be configured")
	}

	if config.Server.MaxConnections <= 0 {
		config.Server.MaxConnections = 1000
	}

	if config.Server.ConnectTimeout <= 0 {
		config.Server.ConnectTimeout = 30
	}

	if config.Server.ReadTimeout <= 0 {
		config.Server.ReadTimeout = 60
	}

	if config.Server.WriteTimeout <= 0 {
		config.Server.WriteTimeout = 60
	}

	if config.Pool.MaxConnections <= 0 {
		config.Pool.MaxConnections = 50
	}

	if config.Pool.MinIdleConnections < 0 {
		config.Pool.MinIdleConnections = 2
	}

	if config.Pool.IdleTimeout <= 0 {
		config.Pool.IdleTimeout = 300
	}

	if config.Pool.ConnectionTimeout <= 0 {
		config.Pool.ConnectionTimeout = 30
	}

	if config.Pool.HoldTimeout <= 0 {
		config.Pool.HoldTimeout = 600
	}

	if config.Pool.ReclaimInterval <= 0 {
		config.Pool.ReclaimInterval = 30
	}

	if config.Pool.MaxReconnectRate <= 0 {
		config.Pool.MaxReconnectRate = 10
	}

	if config.Pool.ReconnectWindow <= 0 {
		config.Pool.ReconnectWindow = 1
	}

	if config.Health.Interval <= 0 {
		config.Health.Interval = 30
	}

	if config.Health.Timeout <= 0 {
		config.Health.Timeout = 5
	}

	if config.Health.RetryCount <= 0 {
		config.Health.RetryCount = 3
	}

	if config.Health.FailureThreshold <= 0 {
		config.Health.FailureThreshold = 3
	}

	if config.Health.BatchCleanSize <= 0 {
		config.Health.BatchCleanSize = 100
	}

	if config.Stats.BufferSize <= 0 {
		config.Stats.BufferSize = 1000
	}

	if config.Stats.FlushInterval <= 0 {
		config.Stats.FlushInterval = 10
	}

	for i := range config.TargetTimeouts {
		if config.TargetTimeouts[i].ConnectTimeout <= 0 {
			config.TargetTimeouts[i].ConnectTimeout = config.Pool.ConnectionTimeout
		}
		if config.TargetTimeouts[i].ReadTimeout <= 0 {
			config.TargetTimeouts[i].ReadTimeout = config.Server.ReadTimeout
		}
		if config.TargetTimeouts[i].WriteTimeout <= 0 {
			config.TargetTimeouts[i].WriteTimeout = config.Server.WriteTimeout
		}
		if config.TargetTimeouts[i].IdleTimeout <= 0 {
			config.TargetTimeouts[i].IdleTimeout = config.Pool.IdleTimeout
		}
		if config.TargetTimeouts[i].HoldTimeout <= 0 {
			config.TargetTimeouts[i].HoldTimeout = config.Pool.HoldTimeout
		}
		if config.TargetTimeouts[i].MaxConnections <= 0 {
			config.TargetTimeouts[i].MaxConnections = config.Pool.MaxConnections
		}
	}

	return nil
}

func (cm *ConfigManager) compileRulePattern(rule *ForwardRule) error {
	pattern := regexp.QuoteMeta(rule.TargetPattern)
	pattern = regexp.MustCompile(`\\\*`).ReplaceAllString(pattern, ".*")
	pattern = "^" + pattern + "$"

	re, err := regexp.Compile(pattern)
	if err != nil {
		return err
	}
	rule.regexp = re
	return nil
}

func (cm *ConfigManager) compileTimeoutPattern(cfg *TargetTimeoutConfig) error {
	pattern := regexp.QuoteMeta(cfg.TargetPattern)
	pattern = regexp.MustCompile(`\\\*`).ReplaceAllString(pattern, ".*")
	pattern = "^" + pattern + "$"

	re, err := regexp.Compile(pattern)
	if err != nil {
		return err
	}
	cfg.regexp = re
	return nil
}

func (cm *ConfigManager) GetConfig() *AppConfig {
	cm.mu.RLock()
	defer cm.mu.RUnlock()
	return cm.config
}

func (cm *ConfigManager) GetRuleByTarget(targetHost string, protocol string) *ForwardRule {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	for _, rule := range cm.config.Rules {
		if !rule.Enabled {
			continue
		}

		if rule.regexp.MatchString(targetHost) {
			if len(rule.AllowedProtocols) == 0 {
				return rule
			}
			for _, p := range rule.AllowedProtocols {
				if p == protocol {
					return rule
				}
			}
		}
	}
	return nil
}

func (cm *ConfigManager) GetTimeoutConfig(targetHost string) *TargetTimeoutConfig {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	if cm.config == nil {
		return nil
	}

	for _, cfg := range cm.config.TargetTimeouts {
		if cfg.regexp != nil && cfg.regexp.MatchString(targetHost) {
			cfgCopy := *cfg
			return &cfgCopy
		}
	}
	return nil
}

func (cm *ConfigManager) GetEffectivePoolConfig(targetHost string) *PoolConfig {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	if cm.config == nil {
		return nil
	}

	baseConfig := cm.config.Pool

	for _, cfg := range cm.config.TargetTimeouts {
		if cfg.regexp != nil && cfg.regexp.MatchString(targetHost) {
			effectiveConfig := baseConfig
			if cfg.ConnectTimeout > 0 {
				effectiveConfig.ConnectionTimeout = cfg.ConnectTimeout
			}
			if cfg.IdleTimeout > 0 {
				effectiveConfig.IdleTimeout = cfg.IdleTimeout
			}
			if cfg.HoldTimeout > 0 {
				effectiveConfig.HoldTimeout = cfg.HoldTimeout
			}
			if cfg.MaxConnections > 0 {
				effectiveConfig.MaxConnections = cfg.MaxConnections
			}
			configCopy := effectiveConfig
			return &configCopy
		}
	}

	configCopy := baseConfig
	return &configCopy
}

func (cm *ConfigManager) AddRule(rule *ForwardRule) error {
	if rule.RuleID == "" {
		return fmt.Errorf("rule_id is required")
	}

	if rule.TargetPattern == "" {
		return fmt.Errorf("target_pattern is required")
	}

	if err := cm.compileRulePattern(rule); err != nil {
		return err
	}

	cm.mu.Lock()
	defer cm.mu.Unlock()

	if _, exists := cm.rules[rule.RuleID]; exists {
		return fmt.Errorf("rule with id %s already exists", rule.RuleID)
	}

	cm.config.Rules = append(cm.config.Rules, *rule)
	cm.rules[rule.RuleID] = &cm.config.Rules[len(cm.config.Rules)-1]

	return nil
}

func (cm *ConfigManager) UpdateRule(ruleID string, rule *ForwardRule) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	existingRule, exists := cm.rules[ruleID]
	if !exists {
		return fmt.Errorf("rule with id %s not found", ruleID)
	}

	if rule.TargetPattern != "" && rule.TargetPattern != existingRule.TargetPattern {
		if err := cm.compileRulePattern(rule); err != nil {
			return err
		}
	}

	if rule.TargetPattern != "" {
		existingRule.TargetPattern = rule.TargetPattern
		if rule.regexp != nil {
			existingRule.regexp = rule.regexp
		}
	}
	if rule.AllowedProtocols != nil {
		existingRule.AllowedProtocols = rule.AllowedProtocols
	}
	if rule.ForwardMode != "" {
		existingRule.ForwardMode = rule.ForwardMode
	}
	if rule.RateLimit > 0 {
		existingRule.RateLimit = rule.RateLimit
	}
	existingRule.Enabled = rule.Enabled

	return nil
}

func (cm *ConfigManager) DeleteRule(ruleID string) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	if _, exists := cm.rules[ruleID]; !exists {
		return fmt.Errorf("rule with id %s not found", ruleID)
	}

	for i, rule := range cm.config.Rules {
		if rule.RuleID == ruleID {
			cm.config.Rules = append(cm.config.Rules[:i], cm.config.Rules[i+1:]...)
			break
		}
	}

	delete(cm.rules, ruleID)
	return nil
}

func (cm *ConfigManager) GetRule(ruleID string) (*ForwardRule, bool) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	rule, exists := cm.rules[ruleID]
	if !exists {
		return nil, false
	}

	ruleCopy := *rule
	return &ruleCopy, true
}

func (cm *ConfigManager) GetAllRules() []ForwardRule {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	rules := make([]ForwardRule, len(cm.config.Rules))
	for i, rule := range cm.config.Rules {
		rules[i] = rule
	}
	return rules
}

func (cm *ConfigManager) AddTimeoutConfig(cfg *TargetTimeoutConfig) error {
	if cfg.TargetPattern == "" {
		return fmt.Errorf("target_pattern is required")
	}

	if err := cm.compileTimeoutPattern(cfg); err != nil {
		return err
	}

	cm.mu.Lock()
	defer cm.mu.Unlock()

	cm.config.TargetTimeouts = append(cm.config.TargetTimeouts, *cfg)
	cm.targetTimeouts[cfg.TargetPattern] = &cm.config.TargetTimeouts[len(cm.config.TargetTimeouts)-1]

	return nil
}

func (cm *ConfigManager) GetAllTimeoutConfigs() []TargetTimeoutConfig {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	configs := make([]TargetTimeoutConfig, len(cm.config.TargetTimeouts))
	for i, cfg := range cm.config.TargetTimeouts {
		configs[i] = cfg
	}
	return configs
}

func (cm *ConfigManager) SaveConfig(configPath string) error {
	cm.mu.RLock()
	defer cm.mu.RUnlock()

	data, err := yaml.Marshal(cm.config)
	if err != nil {
		return fmt.Errorf("failed to marshal config: %w", err)
	}

	absPath, err := filepath.Abs(configPath)
	if err != nil {
		return fmt.Errorf("failed to get absolute path: %w", err)
	}

	if err := os.WriteFile(absPath, data, 0644); err != nil {
		return fmt.Errorf("failed to write config file: %w", err)
	}

	return nil
}
