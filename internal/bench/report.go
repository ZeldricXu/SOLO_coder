package bench

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"time"
)

const (
	ansiRed    = "\033[31m"
	ansiGreen  = "\033[32m"
	ansiYellow = "\033[33m"
	ansiCyan   = "\033[36m"
	ansiBold   = "\033[1m"
	ansiReset  = "\033[0m"
)

type ReportData struct {
	Timestamp    string            `json:"timestamp"`
	Duration     string            `json:"duration"`
	TotalReqs    int64             `json:"total_requests"`
	SuccessCount int64             `json:"success_count"`
	ErrorCount   int64             `json:"error_count"`
	QPS          float64           `json:"qps"`
	ErrorRate    float64           `json:"error_rate"`
	Latency      LatencyData       `json:"latency"`
	Percentiles  map[string]string `json:"percentiles"`
	Errors       map[string]int    `json:"errors,omitempty"`
}

type LatencyData struct {
	Min string `json:"min"`
	Avg string `json:"avg"`
	Max string `json:"max"`
}

type Report struct {
	Stats       *Stats
	Percentiles map[string]time.Duration
	ErrorRate   float64
	AvgLatency  time.Duration
	MinLatency  time.Duration
	MaxLatency  time.Duration
}

func NewReport(stats *Stats) *Report {
	r := &Report{
		Stats:       stats,
		Percentiles: make(map[string]time.Duration),
	}

	latencies := make([]time.Duration, len(stats.Latencies))
	copy(latencies, stats.Latencies)
	sort.Slice(latencies, func(i, j int) bool {
		return latencies[i] < latencies[j]
	})

	if len(latencies) > 0 {
		r.MinLatency = latencies[0]
		r.MaxLatency = latencies[len(latencies)-1]

		var total time.Duration
		for _, l := range latencies {
			total += l
		}
		r.AvgLatency = total / time.Duration(len(latencies))

		r.Percentiles["P50"] = percentile(latencies, 50)
		r.Percentiles["P90"] = percentile(latencies, 90)
		r.Percentiles["P99"] = percentile(latencies, 99)
	}

	if stats.TotalRequests > 0 {
		r.ErrorRate = float64(stats.ErrorCount) / float64(stats.TotalRequests) * 100
	}

	return r
}

func percentile(sorted []time.Duration, p int) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	idx := (p * len(sorted)) / 100
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func (r *Report) String() string {
	var b []byte
	b = append(b, fmt.Sprintf("\n%s%s━━━ Benchmark Report ━━━%s\n", ansiBold, ansiCyan, ansiReset)...)
	b = append(b, fmt.Sprintf("%sTimestamp: %s%s\n\n", ansiYellow, r.Stats.StartTime.Format(time.RFC3339), ansiReset)...)

	b = append(b, fmt.Sprintf("%s─── Summary ───%s\n", ansiCyan, ansiReset)...)
	b = append(b, fmt.Sprintf("  Total Requests:  %s%d%s\n", ansiBold, r.Stats.TotalRequests, ansiReset)...)
	b = append(b, fmt.Sprintf("  Success:         %s%d%s\n", ansiGreen, r.Stats.SuccessCount, ansiReset)...)
	b = append(b, fmt.Sprintf("  Errors:          %s%d%s\n", ansiRed, r.Stats.ErrorCount, ansiReset)...)
	b = append(b, fmt.Sprintf("  QPS:             %s%.2f%s\n", ansiYellow, r.Stats.QPS, ansiReset)...)
	b = append(b, fmt.Sprintf("  Error Rate:      %.2f%%\n", r.ErrorRate)...)
	b = append(b, fmt.Sprintf("  Duration:        %s%v%s\n\n", ansiYellow, r.Stats.TotalDuration, ansiReset)...)

	b = append(b, fmt.Sprintf("%s─── Latency ───%s\n", ansiCyan, ansiReset)...)
	b = append(b, fmt.Sprintf("  Min:  %s%v%s\n", ansiGreen, r.MinLatency, ansiReset)...)
	b = append(b, fmt.Sprintf("  Avg:  %s%v%s\n", ansiYellow, r.AvgLatency, ansiReset)...)
	if p50, ok := r.Percentiles["P50"]; ok {
		b = append(b, fmt.Sprintf("  P50:  %s%v%s\n", ansiCyan, p50, ansiReset)...)
	}
	if p90, ok := r.Percentiles["P90"]; ok {
		b = append(b, fmt.Sprintf("  P90:  %s%v%s\n", ansiYellow, p90, ansiReset)...)
	}
	if p99, ok := r.Percentiles["P99"]; ok {
		b = append(b, fmt.Sprintf("  P99:  %s%v%s\n", ansiRed, p99, ansiReset)...)
	}
	b = append(b, fmt.Sprintf("  Max:  %s%v%s\n", ansiRed, r.MaxLatency, ansiReset)...)

	if len(r.Stats.Errors) > 0 {
		b = append(b, fmt.Sprintf("\n%s─── Error Breakdown ───%s\n", ansiRed, ansiReset)...)
		for msg, count := range r.Stats.Errors {
			b = append(b, fmt.Sprintf("  %s✗%s %s (x%d)\n", ansiRed, ansiReset, msg, count)...)
		}
	}

	return string(b)
}

func (r *Report) ToJSON() ([]byte, error) {
	data := ReportData{
		Timestamp:    r.Stats.StartTime.Format(time.RFC3339),
		Duration:     r.Stats.TotalDuration.String(),
		TotalReqs:    r.Stats.TotalRequests,
		SuccessCount: r.Stats.SuccessCount,
		ErrorCount:   r.Stats.ErrorCount,
		QPS:          r.Stats.QPS,
		ErrorRate:    r.ErrorRate,
		Latency: LatencyData{
			Min: r.MinLatency.String(),
			Avg: r.AvgLatency.String(),
			Max: r.MaxLatency.String(),
		},
		Percentiles: make(map[string]string),
		Errors:      r.Stats.Errors,
	}
	for k, v := range r.Percentiles {
		data.Percentiles[k] = v.String()
	}
	return json.MarshalIndent(data, "", "  ")
}

func (r *Report) LiveReport(ctx context.Context, stats *Stats, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			finalReport := NewReport(stats)
			fmt.Print(finalReport.String())
			return
		case <-ticker.C:
			report := NewReport(stats)

			fmt.Print("\033[2J\033[H")

			elapsed := time.Since(stats.StartTime)
			currentQPS := float64(0)
			if elapsed.Seconds() > 0 {
				currentQPS = float64(stats.TotalRequests) / elapsed.Seconds()
			}

			fmt.Printf("%s%s━━━ Live Benchmark ━━━%s\n", ansiBold, ansiCyan, ansiReset)
			fmt.Printf("  Elapsed:    %s%v%s\n", ansiYellow, elapsed.Round(time.Millisecond), ansiReset)
			fmt.Printf("  Requests:   %s%d%s (success: %s%d%s, errors: %s%d%s)\n",
				ansiBold, stats.TotalRequests, ansiReset,
				ansiGreen, stats.SuccessCount, ansiReset,
				ansiRed, stats.ErrorCount, ansiReset)
			fmt.Printf("  QPS:        %s%.2f%s\n", ansiYellow, currentQPS, ansiReset)

			if len(report.Percentiles) > 0 {
				fmt.Printf("\n  Latency:\n")
				if p50, ok := report.Percentiles["P50"]; ok {
					fmt.Printf("    P50:  %s%v%s\n", ansiCyan, p50, ansiReset)
				}
				if p90, ok := report.Percentiles["P90"]; ok {
					fmt.Printf("    P90:  %s%v%s\n", ansiYellow, p90, ansiReset)
				}
				if p99, ok := report.Percentiles["P99"]; ok {
					fmt.Printf("    P99:  %s%v%s\n", ansiRed, p99, ansiReset)
				}
			}
		}
	}
}
