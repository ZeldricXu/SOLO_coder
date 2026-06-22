package featureflag

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"math/big"
	"strings"
	"sync"
	"time"
)

type SwitchType string

const (
	SwitchTypeBoolean    SwitchType = "BOOLEAN"
	SwitchTypePercentage SwitchType = "PERCENTAGE"
	SwitchTypeWhitelist  SwitchType = "WHITELIST"
)

type StrategyOperator string

const (
	OperatorAND StrategyOperator = "AND"
	OperatorOR  StrategyOperator = "OR"
)

type WhitelistField string

const (
	FieldUserID     WhitelistField = "USER_ID"
	FieldDepartment WhitelistField = "DEPARTMENT"
	FieldTag        WhitelistField = "TAG"
)

type WhitelistOperator string

const (
	OpIn          WhitelistOperator = "IN"
	OpNotIn       WhitelistOperator = "NOT_IN"
	OpContains    WhitelistOperator = "CONTAINS"
	OpNotContains WhitelistOperator = "NOT_CONTAINS"
)

type EvaluationContext struct {
	UserID      string            `json:"user_id"`
	Department  string            `json:"department"`
	Tags        []string          `json:"tags"`
	Environment string            `json:"environment"`
	TenantID    string            `json:"tenant_id"`
	Attributes  map[string]string `json:"attributes"`
}

type EvaluationResult struct {
	Enabled    bool        `json:"enabled"`
	Matched    bool        `json:"matched"`
	Reason     string      `json:"reason"`
	SwitchKey  string      `json:"switch_key"`
	Value      interface{} `json:"value,omitempty"`
	Evaluation int64       `json:"-"`
	Latency    int64       `json:"-"`
}

type SwitchSnapshot struct {
	Key             string              `json:"key"`
	Type            SwitchType          `json:"type"`
	Enabled         bool                `json:"enabled"`
	BooleanValue    bool                `json:"boolean_value,omitempty"`
	PercentageValue int                 `json:"percentage_value,omitempty"`
	Strategies      []*StrategySnapshot `json:"strategies,omitempty"`
	UpdatedAt       time.Time           `json:"updated_at"`
}

type StrategySnapshot struct {
	ID         string                  `json:"id"`
	Operator   StrategyOperator        `json:"operator"`
	Priority   int                     `json:"priority"`
	Conditions []*ConditionSnapshot    `json:"conditions"`
}

type ConditionSnapshot struct {
	Field    WhitelistField    `json:"field"`
	Operator WhitelistOperator `json:"operator"`
	Values   []string          `json:"values"`
}

type SDKConfig struct {
	Version     int64            `json:"version"`
	Switches    []*SwitchSnapshot `json:"switches"`
	UpdatedAt   time.Time        `json:"updated_at"`
}

type SDKOptions struct {
	ServerURL          string
	AppKey             string
	AppSecret          string
	PollInterval       time.Duration
	LongPollTimeout    time.Duration
	CacheType          string
	CacheTTL           time.Duration
	MaxCacheSize       int
	CircuitBreakerThreshold int
	CircuitBreakerTimeout time.Duration
	FallbackEnabled    bool
	StatsEnabled       bool
	StatsReportInterval time.Duration
	ServiceName        string
	SDKVersion         string
}

func DefaultOptions() *SDKOptions {
	return &SDKOptions{
		ServerURL:              "http://localhost:8080",
		PollInterval:           30 * time.Second,
		LongPollTimeout:        60 * time.Second,
		CacheType:              "memory",
		CacheTTL:               5 * time.Minute,
		MaxCacheSize:           10000,
		CircuitBreakerThreshold: 5,
		CircuitBreakerTimeout:  30 * time.Second,
		FallbackEnabled:        true,
		StatsEnabled:           true,
		StatsReportInterval:    60 * time.Second,
		SDKVersion:             "1.0.0",
	}
}

type CacheBackend interface {
	Get(ctx context.Context, key string) (*SwitchSnapshot, bool)
	Set(ctx context.Context, key string, value *SwitchSnapshot) error
	Delete(ctx context.Context, key string) error
	GetAll(ctx context.Context) (map[string]*SwitchSnapshot, error)
	SetAll(ctx context.Context, switches map[string]*SwitchSnapshot) error
	Clear(ctx context.Context) error
	Close() error
}

type SwitchSource interface {
	Fetch(ctx context.Context, version int64) (*SDKConfig, error)
	Evaluate(ctx context.Context, key string, ctx2 *EvaluationContext) (*EvaluationResult, error)
	BatchEvaluate(ctx context.Context, ctx2 *EvaluationContext) (map[string]*EvaluationResult, error)
	ReportStats(ctx context.Context, stats *StatsReport) error
}

type StatsReport struct {
	SwitchKey    string  `json:"switch_key"`
	TotalCount   int64   `json:"total_count"`
	TrueCount    int64   `json:"true_count"`
	FalseCount   int64   `json:"false_count"`
	ErrorCount   int64   `json:"error_count"`
	AvgLatencyMs float64 `json:"avg_latency_ms"`
	P99LatencyMs float64 `json:"p99_latency_ms"`
	ServiceName  string  `json:"service_name"`
	SDKVersion   string  `json:"sdk_version"`
}

type CircuitBreaker interface {
	Allow() bool
	Success()
	Failure()
	State() CircuitBreakerState
	Reset()
}

type CircuitBreakerState int

const (
	StateClosed CircuitBreakerState = iota
	StateHalfOpen
	StateOpen
)

func PercentageHash(userID string, salt string) int {
	h := sha256.New()
	h.Write([]byte(userID + "|" + salt))
	hashBytes := h.Sum(nil)

	hashInt := new(big.Int).SetBytes(hashBytes[:8])
	mod := new(big.Int).Mod(hashInt, big.NewInt(100))
	return int(mod.Int64())
}

func HashUserID(userID string) string {
	h := sha256.New()
	h.Write([]byte(userID))
	return hex.EncodeToString(h.Sum(nil))
}

func ContainsString(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func EvaluateSwitch(sw *SwitchSnapshot, ctx *EvaluationContext) *EvaluationResult {
	result := &EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
	}

	if !sw.Enabled {
		result.Reason = "switch_disabled"
		return result
	}

	switch sw.Type {
	case SwitchTypeBoolean:
		return evaluateBoolean(sw, ctx)
	case SwitchTypePercentage:
		return evaluatePercentage(sw, ctx)
	case SwitchTypeWhitelist:
		return evaluateWhitelist(sw, ctx)
	default:
		result.Reason = "unknown_type"
		return result
	}
}

func evaluateBoolean(sw *SwitchSnapshot, ctx *EvaluationContext) *EvaluationResult {
	return &EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
		Matched:   sw.BooleanValue,
		Reason:    "boolean_type",
		Value:     sw.BooleanValue,
	}
}

func evaluatePercentage(sw *SwitchSnapshot, ctx *EvaluationContext) *EvaluationResult {
	result := &EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
	}

	if ctx.UserID == "" {
		result.Reason = "no_user_id"
		return result
	}

	hash := PercentageHash(ctx.UserID, sw.Key)
	percentagePassed := hash < sw.PercentageValue

	if len(sw.Strategies) == 0 {
		result.Matched = percentagePassed
		if percentagePassed {
			result.Reason = "percentage_passed"
		} else {
			result.Reason = "percentage_not_passed"
		}
		result.Value = percentagePassed
		return result
	}

	for _, strategy := range sw.Strategies {
		strategyMatched := evaluateStrategy(strategy, ctx)

		switch strategy.Operator {
		case OperatorAND:
			if percentagePassed && strategyMatched {
				result.Matched = true
				result.Reason = "percentage_and_strategy_passed"
				result.Value = true
				return result
			}
		case OperatorOR:
			if percentagePassed || strategyMatched {
				result.Matched = true
				result.Reason = "percentage_or_strategy_passed"
				result.Value = true
				return result
			}
		}
	}

	result.Reason = "no_strategy_matched"
	return result
}

func evaluateWhitelist(sw *SwitchSnapshot, ctx *EvaluationContext) *EvaluationResult {
	result := &EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
	}

	if len(sw.Strategies) == 0 {
		result.Reason = "no_strategy_configured"
		return result
	}

	for _, strategy := range sw.Strategies {
		if evaluateStrategy(strategy, ctx) {
			result.Matched = true
			result.Reason = "strategy_matched"
			result.Value = true
			return result
		}
	}

	result.Reason = "no_strategy_matched"
	return result
}

func evaluateStrategy(strategy *StrategySnapshot, ctx *EvaluationContext) bool {
	if len(strategy.Conditions) == 0 {
		return true
	}

	allMatch := true
	anyMatch := false

	for _, condition := range strategy.Conditions {
		matched := evaluateCondition(condition, ctx)

		switch strategy.Operator {
		case OperatorAND:
			if !matched {
				allMatch = false
			}
		case OperatorOR:
			if matched {
				anyMatch = true
			}
		}
	}

	switch strategy.Operator {
	case OperatorAND:
		return allMatch
	case OperatorOR:
		return anyMatch
	default:
		return false
	}
}

func evaluateCondition(cond *ConditionSnapshot, ctx *EvaluationContext) bool {
	var fieldValue string
	var fieldValues []string

	switch cond.Field {
	case FieldUserID:
		fieldValue = ctx.UserID
	case FieldDepartment:
		fieldValue = ctx.Department
	case FieldTag:
		fieldValues = ctx.Tags
	default:
		return false
	}

	switch cond.Operator {
	case OpIn:
		if cond.Field == FieldTag {
			for _, v := range fieldValues {
				if ContainsString(cond.Values, v) {
					return true
				}
			}
			return false
		}
		return ContainsString(cond.Values, fieldValue)

	case OpNotIn:
		if cond.Field == FieldTag {
			for _, v := range fieldValues {
				if ContainsString(cond.Values, v) {
					return false
				}
			}
			return true
		}
		return !ContainsString(cond.Values, fieldValue)

	case OpContains:
		if cond.Field == FieldTag {
			for _, tag := range fieldValues {
				for _, v := range cond.Values {
					if strings.Contains(tag, v) {
						return true
					}
				}
			}
			return false
		}
		for _, v := range cond.Values {
			if strings.Contains(fieldValue, v) {
				return true
			}
		}
		return false

	case OpNotContains:
		if cond.Field == FieldTag {
			for _, tag := range fieldValues {
				for _, v := range cond.Values {
					if strings.Contains(tag, v) {
						return false
					}
				}
			}
			return true
		}
		for _, v := range cond.Values {
			if strings.Contains(fieldValue, v) {
				return false
			}
		}
		return true

	default:
		return false
	}
}

type StatsCollector struct {
	mu            sync.Mutex
	switchStats   map[string]*switchStats
	reportChan    chan *StatsReport
	stopChan      chan struct{}
	reportFunc    func(context.Context, *StatsReport) error
	serviceName   string
	sdkVersion    string
	reportInterval time.Duration
}

type switchStats struct {
	totalCount   int64
	trueCount    int64
	falseCount   int64
	errorCount   int64
	latencies    []int64
}

func NewStatsCollector(serviceName, sdkVersion string, reportInterval time.Duration, reportFunc func(context.Context, *StatsReport) error) *StatsCollector {
	sc := &StatsCollector{
		switchStats:    make(map[string]*switchStats),
		reportChan:     make(chan *StatsReport, 100),
		stopChan:       make(chan struct{}),
		reportFunc:     reportFunc,
		serviceName:    serviceName,
		sdkVersion:     sdkVersion,
		reportInterval: reportInterval,
	}
	go sc.run()
	return sc
}

func (sc *StatsCollector) Record(key string, result *EvaluationResult, latency int64) {
	sc.mu.Lock()
	defer sc.mu.Unlock()

	stats, ok := sc.switchStats[key]
	if !ok {
		stats = &switchStats{
			latencies: make([]int64, 0, 100),
		}
		sc.switchStats[key] = stats
	}

	stats.totalCount++
	if result.Matched {
		stats.trueCount++
	} else {
		stats.falseCount++
	}
	stats.latencies = append(stats.latencies, latency)
}

func (sc *StatsCollector) RecordError(key string) {
	sc.mu.Lock()
	defer sc.mu.Unlock()

	stats, ok := sc.switchStats[key]
	if !ok {
		stats = &switchStats{
			latencies: make([]int64, 0, 100),
		}
		sc.switchStats[key] = stats
	}
	stats.errorCount++
	stats.totalCount++
}

func (sc *StatsCollector) run() {
	ticker := time.NewTicker(sc.reportInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			sc.report()
		case <-sc.stopChan:
			sc.report()
			return
		}
	}
}

func (sc *StatsCollector) report() {
	sc.mu.Lock()
	stats := sc.switchStats
	sc.switchStats = make(map[string]*switchStats)
	sc.mu.Unlock()

	for key, s := range stats {
		if s.totalCount == 0 {
			continue
		}

		avgLatency := 0.0
		p99Latency := 0.0
		if len(s.latencies) > 0 {
			var sum int64
			for _, l := range s.latencies {
				sum += l
			}
			avgLatency = float64(sum) / float64(len(s.latencies))

			sorted := make([]int64, len(s.latencies))
			copy(sorted, s.latencies)
			for i := 1; i < len(sorted); i++ {
				for j := i; j > 0 && sorted[j-1] > sorted[j]; j-- {
					sorted[j-1], sorted[j] = sorted[j], sorted[j-1]
				}
			}
			p99Index := int(float64(len(sorted)) * 0.99)
			if p99Index >= len(sorted) {
				p99Index = len(sorted) - 1
			}
			p99Latency = float64(sorted[p99Index])
		}

		report := &StatsReport{
			SwitchKey:    key,
			TotalCount:   s.totalCount,
			TrueCount:    s.trueCount,
			FalseCount:   s.falseCount,
			ErrorCount:   s.errorCount,
			AvgLatencyMs: avgLatency,
			P99LatencyMs: p99Latency,
			ServiceName:  sc.serviceName,
			SDKVersion:   sc.sdkVersion,
		}

		if sc.reportFunc != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			_ = sc.reportFunc(ctx, report)
			cancel()
		}
	}
}

func (sc *StatsCollector) Stop() {
	close(sc.stopChan)
}
