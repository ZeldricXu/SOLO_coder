package testutils

import (
	"fmt"
	"math/rand"
	"time"

	"taskflow/pkg/models"
)

type TestDataFactory struct{}

func NewTestDataFactory() *TestDataFactory {
	return &TestDataFactory{}
}

func (f *TestDataFactory) CreatePythonCode(issues map[string]bool) string {
	code := `def hello(name):
    """Greets a person."""
    return f"Hello, {name}!"

`
	if issues["print"] {
		code += `print("debug message")
`
	}
	if issues["todo"] {
		code += `# TODO: refactor this function
`
	}
	if issues["long_line"] {
		code += `def long_line_function(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16):
    pass
`
	}
	if issues["debug_import"] {
		code += `import pdb
pdb.set_trace()
`
	}
	if issues["hardcoded_secret"] {
		code += `password = "my_secret_password_123"
`
	}
	code += `
class Calculator:
    def add(self, a, b):
        return a + b

    def multiply(self, a, b):
        return a * b
`
	return code
}

func (f *TestDataFactory) CreateJavaScriptCode(issues map[string]bool) string {
	code := `function hello(name) {
    return "Hello, " + name + "!";
}

`
	if issues["console_log"] {
		code += `console.log("debug message");
`
	}
	if issues["eval"] {
		code += `const result = eval("2 + 2");
`
	}
	if issues["var_declaration"] {
		code += `var x = 10;
`
	}
	return code
}

func (f *TestDataFactory) CreateCleanPythonCode() string {
	return `import logging

logger = logging.getLogger(__name__)

def calculate_sum(numbers):
    """Calculate the sum of a list of numbers."""
    total = 0
    for num in numbers:
        total += num
    logger.info("Calculated sum: %d", total)
    return total

class DataProcessor:
    def __init__(self, config):
        self.config = config

    def process(self, data):
        return [item for item in data if item is not None]
`
}

func (f *TestDataFactory) CreateAnalysisRule(ruleID string, language models.Language) models.AnalysisRule {
	return models.AnalysisRule{
		RuleID:      ruleID,
		Name:        fmt.Sprintf("Rule_%s", ruleID),
		Description: fmt.Sprintf("Test rule %s", ruleID),
		Language:    language,
		Severity:    models.SeverityWarning,
		Pattern:     `#.*TEST_PATTERN`,
		Enabled:     true,
	}
}

func (f *TestDataFactory) CreateQualityThreshold() models.QualityThreshold {
	return models.QualityThreshold{
		Critical:     0,
		Error:        2,
		Warning:      5,
		QualityScore: 80.0,
	}
}

func (f *TestDataFactory) CreateEntity(entityType models.EntityType, id string) *models.Entity {
	if id == "" {
		id = fmt.Sprintf("ent_%d", rand.Int63())
	}
	now := time.Now()
	return &models.Entity{
		ID:     id,
		Type:   entityType,
		Status: models.EntityStatusActive,
		Attributes: map[string]interface{}{
			"name":        fmt.Sprintf("Test Entity %s", id),
			"description": "This is a test entity",
			"priority":    "high",
		},
		CreatedAt: now,
		UpdatedAt: now,
	}
}

func (f *TestDataFactory) CreateConfigDefinition(namespace string, version int) *models.ConfigDefinition {
	now := time.Now()
	return &models.ConfigDefinition{
		ConfigID:  fmt.Sprintf("cfg_%s", namespace),
		Namespace: namespace,
		Version:   version,
		Parameters: map[string]interface{}{
			"timeout":            30,
			"retries":            3,
			"pool_size":          10,
			"resource_timeout":   5,
			"processing_timeout": 30,
		},
		Enabled:   true,
		AppliedAt: &now,
		CreatedAt: now,
		UpdatedAt: now,
	}
}

func (f *TestDataFactory) CreateRunInstance(entityID string, phase models.RunPhase) *models.RunInstance {
	now := time.Now()
	run := &models.RunInstance{
		RunID:     fmt.Sprintf("run_%d", now.UnixNano()),
		EntityID:  entityID,
		Phase:     phase,
		Progress:  0.5,
		Metadata:  make(map[string]interface{}),
		CreatedAt: now,
	}

	if phase != models.RunPhasePending && phase != models.RunPhaseInitializing {
		run.StartedAt = &now
	}

	if phase == models.RunPhaseCompleted || phase == models.RunPhaseFailed || phase == models.RunPhaseCancelled {
		run.CompletedAt = &now
	}

	if phase == models.RunPhaseFailed {
		run.ErrorDetail = "Test error message"
	}

	return run
}

func (f *TestDataFactory) CreateExecuteRequest(traceID string, namespace string, action string) *models.ProcessingResult {
	return &models.ProcessingResult{
		Success:       true,
		ErrorCode:     200,
		ErrorMessage:  "success",
		RunID:         fmt.Sprintf("run_%s", traceID),
	}
}

func (f *TestDataFactory) CreateRandomString(length int) string {
	const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, length)
	for i := range b {
		b[i] = charset[rand.Intn(len(charset))]
	}
	return string(b)
}

func (f *TestDataFactory) CreateCacheEntries(count int) []struct {
	Key   string
	Value interface{}
} {
	entries := make([]struct {
		Key   string
		Value interface{}
	}, count)

	for i := 0; i < count; i++ {
		entries[i] = struct {
			Key   string
			Value interface{}
		}{
			Key:   fmt.Sprintf("key_%s", f.CreateRandomString(8)),
			Value: map[string]interface{}{
				"id":    i,
				"name":  fmt.Sprintf("item_%d", i),
				"value": rand.Intn(1000),
			},
		}
	}
	return entries
}

func (f *TestDataFactory) CreateAnalysisReport(pass bool, language models.Language) *models.AnalysisReport {
	report := &models.AnalysisReport{
		ReportID:    fmt.Sprintf("report_%d", time.Now().UnixNano()),
		Language:    language,
		TotalFiles:  5,
		TotalIssues: 0,
		IssuesBySeverity: map[models.Severity]int{
			models.SeverityCritical: 0,
			models.SeverityError:    0,
			models.SeverityWarning:  0,
			models.SeverityInfo:     0,
		},
		Issues:        []models.AnalysisIssue{},
		ThresholdPass: pass,
		CreatedAt:     time.Now(),
	}

	if pass {
		report.QualityScore = 95.0
	} else {
		report.QualityScore = 50.0
		report.IssuesBySeverity[models.SeverityCritical] = 2
		report.TotalIssues = 2
	}

	return report
}

func (f *TestDataFactory) CreateAnalysisIssue(ruleID string, severity models.Severity, line int) models.AnalysisIssue {
	return models.AnalysisIssue{
		RuleID:   ruleID,
		Line:     line,
		Column:   1,
		Message:  fmt.Sprintf("Test issue for rule %s", ruleID),
		Severity: severity,
		Snippet:  "code_snippet",
	}
}
