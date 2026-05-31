package ota

import (
	"context"
	"encoding/json"
	"time"
)

const (
	ConfigKeyPrefix = "ota:config"
	DefaultProfile  = "default"
)

type UpgradeStrategy string

const (
	StrategyBatch       UpgradeStrategy = "batch"
	StrategyRolling     UpgradeStrategy = "rolling"
	StrategyCanary      UpgradeStrategy = "canary"
	StrategyImmediate   UpgradeStrategy = "immediate"
)

type OTAConfig struct {
	Profile              string                 `json:"profile"`
	DefaultBatchSize     int                    `json:"default_batch_size"`
	BatchInterval        time.Duration          `json:"batch_interval"`
	DownloadTimeout      time.Duration          `json:"download_timeout"`
	InstallTimeout       time.Duration          `json:"install_timeout"`
	DefaultFailureThreshold float64            `json:"default_failure_threshold"`
	DefaultAutoRollback  bool                   `json:"default_auto_rollback"`
	MaxConcurrentDevices int                    `json:"max_concurrent_devices"`
	RetryInterval        time.Duration          `json:"retry_interval"`
	MaxRetries           int                    `json:"max_retries"`
	Strategies           map[string]StrategyConfig `json:"strategies"`
	DeviceTypeOverrides  map[string]DeviceTypeConfig `json:"device_type_overrides"`
	CreatedAt            time.Time              `json:"created_at"`
	UpdatedAt            time.Time              `json:"updated_at"`
}

type StrategyConfig struct {
	BatchSize        int           `json:"batch_size"`
	BatchInterval    time.Duration `json:"batch_interval"`
	FailureThreshold float64       `json:"failure_threshold"`
	AutoRollback     bool          `json:"auto_rollback"`
	CanaryPercentage float64       `json:"canary_percentage,omitempty"`
	CanaryDuration   time.Duration `json:"canary_duration,omitempty"`
}

type DeviceTypeConfig struct {
	DefaultBatchSize int           `json:"default_batch_size"`
	DownloadTimeout  time.Duration `json:"download_timeout"`
	InstallTimeout   time.Duration `json:"install_timeout"`
	MaxRetries       int           `json:"max_retries"`
}

type ConfigRepository interface {
	Save(ctx context.Context, profile string, config *OTAConfig) error
	Get(ctx context.Context, profile string) (*OTAConfig, error)
	List(ctx context.Context) ([]string, error)
	Delete(ctx context.Context, profile string) error
}

type ConfigManager interface {
	GetConfig(ctx context.Context, profile string) (*OTAConfig, error)
	SaveConfig(ctx context.Context, profile string, config *OTAConfig) error
	UpdateConfig(ctx context.Context, profile string, updates map[string]interface{}) (*OTAConfig, error)
	ListProfiles(ctx context.Context) ([]string, error)
	DeleteProfile(ctx context.Context, profile string) error
	GetEffectiveConfig(ctx context.Context, profile string, deviceType string) *OTAConfig
	Watch(ctx context.Context, profile string) <-chan *OTAConfig
}

func DefaultConfig() *OTAConfig {
	return &OTAConfig{
		Profile:              DefaultProfile,
		DefaultBatchSize:     10,
		BatchInterval:        10 * time.Second,
		DownloadTimeout:      30 * time.Minute,
		InstallTimeout:       15 * time.Minute,
		DefaultFailureThreshold: 0.2,
		DefaultAutoRollback:  true,
		MaxConcurrentDevices: 100,
		RetryInterval:        5 * time.Minute,
		MaxRetries:           3,
		Strategies: map[string]StrategyConfig{
			string(StrategyBatch): {
				BatchSize:        10,
				BatchInterval:    10 * time.Second,
				FailureThreshold: 0.2,
				AutoRollback:     true,
			},
			string(StrategyRolling): {
				BatchSize:        5,
				BatchInterval:    30 * time.Second,
				FailureThreshold: 0.1,
				AutoRollback:     true,
			},
			string(StrategyCanary): {
				BatchSize:        1,
				BatchInterval:    60 * time.Second,
				FailureThreshold: 0.0,
				AutoRollback:     true,
				CanaryPercentage: 10.0,
				CanaryDuration:   2 * time.Hour,
			},
			string(StrategyImmediate): {
				BatchSize:        1000,
				BatchInterval:    0,
				FailureThreshold: 0.5,
				AutoRollback:     false,
			},
		},
		DeviceTypeOverrides: map[string]DeviceTypeConfig{},
		CreatedAt:           time.Now(),
		UpdatedAt:           time.Now(),
	}
}

func (c *OTAConfig) ToJSON() string {
	data, _ := json.Marshal(c)
	return string(data)
}

func (c *OTAConfig) Clone() *OTAConfig {
	clone := *c
	clone.Strategies = make(map[string]StrategyConfig, len(c.Strategies))
	for k, v := range c.Strategies {
		clone.Strategies[k] = v
	}
	clone.DeviceTypeOverrides = make(map[string]DeviceTypeConfig, len(c.DeviceTypeOverrides))
	for k, v := range c.DeviceTypeOverrides {
		clone.DeviceTypeOverrides[k] = v
	}
	return &clone
}
