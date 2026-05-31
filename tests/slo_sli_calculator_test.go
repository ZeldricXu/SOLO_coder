package tests

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"session130/internal/slo"
	"session130/pkg/models"
)

func TestNewSLICalculator(t *testing.T) {
	t.Run("create calculator", func(t *testing.T) {
		calc := slo.NewSLICalculator()
		assert.NotNil(t, calc)

		snapshot := calc.GetSnapshot()
		assert.Equal(t, 0, snapshot["sli_configs_count"])
		assert.Equal(t, 0, snapshot["slo_configs_count"])
		assert.Equal(t, 0, snapshot["error_budgets_count"])
		assert.Equal(t, 0, snapshot["active_alerts"])
	})
}

func TestSLICalculator_AddSLIConfig(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("add valid SLI config", func(t *testing.T) {
		config := models.SLIConfig{
			Name:        "Availability SLI",
			Description: "Service availability",
			MetricName:  "availability",
			Goal:        99.9,
		}

		result := calc.AddSLIConfig(config)
		assert.True(t, result)

		retrieved := calc.GetSLIConfig(config.SLIID)
		assert.NotNil(t, retrieved)
		assert.Equal(t, "Availability SLI", retrieved.Name)
		assert.Equal(t, "availability", retrieved.MetricName)
		assert.Equal(t, 99.9, retrieved.Goal)
		assert.False(t, retrieved.CreatedAt.IsZero())
	})

	t.Run("add SLI with existing ID", func(t *testing.T) {
		config1 := models.SLIConfig{
			SLIID:      "existing-id",
			Name:       "First SLI",
			MetricName: "metric1",
			Goal:       99.0,
		}
		calc.AddSLIConfig(config1)

		config2 := models.SLIConfig{
			SLIID:      "existing-id",
			Name:       "Second SLI",
			MetricName: "metric2",
			Goal:       95.0,
		}
		result := calc.AddSLIConfig(config2)
		assert.True(t, result)

		retrieved := calc.GetSLIConfig("existing-id")
		assert.Equal(t, "Second SLI", retrieved.Name)
	})

	t.Run("add SLI with auto-generated ID", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "Auto ID SLI",
			MetricName: "auto_metric",
			Goal:       99.0,
		}

		result := calc.AddSLIConfig(config)
		assert.True(t, result)
		assert.NotEmpty(t, config.SLIID)
	})

	t.Run("add SLI with very long name", func(t *testing.T) {
		longName := make([]byte, 10000)
		for i := range longName {
			longName[i] = 'a'
		}

		config := models.SLIConfig{
			Name:       string(longName),
			MetricName: "long_name_metric",
			Goal:       99.0,
		}

		result := calc.AddSLIConfig(config)
		assert.True(t, result)
	})

	t.Run("add empty name SLI", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "",
			MetricName: "test",
			Goal:       99.0,
		}

		result := calc.AddSLIConfig(config)
		assert.True(t, result)
	})
}

func TestSLICalculator_AddSLOConfig(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("add valid SLO config", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Test SLI",
			MetricName: "test",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		sloConfig := models.SLOConfig{
			Name:        "Availability SLO",
			SLIID:       sli.SLIID,
			Target:      99.9,
			Window:      "30d",
			ErrorBudget: 0.1,
		}

		result := calc.AddSLOConfig(sloConfig)
		assert.True(t, result)

		retrieved := calc.GetSLOConfig(sloConfig.SLOID)
		assert.NotNil(t, retrieved)
		assert.Equal(t, "Availability SLO", retrieved.Name)
		assert.Equal(t, 99.9, retrieved.Target)
		assert.Equal(t, "30d", retrieved.Window)

		budget := calc.GetErrorBudgetState(sloConfig.SLOID)
		assert.NotNil(t, budget)
		assert.Equal(t, 0.1, budget.TotalBudget)
		assert.Equal(t, 0.1, budget.RemainingBudget)
	})

	t.Run("add SLO with auto-generated ID", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Test SLI 2",
			MetricName: "test2",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		sloConfig := models.SLOConfig{
			Name:        "Auto ID SLO",
			SLIID:       sli.SLIID,
			Target:      95.0,
			Window:      "7d",
			ErrorBudget: 0.05,
		}

		result := calc.AddSLOConfig(sloConfig)
		assert.True(t, result)
		assert.NotEmpty(t, sloConfig.SLOID)
	})

	t.Run("add SLO with invalid window", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Test SLI 3",
			MetricName: "test3",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		sloConfig := models.SLOConfig{
			Name:        "Invalid Window SLO",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "invalid",
			ErrorBudget: 0.01,
		}

		result := calc.AddSLOConfig(sloConfig)
		assert.True(t, result)

		budget := calc.GetErrorBudgetState(sloConfig.SLOID)
		assert.NotNil(t, budget)
	})

	t.Run("add multiple SLOs for same SLI", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Shared SLI",
			MetricName: "shared",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		slo1 := models.SLOConfig{
			Name:        "Daily SLO",
			SLIID:       sli.SLIID,
			Target:      99.9,
			Window:      "1d",
			ErrorBudget: 0.001,
		}
		slo2 := models.SLOConfig{
			Name:        "Weekly SLO",
			SLIID:       sli.SLIID,
			Target:      99.95,
			Window:      "7d",
			ErrorBudget: 0.0005,
		}

		assert.True(t, calc.AddSLOConfig(slo1))
		assert.True(t, calc.AddSLOConfig(slo2))

		allConfigs := calc.GetAllSLOConfigs()
		assert.GreaterOrEqual(t, len(allConfigs), 2)
	})
}

func TestSLICalculator_RecordSLI(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("record SLI for existing config", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Record Test SLI",
			MetricName: "record_test",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		metric := calc.RecordSLI(sli.SLIID, 95, 100, nil)
		assert.NotNil(t, metric)
		assert.Equal(t, 0.95, metric.Value)
		assert.Equal(t, int64(95), metric.GoodEvents)
		assert.Equal(t, int64(100), metric.TotalEvents)
		assert.False(t, metric.Timestamp.IsZero())
	})

	t.Run("record SLI for non-existing config", func(t *testing.T) {
		metric := calc.RecordSLI("non-existent-id", 95, 100, nil)
		assert.Nil(t, metric)
	})

	t.Run("record SLI with zero total events", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Zero Test SLI",
			MetricName: "zero_test",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		metric := calc.RecordSLI(sli.SLIID, 0, 0, nil)
		assert.NotNil(t, metric)
		assert.Equal(t, 0.0, metric.Value)
	})

	t.Run("record SLI with negative events", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Negative Test SLI",
			MetricName: "negative_test",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		metric := calc.RecordSLI(sli.SLIID, -5, 100, nil)
		assert.NotNil(t, metric)
	})

	t.Run("record SLI with labels", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Labels Test SLI",
			MetricName: "labels_test",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		labels := map[string]string{
			"service": "api",
			"env":     "prod",
		}

		metric := calc.RecordSLI(sli.SLIID, 99, 100, labels)
		assert.NotNil(t, metric)
		assert.Equal(t, "api", metric.Labels["service"])
		assert.Equal(t, "prod", metric.Labels["env"])
	})

	t.Run("record SLI with very large numbers", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Large Numbers SLI",
			MetricName: "large_test",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		metric := calc.RecordSLI(sli.SLIID, 999999999, 1000000000, nil)
		assert.NotNil(t, metric)
		assert.InDelta(t, 0.999999999, metric.Value, 0.0001)
	})
}

func TestSLICalculator_RecordAvailabilitySLI(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("record success", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Availability SLI",
			MetricName: "availability",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		calc.RecordAvailabilitySLI(sli.SLIID, true, nil)
	})

	t.Run("record failure", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Availability SLI 2",
			MetricName: "availability2",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		calc.RecordAvailabilitySLI(sli.SLIID, false, nil)
	})

	t.Run("record for non-existent SLI", func(t *testing.T) {
		assert.NotPanics(t, func() {
			calc.RecordAvailabilitySLI("non-existent", true, nil)
		})
	})
}

func TestSLICalculator_ErrorBudget(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("error budget consumption", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Budget Test SLI",
			MetricName: "budget_test",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		sloConfig := models.SLOConfig{
			Name:        "Budget Test SLO",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "30d",
			ErrorBudget: 100,
		}
		calc.AddSLOConfig(sloConfig)

		calc.RecordSLI(sli.SLIID, 0, 100, nil)

		time.Sleep(100 * time.Millisecond)

		budget := calc.GetErrorBudgetState(sloConfig.SLOID)
		assert.NotNil(t, budget)
		assert.Less(t, budget.RemainingBudget, 100.0)
		assert.Greater(t, budget.ConsumedBudget, 0.0)
	})

	t.Run("get all error budgets", func(t *testing.T) {
		budgets := calc.GetAllErrorBudgetStates()
		assert.NotNil(t, budgets)
	})

	t.Run("get non-existent budget", func(t *testing.T) {
		budget := calc.GetErrorBudgetState("non-existent")
		assert.Nil(t, budget)
	})
}

func TestSLICalculator_Alerts(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("no active alerts initially", func(t *testing.T) {
		alerts := calc.GetActiveAlerts()
		assert.Empty(t, alerts)
	})

	t.Run("burn rate alert triggers", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Alert Test SLI",
			MetricName: "alert_test",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		sloConfig := models.SLOConfig{
			Name:        "Alert Test SLO",
			SLIID:       sli.SLIID,
			Target:      99.9,
			Window:      "1h",
			ErrorBudget: 0.1,
		}
		calc.AddSLOConfig(sloConfig)

		for i := 0; i < 10; i++ {
			calc.RecordSLI(sli.SLIID, 0, 100, nil)
		}

		time.Sleep(100 * time.Millisecond)

		alerts := calc.GetActiveAlerts()
		assert.NotEmpty(t, alerts)
	})
}

func TestSLICalculator_Concurrent(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("concurrent SLI additions", func(t *testing.T) {
		var wg sync.WaitGroup
		count := 100

		for i := 0; i < count; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				config := models.SLIConfig{
					Name:       "Concurrent SLI",
					MetricName: "concurrent",
					Goal:       99.0,
				}
				calc.AddSLIConfig(config)
			}(i)
		}

		wg.Wait()

		snapshot := calc.GetSnapshot()
		assert.Equal(t, count, snapshot["sli_configs_count"])
	})

	t.Run("concurrent SLI recordings", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Concurrent Record SLI",
			MetricName: "concurrent_record",
			Goal:       99.9,
		}
		calc.AddSLIConfig(sli)

		var wg sync.WaitGroup
		count := int32(0)
		iterations := 1000

		for i := 0; i < iterations; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				metric := calc.RecordSLI(sli.SLIID, 99, 100, nil)
				if metric != nil {
					atomic.AddInt32(&count, 1)
				}
			}()
		}

		wg.Wait()
		assert.Equal(t, int32(iterations), count)
	})

	t.Run("concurrent read and write", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Read Write SLI",
			MetricName: "read_write",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		var wg sync.WaitGroup

		for i := 0; i < 100; i++ {
			wg.Add(3)

			go func() {
				defer wg.Done()
				calc.RecordSLI(sli.SLIID, 99, 100, nil)
			}()

			go func() {
				defer wg.Done()
				_ = calc.GetSLIConfig(sli.SLIID)
			}()

			go func() {
				defer wg.Done()
				_ = calc.GetSnapshot()
			}()
		}

		wg.Wait()
	})
}

func TestSLICalculator_EdgeCases(t *testing.T) {
	calc := slo.NewSLICalculator()

	t.Run("empty SLI name", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "",
			MetricName: "empty_name",
			Goal:       99.0,
		}
		assert.True(t, calc.AddSLIConfig(config))
	})

	t.Run("very large goal value", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "Large Goal",
			MetricName: "large_goal",
			Goal:       1000000.0,
		}
		assert.True(t, calc.AddSLIConfig(config))
	})

	t.Run("negative goal value", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "Negative Goal",
			MetricName: "negative_goal",
			Goal:       -99.0,
		}
		assert.True(t, calc.AddSLIConfig(config))
	})

	t.Run("zero error budget", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Zero Budget SLI",
			MetricName: "zero_budget",
			Goal:       100.0,
		}
		calc.AddSLIConfig(sli)

		sloConfig := models.SLOConfig{
			Name:        "Zero Budget SLO",
			SLIID:       sli.SLIID,
			Target:      100.0,
			Window:      "1d",
			ErrorBudget: 0,
		}
		assert.True(t, calc.AddSLOConfig(sloConfig))
	})

	t.Run("nil labels", func(t *testing.T) {
		sli := models.SLIConfig{
			Name:       "Nil Labels SLI",
			MetricName: "nil_labels",
			Goal:       99.0,
		}
		calc.AddSLIConfig(sli)

		metric := calc.RecordSLI(sli.SLIID, 99, 100, nil)
		assert.NotNil(t, metric)
		assert.Nil(t, metric.Labels)
	})
}

func TestParseWindowDuration(t *testing.T) {
	tests := []struct {
		name     string
		window   string
		expected time.Duration
	}{
		{"1 hour", "1h", time.Hour},
		{"1 day", "1d", 24 * time.Hour},
		{"7 days", "7d", 7 * 24 * time.Hour},
		{"30 days", "30d", 30 * 24 * time.Hour},
		{"invalid defaults to 1d", "invalid", 24 * time.Hour},
		{"empty defaults to 1d", "", 24 * time.Hour},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			calc := slo.NewSLICalculator()
			sli := models.SLIConfig{
				Name:       "Test SLI",
				MetricName: "test",
				Goal:       99.0,
			}
			calc.AddSLIConfig(sli)

			sloConfig := models.SLOConfig{
				Name:        "Test SLO",
				SLIID:       sli.SLIID,
				Target:      99.0,
				Window:      tt.window,
				ErrorBudget: 0.01,
			}
			calc.AddSLOConfig(sloConfig)

			budget := calc.GetErrorBudgetState(sloConfig.SLOID)
			assert.NotNil(t, budget)
			assert.InDelta(t, tt.expected, budget.WindowEnd.Sub(budget.WindowStart), time.Second)
		})
	}
}
