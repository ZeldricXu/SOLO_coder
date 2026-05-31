package metrics

import (
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"session130/internal/logger"
	"session130/pkg/models"
)

type MetricType string

const (
	Counter   MetricType = "counter"
	Gauge     MetricType = "gauge"
	Histogram MetricType = "histogram"
)

type Metric struct {
	Name       string
	Type       MetricType
	Value      float64
	Labels     map[string]string
	Timestamp  time.Time
}

type HistogramData struct {
	Sum    float64
	Count  int64
	Values []float64
}

type Registry struct {
	mu          sync.RWMutex
	counters    map[string]*atomic.Int64
	gauges      map[string]*atomic.Int64
	histograms  map[string]*HistogramData
	dimensions  map[string]string
	snapshots   []*models.MetricsSnapshot
}

var (
	instance *Registry
	once     sync.Once
)

func NewRegistry() *Registry {
	return &Registry{
		counters:   make(map[string]*atomic.Int64),
		gauges:     make(map[string]*atomic.Int64),
		histograms: make(map[string]*HistogramData),
		dimensions: make(map[string]string),
	}
}

func GetRegistry() *Registry {
	once.Do(func() {
		instance = NewRegistry()
	})
	return instance
}

func (r *Registry) SetDimensions(dims map[string]string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for k, v := range dims {
		r.dimensions[k] = v
	}
}

func (r *Registry) getCounter(name string) *atomic.Int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if c, exists := r.counters[name]; exists {
		return c
	}
	c := &atomic.Int64{}
	r.counters[name] = c
	return c
}

func (r *Registry) getGauge(name string) *atomic.Int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if g, exists := r.gauges[name]; exists {
		return g
	}
	g := &atomic.Int64{}
	r.gauges[name] = g
	return g
}

func (r *Registry) getHistogram(name string) *HistogramData {
	r.mu.Lock()
	defer r.mu.Unlock()
	if h, exists := r.histograms[name]; exists {
		return h
	}
	h := &HistogramData{
		Values: make([]float64, 0, 1000),
	}
	r.histograms[name] = h
	return h
}

func (r *Registry) Inc(name string, labels map[string]string) {
	key := buildMetricKey(name, labels)
	c := r.getCounter(key)
	c.Add(1)
	logger.Debug("", "metric incremented", map[string]interface{}{
		"name": name,
		"key":  key,
	})
}

func (r *Registry) IncBy(name string, value int64, labels map[string]string) {
	key := buildMetricKey(name, labels)
	c := r.getCounter(key)
	c.Add(value)
}

func (r *Registry) Set(name string, value int64, labels map[string]string) {
	key := buildMetricKey(name, labels)
	g := r.getGauge(key)
	g.Store(value)
}

func (r *Registry) Observe(name string, value float64, labels map[string]string) {
	key := buildMetricKey(name, labels)
	h := r.getHistogram(key)
	r.mu.Lock()
	defer r.mu.Unlock()
	h.Sum += value
	h.Count++
	h.Values = append(h.Values, value)
	if len(h.Values) > 10000 {
		h.Values = h.Values[len(h.Values)-10000:]
	}
}

func (r *Registry) GetCounter(name string, labels map[string]string) int64 {
	key := buildMetricKey(name, labels)
	r.mu.RLock()
	defer r.mu.RUnlock()
	if c, exists := r.counters[key]; exists {
		return c.Load()
	}
	return 0
}

func (r *Registry) GetGauge(name string, labels map[string]string) int64 {
	key := buildMetricKey(name, labels)
	r.mu.RLock()
	defer r.mu.RUnlock()
	if g, exists := r.gauges[key]; exists {
		return g.Load()
	}
	return 0
}

func (r *Registry) GetHistogramPercentile(name string, labels map[string]string, percentile float64) float64 {
	key := buildMetricKey(name, labels)
	r.mu.RLock()
	defer r.mu.RUnlock()
	if h, exists := r.histograms[key]; exists && h.Count > 0 {
		return calculatePercentile(h.Values, percentile)
	}
	return 0
}

func (r *Registry) Snapshot() *models.MetricsSnapshot {
	r.mu.RLock()
	defer r.mu.RUnlock()

	metrics := make(map[string]float64)

	for name, c := range r.counters {
		metrics[name+".count"] = float64(c.Load())
	}

	for name, g := range r.gauges {
		metrics[name+".value"] = float64(g.Load())
	}

	for name, h := range r.histograms {
		if h.Count > 0 {
			metrics[name+".sum"] = h.Sum
			metrics[name+".count"] = float64(h.Count)
			metrics[name+".avg"] = h.Sum / float64(h.Count)
			metrics[name+".p50"] = calculatePercentile(h.Values, 50)
			metrics[name+".p95"] = calculatePercentile(h.Values, 95)
			metrics[name+".p99"] = calculatePercentile(h.Values, 99)
		}
	}

	dims := make(map[string]string)
	for k, v := range r.dimensions {
		dims[k] = v
	}

	snapshot := &models.MetricsSnapshot{
		SnapshotID: fmt.Sprintf("snap_%d", time.Now().UnixNano()),
		Timestamp:  time.Now(),
		Metrics:    metrics,
		Dimensions: dims,
	}

	r.snapshots = append(r.snapshots, snapshot)
	if len(r.snapshots) > 100 {
		r.snapshots = r.snapshots[len(r.snapshots)-100:]
	}

	return snapshot
}

func (r *Registry) GetSnapshots() []*models.MetricsSnapshot {
	r.mu.RLock()
	defer r.mu.RUnlock()
	snaps := make([]*models.MetricsSnapshot, len(r.snapshots))
	copy(snaps, r.snapshots)
	return snaps
}

func (r *Registry) Reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.counters = make(map[string]*atomic.Int64)
	r.gauges = make(map[string]*atomic.Int64)
	r.histograms = make(map[string]*HistogramData)
}

func (r *Registry) MarshalJSON() ([]byte, error) {
	snap := r.Snapshot()
	return json.Marshal(snap)
}

func buildMetricKey(name string, labels map[string]string) string {
	if len(labels) == 0 {
		return name
	}
	keys := make([]string, 0, len(labels))
	for k := range labels {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	key := name + "{"
	for i, k := range keys {
		if i > 0 {
			key += ","
		}
		key += fmt.Sprintf("%s=%s", k, labels[k])
	}
	key += "}"
	return key
}

func calculatePercentile(values []float64, percentile float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)
	index := int(math.Ceil((percentile / 100.0) * float64(len(sorted))))
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func Inc(name string, labels map[string]string) {
	GetRegistry().Inc(name, labels)
}

func IncBy(name string, value int64, labels map[string]string) {
	GetRegistry().IncBy(name, value, labels)
}

func Set(name string, value int64, labels map[string]string) {
	GetRegistry().Set(name, value, labels)
}

func Observe(name string, value float64, labels map[string]string) {
	GetRegistry().Observe(name, value, labels)
}

func Gauge(name string, value int64, labels map[string]string) {
	GetRegistry().Set(name, value, labels)
}
