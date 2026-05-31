package tests

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"session130/internal/slo"
	"session130/pkg/models"
)

func TestNewSLOManager(t *testing.T) {
	t.Run("create manager", func(t *testing.T) {
		m := slo.NewSLOManager()
		assert.NotNil(t, m)
		assert.NotNil(t, m.GetCalculator())
		assert.NotNil(t, m.GetRouter())
	})

		m2 := slo.GetManager()
		assert.NotNil(t, m2)
	})
}

func TestSLOManager_CreateSLI(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("create valid SLI", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "Test SLI",
			MetricName: "test_metric",
			Goal:       99.9,
		}

		result, err := m.CreateSLI(ctx, config)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.NotEmpty(t, result.SLIID)
		assert.Equal(t, "Test SLI", result.Name)
	})

	t.Run("create SLI with empty name", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "",
			MetricName: "empty_name",
			Goal:       99.0,
		}

		result, err := m.CreateSLI(ctx, config)
		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, slo.ErrSLINameRequired, err)
	})

	t.Run("create SLI with very long name", func(t *testing.T) {
		longName := make([]byte, 10000)
		for i := range longName {
			longName[i] = 'a'
		}

		config := models.SLIConfig{
			Name:       string(longName),
			MetricName: "long_name",
			Goal:       99.0,
		}

		result, err := m.CreateSLI(ctx, config)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("create SLI with existing ID", func(t *testing.T) {
		config := models.SLIConfig{
			SLIID:      "custom-sli-id",
			Name:       "Custom ID SLI",
			MetricName: "custom_id",
			Goal:       99.0,
		}

		result, err := m.CreateSLI(ctx, config)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, "custom-sli-id", result.SLIID)
	})

	t.Run("concurrent SLI creation", func(t *testing.T) {
		m2 := slo.NewSLOManager()
		var wg sync.WaitGroup
		successCount := int32(0)
		iterations := 100

		for i := 0; i < iterations; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				config := models.SLIConfig{
					Name:       "Concurrent SLI",
					MetricName: "concurrent",
					Goal:       99.0,
				}
				_, err := m2.CreateSLI(ctx, config)
				if err == nil {
					atomic.AddInt32(&successCount, 1)
				}
			}(i)
		}

		wg.Wait()
		assert.Equal(t, int32(iterations), successCount)
	})
}

func TestSLOManager_CreateSLO(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("create valid SLO", func(t *testing.T) {
		sliConfig := models.SLIConfig{
			Name:       "SLO Test SLI",
			MetricName: "slo_test",
			Goal:       99.9,
		}
		sli, err := m.CreateSLI(ctx, sliConfig)
		require.NoError(t, err)

		sloConfig := models.SLOConfig{
			Name:        "Test SLO",
			SLIID:       sli.SLIID,
			Target:      99.9,
			Window:      "30d",
			ErrorBudget: 0.001,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.NotEmpty(t, result.SLOID)
		assert.Equal(t, "Test SLO", result.Name)
	})

	t.Run("create SLO with empty name", func(t *testing.T) {
		sliConfig := models.SLIConfig{
			Name:       "Empty Name SLI",
			MetricName: "empty_name_sli",
			Goal:       99.0,
		}
		sli, _ := m.CreateSLI(ctx, sliConfig)

		sloConfig := models.SLOConfig{
			Name:        "",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "7d",
			ErrorBudget: 0.01,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, slo.ErrSLONameRequired, err)
	})

	t.Run("create SLO with empty SLIID", func(t *testing.T) {
		sloConfig := models.SLOConfig{
			Name:        "Empty SLIID SLO",
			SLIID:       "",
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: 0.01,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.Error(t, err)
		assert.Nil(t, result)
		assert.Equal(t, slo.ErrSLIIDRequired, err)
	})

	t.Run("create SLO with non-existent SLIID", func(t *testing.T) {
		sloConfig := models.SLOConfig{
			Name:        "Non-existent SLI SLO",
			SLIID:       "non-existent-sli-id",
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: 0.01,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("create SLO with unicode characters", func(t *testing.T) {
		sliConfig := models.SLIConfig{
			Name:       "Unicode SLI",
			MetricName: "unicode",
			Goal:       99.0,
		}
		sli, _ := m.CreateSLI(ctx, sliConfig)

		sloConfig := models.SLOConfig{
			Name:        "SLO with 中文和🌍",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: 0.01,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("create SLO with very large error budget", func(t *testing.T) {
		sliConfig := models.SLIConfig{
			Name:       "Large Budget SLI",
			MetricName: "large_budget",
			Goal:       99.0,
		}
		sli, _ := m.CreateSLI(ctx, sliConfig)

		sloConfig := models.SLOConfig{
			Name:        "Large Budget SLO",
			SLIID:       sli.SLIID,
			Target:      50.0,
			Window:      "1d",
			ErrorBudget: 1000000.0,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})
}

func TestSLOManager_GetSLO(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("get existing SLO", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Get Test SLI",
			MetricName: "get_test",
			Goal:       99.0,
		})

		sloConfig, _ := m.CreateSLO(ctx, models.SLOConfig{
			Name:        "Get Test SLO",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: 0.01,
		})

		result, err := m.GetSLO(ctx, sloConfig.SLOID)
		assert.NoError(t, err)
		assert.NotNil(t, result)
		assert.Equal(t, sloConfig.SLOID, result.SLOID)
	})

	t.Run("get non-existent SLO", func(t *testing.T) {
		result, err := m.GetSLO(ctx, "non-existent-id")
		assert.Error(t, err)
		assert.Nil(t, result)
	})

	t.Run("get SLO with empty ID", func(t *testing.T) {
		result, err := m.GetSLO(ctx, "")
		assert.Error(t, err)
		assert.Nil(t, result)
	})
}

func TestSLOManager_GetAllErrorBudgets(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("get all error budgets", func(t *testing.T) {
		budgets, err := m.GetAllErrorBudgets(ctx)
		assert.NoError(t, err)
		assert.NotNil(t, budgets)
		assert.Empty(t, budgets)

		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Budget SLI",
			MetricName: "budget",
			Goal:       99.0,
		})

		_, _ = m.CreateSLO(ctx, models.SLOConfig{
			Name:        "Budget SLO",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: 0.01,
		})

		budgets, err = m.GetAllErrorBudgets(ctx)
		assert.NoError(t, err)
		assert.NotNil(t, budgets)
		assert.Len(t, budgets, 1)
	})
}

func TestSLOManager_RecordSuccessAndFailure(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("record success", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Record SLI",
			MetricName: "record",
			Goal:       99.0,
		})

		assert.NotPanics(t, func() {
			m.RecordSuccess(ctx, sli.SLIID, nil)
		})
	})

	t.Run("record failure", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Failure SLI",
			MetricName: "failure",
			Goal:       99.0,
		})

		assert.NotPanics(t, func() {
			m.RecordFailure(ctx, sli.SLIID, nil)
		})
	})

	t.Run("record for non-existent SLI", func(t *testing.T) {
		assert.NotPanics(t, func() {
			m.RecordSuccess(ctx, "non-existent", nil)
			m.RecordFailure(ctx, "non-existent", nil)
		})
	})

	t.Run("record with labels", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Labels SLI",
			MetricName: "labels_record",
			Goal:       99.0,
		})

		labels := map[string]string{
			"service": "api",
			"env":     "prod",
		}

		assert.NotPanics(t, func() {
			m.RecordSuccess(ctx, sli.SLIID, labels)
			m.RecordFailure(ctx, sli.SLIID, labels)
		})
	})
}

func TestSLOManager_ReplicaManagement(t *testing.T) {
	m := slo.NewSLOManager()

	t.Run("add and get replicas", func(t *testing.T) {
		replica := slo.ReplicaInfo{
			ID:       "replica-1",
			Address:  "localhost:8081",
			IsLeader: false,
			IsWriter: true,
		}

		m.AddReplica(replica)

		replicas := m.GetAllReplicas()
		assert.NotNil(t, replicas)
		assert.Len(t, replicas, 1)
		assert.Equal(t, "replica-1", replicas[0].ID)
	})

	t.Run("remove replica", func(t *testing.T) {
		m2 := slo.NewSLOManager()

		m2.AddReplica(slo.ReplicaInfo{ID: "replica-1", Address: "localhost:8081"})

		result := m2.RemoveReplica("replica-1")
		assert.True(t, result)

		replicas := m2.GetAllReplicas()
		assert.Empty(t, replicas)
	})

	t.Run("remove non-existent replica", func(t *testing.T) {
		m2 := slo.NewSLOManager()
		result := m2.RemoveReplica("non-existent")
		assert.False(t, result)
	})

	t.Run("trigger failover", func(t *testing.T) {
		m2 := slo.NewSLOManager()
		m2.AddReplica(slo.ReplicaInfo{
			ID: "replica-1", Address: "localhost:8081", IsWriter: true})
		m2.AddReplica(slo.ReplicaInfo{
			ID: "replica-2", Address: "localhost:8082", IsWriter: false})

		newLeader, err := m2.TriggerFailover()
		assert.NoError(t, err)
		assert.NotEmpty(t, newLeader)
	})

	t.Run("failover with no replicas", func(t *testing.T) {
		m2 := slo.NewSLOManager()
		newLeader, err := m2.TriggerFailover()
		assert.Error(t, err)
		assert.Empty(t, newLeader)
	})
}

func TestSLOManager_Concurrent(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("concurrent create and get", func(t *testing.T) {
		var wg sync.WaitGroup
		sliIDs := make(chan string, 100)

		for i := 0; i < 50; i++ {
			wg.Add(2)

			go func(idx int) {
				defer wg.Done()
				config := models.SLIConfig{
					Name:       "Concurrent Create SLI",
					MetricName: "concurrent_create",
					Goal:       99.0,
				}
				sli, err := m.CreateSLI(ctx, config)
				if err == nil {
					sliIDs <- sli.SLIID
				}
			}(i)

			go func() {
				defer wg.Done()
				_ = m.GetAllErrorBudgets(ctx)
			}()
		}

		wg.Wait()
		close(sliIDs)
	})

	t.Run("concurrent record operations", func(t *testing.T) {
		m2 := slo.NewSLOManager()
		sli, _ := m2.CreateSLI(ctx, models.SLIConfig{
			Name:       "Concurrent Record SLI",
			MetricName: "concurrent_record",
			Goal:       99.9,
		})

		var wg sync.WaitGroup
		iterations := 1000

		for i := 0; i < iterations; i++ {
			wg.Add(1)
			go func(success bool) {
				defer wg.Done()
				if success {
					m2.RecordSuccess(ctx, sli.SLIID, nil)
				} else {
					m2.RecordFailure(ctx, sli.SLIID, nil)
				}
			}(i%2 == 0)
		}

		wg.Wait()
	})

	t.Run("concurrent replica operations", func(t *testing.T) {
		m2 := slo.NewSLOManager()
		var wg sync.WaitGroup

		for i := 0; i < 100; i++ {
			wg.Add(3)

			go func(idx int) {
				defer wg.Done()
				m2.AddReplica(slo.ReplicaInfo{
					ID: "replica", Address: "localhost:808" + string(rune(idx)),
				})
			}(i)

			go func() {
				defer wg.Done()
				_ = m2.GetAllReplicas()
			}()

			go func(idx int) {
				defer wg.Done()
				_ = m2.GetRouterStats()
			}(i)
		}

		wg.Wait()
	})
}

func TestSLOManager_EdgeCases(t *testing.T) {
	m := slo.NewSLOManager()
	ctx := context.Background()

	t.Run("create SLI with special characters", func(t *testing.T) {
		config := models.SLIConfig{
			Name:       "SLI with !@#$%^&*()",
			MetricName: "special_chars",
			Goal:       99.0,
		}

		result, err := m.CreateSLI(ctx, config)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("create SLO with zero target", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Zero Target SLI",
			MetricName: "zero_target",
			Goal:       0.0,
		})

		sloConfig := models.SLOConfig{
			Name:        "Zero Target SLO",
			SLIID:       sli.SLIID,
			Target:      0.0,
			Window:      "1d",
			ErrorBudget: 1.0,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("create SLO with 100 percent target", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "100% SLI",
			MetricName: "100_percent",
			Goal:       100.0,
		})

		sloConfig := models.SLOConfig{
			Name:        "100% SLO",
			SLIID:       sli.SLIID,
			Target:      100.0,
			Window:      "1d",
			ErrorBudget: 0.0,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("record with nil labels", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Nil Labels SLI",
			MetricName: "nil_labels_sli",
			Goal:       99.0,
		})

		assert.NotPanics(t, func() {
			m.RecordSuccess(ctx, sli.SLIID, nil)
		})
	})

	t.Run("create SLO with negative error budget", func(t *testing.T) {
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Negative Budget SLI",
			MetricName: "negative_budget",
			Goal:       99.0,
		})

		sloConfig := models.SLOConfig{
			Name:        "Negative Budget SLO",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: -0.01,
		}

		result, err := m.CreateSLO(ctx, sloConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})
}

func TestSLOManager_RouterStats(t *testing.T) {
	m := slo.NewSLOManager()

	t.Run("get router stats", func(t *testing.T) {
		stats := m.GetRouterStats()
		assert.NotNil(t, stats)
	})

	t.Run("stats after operations", func(t *testing.T) {
		ctx := context.Background()
		sli, _ := m.CreateSLI(ctx, models.SLIConfig{
			Name:       "Stats SLI",
			MetricName: "stats",
			Goal:       99.0,
		})

		_, _ = m.CreateSLO(ctx, models.SLOConfig{
			Name:        "Stats SLO",
			SLIID:       sli.SLIID,
			Target:      99.0,
			Window:      "1d",
			ErrorBudget: 0.01,
		})

		stats := m.GetRouterStats()
		assert.NotNil(t, stats)
	})
}

func TestSLOManager_ContextCancellation(t *testing.T) {
	m := slo.NewSLOManager()

	t.Run("cancelled context", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()

		sliConfig := models.SLIConfig{
			Name:       "Cancelled Context SLI",
			MetricName: "cancelled",
			Goal:       99.0,
		}

		result, err := m.CreateSLI(ctx, sliConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})

	t.Run("context with deadline", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()

		sliConfig := models.SLIConfig{
			Name:       "Deadline SLI",
			MetricName: "deadline",
			Goal:       99.0,
		}

		result, err := m.CreateSLI(ctx, sliConfig)
		assert.NoError(t, err)
		assert.NotNil(t, result)
	})
}
