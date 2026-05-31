package qualitygate

import (
	"context"
	"depguard/database"
	"depguard/events"
	"depguard/logger"
	"depguard/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"math"
	"strings"
	"time"
	"unicode/utf8"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{db: database.Get()}
}

func NewServiceWithDB(db *gorm.DB) *Service {
	return &Service{db: db}
}

func (s *Service) InitDefaultRules() {
	var count int64
	s.db.Model(&AnalysisRule{}).Count(&count)
	if count > 0 {
		return
	}

	defaultRules := []*AnalysisRule{
		{ID: utils.GenerateID("rule"), Language: "go", Key: "GO001", Name: "Line too long", Description: "Line exceeds 120 characters", Severity: "minor", Category: "style", Default: true, Enabled: true, Config: map[string]interface{}{"max_length": 120}},
		{ID: utils.GenerateID("rule"), Language: "go", Key: "GO002", Name: "Missing error check", Description: "Error return value is not checked", Severity: "critical", Category: "bug", Default: true, Enabled: true},
		{ID: utils.GenerateID("rule"), Language: "go", Key: "GO003", Name: "Function too long", Description: "Function exceeds 50 lines", Severity: "major", Category: "complexity", Default: true, Enabled: true, Config: map[string]interface{}{"max_lines": 50}},
		{ID: utils.GenerateID("rule"), Language: "js", Key: "JS001", Name: "Use console.log", Description: "Avoid console.log in production", Severity: "major", Category: "style", Default: true, Enabled: true},
		{ID: utils.GenerateID("rule"), Language: "js", Key: "JS002", Name: "Use var instead of let/const", Description: "Use let or const instead of var", Severity: "major", Category: "style", Default: true, Enabled: true},
		{ID: utils.GenerateID("rule"), Language: "python", Key: "PY001", Name: "Line too long", Description: "Line exceeds 100 characters", Severity: "minor", Category: "style", Default: true, Enabled: true, Config: map[string]interface{}{"max_length": 100}},
		{ID: utils.GenerateID("rule"), Language: "python", Key: "PY002", Name: "Import not at top", Description: "Import statement not at top of file", Severity: "minor", Category: "style", Default: true, Enabled: true},
	}

	now := time.Now()
	for _, r := range defaultRules {
		r.CreatedAt = now
		r.UpdatedAt = now
	}
	s.db.Create(defaultRules)

	defaultGate := &QualityGate{
		ID:        utils.GenerateID("gate"),
		Name:      "Default Quality Gate",
		IsDefault: true,
		Conditions: []GateCondition{
			{Metric: "critical_issues", Threshold: 0, Operator: "LTE"},
			{Metric: "major_issues", Threshold: 5, Operator: "LTE"},
			{Metric: "quality_score", Threshold: 70, Operator: "GTE"},
		},
		CreatedAt: now,
		UpdatedAt: now,
	}
	s.db.Create(defaultGate)
}

func (s *Service) ListRules(ctx context.Context, language string) ([]AnalysisRule, error) {
	var rules []AnalysisRule
	q := s.db.WithContext(ctx)
	if language != "" {
		q = q.Where("language = ?", language)
	}
	if err := q.Order("language, key").Find(&rules).Error; err != nil {
		return nil, err
	}
	return rules, nil
}

func (s *Service) CreateRule(ctx context.Context, rule *AnalysisRule) (*AnalysisRule, error) {
	rule.ID = utils.GenerateID("rule")
	rule.CreatedAt = time.Now()
	rule.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Create(rule).Error; err != nil {
		return nil, err
	}
	return rule, nil
}

func (s *Service) UpdateRule(ctx context.Context, rule *AnalysisRule) (*AnalysisRule, error) {
	rule.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Save(rule).Error; err != nil {
		return nil, err
	}
	return rule, nil
}

func (s *Service) DeleteRule(ctx context.Context, id string) error {
	return s.db.WithContext(ctx).Delete(&AnalysisRule{}, "id = ?", id).Error
}

func (s *Service) ListGates(ctx context.Context) ([]QualityGate, error) {
	var gates []QualityGate
	if err := s.db.WithContext(ctx).Order("created_at DESC").Find(&gates).Error; err != nil {
		return nil, err
	}
	return gates, nil
}

func (s *Service) CreateGate(ctx context.Context, gate *QualityGate) (*QualityGate, error) {
	gate.ID = utils.GenerateID("gate")
	gate.CreatedAt = time.Now()
	gate.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Create(gate).Error; err != nil {
		return nil, err
	}
	return gate, nil
}

func (s *Service) Analyze(ctx context.Context, req *AnalyzeRequest) (*AnalysisReport, error) {
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

	go s.runAnalysis(ctx, report, req)

	return report, nil
}

func (s *Service) runAnalysis(ctx context.Context, report *AnalysisReport, req *AnalyzeRequest) {
	defer func() {
		now := time.Now()
		report.CompletedAt = &now
		report.UpdatedAt = now
		s.db.Save(report)

		events.Get().Publish(ctx, events.Event{
			Type: "analysis.completed",
			Payload: map[string]interface{}{
				"report_id": report.ID,
				"project_id": report.ProjectID,
				"status":    report.Status,
			},
			TraceID: ctx.Value("trace_id").(string),
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

	var issues []AnalysisIssue
	for filePath, content := range req.Code {
		fileIssues := s.analyzeFile(filePath, content, ruleMap)
		issues = append(issues, fileIssues...)
	}

	critical := 0
	major := 0
	minor := 0
	for _, issue := range issues {
		switch issue.Severity {
		case "critical":
			critical++
		case "major":
			major++
		case "minor":
			minor++
		}
	}

	report.Issues = issues
	report.TotalIssues = len(issues)
	report.CriticalIssues = critical
	report.MajorIssues = major
	report.MinorIssues = minor

	score := s.calculateQualityScore(critical, major, minor)
	report.QualityScore = score

	gate := s.getGateForProject(req.ProjectID)
	if gate != nil {
		gateResult := s.checkQualityGate(gate, report)
		report.GateResult = gateResult
		if gateResult.Passed {
			report.Status = "passed"
		} else {
			report.Status = "failed"
		}
	} else {
		report.Status = "completed"
	}

	logger.Get().Info("analysis completed",
		zap.String("report_id", report.ID),
		zap.Int("issues", len(issues)),
		zap.Float64("score", score),
	)
}

func (s *Service) analyzeFile(filePath string, content string, rules map[string]*AnalysisRule) []AnalysisIssue {
	var issues []AnalysisIssue
	lines := strings.Split(content, "\n")

	for lineIdx, line := range lines {
		lineNum := lineIdx + 1

		if rule, ok := rules["GO001"]; ok {
			maxLen := 120
			if v, ok := rule.Config["max_length"].(float64); ok {
				maxLen = int(v)
			}
			if utf8.RuneCountInString(line) > maxLen {
				issues = append(issues, AnalysisIssue{
					RuleKey:    "GO001",
					Message:    "Line exceeds maximum length",
					Severity:   "minor",
					Line:       lineNum,
					File:       filePath,
					StartLine:  lineNum,
					EndLine:    lineNum,
					StartColumn: 1,
					EndColumn:  utf8.RuneCountInString(line),
				})
			}
		}

		if rule, ok := rules["GO002"]; ok && rule.Enabled {
			if strings.Contains(line, "err = ") && !strings.Contains(lines[lineIdx:][:min(5, len(lines)-lineIdx)], "if err") {
				issues = append(issues, AnalysisIssue{
					RuleKey:   "GO002",
					Message:   "Potentially unchecked error",
					Severity:  "critical",
					Line:      lineNum,
					File:      filePath,
					StartLine: lineNum,
					EndLine:   lineNum,
				})
			}
		}

		if rule, ok := rules["JS001"]; ok && rule.Enabled {
			if strings.Contains(line, "console.log") {
				issues = append(issues, AnalysisIssue{
					RuleKey:   "JS001",
					Message:   "Avoid console.log in production",
					Severity:  "major",
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
					Severity:  "major",
					Line:      lineNum,
					File:      filePath,
					StartLine: lineNum,
					EndLine:   lineNum,
				})
			}
		}

		if rule, ok := rules["PY001"]; ok {
			maxLen := 100
			if v, ok := rule.Config["max_length"].(float64); ok {
				maxLen = int(v)
			}
			if utf8.RuneCountInString(line) > maxLen {
				issues = append(issues, AnalysisIssue{
					RuleKey:   "PY001",
					Message:   "Line exceeds maximum length",
					Severity:  "minor",
					Line:      lineNum,
					File:      filePath,
					StartLine: lineNum,
					EndLine:   lineNum,
				})
			}
		}
	}

	if rule, ok := rules["GO003"]; ok && rule.Enabled {
		maxLines := 50
		if v, ok := rule.Config["max_lines"].(float64); ok {
			maxLines = int(v)
		}
		funcStart := -1
		for i, line := range lines {
			if strings.HasPrefix(strings.TrimSpace(line), "func ") {
				funcStart = i
			}
			if funcStart >= 0 && strings.TrimSpace(line) == "}" {
				if i-funcStart > maxLines {
					issues = append(issues, AnalysisIssue{
						RuleKey:   "GO003",
						Message:   "Function is too long",
						Severity:  "major",
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

	return issues
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (s *Service) calculateQualityScore(critical, major, minor int) float64 {
	score := 100.0
	score -= float64(critical) * 15.0
	score -= float64(major) * 5.0
	score -= float64(minor) * 1.0
	return math.Max(0, math.Min(100, score))
}

func (s *Service) getGateForProject(projectID string) *QualityGate {
	var gates []QualityGate
	s.db.Where("? = ANY(project_ids)", projectID).Find(&gates)
	if len(gates) > 0 {
		return &gates[0]
	}

	var defaultGate QualityGate
	if err := s.db.Where("is_default = ?", true).First(&defaultGate).Error; err != nil {
		return nil
	}
	return &defaultGate
}

func (s *Service) checkQualityGate(gate *QualityGate, report *AnalysisReport) *GateCheckResult {
	result := &GateCheckResult{
		Passed: true,
		Checks: []GateCheckDetail{},
	}

	actual := map[string]float64{
		"critical_issues": float64(report.CriticalIssues),
		"major_issues":    float64(report.MajorIssues),
		"minor_issues":    float64(report.MinorIssues),
		"total_issues":    float64(report.TotalIssues),
		"quality_score":   report.QualityScore,
	}

	for _, cond := range gate.Conditions {
		val, ok := actual[cond.Metric]
		if !ok {
			continue
		}

		passed := s.evaluateCondition(val, cond)
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

func (s *Service) evaluateCondition(actual float64, cond GateCondition) bool {
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

func (s *Service) GetReport(ctx context.Context, id string) (*AnalysisReport, error) {
	var report AnalysisReport
	if err := s.db.WithContext(ctx).First(&report, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &report, nil
}

func (s *Service) ListReports(ctx context.Context, projectID string, page, size int) ([]AnalysisReport, int64, error) {
	if page < 0 {
		page = 0
	}
	if size <= 0 || size > 100 {
		size = 20
	}

	var reports []AnalysisReport
	var total int64

	q := s.db.WithContext(ctx).Model(&AnalysisReport{})
	if projectID != "" {
		q = q.Where("project_id = ?", projectID)
	}

	q.Count(&total)

	if err := q.Order("created_at DESC").Offset(page * size).Limit(size).Find(&reports).Error; err != nil {
		return nil, 0, err
	}

	return reports, total, nil
}
