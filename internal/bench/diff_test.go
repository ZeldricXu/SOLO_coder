package bench

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestDiffReports_QPSIncrease(t *testing.T) {
	baseline := &ReportData{
		Timestamp: "2026-01-01T00:00:00Z",
		QPS:       100,
		Percentiles: map[string]string{
			"P50": "10ms",
			"P90": "50ms",
			"P99": "100ms",
		},
		ErrorRate: 1.0,
	}
	current := &ReportData{
		Timestamp: "2026-01-02T00:00:00Z",
		QPS:       120,
		Percentiles: map[string]string{
			"P50": "8ms",
			"P90": "40ms",
			"P99": "80ms",
		},
		ErrorRate: 0.5,
	}

	diff := DiffReports(baseline, current)

	assert.Equal(t, baseline.Timestamp, diff.Baseline)
	assert.Equal(t, current.Timestamp, diff.Current)
	assert.InDelta(t, 20.0, diff.QPSDiff, 0.01)
	assert.InDelta(t, -20.0, diff.P50Diff, 0.01)
	assert.InDelta(t, -20.0, diff.P90Diff, 0.01)
	assert.InDelta(t, -20.0, diff.P99Diff, 0.01)
	assert.InDelta(t, -0.5, diff.ErrorDiff, 0.01)
}

func TestDiffReports_QPSDecrease(t *testing.T) {
	baseline := &ReportData{
		Timestamp: "2026-01-01T00:00:00Z",
		QPS:       100,
		Percentiles: map[string]string{
			"P50": "10ms",
			"P90": "50ms",
			"P99": "100ms",
		},
		ErrorRate: 1.0,
	}
	current := &ReportData{
		Timestamp: "2026-01-02T00:00:00Z",
		QPS:       80,
		Percentiles: map[string]string{
			"P50": "15ms",
			"P90": "75ms",
			"P99": "150ms",
		},
		ErrorRate: 3.0,
	}

	diff := DiffReports(baseline, current)

	assert.InDelta(t, -20.0, diff.QPSDiff, 0.01)
	assert.InDelta(t, 50.0, diff.P50Diff, 0.01)
	assert.InDelta(t, 2.0, diff.ErrorDiff, 0.01)
}

func TestDiffReports_ZeroBaseline(t *testing.T) {
	baseline := &ReportData{
		Timestamp:   "2026-01-01T00:00:00Z",
		QPS:         0,
		Percentiles: map[string]string{},
		ErrorRate:   0,
	}
	current := &ReportData{
		Timestamp:   "2026-01-02T00:00:00Z",
		QPS:         50,
		Percentiles: map[string]string{},
		ErrorRate:   0,
	}

	diff := DiffReports(baseline, current)
	assert.InDelta(t, 0, diff.QPSDiff, 0.01)
}

func TestDiffResult_String(t *testing.T) {
	diff := &DiffResult{
		Baseline:  "2026-01-01",
		Current:   "2026-01-02",
		QPSDiff:   20.0,
		P50Diff:   -10.0,
		P90Diff:   5.0,
		P99Diff:   15.0,
		ErrorDiff: -2.0,
	}

	s := diff.String()
	assert.Contains(t, s, "Benchmark Diff")
	assert.Contains(t, s, "20.00%")
}

func TestReportToJSON(t *testing.T) {
	stats := &Stats{
		StartTime:     time.Now(),
		TotalRequests: 100,
		SuccessCount:  95,
		ErrorCount:    5,
		QPS:           50.5,
		Latencies:     []time.Duration{10 * time.Millisecond, 20 * time.Millisecond},
		Errors:        map[string]int{"timeout": 5},
	}
	report := NewReport(stats)

	jsonData, err := report.ToJSON()
	assert.NoError(t, err)
	assert.Contains(t, string(jsonData), "total_requests")
	assert.Contains(t, string(jsonData), "qps")
	assert.Contains(t, string(jsonData), "percentiles")
}
