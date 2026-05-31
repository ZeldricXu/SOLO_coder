package tests

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap/zaptest"

	"session133/internal/model"
)

func TestDynamicConfigManager_SetScenario(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	initialScenario := mgr.GetCurrentScenario()
	assert.Equal(t, model.ScenarioDevelopment, initialScenario)

	mgr.SetScenario(model.ScenarioProduction)
	assert.Equal(t, model.ScenarioProduction, mgr.GetCurrentScenario())

	mgr.SetScenario(model.ScenarioStaging)
	assert.Equal(t, model.ScenarioStaging, mgr.GetCurrentScenario())

	mgr.SetScenario("unknown_scenario")
	assert.Equal(t, model.ScenarioDevelopment, mgr.GetCurrentScenario())
}

func TestDynamicConfigManager_GetConfig(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	mgr.SetScenario(model.ScenarioDevelopment)
	devConfig := mgr.GetConfig()
	assert.Equal(t, 100, devConfig.MaxVersionsPerModel)
	assert.Equal(t, 128, devConfig.MaxNameLength)
	assert.False(t, devConfig.DescriptionRequired)

	mgr.SetScenario(model.ScenarioProduction)
	prodConfig := mgr.GetConfig()
	assert.Equal(t, 20, prodConfig.MaxVersionsPerModel)
	assert.Equal(t, 64, prodConfig.MaxNameLength)
	assert.True(t, prodConfig.DescriptionRequired)

	mgr.SetScenario(model.ScenarioStaging)
	stagingConfig := mgr.GetConfig()
	assert.Equal(t, 50, stagingConfig.MaxVersionsPerModel)
	assert.Equal(t, 128, stagingConfig.MaxNameLength)
	assert.True(t, stagingConfig.DescriptionRequired)
}

func TestDynamicConfigManager_ValidateModelCreation(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	mgr.SetScenario(model.ScenarioDevelopment)
	err := mgr.ValidateModelCreation("test-model", "")
	assert.NoError(t, err)

	err = mgr.ValidateModelCreation("", "")
	assert.Error(t, err)

	longName := string(make([]byte, 200))
	err = mgr.ValidateModelCreation(longName, "")
	assert.Error(t, err)

	mgr.SetScenario(model.ScenarioProduction)
	err = mgr.ValidateModelCreation("test-model", "")
	assert.Error(t, err)

	err = mgr.ValidateModelCreation("test-model", "valid description")
	assert.NoError(t, err)
}

func TestDynamicConfigManager_CanTransitionStage(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	mgr.SetScenario(model.ScenarioDevelopment)

	ok, reason := mgr.CanTransitionStage(model.StageDevelopment, model.StageStaging, 1, []string{})
	assert.False(t, ok)
	assert.Contains(t, reason, "lint")
	assert.Contains(t, reason, "unit_test")

	ok, reason = mgr.CanTransitionStage(model.StageDevelopment, model.StageStaging, 1, []string{"lint", "unit_test"})
	assert.True(t, ok)
	assert.Empty(t, reason)

	ok, reason = mgr.CanTransitionStage(model.StageStaging, model.StageProduction, 2, []string{"lint", "unit_test", "integration_test"})
	assert.False(t, ok)
	assert.Contains(t, reason, "至少需要3个版本")

	ok, reason = mgr.CanTransitionStage(model.StageStaging, model.StageProduction, 3, []string{"lint", "unit_test", "integration_test", "stress_test"})
	assert.True(t, ok)
	assert.Empty(t, reason)
}

func TestDynamicConfigManager_UpdateConfig(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	config := mgr.GetConfig()
	assert.Equal(t, 100, config.MaxVersionsPerModel)

	config.MaxVersionsPerModel = 50
	config.MaxNameLength = 64
	err := mgr.UpdateConfig(model.ScenarioDevelopment, config)
	require.NoError(t, err)

	updatedConfig := mgr.GetConfig()
	assert.Equal(t, 50, updatedConfig.MaxVersionsPerModel)
	assert.Equal(t, 64, updatedConfig.MaxNameLength)
}

func TestDynamicConfigManager_PatchConfig(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	mgr.SetScenario(model.ScenarioProduction)
	originalConfig := mgr.GetConfig()
	assert.Equal(t, 20, originalConfig.MaxVersionsPerModel)

	patches := map[string]interface{}{
		"max_versions_per_model": 15,
		"auto_archive_days":      3,
	}

	err := mgr.PatchConfig(model.ScenarioProduction, patches)
	require.NoError(t, err)

	updatedConfig := mgr.GetConfig()
	assert.Equal(t, 15, updatedConfig.MaxVersionsPerModel)
	assert.Equal(t, 3, updatedConfig.AutoArchiveDays)
	assert.Equal(t, 64, updatedConfig.MaxNameLength)
}

func TestDynamicConfigManager_ConfigChangeListener(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	listener := &testConfigListener{}
	mgr.AddListener("test_listener", listener)

	mgr.SetScenario(model.ScenarioProduction)

	time.Sleep(10 * time.Millisecond)

	assert.True(t, listener.called)
	assert.Equal(t, model.ScenarioDevelopment, listener.oldScenario)
	assert.Equal(t, model.ScenarioProduction, listener.newScenario)
}

func TestDynamicConfigManager_AutoArchive(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	archived := false
	archiveFn := func(ageDays int) error {
		archived = true
		return nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	mgr.StartAutoArchive(ctx, archiveFn)

	cancel()
	time.Sleep(100 * time.Millisecond)
}

func TestDynamicConfigManager_SimulateRandomScenario(t *testing.T) {
	logger := zaptest.NewLogger(t)
	mgr := model.NewDynamicConfigManager(logger)

	scenarios := make(map[model.ScenarioType]bool)
	for i := 0; i < 100; i++ {
		scenario := mgr.SimulateRandomScenario()
		scenarios[scenario] = true
	}

	assert.True(t, scenarios[model.ScenarioDevelopment])
	assert.True(t, scenarios[model.ScenarioStaging])
	assert.True(t, scenarios[model.ScenarioProduction])
}

type testConfigListener struct {
	called      bool
	oldScenario model.ScenarioType
	newScenario model.ScenarioType
}

func (l *testConfigListener) OnConfigChanged(scenario model.ScenarioType, oldConfig, newConfig model.ModelConfig) {
	l.called = true
	l.oldScenario = scenario
	l.newScenario = scenario
}
