package abtest

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"model-inference-platform/internal/pkg/database"
	"model-inference-platform/internal/pkg/redis"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"gonum.org/v1/gonum/stat"
	"gonum.org/v1/gonum/stat/distuv"
)

type ABTestStatus string

const (
	TestActive   ABTestStatus = "active"
	TestPaused   ABTestStatus = "paused"
	TestComplete ABTestStatus = "completed"
	TestExpired  ABTestStatus = "expired"
)

type SplitStrategy string

const (
	SplitStrategyTraffic SplitStrategy = "traffic"
	SplitStrategyFeature SplitStrategy = "feature"
)

type FeatureCondition struct {
	FieldName    string      `json:"field_name" yaml:"field_name"`
	Operator     string      `json:"operator" yaml:"operator"`
	Value        interface{} `json:"value" yaml:"value"`
	TargetVersion string     `json:"target_version" yaml:"target_version"`
}

type TimeWindow struct {
	StartTime       time.Time `json:"start_time" yaml:"start_time"`
	EndTime         time.Time `json:"end_time" yaml:"end_time"`
	ExtendIfNotSignificant bool `json:"extend_if_not_significant" yaml:"extend_if_not_significant"`
	ExtendDuration  time.Duration `json:"extend_duration" yaml:"extend_duration"`
	AutoStopOnWin   bool      `json:"auto_stop_on_win" yaml:"auto_stop_on_win"`
}

type ABTest struct {
	ID            string           `json:"id"`
	Name          string           `json:"name"`
	ModelName     string           `json:"model_name"`
	Namespace     string           `json:"namespace"`
	VersionA      string           `json:"version_a"`
	VersionB      string           `json:"version_b"`
	TrafficSplitA int              `json:"traffic_split_a"`
	TrafficSplitB int              `json:"traffic_split_b"`
	SplitStrategy SplitStrategy    `json:"split_strategy"`
	FeatureRule   *FeatureCondition `json:"feature_rule,omitempty"`
	TimeWindow    *TimeWindow      `json:"time_window,omitempty"`
	Status        ABTestStatus     `json:"status"`
	PrimaryMetric string           `json:"primary_metric"`
	SignificanceLevel float64      `json:"significance_level"`
	MinSampleSize int64            `json:"min_sample_size"`
	OwnerEmail    string           `json:"owner_email"`
	OwnerDingtalk string          `json:"owner_dingtalk"`
	OwnerWechat   string          `json:"owner_wechat"`
	StartedAt     time.Time        `json:"started_at"`
	EndedAt       *time.Time       `json:"ended_at,omitempty"`
	CreatedBy     string           `json:"created_by"`
}

type VersionMetrics struct {
	Version        string  `json:"version"`
	RequestCount   int64   `json:"request_count"`
	SuccessCount   int64   `json:"success_count"`
	P50LatencyMs   float64 `json:"p50_latency_ms"`
	P95LatencyMs   float64 `json:"p95_latency_ms"`
	P99LatencyMs   float64 `json:"p99_latency_ms"`
	ErrorRate      float64 `json:"error_rate"`
	BusinessMetric float64 `json:"business_metric"`
	BusinessValues []float64 `json:"-"`
}

type TestResult struct {
	TestID           string          `json:"test_id"`
	MetricsA         *VersionMetrics `json:"metrics_a"`
	MetricsB         *VersionMetrics `json:"metrics_b"`
	IsSignificant    bool            `json:"is_significant"`
	PValue           float64         `json:"p_value"`
	ConfidenceLevel  float64         `json:"confidence_level"`
	EffectSize       float64         `json:"effect_size"`
	BetterVersion    string          `json:"better_version"`
	AnalysisWindow   time.Duration   `json:"analysis_window"`
}

type ABTestManager struct {
	db          *database.Database
	redisClient *redis.Client
	logger      *zap.Logger

	tests   map[string]*ABTest
	testsMu sync.RWMutex

	metrics   map[string]map[string]*VersionMetrics
	metricsMu sync.RWMutex

	stopCh chan struct{}
	wg     sync.WaitGroup
}

func NewManager(db *database.Database, redisClient *redis.Client, logger *zap.Logger) *ABTestManager {
	return &ABTestManager{
		db:          db,
		redisClient: redisClient,
		logger:      logger,
		tests:       make(map[string]*ABTest),
		metrics:     make(map[string]map[string]*VersionMetrics),
		stopCh:      make(chan struct{}),
	}
}

func (m *ABTestManager) Start(ctx context.Context) error {
	if err := m.loadActiveTests(ctx); err != nil {
		return err
	}

	m.wg.Add(2)
	go m.metricsCollector(ctx)
	go m.statsCalculator(ctx)

	m.logger.Info("A/B Test Manager started")
	return nil
}

func (m *ABTestManager) Stop() {
	close(m.stopCh)
	m.wg.Wait()
	m.logger.Info("A/B Test Manager stopped")
}

func (m *ABTestManager) loadActiveTests(ctx context.Context) error {
	query := `
		SELECT id, name, model_name, namespace, version_a, version_b,
		       traffic_split_a, traffic_split_b, status, primary_metric, started_at
		FROM ab_test_configs WHERE status = 'active'
	`

	rows, err := m.db.Query(ctx, query)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		test := &ABTest{}
		err := rows.Scan(&test.ID, &test.Name, &test.ModelName, &test.Namespace,
			&test.VersionA, &test.VersionB, &test.TrafficSplitA, &test.TrafficSplitB,
			&test.Status, &test.PrimaryMetric, &test.StartedAt)
		if err != nil {
			continue
		}
		m.testsMu.Lock()
		m.tests[test.ID] = test
		m.testsMu.Unlock()
	}

	return nil
}

type CreateTestRequest struct {
	Name             string
	ModelName        string
	Namespace        string
	VersionA         string
	VersionB         string
	TrafficSplitA    int
	TrafficSplitB    int
	SplitStrategy    SplitStrategy
	FeatureRule      *FeatureCondition
	TimeWindow       *TimeWindow
	PrimaryMetric    string
	SignificanceLevel float64
	MinSampleSize    int64
	OwnerEmail       string
	OwnerDingtalk    string
	OwnerWechat      string
	CreatedBy        string
}

func (m *ABTestManager) CreateTest(ctx context.Context, req *CreateTestRequest) (*ABTest, error) {
	if req.SplitStrategy == SplitStrategyTraffic && req.TrafficSplitA+req.TrafficSplitB != 100 {
		return nil, fmt.Errorf("traffic split must sum to 100")
	}

	if req.SplitStrategy == SplitStrategyFeature && req.FeatureRule == nil {
		return nil, fmt.Errorf("feature rule is required for feature-based split strategy")
	}

	if req.SignificanceLevel <= 0 {
		req.SignificanceLevel = 0.05
	}

	testID := uuid.New().String()
	now := time.Now()

	var startTime time.Time
	if req.TimeWindow != nil && !req.TimeWindow.StartTime.IsZero() {
		startTime = req.TimeWindow.StartTime
	} else {
		startTime = now
	}

	test := &ABTest{
		ID:                testID,
		Name:              req.Name,
		ModelName:         req.ModelName,
		Namespace:         req.Namespace,
		VersionA:          req.VersionA,
		VersionB:          req.VersionB,
		TrafficSplitA:     req.TrafficSplitA,
		TrafficSplitB:     req.TrafficSplitB,
		SplitStrategy:     req.SplitStrategy,
		FeatureRule:       req.FeatureRule,
		TimeWindow:        req.TimeWindow,
		Status:            TestActive,
		PrimaryMetric:     req.PrimaryMetric,
		SignificanceLevel: req.SignificanceLevel,
		MinSampleSize:     req.MinSampleSize,
		OwnerEmail:        req.OwnerEmail,
		OwnerDingtalk:     req.OwnerDingtalk,
		OwnerWechat:       req.OwnerWechat,
		StartedAt:         startTime,
		CreatedBy:         req.CreatedBy,
	}

	if test.SplitStrategy == "" {
		test.SplitStrategy = SplitStrategyTraffic
	}

	featureRuleJSON, _ := json.Marshal(req.FeatureRule)
	timeWindowJSON, _ := json.Marshal(req.TimeWindow)

	query := `
		INSERT INTO ab_test_configs (id, name, model_id, model_name, namespace, version_a, version_b,
			traffic_split_a, traffic_split_b, split_strategy, feature_rule, time_window,
			status, primary_metric, significance_level, min_sample_size,
			owner_email, owner_dingtalk, owner_wechat, started_at, created_by)
		SELECT $1, $2, m.id, $3, $4, $5, $6, $7, $8, $9, $10, $11,
		       $12, $13, $14, $15, $16, $17, $18, $19, $20
		FROM models m WHERE m.name = $3 AND m.namespace = $4
	`
	_, err := m.db.Exec(ctx, query, testID, req.Name, req.ModelName, req.Namespace,
		req.VersionA, req.VersionB, req.TrafficSplitA, req.TrafficSplitB,
		string(req.SplitStrategy), featureRuleJSON, timeWindowJSON,
		string(TestActive), req.PrimaryMetric, req.SignificanceLevel, req.MinSampleSize,
		req.OwnerEmail, req.OwnerDingtalk, req.OwnerWechat, startTime, req.CreatedBy)
	if err != nil {
		return nil, err
	}

	m.testsMu.Lock()
	m.tests[testID] = test
	m.testsMu.Unlock()

	m.logger.Info("A/B Test created",
		zap.String("test_id", testID),
		zap.String("name", req.Name),
		zap.String("strategy", string(req.SplitStrategy)))

	return test, nil
}

type RoutingContext struct {
	RequestFeatures map[string]interface{}
}

func (m *ABTestManager) SelectVersion(ctx context.Context, namespace, modelName string,
	routingCtx *RoutingContext) (string, string, bool) {

	m.testsMu.RLock()
	defer m.testsMu.RUnlock()

	for _, test := range m.tests {
		if test.Namespace != namespace || test.ModelName != modelName {
			continue
		}

		if test.Status != TestActive {
			continue
		}

		if !m.isWithinTimeWindow(test) {
			continue
		}

		return m.selectVersionByStrategy(test, routingCtx)
	}

	return "", "", false
}

func (m *ABTestManager) isWithinTimeWindow(test *ABTest) bool {
	if test.TimeWindow == nil {
		return true
	}

	now := time.Now()

	if !test.TimeWindow.StartTime.IsZero() && now.Before(test.TimeWindow.StartTime) {
		return false
	}

	if !test.TimeWindow.EndTime.IsZero() && now.After(test.TimeWindow.EndTime) {
		return false
	}

	return true
}

func (m *ABTestManager) selectVersionByStrategy(test *ABTest,
	routingCtx *RoutingContext) (string, string, bool) {

	switch test.SplitStrategy {
	case SplitStrategyFeature:
		return m.selectVersionByFeature(test, routingCtx)
	case SplitStrategyTraffic:
		fallthrough
	default:
		return m.selectVersionByTraffic(test)
	}
}

func (m *ABTestManager) selectVersionByTraffic(test *ABTest) (string, string, bool) {
	hash := time.Now().UnixNano() % 100
	if hash < int64(test.TrafficSplitA) {
		return test.VersionA, test.ID, true
	}
	return test.VersionB, test.ID, true
}

func (m *ABTestManager) selectVersionByFeature(test *ABTest,
	routingCtx *RoutingContext) (string, string, bool) {

	if test.FeatureRule == nil {
		return test.VersionA, test.ID, true
	}

	if routingCtx == nil || routingCtx.RequestFeatures == nil {
		return test.VersionA, test.ID, true
	}

	fieldValue, ok := routingCtx.RequestFeatures[test.FeatureRule.FieldName]
	if !ok {
		return test.VersionA, test.ID, true
	}

	matches := m.evaluateFeatureCondition(fieldValue, test.FeatureRule.Operator, test.FeatureRule.Value)
	if matches {
		if test.FeatureRule.TargetVersion == test.VersionA ||
			test.FeatureRule.TargetVersion == "a" {
			return test.VersionA, test.ID, true
		}
		return test.VersionB, test.ID, true
	}

	if test.FeatureRule.TargetVersion == test.VersionA ||
		test.FeatureRule.TargetVersion == "a" {
		return test.VersionB, test.ID, true
	}
	return test.VersionA, test.ID, true
}

func (m *ABTestManager) evaluateFeatureCondition(fieldValue interface{}, operator string,
	targetValue interface{}) bool {

	switch operator {
	case "equals", "==", "=":
		return fmt.Sprintf("%v", fieldValue) == fmt.Sprintf("%v", targetValue)
	case "not_equals", "!=":
		return fmt.Sprintf("%v", fieldValue) != fmt.Sprintf("%v", targetValue)
	case "contains":
		strVal, ok1 := fieldValue.(string)
		strTarget, ok2 := targetValue.(string)
		if ok1 && ok2 {
			return strings.Contains(strVal, strTarget)
		}
		return false
	case "in":
		if targetSlice, ok := targetValue.([]interface{}); ok {
			for _, v := range targetSlice {
				if fmt.Sprintf("%v", fieldValue) == fmt.Sprintf("%v", v) {
					return true
				}
			}
		}
		return false
	case "mod_equals":
		if num, ok := toInt64(fieldValue); ok {
			if targetSlice, ok := targetValue.([]interface{}); ok && len(targetSlice) >= 2 {
				if modulus, ok1 := toInt64(targetSlice[0]); ok1 {
					if expected, ok2 := toInt64(targetSlice[1]); ok2 && modulus > 0 {
						return num%modulus == expected
					}
				}
			}
		}
		return false
	case "greater_than", ">":
		if num1, ok1 := toFloat64(fieldValue); ok1 {
			if num2, ok2 := toFloat64(targetValue); ok2 {
				return num1 > num2
			}
		}
		return false
	case "less_than", "<":
		if num1, ok1 := toFloat64(fieldValue); ok1 {
			if num2, ok2 := toFloat64(targetValue); ok2 {
				return num1 < num2
			}
		}
		return false
	case "even":
		if num, ok := toInt64(fieldValue); ok {
			return num%2 == 0
		}
		return false
	case "odd":
		if num, ok := toInt64(fieldValue); ok {
			return num%2 == 1
		}
		return false
	default:
		return false
	}
}

func toInt64(v interface{}) (int64, bool) {
	switch val := v.(type) {
	case int:
		return int64(val), true
	case int32:
		return int64(val), true
	case int64:
		return val, true
	case float64:
		return int64(val), true
	case string:
		var result int64
		if _, err := fmt.Sscanf(val, "%d", &result); err == nil {
			return result, true
		}
	}
	return 0, false
}

func toFloat64(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case int:
		return float64(val), true
	case int32:
		return float64(val), true
	case int64:
		return float64(val), true
	case float32:
		return float64(val), true
	case float64:
		return val, true
	case string:
		var result float64
		if _, err := fmt.Sscanf(val, "%f", &result); err == nil {
			return result, true
		}
	}
	return 0, false
}

func (m *ABTestManager) RecordRequest(testID, version string, latencyMs int64, success bool, businessMetric float64) {
	m.metricsMu.Lock()
	defer m.metricsMu.Unlock()

	if _, ok := m.metrics[testID]; !ok {
		m.metrics[testID] = make(map[string]*VersionMetrics)
	}

	if _, ok := m.metrics[testID][version]; !ok {
		m.metrics[testID][version] = &VersionMetrics{
			Version:        version,
			BusinessValues: make([]float64, 0),
		}
	}

	metrics := m.metrics[testID][version]
	metrics.RequestCount++
	if success {
		metrics.SuccessCount++
	}

	if latencyMs > 0 {
		if metrics.P50LatencyMs == 0 {
			metrics.P50LatencyMs = float64(latencyMs)
			metrics.P95LatencyMs = float64(latencyMs)
			metrics.P99LatencyMs = float64(latencyMs)
		} else {
			metrics.P50LatencyMs = 0.9*metrics.P50LatencyMs + 0.1*float64(latencyMs)
			metrics.P95LatencyMs = 0.9*metrics.P95LatencyMs + 0.1*float64(latencyMs*2)
			metrics.P99LatencyMs = 0.9*metrics.P99LatencyMs + 0.1*float64(latencyMs*3)
		}
	}

	if businessMetric > 0 {
		metrics.BusinessMetric = 0.9*metrics.BusinessMetric + 0.1*businessMetric
		metrics.BusinessValues = append(metrics.BusinessValues, businessMetric)
		if len(metrics.BusinessValues) > 10000 {
			metrics.BusinessValues = metrics.BusinessValues[1000:]
		}
	}

	metrics.ErrorRate = float64(metrics.RequestCount-metrics.SuccessCount) / float64(metrics.RequestCount)
}

func (m *ABTestManager) AnalyzeTest(ctx context.Context, testID string) (*TestResult, error) {
	m.testsMu.RLock()
	test, ok := m.tests[testID]
	m.testsMu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("test not found")
	}

	m.metricsMu.RLock()
	metrics, ok := m.metrics[testID]
	m.metricsMu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("no metrics collected")
	}

	metricsA := metrics[test.VersionA]
	metricsB := metrics[test.VersionB]

	if metricsA == nil || metricsB == nil {
		return nil, fmt.Errorf("insufficient data for both versions")
	}

	pValue, isSignificant := m.performTTest(metricsA.BusinessValues, metricsB.BusinessValues)
	effectSize := m.calculateEffectSize(metricsA.BusinessValues, metricsB.BusinessValues)

	betterVersion := test.VersionA
	if metricsB.BusinessMetric > metricsA.BusinessMetric {
		betterVersion = test.VersionB
	}

	result := &TestResult{
		TestID:          testID,
		MetricsA:        metricsA,
		MetricsB:        metricsB,
		IsSignificant:   isSignificant,
		PValue:          pValue,
		ConfidenceLevel: 1 - pValue,
		EffectSize:      effectSize,
		BetterVersion:   betterVersion,
		AnalysisWindow:  time.Since(test.StartedAt),
	}

	return result, nil
}

func (m *ABTestManager) performTTest(valuesA, valuesB []float64) (float64, bool) {
	if len(valuesA) < 30 || len(valuesB) < 30 {
		return 0.5, false
	}

	meanA, stdA := stat.MeanStdDev(valuesA, nil)
	meanB, stdB := stat.MeanStdDev(valuesB, nil)

	nA := float64(len(valuesA))
	nB := float64(len(valuesB))

	se := math.Sqrt((stdA*stdA)/nA + (stdB*stdB)/nB)
	if se == 0 {
		return 1.0, false
	}

	tStat := (meanA - meanB) / se

	df := math.Pow((stdA*stdA)/nA+(stdB*stdB)/nB, 2) /
		(math.Pow((stdA*stdA)/nA, 2)/(nA-1) + math.Pow((stdB*stdB)/nB, 2)/(nB-1))

	tDist := distuv.StudentsT{Mu: 0, Sigma: 1, Nu: df}
	pValue := 2 * tDist.CDF(-math.Abs(tStat))

	return pValue, pValue < 0.05
}

func (m *ABTestManager) calculateEffectSize(valuesA, valuesB []float64) float64 {
	if len(valuesA) == 0 || len(valuesB) == 0 {
		return 0
	}

	meanA, stdA := stat.MeanStdDev(valuesA, nil)
	meanB, stdB := stat.MeanStdDev(valuesB, nil)

	pooledStd := math.Sqrt(((float64(len(valuesA))-1)*stdA*stdA + (float64(len(valuesB))-1)*stdB*stdB) /
		(float64(len(valuesA)) + float64(len(valuesB)) - 2))

	if pooledStd == 0 {
		return 0
	}

	return math.Abs(meanA-meanB) / pooledStd
}

func (m *ABTestManager) ChiSquareTest(successA, totalA, successB, totalB int64) (float64, bool) {
	observed := [][]float64{
		{float64(successA), float64(totalA - successA)},
		{float64(successB), float64(totalB - successB)},
	}

	rowSums := make([]float64, 2)
	colSums := make([]float64, 2)
	total := 0.0

	for i := 0; i < 2; i++ {
		for j := 0; j < 2; j++ {
			rowSums[i] += observed[i][j]
			colSums[j] += observed[i][j]
			total += observed[i][j]
		}
	}

	expected := make([][]float64, 2)
	for i := range expected {
		expected[i] = make([]float64, 2)
	}

	for i := 0; i < 2; i++ {
		for j := 0; j < 2; j++ {
			expected[i][j] = rowSums[i] * colSums[j] / total
		}
	}

	chiSquare := 0.0
	for i := 0; i < 2; i++ {
		for j := 0; j < 2; j++ {
			if expected[i][j] > 0 {
				chiSquare += math.Pow(observed[i][j]-expected[i][j], 2) / expected[i][j]
			}
		}
	}

	pValue := 1 - distuv.ChiSquared{K: 1}.CDF(chiSquare)

	return pValue, pValue < 0.05
}

func (m *ABTestManager) metricsCollector(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.persistMetrics(ctx)
		}
	}
}

func (m *ABTestManager) persistMetrics(ctx context.Context) {
	m.metricsMu.Lock()
	tests := make(map[string]map[string]*VersionMetrics)
	for k, v := range m.metrics {
		tests[k] = v
	}
	m.metricsMu.Unlock()

	for testID, versions := range tests {
		for version, metrics := range versions {
			businessMetrics, _ := json.Marshal(map[string]interface{}{
				"value": metrics.BusinessMetric,
			})

			query := `
				INSERT INTO ab_test_metrics (ab_test_id, version, request_count, p50_latency_ms,
					p95_latency_ms, p99_latency_ms, error_rate, business_metrics)
				VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
			`
			m.db.Exec(ctx, query, testID, version, metrics.RequestCount,
				metrics.P50LatencyMs, metrics.P95LatencyMs, metrics.P99LatencyMs,
				metrics.ErrorRate, businessMetrics)
		}
	}
}

func (m *ABTestManager) statsCalculator(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.runAutomatedAnalysis(ctx)
		}
	}
}

func (m *ABTestManager) runAutomatedAnalysis(ctx context.Context) {
	m.testsMu.RLock()
	testIDs := make([]string, 0, len(m.tests))
	testsCopy := make(map[string]*ABTest)
	for id, test := range m.tests {
		testIDs = append(testIDs, id)
		testsCopy[id] = test
	}
	m.testsMu.RUnlock()

	for _, testID := range testIDs {
		test := testsCopy[testID]

		if err := m.checkTimeWindowExpiry(ctx, test); err != nil {
			m.logger.Error("Failed to check time window expiry",
				zap.String("test_id", testID),
				zap.Error(err))
			continue
		}

		result, err := m.AnalyzeTest(ctx, testID)
		if err != nil {
			continue
		}

		sigLevel := test.SignificanceLevel
		if sigLevel <= 0 {
			sigLevel = 0.05
		}

		if result.IsSignificant && result.PValue < sigLevel {
			m.logger.Info("A/B Test reached significance",
				zap.String("test_id", testID),
				zap.String("better_version", result.BetterVersion),
				zap.Float64("p_value", result.PValue),
				zap.Float64("effect_size", result.EffectSize))

			if test.TimeWindow != nil && test.TimeWindow.AutoStopOnWin {
				if test.MinSampleSize == 0 ||
					(result.MetricsA.RequestCount+result.MetricsB.RequestCount) >= test.MinSampleSize {
					m.logger.Info("Auto-stopping A/B Test due to significant result",
						zap.String("test_id", testID),
						zap.String("winner", result.BetterVersion))
					m.EndTest(ctx, testID)
				}
			}
		}
	}
}

func (m *ABTestManager) checkTimeWindowExpiry(ctx context.Context, test *ABTest) error {
	if test.TimeWindow == nil {
		return nil
	}

	if test.Status != TestActive {
		return nil
	}

	now := time.Now()

	if !test.TimeWindow.EndTime.IsZero() && now.After(test.TimeWindow.EndTime) {
		result, err := m.AnalyzeTest(ctx, test.ID)
		if err != nil {
			return err
		}

		sigLevel := test.SignificanceLevel
		if sigLevel <= 0 {
			sigLevel = 0.05
		}

		totalRequests := result.MetricsA.RequestCount + result.MetricsB.RequestCount
		hasEnoughSamples := test.MinSampleSize == 0 || totalRequests >= test.MinSampleSize
		isSignificant := result.IsSignificant && result.PValue < sigLevel

		if isSignificant || !test.TimeWindow.ExtendIfNotSignificant || hasEnoughSamples {
			m.logger.Info("A/B Test time window expired, stopping test",
				zap.String("test_id", test.ID),
				zap.String("better_version", result.BetterVersion),
				zap.Bool("is_significant", isSignificant))

			return m.EndTest(ctx, test.ID)
		} else if test.TimeWindow.ExtendIfNotSignificant {
			extendDuration := test.TimeWindow.ExtendDuration
			if extendDuration <= 0 {
				extendDuration = 7 * 24 * time.Hour
			}

			m.testsMu.Lock()
			test.TimeWindow.EndTime = now.Add(extendDuration)
			m.testsMu.Unlock()

			timeWindowJSON, _ := json.Marshal(test.TimeWindow)
			query := `UPDATE ab_test_configs SET time_window = $1 WHERE id = $2`
			_, err := m.db.Exec(ctx, query, timeWindowJSON, test.ID)
			if err != nil {
				return err
			}

			m.logger.Info("A/B Test extended due to insignificant results",
				zap.String("test_id", test.ID),
				zap.Time("new_end_time", test.TimeWindow.EndTime),
				zap.Float64("p_value", result.PValue))
		}
	}

	return nil
}

func (m *ABTestManager) GetActiveTests(namespace string) []*ABTest {
	m.testsMu.RLock()
	defer m.testsMu.RUnlock()

	var tests []*ABTest
	for _, test := range m.tests {
		if test.Namespace == namespace && test.Status == TestActive {
			tests = append(tests, test)
		}
	}
	return tests
}

func (m *ABTestManager) EndTest(ctx context.Context, testID string) error {
	m.testsMu.Lock()
	defer m.testsMu.Unlock()

	test, ok := m.tests[testID]
	if !ok {
		return fmt.Errorf("test not found")
	}

	now := time.Now()
	test.Status = TestComplete
	test.EndedAt = &now

	query := `UPDATE ab_test_configs SET status = $1, ended_at = $2 WHERE id = $3`
	_, err := m.db.Exec(ctx, query, string(TestComplete), now, testID)
	if err != nil {
		return err
	}

	delete(m.tests, testID)
	return nil
}
