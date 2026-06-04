package bench

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
)

type DiffResult struct {
	Baseline  string
	Current   string
	QPSDiff   float64
	P50Diff   float64
	P90Diff   float64
	P99Diff   float64
	ErrorDiff float64
}

func DiffReports(baseline, current *ReportData) *DiffResult {
	d := &DiffResult{
		Baseline: baseline.Timestamp,
		Current:  current.Timestamp,
	}

	if baseline.QPS > 0 {
		d.QPSDiff = ((current.QPS - baseline.QPS) / baseline.QPS) * 100
	}

	d.P50Diff = diffPercentile(baseline.Percentiles["P50"], current.Percentiles["P50"])
	d.P90Diff = diffPercentile(baseline.Percentiles["P90"], current.Percentiles["P90"])
	d.P99Diff = diffPercentile(baseline.Percentiles["P99"], current.Percentiles["P99"])

	d.ErrorDiff = current.ErrorRate - baseline.ErrorRate

	return d
}

func diffPercentile(baseline, current string) float64 {
	bDur, err := parseDurationString(baseline)
	if err != nil {
		return 0
	}
	cDur, err := parseDurationString(current)
	if err != nil {
		return 0
	}
	if bDur == 0 {
		return 0
	}
	return ((cDur - bDur) / bDur) * 100
}

func parseDurationString(s string) (float64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, nil
	}
	if strings.HasSuffix(s, "µs") {
		v, err := strconv.ParseFloat(strings.TrimSuffix(s, "µs"), 64)
		return v / 1000, err
	}
	if strings.HasSuffix(s, "ms") {
		v, err := strconv.ParseFloat(strings.TrimSuffix(s, "ms"), 64)
		return v, err
	}
	if strings.HasSuffix(s, "s") && !strings.Contains(s, "m") {
		v, err := strconv.ParseFloat(strings.TrimSuffix(s, "s"), 64)
		return v * 1000, err
	}
	if strings.HasSuffix(s, "m") {
		v, err := strconv.ParseFloat(strings.TrimSuffix(s, "m"), 64)
		return v * 60000, err
	}
	return strconv.ParseFloat(s, 64)
}

func (d *DiffResult) String() string {
	var b strings.Builder

	b.WriteString(fmt.Sprintf("\n%s━━━ Benchmark Diff ━━━%s\n", "\033[1m", "\033[0m"))
	b.WriteString(fmt.Sprintf("  Baseline: %s\n", d.Baseline))
	b.WriteString(fmt.Sprintf("  Current:  %s\n\n", d.Current))

	b.WriteString(fmt.Sprintf("%s─── Performance Changes ───%s\n", "\033[36m", "\033[0m"))

	qpsColor := "\033[32m"
	qpsArrow := "↑"
	if d.QPSDiff < 0 {
		qpsColor = "\033[31m"
		qpsArrow = "↓"
	}
	b.WriteString(fmt.Sprintf("  QPS:    %s%s%.2f%%%s\n", qpsColor, qpsArrow, d.QPSDiff, "\033[0m"))

	p50Color := "\033[31m"
	p50Arrow := "↑"
	if d.P50Diff < 0 {
		p50Color = "\033[32m"
		p50Arrow = "↓"
	}
	b.WriteString(fmt.Sprintf("  P50:    %s%s%.2f%%%s (latency)\n", p50Color, p50Arrow, d.P50Diff, "\033[0m"))

	p90Color := "\033[31m"
	p90Arrow := "↑"
	if d.P90Diff < 0 {
		p90Color = "\033[32m"
		p90Arrow = "↓"
	}
	b.WriteString(fmt.Sprintf("  P90:    %s%s%.2f%%%s (latency)\n", p90Color, p90Arrow, d.P90Diff, "\033[0m"))

	p99Color := "\033[31m"
	p99Arrow := "↑"
	if d.P99Diff < 0 {
		p99Color = "\033[32m"
		p99Arrow = "↓"
	}
	b.WriteString(fmt.Sprintf("  P99:    %s%s%.2f%%%s (latency)\n", p99Color, p99Arrow, d.P99Diff, "\033[0m"))

	errColor := "\033[32m"
	errArrow := "↓"
	if d.ErrorDiff > 0 {
		errColor = "\033[31m"
		errArrow = "↑"
	}
	b.WriteString(fmt.Sprintf("  Error:  %s%s%.2f%%%s (error rate)\n", errColor, errArrow, d.ErrorDiff, "\033[0m"))

	return b.String()
}

func LoadReportData(path string) (*ReportData, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading report file: %w", err)
	}
	var report ReportData
	if err := json.Unmarshal(data, &report); err != nil {
		return nil, fmt.Errorf("parsing report JSON: %w", err)
	}
	return &report, nil
}
