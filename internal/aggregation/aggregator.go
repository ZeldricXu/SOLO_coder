package aggregation

import (
	"context"
	"math"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/common/eventbus"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/common/utils"
	"go.uber.org/zap"
)

type AggregationType string

const (
	AggregationSum     AggregationType = "sum"
	AggregationAvg     AggregationType = "avg"
	AggregationMin     AggregationType = "min"
	AggregationMax     AggregationType = "max"
	AggregationCount   AggregationType = "count"
	AggregationPercent AggregationType = "percentile"
)

type DataPoint struct {
	DeviceID  string                 `json:"device_id"`
	Metric    string                 `json:"metric"`
	Value     float64                `json:"value"`
	Timestamp time.Time              `json:"timestamp"`
	Tags      map[string]string      `json:"tags"`
	RawData   map[string]interface{} `json:"raw_data,omitempty"`
}

type AggregationRule struct {
	ID             string                 `json:"id"`
	DeviceID       string                 `json:"device_id"`
	Metric         string                 `json:"metric"`
	AggregationType AggregationType       `json:"aggregation_type"`
	WindowSeconds  int                    `json:"window_seconds"`
	Parameters     map[string]interface{} `json:"parameters"`
	Enabled        bool                   `json:"enabled"`
}

type AggregationResult struct {
	ResultID       string                 `json:"result_id"`
	DeviceID       string                 `json:"device_id"`
	Metric         string                 `json:"metric"`
	AggregationType AggregationType       `json:"aggregation_type"`
	Value          float64                `json:"value"`
	Count          int64                  `json:"count"`
	WindowStart    time.Time              `json:"window_start"`
	WindowEnd      time.Time              `json:"window_end"`
	GeneratedAt    time.Time              `json:"generated_at"`
	Tags           map[string]string      `json:"tags"`
}

type Window struct {
	Start    time.Time
	End      time.Time
	Values   []float64
	Count    int64
	Tags     map[string]string
}

type Aggregator struct {
	rules          map[string]*AggregationRule
	activeWindows  map[string]*Window
	dataQueue      chan DataPoint
	results        []AggregationResult
	mu             sync.RWMutex
	ctx            context.Context
	cancel         context.CancelFunc
	wg             sync.WaitGroup
	maxResults     int
}

func NewAggregator(maxResults int) *Aggregator {
	ctx, cancel := context.WithCancel(context.Background())
	return &Aggregator{
		rules:         make(map[string]*AggregationRule),
		activeWindows: make(map[string]*Window),
		dataQueue:     make(chan DataPoint, 10000),
		results:       make([]AggregationResult, 0, maxResults),
		maxResults:    maxResults,
		ctx:           ctx,
		cancel:        cancel,
	}
}

func (a *Aggregator) AddRule(rule *AggregationRule) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if rule.ID == "" {
		rule.ID = utils.GenerateID("rule")
	}
	a.rules[rule.ID] = rule
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "aggregation.rule.added",
		Payload: map[string]interface{}{
			"rule_id": rule.ID,
			"metric":  rule.Metric,
		},
	})
	logger.Get().Info("Aggregation rule added",
		zap.String("rule_id", rule.ID),
		zap.String("metric", rule.Metric))
	return rule.ID
}

func (a *Aggregator) GetRule(id string) (*AggregationRule, bool) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	rule, exists := a.rules[id]
	return rule, exists
}

func (a *Aggregator) ListRules() []*AggregationRule {
	a.mu.RLock()
	defer a.mu.RUnlock()
	rules := make([]*AggregationRule, 0, len(a.rules))
	for _, r := range a.rules {
		rules = append(rules, r)
	}
	return rules
}

func (a *Aggregator) UpdateRule(id string, rule *AggregationRule) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	existing, exists := a.rules[id]
	if !exists {
		return false
	}
	rule.ID = id
	a.rules[id] = rule
	_ = existing
	return true
}

func (a *Aggregator) DeleteRule(id string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, exists := a.rules[id]; !exists {
		return false
	}
	delete(a.rules, id)
	return true
}

func (a *Aggregator) Ingest(data DataPoint) {
	select {
	case a.dataQueue <- data:
	case <-a.ctx.Done():
	}
}

func (a *Aggregator) Start() {
	a.wg.Add(1)
	go a.processLoop()
	a.wg.Add(1)
	go a.windowTicker()
	logger.Get().Info("Data aggregator started")
}

func (a *Aggregator) processLoop() {
	defer a.wg.Done()
	for {
		select {
		case data := <-a.dataQueue:
			a.processDataPoint(data)
		case <-a.ctx.Done():
			return
		}
	}
}

func (a *Aggregator) windowTicker() {
	defer a.wg.Done()
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			a.checkWindows()
		case <-a.ctx.Done():
			return
		}
	}
}

func (a *Aggregator) processDataPoint(data DataPoint) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, rule := range a.rules {
		if !rule.Enabled {
			continue
		}
		if rule.DeviceID != "" && rule.DeviceID != data.DeviceID {
			continue
		}
		if rule.Metric != "" && rule.Metric != data.Metric {
			continue
		}
		windowKey := rule.ID + "_" + data.DeviceID
		window, exists := a.activeWindows[windowKey]
		now := time.Now().UTC()
		if !exists {
			window = &Window{
				Start: now,
				End:   now.Add(time.Duration(rule.WindowSeconds) * time.Second),
				Tags:  make(map[string]string),
			}
			a.activeWindows[windowKey] = window
		}
		window.Values = append(window.Values, data.Value)
		window.Count++
		for k, v := range data.Tags {
			window.Tags[k] = v
		}
	}
}

func (a *Aggregator) checkWindows() {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := time.Now().UTC()
	for key, window := range a.activeWindows {
		if now.After(window.End) {
			for _, rule := range a.rules {
				windowKey := rule.ID + "_" + key[len(rule.ID)+1:]
				if windowKey == key {
					a.aggregateAndEmit(rule, window)
					break
				}
			}
			delete(a.activeWindows, key)
		}
	}
}

func (a *Aggregator) aggregateAndEmit(rule *AggregationRule, window *Window) {
	var value float64
	switch rule.AggregationType {
	case AggregationSum:
		value = a.sum(window.Values)
	case AggregationAvg:
		value = a.avg(window.Values)
	case AggregationMin:
		value = a.min(window.Values)
	case AggregationMax:
		value = a.max(window.Values)
	case AggregationCount:
		value = float64(window.Count)
	case AggregationPercent:
		percentile, _ := rule.Parameters["percentile"].(float64)
		value = a.percentile(window.Values, percentile)
	}
	result := AggregationResult{
		ResultID:        utils.GenerateID("res"),
		DeviceID:        rule.DeviceID,
		Metric:          rule.Metric,
		AggregationType: rule.AggregationType,
		Value:           value,
		Count:           window.Count,
		WindowStart:     window.Start,
		WindowEnd:       window.End,
		GeneratedAt:     time.Now().UTC(),
		Tags:            window.Tags,
	}
	a.results = append(a.results, result)
	if len(a.results) > a.maxResults {
		a.results = a.results[1:]
	}
	eventbus.GetBus().Publish(eventbus.Event{
		Type: "aggregation.result",
		Payload: result,
	})
	logger.Get().Debug("Aggregation result generated",
		zap.String("result_id", result.ResultID),
		zap.String("metric", result.Metric),
		zap.Float64("value", result.Value),
		zap.Int64("count", result.Count))
}

func (a *Aggregator) sum(values []float64) float64 {
	var sum float64
	for _, v := range values {
		sum += v
	}
	return sum
}

func (a *Aggregator) avg(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	return a.sum(values) / float64(len(values))
}

func (a *Aggregator) min(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	min := values[0]
	for _, v := range values {
		if v < min {
			min = v
		}
	}
	return min
}

func (a *Aggregator) max(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	max := values[0]
	for _, v := range values {
		if v > max {
			max = v
		}
	}
	return max
}

func (a *Aggregator) percentile(values []float64, p float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	for i := 0; i < len(sorted); i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[j] < sorted[i] {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}
	index := int(math.Ceil(float64(len(sorted)) * p / 100))
	if index >= len(sorted) {
		index = len(sorted) - 1
	}
	return sorted[index]
}

func (a *Aggregator) GetResults(deviceID string, metric string) []AggregationResult {
	a.mu.RLock()
	defer a.mu.RUnlock()
	var results []AggregationResult
	for _, r := range a.results {
		if (deviceID == "" || r.DeviceID == deviceID) && (metric == "" || r.Metric == metric) {
			results = append(results, r)
		}
	}
	return results
}

func (a *Aggregator) Stop() {
	a.cancel()
	close(a.dataQueue)
	a.wg.Wait()
	logger.Get().Info("Data aggregator stopped")
}
