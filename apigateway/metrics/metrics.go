package metrics

import (
	"apigateway/models"
	"sort"
	"sync"
	"time"
)

type RouteMetrics struct {
	RouteID       string
	RequestCount  int64
	SuccessCount  int64
	FailCount     int64
	TotalLatency  int64
	MaxLatency    int64
	MinLatency    int64
	LastRequestAt time.Time
	mu            sync.RWMutex
}

type MinuteBucket struct {
	Minute      time.Time
	RouteStats  map[string]*RouteStats
	mu          sync.RWMutex
}

type RouteStats struct {
	RequestCount int
	SuccessCount int
	FailCount    int
	TotalLatency int64
	MaxLatency   int64
	MinLatency   int64
}

type MetricsCollector struct {
	realtimeStats map[string]*RouteMetrics
	minuteBuckets []*MinuteBucket
	maxBuckets    int
	mu            sync.RWMutex
}

func NewMetricsCollector() *MetricsCollector {
	return &MetricsCollector{
		realtimeStats: make(map[string]*RouteMetrics),
		minuteBuckets: make([]*MinuteBucket, 0),
		maxBuckets:    60,
	}
}

func (mc *MetricsCollector) getOrCreateRouteMetrics(routeID string) *RouteMetrics {
	mc.mu.Lock()
	defer mc.mu.Unlock()

	metrics, exists := mc.realtimeStats[routeID]
	if !exists {
		metrics = &RouteMetrics{
			RouteID:    routeID,
			MinLatency: -1,
		}
		mc.realtimeStats[routeID] = metrics
	}
	return metrics
}

func (mc *MetricsCollector) RecordSuccess(routeID string, latency int64) {
	metrics := mc.getOrCreateRouteMetrics(routeID)
	metrics.RecordSuccess(latency)
	mc.recordToMinuteBucket(routeID, latency, true)
}

func (mc *MetricsCollector) RecordFailure(routeID string, latency int64) {
	metrics := mc.getOrCreateRouteMetrics(routeID)
	metrics.RecordFailure(latency)
	mc.recordToMinuteBucket(routeID, latency, false)
}

func (mc *MetricsCollector) recordToMinuteBucket(routeID string, latency int64, success bool) {
	now := time.Now().Truncate(time.Minute)

	mc.mu.Lock()
	defer mc.mu.Unlock()

	var currentBucket *MinuteBucket
	if len(mc.minuteBuckets) > 0 && mc.minuteBuckets[len(mc.minuteBuckets)-1].Minute.Equal(now) {
		currentBucket = mc.minuteBuckets[len(mc.minuteBuckets)-1]
	} else {
		currentBucket = &MinuteBucket{
			Minute:     now,
			RouteStats: make(map[string]*RouteStats),
		}
		mc.minuteBuckets = append(mc.minuteBuckets, currentBucket)

		if len(mc.minuteBuckets) > mc.maxBuckets {
			mc.minuteBuckets = mc.minuteBuckets[len(mc.minuteBuckets)-mc.maxBuckets:]
		}
	}

	currentBucket.mu.Lock()
	defer currentBucket.mu.Unlock()

	stats, exists := currentBucket.RouteStats[routeID]
	if !exists {
		stats = &RouteStats{MinLatency: -1}
		currentBucket.RouteStats[routeID] = stats
	}

	stats.RequestCount++
	if success {
		stats.SuccessCount++
	} else {
		stats.FailCount++
	}
	stats.TotalLatency += latency
	if stats.MinLatency < 0 || latency < stats.MinLatency {
		stats.MinLatency = latency
	}
	if latency > stats.MaxLatency {
		stats.MaxLatency = latency
	}
}

func (rm *RouteMetrics) RecordSuccess(latency int64) {
	rm.mu.Lock()
	defer rm.mu.Unlock()

	rm.RequestCount++
	rm.SuccessCount++
	rm.TotalLatency += latency
	rm.LastRequestAt = time.Now()

	if rm.MinLatency < 0 || latency < rm.MinLatency {
		rm.MinLatency = latency
	}
	if latency > rm.MaxLatency {
		rm.MaxLatency = latency
	}
}

func (rm *RouteMetrics) RecordFailure(latency int64) {
	rm.mu.Lock()
	defer rm.mu.Unlock()

	rm.RequestCount++
	rm.FailCount++
	rm.TotalLatency += latency
	rm.LastRequestAt = time.Now()

	if rm.MinLatency < 0 || latency < rm.MinLatency {
		rm.MinLatency = latency
	}
	if latency > rm.MaxLatency {
		rm.MaxLatency = latency
	}
}

func (rm *RouteMetrics) GetAvgLatency() int64 {
	rm.mu.RLock()
	defer rm.mu.RUnlock()

	if rm.RequestCount == 0 {
		return 0
	}
	return rm.TotalLatency / rm.RequestCount
}

func (mc *MetricsCollector) GetRouteStats(routeID string) *models.CallStats {
	metrics, exists := mc.realtimeStats[routeID]
	if !exists {
		return nil
	}

	metrics.mu.RLock()
	defer metrics.mu.RUnlock()

	avgLatency := int64(0)
	if metrics.RequestCount > 0 {
		avgLatency = metrics.TotalLatency / metrics.RequestCount
	}

	return &models.CallStats{
		RouteID:      metrics.RouteID,
		RequestCount: int(metrics.RequestCount),
		SuccessCount: int(metrics.SuccessCount),
		FailCount:    int(metrics.FailCount),
		AvgLatency:   avgLatency,
		MaxLatency:   metrics.MaxLatency,
		MinLatency:   metrics.MinLatency,
	}
}

func (mc *MetricsCollector) GetAllStats() map[string]*models.CallStats {
	mc.mu.RLock()
	defer mc.mu.RUnlock()

	stats := make(map[string]*models.CallStats)
	for routeID, metrics := range mc.realtimeStats {
		metrics.mu.RLock()
		avgLatency := int64(0)
		if metrics.RequestCount > 0 {
			avgLatency = metrics.TotalLatency / metrics.RequestCount
		}
		stats[routeID] = &models.CallStats{
			RouteID:      metrics.RouteID,
			RequestCount: int(metrics.RequestCount),
			SuccessCount: int(metrics.SuccessCount),
			FailCount:    int(metrics.FailCount),
			AvgLatency:   avgLatency,
			MaxLatency:   metrics.MaxLatency,
			MinLatency:   metrics.MinLatency,
		}
		metrics.mu.RUnlock()
	}
	return stats
}

func (mc *MetricsCollector) QueryStats(routeID string, startTime, endTime time.Time) []*models.CallStats {
	mc.mu.RLock()
	defer mc.mu.RUnlock()

	results := make([]*models.CallStats, 0)

	for _, bucket := range mc.minuteBuckets {
		if bucket.Minute.Before(startTime) || bucket.Minute.After(endTime) {
			continue
		}

		bucket.mu.RLock()
		stats, exists := bucket.RouteStats[routeID]
		if exists {
			avgLatency := int64(0)
			if stats.RequestCount > 0 {
				avgLatency = stats.TotalLatency / int64(stats.RequestCount)
			}
			results = append(results, &models.CallStats{
				RouteID:      routeID,
				StatTime:     bucket.Minute,
				RequestCount: stats.RequestCount,
				SuccessCount: stats.SuccessCount,
				FailCount:    stats.FailCount,
				AvgLatency:   avgLatency,
				MaxLatency:   stats.MaxLatency,
				MinLatency:   stats.MinLatency,
			})
		}
		bucket.mu.RUnlock()
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].StatTime.Before(results[j].StatTime)
	})

	return results
}

func (mc *MetricsCollector) GetTopRoutes(limit int, sortBy string) []*models.CallStats {
	mc.mu.RLock()
	defer mc.mu.RUnlock()

	allStats := make([]*models.CallStats, 0, len(mc.realtimeStats))
	for _, metrics := range mc.realtimeStats {
		metrics.mu.RLock()
		avgLatency := int64(0)
		if metrics.RequestCount > 0 {
			avgLatency = metrics.TotalLatency / metrics.RequestCount
		}
		allStats = append(allStats, &models.CallStats{
			RouteID:      metrics.RouteID,
			RequestCount: int(metrics.RequestCount),
			SuccessCount: int(metrics.SuccessCount),
			FailCount:    int(metrics.FailCount),
			AvgLatency:   avgLatency,
			MaxLatency:   metrics.MaxLatency,
		})
		metrics.mu.RUnlock()
	}

	switch sortBy {
	case "request_count":
		sort.Slice(allStats, func(i, j int) bool {
			return allStats[i].RequestCount > allStats[j].RequestCount
		})
	case "fail_count":
		sort.Slice(allStats, func(i, j int) bool {
			return allStats[i].FailCount > allStats[j].FailCount
		})
	case "avg_latency":
		sort.Slice(allStats, func(i, j int) bool {
			return allStats[i].AvgLatency > allStats[j].AvgLatency
		})
	default:
		sort.Slice(allStats, func(i, j int) bool {
			return allStats[i].RequestCount > allStats[j].RequestCount
		})
	}

	if limit > 0 && limit < len(allStats) {
		return allStats[:limit]
	}
	return allStats
}

func (mc *MetricsCollector) ResetRouteStats(routeID string) {
	mc.mu.Lock()
	defer mc.mu.Unlock()

	delete(mc.realtimeStats, routeID)
}

func (mc *MetricsCollector) ResetAllStats() {
	mc.mu.Lock()
	defer mc.mu.Unlock()

	mc.realtimeStats = make(map[string]*RouteMetrics)
	mc.minuteBuckets = make([]*MinuteBucket, 0)
}

func (mc *MetricsCollector) GetSummary() map[string]interface{} {
	mc.mu.RLock()
	defer mc.mu.RUnlock()

	totalRequests := int64(0)
	totalSuccess := int64(0)
	totalFail := int64(0)
	activeRoutes := 0

	for _, metrics := range mc.realtimeStats {
		metrics.mu.RLock()
		totalRequests += metrics.RequestCount
		totalSuccess += metrics.SuccessCount
		totalFail += metrics.FailCount
		activeRoutes++
		metrics.mu.RUnlock()
	}

	successRate := float64(0)
	if totalRequests > 0 {
		successRate = float64(totalSuccess) / float64(totalRequests) * 100
	}

	return map[string]interface{}{
		"active_routes":   activeRoutes,
		"total_requests":  totalRequests,
		"total_success":   totalSuccess,
		"total_fail":      totalFail,
		"success_rate":    successRate,
		"active_buckets":  len(mc.minuteBuckets),
	}
}
