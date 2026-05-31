package metrics

import (
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"time"
)

var (
	RequestDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP请求耗时分布",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"module", "endpoint", "method", "status"},
	)

	RequestCount = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "HTTP请求总数",
		},
		[]string{"module", "endpoint", "method", "status"},
	)

	CacheHits = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cache_hits_total",
			Help: "缓存命中次数",
		},
		[]string{"module", "cache_level", "key"},
	)

	CacheMisses = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cache_misses_total",
			Help: "缓存未命中次数",
		},
		[]string{"module", "cache_level", "key"},
	)

	CacheSize = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "cache_size_entries",
			Help: "缓存条目数量",
		},
		[]string{"module", "cache_level"},
	)

	OperationDuration = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "operation_duration_seconds",
			Help:    "关键操作耗时分布",
			Buckets: []float64{0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10},
		},
		[]string{"module", "operation", "status"},
	)

	BatchSize = promauto.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "batch_operation_size",
			Help:    "批量操作大小分布",
			Buckets: []float64{1, 5, 10, 25, 50, 100, 250, 500, 1000},
		},
		[]string{"module", "operation"},
	)

	ActiveConnections = promauto.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "active_connections",
			Help: "活跃连接数",
		},
		[]string{"module"},
	)

	ErrorCount = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "errors_total",
			Help: "错误总数",
		},
		[]string{"module", "error_type"},
	)
)

type Timer struct {
	start    time.Time
	module   string
	operation string
}

func NewTimer(module, operation string) *Timer {
	return &Timer{
		start:     time.Now(),
		module:    module,
		operation: operation,
	}
}

func (t *Timer) Observe(status string) {
	duration := time.Since(t.start).Seconds()
	OperationDuration.WithLabelValues(t.module, t.operation, status).Observe(duration)
}

func (t *Timer) ObserveSuccess() {
	t.Observe("success")
}

func (t *Timer) ObserveError() {
	t.Observe("error")
}

func RecordRequest(module, endpoint, method, status string, duration time.Duration) {
	RequestDuration.WithLabelValues(module, endpoint, method, status).Observe(duration.Seconds())
	RequestCount.WithLabelValues(module, endpoint, method, status).Inc()
}

func RecordCacheHit(module, level, key string) {
	CacheHits.WithLabelValues(module, level, key).Inc()
}

func RecordCacheMiss(module, level, key string) {
	CacheMisses.WithLabelValues(module, level, key).Inc()
}

func SetCacheSize(module, level string, size int) {
	CacheSize.WithLabelValues(module, level).Set(float64(size))
}

func RecordBatchSize(module, operation string, size int) {
	BatchSize.WithLabelValues(module, operation).Observe(float64(size))
}

func RecordError(module, errorType string) {
	ErrorCount.WithLabelValues(module, errorType).Inc()
}

func SetActiveConnections(module string, count int) {
	ActiveConnections.WithLabelValues(module).Set(float64(count))
}

var (
	VulnerabilitiesFound = promauto.NewCounterVec(
		prometheus.CounterOpts{
			Name: "vulnerabilities_found_total",
			Help: "发现的漏洞总数",
		},
		[]string{"severity"},
	)

	ActiveScans = promauto.NewGauge(
		prometheus.GaugeOpts{
			Name: "active_scans",
			Help: "当前活跃扫描数",
		},
	)
)

func RecordVulnerabilitiesFound(count int) {
	for i := 0; i < count; i++ {
		VulnerabilitiesFound.WithLabelValues("unknown").Inc()
	}
}

func RecordActiveScan(count int64) {
	ActiveScans.Set(float64(count))
}
