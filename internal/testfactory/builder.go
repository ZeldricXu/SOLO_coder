package testfactory

import (
	"math/rand"
	"time"

	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/utils"
)

type ConfigDefinitionBuilder struct {
	config *model.ConfigDefinition
}

func NewConfigDefinitionBuilder() *ConfigDefinitionBuilder {
	now := utils.NowUTC()
	return &ConfigDefinitionBuilder{
		config: &model.ConfigDefinition{
			ConfigID:   utils.GenerateID("cfg"),
			Namespace:  "test-namespace",
			Version:    1,
			Parameters: make(map[string]interface{}),
			Enabled:    true,
			CreatedAt:  now,
			UpdatedAt:  now,
		},
	}
}

func (b *ConfigDefinitionBuilder) WithConfigID(id string) *ConfigDefinitionBuilder {
	b.config.ConfigID = id
	return b
}

func (b *ConfigDefinitionBuilder) WithNamespace(ns string) *ConfigDefinitionBuilder {
	b.config.Namespace = ns
	return b
}

func (b *ConfigDefinitionBuilder) WithVersion(v int64) *ConfigDefinitionBuilder {
	b.config.Version = v
	return b
}

func (b *ConfigDefinitionBuilder) WithParameter(key string, value interface{}) *ConfigDefinitionBuilder {
	b.config.Parameters[key] = value
	return b
}

func (b *ConfigDefinitionBuilder) WithParameters(params map[string]interface{}) *ConfigDefinitionBuilder {
	b.config.Parameters = params
	return b
}

func (b *ConfigDefinitionBuilder) WithEnabled(enabled bool) *ConfigDefinitionBuilder {
	b.config.Enabled = enabled
	return b
}

func (b *ConfigDefinitionBuilder) WithAppliedAt(t time.Time) *ConfigDefinitionBuilder {
	b.config.AppliedAt = &t
	return b
}

func (b *ConfigDefinitionBuilder) Build() *model.ConfigDefinition {
	return b.config
}

type RuleBuilder struct {
	rule *model.Rule
}

func NewRuleBuilder() *RuleBuilder {
	now := utils.NowUTC()
	return &RuleBuilder{
		rule: &model.Rule{
			RuleID:          utils.GenerateID("rule"),
			Name:            "Test Rule",
			Description:     "Test rule for automated testing",
			DeviceID:        "dev-001",
			Enabled:         true,
			Conditions:      make([]model.RuleCondition, 0),
			Actions:         make([]model.RuleAction, 0),
			MatchAll:        true,
			CooldownSeconds: 60,
			TriggerCount:    0,
			Tags:            make(map[string]string),
			CreatedAt:       now,
			UpdatedAt:       now,
		},
	}
}

func (b *RuleBuilder) WithRuleID(id string) *RuleBuilder {
	b.rule.RuleID = id
	return b
}

func (b *RuleBuilder) WithName(name string) *RuleBuilder {
	b.rule.Name = name
	return b
}

func (b *RuleBuilder) WithDescription(desc string) *RuleBuilder {
	b.rule.Description = desc
	return b
}

func (b *RuleBuilder) WithDeviceID(deviceID string) *RuleBuilder {
	b.rule.DeviceID = deviceID
	return b
}

func (b *RuleBuilder) WithEnabled(enabled bool) *RuleBuilder {
	b.rule.Enabled = enabled
	return b
}

func (b *RuleBuilder) WithCondition(cond model.RuleCondition) *RuleBuilder {
	b.rule.Conditions = append(b.rule.Conditions, cond)
	return b
}

func (b *RuleBuilder) WithConditions(conditions []model.RuleCondition) *RuleBuilder {
	b.rule.Conditions = conditions
	return b
}

func (b *RuleBuilder) WithAction(action model.RuleAction) *RuleBuilder {
	b.rule.Actions = append(b.rule.Actions, action)
	return b
}

func (b *RuleBuilder) WithActions(actions []model.RuleAction) *RuleBuilder {
	b.rule.Actions = actions
	return b
}

func (b *RuleBuilder) WithMatchAll(matchAll bool) *RuleBuilder {
	b.rule.MatchAll = matchAll
	return b
}

func (b *RuleBuilder) WithCooldown(seconds int) *RuleBuilder {
	b.rule.CooldownSeconds = seconds
	return b
}

func (b *RuleBuilder) WithLastTriggeredAt(t time.Time) *RuleBuilder {
	b.rule.LastTriggeredAt = &t
	return b
}

func (b *RuleBuilder) WithTag(key, value string) *RuleBuilder {
	b.rule.Tags[key] = value
	return b
}

func (b *RuleBuilder) Build() *model.Rule {
	return b.rule
}

type AIModelBuilder struct {
	model *model.AIModel
}

func NewAIModelBuilder() *AIModelBuilder {
	now := utils.NowUTC()
	return &AIModelBuilder{
		model: &model.AIModel{
			ModelID:      utils.GenerateID("model"),
			Name:         "test-model",
			Version:      "v1.0.0",
			Framework:    "pytorch",
			Architecture: "resnet50",
			SizeBytes:    1024 * 1024 * 100,
			Checksum:     "sha256:abc123",
			DownloadURL:  "https://example.com/models/model.pt",
			Status:       model.ModelStatusPending,
			DeviceIDs:    []string{"dev-001"},
			Labels:       make(map[string]string),
			Metadata:     make(map[string]interface{}),
			CreatedAt:    now,
			UpdatedAt:    now,
		},
	}
}

func (b *AIModelBuilder) WithModelID(id string) *AIModelBuilder {
	b.model.ModelID = id
	return b
}

func (b *AIModelBuilder) WithName(name string) *AIModelBuilder {
	b.model.Name = name
	return b
}

func (b *AIModelBuilder) WithVersion(version string) *AIModelBuilder {
	b.model.Version = version
	return b
}

func (b *AIModelBuilder) WithFramework(framework string) *AIModelBuilder {
	b.model.Framework = framework
	return b
}

func (b *AIModelBuilder) WithStatus(status model.ModelStatus) *AIModelBuilder {
	b.model.Status = status
	return b
}

func (b *AIModelBuilder) WithDeviceIDs(ids []string) *AIModelBuilder {
	b.model.DeviceIDs = ids
	return b
}

func (b *AIModelBuilder) WithLabel(key, value string) *AIModelBuilder {
	b.model.Labels[key] = value
	return b
}

func (b *AIModelBuilder) Build() *model.AIModel {
	return b.model
}

type InferenceTaskBuilder struct {
	task *model.InferenceTask
}

func NewInferenceTaskBuilder() *InferenceTaskBuilder {
	now := utils.NowUTC()
	return &InferenceTaskBuilder{
		task: &model.InferenceTask{
			TaskID:         utils.GenerateID("task"),
			ModelID:        "model-001",
			DeviceID:       "dev-001",
			TraceID:        utils.GenerateID("trace"),
			Status:         model.InferenceStatusQueued,
			InputData:      `{"data": "test"}`,
			InputFormat:    "json",
			OutputFormat:   "json",
			Priority:       0,
			TimeoutSeconds: 300,
			CallbackURL:    "https://example.com/callback",
			ResultSynced:   false,
			CreatedAt:      now,
			UpdatedAt:      now,
		},
	}
}

func (b *InferenceTaskBuilder) WithTaskID(id string) *InferenceTaskBuilder {
	b.task.TaskID = id
	return b
}

func (b *InferenceTaskBuilder) WithModelID(id string) *InferenceTaskBuilder {
	b.task.ModelID = id
	return b
}

func (b *InferenceTaskBuilder) WithDeviceID(id string) *InferenceTaskBuilder {
	b.task.DeviceID = id
	return b
}

func (b *InferenceTaskBuilder) WithTraceID(id string) *InferenceTaskBuilder {
	b.task.TraceID = id
	return b
}

func (b *InferenceTaskBuilder) WithStatus(status model.InferenceStatus) *InferenceTaskBuilder {
	b.task.Status = status
	return b
}

func (b *InferenceTaskBuilder) WithInputData(data string) *InferenceTaskBuilder {
	b.task.InputData = data
	return b
}

func (b *InferenceTaskBuilder) WithPriority(priority int) *InferenceTaskBuilder {
	b.task.Priority = priority
	return b
}

func (b *InferenceTaskBuilder) WithTimeout(seconds int) *InferenceTaskBuilder {
	b.task.TimeoutSeconds = seconds
	return b
}

func (b *InferenceTaskBuilder) Build() *model.InferenceTask {
	return b.task
}

type InferenceRequestBuilder struct {
	req *model.InferenceRequest
}

func NewInferenceRequestBuilder() *InferenceRequestBuilder {
	return &InferenceRequestBuilder{
		req: &model.InferenceRequest{
			ModelID:        "model-001",
			DeviceID:       "dev-001",
			InputData:      `{"input": [1, 2, 3]}`,
			InputFormat:    "json",
			OutputFormat:   "json",
			Priority:       0,
			TimeoutSeconds: 300,
			CallbackURL:    "",
			Parameters:     make(map[string]interface{}),
		},
	}
}

func (b *InferenceRequestBuilder) WithModelID(id string) *InferenceRequestBuilder {
	b.req.ModelID = id
	return b
}

func (b *InferenceRequestBuilder) WithDeviceID(id string) *InferenceRequestBuilder {
	b.req.DeviceID = id
	return b
}

func (b *InferenceRequestBuilder) WithInputData(data string) *InferenceRequestBuilder {
	b.req.InputData = data
	return b
}

func (b *InferenceRequestBuilder) WithPriority(priority int) *InferenceRequestBuilder {
	b.req.Priority = priority
	return b
}

func (b *InferenceRequestBuilder) Build() *model.InferenceRequest {
	return b.req
}

type TestDataFactory struct {
	rand *rand.Rand
}

func NewTestDataFactory() *TestDataFactory {
	return &TestDataFactory{
		rand: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (f *TestDataFactory) CreateValidSystemConfig() map[string]interface{} {
	return map[string]interface{}{
		"timeout":        30,
		"retries":        3,
		"log_level":      "info",
		"max_concurrent": 100,
		"enabled":        true,
	}
}

func (f *TestDataFactory) CreateInvalidSystemConfig() map[string]interface{} {
	return map[string]interface{}{
		"timeout":        -1,
		"retries":        100,
		"log_level":      "invalid_level",
		"max_concurrent": 20000,
		"enabled":        "not_bool",
	}
}

func (f *TestDataFactory) CreateValidGatewayConfig() map[string]interface{} {
	return map[string]interface{}{
		"rate_limit": 1000,
		"burst_size": 100,
		"protocol":   "https",
		"port":       8443,
	}
}

func (f *TestDataFactory) CreateTemperatureRule() *model.Rule {
	return NewRuleBuilder().
		WithName("Temperature Alert").
		WithDeviceID("sensor-001").
		WithCondition(model.RuleCondition{
			Field:    "temperature",
			Operator: model.OpGreaterThan,
			Value:    30.0,
		}).
		WithAction(model.RuleAction{
			Type: "notification",
			Parameters: map[string]interface{}{
				"message": "Temperature exceeded threshold!",
			},
		}).
		WithCooldown(30).
		Build()
}

func (f *TestDataFactory) CreateMultiConditionRule() *model.Rule {
	return NewRuleBuilder().
		WithName("Complex Rule").
		WithDeviceID("device-multi").
		WithCondition(model.RuleCondition{
			Field:    "temperature",
			Operator: model.OpGreaterThan,
			Value:    25.0,
		}).
		WithCondition(model.RuleCondition{
			Field:    "humidity",
			Operator: model.OpGreaterThan,
			Value:    80.0,
		}).
		WithMatchAll(true).
		WithAction(model.RuleAction{
			Type: "notification",
			Parameters: map[string]interface{}{
				"message": "High temperature and humidity!",
			},
		}).
		Build()
}

func (f *TestDataFactory) CreateRegexRule() *model.Rule {
	return NewRuleBuilder().
		WithName("Error Log Pattern").
		WithDeviceID("log-001").
		WithCondition(model.RuleCondition{
			Field:    "log_message",
			Operator: model.OpRegex,
			Value:    `ERROR.*timeout`,
		}).
		WithAction(model.RuleAction{
			Type: "webhook",
			Parameters: map[string]interface{}{
				"url": "https://example.com/alert",
			},
		}).
		Build()
}

func (f *TestDataFactory) CreateInferenceModel() *model.AIModel {
	return NewAIModelBuilder().
		WithName("image-classifier").
		WithVersion("v2.1.0").
		WithFramework("tensorflow").
		WithStatus(model.ModelStatusReady).
		WithDeviceIDs([]string{"edge-001", "edge-002"}).
		WithLabel("type", "classification").
		WithLabel("accuracy", "95%").
		Build()
}

func (f *TestDataFactory) CreateRandomDeviceID() string {
	return utils.GenerateID("dev")
}

func (f *TestDataFactory) CreateRandomTraceID() string {
	return utils.GenerateID("trace")
}

func (f *TestDataFactory) CreateTestRuleTriggerData() map[string]interface{} {
	return map[string]interface{}{
		"temperature": 35.5,
		"humidity":    75.0,
		"pressure":    1013.25,
		"timestamp":   time.Now().Unix(),
		"device_id":   "sensor-001",
	}
}

func (f *TestDataFactory) CreateRuleTriggerData(temp float64, humidity float64) map[string]interface{} {
	return map[string]interface{}{
		"temperature": temp,
		"humidity":    humidity,
		"timestamp":   time.Now().Unix(),
	}
}
