package codequality

import (
	"sync"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"taskflow/internal/testutils"
	"taskflow/pkg/models"
)

func TestPythonAnalyzer_Analyze_NoIssues(t *testing.T) {
	factory := testutils.NewTestDataFactory()
	analyzer := NewPythonAnalyzer()

	cleanCode := factory.CreateCleanPythonCode()
	issues := analyzer.Analyze(cleanCode, "clean.py")

	assert.Empty(t, issues, "Clean Python code should have no issues")
}

func TestPythonAnalyzer_Analyze_AllIssues(t *testing.T) {
	factory := testutils.NewTestDataFactory()
	analyzer := NewPythonAnalyzer()

	code := factory.CreatePythonCode(map[string]bool{
		"print":            true,
		"todo":             true,
		"debug_import":     true,
		"hardcoded_secret": true,
	})

	issues := analyzer.Analyze(code, "test.py")

	foundRules := make(map[string]bool)
	for _, issue := range issues {
		foundRules[issue.RuleID] = true
	}

	assert.True(t, foundRules["PY001"], "Should detect print statements")
	assert.True(t, foundRules["PY002"], "Should detect TODO comments")
	assert.True(t, foundRules["PY004"], "Should detect debug imports")
	assert.True(t, foundRules["PY005"], "Should detect hardcoded secrets")
}

func TestPythonAnalyzer_Analyze_LineAndColumn(t *testing.T) {
	code := `print("hello")
# TODO: fix this
print("world")`

	analyzer := NewPythonAnalyzer()
	issues := analyzer.Analyze(code, "test.py")

	assert.GreaterOrEqual(t, len(issues), 3)

	for _, issue := range issues {
		assert.Greater(t, issue.Line, 0)
		assert.GreaterOrEqual(t, issue.Column, 0)
	}
}

func TestPythonAnalyzer_RuleManagement(t *testing.T) {
	analyzer := NewPythonAnalyzer()
	factory := testutils.NewTestDataFactory()

	rules := analyzer.GetRules()
	assert.Greater(t, len(rules), 0)

	newRule := factory.CreateAnalysisRule("TEST001", models.LanguagePython)
	analyzer.AddRule(newRule)

	rulesAfter := analyzer.GetRules()
	assert.Equal(t, len(rules)+1, len(rulesAfter))

	analyzer.DisableRule("PY001")
	code := `print("test")`
	issues := analyzer.Analyze(code, "test.py")
	hasPY001 := false
	for _, issue := range issues {
		if issue.RuleID == "PY001" {
			hasPY001 = true
			break
		}
	}
	assert.False(t, hasPY001, "Disabled rule should not trigger")

	analyzer.EnableRule("PY001")
	issuesAfter := analyzer.Analyze(code, "test.py")
	hasPY001After := false
	for _, issue := range issuesAfter {
		if issue.RuleID == "PY001" {
			hasPY001After = true
			break
		}
	}
	assert.True(t, hasPY001After, "Re-enabled rule should trigger")

	analyzer.RemoveRule("TEST001")
	rulesFinal := analyzer.GetRules()
	assert.Equal(t, len(rules), len(rulesFinal))
}

func TestJavaScriptAnalyzer_Analyze(t *testing.T) {
	factory := testutils.NewTestDataFactory()
	analyzer := NewJavaScriptAnalyzer()

	code := factory.CreateJavaScriptCode(map[string]bool{
		"console_log":     true,
		"eval":          true,
		"var_declaration": true,
	})

	issues := analyzer.Analyze(code, "test.js")

	foundRules := make(map[string]bool)
	for _, issue := range issues {
		foundRules[issue.RuleID] = true
	}

	assert.True(t, foundRules["JS001"], "Should detect console.log")
	assert.True(t, foundRules["JS002"], "Should detect eval")
	assert.True(t, foundRules["JS003"], "Should detect var declarations")
}

func TestAnalyzerFactory_GetAnalyzer(t *testing.T) {
	factory := GetAnalyzerFactory()

	pythonAnalyzer, err := factory.GetAnalyzer(models.LanguagePython)
	require.NoError(t, err)
	assert.NotNil(t, pythonAnalyzer)
	assert.Equal(t, models.LanguagePython, pythonAnalyzer.GetLanguage())

	jsAnalyzer, err := factory.GetAnalyzer(models.LanguageJavaScript)
	require.NoError(t, err)
	assert.NotNil(t, jsAnalyzer)

	tsAnalyzer, err := factory.GetAnalyzer(models.LanguageTypeScript)
	require.NoError(t, err)
	assert.NotNil(t, tsAnalyzer)

	goAnalyzer, err := factory.GetAnalyzer(models.LanguageGo)
	require.NoError(t, err)
	assert.NotNil(t, goAnalyzer)

	_, err = factory.GetAnalyzer("unknown")
	assert.Error(t, err)
}

func TestQualityGate_Evaluate_Pass(t *testing.T) {
	gate := NewQualityGate()
	factory := testutils.NewTestDataFactory()

	report := factory.CreateAnalysisReport(true, models.LanguagePython)
	gate.Evaluate(report)
	assert.True(t, report.ThresholdPass)
	assert.GreaterOrEqual(t, report.QualityScore, 70.0)
}

func TestQualityGate_Evaluate_Fail(t *testing.T) {
	gate := NewQualityGate()
	factory := testutils.NewTestDataFactory()

	report := factory.CreateAnalysisReport(false, models.LanguagePython)
	gate.Evaluate(report)
	assert.False(t, report.ThresholdPass)
}

func TestQualityGate_ThresholdConfiguration(t *testing.T) {
	gate := NewQualityGate()
	factory := testutils.NewTestDataFactory()

	thresholds := factory.CreateQualityThreshold()
	gate.SetThresholds(thresholds)

	retrieved := gate.GetThresholds()
	assert.Equal(t, thresholds.Critical, retrieved.Critical)
	assert.Equal(t, thresholds.Error, retrieved.Error)
	assert.Equal(t, thresholds.Warning, retrieved.Warning)
}

func TestQualityGate_CalculateScore(t *testing.T) {
	gate := NewQualityGate()

	tests := []struct {
		name          string
		critical      int
		errorCount    int
		warning       int
		info          int
		expectedMin   float64
	}{
		{
			name:        "No issues",
			critical:    0,
			errorCount:  0,
			warning:     0,
			info:        0,
			expectedMin: 90,
		},
		{
			name:        "Critical issues",
			critical:    5,
			errorCount:  0,
			warning:     0,
			info:        0,
			expectedMin: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			report := &models.AnalysisReport{
				TotalFiles: 1,
				IssuesBySeverity: map[models.Severity]int{
					models.SeverityCritical: tt.critical,
					models.SeverityError:    tt.errorCount,
					models.SeverityWarning:  tt.warning,
					models.SeverityInfo:     tt.info,
				},
			}
			score := gate.calculateScore(report)
			assert.GreaterOrEqual(t, score, 0.0)
			assert.LessOrEqual(t, score, 100.0)
		})
	}
}

func TestCodeQualityService_AnalyzeCode(t *testing.T) {
	service := GetCodeQualityService()
	factory := testutils.NewTestDataFactory()

	cleanCode := factory.CreateCleanPythonCode()
	report, err := service.AnalyzeCode(cleanCode, models.LanguagePython, "clean.py")

	require.NoError(t, err)
	assert.NotNil(t, report)
	assert.Equal(t, models.LanguagePython, report.Language)
	assert.True(t, report.ThresholdPass)
	assert.GreaterOrEqual(t, report.QualityScore, 70.0)
}

func TestCodeQualityService_AnalyzeCode_WithIssues(t *testing.T) {
	service := GetCodeQualityService()
	factory := testutils.NewTestDataFactory()

	code := factory.CreatePythonCode(map[string]bool{
		"hardcoded_secret": true,
		"debug_import":     true,
	})

	report, err := service.AnalyzeCode(code, models.LanguagePython, "test.py")

	require.NoError(t, err)
	assert.NotNil(t, report)
	assert.Greater(t, report.TotalIssues, 0)
	assert.Contains(t, report.IssuesBySeverity, models.SeverityCritical)
	assert.Greater(t, report.IssuesBySeverity[models.SeverityCritical], 0)
}

func TestCodeQualityService_InvalidLanguage(t *testing.T) {
	service := GetCodeQualityService()

	_, err := service.AnalyzeCode("code", "invalid_language", "test.txt")
	assert.Error(t, err)
}

func TestCodeQualityService_ConcurrentAnalysis(t *testing.T) {
	service := GetCodeQualityService()
	factory := testutils.NewTestDataFactory()

	cleanCode := factory.CreateCleanPythonCode()
	codeWithIssues := factory.CreatePythonCode(map[string]bool{
		"print": true,
		"todo":  true,
	})

	var wg sync.WaitGroup
	results := make(chan *models.AnalysisReport, 20)

	for i := 0; i < 10; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			report, _ := service.AnalyzeCode(cleanCode, models.LanguagePython, "clean.py")
			results <- report
		}()
		go func() {
			defer wg.Done()
			report, _ := service.AnalyzeCode(codeWithIssues, models.LanguagePython, "issues.py")
			results <- report
		}()
	}

	go func() {
		wg.Wait()
		close(results)
	}()

	for result := range results {
		assert.NotNil(t, result)
		assert.NotEmpty(t, result.ReportID)
	}
}

func TestAnalyzerFactory_Singleton(t *testing.T) {
	factory1 := GetAnalyzerFactory()
	factory2 := GetAnalyzerFactory()
	assert.Same(t, factory1, factory2)
}

func TestCodeQualityService_Singleton(t *testing.T) {
	service1 := GetCodeQualityService()
	service2 := GetCodeQualityService()
	assert.Same(t, service1, service2)
}

func TestCodeQualityService_ThresholdManagement(t *testing.T) {
	service := GetCodeQualityService()
	factory := testutils.NewTestDataFactory()

	thresholds := factory.CreateQualityThreshold()
	service.UpdateThresholds(thresholds)

	retrieved := service.GetThresholds()
	assert.Equal(t, thresholds.QualityScore, retrieved.QualityScore)
	assert.Equal(t, thresholds.Critical, retrieved.Critical)
}

func TestPythonAnalyzer_LongLineDetection(t *testing.T) {
	analyzer := NewPythonAnalyzer()
	longCode := `x = "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a" + "a"
`
	issues := analyzer.Analyze(longCode, "long.py")

	foundPY003 := false
	for _, issue := range issues {
		if issue.RuleID == "PY003" {
			foundPY003 = true
			break
		}
	}
	assert.True(t, foundPY003, "Should detect long lines")
}
