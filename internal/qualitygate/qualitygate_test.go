package qualitygate

import (
	"fmt"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/tasktracker/internal/testfixtures"
)

func TestNewQualityGate(t *testing.T) {
	t.Parallel()

	t.Run("default thresholds", func(t *testing.T) {
		qg := NewQualityGate(Config{MaxCritical: 0, MaxMajor: 5, MaxMinor: 20, MinScore: 70.0}, nil)
		assert.NotNil(t, qg)
		assert.NotNil(t, qg.rules)
		assert.NotNil(t, qg.thresholds)

		thresholds := qg.GetThresholds()
		assert.Equal(t, 0, thresholds[SeverityCritical])
		assert.Equal(t, 5, thresholds[SeverityMajor])
		assert.Equal(t, 20, thresholds[SeverityMinor])
	})

	t.Run("default rules are registered", func(t *testing.T) {
		qg := NewQualityGate(Config{}, nil)
		rules := qg.GetRules("")
		assert.Greater(t, len(rules), 0)
	})
}

func TestQualityGate_RuleManagement(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{}, nil)

	t.Run("add new rule", func(t *testing.T) {
		rule := testfixtures.NewQualityGateRuleBuilder().
			WithID("TEST001").
			WithName("custom-rule").
			WithLanguage(LangGo).
			WithSeverity(SeverityMinor).
			Build()

		qg.AddRule(rule)

		rules := qg.GetRules(LangGo)
		found := false
		for _, r := range rules {
			if r.ID == "TEST001" {
				found = true
				assert.Equal(t, "custom-rule", r.Name)
				assert.True(t, r.Enabled)
			}
		}
		assert.True(t, found)
	})

	t.Run("remove rule", func(t *testing.T) {
		rule := testfixtures.NewQualityGateRuleBuilder().
			WithID("TEST002").
			WithName("to-remove").
			WithLanguage(LangPython).
			Build()

		qg.AddRule(rule)
		qg.RemoveRule("TEST002")

		rules := qg.GetRules(LangPython)
		for _, r := range rules {
			assert.NotEqual(t, "TEST002", r.ID)
		}
	})

	t.Run("enable existing rule", func(t *testing.T) {
		rule := testfixtures.NewQualityGateRuleBuilder().
			WithID("TEST003").
			WithEnabled(false).
			WithLanguage(LangJava).
			Build()

		qg.AddRule(rule)

		err := qg.EnableRule("TEST003")
		require.NoError(t, err)

		rules := qg.GetRules(LangJava)
		for _, r := range rules {
			if r.ID == "TEST003" {
				assert.True(t, r.Enabled)
			}
		}
	})

	t.Run("disable existing rule", func(t *testing.T) {
		rule := testfixtures.NewQualityGateRuleBuilder().
			WithID("TEST004").
			WithEnabled(true).
			WithLanguage(LangJavaScript).
			Build()

		qg.AddRule(rule)

		err := qg.DisableRule("TEST004")
		require.NoError(t, err)

		rules := qg.GetRules(LangJavaScript)
		for _, r := range rules {
			if r.ID == "TEST004" {
				assert.False(t, r.Enabled)
			}
		}
	})

	t.Run("enable non-existent rule", func(t *testing.T) {
		err := qg.EnableRule("NON_EXISTENT")
		require.Error(t, err)
		assert.Contains(t, err.Error(), "rule not found")
	})

	t.Run("disable non-existent rule", func(t *testing.T) {
		err := qg.DisableRule("NON_EXISTENT")
		require.Error(t, err)
		assert.Contains(t, err.Error(), "rule not found")
	})
}

func TestQualityGate_GetRules(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{}, nil)

	t.Run("get all rules", func(t *testing.T) {
		allRules := qg.GetRules("")
		assert.Greater(t, len(allRules), 5)
	})

	t.Run("filter by Go language", func(t *testing.T) {
		goRules := qg.GetRules(LangGo)
		for _, r := range goRules {
			assert.Equal(t, LangGo, r.Language)
		}
		assert.GreaterOrEqual(t, len(goRules), 3)
	})

	t.Run("filter by Python language", func(t *testing.T) {
		pyRules := qg.GetRules(LangPython)
		for _, r := range pyRules {
			assert.Equal(t, LangPython, r.Language)
		}
		assert.GreaterOrEqual(t, len(pyRules), 2)
	})

	t.Run("filter by unsupported language returns empty", func(t *testing.T) {
		rules := qg.GetRules(Language("unknown"))
		assert.Len(t, rules, 0)
	})
}

func TestQualityGate_Analyze_Go(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	t.Run("clean Go code passes", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddCleanGoFile().Build()
		report, err := qg.Analyze("proj_go_clean", LangGo, files)
		require.NoError(t, err)
		assert.NotNil(t, report)
		assert.Equal(t, "proj_go_clean", report.ProjectID)
		assert.Equal(t, LangGo, report.Language)
		assert.True(t, report.Passed)
		assert.Greater(t, report.Score, 0.0)
	})

	t.Run("Go code with unused import", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddGoFileWithUnusedImport().Build()
		report, err := qg.Analyze("proj_go_import", LangGo, files)
		require.NoError(t, err)
		assert.Greater(t, report.TotalIssues, 0)
		assert.Greater(t, report.Minor, 0)
	})

	t.Run("Go code with unhandled error", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddGoFileWithUnhandledError().Build()
		report, err := qg.Analyze("proj_go_err", LangGo, files)
		require.NoError(t, err)
		assert.Greater(t, report.Major, 0)
	})
}

func TestQualityGate_Analyze_Python(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	t.Run("Python code with eval", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddPythonFileWithEval().Build()
		report, err := qg.Analyze("proj_py_eval", LangPython, files)
		require.NoError(t, err)
		assert.Greater(t, report.Critical, 0)
	})

	t.Run("Python code with broad except", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddPythonFileWithBroadExcept().Build()
		report, err := qg.Analyze("proj_py_except", LangPython, files)
		require.NoError(t, err)
		assert.Greater(t, report.Major, 0)
	})
}

func TestQualityGate_Analyze_JavaScript(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	t.Run("JavaScript with console.log", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddJSFileWithConsoleLog().Build()
		report, err := qg.Analyze("proj_js_console", LangJavaScript, files)
		require.NoError(t, err)
		assert.Greater(t, report.Minor, 0)
	})
}

func TestQualityGate_Analyze_BoundaryConditions(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	t.Run("empty file map", func(t *testing.T) {
		report, err := qg.Analyze("proj_empty", LangGo, map[string]string{})
		require.NoError(t, err)
		assert.Equal(t, 0, report.TotalIssues)
		assert.True(t, report.Passed)
		assert.Equal(t, 100.0, report.Score)
	})

	t.Run("single empty file", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().AddEmptyFile("empty.go").Build()
		report, err := qg.Analyze("proj_empty_file", LangGo, files)
		require.NoError(t, err)
		assert.Equal(t, 0, report.TotalIssues)
	})

	t.Run("multiple files", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().
			AddGoFileWithUnusedImport().
			AddGoFileWithUnhandledError().
			Build()

		report, err := qg.Analyze("proj_multi", LangGo, files)
		require.NoError(t, err)
		assert.Greater(t, report.TotalIssues, 1)
	})

	t.Run("file with no issues", func(t *testing.T) {
		files := map[string]string{
			"simple.go": `package main

func main() {
	println("hello")
}
`,
		}
		report, err := qg.Analyze("proj_simple", LangGo, files)
		require.NoError(t, err)
		assert.Equal(t, 0, report.TotalIssues)
	})
}

func TestQualityGate_ThresholdValidation(t *testing.T) {
	t.Parallel()

	t.Run("pass when within thresholds", func(t *testing.T) {
		qg := NewQualityGate(Config{MaxCritical: 0, MaxMajor: 5, MaxMinor: 20, MinScore: 70.0}, nil)
		files := testfixtures.NewSourceFileBuilder().AddCleanGoFile().Build()
		report, err := qg.Analyze("proj_pass", LangGo, files)
		require.NoError(t, err)
		assert.True(t, report.Passed)
	})

	t.Run("fail when critical exceeds threshold", func(t *testing.T) {
		qg := NewQualityGate(Config{MaxCritical: 0, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)
		files := testfixtures.NewSourceFileBuilder().AddPythonFileWithEval().Build()
		report, err := qg.Analyze("proj_critical_fail", LangPython, files)
		require.NoError(t, err)
		assert.False(t, report.Passed)
	})

	t.Run("fail when major exceeds threshold", func(t *testing.T) {
		qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 0, MaxMinor: 20, MinScore: 0}, nil)
		files := testfixtures.NewSourceFileBuilder().AddPythonFileWithBroadExcept().Build()
		report, err := qg.Analyze("proj_major_fail", LangPython, files)
		require.NoError(t, err)
		assert.False(t, report.Passed)
	})

	t.Run("fail when minor exceeds threshold", func(t *testing.T) {
		qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 0, MinScore: 0}, nil)
		files := testfixtures.NewSourceFileBuilder().AddGoFileWithUnusedImport().Build()
		report, err := qg.Analyze("proj_minor_fail", LangGo, files)
		require.NoError(t, err)
		assert.False(t, report.Passed)
	})
}

func TestQualityGate_ScoreCalculation(t *testing.T) {
	t.Parallel()

	t.Run("perfect score with no issues", func(t *testing.T) {
		qg := NewQualityGate(Config{}, nil)
		score := qg.calculateScore(0, 0, 0, 0)
		assert.Equal(t, 100.0, score)
	})

	t.Run("score decreases with critical issues", func(t *testing.T) {
		qg := NewQualityGate(Config{}, nil)
		score := qg.calculateScore(1, 0, 0, 0)
		assert.Less(t, score, 100.0)
		assert.Greater(t, score, 0.0)
	})

	t.Run("score floor at 0", func(t *testing.T) {
		qg := NewQualityGate(Config{}, nil)
		score := qg.calculateScore(100, 100, 100, 100)
		assert.Equal(t, 0.0, score)
	})

	t.Run("critical issues impact more than minor", func(t *testing.T) {
		qg := NewQualityGate(Config{}, nil)
		scoreWithCritical := qg.calculateScore(1, 0, 0, 0)
		scoreWithMinor := qg.calculateScore(0, 0, 1, 0)
		assert.Less(t, scoreWithCritical, scoreWithMinor)
	})
}

func TestQualityGate_ReportContent(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)
	files := testfixtures.NewSourceFileBuilder().
		AddGoFileWithUnusedImport().
		AddPythonFileWithEval().
		Build()

	report, err := qg.Analyze("proj_report", LangGo, files)
	require.NoError(t, err)

	t.Run("report has required fields", func(t *testing.T) {
		assert.NotEmpty(t, report.ReportID)
		assert.Equal(t, "proj_report", report.ProjectID)
		assert.Equal(t, LangGo, report.Language)
		assert.False(t, report.AnalyzedAt.IsZero())
		assert.GreaterOrEqual(t, report.DurationMs, int64(0))
	})

	t.Run("issue counts match", func(t *testing.T) {
		assert.Equal(t, report.TotalIssues, report.Critical+report.Major+report.Minor+report.Info)
	})

	t.Run("issues have file references", func(t *testing.T) {
		for _, issue := range report.Issues {
			assert.NotEmpty(t, issue.File)
			assert.Greater(t, issue.Line, 0)
			assert.NotEmpty(t, issue.RuleID)
		}
	})
}

func TestQualityGate_ThresholdManagement(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 0, MaxMajor: 5, MaxMinor: 20, MinScore: 70.0}, nil)

	t.Run("update threshold", func(t *testing.T) {
		qg.SetThreshold(SeverityCritical, 5)
		thresholds := qg.GetThresholds()
		assert.Equal(t, 5, thresholds[SeverityCritical])
	})

	t.Run("thresholds are isolated", func(t *testing.T) {
		qg.SetThreshold(SeverityMajor, 10)
		thresholds := qg.GetThresholds()
		assert.Equal(t, 5, thresholds[SeverityCritical])
		assert.Equal(t, 10, thresholds[SeverityMajor])
		assert.Equal(t, 20, thresholds[SeverityMinor])
	})

	t.Run("set negative threshold", func(t *testing.T) {
		qg.SetThreshold(SeverityMinor, -1)
		thresholds := qg.GetThresholds()
		assert.Equal(t, -1, thresholds[SeverityMinor])
	})
}

func TestQualityGate_DisabledRules(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	ruleID := "GO001"
	err := qg.DisableRule(ruleID)
	require.NoError(t, err)

	files := testfixtures.NewSourceFileBuilder().AddGoFileWithUnusedImport().Build()
	report, err := qg.Analyze("proj_disabled", LangGo, files)
	require.NoError(t, err)

	for _, issue := range report.Issues {
		assert.NotEqual(t, ruleID, issue.RuleID)
	}
}

func TestQualityGate_RuleBuilder(t *testing.T) {
	t.Parallel()

	t.Run("build rule with all fields", func(t *testing.T) {
		rule := testfixtures.NewQualityGateRuleBuilder().
			WithID("CUST001").
			WithName("custom-check").
			WithDescription("Custom rule").
			WithLanguage(LangRust).
			WithSeverity(SeverityCritical).
			WithCategory("security").
			WithEnabled(true).
			Build()

		assert.Equal(t, "CUST001", rule.ID)
		assert.Equal(t, "custom-check", rule.Name)
		assert.Equal(t, LangRust, rule.Language)
		assert.Equal(t, SeverityCritical, rule.Severity)
		assert.Equal(t, "security", rule.Category)
		assert.True(t, rule.Enabled)
	})
}

func TestQualityGate_ConcurrentAnalysis(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	done := make(chan bool)
	numRuns := 10

	for i := 0; i < numRuns; i++ {
		go func() {
			files := testfixtures.NewSourceFileBuilder().AddCleanGoFile().Build()
			_, _ = qg.Analyze("concurrent", LangGo, files)
			done <- true
		}()
	}

	for i := 0; i < numRuns; i++ {
		select {
		case <-done:
		case <-t.Timer.After(5 * time.Second):
			t.Fatal("timeout waiting for concurrent analysis")
		}
	}
}

func TestQualityGate_IssueSeverity(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	files := testfixtures.NewSourceFileBuilder().
		AddPythonFileWithEval().
		Build()

	report, err := qg.Analyze("proj_severity", LangPython, files)
	require.NoError(t, err)

	for _, issue := range report.Issues {
		if issue.RuleID == "PY003" {
			assert.Equal(t, SeverityCritical, issue.Severity)
		}
	}
}

func TestQualityGate_ResourceLimits(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{}, nil)

	t.Run("nil source files returns error", func(t *testing.T) {
		_, err := qg.Analyze("proj_nil", LangGo, nil)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "cannot be nil")
	})

	t.Run("single file exceeds max size", func(t *testing.T) {
		largeContent := strings.Repeat("a", MaxFileSize+1)
		files := map[string]string{
			"large.go": largeContent,
		}
		_, err := qg.Analyze("proj_large_file", LangGo, files)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "exceeds maximum size")
	})

	t.Run("total size exceeds limit", func(t *testing.T) {
		numFiles := 11
		fileSize := MaxFileSize
		files := make(map[string]string)
		for i := 0; i < numFiles; i++ {
			files[fmt.Sprintf("file_%d.go", i)] = strings.Repeat("a", fileSize)
		}
		_, err := qg.Analyze("proj_total_size", LangGo, files)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "total file size exceeds maximum")
	})

	t.Run("too many files", func(t *testing.T) {
		files := make(map[string]string)
		for i := 0; i < MaxFilesPerAnalysis+1; i++ {
			files[fmt.Sprintf("file_%d.go", i)] = "package main"
		}
		_, err := qg.Analyze("proj_too_many", LangGo, files)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "too many files")
	})

	t.Run("file at max size is allowed", func(t *testing.T) {
		content := strings.Repeat("a", MaxFileSize)
		files := map[string]string{
			"max_size.go": content,
		}
		_, err := qg.Analyze("proj_max_size", LangGo, files)
		assert.NoError(t, err)
	})

	t.Run("empty files map is allowed", func(t *testing.T) {
		report, err := qg.Analyze("proj_empty", LangGo, map[string]string{})
		require.NoError(t, err)
		assert.Equal(t, 0, report.TotalIssues)
		assert.True(t, report.Passed)
	})
}

func TestQualityGate_DetectorInitialization(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{}, nil)

	assert.NotNil(t, qg.detectors)
	assert.Greater(t, len(qg.detectors), 0)

	_, ok := qg.detectors["unused-import"]
	assert.True(t, ok)

	_, ok = qg.detectors["eval-used"]
	assert.True(t, ok)
}

func TestQualityGate_AnalyzeWithLimits(t *testing.T) {
	t.Parallel()

	qg := NewQualityGate(Config{MaxCritical: 10, MaxMajor: 10, MaxMinor: 20, MinScore: 0}, nil)

	t.Run("multiple files within limits", func(t *testing.T) {
		files := testfixtures.NewSourceFileBuilder().
			AddGoFileWithUnusedImport().
			AddPythonFileWithEval().
			Build()

		report, err := qg.Analyze("proj_multi_ok", LangGo, files)
		require.NoError(t, err)
		assert.NotNil(t, report)
		assert.Greater(t, report.TotalIssues, 0)
	})
}
