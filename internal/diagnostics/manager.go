package diagnostics

import (
	"errors"
	"strings"
	"sync"
	"time"
)

type DiagnosticManager struct {
	mu            sync.RWMutex
	buckets       map[string]*timeBucket
	metricsBuffer []*RequestMetric
	bufferSize    int
	bucketSize    time.Duration
	retention     time.Duration
	stopCh        chan struct{}
	running       bool
}

func NewDiagnosticManager() *DiagnosticManager {
	return &DiagnosticManager{
		buckets:       make(map[string]*timeBucket),
		metricsBuffer: make([]*RequestMetric, 0, 10000),
		bufferSize:    10000,
		bucketSize:    10 * time.Second,
		retention:     1 * time.Hour,
		stopCh:        make(chan struct{}),
	}
}

func NewDiagnosticManagerWithConfig(bufferSize int, bucketSize, retention time.Duration) *DiagnosticManager {
	if bufferSize <= 0 {
		bufferSize = 10000
	}
	if bucketSize <= 0 {
		bucketSize = 10 * time.Second
	}
	if retention <= 0 {
		retention = 1 * time.Hour
	}

	return &DiagnosticManager{
		buckets:       make(map[string]*timeBucket),
		metricsBuffer: make([]*RequestMetric, 0, bufferSize),
		bufferSize:    bufferSize,
		bucketSize:    bucketSize,
		retention:     retention,
		stopCh:        make(chan struct{}),
	}
}

func (dm *DiagnosticManager) Start() {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	if dm.running {
		return
	}

	dm.running = true

	go dm.cleanupLoop()
}

func (dm *DiagnosticManager) Stop() {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	if !dm.running {
		return
	}

	dm.running = false
	close(dm.stopCh)
}

func (dm *DiagnosticManager) Record(metric *RequestMetric) {
	if metric == nil {
		return
	}

	dm.mu.Lock()
	defer dm.mu.Unlock()

	dm.metricsBuffer = append(dm.metricsBuffer, metric)

	if len(dm.metricsBuffer) >= dm.bufferSize {
		dm.flushBuffer()
	}
}

func (dm *DiagnosticManager) flushBuffer() {
	for _, metric := range dm.metricsBuffer {
		dm.addToBucket(metric)
	}
	dm.metricsBuffer = dm.metricsBuffer[:0]
}

func (dm *DiagnosticManager) addToBucket(metric *RequestMetric) {
	bucketKey := dm.getBucketKey(metric)
	bucketStart := metric.Timestamp.Truncate(dm.bucketSize)

	key := bucketKey + ":" + bucketStart.Format(time.RFC3339Nano)

	bucket, exists := dm.buckets[key]
	if !exists {
		bucket = newTimeBucket(bucketStart)
		dm.buckets[key] = bucket
	}

	bucket.add(metric)
}

func (dm *DiagnosticManager) getBucketKey(metric *RequestMetric) string {
	var parts []string
	if metric.Path != "" {
		parts = append(parts, "path:"+metric.Path)
	}
	if metric.RouteID != "" {
		parts = append(parts, "route:"+metric.RouteID)
	}
	if metric.Method != "" {
		parts = append(parts, "method:"+metric.Method)
	}
	return strings.Join(parts, "|")
}

func (dm *DiagnosticManager) Query(filter *DiagnosticFilter) (*DiagnosticSummary, error) {
	if filter == nil {
		return nil, errors.New("filter is required")
	}

	if filter.StartTime.IsZero() {
		filter.StartTime = time.Now().Add(-1 * time.Hour)
	}
	if filter.EndTime.IsZero() {
		filter.EndTime = time.Now()
	}
	if filter.Step <= 0 {
		filter.Step = 60
	}

	dm.mu.Lock()
	dm.flushBuffer()
	dm.mu.Unlock()

	step := time.Duration(filter.Step) * time.Second
	if step < dm.bucketSize {
		step = dm.bucketSize
	}

	timeSeries := make([]DiagnosticDataPoint, 0)

	for t := filter.StartTime.Truncate(step); t.Before(filter.EndTime); t = t.Add(step) {
		bucketEnd := t.Add(step)
		if bucketEnd.After(filter.EndTime) {
			bucketEnd = filter.EndTime
		}

		dp, err := dm.aggregateBucket(t, bucketEnd, filter)
		if err != nil {
			return nil, err
		}

		timeSeries = append(timeSeries, dp)
	}

	return dm.buildSummary(filter, timeSeries), nil
}

func (dm *DiagnosticManager) aggregateBucket(start, end time.Time, filter *DiagnosticFilter) (DiagnosticDataPoint, error) {
	dm.mu.RLock()
	defer dm.mu.RUnlock()

	aggBucket := newTimeBucket(start)

	for key, bucket := range dm.buckets {
		if bucket.startTime.Before(start) || !bucket.startTime.Before(end) {
			continue
		}

		if !dm.matchFilter(key, filter) {
			continue
		}

		if filter.ErrorType != "" {
			if cnt, ok := bucket.errors.byType[filter.ErrorType]; ok && cnt > 0 {
				aggBucket.requestCount += cnt
				aggBucket.errorCount += cnt
				aggBucket.errors.byType[filter.ErrorType] += cnt
			}
		} else {
			aggBucket.requestCount += bucket.requestCount
			aggBucket.successCount += bucket.successCount
			aggBucket.errorCount += bucket.errorCount
			aggBucket.rateLimitRej += bucket.rateLimitRej
			aggBucket.circuitOpen += bucket.circuitOpen

			for _, v := range bucket.latencies.values {
				aggBucket.latencies.Add(v)
			}
		}
	}

	return aggBucket.toDataPoint(), nil
}

func (dm *DiagnosticManager) matchFilter(key string, filter *DiagnosticFilter) bool {
	if filter.Path != "" {
		pathFilter := "path:" + filter.Path
		if filter.Path[len(filter.Path)-1] == '*' {
			prefix := "path:" + filter.Path[:len(filter.Path)-1]
			if !strings.Contains(key, prefix) {
				return false
			}
		} else {
			if !strings.Contains(key, pathFilter+"|") && !strings.HasSuffix(key, pathFilter) && !strings.Contains(key, pathFilter+":") {
				return false
			}
		}
	}

	if filter.RouteID != "" {
		routeFilter := "route:" + filter.RouteID
		if !strings.Contains(key, routeFilter+"|") && !strings.HasSuffix(key, routeFilter) && !strings.Contains(key, routeFilter+":") {
			return false
		}
	}

	if filter.Method != "" {
		methodFilter := "method:" + filter.Method
		if !strings.Contains(key, methodFilter+"|") && !strings.HasSuffix(key, methodFilter) && !strings.Contains(key, methodFilter+":") {
			return false
		}
	}

	return true
}

func (dm *DiagnosticManager) buildSummary(filter *DiagnosticFilter, timeSeries []DiagnosticDataPoint) *DiagnosticSummary {
	summary := &DiagnosticSummary{
		StartTime:  filter.StartTime,
		EndTime:    filter.EndTime,
		TimeSeries: timeSeries,
	}

	var totalP50, totalP90, totalP99 float64
	validPoints := 0

	for _, dp := range timeSeries {
		summary.TotalRequests += dp.RequestCount
		summary.TotalSuccess += dp.SuccessCount
		summary.TotalErrors += dp.ErrorCount
		summary.TotalRateLimitRej += dp.RateLimitRejected
		summary.TotalCircuitBreaker += dp.CircuitBreakerOpen

		if dp.RequestCount > 0 {
			totalP50 += dp.P50Latency
			totalP90 += dp.P90Latency
			totalP99 += dp.P99Latency
			validPoints++
		}
	}

	if summary.TotalRequests > 0 {
		summary.OverallSuccessRate = float64(summary.TotalSuccess) / float64(summary.TotalRequests) * 100
	}

	if validPoints > 0 {
		summary.AvgP50Latency = totalP50 / float64(validPoints)
		summary.AvgP90Latency = totalP90 / float64(validPoints)
		summary.AvgP99Latency = totalP99 / float64(validPoints)
	}

	return summary
}

func (dm *DiagnosticManager) cleanupLoop() {
	ticker := time.NewTicker(dm.retention / 2)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			dm.cleanup()
		case <-dm.stopCh:
			return
		}
	}
}

func (dm *DiagnosticManager) cleanup() {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	cutoff := time.Now().Add(-dm.retention)

	for key, bucket := range dm.buckets {
		if bucket.startTime.Before(cutoff) {
			delete(dm.buckets, key)
		}
	}
}

func (dm *DiagnosticManager) Flush() {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	dm.flushBuffer()
}

func (dm *DiagnosticManager) GetBufferCount() int {
	dm.mu.RLock()
	defer dm.mu.RUnlock()

	return len(dm.metricsBuffer)
}

func (dm *DiagnosticManager) GetBucketCount() int {
	dm.mu.RLock()
	defer dm.mu.RUnlock()

	return len(dm.buckets)
}
