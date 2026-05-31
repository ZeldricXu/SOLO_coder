package offline

import (
	"context"
	"errors"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
)

const (
	StrategyFIFO       = "fifo"
	StrategyLIFO       = "lifo"
	StrategyPriority   = "priority"
	StrategyDeadline   = "deadline"
	StrategySizeBatch  = "size_batch"
	StrategyTimeWindow = "time_window"
)

type SyncStrategy interface {
	Name() string
	Description() string
	SelectNext(ctx context.Context, records []model.OfflineDataRecord, batchSize int) []model.OfflineDataRecord
	ShouldProcess(ctx context.Context, record model.OfflineDataRecord) bool
	GetBatchSize(ctx context.Context) int
	GetTimeout(ctx context.Context) time.Duration
	OnProcess(ctx context.Context, record model.OfflineDataRecord)
	OnSuccess(ctx context.Context, record model.OfflineDataRecord)
	OnFailure(ctx context.Context, record model.OfflineDataRecord, err error)
}

type StrategyConfig struct {
	BatchSize int           `json:"batch_size"`
	Timeout   time.Duration `json:"timeout"`
	MaxRetry  int           `json:"max_retry"`
}

type StrategyManager interface {
	RegisterStrategy(name string, strategy SyncStrategy)
	GetStrategy(name string) (SyncStrategy, bool)
	ListStrategies() []string
	SetDefaultStrategy(name string)
	GetDefaultStrategy() string
	SetDeviceStrategy(deviceID, strategyName string) error
	GetDeviceStrategy(deviceID string) string
	SetStrategyConfig(name string, config StrategyConfig) error
	GetStrategyConfig(name string) (StrategyConfig, error)
}

type StrategyRegistry struct {
	strategies       map[string]SyncStrategy
	deviceStrategies map[string]string
	strategyConfigs  map[string]StrategyConfig
	defaultStrategy  string
}

func NewStrategyRegistry() *StrategyRegistry {
	registry := &StrategyRegistry{
		strategies:       make(map[string]SyncStrategy),
		deviceStrategies: make(map[string]string),
		strategyConfigs:  make(map[string]StrategyConfig),
		defaultStrategy:  StrategyFIFO,
	}

	registry.RegisterStrategy(StrategyFIFO, NewFIFOStrategy(registry))
	registry.RegisterStrategy(StrategyPriority, NewPriorityStrategy(registry))
	registry.RegisterStrategy(StrategyDeadline, NewDeadlineStrategy(registry))
	registry.RegisterStrategy(StrategyTimeWindow, NewTimeWindowStrategy(registry))

	return registry
}

func (r *StrategyRegistry) RegisterStrategy(name string, strategy SyncStrategy) {
	r.strategies[name] = strategy
	r.strategyConfigs[name] = StrategyConfig{
		BatchSize: 10,
		Timeout:   5 * time.Minute,
		MaxRetry:  3,
	}
}

func (r *StrategyRegistry) GetStrategy(name string) (SyncStrategy, bool) {
	strategy, ok := r.strategies[name]
	return strategy, ok
}

func (r *StrategyRegistry) ListStrategies() []string {
	names := make([]string, 0, len(r.strategies))
	for name := range r.strategies {
		names = append(names, name)
	}
	return names
}

func (r *StrategyRegistry) SetDefaultStrategy(name string) {
	if _, ok := r.strategies[name]; ok {
		r.defaultStrategy = name
	}
}

func (r *StrategyRegistry) GetDefaultStrategy() string {
	return r.defaultStrategy
}

func (r *StrategyRegistry) SetDeviceStrategy(deviceID, strategyName string) error {
	if _, ok := r.strategies[strategyName]; !ok {
		return ErrStrategyNotFound
	}
	r.deviceStrategies[deviceID] = strategyName
	return nil
}

func (r *StrategyRegistry) GetDeviceStrategy(deviceID string) string {
	if strategy, ok := r.deviceStrategies[deviceID]; ok {
		return strategy
	}
	return r.defaultStrategy
}

func (r *StrategyRegistry) SetStrategyConfig(name string, config StrategyConfig) error {
	if _, ok := r.strategies[name]; !ok {
		return ErrStrategyNotFound
	}
	r.strategyConfigs[name] = config
	return nil
}

func (r *StrategyRegistry) GetStrategyConfig(name string) (StrategyConfig, error) {
	config, ok := r.strategyConfigs[name]
	if !ok {
		return StrategyConfig{}, ErrStrategyNotFound
	}
	return config, nil
}

var ErrStrategyNotFound = errors.New("strategy not found")

type FIFOStrategy struct {
	registry *StrategyRegistry
}

func NewFIFOStrategy(registry *StrategyRegistry) *FIFOStrategy {
	return &FIFOStrategy{registry: registry}
}

func (s *FIFOStrategy) Name() string        { return StrategyFIFO }
func (s *FIFOStrategy) Description() string { return "First In First Out - Process records in creation order" }

func (s *FIFOStrategy) SelectNext(ctx context.Context, records []model.OfflineDataRecord, batchSize int) []model.OfflineDataRecord {
	if len(records) <= batchSize {
		return records
	}
	return records[:batchSize]
}

func (s *FIFOStrategy) ShouldProcess(ctx context.Context, record model.OfflineDataRecord) bool {
	return true
}

func (s *FIFOStrategy) GetBatchSize(ctx context.Context) int {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.BatchSize
}

func (s *FIFOStrategy) GetTimeout(ctx context.Context) time.Duration {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.Timeout
}

func (s *FIFOStrategy) OnProcess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *FIFOStrategy) OnSuccess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *FIFOStrategy) OnFailure(ctx context.Context, record model.OfflineDataRecord, err error) {}

type PriorityStrategy struct {
	registry *StrategyRegistry
}

func NewPriorityStrategy(registry *StrategyRegistry) *PriorityStrategy {
	return &PriorityStrategy{registry: registry}
}

func (s *PriorityStrategy) Name() string        { return StrategyPriority }
func (s *PriorityStrategy) Description() string { return "Process higher priority records first" }

func (s *PriorityStrategy) SelectNext(ctx context.Context, records []model.OfflineDataRecord, batchSize int) []model.OfflineDataRecord {
	sorted := make([]model.OfflineDataRecord, len(records))
	copy(sorted, records)

	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[j].Priority > sorted[i].Priority {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	if len(sorted) <= batchSize {
		return sorted
	}
	return sorted[:batchSize]
}

func (s *PriorityStrategy) ShouldProcess(ctx context.Context, record model.OfflineDataRecord) bool {
	return true
}

func (s *PriorityStrategy) GetBatchSize(ctx context.Context) int {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.BatchSize
}

func (s *PriorityStrategy) GetTimeout(ctx context.Context) time.Duration {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.Timeout
}

func (s *PriorityStrategy) OnProcess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *PriorityStrategy) OnSuccess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *PriorityStrategy) OnFailure(ctx context.Context, record model.OfflineDataRecord, err error) {}

type DeadlineStrategy struct {
	registry *StrategyRegistry
}

func NewDeadlineStrategy(registry *StrategyRegistry) *DeadlineStrategy {
	return &DeadlineStrategy{registry: registry}
}

func (s *DeadlineStrategy) Name() string        { return StrategyDeadline }
func (s *DeadlineStrategy) Description() string { return "Process records with earlier deadlines first" }

func (s *DeadlineStrategy) SelectNext(ctx context.Context, records []model.OfflineDataRecord, batchSize int) []model.OfflineDataRecord {
	sorted := make([]model.OfflineDataRecord, len(records))
	copy(sorted, records)

	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[j].Timestamp.Before(sorted[i].Timestamp) {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	if len(sorted) <= batchSize {
		return sorted
	}
	return sorted[:batchSize]
}

func (s *DeadlineStrategy) ShouldProcess(ctx context.Context, record model.OfflineDataRecord) bool {
	return true
}

func (s *DeadlineStrategy) GetBatchSize(ctx context.Context) int {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.BatchSize
}

func (s *DeadlineStrategy) GetTimeout(ctx context.Context) time.Duration {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.Timeout
}

func (s *DeadlineStrategy) OnProcess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *DeadlineStrategy) OnSuccess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *DeadlineStrategy) OnFailure(ctx context.Context, record model.OfflineDataRecord, err error) {}

type TimeWindowStrategy struct {
	registry     *StrategyRegistry
	windowStart  time.Time
	windowEnd    time.Time
	windowSize   time.Duration
}

func NewTimeWindowStrategy(registry *StrategyRegistry) *TimeWindowStrategy {
	now := time.Now()
	return &TimeWindowStrategy{
		registry:    registry,
		windowStart: now.Truncate(time.Hour),
		windowEnd:   now.Truncate(time.Hour).Add(time.Hour),
		windowSize:  time.Hour,
	}
}

func (s *TimeWindowStrategy) Name() string        { return StrategyTimeWindow }
func (s *TimeWindowStrategy) Description() string { return "Process records in time window batches" }

func (s *TimeWindowStrategy) SelectNext(ctx context.Context, records []model.OfflineDataRecord, batchSize int) []model.OfflineDataRecord {
	var windowRecords []model.OfflineDataRecord
	for _, r := range records {
		if r.Timestamp.After(s.windowStart) && r.Timestamp.Before(s.windowEnd) {
			windowRecords = append(windowRecords, r)
		}
	}

	if len(windowRecords) == 0 {
		if len(records) > 0 {
			return records[:1]
		}
		return records
	}

	if len(windowRecords) <= batchSize {
		return windowRecords
	}
	return windowRecords[:batchSize]
}

func (s *TimeWindowStrategy) ShouldProcess(ctx context.Context, record model.OfflineDataRecord) bool {
	now := time.Now()
	if now.After(s.windowEnd) {
		s.windowStart = now.Truncate(s.windowSize)
		s.windowEnd = s.windowStart.Add(s.windowSize)
	}
	return true
}

func (s *TimeWindowStrategy) GetBatchSize(ctx context.Context) int {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.BatchSize
}

func (s *TimeWindowStrategy) GetTimeout(ctx context.Context) time.Duration {
	config, _ := s.registry.GetStrategyConfig(s.Name())
	return config.Timeout
}

func (s *TimeWindowStrategy) OnProcess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *TimeWindowStrategy) OnSuccess(ctx context.Context, record model.OfflineDataRecord) {}
func (s *TimeWindowStrategy) OnFailure(ctx context.Context, record model.OfflineDataRecord, err error) {}
