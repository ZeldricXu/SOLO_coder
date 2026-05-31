package monitoring

import (
	"context"
	"fmt"
	"math"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/solocoder/logrotate/internal/domain"
)

type MetricType string

const (
	MetricCounter   MetricType = "counter"
	MetricGauge     MetricType = "gauge"
	MetricHistogram MetricType = "histogram"
	MetricTimer     MetricType = "timer"
)

type Counter struct {
	value  int64
	labels map[string]string
}

type Gauge struct {
	value  float64
	labels map[string]string
}

type Histogram struct {
	mu      sync.Mutex
	buckets []float64
	counts  []int64
	sum     float64
	count   int64
	labels  map[string]string
}

type Timer struct {
	start    time.Time
	name     string
	labels   map[string]string
	recorder *Metrics
}

type Metrics struct {
	mu            sync.RWMutex
	counters      map[string]*Counter
	gauges        map[string]*Gauge
	histograms    map[string]*Histogram
	snapshots     []*domain.MetricsSnapshot
	collectInterval time.Duration
	ctx           context.Context
	cancel        context.CancelFunc
	flushFunc     func(snapshot *domain.MetricsSnapshot)
}

type Option func(*Metrics)

func WithCollectInterval(d time.Duration) Option {
	return func(m *Metrics) {
		m.collectInterval = d
	}
}

func WithFlushCallback(f func(snapshot *domain.MetricsSnapshot)) Option {
	return func(m *Metrics) {
		m.flushFunc = f
	}
}

func New(opts ...Option) *Metrics {
	ctx, cancel := context.WithCancel(context.Background())

	m := &Metrics{
		counters:        make(map[string]*Counter),
		gauges:          make(map[string]*Gauge),
		histograms:      make(map[string]*Histogram),
		snapshots:       make([]*domain.MetricsSnapshot, 0),
		collectInterval: 30 * time.Second,
		ctx:             ctx,
		cancel:          cancel,
	}

	for _, opt := range opts {
		opt(m)
	}

	go m.collectLoop()

	return m
}

func (m *Metrics) collectLoop() {
	ticker := time.NewTicker(m.collectInterval)
	defer ticker.Stop()

	for {
		select {
		case <-m.ctx.Done():
			return
		case <-ticker.C:
			snapshot := m.CollectSnapshot()
			if m.flushFunc != nil {
				m.flushFunc(snapshot)
			}
		}
	}
}

func (m *Metrics) CounterInc(name string, value int64, labels map[string]string) {
	key := metricKey(name, labels)

	m.mu.RLock()
	c, ok := m.counters[key]
	m.mu.RUnlock()

	if !ok {
		m.mu.Lock()
		if c, ok = m.counters[key]; !ok {
			c = &Counter{labels: labels}
			m.counters[key] = c
		}
		m.mu.Unlock()
	}

	atomic.AddInt64(&c.value, value)
}

func (m *Metrics) GaugeSet(name string, value float64, labels map[string]string) {
	key := metricKey(name, labels)

	m.mu.Lock()
	defer m.mu.Unlock()

	g, ok := m.gauges[key]
	if !ok {
		g = &Gauge{labels: labels}
		m.gauges[key] = g
	}
	g.value = value
}

func (m *Metrics) GaugeAdd(name string, value float64, labels map[string]string) {
	key := metricKey(name, labels)

	m.mu.Lock()
	defer m.mu.Unlock()

	g, ok := m.gauges[key]
	if !ok {
		g = &Gauge{labels: labels}
		m.gauges[key] = g
	}
	g.value += value
}

func (m *Metrics) HistogramObserve(name string, value float64, buckets []float64, labels map[string]string) {
	key := metricKey(name, labels)

	m.mu.Lock()
	defer m.mu.Unlock()

	h, ok := m.histograms[key]
	if !ok {
		h = &Histogram{
			buckets: buckets,
			counts:  make([]int64, len(buckets)+1),
			labels:  labels,
		}
		m.histograms[key] = h
	}

	for i, bucket := range h.buckets {
		if value <= bucket {
			atomic.AddInt64(&h.counts[i], 1)
			break
		}
	}
	atomic.AddInt64(&h.counts[len(h.buckets)], 1)

	atomic.AddInt64(&h.count, 1)
	h.mu.Lock()
	h.sum += value
	h.mu.Unlock()
}

func (m *Metrics) StartTimer(name string, labels map[string]string) *Timer {
	return &Timer{
		start:    time.Now(),
		name:     name,
		labels:   labels,
		recorder: m,
	}
}

func (t *Timer) Stop() time.Duration {
	duration := time.Since(t.start)
	t.recorder.HistogramObserve(
		t.name,
		float64(duration.Milliseconds()),
		[]float64{1, 5, 10, 50, 100, 500, 1000, 5000},
		t.labels,
	)
	return duration
}

func (m *Metrics) CollectSnapshot() *domain.MetricsSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	snapshot := &domain.MetricsSnapshot{
		SnapshotID: uuid.New().String(),
		Timestamp:  time.Now(),
		Metrics:    make(map[string]float64),
		Dimensions: make(map[string]string),
	}

	for name, c := range m.counters {
		snapshot.Metrics[name] = float64(atomic.LoadInt64(&c.value))
		for k, v := range c.labels {
			snapshot.Dimensions[k] = v
		}
	}

	for name, g := range m.gauges {
		snapshot.Metrics[name] = g.value
		for k, v := range g.labels {
			snapshot.Dimensions[k] = v
		}
	}

	for name, h := range m.histograms {
		count := atomic.LoadInt64(&h.count)
		h.mu.Lock()
		sum := h.sum
		h.mu.Unlock()
		if count > 0 {
			snapshot.Metrics[name+".avg"] = sum / float64(count)
			snapshot.Metrics[name+".count"] = float64(count)
			snapshot.Metrics[name+".sum"] = sum

			p50 := m.percentile(h, 50)
			p95 := m.percentile(h, 95)
			p99 := m.percentile(h, 99)
			snapshot.Metrics[name+".p50"] = p50
			snapshot.Metrics[name+".p95"] = p95
			snapshot.Metrics[name+".p99"] = p99
		}
	}

	m.snapshots = append(m.snapshots, snapshot)
	if len(m.snapshots) > 100 {
		m.snapshots = m.snapshots[1:]
	}

	return snapshot
}

func (m *Metrics) percentile(h *Histogram, p float64) float64 {
	totalCount := atomic.LoadInt64(&h.count)
	if totalCount == 0 {
		return 0
	}

	rank := int64(math.Ceil(float64(totalCount) * p / 100.0))
	var accumulated int64

	for i, bucket := range h.buckets {
		accumulated += atomic.LoadInt64(&h.counts[i])
		if accumulated >= rank {
			if i == 0 {
				return bucket
			}
			prevBucket := h.buckets[i-1]
			prevCount := accumulated - atomic.LoadInt64(&h.counts[i])
			needed := rank - prevCount
			countInBucket := atomic.LoadInt64(&h.counts[i])

			if countInBucket == 0 {
				return bucket
			}
			return prevBucket + (bucket-prevBucket)*(float64(needed)/float64(countInBucket))
		}
	}

	return h.buckets[len(h.buckets)-1]
}

func (m *Metrics) GetSnapshot(snapshotID string) (*domain.MetricsSnapshot, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, s := range m.snapshots {
		if s.SnapshotID == snapshotID {
			return s, true
		}
	}
	return nil, false
}

func (m *Metrics) ListSnapshots(start, end time.Time) []*domain.MetricsSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var result []*domain.MetricsSnapshot
	for _, s := range m.snapshots {
		if (start.IsZero() || !s.Timestamp.Before(start)) && (end.IsZero() || !s.Timestamp.After(end)) {
			result = append(result, s)
		}
	}
	return result
}

func (m *Metrics) AggregateMetrics(metricName string, startTime, endTime time.Time) (map[string]float64, error) {
	snapshots := m.ListSnapshots(startTime, endTime)
	if len(snapshots) == 0 {
		return nil, fmt.Errorf("no snapshots found in time range")
	}

	values := make([]float64, 0, len(snapshots))
	for _, s := range snapshots {
		if v, ok := s.Metrics[metricName]; ok {
			values = append(values, v)
		}
	}

	if len(values) == 0 {
		return nil, fmt.Errorf("metric %s not found", metricName)
	}

	sort.Float64s(values)

	sum := 0.0
	for _, v := range values {
		sum += v
	}

	result := map[string]float64{
		"count": float64(len(values)),
		"sum":   sum,
		"avg":   sum / float64(len(values)),
		"min":   values[0],
		"max":   values[len(values)-1],
	}

	if len(values) > 1 {
		medianIdx := len(values) / 2
		if len(values)%2 == 0 {
			result["median"] = (values[medianIdx-1] + values[medianIdx]) / 2
		} else {
			result["median"] = values[medianIdx]
		}
	}

	return result, nil
}

func (m *Metrics) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.counters = make(map[string]*Counter)
	m.gauges = make(map[string]*Gauge)
	m.histograms = make(map[string]*Histogram)
}

func (m *Metrics) Close() {
	m.cancel()
}

func metricKey(name string, labels map[string]string) string {
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
		key += k + "=" + labels[k]
	}
	key += "}"
	return key
}
