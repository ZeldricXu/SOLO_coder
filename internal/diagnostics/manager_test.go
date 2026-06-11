package diagnostics

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestDiagnosticManager_RecordAndQuery(t *testing.T) {
	dm := NewDiagnosticManagerWithConfig(100, 100*time.Millisecond, 10*time.Minute)
	dm.Start()
	defer dm.Stop()

	now := time.Now()

	dm.Record(&RequestMetric{
		Timestamp:          now.Add(-500 * time.Millisecond),
		Path:               "/api/users",
		RouteID:            "route-1",
		Method:             "GET",
		StatusCode:         200,
		Latency:            50 * time.Millisecond,
		RateLimitRejected:  false,
		CircuitBreakerOpen: false,
	})

	dm.Record(&RequestMetric{
		Timestamp:          now.Add(-400 * time.Millisecond),
		Path:               "/api/users",
		RouteID:            "route-1",
		Method:             "GET",
		StatusCode:         200,
		Latency:            100 * time.Millisecond,
		RateLimitRejected:  false,
		CircuitBreakerOpen: false,
	})

	dm.Record(&RequestMetric{
		Timestamp:          now.Add(-300 * time.Millisecond),
		Path:               "/api/users",
		RouteID:            "route-1",
		Method:             "GET",
		StatusCode:         500,
		Latency:            200 * time.Millisecond,
		RateLimitRejected:  false,
		CircuitBreakerOpen: false,
		ErrorType:          "internal_error",
	})

	dm.Record(&RequestMetric{
		Timestamp:          now.Add(-200 * time.Millisecond),
		Path:               "/api/users",
		RouteID:            "route-1",
		Method:             "GET",
		StatusCode:         429,
		Latency:            10 * time.Millisecond,
		RateLimitRejected:  true,
		CircuitBreakerOpen: false,
		ErrorType:          "rate_limit",
	})

	dm.Record(&RequestMetric{
		Timestamp:          now.Add(-100 * time.Millisecond),
		Path:               "/api/orders",
		RouteID:            "route-2",
		Method:             "POST",
		StatusCode:         503,
		Latency:            5 * time.Millisecond,
		RateLimitRejected:  false,
		CircuitBreakerOpen: true,
		ErrorType:          "circuit_breaker",
	})

	dm.Flush()

	filter := &DiagnosticFilter{
		StartTime: now.Add(-1 * time.Second),
		EndTime:   now,
		Path:      "/api/users",
		Step:      1,
	}

	summary, err := dm.Query(filter)
	assert.NoError(t, err)
	assert.NotNil(t, summary)

	assert.Equal(t, int64(4), summary.TotalRequests)
	assert.Equal(t, int64(2), summary.TotalSuccess)
	assert.Equal(t, int64(2), summary.TotalErrors)
	assert.Equal(t, int64(1), summary.TotalRateLimitRej)
	assert.Equal(t, int64(0), summary.TotalCircuitBreaker)
	assert.InDelta(t, 50.0, summary.OverallSuccessRate, 0.1)
	assert.True(t, len(summary.TimeSeries) > 0)
}

func TestDiagnosticManager_QueryWithRouteIDFilter(t *testing.T) {
	dm := NewDiagnosticManagerWithConfig(100, 100*time.Millisecond, 10*time.Minute)
	dm.Start()
	defer dm.Stop()

	now := time.Now()

	dm.Record(&RequestMetric{
		Timestamp:  now.Add(-200 * time.Millisecond),
		Path:         "/api/test",
		RouteID:      "route-1",
		Method:       "GET",
		StatusCode:   200,
		Latency:      50 * time.Millisecond,
	})

	dm.Record(&RequestMetric{
		Timestamp:  now.Add(-100 * time.Millisecond),
		Path:         "/api/test",
		RouteID:      "route-2",
		Method:       "GET",
		StatusCode:   200,
		Latency:      60 * time.Millisecond,
	})

	dm.Flush()

	filter := &DiagnosticFilter{
		StartTime: now.Add(-1 * time.Second),
		EndTime:   now,
		RouteID:   "route-1",
		Step:      1,
	}

	summary, err := dm.Query(filter)
	assert.NoError(t, err)
	assert.Equal(t, int64(1), summary.TotalRequests)
}

func TestDiagnosticManager_QueryWithErrorTypeFilter(t *testing.T) {
	dm := NewDiagnosticManagerWithConfig(100, 100*time.Millisecond, 10*time.Minute)
	dm.Start()
	defer dm.Stop()

	now := time.Now()

	dm.Record(&RequestMetric{
		Timestamp: now.Add(-200 * time.Millisecond),
		Path:      "/api/test",
		RouteID:   "route-1",
		Method:    "GET",
		StatusCode: 500,
		Latency:   50 * time.Millisecond,
		ErrorType: "internal_error",
	})

	dm.Record(&RequestMetric{
		Timestamp: now.Add(-100 * time.Millisecond),
		Path:      "/api/test",
		RouteID:   "route-1",
		Method:    "GET",
		StatusCode: 500,
		Latency:   60 * time.Millisecond,
		ErrorType: "timeout",
	})

	dm.Flush()

	filter := &DiagnosticFilter{
		StartTime: now.Add(-1 * time.Second),
		EndTime:   now,
		ErrorType: "timeout",
		Step:      1,
	}

	summary, err := dm.Query(filter)
	assert.NoError(t, err)
	assert.Equal(t, int64(1), summary.TotalRequests)
	assert.Equal(t, int64(1), summary.TotalErrors)
}

func TestDiagnosticManager_PathWildcard(t *testing.T) {
	dm := NewDiagnosticManagerWithConfig(100, 100*time.Millisecond, 10*time.Minute)
	dm.Start()
	defer dm.Stop()

	now := time.Now()

	dm.Record(&RequestMetric{
		Timestamp:  now.Add(-200 * time.Millisecond),
		Path:         "/api/v1/users",
		RouteID:      "route-1",
		Method:       "GET",
		StatusCode:   200,
		Latency:      50 * time.Millisecond,
	})

	dm.Record(&RequestMetric{
		Timestamp:  now.Add(-100 * time.Millisecond),
		Path:         "/api/v1/orders",
		RouteID:      "route-2",
		Method:       "GET",
		StatusCode:   200,
		Latency:      60 * time.Millisecond,
	})

	dm.Flush()

	filter := &DiagnosticFilter{
		StartTime: now.Add(-1 * time.Second),
		EndTime:   now,
		Path:      "/api/v1/*",
		Step:      1,
	}

	summary, err := dm.Query(filter)
	assert.NoError(t, err)
	assert.Equal(t, int64(2), summary.TotalRequests)
}

func TestDiagnosticManager_BufferAutoFlush(t *testing.T) {
	bufferSize := 5
	dm := NewDiagnosticManagerWithConfig(bufferSize, 100*time.Millisecond, 10*time.Minute)
	dm.Start()
	defer dm.Stop()

	now := time.Now()

	for i := 0; i < bufferSize-1; i++ {
		dm.Record(&RequestMetric{
			Timestamp:  now.Add(time.Duration(-i) * 10 * time.Millisecond),
			Path:         "/api/test",
			RouteID:      "route-1",
			Method:       "GET",
			StatusCode:   200,
			Latency:      50 * time.Millisecond,
		})
	}

	assert.Equal(t, bufferSize-1, dm.GetBufferCount())

	dm.Record(&RequestMetric{
		Timestamp:  now,
		Path:         "/api/test",
		RouteID:      "route-1",
		Method:       "GET",
		StatusCode:   200,
		Latency:      50 * time.Millisecond,
	})

	assert.Equal(t, 0, dm.GetBufferCount())
	assert.Greater(t, dm.GetBucketCount(), 0)
}

func TestDiagnosticManager_StartStop(t *testing.T) {
	dm := NewDiagnosticManagerWithConfig(100, 10*time.Millisecond, 50*time.Millisecond)

	assert.False(t, dm.running)

	dm.Start()
	assert.True(t, dm.running)

	dm.Start()
	assert.True(t, dm.running)

	dm.Stop()
	assert.False(t, dm.running)

	dm.Stop()
	assert.False(t, dm.running)
}

func TestLatencySample_Percentile(t *testing.T) {
	ls := &latencySample{}

	for i := 1; i <= 100; i++ {
		ls.Add(float64(i))
	}

	assert.InDelta(t, 50.0, ls.Percentile(50), 1.0)
	assert.InDelta(t, 90.0, ls.Percentile(90), 1.0)
	assert.InDelta(t, 99.0, ls.Percentile(99), 1.0)
}

func TestLatencySample_Empty(t *testing.T) {
	ls := &latencySample{}
	assert.Equal(t, 0.0, ls.Percentile(50))
}

func TestDiagnosticManager_DefaultValues(t *testing.T) {
	dm := NewDiagnosticManager()
	dm.Start()
	defer dm.Stop()

	assert.Equal(t, 0, dm.GetBufferCount())
	assert.Equal(t, 0, dm.GetBucketCount())
}

func TestDiagnosticManager_QueryDefaultTimeRange(t *testing.T) {
	dm := NewDiagnosticManagerWithConfig(100, 100*time.Millisecond, 10*time.Minute)
	dm.Start()
	defer dm.Stop()

	now := time.Now()

	dm.Record(&RequestMetric{
		Timestamp:  now.Add(-100 * time.Millisecond),
		Path:         "/api/test",
		RouteID:      "route-1",
		Method:       "GET",
		StatusCode:   200,
		Latency:      50 * time.Millisecond,
	})

	dm.Flush()

	filter := &DiagnosticFilter{
		Step: 1,
	}

	summary, err := dm.Query(filter)
	assert.NoError(t, err)
	assert.NotNil(t, summary)
	assert.False(t, summary.StartTime.IsZero())
	assert.False(t, summary.EndTime.IsZero())
}
