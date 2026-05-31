package config

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/edgeplatform/session306/internal/mocks"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/internal/testfactory"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

type configTestFixture struct {
	cm         *ConfigManager
	configRepo *mocks.MockConfigRepository
	eventBus   *mocks.MockEventBus
	logger     *zap.Logger
	factory    *testfactory.TestDataFactory
	ctx        context.Context
	cancel     context.CancelFunc
}

func setupConfigTest(t *testing.T) *configTestFixture {
	t.Helper()

	logger, _ := zap.NewDevelopment()
	configRepo := mocks.NewMockConfigRepository()
	eventBus := mocks.NewMockEventBus()
	mockDA := mocks.NewMockDataAccess()
	factory := testfactory.NewTestDataFactory()

	ctx, cancel := context.WithCancel(context.Background())

	cm := NewConfigManager(mockDA.DataAccess, configRepo, eventBus, logger)

	eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)
	eventBus.On("Subscribe", mock.Anything, mock.Anything).Return("sub_test")
	eventBus.On("Unsubscribe", mock.Anything).Return()
	eventBus.On("Close").Return()

	return &configTestFixture{
		cm:         cm,
		configRepo: configRepo,
		eventBus:   eventBus,
		logger:     logger,
		factory:    factory,
		ctx:        ctx,
		cancel:     cancel,
	}
}

func (f *configTestFixture) teardown() {
	f.cancel()
}

func TestNewConfigManager(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	assert.NotNil(t, f.cm)
	assert.NotNil(t, f.cm.validate)
	assert.NotNil(t, f.cm.schemas)
	assert.NotNil(t, f.cm.cache)

	assert.Contains(t, f.cm.schemas, "system")
	assert.Contains(t, f.cm.schemas, "gateway")
	assert.Contains(t, f.cm.schemas, "inference")
	assert.Contains(t, f.cm.schemas, "ota")
}

func TestRegisterSchema_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := &NamespaceSchema{
		Namespace: "custom",
		Parameters: []ParameterSchema{
			{Name: "custom_param", Type: "string", Required: true, Description: "Custom parameter"},
		},
	}

	err := f.cm.RegisterSchema(f.ctx, schema)
	require.NoError(t, err)

	assert.Contains(t, f.cm.schemas, "custom")
}

func TestRegisterSchema_Invalid(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := &NamespaceSchema{
		Namespace: "",
		Parameters: []ParameterSchema{
			{Name: "", Type: "invalid_type"},
		},
	}

	err := f.cm.RegisterSchema(f.ctx, schema)
	assert.Error(t, err)
}

func TestValidateConfig_ValidSystemConfig(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateValidSystemConfig()
	result, err := f.cm.ValidateConfig(f.ctx, "system", params)

	require.NoError(t, err)
	assert.True(t, result.Valid)
	assert.Empty(t, result.Details)
}

func TestValidateConfig_InvalidSystemConfig(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateInvalidSystemConfig()
	result, err := f.cm.ValidateConfig(f.ctx, "system", params)

	require.NoError(t, err)
	assert.False(t, result.Valid)
	assert.NotEmpty(t, result.Details)
}

func TestValidateConfig_UnknownNamespace(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{"key": "value"}
	result, err := f.cm.ValidateConfig(f.ctx, "unknown_namespace", params)

	require.NoError(t, err)
	assert.True(t, result.Valid)
}

func TestValidateConfig_TypeMismatch(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{
		"timeout": "not_a_number",
	}
	result, err := f.cm.ValidateConfig(f.ctx, "system", params)

	require.NoError(t, err)
	assert.False(t, result.Valid)
	assert.NotEmpty(t, result.Details)
}

func TestValidateConfig_OutOfRange(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{
		"timeout": 10000,
	}
	result, err := f.cm.ValidateConfig(f.ctx, "system", params)

	require.NoError(t, err)
	assert.False(t, result.Valid)
	assert.NotEmpty(t, result.Details)
}

func TestValidateConfig_InvalidEnumValue(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{
		"log_level": "super_debug",
	}
	result, err := f.cm.ValidateConfig(f.ctx, "system", params)

	require.NoError(t, err)
	assert.False(t, result.Valid)
	assert.NotEmpty(t, result.Details)
}

func TestApplyDefaults(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{
		"timeout": 60,
	}

	result := f.cm.ApplyDefaults(f.ctx, "system", params)

	assert.Equal(t, 60, result["timeout"])
	assert.Equal(t, 3, result["retries"])
	assert.Equal(t, "info", result["log_level"])
	assert.Equal(t, 100, result["max_concurrent"])
	assert.Equal(t, true, result["enabled"])
}

func TestApplyDefaults_UnknownNamespace(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{"key": "value"}
	result := f.cm.ApplyDefaults(f.ctx, "unknown", params)

	assert.Equal(t, params, result)
}

func TestCreateConfig_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("Create", mock.Anything, mock.Anything).Return(nil)
	f.eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)

	params := f.factory.CreateValidSystemConfig()
	config, err := f.cm.CreateConfig(f.ctx, "system", params, true)

	require.NoError(t, err)
	assert.NotNil(t, config)
	assert.Equal(t, "system", config.Namespace)
	assert.Equal(t, int64(1), config.Version)
	assert.True(t, config.Enabled)
	assert.Equal(t, params, config.Parameters)

	f.configRepo.AssertCalled(t, "Create", mock.Anything, mock.Anything)
}

func TestCreateConfig_WithDefaults(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("Create", mock.Anything, mock.Anything).Return(nil)
	f.eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)

	params := map[string]interface{}{"timeout": 60}
	config, err := f.cm.CreateConfig(f.ctx, "system", params, true)

	require.NoError(t, err)
	assert.NotNil(t, config)
	assert.Equal(t, 60, config.Parameters["timeout"])
	assert.Equal(t, 3, config.Parameters["retries"])
}

func TestCreateConfig_InvalidParams(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateInvalidSystemConfig()
	config, err := f.cm.CreateConfig(f.ctx, "system", params, true)

	assert.Error(t, err)
	assert.Nil(t, config)
	assert.Contains(t, err.Error(), "validation failed")
}

func TestCreateConfig_RepoError(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("Create", mock.Anything, mock.Anything).Return(assert.AnError)

	params := f.factory.CreateValidSystemConfig()
	config, err := f.cm.CreateConfig(f.ctx, "system", params, true)

	assert.Error(t, err)
	assert.Nil(t, config)
}

func TestGetConfig_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	expectedConfig := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		Build()

	f.configRepo.Configs[expectedConfig.ConfigID] = expectedConfig
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(expectedConfig, nil)

	config, err := f.cm.GetConfig(f.ctx, "system")

	require.NoError(t, err)
	assert.NotNil(t, config)
	assert.Equal(t, "system", config.Namespace)
}

func TestGetConfig_NotFound(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("GetLatest", mock.Anything, "system").Return(nil, nil)

	config, err := f.cm.GetConfig(f.ctx, "system")

	require.NoError(t, err)
	assert.NotNil(t, config)
	assert.True(t, config.Enabled)
}

func TestGetParameter_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateValidSystemConfig()
	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(params).
		Build()

	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	val, err := f.cm.GetParameter(f.ctx, "system", "timeout")

	require.NoError(t, err)
	assert.Equal(t, 30, val)
}

func TestGetParameter_DefaultValue(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("GetLatest", mock.Anything, "system").Return(nil, nil)

	val, err := f.cm.GetParameter(f.ctx, "system", "timeout")

	require.NoError(t, err)
	assert.Equal(t, 30, val)
}

func TestGetParameter_NotFound(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("GetLatest", mock.Anything, "system").Return(nil, nil)

	val, err := f.cm.GetParameter(f.ctx, "system", "non_existent_param")

	assert.Error(t, err)
	assert.Nil(t, val)
}

func TestGetIntParameter(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateValidSystemConfig()
	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(params).
		Build()

	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	val, err := f.cm.GetIntParameter(f.ctx, "system", "timeout")

	require.NoError(t, err)
	assert.Equal(t, 30, val)
}

func TestGetStringParameter(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateValidSystemConfig()
	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(params).
		Build()

	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	val, err := f.cm.GetStringParameter(f.ctx, "system", "log_level")

	require.NoError(t, err)
	assert.Equal(t, "info", val)
}

func TestGetBoolParameter(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateValidSystemConfig()
	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(params).
		Build()

	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	val, err := f.cm.GetBoolParameter(f.ctx, "system", "enabled")

	require.NoError(t, err)
	assert.True(t, val)
}

func TestGetFloatParameter(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := map[string]interface{}{
		"failure_threshold": 0.1,
	}
	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("ota").
		WithParameters(params).
		Build()

	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "ota").Return(config, nil)

	val, err := f.cm.GetFloatParameter(f.ctx, "ota", "failure_threshold")

	require.NoError(t, err)
	assert.Equal(t, 0.1, val)
}

func TestUpdateConfig_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	existingConfig := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		WithVersion(1).
		Build()

	f.configRepo.Configs[existingConfig.ConfigID] = existingConfig

	newParams := f.factory.CreateValidSystemConfig()
	newParams["timeout"] = 120

	f.configRepo.On("Update", mock.Anything, "system", newParams, true).Return(nil, nil)
	f.eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)

	config, err := f.cm.UpdateConfig(f.ctx, "system", newParams, true)

	require.NoError(t, err)
	assert.NotNil(t, config)
}

func TestUpdateConfig_InvalidParams(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	params := f.factory.CreateInvalidSystemConfig()
	config, err := f.cm.UpdateConfig(f.ctx, "system", params, true)

	assert.Error(t, err)
	assert.Nil(t, config)
}

func TestDeleteConfig_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	existingConfig := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		Build()
	f.configRepo.Configs[existingConfig.ConfigID] = existingConfig

	f.configRepo.On("Delete", mock.Anything, "system").Return(nil)
	f.eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)

	err := f.cm.DeleteConfig(f.ctx, "system")

	require.NoError(t, err)
	assert.Empty(t, f.configRepo.Configs)
}

func TestEnableConfig_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	config := testfactory.NewConfigDefinitionBuilder().
		WithConfigID("cfg_test").
		WithEnabled(true).
		Build()
	f.configRepo.Configs["cfg_test"] = config

	f.configRepo.On("UpdateEnabled", mock.Anything, "cfg_test", false).Return(nil)
	f.configRepo.On("GetByID", mock.Anything, "cfg_test").Return(config, nil)

	err := f.cm.EnableConfig(f.ctx, "cfg_test", false)

	require.NoError(t, err)
	assert.False(t, f.configRepo.Configs["cfg_test"].Enabled)
}

func TestApplyConfig_Success(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	config := testfactory.NewConfigDefinitionBuilder().
		WithConfigID("cfg_test").
		Build()
	f.configRepo.Configs["cfg_test"] = config

	f.configRepo.On("Apply", mock.Anything, "cfg_test").Return(nil)
	f.eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)

	err := f.cm.ApplyConfig(f.ctx, "cfg_test")

	require.NoError(t, err)
	assert.NotNil(t, f.configRepo.Configs["cfg_test"].AppliedAt)
}

func TestConcurrentConfigAccess(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		Build()
	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	var wg sync.WaitGroup
	concurrency := 50

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()

			_, err := f.cm.GetConfig(f.ctx, "system")
			assert.NoError(t, err)

			_, err = f.cm.GetIntParameter(f.ctx, "system", "timeout")
			assert.NoError(t, err)

			params := map[string]interface{}{"timeout": 30 + i}
			_ = f.cm.ApplyDefaults(f.ctx, "system", params)

			_, _ = f.cm.ValidateConfig(f.ctx, "system", params)
		}(i)
	}

	wg.Wait()
}

func TestConcurrentConfigCreateAndRead(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.configRepo.On("Create", mock.Anything, mock.Anything).Return(nil)
	f.eventBus.On("Publish", mock.Anything, mock.Anything).Return(nil)

	var wg sync.WaitGroup
	concurrency := 20

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()

			params := f.factory.CreateValidSystemConfig()
			params["timeout"] = 30 + i

			config, err := f.cm.CreateConfig(f.ctx, "system", params, true)
			if err == nil {
				assert.NotNil(t, config)
			}
		}(i)
	}

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			config := testfactory.NewConfigDefinitionBuilder().
				WithNamespace("system").
				WithParameters(f.factory.CreateValidSystemConfig()).
				Build()

			f.configRepo.Lock()
			f.configRepo.Configs[config.ConfigID] = config
			f.configRepo.Unlock()

			f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)
			_, _ = f.cm.GetConfig(f.ctx, "system")
		}()
	}

	wg.Wait()
}

func TestWatchConfig(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	var receivedConfig *model.ConfigDefinition
	var mu sync.Mutex

	handler := func(cfg *model.ConfigDefinition) {
		mu.Lock()
		defer mu.Unlock()
		receivedConfig = cfg
	}

	subID := f.cm.WatchConfig(f.ctx, "system", handler)
	assert.NotEmpty(t, subID)

	time.Sleep(10 * time.Millisecond)

	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		Build()
	f.configRepo.Configs[config.ConfigID] = config
	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	f.eventBus.Mu.RLock()
	handlers := make([]events.EventHandler, 0, len(f.eventBus.Subscriptions))
	for _, h := range f.eventBus.Subscriptions {
		handlers = append(handlers, h)
	}
	f.eventBus.Mu.RUnlock()

	for _, h := range handlers {
		event := events.Event{
			Type: events.EventConfigChanged,
			Payload: map[string]interface{}{
				"namespace": "system",
			},
		}
		h(f.ctx, event)
	}

	time.Sleep(10 * time.Millisecond)

	mu.Lock()
	assert.NotNil(t, receivedConfig)
	assert.Equal(t, "system", receivedConfig.Namespace)
	mu.Unlock()

	f.cm.Unwatch(subID)

	f.eventBus.Mu.RLock()
	assert.Empty(t, f.eventBus.Subscriptions)
	f.eventBus.Mu.RUnlock()
}

func TestValidationScheduler(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		Build()
	f.configRepo.Configs[config.ConfigID] = config

	f.configRepo.On("List", mock.Anything, "", 0, 100).Return([]model.ConfigDefinition{*config}, int64(1), nil)

	ctx, cancel := context.WithCancel(f.ctx)
	defer cancel()

	go f.cm.StartValidationScheduler(ctx, 10*time.Millisecond)

	time.Sleep(50 * time.Millisecond)
}

func TestResourceCleanup(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	ctx, cancel := context.WithCancel(f.ctx)

	go f.cm.StartValidationScheduler(ctx, 10*time.Millisecond)

	handler1 := func(cfg *model.ConfigDefinition) {}
	handler2 := func(cfg *model.ConfigDefinition) {}

	sub1 := f.cm.WatchConfig(ctx, "system", handler1)
	sub2 := f.cm.WatchConfig(ctx, "system", handler2)

	assert.NotEmpty(t, sub1)
	assert.NotEmpty(t, sub2)

	f.eventBus.Mu.RLock()
	assert.Len(t, f.eventBus.Subscriptions, 2)
	f.eventBus.Mu.RUnlock()

	f.cm.Unwatch(sub1)

	f.eventBus.Mu.RLock()
	assert.Len(t, f.eventBus.Subscriptions, 1)
	f.eventBus.Mu.RUnlock()

	f.cm.Unwatch(sub2)

	f.eventBus.Mu.RLock()
	assert.Empty(t, f.eventBus.Subscriptions)
	f.eventBus.Mu.RUnlock()

	cancel()
	time.Sleep(20 * time.Millisecond)
}

func TestCacheTTL(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	f.cm.cacheTTL = 50 * time.Millisecond

	config := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithParameters(f.factory.CreateValidSystemConfig()).
		Build()
	f.configRepo.Configs[config.ConfigID] = config

	f.configRepo.On("GetLatest", mock.Anything, "system").Return(config, nil)

	_, err := f.cm.GetConfig(f.ctx, "system")
	require.NoError(t, err)

	f.cm.mu.RLock()
	assert.Contains(t, f.cm.cache, "system")
	f.cm.mu.RUnlock()

	time.Sleep(100 * time.Millisecond)

	_, err = f.cm.GetConfig(f.ctx, "system")
	require.NoError(t, err)
}

func TestListConfigs(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	config1 := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithConfigID("cfg_1").
		Build()
	config2 := testfactory.NewConfigDefinitionBuilder().
		WithNamespace("system").
		WithConfigID("cfg_2").
		Build()

	f.configRepo.Configs["cfg_1"] = config1
	f.configRepo.Configs["cfg_2"] = config2

	f.configRepo.On("List", mock.Anything, "system", 0, 10).Return([]model.ConfigDefinition{*config1, *config2}, int64(2), nil)

	configs, total, err := f.cm.ListConfigs(f.ctx, "system", 0, 10)

	require.NoError(t, err)
	assert.Equal(t, int64(2), total)
	assert.Len(t, configs, 2)
}

func TestValidateParameter_TypeInt(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := ParameterSchema{Name: "test", Type: "int", MinValue: 1, MaxValue: 10}

	err := f.cm.validateParameter(schema, 5)
	assert.NoError(t, err)

	err = f.cm.validateParameter(schema, 0)
	assert.Error(t, err)

	err = f.cm.validateParameter(schema, 11)
	assert.Error(t, err)

	err = f.cm.validateParameter(schema, "not_int")
	assert.Error(t, err)
}

func TestValidateParameter_TypeString(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := ParameterSchema{Name: "test", Type: "string", EnumValues: []string{"a", "b", "c"}}

	err := f.cm.validateParameter(schema, "a")
	assert.NoError(t, err)

	err = f.cm.validateParameter(schema, "d")
	assert.Error(t, err)
}

func TestValidateParameter_TypeBool(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := ParameterSchema{Name: "test", Type: "bool"}

	err := f.cm.validateParameter(schema, true)
	assert.NoError(t, err)

	err = f.cm.validateParameter(schema, "not_bool")
	assert.Error(t, err)
}

func TestValidateParameter_RegexPattern(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := ParameterSchema{Name: "test", Type: "string", Pattern: `^[a-z]+$`}

	err := f.cm.validateParameter(schema, "abc")
	assert.NoError(t, err)

	err = f.cm.validateParameter(schema, "ABC123")
	assert.Error(t, err)
}

func TestValidateParameter_Required(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := ParameterSchema{Name: "test", Type: "string", Required: true}

	err := f.cm.validateParameter(schema, nil)
	assert.Error(t, err)

	err = f.cm.validateParameter(schema, "value")
	assert.NoError(t, err)
}

func TestValidateParameter_FloatType(t *testing.T) {
	t.Parallel()

	f := setupConfigTest(t)
	defer f.teardown()

	schema := ParameterSchema{Name: "test", Type: "float", MinValue: 0.0, MaxValue: 1.0}

	err := f.cm.validateParameter(schema, 0.5)
	assert.NoError(t, err)

	err = f.cm.validateParameter(schema, 1.5)
	assert.Error(t, err)

	err = f.cm.validateParameter(schema, "not_float")
	assert.Error(t, err)
}
