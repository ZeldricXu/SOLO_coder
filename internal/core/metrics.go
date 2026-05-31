package core

import (
	"sync"
	"time"
)

type MetricsCollector struct {
	throughput int64
	latencies  []time.Duration
	errorCount int64
	totalCount int64
	mu         sync.Mutex
}

func NewMetricsCollector() *MetricsCollector {
	return &MetricsCollector{
		latencies: make([]time.Duration, 0),
	}
}

func (m *MetricsCollector) Record(latency time.Duration, success bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.totalCount++
	m.latencies = append(m.latencies, latency)
	if !success {
		m.errorCount++
	}
	m.throughput++
}

func (m *MetricsCollector) Snapshot() map[string]interface{} {
	m.mu.Lock()
	defer m.mu.Unlock()

	metrics := make(map[string]interface{})
	metrics["total_count"] = m.totalCount
	metrics["error_count"] = m.errorCount
	metrics["throughput"] = m.throughput

	if len(m.latencies) > 0 {
		var total time.Duration
		for _, l := range m.latencies {
			total += l
		}
		metrics["avg_latency_ms"] = total.Milliseconds() / int64(len(m.latencies))

		if len(m.latencies) >= 99 {
			p99Index := len(m.latencies) * 99 / 100
			metrics["latency_p99_ms"] = m.latencies[p99Index].Milliseconds()
		}
	}

	if m.totalCount > 0 {
		metrics["error_rate"] = float64(m.errorCount) / float64(m.totalCount)
	}

	return metrics
}
