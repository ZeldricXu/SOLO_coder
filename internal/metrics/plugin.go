package metrics

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"session130/internal/logger"
)

type PluginType string

const (
	PluginTypeAggregation  PluginType = "aggregation"
	PluginTypeStorage      PluginType = "storage"
)

type PluginStatus string

const (
	PluginStatusLoaded    PluginStatus = "loaded"
	PluginStatusActive    PluginStatus = "active"
	PluginStatusInactive  PluginStatus = "inactive"
	PluginStatusError     PluginStatus = "error"
)

type MetricsPlugin interface {
	ID() string
	Name() string
	Version() string
	Type() PluginType
	Description() string
	Init(ctx context.Context, config map[string]interface{}) error
	Start(ctx context.Context) error
	Stop(ctx context.Context) error
	Status() PluginStatus
	LastError() error
}

type AggregationFunction func(ctx context.Context, values []float64) (float64, error)

type AggregationPlugin interface {
	MetricsPlugin
	AggregationFunctions() map[string]AggregationFunction
}

type StorageAdapter interface {
	Store(ctx context.Context, metric string, value float64, labels map[string]string, timestamp time.Time) error
	StoreBatch(ctx context.Context, metrics []StoredMetric) error
	Query(ctx context.Context, metric string, startTime, endTime time.Time, labels map[string]string) ([]StoredMetric, error)
	Close(ctx context.Context) error
}

type StoragePlugin interface {
	MetricsPlugin
	StorageAdapter() StorageAdapter
}

type StoredMetric struct {
	Name      string            `json:"name"`
	Value     float64           `json:"value"`
	Labels    map[string]string `json:"labels"`
	Timestamp time.Time         `json:"timestamp"`
}

type PluginInfo struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	Version     string            `json:"version"`
	Type        string            `json:"type"`
	Description string            `json:"description"`
	Status      string            `json:"status"`
	LoadedAt    time.Time         `json:"loaded_at"`
	LastError   string            `json:"last_error,omitempty"`
}

type PluginManagerConfig struct {
	EnableAutoReload bool
	PluginDir        string
}

type PluginManager struct {
	mu            sync.RWMutex
	plugins       map[string]MetricsPlugin
	aggregations  map[string]AggregationFunction
	storage       map[string]StorageAdapter
	config        PluginManagerConfig
	loadedAt      map[string]time.Time
}

var (
	pluginManagerInstance *PluginManager
	pluginManagerOnce     sync.Once
)

func NewPluginManager(config PluginManagerConfig) *PluginManager {
	return &PluginManager{
		plugins:      make(map[string]MetricsPlugin),
		aggregations: make(map[string]AggregationFunction),
		storage:      make(map[string]StorageAdapter),
		config:       config,
		loadedAt:     make(map[string]time.Time),
	}
}

func GetPluginManager() *PluginManager {
	pluginManagerOnce.Do(func() {
		pluginManagerInstance = NewPluginManager(PluginManagerConfig{})
	})
	return pluginManagerInstance
}

func (pm *PluginManager) LoadPlugin(ctx context.Context, plugin MetricsPlugin, config map[string]interface{}) (string, error) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	pluginID := plugin.ID()

	if _, exists := pm.plugins[pluginID]; exists {
		return "", fmt.Errorf("plugin with id %s already loaded", pluginID)
	}

	if err := plugin.Init(ctx, config); err != nil {
		return "", fmt.Errorf("failed to init plugin %s: %w", pluginID, err)
	}

	if err := plugin.Start(ctx); err != nil {
		return "", fmt.Errorf("failed to start plugin %s: %w", pluginID, err)
	}

	pm.plugins[pluginID] = plugin
	pm.loadedAt[pluginID] = time.Now()

	if aggPlugin, ok := plugin.(AggregationPlugin); ok {
		for name, fn := range aggPlugin.AggregationFunctions() {
			pm.aggregations[name] = fn
			logger.Info("", "loaded aggregation function from plugin", map[string]interface{}{
				"plugin_id":       pluginID,
				"function_name":   name,
			})
		}
	}

	if storagePlugin, ok := plugin.(StoragePlugin); ok {
		pm.storage[pluginID] = storagePlugin.StorageAdapter()
		logger.Info("", "loaded storage adapter from plugin", map[string]interface{}{
			"plugin_id": pluginID,
		})
	}

	Inc("metrics_plugin_loaded_total", map[string]string{
		"plugin_id":   pluginID,
		"plugin_type": string(plugin.Type()),
	})

	logger.Info("", "plugin loaded successfully", map[string]interface{}{
		"plugin_id":   pluginID,
		"plugin_name": plugin.Name(),
		"plugin_type": string(plugin.Type()),
	})

	return pluginID, nil
}

func (pm *PluginManager) UnloadPlugin(ctx context.Context, pluginID string) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	plugin, exists := pm.plugins[pluginID]
	if !exists {
		return fmt.Errorf("plugin with id %s not found", pluginID)
	}

	if err := plugin.Stop(ctx); err != nil {
		logger.Error("", "failed to stop plugin", map[string]interface{}{
			"plugin_id": pluginID,
			"error":     err.Error(),
		})
	}

	if aggPlugin, ok := plugin.(AggregationPlugin); ok {
		for name := range aggPlugin.AggregationFunctions() {
			delete(pm.aggregations, name)
		}
	}

	if storagePlugin, ok := plugin.(StoragePlugin); ok {
		if adapter := storagePlugin.StorageAdapter(); adapter != nil {
			adapter.Close(ctx)
		}
	}

	delete(pm.plugins, pluginID)
	delete(pm.loadedAt, pluginID)
	delete(pm.storage, pluginID)

	Inc("metrics_plugin_unloaded_total", map[string]string{
		"plugin_id": pluginID,
	})

	logger.Info("", "plugin unloaded successfully", map[string]interface{}{
		"plugin_id": pluginID,
	})

	return nil
}

func (pm *PluginManager) GetPlugin(pluginID string) (MetricsPlugin, bool) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	plugin, exists := pm.plugins[pluginID]
	return plugin, exists
}

func (pm *PluginManager) ListPlugins() []PluginInfo {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	plugins := make([]PluginInfo, 0, len(pm.plugins))
	for id, plugin := range pm.plugins {
		info := PluginInfo{
			ID:          plugin.ID(),
			Name:        plugin.Name(),
			Version:     plugin.Version(),
			Type:        string(plugin.Type()),
			Description: plugin.Description(),
			Status:      string(plugin.Status()),
			LoadedAt:    pm.loadedAt[id],
		}
		if err := plugin.LastError(); err != nil {
			info.LastError = err.Error()
		}
		plugins = append(plugins, info)
	}

	sort.Slice(plugins, func(i, j int) bool {
		return plugins[i].LoadedAt.After(plugins[j].LoadedAt)
	})

	return plugins
}

func (pm *PluginManager) GetAggregationFunction(name string) (AggregationFunction, bool) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	fn, exists := pm.aggregations[name]
	return fn, exists
}

func (pm *PluginManager) ListAggregationFunctions() []string {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	names := make([]string, 0, len(pm.aggregations))
	for name := range pm.aggregations {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func (pm *PluginManager) GetStorageAdapter(pluginID string) (StorageAdapter, bool) {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	adapter, exists := pm.storage[pluginID]
	return adapter, exists
}

func (pm *PluginManager) ListStorageAdapters() []string {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	ids := make([]string, 0, len(pm.storage))
	for id := range pm.storage {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

func (pm *PluginManager) ExecuteAggregation(ctx context.Context, funcName string, values []float64) (float64, error) {
	fn, exists := pm.GetAggregationFunction(funcName)
	if !exists {
		return 0, fmt.Errorf("aggregation function %s not found", funcName)
	}

	start := time.Now()
	result, err := fn(ctx, values)
	duration := time.Since(start)

	Observe("metrics_plugin_aggregation_duration_seconds", duration.Seconds(), map[string]string{
		"function": funcName,
	})

	if err != nil {
		Inc("metrics_plugin_aggregation_error_total", map[string]string{
			"function": funcName,
		})
		return 0, fmt.Errorf("aggregation function %s failed: %w", funcName, err)
	}

	Inc("metrics_plugin_aggregation_success_total", map[string]string{
		"function": funcName,
	})

	return result, nil
}

func (pm *PluginManager) Shutdown(ctx context.Context) error {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	var errs []error
	for id, plugin := range pm.plugins {
		if err := plugin.Stop(ctx); err != nil {
			errs = append(errs, fmt.Errorf("plugin %s stop error: %w", id, err))
		}

		if storagePlugin, ok := plugin.(StoragePlugin); ok {
			if adapter := storagePlugin.StorageAdapter(); adapter != nil {
				if err := adapter.Close(ctx); err != nil {
					errs = append(errs, fmt.Errorf("plugin %s storage close error: %w", id, err))
				}
			}
		}
	}

	if len(errs) > 0 {
		return fmt.Errorf("errors during shutdown: %v", errs)
	}

	logger.Info("", "plugin manager shutdown complete", map[string]interface{}{
		"unloaded_plugins": len(pm.plugins),
	})

	return nil
}

func (pm *PluginManager) GetStats() map[string]interface{} {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	aggCount := len(pm.aggregations)
	storageCount := len(pm.storage)
	activePlugins := 0
	for _, p := range pm.plugins {
		if p.Status() == PluginStatusActive {
			activePlugins++
		}
	}

	return map[string]interface{}{
		"total_plugins":          len(pm.plugins),
		"active_plugins":         activePlugins,
		"aggregation_functions":  aggCount,
		"storage_adapters":       storageCount,
	}
}

type StatisticalAggregationPlugin struct {
	id          string
	name        string
	version     string
	description string
	status      PluginStatus
	lastErr     error
}

func NewStatisticalAggregationPlugin() *StatisticalAggregationPlugin {
	return &StatisticalAggregationPlugin{
		id:          "statistical-aggregations",
		name:        "Statistical Aggregations",
		version:     "1.0.0",
		description: "Provides statistical aggregation functions including variance, stddev, median, p50, p90, p95, p99",
		status:      PluginStatusLoaded,
	}
}

func (p *StatisticalAggregationPlugin) ID() string              { return p.id }
func (p *StatisticalAggregationPlugin) Name() string            { return p.name }
func (p *StatisticalAggregationPlugin) Version() string         { return p.version }
func (p *StatisticalAggregationPlugin) Type() PluginType        { return PluginTypeAggregation }
func (p *StatisticalAggregationPlugin) Description() string     { return p.description }
func (p *StatisticalAggregationPlugin) Status() PluginStatus    { return p.status }
func (p *StatisticalAggregationPlugin) LastError() error        { return p.lastErr }

func (p *StatisticalAggregationPlugin) Init(ctx context.Context, config map[string]interface{}) error {
	p.status = PluginStatusActive
	return nil
}

func (p *StatisticalAggregationPlugin) Start(ctx context.Context) error {
	p.status = PluginStatusActive
	return nil
}

func (p *StatisticalAggregationPlugin) Stop(ctx context.Context) error {
	p.status = PluginStatusInactive
	return nil
}

func (p *StatisticalAggregationPlugin) AggregationFunctions() map[string]AggregationFunction {
	return map[string]AggregationFunction{
		"variance":   aggregateVariance,
		"stddev":     aggregateStdDev,
		"median":     aggregateMedian,
		"p50":        aggregateP50,
		"p90":        aggregateP90,
		"p95":        aggregateP95,
		"p99":        aggregateP99,
	}
}

func aggregateVariance(ctx context.Context, values []float64) (float64, error) {
	if len(values) == 0 {
		return 0, nil
	}

	mean := 0.0
	for _, v := range values {
		mean += v
	}
	mean /= float64(len(values))

	variance := 0.0
	for _, v := range values {
		diff := v - mean
		variance += diff * diff
	}
	variance /= float64(len(values))

	return variance, nil
}

func aggregateStdDev(ctx context.Context, values []float64) (float64, error) {
	variance, err := aggregateVariance(ctx, values)
	if err != nil {
		return 0, err
	}
	return mathSqrt(variance), nil
}

func mathSqrt(v float64) float64 {
	if v <= 0 {
		return 0
	}

	z := v
	x := z / 2.0
	for i := 0; i < 100; i++ {
		if x == 0 {
			return 0
		}
		next := (x + z/x) / 2
		if next == x {
			break
		}
		x = next
	}
	return x
}

func aggregateMedian(ctx context.Context, values []float64) (float64, error) {
	return percentile(values, 50), nil
}

func aggregateP50(ctx context.Context, values []float64) (float64, error) {
	return percentile(values, 50), nil
}

func aggregateP90(ctx context.Context, values []float64) (float64, error) {
	return percentile(values, 90), nil
}

func aggregateP95(ctx context.Context, values []float64) (float64, error) {
	return percentile(values, 95), nil
}

func aggregateP99(ctx context.Context, values []float64) (float64, error) {
	return percentile(values, 99), nil
}

func percentile(values []float64, p int) float64 {
	if len(values) == 0 {
		return 0
	}

	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	index := (float64(p) / 100.0) * float64(len(sorted)-1)
	lower := int(index)
	upper := lower + 1

	if upper >= len(sorted) {
		return sorted[lower]
	}

	weight := index - float64(lower)
	return sorted[lower]*(1-weight) + sorted[upper]*weight
}

type InMemoryStoragePlugin struct {
	id          string
	name        string
	version     string
	description string
	status      PluginStatus
	lastErr     error
	adapter     *InMemoryStorageAdapter
}

type InMemoryStorageAdapter struct {
	mu      sync.RWMutex
	metrics map[string][]StoredMetric
	maxSize int
}

func NewInMemoryStoragePlugin() *InMemoryStoragePlugin {
	return &InMemoryStoragePlugin{
		id:          "inmemory-storage",
		name:        "In-Memory Storage",
		version:     "1.0.0",
		description: "In-memory metrics storage adapter for testing and development",
		status:      PluginStatusLoaded,
	}
}

func (p *InMemoryStoragePlugin) ID() string              { return p.id }
func (p *InMemoryStoragePlugin) Name() string            { return p.name }
func (p *InMemoryStoragePlugin) Version() string         { return p.version }
func (p *InMemoryStoragePlugin) Type() PluginType        { return PluginTypeStorage }
func (p *InMemoryStoragePlugin) Description() string     { return p.description }
func (p *InMemoryStoragePlugin) Status() PluginStatus    { return p.status }
func (p *InMemoryStoragePlugin) LastError() error        { return p.lastErr }

func (p *InMemoryStoragePlugin) Init(ctx context.Context, config map[string]interface{}) error {
	maxSize := 10000
	if cfgMaxSize, ok := config["max_size"].(int); ok {
		maxSize = cfgMaxSize
	}

	p.adapter = &InMemoryStorageAdapter{
		metrics: make(map[string][]StoredMetric),
		maxSize: maxSize,
	}

	p.status = PluginStatusActive
	return nil
}

func (p *InMemoryStoragePlugin) Start(ctx context.Context) error {
	p.status = PluginStatusActive
	return nil
}

func (p *InMemoryStoragePlugin) Stop(ctx context.Context) error {
	p.status = PluginStatusInactive
	return nil
}

func (p *InMemoryStoragePlugin) StorageAdapter() StorageAdapter {
	return p.adapter
}

func (a *InMemoryStorageAdapter) Store(ctx context.Context, metric string, value float64, labels map[string]string, timestamp time.Time) error {
	a.mu.Lock()
	defer a.mu.Unlock()

	m := StoredMetric{
		Name:      metric,
		Value:     value,
		Labels:    labels,
		Timestamp: timestamp,
	}

	a.metrics[metric] = append(a.metrics[metric], m)
	if len(a.metrics[metric]) > a.maxSize {
		a.metrics[metric] = a.metrics[metric][len(a.metrics[metric])-a.maxSize:]
	}

	return nil
}

func (a *InMemoryStorageAdapter) StoreBatch(ctx context.Context, metrics []StoredMetric) error {
	for _, m := range metrics {
		if err := a.Store(ctx, m.Name, m.Value, m.Labels, m.Timestamp); err != nil {
			return err
		}
	}
	return nil
}

func (a *InMemoryStorageAdapter) Query(ctx context.Context, metric string, startTime, endTime time.Time, labels map[string]string) ([]StoredMetric, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()

	allMetrics, exists := a.metrics[metric]
	if !exists {
		return nil, nil
	}

	var result []StoredMetric
	for _, m := range allMetrics {
		if m.Timestamp.After(startTime) && m.Timestamp.Before(endTime) {
			if labelsMatch(m.Labels, labels) {
				result = append(result, m)
			}
		}
	}

	return result, nil
}

func labelsMatch(metricLabels, queryLabels map[string]string) bool {
	for k, v := range queryLabels {
		if metricLabels[k] != v {
			return false
		}
	}
	return true
}

func (a *InMemoryStorageAdapter) Close(ctx context.Context) error {
	return nil
}
