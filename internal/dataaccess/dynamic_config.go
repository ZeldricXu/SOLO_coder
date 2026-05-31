package dataaccess

import (
	"context"
	"fmt"
	"sync"
	"time"

	"gorm.io/gorm"
	"session172/internal/config"
	applogger "session172/internal/logger"
)

type Scenario string

const (
	ScenarioDefault     Scenario = "default"
	ScenarioHighLoad    Scenario = "high_load"
	ScenarioLowLatency  Scenario = "low_latency"
	ScenarioBatch       Scenario = "batch"
	ScenarioMaintenance Scenario = "maintenance"
)

type DynamicConfig struct {
	mu             sync.RWMutex
	currentScenario Scenario
	scenarios      map[Scenario]*PoolConfig
	activeConfig   *PoolConfig
	configManager  *config.Manager
	listeners      []func(old, new *PoolConfig)
}

var (
	dcInstance *DynamicConfig
	dcOnce     sync.Once
)

func NewDynamicConfig() *DynamicConfig {
	dcOnce.Do(func() {
		dcInstance = &DynamicConfig{
			currentScenario: ScenarioDefault,
			scenarios:       make(map[Scenario]*PoolConfig),
			listeners:       make([]func(old, new *PoolConfig), 0),
		}
		dcInstance.initDefaultScenarios()
		dcInstance.watchConfigChanges()
	})
	return dcInstance
}

func GetDynamicConfig() *DynamicConfig {
	if dcInstance == nil {
		return NewDynamicConfig()
	}
	return dcInstance
}

func (dc *DynamicConfig) initDefaultScenarios() {
	dc.scenarios[ScenarioDefault] = &PoolConfig{
		MaxOpenConns:    20,
		MaxIdleConns:    10,
		ConnMaxLifetime: time.Hour,
		ConnMaxIdleTime: 30 * time.Minute,
		SlowThreshold:   200 * time.Millisecond,
	}

	dc.scenarios[ScenarioHighLoad] = &PoolConfig{
		MaxOpenConns:    100,
		MaxIdleConns:    50,
		ConnMaxLifetime: 30 * time.Minute,
		ConnMaxIdleTime: 10 * time.Minute,
		SlowThreshold:   500 * time.Millisecond,
	}

	dc.scenarios[ScenarioLowLatency] = &PoolConfig{
		MaxOpenConns:    50,
		MaxIdleConns:    30,
		ConnMaxLifetime: 2 * time.Hour,
		ConnMaxIdleTime: 1 * time.Hour,
		SlowThreshold:   50 * time.Millisecond,
	}

	dc.scenarios[ScenarioBatch] = &PoolConfig{
		MaxOpenConns:    10,
		MaxIdleConns:    5,
		ConnMaxLifetime: 4 * time.Hour,
		ConnMaxIdleTime: 2 * time.Hour,
		SlowThreshold:   2 * time.Second,
	}

	dc.scenarios[ScenarioMaintenance] = &PoolConfig{
		MaxOpenConns:    5,
		MaxIdleConns:    2,
		ConnMaxLifetime: 10 * time.Minute,
		ConnMaxIdleTime: 5 * time.Minute,
		SlowThreshold:   5 * time.Second,
	}

	dc.activeConfig = dc.scenarios[ScenarioDefault]
}

func (dc *DynamicConfig) SetScenario(scenario Scenario) error {
	dc.mu.Lock()
	defer dc.mu.Unlock()

	cfg, exists := dc.scenarios[scenario]
	if !exists {
		return fmt.Errorf("unknown scenario: %s", scenario)
	}

	oldConfig := dc.activeConfig
	dc.currentScenario = scenario
	dc.activeConfig = cfg

	applogger.Infof("Pool scenario changed: %s -> %s", oldConfig, scenario)

	go dc.applyConfig(oldConfig, cfg)

	for _, listener := range dc.listeners {
		go listener(oldConfig, cfg)
	}

	return nil
}

func (dc *DynamicConfig) GetScenario() Scenario {
	dc.mu.RLock()
	defer dc.mu.RUnlock()
	return dc.currentScenario
}

func (dc *DynamicConfig) GetActiveConfig() *PoolConfig {
	dc.mu.RLock()
	defer dc.mu.RUnlock()
	return dc.activeConfig
}

func (dc *DynamicConfig) RegisterScenario(scenario Scenario, cfg *PoolConfig) error {
	dc.mu.Lock()
	defer dc.mu.Unlock()

	if _, exists := dc.scenarios[scenario]; exists {
		return fmt.Errorf("scenario already exists: %s", scenario)
	}

	dc.scenarios[scenario] = cfg
	applogger.Infof("New scenario registered: %s", scenario)
	return nil
}

func (dc *DynamicConfig) UpdateScenarioConfig(scenario Scenario, cfg *PoolConfig) error {
	dc.mu.Lock()
	defer dc.mu.Unlock()

	if _, exists := dc.scenarios[scenario]; !exists {
		return fmt.Errorf("scenario not found: %s", scenario)
	}

	dc.scenarios[scenario] = cfg

	if dc.currentScenario == scenario {
		oldConfig := dc.activeConfig
		dc.activeConfig = cfg
		go dc.applyConfig(oldConfig, cfg)
	}

	applogger.Infof("Scenario config updated: %s", scenario)
	return nil
}

func (dc *DynamicConfig) GetScenarios() map[Scenario]*PoolConfig {
	dc.mu.RLock()
	defer dc.mu.RUnlock()

	result := make(map[Scenario]*PoolConfig)
	for k, v := range dc.scenarios {
		result[k] = v
	}
	return result
}

func (dc *DynamicConfig) applyConfig(old, new *PoolConfig) {
	pool := GetPool()
	if pool == nil {
		applogger.Warn("Connection pool not initialized, cannot apply dynamic config")
		return
	}

	sqlDB, err := pool.db.DB()
	if err != nil {
		applogger.Errorf("Failed to get sql DB: %v", err)
		return
	}

	sqlDB.SetMaxOpenConns(new.MaxOpenConns)
	sqlDB.SetMaxIdleConns(new.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(new.ConnMaxLifetime)
	sqlDB.SetConnMaxIdleTime(new.ConnMaxIdleTime)

	pool.mu.Lock()
	pool.config = new
	pool.mu.Unlock()

	applogger.Infof("Pool config applied: max_open=%d, max_idle=%d",
		new.MaxOpenConns, new.MaxIdleConns)
}

func (dc *DynamicConfig) AddChangeListener(listener func(old, new *PoolConfig)) {
	dc.mu.Lock()
	defer dc.mu.Unlock()
	dc.listeners = append(dc.listeners, listener)
}

func (dc *DynamicConfig) watchConfigChanges() {
	cfg := config.GetManager()
	_ = cfg
}

func (dc *DynamicConfig) AutoDetectAndSwitch(stats map[string]interface{}) error {
	activeConns, _ := stats["active_connections"].(float64)
	waitCount, _ := stats["queue_length"].(float64)
	avgWaitTime, _ := stats["avg_wait_time_ms"].(float64)

	load := activeConns / float64(dc.activeConfig.MaxOpenConns)
	waitPressure := waitCount

	switch {
	case load > 0.8 || waitPressure > 10 || avgWaitTime > 500:
		if dc.currentScenario != ScenarioHighLoad {
			applogger.Infof("Auto-switching to high load scenario: load=%.2f, wait=%.0f, avg_wait=%.0fms",
				load, waitCount, avgWaitTime)
			return dc.SetScenario(ScenarioHighLoad)
		}
	case load < 0.3 && dc.currentScenario == ScenarioHighLoad:
		applogger.Info("Auto-switching back to default scenario")
		return dc.SetScenario(ScenarioDefault)
	case avgWaitTime < 10 && load < 0.5:
		if dc.currentScenario != ScenarioLowLatency && dc.currentScenario != ScenarioDefault {
			applogger.Info("Auto-switching to low latency scenario")
			return dc.SetScenario(ScenarioLowLatency)
		}
	}

	return nil
}

func (dc *DynamicConfig) GetScenarioInfo() map[string]interface{} {
	dc.mu.RLock()
	defer dc.mu.RUnlock()

	scenarios := make([]string, 0, len(dc.scenarios))
	for s := range dc.scenarios {
		scenarios = append(scenarios, string(s))
	}

	return map[string]interface{}{
		"current_scenario": string(dc.currentScenario),
		"available_scenarios": scenarios,
		"active_config": dc.activeConfig,
		"listeners_count": len(dc.listeners),
	}
}

func WithDynamicConfig(ctx context.Context, scenario Scenario, fn func(*gorm.DB) error) error {
	dc := GetDynamicConfig()
	originalScenario := dc.GetScenario()

	if err := dc.SetScenario(scenario); err != nil {
		return err
	}
	defer dc.SetScenario(originalScenario)

	return WithRetry(ctx, fn)
}
