package alerts

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"observability-platform/pkg/models"
)

type ExpressionAST struct {
	Op       string
	Left     *ExpressionAST
	Right    *ExpressionAST
	Value    float64
	Metric   string
	Function string
	Args     []*ExpressionAST
}

type AlertEvaluator interface {
	Evaluate(rule *models.AlertRule, now time.Time) (*models.AlertEvaluationResult, error)
}

type MetricProvider interface {
	GetMetricValue(metricName string, labels map[string]string) (float64, bool)
	GetMetricSeries(metricName string, labels map[string]string, duration time.Duration) []float64
}

type NotificationSender interface {
	Send(alert *models.Alert, channels []string) error
	Name() string
}

type expressionCache struct {
	cache map[string]*ExpressionAST
	mu    sync.RWMutex
	hits  int64
	miss  int64
}

func newExpressionCache() *expressionCache {
	return &expressionCache{
		cache: make(map[string]*ExpressionAST),
	}
}

func (ec *expressionCache) Get(expr string) (*ExpressionAST, bool) {
	ec.mu.RLock()
	ast, ok := ec.cache[expr]
	ec.mu.RUnlock()
	if ok {
		atomic.AddInt64(&ec.hits, 1)
		return ast, true
	}
	atomic.AddInt64(&ec.miss, 1)
	return nil, false
}

func (ec *expressionCache) Set(expr string, ast *ExpressionAST) {
	ec.mu.Lock()
	ec.cache[expr] = ast
	ec.mu.Unlock()
}

func (ec *expressionCache) Stats() map[string]interface{} {
	ec.mu.RLock()
	size := len(ec.cache)
	ec.mu.RUnlock()
	return map[string]interface{}{
		"size": size,
		"hits": atomic.LoadInt64(&ec.hits),
		"miss": atomic.LoadInt64(&ec.miss),
	}
}

type silenceIndex struct {
	exactMatches map[string][]*models.AlertSilence
	regexMatches []*models.AlertSilence
	mu           sync.RWMutex
}

func newSilenceIndex() *silenceIndex {
	return &silenceIndex{
		exactMatches: make(map[string][]*models.AlertSilence),
		regexMatches: make([]*models.AlertSilence, 0),
	}
}

func (si *silenceIndex) rebuild(silences map[string]*models.AlertSilence) {
	si.mu.Lock()
	defer si.mu.Unlock()

	si.exactMatches = make(map[string][]*models.AlertSilence)
	si.regexMatches = make([]*models.AlertSilence, 0)

	for _, silence := range silences {
		allExact := true
		for _, matcher := range silence.Matchers {
			if matcher.IsRegex {
				allExact = false
				break
			}
		}

		if allExact {
			for _, matcher := range silence.Matchers {
				key := matcher.Name + "=" + matcher.Value
				si.exactMatches[key] = append(si.exactMatches[key], silence)
			}
		} else {
			si.regexMatches = append(si.regexMatches, silence)
		}
	}
}

func (si *silenceIndex) lookup(alert *models.Alert, now time.Time) bool {
	si.mu.RLock()
	defer si.mu.RUnlock()

	for labelName, labelValue := range alert.Labels {
		key := labelName + "=" + labelValue
		if silences, exists := si.exactMatches[key]; exists {
			for _, silence := range silences {
				if now.Before(silence.StartsAt) || now.After(silence.EndsAt) {
					continue
				}
				if si.matchesSilence(alert, silence) {
					return true
				}
			}
		}
	}

	for _, silence := range si.regexMatches {
		if now.Before(silence.StartsAt) || now.After(silence.EndsAt) {
			continue
		}
		if si.matchesSilence(alert, silence) {
			return true
		}
	}

	return false
}

func (si *silenceIndex) matchesSilence(alert *models.Alert, silence *models.AlertSilence) bool {
	for _, matcher := range silence.Matchers {
		alertValue := alert.Labels[matcher.Name]
		if matcher.IsRegex {
			if matched, _ := regexp.MatchString(matcher.Value, alertValue); !matched {
				return false
			}
		} else {
			if matcher.IsEqual && alertValue != matcher.Value {
				return false
			}
			if !matcher.IsEqual && alertValue == matcher.Value {
				return false
			}
		}
	}
	return true
}

type AlertEngine struct {
	rules               map[string]*models.AlertRule
	activeAlerts        map[string]*models.Alert
	ruleAlertsIndex     map[string]map[string]struct{}
	metricProvider      MetricProvider
	notificationSenders map[string]NotificationSender
	notificationChannels map[string]*models.NotificationChannel
	evalInterval        time.Duration
	mu                  sync.RWMutex
	ctx                 context.Context
	cancel              context.CancelFunc
	wg                  sync.WaitGroup
	alertHistory        []models.AlertHistory
	silences            map[string]*models.AlertSilence
	silenceIdx          *silenceIndex
	exprCache           *expressionCache
	maxConcurrentEvals  int
}

type EngineConfig struct {
	EvaluationInterval time.Duration
	MaxHistorySize     int
	MaxConcurrentEvals int
}

func NewAlertEngine(config EngineConfig, provider MetricProvider) *AlertEngine {
	if config.EvaluationInterval <= 0 {
		config.EvaluationInterval = time.Second * 30
	}
	if config.MaxHistorySize <= 0 {
		config.MaxHistorySize = 1000
	}
	if config.MaxConcurrentEvals <= 0 {
		config.MaxConcurrentEvals = 10
	}

	ctx, cancel := context.WithCancel(context.Background())

	engine := &AlertEngine{
		rules:               make(map[string]*models.AlertRule),
		activeAlerts:        make(map[string]*models.Alert),
		ruleAlertsIndex:     make(map[string]map[string]struct{}),
		metricProvider:      provider,
		notificationSenders: make(map[string]NotificationSender),
		notificationChannels: make(map[string]*models.NotificationChannel),
		evalInterval:         config.EvaluationInterval,
		ctx:                  ctx,
		cancel:               cancel,
		alertHistory:         make([]models.AlertHistory, 0, config.MaxHistorySize),
		silences:             make(map[string]*models.AlertSilence),
		silenceIdx:           newSilenceIndex(),
		exprCache:            newExpressionCache(),
		maxConcurrentEvals:   config.MaxConcurrentEvals,
	}

	return engine
}

func (e *AlertEngine) AddRule(rule *models.AlertRule) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if rule.ID == "" {
		rule.ID = uuid.New().String()
	}
	rule.CreatedAt = time.Now()
	rule.UpdatedAt = time.Now()
	e.rules[rule.ID] = rule
}

func (e *AlertEngine) UpdateRule(rule *models.AlertRule) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if _, exists := e.rules[rule.ID]; !exists {
		return fmt.Errorf("rule not found: %s", rule.ID)
	}

	rule.UpdatedAt = time.Now()
	e.rules[rule.ID] = rule
	return nil
}

func (e *AlertEngine) DeleteRule(ruleID string) error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if _, exists := e.rules[ruleID]; !exists {
		return fmt.Errorf("rule not found: %s", ruleID)
	}

	delete(e.rules, ruleID)
	return nil
}

func (e *AlertEngine) GetRule(ruleID string) (*models.AlertRule, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rule, exists := e.rules[ruleID]
	return rule, exists
}

func (e *AlertEngine) GetAllRules() []*models.AlertRule {
	e.mu.RLock()
	defer e.mu.RUnlock()

	rules := make([]*models.AlertRule, 0, len(e.rules))
	for _, rule := range e.rules {
		rules = append(rules, rule)
	}

	sort.Slice(rules, func(i, j int) bool {
		return rules[i].Name < rules[j].Name
	})

	return rules
}

func (e *AlertEngine) AddNotificationSender(sender NotificationSender) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.notificationSenders[sender.Name()] = sender
}

func (e *AlertEngine) AddNotificationChannel(channel *models.NotificationChannel) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if channel.ID == "" {
		channel.ID = uuid.New().String()
	}
	e.notificationChannels[channel.ID] = channel
}

func (e *AlertEngine) Start() {
	e.wg.Add(1)
	go e.evaluationLoop()
}

func (e *AlertEngine) Stop() {
	e.cancel()
	e.wg.Wait()
}

func (e *AlertEngine) evaluationLoop() {
	defer e.wg.Done()

	ticker := time.NewTicker(e.evalInterval)
	defer ticker.Stop()

	for {
		select {
		case <-e.ctx.Done():
			return
		case <-ticker.C:
			e.evaluateAllRules()
		}
	}
}

func (e *AlertEngine) evaluateAllRules() {
	e.mu.RLock()
	rules := make([]*models.AlertRule, 0, len(e.rules))
	for _, rule := range e.rules {
		if rule.Enabled {
			rules = append(rules, rule)
		}
	}
	e.mu.RUnlock()

	if len(rules) == 0 {
		return
	}

	type ruleResult struct {
		rule   *models.AlertRule
		result *models.AlertEvaluationResult
		err    error
	}

	resultChan := make(chan ruleResult, len(rules))
	sem := make(chan struct{}, e.maxConcurrentEvals)

	var wg sync.WaitGroup
	for _, rule := range rules {
		wg.Add(1)
		go func(r *models.AlertRule) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			result, err := e.evaluateRule(r)
			resultChan <- ruleResult{rule: r, result: result, err: err}
		}(rule)
	}

	go func() {
		wg.Wait()
		close(resultChan)
	}()

	for rr := range resultChan {
		if rr.err != nil {
			continue
		}
		e.processEvaluationResult(rr.rule, rr.result)
	}
}

func (e *AlertEngine) evaluateRule(rule *models.AlertRule) (*models.AlertEvaluationResult, error) {
	now := time.Now()
	result := &models.AlertEvaluationResult{
		RuleID:      rule.ID,
		RuleName:    rule.Name,
		EvaluatedAt: now,
		Matches:     make([]models.AlertMatch, 0),
	}

	ast, err := e.parseExpressionCached(rule.Expression)
	if err != nil {
		return nil, err
	}

	matches := e.evaluateExpression(ast, rule)
	for _, match := range matches {
		if match.Value != 0 {
			result.Matches = append(result.Matches, models.AlertMatch{
				Labels:    match.Labels,
				Value:     match.Value,
				Condition: rule.Expression,
				ActiveAt:  now,
			})
		}
	}

	result.Duration = time.Since(now)
	return result, nil
}

func (e *AlertEngine) parseExpressionCached(expr string) (*ExpressionAST, error) {
	if ast, ok := e.exprCache.Get(expr); ok {
		return ast, nil
	}

	ast, err := parseExpression(expr)
	if err != nil {
		return nil, err
	}

	e.exprCache.Set(expr, ast)
	return ast, nil
}

type ExpressionMatch struct {
	Labels map[string]string
	Value  float64
}

func (e *AlertEngine) evaluateExpression(ast *ExpressionAST, rule *models.AlertRule) []ExpressionMatch {
	if ast == nil {
		return nil
	}

	switch ast.Op {
	case "literal":
		return []ExpressionMatch{{Value: ast.Value}}

	case "metric":
		return e.evaluateMetricExpression(ast.Metric, ast.Function, rule)

	case "gt", "gte", "lt", "lte", "eq", "neq":
		return e.evaluateComparison(ast, rule)

	case "and", "or":
		return e.evaluateLogical(ast, rule)

	case "plus", "minus", "multiply", "divide":
		return e.evaluateArithmetic(ast, rule)
	}

	return nil
}

func (e *AlertEngine) evaluateMetricExpression(metricName, function string, rule *models.AlertRule) []ExpressionMatch {
	results := make([]ExpressionMatch, 0, 1)

	value, _ := e.metricProvider.GetMetricValue(metricName, nil)

	if function != "" {
		values := e.metricProvider.GetMetricSeries(metricName, nil, rule.Duration)
		value = e.applyFunction(function, values)
	}

	results = append(results, ExpressionMatch{
		Labels: make(map[string]string),
		Value:  value,
	})

	return results
}

func (e *AlertEngine) applyFunction(function string, values []float64) float64 {
	if len(values) == 0 {
		return 0
	}

	switch strings.ToLower(function) {
	case "avg", "mean":
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum / float64(len(values))

	case "sum":
		sum := 0.0
		for _, v := range values {
			sum += v
		}
		return sum

	case "count":
		return float64(len(values))

	case "min":
		min := values[0]
		for _, v := range values[1:] {
			if v < min {
				min = v
			}
		}
		return min

	case "max":
		max := values[0]
		for _, v := range values[1:] {
			if v > max {
				max = v
			}
		}
		return max

	case "rate":
		if len(values) >= 2 {
			return (values[len(values)-1] - values[0]) / float64(len(values))
		}
		return 0

	case "delta":
		if len(values) >= 2 {
			return values[len(values)-1] - values[0]
		}
		return 0

	default:
		if len(values) > 0 {
			return values[len(values)-1]
		}
		return 0
	}
}

func (e *AlertEngine) evaluateComparison(ast *ExpressionAST, rule *models.AlertRule) []ExpressionMatch {
	leftResults := e.evaluateExpression(ast.Left, rule)
	rightResults := e.evaluateExpression(ast.Right, rule)

	if len(leftResults) == 0 || len(rightResults) == 0 {
		return nil
	}

	leftVal := leftResults[0].Value
	rightVal := rightResults[0].Value

	var match bool
	switch ast.Op {
	case "gt":
		match = leftVal > rightVal
	case "gte":
		match = leftVal >= rightVal
	case "lt":
		match = leftVal < rightVal
	case "lte":
		match = leftVal <= rightVal
	case "eq":
		match = leftVal == rightVal
	case "neq":
		match = leftVal != rightVal
	}

	if !match {
		return nil
	}

	labels := make(map[string]string, len(leftResults[0].Labels)+len(rightResults[0].Labels))
	for k, v := range leftResults[0].Labels {
		labels[k] = v
	}
	for k, v := range rightResults[0].Labels {
		labels[k] = v
	}

	return []ExpressionMatch{{Labels: labels, Value: 1.0}}
}

func (e *AlertEngine) evaluateLogical(ast *ExpressionAST, rule *models.AlertRule) []ExpressionMatch {
	leftResults := e.evaluateExpression(ast.Left, rule)
	rightResults := e.evaluateExpression(ast.Right, rule)

	results := make([]ExpressionMatch, 0)
	leftMap := make(map[string]ExpressionMatch)
	rightMap := make(map[string]ExpressionMatch)

	for _, r := range leftResults {
		leftMap[labelsToKey(r.Labels)] = r
	}
	for _, r := range rightResults {
		rightMap[labelsToKey(r.Labels)] = r
	}

	if ast.Op == "and" {
		for key, left := range leftMap {
			if right, exists := rightMap[key]; exists {
				results = append(results, ExpressionMatch{
					Labels: left.Labels,
					Value:  left.Value * right.Value,
				})
			}
		}
	} else if ast.Op == "or" {
		seen := make(map[string]bool)
		for key, left := range leftMap {
			if !seen[key] {
				results = append(results, left)
				seen[key] = true
			}
		}
		for key, right := range rightMap {
			if !seen[key] {
				results = append(results, right)
				seen[key] = true
			}
		}
	}

	return results
}

func (e *AlertEngine) evaluateArithmetic(ast *ExpressionAST, rule *models.AlertRule) []ExpressionMatch {
	leftResults := e.evaluateExpression(ast.Left, rule)
	rightResults := e.evaluateExpression(ast.Right, rule)

	if len(leftResults) == 0 || len(rightResults) == 0 {
		return nil
	}

	left := leftResults[0]
	right := rightResults[0]

	var value float64

	switch ast.Op {
	case "plus":
		value = left.Value + right.Value
	case "minus":
		value = left.Value - right.Value
	case "multiply":
		value = left.Value * right.Value
	case "divide":
		if right.Value != 0 {
			value = left.Value / right.Value
		}
	}

	labels := make(map[string]string, len(left.Labels)+len(right.Labels))
	for k, v := range left.Labels {
		labels[k] = v
	}
	for k, v := range right.Labels {
		labels[k] = v
	}

	return []ExpressionMatch{{Labels: labels, Value: value}}
}

func labelsToKey(labels map[string]string) string {
	keys := make([]string, 0, len(labels))
	for k := range labels {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	key := ""
	for _, k := range keys {
		key += fmt.Sprintf("%s=%s|", k, labels[k])
	}
	return key
}

func parseExpression(expr string) (*ExpressionAST, error) {
	expr = strings.TrimSpace(expr)

	if value, err := strconv.ParseFloat(expr, 64); err == nil {
		return &ExpressionAST{Op: "literal", Value: value}, nil
	}

	comparisonOps := []struct {
		op string
		ty string
	}{
		{">=", "gte"},
		{"<=", "lte"},
		{">", "gt"},
		{"<", "lt"},
		{"==", "eq"},
		{"!=", "neq"},
	}

	for _, cop := range comparisonOps {
		if idx := strings.Index(expr, cop.op); idx > 0 {
			left, err := parseExpression(expr[:idx])
			if err != nil {
				return nil, err
			}
			right, err := parseExpression(expr[idx+len(cop.op):])
			if err != nil {
				return nil, err
			}
			return &ExpressionAST{Op: cop.ty, Left: left, Right: right}, nil
		}
	}

	functionRegex := regexp.MustCompile(`^(\w+)\((.*)\)$`)
	if matches := functionRegex.FindStringSubmatch(expr); matches != nil {
		return &ExpressionAST{Op: "metric", Metric: matches[2], Function: matches[1]}, nil
	}

	return &ExpressionAST{Op: "metric", Metric: expr}, nil
}

func (e *AlertEngine) processEvaluationResult(rule *models.AlertRule, result *models.AlertEvaluationResult) {
	e.mu.Lock()
	defer e.mu.Unlock()

	for _, match := range result.Matches {
		fingerprint := e.calculateFingerprint(rule, match.Labels)

		if alert, exists := e.activeAlerts[fingerprint]; exists {
			if alert.Status == models.AlertStatusFiring {
				continue
			}
			alert.Status = models.AlertStatusFiring
			alert.StartsAt = match.ActiveAt
			e.addHistory(alert, "alert fired")
			e.sendNotification(alert, rule.NotificationIDs)
		} else {
			alert := &models.Alert{
				ID:          uuid.New().String(),
				RuleID:      rule.ID,
				RuleName:    rule.Name,
				Status:      models.AlertStatusFiring,
				Severity:    rule.Severity,
				Message:     e.buildMessage(rule, match),
				Labels:      match.Labels,
				Annotations: rule.Annotations,
				StartsAt:    match.ActiveAt,
				Fingerprint: fingerprint,
			}

			for k, v := range rule.Labels {
				alert.Labels[k] = v
			}

			e.activeAlerts[fingerprint] = alert
			e.addRuleAlertIndex(rule.ID, fingerprint)
			e.addHistory(alert, "alert created")
			e.sendNotification(alert, rule.NotificationIDs)
		}
	}

	if ruleFingerprints, exists := e.ruleAlertsIndex[rule.ID]; exists {
		for fingerprint := range ruleFingerprints {
			alert, alertExists := e.activeAlerts[fingerprint]
			if !alertExists || alert.Status != models.AlertStatusFiring {
				continue
			}

			found := false
			for _, match := range result.Matches {
				if e.calculateFingerprint(rule, match.Labels) == fingerprint {
					found = true
					break
				}
			}

			if !found {
				now := time.Now()
				alert.Status = models.AlertStatusResolved
				alert.EndsAt = &now
				e.addHistory(alert, "alert resolved")
				e.sendNotification(alert, rule.NotificationIDs)
				delete(e.activeAlerts, fingerprint)
				delete(ruleFingerprints, fingerprint)
			}
		}
	}
}

func (e *AlertEngine) addRuleAlertIndex(ruleID, fingerprint string) {
	if _, exists := e.ruleAlertsIndex[ruleID]; !exists {
		e.ruleAlertsIndex[ruleID] = make(map[string]struct{})
	}
	e.ruleAlertsIndex[ruleID][fingerprint] = struct{}{}
}

func (e *AlertEngine) calculateFingerprint(rule *models.AlertRule, labels map[string]string) string {
	h := sha256.New()
	h.Write([]byte(rule.ID))
	h.Write([]byte("|"))
	h.Write([]byte(labelsToKey(labels)))
	return hex.EncodeToString(h.Sum(nil))[:16]
}

func (e *AlertEngine) buildMessage(rule *models.AlertRule, match models.AlertMatch) string {
	msg := rule.Description
	if msg == "" {
		msg = fmt.Sprintf("Alert %s fired", rule.Name)
	}

	msg = strings.ReplaceAll(msg, "{{value}}", fmt.Sprintf("%.2f", match.Value))

	for k, v := range match.Labels {
		msg = strings.ReplaceAll(msg, "{{"+k+"}}", v)
	}

	return msg
}

func (e *AlertEngine) addHistory(alert *models.Alert, message string) {
	history := models.AlertHistory{
		ID:        uuid.New().String(),
		AlertID:   alert.ID,
		RuleID:    alert.RuleID,
		Status:    alert.Status,
		ChangedAt: time.Now(),
		Message:   message,
	}

	e.alertHistory = append(e.alertHistory, history)

	maxHistory := cap(e.alertHistory)
	if len(e.alertHistory) > maxHistory {
		e.alertHistory = e.alertHistory[len(e.alertHistory)-maxHistory:]
	}
}

func (e *AlertEngine) sendNotification(alert *models.Alert, channelIDs []string) {
	for _, channelID := range channelIDs {
		if channel, exists := e.notificationChannels[channelID]; exists {
			if sender, exists := e.notificationSenders[channel.Type]; exists {
				sender.Send(alert, []string{channelID})
			}
		}
	}
}

func (e *AlertEngine) GetActiveAlerts() []*models.Alert {
	e.mu.RLock()
	defer e.mu.RUnlock()

	alerts := make([]*models.Alert, 0, len(e.activeAlerts))
	for _, alert := range e.activeAlerts {
		alerts = append(alerts, alert)
	}

	sort.Slice(alerts, func(i, j int) bool {
		return alerts[i].StartsAt.After(alerts[j].StartsAt)
	})

	return alerts
}

func (e *AlertEngine) GetAlertHistory(limit int) []models.AlertHistory {
	e.mu.RLock()
	defer e.mu.RUnlock()

	if limit <= 0 || limit > len(e.alertHistory) {
		limit = len(e.alertHistory)
	}

	history := make([]models.AlertHistory, limit)
	start := len(e.alertHistory) - limit
	if start < 0 {
		start = 0
	}
	copy(history, e.alertHistory[start:])

	return history
}

func (e *AlertEngine) AddSilence(silence *models.AlertSilence) {
	e.mu.Lock()
	defer e.mu.Unlock()

	if silence.ID == "" {
		silence.ID = uuid.New().String()
	}
	e.silences[silence.ID] = silence
	e.silenceIdx.rebuild(e.silences)
}

func (e *AlertEngine) IsSilenced(alert *models.Alert) bool {
	now := time.Now()
	return e.silenceIdx.lookup(alert, now)
}

type ConsoleNotificationSender struct{}

func NewConsoleNotificationSender() *ConsoleNotificationSender {
	return &ConsoleNotificationSender{}
}

func (s *ConsoleNotificationSender) Send(alert *models.Alert, channels []string) error {
	fmt.Printf("[ALERT %s] %s: %s (severity: %s)\n",
		alert.Status, alert.RuleName, alert.Message, alert.Severity)
	return nil
}

func (s *ConsoleNotificationSender) Name() string {
	return "console"
}

type SimpleMetricProvider struct {
	metrics map[string]float64
	series  map[string][]float64
	mu      sync.RWMutex
}

func NewSimpleMetricProvider() *SimpleMetricProvider {
	return &SimpleMetricProvider{
		metrics: make(map[string]float64),
		series:  make(map[string][]float64),
	}
}

func (p *SimpleMetricProvider) SetMetric(name string, value float64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.metrics[name] = value
	p.series[name] = append(p.series[name], value)
	if len(p.series[name]) > 100 {
		p.series[name] = p.series[name][len(p.series[name])-100:]
	}
}

func (p *SimpleMetricProvider) GetMetricValue(name string, labels map[string]string) (float64, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	v, ok := p.metrics[name]
	return v, ok
}

func (p *SimpleMetricProvider) GetMetricSeries(name string, labels map[string]string, duration time.Duration) []float64 {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.series[name]
}

func (e *AlertEngine) GetExprCacheStats() map[string]interface{} {
	return e.exprCache.Stats()
}
