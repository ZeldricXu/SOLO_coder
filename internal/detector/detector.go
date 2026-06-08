package detector

import (
	"context"
	"fmt"
	"log"
	"math"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/montanaflynn/stats"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/storage"
)

type Algorithm string

const (
	AlgorithmZScore Algorithm = "zscore"
	AlgorithmMAD    Algorithm = "mad"
)

type MetricType string

const (
	MetricErrorRate    MetricType = "error_rate"
	MetricP99Latency   MetricType = "p99_latency"
	MetricErrorPattern MetricType = "error_pattern"
)

type WindowData struct {
	ServiceName  string
	MetricType   MetricType
	Timestamp    time.Time
	Values       []float64
	Count        int64
	ErrorCount   int64
}

type DetectionEngine struct {
	cfg          config.DetectionConfig
	redis        *storage.RedisClient
	input        <-chan *models.LogEvent
	alertCh      chan *models.Alert
	rules        []config.DetectionRule
	rulesMu      sync.RWMutex
	windowData   map[string]*WindowData
	windowMu     sync.RWMutex
	wg           sync.WaitGroup
	stopCh       chan struct{}
	defaultAlgo  Algorithm
}

func NewDetectionEngine(cfg config.DetectionConfig, redis *storage.RedisClient, input <-chan *models.LogEvent) (*DetectionEngine, error) {
	algo := Algorithm(strings.ToLower(cfg.Algorithm))
	if algo != AlgorithmZScore && algo != AlgorithmMAD {
		algo = AlgorithmZScore
	}

	if cfg.WindowSize == 0 {
		cfg.WindowSize = 5 * time.Minute
	}
	if cfg.SlideStep == 0 {
		cfg.SlideStep = 1 * time.Minute
	}

	engine := &DetectionEngine{
		cfg:         cfg,
		redis:       redis,
		input:       input,
		alertCh:     make(chan *models.Alert, 1000),
		windowData:  make(map[string]*WindowData),
		stopCh:      make(chan struct{}),
		defaultAlgo: algo,
	}

	engine.loadRules(cfg.Rules)

	config.RegisterCallback(engine.onConfigChange)

	return engine, nil
}

func (d *DetectionEngine) loadRules(rules []config.DetectionRule) {
	d.rulesMu.Lock()
	defer d.rulesMu.Unlock()

	d.rules = make([]config.DetectionRule, 0, len(rules))
	for _, rule := range rules {
		if rule.Enabled {
			if rule.WindowSize == 0 {
				rule.WindowSize = d.cfg.WindowSize
			}
			if rule.MinObservations == 0 {
				rule.MinObservations = 10
			}
			d.rules = append(d.rules, rule)
		}
	}

	log.Printf("Loaded %d detection rules", len(d.rules))
}

func (d *DetectionEngine) onConfigChange(cfg *config.Config) {
	d.loadRules(cfg.Detection.Rules)
}

func (d *DetectionEngine) Start(ctx context.Context) error {
	d.wg.Add(2)
	go d.collectMetrics(ctx)
	go d.detectLoop(ctx)

	log.Printf("Detection engine started, algorithm: %s, window: %s", d.defaultAlgo, d.cfg.WindowSize)
	return nil
}

func (d *DetectionEngine) collectMetrics(ctx context.Context) {
	defer d.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-d.stopCh:
			return
		case event := <-d.input:
			if event == nil {
				continue
			}
			d.processEvent(ctx, event)
		}
	}
}

func (d *DetectionEngine) processEvent(ctx context.Context, event *models.LogEvent) {
	d.rulesMu.RLock()
	rules := d.rules
	d.rulesMu.RUnlock()

	window := event.Timestamp.Truncate(d.cfg.SlideStep)

	for _, rule := range rules {
		if rule.ServiceName != "" && rule.ServiceName != event.ServiceName {
			continue
		}

		metricType := MetricType(rule.Metric)
		var value float64
		var isError bool

		switch metricType {
		case MetricErrorRate:
			isError = event.Level == models.LevelError || event.Level == models.LevelFatal
			if isError {
				value = 1
			} else {
				value = 0
			}
		case MetricP99Latency:
			if event.ResponseTime > 0 {
				value = float64(event.ResponseTime)
			} else {
				continue
			}
		case MetricErrorPattern:
			if event.ErrorCode != "" {
				if d.matchPattern(event, rule) {
					value = 1
					isError = true
				} else {
					continue
				}
			} else {
				continue
			}
		default:
			continue
		}

		key := fmt.Sprintf("%s:%s:%s", rule.ServiceName, metricType, rule.ID)

		d.addToWindow(ctx, key, window, value, isError)

		d.redis.AddToWindow(ctx, key, window, value)
		d.redis.ExpireWindow(ctx, key, window, 24*time.Hour)
	}
}

func (d *DetectionEngine) addToWindow(ctx context.Context, key string, window time.Time, value float64, isError bool) {
	d.windowMu.Lock()
	defer d.windowMu.Unlock()

	wd, ok := d.windowData[key]
	if !ok {
		wd = &WindowData{
			Timestamp:  window,
			Values:     make([]float64, 0),
		}
		d.windowData[key] = wd
	}

	wd.Values = append(wd.Values, value)
	wd.Count++
	if isError {
		wd.ErrorCount++
	}
}

func (d *DetectionEngine) matchPattern(event *models.LogEvent, rule config.DetectionRule) bool {
	patterns, ok := rule.Config["patterns"].([]interface{})
	if !ok {
		return true
	}

	for _, p := range patterns {
		pattern := fmt.Sprintf("%v", p)
		if strings.Contains(event.Message, pattern) ||
			strings.Contains(event.RawMessage, pattern) ||
			event.ErrorCode == pattern {
			return true
		}
	}

	return len(patterns) == 0
}

func (d *DetectionEngine) detectLoop(ctx context.Context) {
	defer d.wg.Done()

	ticker := time.NewTicker(d.cfg.SlideStep)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-d.stopCh:
			return
		case <-ticker.C:
			d.runDetection(ctx)
		}
	}
}

func (d *DetectionEngine) runDetection(ctx context.Context) {
	d.rulesMu.RLock()
	rules := d.rules
	d.rulesMu.RUnlock()

	now := time.Now()

	for _, rule := range rules {
		go d.detectRule(ctx, rule, now)
	}

	d.cleanOldWindows(now.Add(-24 * time.Hour))
}

func (d *DetectionEngine) detectRule(ctx context.Context, rule config.DetectionRule, now time.Time) {
	metricType := MetricType(rule.Metric)
	key := fmt.Sprintf("%s:%s:%s", rule.ServiceName, metricType, rule.ID)

	endTime := now.Truncate(d.cfg.SlideStep)
	startTime := endTime.Add(-rule.WindowSize)

	stats, err := d.redis.GetWindowStats(ctx, key, startTime, endTime)
	if err != nil {
		log.Printf("Failed to get window stats for %s: %v", key, err)
		return
	}

	if stats.Count < int64(rule.MinObservations) {
		return
	}

	values := stats.Values

	algo := Algorithm(strings.ToLower(rule.Algorithm))
	if algo == "" {
		algo = d.defaultAlgo
	}

	var score float64
	var metricValue float64

	switch metricType {
	case MetricErrorRate:
		metricValue = float64(stats.ErrorCount) / float64(stats.Count) * 100

		historicalData, _ := d.getHistoricalData(ctx, key, endTime, 7*24*time.Hour)
		var dataset []float64
		if len(historicalData) > 0 {
			dataset = append(historicalData, metricValue)
		} else {
			dataset = values
		}
		if algo == AlgorithmZScore {
			score = CalculateZScore(dataset, metricValue)
		} else {
			score = CalculateMAD(dataset, metricValue)
		}

	case MetricP99Latency:
		sorted := make([]float64, len(values))
		copy(sorted, values)
		sort.Float64s(sorted)
		metricValue = Percentile(sorted, 99)

		historicalData, _ := d.getHistoricalData(ctx, key, endTime, 7*24*time.Hour)
		var dataset []float64
		if len(historicalData) > 0 {
			dataset = append(historicalData, metricValue)
		} else {
			dataset = values
		}
		if algo == AlgorithmZScore {
			score = CalculateZScore(dataset, metricValue)
		} else {
			score = CalculateMAD(dataset, metricValue)
		}

	case MetricErrorPattern:
		metricValue = float64(stats.Count)
	}

	if d.isAnomaly(metricValue, score, rule.Threshold) {
		alert := d.createAlert(rule, metricType, metricValue, score, algo, values)
		select {
		case d.alertCh <- alert:
		default:
			log.Printf("Alert channel full, dropping alert: %s", alert.ID)
		}
	}
}

func (d *DetectionEngine) getHistoricalData(ctx context.Context, key string, endTime time.Time, lookback time.Duration) ([]float64, error) {
	historical := make([]float64, 0)

	maxPoints := 100
	step := d.cfg.SlideStep
	if step < time.Minute {
		step = time.Minute
	}
	numPoints := int(lookback / step)
	if numPoints > maxPoints {
		numPoints = maxPoints
	}

	for i := 1; i <= numPoints; i++ {
		windowEnd := endTime.Add(-time.Duration(i) * step)
		windowStart := windowEnd.Add(-step)

		values, err := d.redis.GetWindowValues(ctx, key, windowStart, windowEnd)
		if err != nil {
			continue
		}

		if len(values) == 0 {
			continue
		}

		sorted := make([]float64, len(values))
		copy(sorted, values)
		sort.Float64s(sorted)
		p99 := Percentile(sorted, 99)
		historical = append(historical, p99)
	}

	return historical, nil
}

func (d *DetectionEngine) isAnomaly(value, score, threshold float64) bool {
	if score >= threshold {
		return true
	}
	return false
}

func (d *DetectionEngine) createAlert(rule config.DetectionRule, metricType MetricType, value, score float64, algo Algorithm, values []float64) *models.Alert {
	alertType := models.AlertTypeCustomRule
	switch metricType {
	case MetricErrorRate:
		alertType = models.AlertTypeErrorRate
	case MetricP99Latency:
		alertType = models.AlertTypeP99Latency
	case MetricErrorPattern:
		alertType = models.AlertTypeErrorPattern
	}

	severity := models.Severity(strings.ToUpper(rule.Severity))
	if severity == "" {
		if score >= 4.0 {
			severity = models.SeverityCritical
		} else if score >= 3.0 {
			severity = models.SeverityHigh
		} else if score >= 2.0 {
			severity = models.SeverityMedium
		} else {
			severity = models.SeverityLow
		}
	}

	alert := models.NewAlert(alertType, severity, rule.ServiceName)
	alert.Title = fmt.Sprintf("%s anomaly detected in %s", rule.Name, rule.ServiceName)
	alert.Description = fmt.Sprintf("%s for service %s exceeded threshold. Value: %.2f, Threshold: %.2f",
		rule.Name, rule.ServiceName, value, rule.Threshold)
	alert.MetricValue = value
	alert.Threshold = rule.Threshold
	alert.WindowSize = rule.WindowSize
	alert.Algorithm = string(algo)

	if algo == AlgorithmZScore {
		alert.ZScore = score
	} else {
		alert.MADScore = score
	}

	alert.DeduplicationKey = fmt.Sprintf("%s:%s:%s", alertType, rule.ServiceName, rule.ID)

	if patterns, ok := rule.Config["patterns"].([]interface{}); ok {
		for _, p := range patterns {
			alert.Tags = append(alert.Tags, fmt.Sprintf("%v", p))
		}
	}

	return alert
}

func (d *DetectionEngine) cleanOldWindows(before time.Time) {
	d.windowMu.Lock()
	defer d.windowMu.Unlock()

	for key, wd := range d.windowData {
		if wd.Timestamp.Before(before) {
			delete(d.windowData, key)
		}
	}
}

func CalculateZScore(data []float64, value float64) float64 {
	if len(data) < 2 {
		return 0
	}

	mean, err := stats.Mean(data)
	if err != nil {
		return 0
	}

	stdDev, err := stats.StandardDeviation(data)
	if err != nil || stdDev == 0 {
		return 0
	}

	return math.Abs(value - mean) / stdDev
}

func CalculateMAD(data []float64, value float64) float64 {
	if len(data) < 2 {
		return 0
	}

	median, err := stats.Median(data)
	if err != nil {
		return 0
	}

	absDeviations := make([]float64, len(data))
	for i, v := range data {
		absDeviations[i] = math.Abs(v - median)
	}

	mad, err := stats.Median(absDeviations)
	if err != nil || mad == 0 {
		return 0
	}

	return math.Abs(value - median) / (mad * 1.4826)
}

func Percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	if len(sorted) == 1 {
		return sorted[0]
	}

	index := (p / 100.0) * float64(len(sorted)-1)
	lower := int(math.Floor(index))
	upper := int(math.Ceil(index))

	if lower == upper {
		return sorted[lower]
	}

	weight := index - float64(lower)
	return sorted[lower]*(1-weight) + sorted[upper]*weight
}

func (d *DetectionEngine) Alerts() <-chan *models.Alert {
	return d.alertCh
}

func (d *DetectionEngine) Stop() {
	close(d.stopCh)
	d.wg.Wait()
	close(d.alertCh)
}
