package service

import (
	"context"
	"fmt"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type QualityService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics
}

func NewQualityService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *QualityService {
	return &QualityService{
		db:      db,
		logger:  log,
		metrics: metrics,
	}
}

func (s *QualityService) CreateRule(ctx context.Context, rule *model.QualityRule) (*model.QualityRule, error) {
	rule.ID = uuid.New().String()
	rule.CreatedAt = time.Now()
	rule.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(rule).Error; err != nil {
		return nil, fmt.Errorf("failed to create quality rule: %w", err)
	}
	return rule, nil
}

func (s *QualityService) GetRule(ctx context.Context, ruleID string) (*model.QualityRule, error) {
	var rule model.QualityRule
	if err := s.db.WithContext(ctx).Where("id = ?", ruleID).First(&rule).Error; err != nil {
		return nil, fmt.Errorf("rule not found: %w", err)
	}
	return &rule, nil
}

func (s *QualityService) ListRules(ctx context.Context, language, severity, category string, page, pageSize int) ([]model.QualityRule, int64, error) {
	var rules []model.QualityRule
	var total int64

	query := s.db.WithContext(ctx).Model(&model.QualityRule{})

	if language != "" {
		query = query.Where("language = ?", language)
	}
	if severity != "" {
		query = query.Where("severity = ?", severity)
	}
	if category != "" {
		query = query.Where("category = ?", category)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, total, nil
}

func (s *QualityService) UpdateRule(ctx context.Context, ruleID string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	result := s.db.WithContext(ctx).
		Model(&model.QualityRule{}).
		Where("id = ?", ruleID).
		Updates(updates)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("rule not found")
	}
	return nil
}

func (s *QualityService) DeleteRule(ctx context.Context, ruleID string) error {
	result := s.db.WithContext(ctx).Delete(&model.QualityRule{}, "id = ?", ruleID)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("rule not found")
	}
	return nil
}

func (s *QualityService) RunQualityCheck(ctx context.Context, req *model.QualityCheckRequest) (*model.QualityCheckResult, error) {
	start := time.Now()
	s.metrics.IncInFlight()
	defer s.metrics.DecInFlight()

	defer func() {
		s.metrics.ObserveTaskDuration("quality", "check", "success", time.Since(start))
	}()

	var rules []model.QualityRule
	query := s.db.WithContext(ctx).Where("enabled = ?", true)
	if len(req.Rules) > 0 {
		query = query.Where("id IN ?", req.Rules)
	}
	if err := query.Find(&rules).Error; err != nil {
		return nil, fmt.Errorf("failed to load quality rules: %w", err)
	}

	issues := s.analyzeCode(rules, req)

	criticalCount, highCount, mediumCount, lowCount := s.countIssues(issues)

	report := &model.QualityReport{
		ID:            uuid.New().String(),
		ProjectID:     req.ProjectID,
		Branch:        req.Branch,
		CommitHash:    req.CommitHash,
		Language:      req.Language,
		TotalIssues:   len(issues),
		CriticalCount: criticalCount,
		HighCount:     highCount,
		MediumCount:   mediumCount,
		LowCount:      lowCount,
		Passed:        s.checkGate(criticalCount, highCount, mediumCount, lowCount),
		Issues:        issues,
		GeneratedBy:   "system",
		GeneratedAt:   time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(report).Error; err != nil {
		s.metrics.ObserveError("quality", "db_error")
		return nil, fmt.Errorf("failed to save quality report: %w", err)
	}

	return &model.QualityCheckResult{
		ReportID:      report.ID,
		Passed:        report.Passed,
		TotalIssues:   report.TotalIssues,
		CriticalCount: criticalCount,
		HighCount:     highCount,
		MediumCount:   mediumCount,
		LowCount:      lowCount,
		Issues:        issues,
		GeneratedAt:   report.GeneratedAt,
	}, nil
}

func (s *QualityService) analyzeCode(rules []model.QualityRule, req *model.QualityCheckRequest) []model.QualityIssue {
	var issues []model.QualityIssue

	for _, rule := range rules {
		issue := model.QualityIssue{
			RuleID:      rule.ID,
			RuleName:    rule.Name,
			Severity:    rule.Severity,
			Category:    rule.Category,
			Message:     fmt.Sprintf("Rule %s detected in %s", rule.Name, req.CodePath),
			FilePath:    req.CodePath,
			LineNumber:  1,
			ColumnStart: 1,
			ColumnEnd:   10,
			CodeSnippet: "// Sample code snippet",
			Suggestion:  "Review and fix according to rule requirements",
		}
		issues = append(issues, issue)
	}

	return issues
}

func (s *QualityService) countIssues(issues []model.QualityIssue) (critical, high, medium, low int) {
	for _, issue := range issues {
		switch issue.Severity {
		case "critical":
			critical++
		case "high":
			high++
		case "medium":
			medium++
		case "low":
			low++
		}
	}
	return
}

func (s *QualityService) checkGate(critical, high, medium, low int) bool {
	var gateConfig model.QualityGateConfig
	if err := s.db.Where("enabled = ?", true).First(&gateConfig).Error; err != nil {
		return critical == 0 && high == 0
	}

	return critical <= gateConfig.CriticalLimit &&
		high <= gateConfig.HighLimit &&
		medium <= gateConfig.MediumLimit
}

func (s *QualityService) GetReport(ctx context.Context, reportID string) (*model.QualityReport, error) {
	var report model.QualityReport
	if err := s.db.WithContext(ctx).Where("id = ?", reportID).First(&report).Error; err != nil {
		return nil, fmt.Errorf("report not found: %w", err)
	}
	return &report, nil
}

func (s *QualityService) ListReports(ctx context.Context, projectID string, passed *bool, page, pageSize int) ([]model.QualityReport, int64, error) {
	var reports []model.QualityReport
	var total int64

	query := s.db.WithContext(ctx).Model(&model.QualityReport{})

	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}
	if passed != nil {
		query = query.Where("passed = ?", *passed)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("generated_at DESC").Find(&reports).Error; err != nil {
		return nil, 0, err
	}

	return reports, total, nil
}

func (s *QualityService) GetGateConfig(ctx context.Context, configID string) (*model.QualityGateConfig, error) {
	var config model.QualityGateConfig
	if err := s.db.WithContext(ctx).Where("id = ?", configID).First(&config).Error; err != nil {
		return nil, fmt.Errorf("gate config not found: %w", err)
	}
	return &config, nil
}

func (s *QualityService) UpdateGateConfig(ctx context.Context, configID string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	result := s.db.WithContext(ctx).
		Model(&model.QualityGateConfig{}).
		Where("id = ?", configID).
		Updates(updates)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("gate config not found")
	}
	return nil
}
