package diagnostics

import "time"

type DiagnosticFilter struct {
	StartTime   time.Time `json:"start_time"`
	EndTime     time.Time `json:"end_time"`
	Path        string    `json:"path"`
	RouteID     string    `json:"route_id"`
	Method      string    `json:"method"`
	ErrorType   string    `json:"error_type"`
	StatusClass string    `json:"status_class"`
	Step        int       `json:"step"`
}

type DiagnosticDataPoint struct {
	Timestamp            time.Time `json:"timestamp"`
	RequestCount         int64     `json:"request_count"`
	SuccessCount         int64     `json:"success_count"`
	ErrorCount           int64     `json:"error_count"`
	SuccessRate          float64   `json:"success_rate"`
	P50Latency           float64   `json:"p50_latency_ms"`
	P90Latency           float64   `json:"p90_latency_ms"`
	P99Latency           float64   `json:"p99_latency_ms"`
	RateLimitRejected    int64     `json:"rate_limit_rejected"`
	CircuitBreakerOpen   int64     `json:"circuit_breaker_open"`
}

type DiagnosticSummary struct {
	StartTime            time.Time         `json:"start_time"`
	EndTime              time.Time         `json:"end_time"`
	TotalRequests        int64             `json:"total_requests"`
	TotalSuccess         int64             `json:"total_success"`
	TotalErrors          int64             `json:"total_errors"`
	OverallSuccessRate   float64           `json:"overall_success_rate"`
	AvgP50Latency        float64           `json:"avg_p50_latency_ms"`
	AvgP90Latency        float64           `json:"avg_p90_latency_ms"`
	AvgP99Latency        float64           `json:"avg_p99_latency_ms"`
	TotalRateLimitRej    int64             `json:"total_rate_limit_rejected"`
	TotalCircuitBreaker  int64             `json:"total_circuit_breaker_open"`
	TimeSeries           []DiagnosticDataPoint `json:"time_series"`
}

type RequestMetric struct {
	Timestamp          time.Time
	Path               string
	RouteID            string
	Method             string
	StatusCode         int
	Latency            time.Duration
	RateLimitRejected  bool
	CircuitBreakerOpen bool
	ErrorType          string
}

type errorStats struct {
	total     int64
	byType    map[string]int64
}

type latencySample struct {
	values []float64
}

func (ls *latencySample) Add(value float64) {
	ls.values = append(ls.values, value)
}

func (ls *latencySample) Percentile(p float64) float64 {
	if len(ls.values) == 0 {
		return 0
	}

	sorted := make([]float64, len(ls.values))
	copy(sorted, ls.values)

	for i := 1; i < len(sorted); i++ {
		j := i
		for j > 0 && sorted[j-1] > sorted[j] {
			sorted[j-1], sorted[j] = sorted[j], sorted[j-1]
			j--
		}
	}

	index := int(float64(len(sorted)-1) * p / 100)
	return sorted[index]
}

func (ls *latencySample) Clear() {
	ls.values = ls.values[:0]
}

type timeBucket struct {
	startTime    time.Time
	requestCount int64
	successCount int64
	errorCount   int64
	errors       errorStats
	latencies    latencySample
	rateLimitRej int64
	circuitOpen  int64
}

func newTimeBucket(start time.Time) *timeBucket {
	return &timeBucket{
		startTime: start,
		errors: errorStats{
			byType: make(map[string]int64),
		},
	}
}

func (tb *timeBucket) add(metric *RequestMetric) {
	tb.requestCount++

	statusClass := getStatusClass(metric.StatusCode)
	if statusClass == "2xx" || statusClass == "3xx" {
		tb.successCount++
	} else {
		tb.errorCount++
		if metric.ErrorType != "" {
			tb.errors.byType[metric.ErrorType]++
		}
	}

	tb.errors.total = tb.errorCount

	if metric.Latency > 0 {
		tb.latencies.Add(float64(metric.Latency.Milliseconds()))
	}

	if metric.RateLimitRejected {
		tb.rateLimitRej++
	}

	if metric.CircuitBreakerOpen {
		tb.circuitOpen++
	}
}

func (tb *timeBucket) toDataPoint() DiagnosticDataPoint {
	successRate := 0.0
	if tb.requestCount > 0 {
		successRate = float64(tb.successCount) / float64(tb.requestCount) * 100
	}

	return DiagnosticDataPoint{
		Timestamp:         tb.startTime,
		RequestCount:      tb.requestCount,
		SuccessCount:      tb.successCount,
		ErrorCount:        tb.errorCount,
		SuccessRate:       successRate,
		P50Latency:        tb.latencies.Percentile(50),
		P90Latency:        tb.latencies.Percentile(90),
		P99Latency:        tb.latencies.Percentile(99),
		RateLimitRejected: tb.rateLimitRej,
		CircuitBreakerOpen: tb.circuitOpen,
	}
}

func getStatusClass(code int) string {
	switch {
	case code >= 100 && code < 200:
		return "1xx"
	case code >= 200 && code < 300:
		return "2xx"
	case code >= 300 && code < 400:
		return "3xx"
	case code >= 400 && code < 500:
		return "4xx"
	case code >= 500 && code < 600:
		return "5xx"
	default:
		return "unknown"
	}
}
