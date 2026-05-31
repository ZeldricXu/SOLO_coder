package testbuilders

import (
	"time"

	"session133/internal/model"
	"session133/internal/apigateway"
	"session133/internal/prompt"
)

type ModelBuilder struct {
	mod *model.Model
}

func NewModelBuilder() *ModelBuilder {
	now := time.Now()
	return &ModelBuilder{
		mod: &model.Model{
			ID:          "test_model_001",
			Name:        "Test Model",
			Namespace:   "test-namespace",
			Description: "Test model for unit testing",
			Type:        "llm",
			Status:      model.ModelStatusDraft,
			Labels: map[string]string{
				"env":     "test",
				"team":    "qa",
			},
			CreatedBy:   "test-user",
			CreatedAt:   now,
			UpdatedAt:   now,
			Metadata: map[string]interface{}{
				"framework": "pytorch",
				"version":   "1.0.0",
			},
		},
	}
}

func (b *ModelBuilder) WithID(id string) *ModelBuilder {
	b.mod.ID = id
	return b
}

func (b *ModelBuilder) WithName(name string) *ModelBuilder {
	b.mod.Name = name
	return b
}

func (b *ModelBuilder) WithNamespace(namespace string) *ModelBuilder {
	b.mod.Namespace = namespace
	return b
}

func (b *ModelBuilder) WithStatus(status model.ModelStatus) *ModelBuilder {
	b.mod.Status = status
	return b
}

func (b *ModelBuilder) WithLabels(labels map[string]string) *ModelBuilder {
	b.mod.Labels = labels
	return b
}

func (b *ModelBuilder) WithType(modelType string) *ModelBuilder {
	b.mod.Type = modelType
	return b
}

func (b *ModelBuilder) Build() *model.Model {
	return b.mod
}

type ModelVersionBuilder struct {
	ver *model.ModelVersion
}

func NewModelVersionBuilder() *ModelVersionBuilder {
	now := time.Now()
	return &ModelVersionBuilder{
		ver: &model.ModelVersion{
			ID:        "test_version_001",
			ModelID:     "test_model_001",
			Version:     "1.0.0",
			Stage:       model.StageDevelopment,
			Description: "Initial version",
			Checksum:    "sha256:abc123",
			Size:        1024000,
			CreatedBy:   "test-user",
			CreatedAt:   now,
			UpdatedAt:   now,
			Metrics: map[string]float64{
				"accuracy": 0.95,
				"latency_p99": 150.0,
			},
			Artifacts: []string{"model.pt", "config.yaml"},
		},
	}
}

func (b *ModelVersionBuilder) WithID(id string) *ModelVersionBuilder {
	b.ver.ID = id
	return b
}

func (b *ModelVersionBuilder) WithModelID(modelID string) *ModelVersionBuilder {
	b.ver.ModelID = modelID
	return b
}

func (b *ModelVersionBuilder) WithVersion(version string) *ModelVersionBuilder {
	b.ver.Version = version
	return b
}

func (b *ModelVersionBuilder) WithStage(stage model.Stage) *ModelVersionBuilder {
	b.ver.Stage = stage
	return b
}

func (b *ModelVersionBuilder) Build() *model.ModelVersion {
	return b.ver
}

type UserBuilder struct {
	user *apigateway.User
}

func NewUserBuilder() *UserBuilder {
	now := time.Now()
	return &UserBuilder{
		user: &apigateway.User{
			ID:       "test_user_001",
			Username: "testuser",
			Email:    "test@example.com",
			PasswordHash: "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
			Roles:    []string{"user"},
			APIKey:   "test-api-key-12345",
			Status:   apigateway.UserStatusActive,
			CreatedAt: now,
			UpdatedAt: now,
		},
	}
}

func (b *UserBuilder) WithID(id string) *UserBuilder {
	b.user.ID = id
	return b
}

func (b *UserBuilder) WithUsername(username string) *UserBuilder {
	b.user.Username = username
	return b
}

func (b *UserBuilder) WithRoles(roles []string) *UserBuilder {
	b.user.Roles = roles
	return b
}

func (b *UserBuilder) WithAPIKey(apiKey string) *UserBuilder {
	b.user.APIKey = apiKey
	return b
}

func (b *UserBuilder) WithStatus(status apigateway.UserStatus) *UserBuilder {
	b.user.Status = status
	return b
}

func (b *UserBuilder) Build() *apigateway.User {
	return b.user
}

type PromptBuilder struct {
	p *prompt.Prompt
}

func NewPromptBuilder() *PromptBuilder {
	now := time.Now()
	return &PromptBuilder{
		p: &prompt.Prompt{
			ID:          "test_prompt_001",
			Name:        "Test Prompt",
			Namespace:   "test-namespace",
			Description: "Test prompt for testing",
			Content:     "You are a helpful assistant.",
			Version:     1,
			Status:    prompt.PromptStatusActive,
			CreatedBy:   "test-user",
			CreatedAt:   now,
			UpdatedAt:   now,
			Variables:   []string{"user_input", "context"},
			Labels: map[string]string{
				"type": "chat",
			},
		},
	}
}

func (b *PromptBuilder) WithID(id string) *PromptBuilder {
	b.p.ID = id
	return b
}

func (b *PromptBuilder) WithName(name string) *PromptBuilder {
	b.p.Name = name
	return b
}

func (b *PromptBuilder) WithContent(content string) *PromptBuilder {
	b.p.Content = content
	return b
}

func (b *PromptBuilder) WithStatus(status prompt.PromptStatus) *PromptBuilder {
	b.p.Status = status
	return b
}

func (b *PromptBuilder) Build() *prompt.Prompt {
	return b.p
}

type ABTestBuilder struct {
	test *prompt.ABTest
}

func NewABTestBuilder() *ABTestBuilder {
	now := time.Now()
	return &ABTestBuilder{
		test: &prompt.ABTest{
			ID:              "test_ab_001",
			Name:            "Test AB Experiment",
			Namespace:       "test-namespace",
			Description:     "AB test for unit testing",
			ControlPromptID: "prompt_control",
			TestPromptID:   "prompt_test",
			TrafficPercentage: 50,
			Status:          prompt.ABTestStatusRunning,
			CreatedBy:       "test-user",
			CreatedAt:       now,
			UpdatedAt:       now,
			StartTime:     &now,
			Metrics: []prompt.ABTestMetric{
				{Name: "conversion_rate", DisplayName: "Conversion Rate"},
			},
		},
	}
}

func (b *ABTestBuilder) WithID(id string) *ABTestBuilder {
	b.test.ID = id
	return b
}

func (b *ABTestBuilder) WithName(name string) *ABTestBuilder {
	b.test.Name = name
	return b
}

func (b *ABTestBuilder) WithStatus(status prompt.ABTestStatus) *ABTestBuilder {
	b.test.Status = status
	return b
}

func (b *ABTestBuilder) WithTrafficPercentage(pct int) *ABTestBuilder {
	b.test.TrafficPercentage = pct
	return b
}

func (b *ABTestBuilder) Build() *prompt.ABTest {
	return b.test
}
