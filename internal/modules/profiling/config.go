package profiling

import (
	"context"
	"time"

	"session189/internal/domain"
	"session189/pkg/config"
	"session189/pkg/eventbus"
)

const (
	ConfigKeyCPUDuration     = "profiling.cpu.duration_seconds"
	ConfigKeyMemoryDuration  = "profiling.memory.duration_seconds"
	ConfigKeyMemoryRate      = "profiling.memory.rate"
	ConfigKeyAutoProfile     = "profiling.auto.enabled"
	ConfigKeyAutoInterval    = "profiling.auto.interval_minutes"
	ConfigKeyMaxSamples      = "profiling.max_samples"
	ConfigKeySampleRetention = "profiling.retention_hours"
)

type ProfileStrategy string

const (
	StrategyDefault   ProfileStrategy = "default"
	StrategyDebug     ProfileStrategy = "debug"
	StrategyProduction ProfileStrategy = "production"
	StrategyLowImpact ProfileStrategy = "low_impact"
)

type ProfileConfig struct {
	CPUDuration     time.Duration
	MemoryDuration  time.Duration
	MemoryRate      int
	AutoEnabled     bool
	AutoInterval    time.Duration
	MaxSamples      int
	RetentionHours  int
	CurrentStrategy ProfileStrategy
}

type DynamicProfiler struct {
	*Profiler
	cfgManager   config.ConfigManager
	bus          eventbus.EventBus
	currentCfg   ProfileConfig
	subscription config.Subscription
}

func NewDynamicProfiler(base *Profiler, cfgManager config.ConfigManager, bus eventbus.EventBus) *DynamicProfiler {
	dp := &DynamicProfiler{
		Profiler:   base,
		cfgManager: cfgManager,
		bus:        bus,
		currentCfg: defaultConfig(),
	}
	dp.loadConfig()
	dp.subscribeConfigChanges()
	return dp
}

func defaultConfig() ProfileConfig {
	return ProfileConfig{
		CPUDuration:     30 * time.Second,
		MemoryDuration:  10 * time.Second,
		MemoryRate:      4096,
		AutoEnabled:     false,
		AutoInterval:    5 * time.Minute,
		MaxSamples:      1000,
		RetentionHours:  72,
		CurrentStrategy: StrategyDefault,
	}
}

func (dp *DynamicProfiler) loadConfig() {
	ctx := context.Background()
	dp.currentCfg.CPUDuration = time.Duration(dp.cfgManager.GetInt(ctx, ConfigKeyCPUDuration, int(dp.currentCfg.CPUDuration.Seconds()))) * time.Second
	dp.currentCfg.MemoryDuration = time.Duration(dp.cfgManager.GetInt(ctx, ConfigKeyMemoryDuration, int(dp.currentCfg.MemoryDuration.Seconds()))) * time.Second
	dp.currentCfg.MemoryRate = dp.cfgManager.GetInt(ctx, ConfigKeyMemoryRate, dp.currentCfg.MemoryRate)
	dp.currentCfg.AutoEnabled = dp.cfgManager.GetBool(ctx, ConfigKeyAutoProfile, dp.currentCfg.AutoEnabled)
	dp.currentCfg.AutoInterval = time.Duration(dp.cfgManager.GetInt(ctx, ConfigKeyAutoInterval, int(dp.currentCfg.AutoInterval.Minutes()))) * time.Minute
	dp.currentCfg.MaxSamples = dp.cfgManager.GetInt(ctx, ConfigKeyMaxSamples, dp.currentCfg.MaxSamples)
	dp.currentCfg.RetentionHours = dp.cfgManager.GetInt(ctx, ConfigKeySampleRetention, dp.currentCfg.RetentionHours)
}

func (dp *DynamicProfiler) subscribeConfigChanges() {
	dp.subscription = dp.cfgManager.Subscribe("profiling.", func(ctx context.Context, change config.ConfigChangeEvent) error {
		dp.loadConfig()
		dp.bus.Publish(ctx, eventbus.Event{
			Type:      eventbus.EventTypeConfigUpdated,
			Source:    "profiling",
			Timestamp: time.Now().UnixNano(),
			Data:      change,
		})
		return nil
	})
}

func (dp *DynamicProfiler) ApplyStrategy(strategy ProfileStrategy) {
	ctx := context.Background()
	switch strategy {
	case StrategyDebug:
		dp.cfgManager.Set(ctx, ConfigKeyCPUDuration, 60)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryDuration, 30)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryRate, 1024)
		dp.cfgManager.Set(ctx, ConfigKeyAutoProfile, true)
		dp.cfgManager.Set(ctx, ConfigKeyAutoInterval, 1)
		dp.currentCfg.CurrentStrategy = StrategyDebug
	case StrategyProduction:
		dp.cfgManager.Set(ctx, ConfigKeyCPUDuration, 10)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryDuration, 5)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryRate, 8192)
		dp.cfgManager.Set(ctx, ConfigKeyAutoProfile, false)
		dp.currentCfg.CurrentStrategy = StrategyProduction
	case StrategyLowImpact:
		dp.cfgManager.Set(ctx, ConfigKeyCPUDuration, 5)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryDuration, 2)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryRate, 16384)
		dp.cfgManager.Set(ctx, ConfigKeyAutoProfile, false)
		dp.currentCfg.CurrentStrategy = StrategyLowImpact
	default:
		dp.cfgManager.Set(ctx, ConfigKeyCPUDuration, 30)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryDuration, 10)
		dp.cfgManager.Set(ctx, ConfigKeyMemoryRate, 4096)
		dp.cfgManager.Set(ctx, ConfigKeyAutoProfile, false)
		dp.currentCfg.CurrentStrategy = StrategyDefault
	}
}

func (dp *DynamicProfiler) StartCPUProfileWithConfig(duration ...time.Duration) (*domain.ProfileSample, error) {
	d := dp.currentCfg.CPUDuration
	if len(duration) > 0 {
		d = duration[0]
	}
	return dp.Profiler.StartCPUProfile(d)
}

func (dp *DynamicProfiler) StartMemoryProfileWithConfig(duration ...time.Duration) (*domain.ProfileSample, error) {
	d := dp.currentCfg.MemoryDuration
	if len(duration) > 0 {
		d = duration[0]
	}
	return dp.Profiler.StartMemoryProfile(d, dp.currentCfg.MemoryRate)
}

func (dp *DynamicProfiler) Config() ProfileConfig {
	return dp.currentCfg
}

func (dp *DynamicProfiler) Close() {
	if dp.subscription != nil {
		dp.subscription.Unsubscribe()
	}
}
