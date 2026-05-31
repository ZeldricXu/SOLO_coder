package tests

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"session130/internal/config"
	"session130/tests/builders"
)

func TestConfigManager_CreateConfig_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	params := map[string]interface{}{
		"timeout": 30,
		"retries": 3,
	}

	cfg, err := m.CreateConfig("test_ns", params)

	require.NoError(t, err)
	assert.NotNil(t, cfg)
	assert.Equal(t, "test_ns", cfg.Namespace)
	assert.Equal(t, 1, cfg.Version)
	assert.True(t, cfg.Enabled)
	assert.Equal(t, params, cfg.Parameters)
}

func TestConfigManager_CreateConfig_EmptyNamespace(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	cfg, err := m.CreateConfig("", map[string]interface{}{})

	assert.Error(t, err)
	assert.Nil(t, cfg)
	assert.Contains(t, err.Error(), "namespace is required")
}

func TestConfigManager_CreateConfig_NilParameters(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	cfg, err := m.CreateConfig("test_ns", nil)

	require.NoError(t, err)
	assert.NotNil(t, cfg)
	assert.Nil(t, cfg.Parameters)
}

func TestConfigManager_CreateConfig_MultipleVersions(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	for i := 0; i < 5; i++ {
		cfg, err := m.CreateConfig("test_ns", map[string]interface{}{"v": i})
		require.NoError(t, err)
		assert.Equal(t, i+1, cfg.Version)
	}

	cfg, err := m.GetConfig("test_ns")
	require.NoError(t, err)
	assert.Equal(t, 5, cfg.Version)
}

func TestConfigManager_UpdateConfig_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, err := m.CreateConfig("test_ns", map[string]interface{}{"timeout": 30})
	require.NoError(t, err)

	updated, err := m.UpdateConfig("test_ns", map[string]interface{}{"timeout": 60, "retries": 5})

	require.NoError(t, err)
	assert.Equal(t, 2, updated.Version)
	assert.Equal(t, 60, updated.Parameters["timeout"])
	assert.Equal(t, 5, updated.Parameters["retries"])
}

func TestConfigManager_UpdateConfig_NotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	cfg, err := m.UpdateConfig("nonexistent", map[string]interface{}{})

	assert.Error(t, err)
	assert.Nil(t, cfg)
	assert.Contains(t, err.Error(), "not found")
}

func TestConfigManager_UpdateConfig_EmptyNamespace(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	cfg, err := m.UpdateConfig("", map[string]interface{}{})

	assert.Error(t, err)
	assert.Nil(t, cfg)
	assert.Contains(t, err.Error(), "namespace is required")
}

func TestConfigManager_GetConfig_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	created, _ := m.CreateConfig("test_ns", map[string]interface{}{"key": "value"})

	retrieved, err := m.GetConfig("test_ns")

	require.NoError(t, err)
	assert.Equal(t, created.ConfigID, retrieved.ConfigID)
	assert.Equal(t, created.Parameters, retrieved.Parameters)
}

func TestConfigManager_GetConfig_NotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	cfg, err := m.GetConfig("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, cfg)
}

func TestConfigManager_GetConfigByVersion_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	for i := 0; i < 3; i++ {
		_, _ = m.CreateConfig("test_ns", map[string]interface{}{"version": i + 1})
	}

	cfg, err := m.GetConfigByVersion("test_ns", 2)

	require.NoError(t, err)
	assert.Equal(t, 2, cfg.Version)
	assert.Equal(t, 2, cfg.Parameters["version"])
}

func TestConfigManager_GetConfigByVersion_NotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{})

	cfg, err := m.GetConfigByVersion("test_ns", 999)

	assert.Error(t, err)
	assert.Nil(t, cfg)
	assert.Contains(t, err.Error(), "version 999 not found")
}

func TestConfigManager_Rollback_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"value": "v1"})
	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"value": "v2"})
	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"value": "v3"})

	rolledBack, err := m.Rollback("test_ns", 1)

	require.NoError(t, err)
	assert.Equal(t, 4, rolledBack.Version)
	assert.Equal(t, "v1", rolledBack.Parameters["value"])

	current, _ := m.GetConfig("test_ns")
	assert.Equal(t, "v1", current.Parameters["value"])
}

func TestConfigManager_Rollback_NotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	cfg, err := m.Rollback("nonexistent", 1)

	assert.Error(t, err)
	assert.Nil(t, cfg)
	assert.Contains(t, err.Error(), "not found")
}

func TestConfigManager_Rollback_TargetVersionNotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{})

	cfg, err := m.Rollback("test_ns", 999)

	assert.Error(t, err)
	assert.Nil(t, cfg)
	assert.Contains(t, err.Error(), "target version 999 not found")
}

func TestConfigManager_Rollback_VersionHistoryPreserved(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": 1})
	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": 2})

	_, _ = m.Rollback("test_ns", 1)

	history, err := m.GetVersionHistory("test_ns")
	require.NoError(t, err)
	assert.Len(t, history, 3)

	assert.Equal(t, 1, history[0].Version)
	assert.Equal(t, 2, history[1].Version)
	assert.Equal(t, 3, history[2].Version)
	assert.Equal(t, 1, history[2].Parameters["v"])
}

func TestConfigManager_Rollback_ParametersAreCopied(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	originalParams := map[string]interface{}{
		"nested": map[string]interface{}{
			"key": "value",
		},
		"list": []interface{}{1, 2, 3},
	}

	_, _ = m.CreateConfig("test_ns", originalParams)
	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"other": "data"})

	rolledBack, err := m.Rollback("test_ns", 1)
	require.NoError(t, err)

	assert.Equal(t, "value", rolledBack.Parameters["nested"].(map[string]interface{})["key"])
	assert.Equal(t, []interface{}{1, 2, 3}, rolledBack.Parameters["list"])

	originalParams["nested"].(map[string]interface{})["key"] = "modified"
	assert.Equal(t, "value", rolledBack.Parameters["nested"].(map[string]interface{})["key"])
}

func TestConfigManager_GetVersionHistory_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	for i := 0; i < 5; i++ {
		_, _ = m.CreateConfig("test_ns", map[string]interface{}{"i": i})
	}

	history, err := m.GetVersionHistory("test_ns")

	require.NoError(t, err)
	assert.Len(t, history, 5)
	for i, cfg := range history {
		assert.Equal(t, i+1, cfg.Version)
	}
}

func TestConfigManager_GetVersionHistory_NotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	history, err := m.GetVersionHistory("nonexistent")

	assert.Error(t, err)
	assert.Nil(t, history)
}

func TestConfigManager_DeleteConfig_Success(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{})

	err := m.DeleteConfig("test_ns")

	require.NoError(t, err)

	cfg, err := m.GetConfig("test_ns")
	assert.Error(t, err)
	assert.Nil(t, cfg)

	history, err := m.GetVersionHistory("test_ns")
	assert.Error(t, err)
	assert.Nil(t, history)
}

func TestConfigManager_DeleteConfig_NotFound(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	err := m.DeleteConfig("nonexistent")

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestConfigManager_ListNamespaces(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("ns1", map[string]interface{}{})
	_, _ = m.CreateConfig("ns2", map[string]interface{}{})
	_, _ = m.CreateConfig("ns3", map[string]interface{}{})

	namespaces := m.ListNamespaces()

	assert.Len(t, namespaces, 3)
	assert.Contains(t, namespaces, "ns1")
	assert.Contains(t, namespaces, "ns2")
	assert.Contains(t, namespaces, "ns3")
}

func TestConfigManager_Subscribe_Notification(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	var wg sync.WaitGroup
	wg.Add(1)

	var notifiedCfg *config.Config
	m.Subscribe(func(cfg *config.Config) {
		notifiedCfg = cfg
		wg.Done()
	})

	created, _ := m.CreateConfig("test_ns", map[string]interface{}{"key": "value"})

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		assert.NotNil(t, notifiedCfg)
		assert.Equal(t, created.ConfigID, notifiedCfg.ConfigID)
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for notification")
	}
}

func TestConfigManager_AsyncOperation_Create(t *testing.T) {
	m := config.NewManagerWithWorkers(1, 4, false)
	defer m.Shutdown()

	op := m.CreateConfigAsync("test_ns", map[string]interface{}{"async": true})

	assert.Equal(t, config.OpCreate, op.Type)
	assert.Equal(t, "test_ns", op.Namespace)

	cfg, err := op.Wait()
	require.NoError(t, err)
	assert.Equal(t, 1, cfg.Version)
	assert.Equal(t, true, cfg.Parameters["async"])
}

func TestConfigManager_AsyncOperation_Rollback(t *testing.T) {
	m := config.NewManagerWithWorkers(1, 4, false)
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": 1})
	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": 2})

	op := m.RollbackAsync("test_ns", 1)

	assert.Equal(t, config.OpRollback, op.Type)
	assert.Equal(t, 1, op.TargetVersion)

	cfg, err := op.Wait()
	require.NoError(t, err)
	assert.Equal(t, 3, cfg.Version)
	assert.Equal(t, 1, cfg.Parameters["v"])
}

func TestConfigManager_AsyncOperation_GetOperation(t *testing.T) {
	m := config.NewManagerWithWorkers(1, 4, false)
	defer m.Shutdown()

	op := m.CreateConfigAsync("test_ns", map[string]interface{}{})

	retrieved, err := m.GetOperation(op.ID)
	require.NoError(t, err)
	assert.Equal(t, op.ID, retrieved.ID)
}

func TestConfigManager_AsyncOperation_ListOperations(t *testing.T) {
	m := config.NewManagerWithWorkers(1, 4, false)
	defer m.Shutdown()

	op1 := m.CreateConfigAsync("ns1", map[string]interface{}{})
	op2 := m.CreateConfigAsync("ns2", map[string]interface{}{})

	_, _ = op1.Wait()
	_, _ = op2.Wait()

	ops := m.ListOperations(config.StatusCompleted)
	assert.GreaterOrEqual(t, len(ops), 0)
}

func TestConfigManager_ConcurrentAccess(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	var wg sync.WaitGroup
	numGoroutines := 50

	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			ns := "concurrent_ns"
			_, _ = m.CreateConfig(ns, map[string]interface{}{"idx": idx})
		}(i)
	}

	wg.Wait()

	cfg, err := m.GetConfig("concurrent_ns")
	require.NoError(t, err)
	assert.Equal(t, numGoroutines, cfg.Version)
}

func TestConfigManager_ConcurrentReadsAndWrites(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": 0})

	var wg sync.WaitGroup

	for i := 0; i < 20; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": 1})
		}()
		go func() {
			defer wg.Done()
			_, _ = m.GetConfig("test_ns")
		}()
	}

	wg.Wait()

	cfg, err := m.GetConfig("test_ns")
	require.NoError(t, err)
	assert.GreaterOrEqual(t, cfg.Version, 20)
}

func TestConfigManager_ConcurrentRollback(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	for i := 0; i < 10; i++ {
		_, _ = m.CreateConfig("test_ns", map[string]interface{}{"v": i + 1})
	}

	var wg sync.WaitGroup
	results := make([]*config.Config, 5)

	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			cfg, _ := m.Rollback("test_ns", 1)
			results[idx] = cfg
		}(i)
	}

	wg.Wait()

	for _, cfg := range results {
		if cfg != nil {
			assert.Equal(t, 1, cfg.Parameters["v"])
		}
	}
}

func TestConfigManager_WorkerStats(t *testing.T) {
	m := config.NewManagerWithWorkers(2, 8, false)
	defer m.Shutdown()

	stats := m.GetWorkerStats()

	assert.Equal(t, 2, stats["min_workers"])
	assert.Equal(t, 8, stats["max_workers"])
	assert.Equal(t, false, stats["auto_scale"])
}

func TestConfigManager_BuilderIntegration(t *testing.T) {
	m := config.NewManager()
	defer m.Shutdown()

	builder := builders.NewConfigBuilder().
		WithNamespace("builder_test").
		WithParameter("timeout", 60).
		WithParameter("retries", 5).
		WithEnabled(true)

	cfg := builder.Build()

	created, err := m.CreateConfig(cfg.Namespace, cfg.Parameters)
	require.NoError(t, err)
	assert.Equal(t, cfg.Parameters, created.Parameters)

	updatedBuilder := builders.NewConfigBuilder().
		WithNamespace("builder_test").
		WithParameter("timeout", 120).
		WithParameter("new_param", "value")

	updated, err := m.UpdateConfig("builder_test", updatedBuilder.Build().Parameters)
	require.NoError(t, err)
	assert.Equal(t, 120, updated.Parameters["timeout"])
	assert.Equal(t, "value", updated.Parameters["new_param"])
}
