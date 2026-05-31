package quality

import (
	"streamsql/internal/common/config"
	"streamsql/internal/common/logger"
)

type QualityService struct {
	executor *RuleExecutor
	config   config.QualityConfig
}

func NewQualityService(cfg config.QualityConfig) *QualityService {
	notification := NewConsoleNotification()
	executor := NewRuleExecutor(notification)

	svc := &QualityService{
		executor: executor,
		config:   cfg,
	}

	svc.initDefaultRules()
	logger.Sugar().Info("Quality service initialized")
	return svc
}

func (s *QualityService) initDefaultRules() {
	defaultRules := []*QualityRule{
		{
			Name:        "Temperature Range Check",
			Description: "Ensure temperature values are within valid range",
			Type:        RuleTypeRange,
			TableName:   "sensor_data",
			ColumnName:  "temperature",
			Parameters: map[string]interface{}{
				"range": map[string]interface{}{
					"min_value": -40.0,
					"max_value": 85.0,
				},
			},
			Severity:       SeverityHigh,
			Status:         RuleStatusActive,
			CronExpression: "*/5 * * * *",
			TimeoutSeconds: 30,
			MaxRetries:     s.config.MaxRetry,
			NotificationCfg: NotificationConfig{
				Enabled:    true,
				Channels:   []string{"console"},
				Recipients: []string{"admin@example.com"},
			},
		},
		{
			Name:        "Device ID Not Null",
			Description: "Ensure device_id is never null",
			Type:        RuleTypeNullCheck,
			TableName:   "sensor_data",
			ColumnName:  "device_id",
			Severity:    SeverityCritical,
			Status:      RuleStatusActive,
			CronExpression: "*/10 * * * *",
			TimeoutSeconds: 30,
			MaxRetries:     s.config.MaxRetry,
			NotificationCfg: NotificationConfig{
				Enabled:    true,
				Channels:   []string{"console", "email"},
				Recipients: []string{"admin@example.com"},
			},
		},
	}

	for _, rule := range defaultRules {
		_ = s.executor.AddRule(rule)
	}
}

func (s *QualityService) AddRule(rule *QualityRule) error {
	return s.executor.AddRule(rule)
}

func (s *QualityService) RemoveRule(ruleID string) error {
	return s.executor.RemoveRule(ruleID)
}

func (s *QualityService) UpdateRule(ruleID string, updates map[string]interface{}) (*QualityRule, error) {
	return s.executor.UpdateRule(ruleID, updates)
}

func (s *QualityService) GetRule(ruleID string) (*QualityRule, error) {
	return s.executor.GetRule(ruleID)
}

func (s *QualityService) ListRules() []*QualityRule {
	return s.executor.ListRules()
}

func (s *QualityService) ExecuteRule(ruleID string) (*RuleExecutionResult, error) {
	return s.executor.ExecuteRule(ruleID)
}

func (s *QualityService) ExecuteAllRules() []*RuleExecutionResult {
	return s.executor.ExecuteAll()
}

func (s *QualityService) GetRuleResults(ruleID string) []RuleExecutionResult {
	return s.executor.GetResults(ruleID)
}

func (s *QualityService) GetRuleAnomalies(ruleID string) []AnomalyRecord {
	return s.executor.GetAnomalies(ruleID)
}

func (s *QualityService) GetAllAnomalies() []AnomalyRecord {
	return s.executor.GetAllAnomalies()
}

func (s *QualityService) ResolveAnomaly(anomalyID string) error {
	return s.executor.ResolveAnomaly(anomalyID)
}

func (s *QualityService) GetStats() map[string]interface{} {
	rules := s.executor.ListRules()
	anomalies := s.executor.GetAllAnomalies()

	resolved := 0
	unresolved := 0
	for _, a := range anomalies {
		if a.Resolved {
			resolved++
		} else {
			unresolved++
		}
	}

	severityCounts := make(map[SeverityLevel]int)
	for _, a := range anomalies {
		severityCounts[a.Severity]++
	}

	return map[string]interface{}{
		"total_rules":      len(rules),
		"total_anomalies":  len(anomalies),
		"resolved":         resolved,
		"unresolved":       unresolved,
		"severity_counts":  severityCounts,
	}
}

func (s *QualityService) Stop() {
	s.executor.Stop()
}
