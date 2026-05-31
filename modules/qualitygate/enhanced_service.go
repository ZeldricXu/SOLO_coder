package qualitygate

import (
	"context"
	"depguard/database"
	"depguard/dynamicconfig"
	"depguard/events"
	"depguard/logger"
	"depguard/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"math"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type AnalysisStrategy interface {
	Analyze(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue
	Name() string
	Description() string
	SupportsLanguage(language string) bool
	Initialize() error
}

type QualityCalculationStrategy interface {
	CalculateScore(critical, major, minor, info int) float64
	Name() string
	Description() string
}

type GateEvaluationStrategy interface {
	Evaluate(gate *QualityGate, report *AnalysisReport) *GateCheckResult
	Name() string
	Description() string
}

type StrictQualityStrategy struct{}

func (s *StrictQualityStrategy) CalculateScore(critical, major, minor, info int) float64 {
	score := 100.0
	score -= float64(critical) * 20.0
	score -= float64(major) * 7.0
	score -= float64(minor) * 2.0
	score -= float64(info) * 0.5
	return math.Max(0, math.Min(100, score))
}

func (s *StrictQualityStrategy) Name() string { return "strict" }
func (s *StrictQualityStrategy) Description() string {
	return "Strict quality calculation with higher penalty weights"
}

type LenientQualityStrategy struct{}

func (s *LenientQualityStrategy) CalculateScore(critical, major, minor, info int) float64 {
	score := 100.0
	score -= float64(critical) * 10.0
	score -= float64(major) * 3.0
	score -= float64(minor) * 1.0
	return math.Max(0, math.Min(100, score))
}

func (s *LenientQualityStrategy) Name() string { return "lenient" }
func (s *LenientQualityStrategy) Description() string {
	return "Lenient quality calculation with lower penalty weights"
}

type DefaultQualityStrategy struct{}

func (s *DefaultQualityStrategy) CalculateScore(critical, major, minor, info int) float64 {
	score := 100.0
	score -= float64(critical) * 15.0
	score -= float64(major) * 5.0
	score -= float64(minor) * 1.0
	return math.Max(0, math.Min(100, score))
}

func (s *DefaultQualityStrategy) Name() string { return "default" }
func (s *DefaultQualityStrategy) Description() string {
	return "Default quality calculation strategy"
}

type StrictGateStrategy struct{}

func (s *StrictGateStrategy) Evaluate(gate *QualityGate, report *AnalysisReport) *GateCheckResult {
	result := &GateCheckResult{
		Passed: true,
		Checks: []GateCheckDetail{},
	}

	actual := map[string]float64{
		"critical_issues": float64(report.CriticalIssues),
		"major_issues":    float64(report.MajorIssues),
		"minor_issues":    float64(report.MinorIssues),
		"info_issues":     float64(report.InfoIssues),
		"total_issues":    float64(report.TotalIssues),
		"quality_score":   report.QualityScore,
	}

	for _, cond := range gate.Conditions {
		val, ok := actual[cond.Metric]
		if !ok {
			continue
		}

		passed := evaluateCondition(val, cond)
		result.Checks = append(result.Checks, GateCheckDetail{
			Metric:    cond.Metric,
			Threshold: cond.Threshold,
			Actual:    val,
			Passed:    passed,
		})

		if !passed {
			result.Passed = false
			result.Failures = append(result.Failures, cond.Metric)
		}
	}

	return result
}

func (s *StrictGateStrategy) Name() string { return "strict_gate" }
func (s *StrictGateStrategy) Description() string {
	return "Strict gate evaluation - all conditions must pass"
}

type SoftFailGateStrategy struct{}

func (s *SoftFailGateStrategy) Evaluate(gate *QualityGate, report *AnalysisReport) *GateCheckResult {
	result := &GateCheckResult{
		Passed: true,
		Checks: []GateCheckDetail{},
	}

	actual := map[string]float64{
		"critical_issues": float64(report.CriticalIssues),
		"major_issues":    float64(report.MajorIssues),
		"minor_issues":    float64(report.MinorIssues),
		"info_issues":     float64(report.InfoIssues),
		"total_issues":    float64(report.TotalIssues),
		"quality_score":   report.QualityScore,
	}

	criticalFail := false
	otherFailCount := 0

	for _, cond := range gate.Conditions {
		val, ok := actual[cond.Metric]
		if !ok {
			continue
		}

		passed := evaluateCondition(val, cond)
		result.Checks = append(result.Checks, GateCheckDetail{
			Metric:    cond.Metric,
			Threshold: cond.Threshold,
			Actual:    val,
			Passed:    passed,
		})

		if !passed {
			if cond.Metric == "critical_issues" {
				criticalFail = true
			}
			otherFailCount++
			result.Failures = append(result.Failures, cond.Metric)
		}
	}

	if criticalFail || otherFailCount >= 3 {
		result.Passed = false
	}

	return result
}

func (s *SoftFailGateStrategy) Name() string { return "soft_fail_gate" }
func (s *SoftFailGateStrategy) Description() string {
	return "Soft fail gate - allows up to 2 non-critical failures"
}

type LineLengthRuleStrategy struct{}

func (s *LineLengthRuleStrategy) Analyze(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue {
	var issues []AnalysisIssue
	lines := strings.Split(content, "\n")

	for lineIdx, line := range lines {
		lineNum := lineIdx + 1
		ruleKeys := []string{"GO001", "PY001", "JS003"}

		for _, key := range ruleKeys {
			if rule, ok := rules[key]; ok && rule.Enabled {
				maxLen := 120
				if v, ok := rule.Config["max_length"].(float64); ok {
					maxLen = int(v)
				} else if v, ok := rule.Config["max_length"].(int); ok {
					maxLen = v
				}

				if utf8.RuneCountInString(line) > maxLen {
					issues = append(issues, AnalysisIssue{
						RuleKey:     key,
						Message:     "Line exceeds maximum length",
						Severity:    rule.Severity,
						Line:        lineNum,
						File:        filePath,
						StartLine:   lineNum,
						EndLine:     lineNum,
						StartColumn: 1,
						EndColumn:   utf8.RuneCountInString(line),
					})
				}
			}
		}
	}

	return issues
}

func (s *LineLengthRuleStrategy) Name() string { return "line_length" }
func (s *LineLengthRuleStrategy) Description() string {
	return "Checks for lines exceeding maximum length"
}
func (s *LineLengthRuleStrategy) SupportsLanguage(language string) bool {
	return true
}
func (s *LineLengthRuleStrategy) Initialize() error { return nil }

type JSLogRuleStrategy struct{}

func (s *JSLogRuleStrategy) Analyze(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue {
	var issues []AnalysisIssue
	lines := strings.Split(content, "\n")

	for lineIdx, line := range lines {
		lineNum := lineIdx + 1

		if rule, ok := rules["JS001"]; ok && rule.Enabled {
			if strings.Contains(line, "console.log") {
				issues = append(issues, AnalysisIssue{
					RuleKey:   "JS001",
					Message:   "Avoid console.log in production",
					Severity:  rule.Severity,
					Line:      lineNum,
					File:      filePath,
					StartLine: lineNum,
					EndLine:   lineNum,
				})
			}
		}

		if rule, ok := rules["JS002"]; ok && rule.Enabled {
			if strings.Contains(line, "var ") {
				issues = append(issues, AnalysisIssue{
					RuleKey:   "JS002",
					Message:   "Use let or const instead of var",
					Severity:  rule.Severity,
					Line:      lineNum,
					File:      filePath,
					StartLine: lineNum,
					EndLine:   lineNum,
				})
			}
		}
	}

	return issues
}

func (s *JSLogRuleStrategy) Name() string { return "js_log" }
func (s *JSLogRuleStrategy) Description() string {
	return "Checks JavaScript code quality issues"
}
func (s *JSLogRuleStrategy) SupportsLanguage(language string) bool {
	return language == "js" || language == "javascript" || language == "ts" || language == "typescript"
}
func (s *JSLogRuleStrategy) Initialize() error { return nil }

type GoErrorRuleStrategy struct{}

func (s *GoErrorRuleStrategy) Analyze(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue {
	var issues []AnalysisIssue
	lines := strings.Split(content, "\n")

	for lineIdx, line := range lines {
		lineNum := lineIdx + 1

		if rule, ok := rules["GO002"]; ok && rule.Enabled {
			if strings.Contains(line, "err = ") || strings.Contains(line, "err := ") {
				checked := false
				for j := lineIdx + 1; j < min(lineIdx+5, len(lines)); j++ {
					if strings.Contains(lines[j], "if err") {
						checked = true
						break
					}
				}
				if !checked {
					issues = append(issues, AnalysisIssue{
						RuleKey:   "GO002",
						Message:   "Potentially unchecked error",
						Severity:  rule.Severity,
						Line:      lineNum,
						File:      filePath,
						StartLine: lineNum,
						EndLine:   lineNum,
					})
				}
			}
		}
	}

	return issues
}

func (s *GoErrorRuleStrategy) Name() string { return "go_error" }
func (s *GoErrorRuleStrategy) Description() string {
	return "Checks Go error handling patterns"
}
func (s *GoErrorRuleStrategy) SupportsLanguage(language string) bool {
	return language == "go"
}
func (s *GoErrorRuleStrategy) Initialize() error { return nil }

type FunctionLengthStrategy struct{}

func (s *FunctionLengthStrategy) Analyze(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue {
	var issues []AnalysisIssue
	lines := strings.Split(content, "\n")

	for langKey, maxLinesKey := range map[string]string{"GO003": "go", "PY003": "python", "JS003": "js"} {
		if rule, ok := rules[langKey]; ok && rule.Enabled {
			maxLines := 50
			if v, ok := rule.Config["max_lines"].(float64); ok {
				maxLines = int(v)
			}

			funcStart := -1
			for i, line := range lines {
				trimmed := strings.TrimSpace(line)
				switch maxLinesKey {
				case "go":
					if strings.HasPrefix(trimmed, "func ") {
						funcStart = i
					}
				case "python":
					if strings.HasPrefix(trimmed, "def ") {
						funcStart = i
					}
				case "js":
					if strings.HasPrefix(trimmed, "function ") || strings.Contains(trimmed, "=>") {
						funcStart = i
					}
				}

				if funcStart >= 0 && trimmed == "}" {
					if i-funcStart > maxLines {
						issues = append(issues, AnalysisIssue{
							RuleKey:   langKey,
							Message:   "Function is too long",
							Severity:  rule.Severity,
							Line:      funcStart + 1,
							File:      filePath,
							StartLine: funcStart + 1,
							EndLine:   i + 1,
						})
					}
					funcStart = -1
				}
			}
		}
	}

	return issues
}

func (s *FunctionLengthStrategy) Name() string { return "function_length" }
func (s *FunctionLengthStrategy) Description() string {
	return "Checks function/method length"
}
func (s *FunctionLengthStrategy) SupportsLanguage(language string) bool {
	return true
}
func (s *FunctionLengthStrategy) Initialize() error { return nil }

type CompositeAnalysisStrategy struct {
	strategies []AnalysisStrategy
}

func (s *CompositeAnalysisStrategy) Analyze(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue {
	var allIssues []AnalysisIssue
	for _, strategy := range s.strategies {
		issues := strategy.Analyze(filePath, content, rules)
		allIssues = append(allIssues, issues...)
	}
	return allIssues
}

func (s *CompositeAnalysisStrategy) Name() string { return "composite" }
func (s *CompositeAnalysisStrategy) Description() string {
	return "Combines multiple analysis strategies"
}
func (s *CompositeAnalysisStrategy) SupportsLanguage(language string) bool { return true }
func (s *CompositeAnalysisStrategy) Initialize() error { return nil }

func (s *CompositeAnalysisStrategy) AddStrategy(strategy AnalysisStrategy) {
	s.strategies = append(s.strategies, strategy)
}

func (s *CompositeAnalysisStrategy) RemoveStrategy(name string) {
	var newStrategies []AnalysisStrategy
	for _, strat := range s.strategies {
		if strat.Name() != name {
			newStrategies = append(newStrategies, strat)
		}
	}
	s.strategies = newStrategies
}

func (s *CompositeAnalysisStrategy) GetStrategies() []AnalysisStrategy {
	return s.strategies
}

type EnhancedService struct {
	Service
	configManager               *dynamicconfig.Manager
	analysisStrategies          map[string]AnalysisStrategy
	qualityStrategies           map[string]QualityCalculationStrategy
	gateStrategies              map[string]GateEvaluationStrategy
	currentAnalysisStrategy     string
	currentQualityStrategy      string
	currentGateStrategy         string
	strategyMu                  sync.RWMutex
	initialized                 bool
}

var enhancedQualityInstance *EnhancedService
var enhancedQualityOnce sync.Once

func NewEnhancedService() *EnhancedService {
	enhancedQualityOnce.Do(func() {
		enhancedQualityInstance = &EnhancedService{
			Service: Service{
				db: database.Get(),
			},
			configManager:              dynamicconfig.GetManager(),
			analysisStrategies:         make(map[string]AnalysisStrategy),
			qualityStrategies:          make(map[string]QualityCalculationStrategy),
			gateStrategies:             make(map[string]GateEvaluationStrategy),
			currentAnalysisStrategy:    "composite",
			currentQualityStrategy:     "default",
			currentGateStrategy:        "strict_gate",
			initialized:                false,
		}
		enhancedQualityInstance.initialize()
	})
	return enhancedQualityInstance
}

func NewEnhancedServiceWithDB(db *gorm.DB) *EnhancedService {
	svc := &EnhancedService{
		Service: Service{
			db: db,
		},
		configManager:              dynamicconfig.GetManager(),
		analysisStrategies:         make(map[string]AnalysisStrategy),
		qualityStrategies:          make(map[string]QualityCalculationStrategy),
		gateStrategies:             make(map[string]GateEvaluationStrategy),
		currentAnalysisStrategy:    "composite",
		currentQualityStrategy:     "default",
		currentGateStrategy:        "strict_gate",
		initialized:                false,
	}
	svc.initialize()
	return svc
}

func (s *EnhancedService) initialize() {
	composite := &CompositeAnalysisStrategy{}
	composite.AddStrategy(&LineLengthRuleStrategy{})
	composite.AddStrategy(&JSLogRuleStrategy{})
	composite.AddStrategy(&GoErrorRuleStrategy{})
	composite.AddStrategy(&FunctionLengthStrategy{})

	s.analysisStrategies["composite"] = composite
	s.analysisStrategies["line_length"] = &LineLengthRuleStrategy{}
	s.analysisStrategies["js_log"] = &JSLogRuleStrategy{}
	s.analysisStrategies["go_error"] = &GoErrorRuleStrategy{}
	s.analysisStrategies["function_length"] = &FunctionLengthStrategy{}

	s.qualityStrategies["default"] = &DefaultQualityStrategy{}
	s.qualityStrategies["strict"] = &StrictQualityStrategy{}
	s.qualityStrategies["lenient"] = &LenientQualityStrategy{}

	s.gateStrategies["strict_gate"] = &StrictGateStrategy{}
	s.gateStrategies["soft_fail_gate"] = &SoftFailGateStrategy{}

	s.configManager.RegisterListener(dynamicconfig.ConfigTypeQualityGate, s)
	s.initialized = true
	logger.Get().Info("QualityGate EnhancedService initialized",
		zap.String("analysis_strategy", s.currentAnalysisStrategy),
		zap.String("quality_strategy", s.currentQualityStrategy),
		zap.String("gate_strategy", s.currentGateStrategy))
}

func (s *EnhancedService) OnConfigChange(event dynamicconfig.ConfigChangeEvent) {
	logger.Get().Info("QualityGate config change received",
		zap.String("change_type", event.ChangeType),
		zap.String("key", event.Key))

	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()

	switch event.Key {
	case "analysis_strategy":
		if _, exists := s.analysisStrategies[event.NewValue]; exists {
			s.currentAnalysisStrategy = event.NewValue
		}
	case "quality_strategy":
		if _, exists := s.qualityStrategies[event.NewValue]; exists {
			s.currentQualityStrategy = event.NewValue
		}
	case "gate_strategy":
		if _, exists := s.gateStrategies[event.NewValue]; exists {
			s.currentGateStrategy = event.NewValue
		}
	}
}

func (s *EnhancedService) RegisterAnalysisStrategy(strategy AnalysisStrategy) error {
	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()

	if err := strategy.Initialize(); err != nil {
		return err
	}

	s.analysisStrategies[strategy.Name()] = strategy

	if composite, ok := s.analysisStrategies["composite"].(*CompositeAnalysisStrategy); ok {
		composite.AddStrategy(strategy)
	}

	logger.Get().Info("Analysis strategy registered", zap.String("strategy", strategy.Name()))
	return nil
}

func (s *EnhancedService) UnregisterAnalysisStrategy(name string) {
	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()

	if composite, ok := s.analysisStrategies["composite"].(*CompositeAnalysisStrategy); ok {
		composite.RemoveStrategy(name)
	}

	delete(s.analysisStrategies, name)
	logger.Get().Info("Analysis strategy unregistered", zap.String("strategy", name))
}

func (s *EnhancedService) RegisterQualityStrategy(strategy QualityCalculationStrategy) {
	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()
	s.qualityStrategies[strategy.Name()] = strategy
	logger.Get().Info("Quality strategy registered", zap.String("strategy", strategy.Name()))
}

func (s *EnhancedService) UnregisterQualityStrategy(name string) {
	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()
	delete(s.qualityStrategies, name)
}

func (s *EnhancedService) RegisterGateStrategy(strategy GateEvaluationStrategy) {
	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()
	s.gateStrategies[strategy.Name()] = strategy
	logger.Get().Info("Gate strategy registered", zap.String("strategy", strategy.Name()))
}

func (s *EnhancedService) UnregisterGateStrategy(name string) {
	s.strategyMu.Lock()
	defer s.strategyMu.Unlock()
	delete(s.gateStrategies, name)
}

func (s *EnhancedService) SwitchAnalysisStrategy(name string) error {
	s.strategyMu.RLock()
	_, exists := s.analysisStrategies[name]
	s.strategyMu.RUnlock()

	if !exists {
		return nil
	}

	s.strategyMu.Lock()
	s.currentAnalysisStrategy = name
	s.strategyMu.Unlock()

	logger.Get().Info("Analysis strategy switched", zap.String("strategy", name))
	return nil
}

func (s *EnhancedService) SwitchQualityStrategy(name string) error {
	s.strategyMu.RLock()
	_, exists := s.qualityStrategies[name]
	s.strategyMu.RUnlock()

	if !exists {
		return nil
	}

	s.strategyMu.Lock()
	s.currentQualityStrategy = name
	s.strategyMu.Unlock()

	logger.Get().Info("Quality strategy switched", zap.String("strategy", name))
	return nil
}

func (s *EnhancedService) SwitchGateStrategy(name string) error {
	s.strategyMu.RLock()
	_, exists := s.gateStrategies[name]
	s.strategyMu.RUnlock()

	if !exists {
		return nil
	}

	s.strategyMu.Lock()
	s.currentGateStrategy = name
	s.strategyMu.Unlock()

	logger.Get().Info("Gate strategy switched", zap.String("strategy", name))
	return nil
}

func (s *EnhancedService) GetAnalysisStrategies() map[string]AnalysisStrategy {
	s.strategyMu.RLock()
	defer s.strategyMu.RUnlock()
	return s.analysisStrategies
}

func (s *EnhancedService) GetQualityStrategies() map[string]QualityCalculationStrategy {
	s.strategyMu.RLock()
	defer s.strategyMu.RUnlock()
	return s.qualityStrategies
}

func (s *EnhancedService) GetGateStrategies() map[string]GateEvaluationStrategy {
	s.strategyMu.RLock()
	defer s.strategyMu.RUnlock()
	return s.gateStrategies
}

func (s *EnhancedService) GetCurrentStrategies() map[string]string {
	s.strategyMu.RLock()
	defer s.strategyMu.RUnlock()
	return map[string]string{
		"analysis": s.currentAnalysisStrategy,
		"quality":  s.currentQualityStrategy,
		"gate":     s.currentGateStrategy,
	}
}

func (s *EnhancedService) Analyze(ctx context.Context, req *AnalyzeRequest) (*AnalysisReport, error) {
	report := &AnalysisReport{
		ID:        utils.GenerateID("report"),
		ProjectID: req.ProjectID,
		Commit:    req.Commit,
		Branch:    req.Branch,
		Status:    "running",
		StartedAt: time.Now(),
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(report).Error; err != nil {
		return nil, err
	}

	go s.runEnhancedAnalysis(ctx, report, req)

	return report, nil
}

func (s *EnhancedService) runEnhancedAnalysis(ctx context.Context, report *AnalysisReport, req *AnalyzeRequest) {
	defer func() {
		now := time.Now()
		report.CompletedAt = &now
		report.UpdatedAt = now
		s.db.Save(report)

		events.Get().Publish(ctx, events.Event{
			Type: "analysis.completed",
			Payload: map[string]interface{}{
				"report_id":  report.ID,
				"project_id": report.ProjectID,
				"status":     report.Status,
				"score":      report.QualityScore,
			},
			TraceID: getTraceID(ctx),
		})
	}()

	var rules []AnalysisRule
	q := s.db.Where("language = ? AND enabled = ?", req.Language, true)
	if len(req.Rules) > 0 {
		q = q.Where("key IN ?", req.Rules)
	}
	q.Find(&rules)

	ruleMap := make(map[string]*AnalysisRule)
	for i := range rules {
		ruleMap[rules[i].Key] = &rules[i]
	}

	s.strategyMu.RLock()
	analysisStrategy := s.analysisStrategies[s.currentAnalysisStrategy]
	qualityStrategy := s.qualityStrategies[s.currentQualityStrategy]
	gateStrategy := s.gateStrategies[s.currentGateStrategy]
	s.strategyMu.RUnlock()

	var issues []AnalysisIssue
	for filePath, content := range req.Code {
		if analysisStrategy != nil {
			fileIssues := analysisStrategy.Analyze(filePath, content, ruleMap)
			issues = append(issues, fileIssues...)
		}
	}

	critical := 0
	major := 0
	minor := 0
	info := 0
	for _, issue := range issues {
		switch issue.Severity {
		case "critical":
			critical++
		case "major":
			major++
		case "minor":
			minor++
		case "info":
			info++
		}
	}

	report.Issues = issues
	report.TotalIssues = len(issues)
	report.CriticalIssues = critical
	report.MajorIssues = major
	report.MinorIssues = minor
	report.InfoIssues = info

	var score float64
	if qualityStrategy != nil {
		score = qualityStrategy.CalculateScore(critical, major, minor, info)
	} else {
		score = s.calculateQualityScore(critical, major, minor)
	}
	report.QualityScore = score

	gate := s.getGateForProject(req.ProjectID)
	if gate != nil && gateStrategy != nil {
		gateResult := gateStrategy.Evaluate(gate, report)
		report.GateResult = gateResult
		if gateResult.Passed {
			report.Status = "passed"
		} else {
			report.Status = "failed"
		}
	} else {
		report.Status = "completed"
	}

	logger.Get().Info("enhanced analysis completed",
		zap.String("report_id", report.ID),
		zap.Int("issues", len(issues)),
		zap.Float64("score", score),
		zap.String("analysis_strategy", s.currentAnalysisStrategy),
		zap.String("quality_strategy", s.currentQualityStrategy),
		zap.String("gate_strategy", s.currentGateStrategy))
}

func (s *EnhancedService) IsInitialized() bool {
	return s.initialized
}

func evaluateCondition(actual float64, cond GateCondition) bool {
	switch cond.Operator {
	case "GT":
		return actual > cond.Threshold
	case "GTE":
		return actual >= cond.Threshold
	case "LT":
		return actual < cond.Threshold
	case "LTE":
		return actual <= cond.Threshold
	case "EQ":
		return actual == cond.Threshold
	case "NEQ":
		return actual != cond.Threshold
	default:
		return true
	}
}

func getTraceID(ctx context.Context) string {
	if val := ctx.Value("trace_id"); val != nil {
		if s, ok := val.(string); ok {
			return s
		}
	}
	return ""
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
