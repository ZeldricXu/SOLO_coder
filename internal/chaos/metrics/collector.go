package metrics

import (
	"sync"
	"sync/atomic"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type MetricsCollector struct {
	mu                   sync.RWMutex
	injectionsStarted    int64
	injectionsCompleted  int64
	injectionsFailed     int64
	rollbacksStarted     int64
	rollbacksCompleted   int64
	rollbacksFailed      int64
	totalRuns            int64
	activeRuns           int
	timings              []*domain.TimingMetric
	injectorStats        map[string]*domain.InjectorStat
}

func NewMetricsCollector() ports.MetricsCollector {
	return &MetricsCollector{
		injectorStats: make(map[string]*domain.InjectorStat),
	}
}

func (c *MetricsCollector) RecordTiming(operation string, duration time.Duration, labels map[string]string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	metric := &domain.TimingMetric{
		Name:      operation,
		Duration:  duration,
		Timestamp: time.Now(),
		Labels:    labels,
	}
	c.timings = append(c.timings, metric)

	if len(c.timings) > 10000 {
		c.timings = c.timings[len(c.timings)-10000:]
	}

	if injectorType, ok := labels["injector_type"]; ok {
		c.updateInjectorTiming(injectorType, operation, duration, labels)
	}
}

func (c *MetricsCollector) updateInjectorTiming(injectorType, operation string, duration time.Duration, labels map[string]string) {
	stat, exists := c.injectorStats[injectorType]
	if !exists {
		stat = &domain.InjectorStat{
			Type:          injectorType,
			MinDurationMs: int64(duration.Milliseconds()),
			MaxDurationMs: int64(duration.Milliseconds()),
		}
		c.injectorStats[injectorType] = stat
	}

	stat.TotalCalls++
	if operation == "inject" {
		if success, ok := labels["success"]; ok && success == "true" {
			stat.SuccessCount++
		} else {
			stat.FailureCount++
		}
	}

	durationMs := int64(duration.Milliseconds())
	if durationMs < stat.MinDurationMs {
		stat.MinDurationMs = durationMs
	}
	if durationMs > stat.MaxDurationMs {
		stat.MaxDurationMs = durationMs
	}

	totalDuration := stat.AvgDurationMs * float64(stat.TotalCalls-1)
	stat.AvgDurationMs = (totalDuration + float64(durationMs)) / float64(stat.TotalCalls)
}

func (c *MetricsCollector) RecordCounter(name string, value int64, labels map[string]string) {
	switch name {
	case "injections_started":
		atomic.AddInt64(&c.injectionsStarted, value)
		if injectorType, ok := labels["injector_type"]; ok {
			c.ensureInjectorStat(injectorType)
		}
	case "injections_completed":
		atomic.AddInt64(&c.injectionsCompleted, value)
	case "injections_failed":
		atomic.AddInt64(&c.injectionsFailed, value)
	case "rollbacks_started":
		atomic.AddInt64(&c.rollbacksStarted, value)
	case "rollbacks_completed":
		atomic.AddInt64(&c.rollbacksCompleted, value)
	case "rollbacks_failed":
		atomic.AddInt64(&c.rollbacksFailed, value)
	case "total_runs":
		atomic.AddInt64(&c.totalRuns, value)
	case "active_runs":
		c.mu.Lock()
		c.activeRuns = int(value)
		c.mu.Unlock()
	}
}

func (c *MetricsCollector) RecordGauge(name string, value float64, labels map[string]string) {
	if name == "active_runs" {
		c.mu.Lock()
		c.activeRuns = int(value)
		c.mu.Unlock()
	}
}

func (c *MetricsCollector) ensureInjectorStat(injectorType string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, exists := c.injectorStats[injectorType]; !exists {
		c.injectorStats[injectorType] = &domain.InjectorStat{
			Type: injectorType,
		}
	}
}

func (c *MetricsCollector) GetMetrics() *domain.ChaosMetrics {
	c.mu.RLock()
	defer c.mu.RUnlock()

	injectorStats := make(map[string]*domain.InjectorStat, len(c.injectorStats))
	for k, v := range c.injectorStats {
		statCopy := *v
		injectorStats[k] = &statCopy
	}

	timings := make([]*domain.TimingMetric, len(c.timings))
	copy(timings, c.timings)

	return &domain.ChaosMetrics{
		InjectionsStarted:   atomic.LoadInt64(&c.injectionsStarted),
		InjectionsCompleted: atomic.LoadInt64(&c.injectionsCompleted),
		InjectionsFailed:    atomic.LoadInt64(&c.injectionsFailed),
		RollbacksStarted:    atomic.LoadInt64(&c.rollbacksStarted),
		RollbacksCompleted:  atomic.LoadInt64(&c.rollbacksCompleted),
		RollbacksFailed:     atomic.LoadInt64(&c.rollbacksFailed),
		ActiveRuns:          c.activeRuns,
		TotalRuns:           atomic.LoadInt64(&c.totalRuns),
		Timings:             timings,
		InjectorStats:       injectorStats,
	}
}
