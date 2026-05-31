package qualitygate

import (
	"context"
	"depguard/test/testutils"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestCreateRule_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should create analysis rule", func(t *testing.T) {
		rule := testutils.NewRuleBuilder().
			WithLanguage("go").
			WithKey("GO888").
			WithSeverity("critical").
			WithName("Test Rule").
			Build()

		created, err := svc.CreateRule(context.Background(), rule)

		assert.NoError(t, err)
		assert.NotEmpty(t, created.ID)
		assert.Equal(t, "go", created.Language)
		assert.Equal(t, "GO888", created.Key)
		assert.Equal(t, "critical", created.Severity)
	})
}

func TestListRules_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should list rules by language", func(t *testing.T) {
		for i := 0; i < 3; i++ {
			rule := testutils.NewRuleBuilder().
				WithLanguage("go").
				WithKey(fmt.Sprintf("GO%d", i)).
				Build()
			_, _ = svc.CreateRule(context.Background(), rule)
		}

		for i := 0; i < 2; i++ {
			rule := testutils.NewRuleBuilder().
				WithLanguage("js").
				WithKey(fmt.Sprintf("JS%d", i)).
				Build()
			_, _ = svc.CreateRule(context.Background(), rule)
		}

		goRules, err := svc.ListRules(context.Background(), "go")
		assert.NoError(t, err)
		assert.GreaterOrEqual(t, len(goRules), 3)

		jsRules, err := svc.ListRules(context.Background(), "js")
		assert.NoError(t, err)
		assert.GreaterOrEqual(t, len(jsRules), 2)

		allRules, err := svc.ListRules(context.Background(), "")
		assert.NoError(t, err)
		assert.GreaterOrEqual(t, len(allRules), 5)
	})
}

func TestUpdateDeleteRule_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should update and delete rule", func(t *testing.T) {
		rule := testutils.NewRuleBuilder().
			WithLanguage("go").
			WithKey("GOUPDATE").
			WithSeverity("minor").
			Build()

		created, _ := svc.CreateRule(context.Background(), rule)

		created.Severity = "critical"
		created.Description = "Updated description"
		updated, err := svc.UpdateRule(context.Background(), created)

		assert.NoError(t, err)
		assert.Equal(t, "critical", updated.Severity)
		assert.Equal(t, "Updated description", updated.Description)

		err = svc.DeleteRule(context.Background(), created.ID)
		assert.NoError(t, err)

		rules, _ := svc.ListRules(context.Background(), "go")
		for _, r := range rules {
			assert.NotEqual(t, created.ID, r.ID)
		}
	})
}

func TestAnalyze_NormalFlow(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should analyze go code with line length rule", func(t *testing.T) {
		rule1 := testutils.NewRuleBuilder().
			WithLanguage("go").
			WithKey("GO001").
			WithName("Line too long").
			WithConfig(map[string]interface{}{"max_length": 80}).
			Build()
		_, _ = svc.CreateRule(context.Background(), rule1)

		code := `package main

import "fmt"

// This is a very long comment line that exceeds the 80 character limit and should trigger a warning
func main() {
    fmt.Println("hello")
}
`

		req := testutils.NewAnalyzeRequestBuilder().
			WithProject("proj-1").
			WithLanguage("go").
			WithRules([]string{"GO001"}).
			WithFile("main.go", code).
			Build()

		report, err := svc.Analyze(context.Background(), req)

		assert.NoError(t, err)
		assert.NotEmpty(t, report.ID)
		assert.Equal(t, "running", report.Status)
	})

	t.Run("should analyze js code with console.log rule", func(t *testing.T) {
		rule := testutils.NewRuleBuilder().
			WithLanguage("js").
			WithKey("JS001").
			WithName("Use console.log").
			Build()
		_, _ = svc.CreateRule(context.Background(), rule)

		code := `function test() {
    console.log("debug message");
    return 42;
}`

		req := testutils.NewAnalyzeRequestBuilder().
			WithProject("proj-js").
			WithLanguage("js").
			WithRules([]string{"JS001"}).
			WithFile("app.js", code).
			Build()

		report, err := svc.Analyze(context.Background(), req)

		assert.NoError(t, err)
		assert.NotEmpty(t, report.ID)
	})
}

func TestQualityScore_Calculation(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should calculate quality score correctly", func(t *testing.T) {
		score1 := svc.calculateQualityScore(0, 0, 0)
		assert.Equal(t, 100.0, score1)

		score2 := svc.calculateQualityScore(1, 0, 0)
		assert.Equal(t, 85.0, score2)

		score3 := svc.calculateQualityScore(0, 1, 0)
		assert.Equal(t, 95.0, score3)

		score4 := svc.calculateQualityScore(0, 0, 1)
		assert.Equal(t, 99.0, score4)

		score5 := svc.calculateQualityScore(10, 10, 10)
		assert.Equal(t, 0.0, score5)
	})
}

func TestEvaluateCondition(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	tests := []struct {
		name     string
		actual   float64
		cond     GateCondition
		expected bool
	}{
		{"GT_pass", 10, GateCondition{Metric: "test", Threshold: 5, Operator: "GT"}, true},
		{"GT_fail", 5, GateCondition{Metric: "test", Threshold: 5, Operator: "GT"}, false},
		{"GTE_pass_equal", 5, GateCondition{Metric: "test", Threshold: 5, Operator: "GTE"}, true},
		{"GTE_pass_higher", 10, GateCondition{Metric: "test", Threshold: 5, Operator: "GTE"}, true},
		{"LT_pass", 5, GateCondition{Metric: "test", Threshold: 10, Operator: "LT"}, true},
		{"LT_fail_equal", 10, GateCondition{Metric: "test", Threshold: 10, Operator: "LT"}, false},
		{"LTE_pass_equal", 10, GateCondition{Metric: "test", Threshold: 10, Operator: "LTE"}, true},
		{"LTE_pass_lower", 5, GateCondition{Metric: "test", Threshold: 10, Operator: "LTE"}, true},
		{"EQ_pass", 5, GateCondition{Metric: "test", Threshold: 5, Operator: "EQ"}, true},
		{"NEQ_pass", 5, GateCondition{Metric: "test", Threshold: 10, Operator: "NEQ"}, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := svc.evaluateCondition(tt.actual, tt.cond)
			assert.Equal(t, tt.expected, result)
		})
	}
}

func TestCheckQualityGate(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should pass quality gate", func(t *testing.T) {
		gate := testutils.NewQualityGateBuilder().
			WithName("Test Gate").
			WithCondition("critical_issues", 0, "LTE").
			WithCondition("quality_score", 70, "GTE").
			Build()

		report := &AnalysisReport{
			CriticalIssues: 0,
			QualityScore:   85,
		}

		result := svc.checkQualityGate(gate, report)

		assert.True(t, result.Passed)
		assert.Len(t, result.Failures, 0)
	})

	t.Run("should fail quality gate", func(t *testing.T) {
		gate := testutils.NewQualityGateBuilder().
			WithName("Strict Gate").
			WithCondition("critical_issues", 0, "LTE").
			WithCondition("quality_score", 90, "GTE").
			Build()

		report := &AnalysisReport{
			CriticalIssues: 2,
			QualityScore:   85,
		}

		result := svc.checkQualityGate(gate, report)

		assert.False(t, result.Passed)
		assert.GreaterOrEqual(t, len(result.Failures), 1)
	})
}

func TestConcurrentRuleOperations(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should handle concurrent rule creations safely", func(t *testing.T) {
		var wg sync.WaitGroup
		var errorCount int64
		successCount := 0

		for i := 0; i < 100; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				rule := testutils.NewRuleBuilder().
					WithLanguage("go").
					WithKey(fmt.Sprintf("CONC%d", idx)).
					WithName(fmt.Sprintf("Concurrent Rule %d", idx)).
					Build()

				_, err := svc.CreateRule(context.Background(), rule)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				}
			}(i)
		}

		wg.Wait()

		rules, err := svc.ListRules(context.Background(), "go")
		successCount = 0
		for _, r := range rules {
			if len(r.Key) > 4 && r.Key[:4] == "CONC" {
				successCount++
			}
		}

		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
		assert.Equal(t, 100, successCount)
	})

	t.Run("should handle concurrent reads and writes safely", func(t *testing.T) {
		var wg sync.WaitGroup
		var errorCount int64
		var readCount int64
		var writeCount int64

		for i := 0; i < 50; i++ {
			wg.Add(2)

			go func(idx int) {
				defer wg.Done()
				rule := testutils.NewRuleBuilder().
					WithLanguage("python").
					WithKey(fmt.Sprintf("RWRULE%d", idx)).
					Build()
				_, err := svc.CreateRule(context.Background(), rule)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				} else {
					atomic.AddInt64(&writeCount, 1)
				}
			}(i)

			go func() {
				defer wg.Done()
				_, err := svc.ListRules(context.Background(), "python")
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				} else {
					atomic.AddInt64(&readCount, 1)
				}
			}()
		}

		wg.Wait()

		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
		assert.Equal(t, int64(50), atomic.LoadInt64(&writeCount))
		assert.Equal(t, int64(50), atomic.LoadInt64(&readCount))
	})
}

func TestConcurrentAnalysis(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should handle concurrent analysis requests", func(t *testing.T) {
		var wg sync.WaitGroup
		var errorCount int64
		var reportCount int64

		for i := 0; i < 30; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				code := fmt.Sprintf(`package main
import "fmt"
var veryLongVariableNameThatExceedsTheLimit = "this is a test code line that should trigger line length warning"
func main() {
    fmt.Println("test %d")
}`, idx)

				req := testutils.NewAnalyzeRequestBuilder().
					WithProject(fmt.Sprintf("proj-conc-%d", idx)).
					WithLanguage("go").
					WithFile("main.go", code).
					Build()

				_, err := svc.Analyze(context.Background(), req)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				} else {
					atomic.AddInt64(&reportCount, 1)
				}
			}(i)
		}

		wg.Wait()

		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
		assert.Equal(t, int64(30), atomic.LoadInt64(&reportCount))
	})

	t.Run("should handle concurrent reads on reports", func(t *testing.T) {
		var wg sync.WaitGroup
		var errorCount int64

		req := testutils.NewAnalyzeRequestBuilder().
			WithProject("read-test").
			WithLanguage("go").
			WithFile("main.go", "package main\n").
			Build()
		report, _ := svc.Analyze(context.Background(), req)

		for i := 0; i < 100; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				_, err := svc.GetReport(context.Background(), report.ID)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				}
			}()
		}

		wg.Wait()
		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
	})
}

func TestConcurrentGateOperations(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should handle concurrent quality gate operations", func(t *testing.T) {
		var wg sync.WaitGroup
		var errorCount int64
		var successCount int64

		for i := 0; i < 20; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				gate := testutils.NewQualityGateBuilder().
					WithName(fmt.Sprintf("Gate %d", idx)).
					WithCondition("critical_issues", 0, "LTE").
					Build()

				_, err := svc.CreateGate(context.Background(), gate)
				if err != nil {
					atomic.AddInt64(&errorCount, 1)
				} else {
					atomic.AddInt64(&successCount, 1)
				}
			}(i)
		}

		wg.Wait()

		gates, _ := svc.ListGates(context.Background())
		actualGates := 0
		for _, g := range gates {
			if len(g.Name) >= 4 && g.Name[:4] == "Gate" {
				actualGates++
			}
		}

		assert.Equal(t, int64(0), atomic.LoadInt64(&errorCount))
		assert.Equal(t, 20, actualGates)
	})
}

func TestAnalyzeFile_RuleDetection(t *testing.T) {
	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should detect line too long in Go", func(t *testing.T) {
		rules := map[string]*AnalysisRule{
			"GO001": {
				Key:     "GO001",
				Enabled: true,
				Config:  map[string]interface{}{"max_length": 50},
			},
		}

		longLine := make([]byte, 60)
		for i := range longLine {
			longLine[i] = 'a'
		}
		code := string(longLine)

		issues := svc.analyzeFile("test.go", code, rules)

		assert.GreaterOrEqual(t, len(issues), 1)
		assert.Equal(t, "GO001", issues[0].RuleKey)
	})

	t.Run("should detect console.log in JS", func(t *testing.T) {
		rules := map[string]*AnalysisRule{
			"JS001": {
				Key:     "JS001",
				Enabled: true,
			},
		}

		code := `function test() {
    console.log("debug");
}`

		issues := svc.analyzeFile("test.js", code, rules)

		assert.GreaterOrEqual(t, len(issues), 1)
		assert.Equal(t, "JS001", issues[0].RuleKey)
	})

	t.Run("should detect var keyword in JS", func(t *testing.T) {
		rules := map[string]*AnalysisRule{
			"JS002": {
				Key:     "JS002",
				Enabled: true,
			},
		}

		code := `var x = 1;`

		issues := svc.analyzeFile("test.js", code, rules)

		assert.GreaterOrEqual(t, len(issues), 1)
		assert.Equal(t, "JS002", issues[0].RuleKey)
	})
}

func TestStressTest_Concurrent(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping stress test in short mode")
	}

	db, err := testutils.NewTestDB()
	require.NoError(t, err)
	defer db.Close()

	svc := NewServiceWithDB(db.DB)

	t.Run("should handle high concurrency without race conditions", func(t *testing.T) {
		var wg sync.WaitGroup
		var totalOps int64 = 0
		start := time.Now()

		for i := 0; i < 10; i++ {
			wg.Add(3)

			go func() {
				defer wg.Done()
				for j := 0; j < 20; j++ {
					rule := testutils.NewRuleBuilder().
						WithLanguage("go").
						WithKey(fmt.Sprintf("STRESS%d", time.Now().UnixNano())).
						Build()
					_, _ = svc.CreateRule(context.Background(), rule)
					atomic.AddInt64(&totalOps, 1)
				}
			}()

			go func() {
				defer wg.Done()
				for j := 0; j < 20; j++ {
					_, _ = svc.ListRules(context.Background(), "go")
					atomic.AddInt64(&totalOps, 1)
				}
			}()

			go func() {
				defer wg.Done()
				for j := 0; j < 10; j++ {
					gate := testutils.NewQualityGateBuilder().
						WithName(fmt.Sprintf("Stress Gate %d", time.Now().UnixNano())).
						Build()
					_, _ = svc.CreateGate(context.Background(), gate)
					atomic.AddInt64(&totalOps, 1)
				}
			}()
		}

		wg.Wait()

		elapsed := time.Since(start)
		assert.GreaterOrEqual(t, atomic.LoadInt64(&totalOps), int64(500))
		assert.Less(t, elapsed, 30*time.Second)
	})
}
