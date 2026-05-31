package mocks

import (
	"context"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/stretchr/testify/mock"
	"gorm.io/gorm"
)

type MockConfigRepository struct {
	mock.Mock
	sync.RWMutex
	Configs map[string]*model.ConfigDefinition
}

func NewMockConfigRepository() *MockConfigRepository {
	return &MockConfigRepository{
		Configs: make(map[string]*model.ConfigDefinition),
	}
}

func (m *MockConfigRepository) Create(ctx context.Context, config *model.ConfigDefinition) error {
	m.Lock()
	defer m.Unlock()

	config.CreatedAt = time.Now().UTC()
	config.UpdatedAt = time.Now().UTC()
	m.Configs[config.ConfigID] = config
	args := m.Called(ctx, config)
	return args.Error(0)
}

func (m *MockConfigRepository) GetByID(ctx context.Context, configID string) (*model.ConfigDefinition, error) {
	m.RLock()
	defer m.RUnlock()

	args := m.Called(ctx, configID)
	if config, ok := m.Configs[configID]; ok {
		return config, args.Error(1)
	}
	return nil, args.Error(1)
}

func (m *MockConfigRepository) GetLatest(ctx context.Context, namespace string) (*model.ConfigDefinition, error) {
	m.RLock()
	defer m.RUnlock()

	args := m.Called(ctx, namespace)

	var latest *model.ConfigDefinition
	for _, cfg := range m.Configs {
		if cfg.Namespace == namespace {
			if latest == nil || cfg.Version > latest.Version {
				latest = cfg
			}
		}
	}

	if latest != nil {
		return latest, args.Error(1)
	}
	return nil, errors.NewNotFoundError("config not found")
}

func (m *MockConfigRepository) List(ctx context.Context, namespace string, offset, limit int) ([]model.ConfigDefinition, int64, error) {
	m.RLock()
	defer m.RUnlock()

	args := m.Called(ctx, namespace, offset, limit)

	var result []model.ConfigDefinition
	for _, cfg := range m.Configs {
		if cfg.Namespace == namespace || namespace == "" {
			result = append(result, *cfg)
		}
	}

	return result, int64(len(result)), args.Error(2)
}

func (m *MockConfigRepository) UpdateEnabled(ctx context.Context, configID string, enabled bool) error {
	m.Lock()
	defer m.Unlock()

	args := m.Called(ctx, configID, enabled)

	if cfg, ok := m.Configs[configID]; ok {
		cfg.Enabled = enabled
		cfg.UpdatedAt = time.Now().UTC()
		return args.Error(0)
	}
	return gorm.ErrRecordNotFound
}

func (m *MockConfigRepository) Apply(ctx context.Context, configID string) error {
	m.Lock()
	defer m.Unlock()

	args := m.Called(ctx, configID)

	if cfg, ok := m.Configs[configID]; ok {
		now := time.Now().UTC()
		cfg.AppliedAt = &now
		cfg.UpdatedAt = now
		return args.Error(0)
	}
	return gorm.ErrRecordNotFound
}

func (m *MockConfigRepository) Update(ctx context.Context, namespace string, parameters map[string]interface{}, enabled bool) (*model.ConfigDefinition, error) {
	m.Lock()
	defer m.Unlock()

	args := m.Called(ctx, namespace, parameters, enabled)

	var latest *model.ConfigDefinition
	for _, cfg := range m.Configs {
		if cfg.Namespace == namespace {
			if latest == nil || cfg.Version > latest.Version {
				latest = cfg
			}
		}
	}

	if latest != nil {
		now := time.Now().UTC()
		newConfig := *latest
		newConfig.Version = latest.Version + 1
		newConfig.Parameters = parameters
		newConfig.Enabled = enabled
		newConfig.UpdatedAt = now
		newConfig.ConfigID = "cfg_" + time.Now().Format("20060102150405")
		m.Configs[newConfig.ConfigID] = &newConfig
		return &newConfig, args.Error(1)
	}

	return nil, errors.NewNotFoundError("config not found")
}

func (m *MockConfigRepository) Delete(ctx context.Context, namespace string) error {
	m.Lock()
	defer m.Unlock()

	args := m.Called(ctx, namespace)

	for id, cfg := range m.Configs {
		if cfg.Namespace == namespace {
			delete(m.Configs, id)
		}
	}

	return args.Error(0)
}

type MockActionExecutor struct {
	mock.Mock
	ExecuteCount int
	LastAction   model.RuleAction
	LastData     map[string]interface{}
	Mu           sync.Mutex
	ReturnError  bool
	ErrorMessage string
}

func NewMockActionExecutor() *MockActionExecutor {
	return &MockActionExecutor{
		ExecuteCount: 0,
		ReturnError:  false,
	}
}

func (m *MockActionExecutor) Execute(ctx context.Context, action model.RuleAction, data map[string]interface{}) error {
	m.Mu.Lock()
	defer m.Mu.Unlock()

	m.ExecuteCount++
	m.LastAction = action
	m.LastData = data

	args := m.Called(ctx, action, data)

	if m.ReturnError {
		if m.ErrorMessage != "" {
			return errors.NewValidationError(m.ErrorMessage)
		}
		return errors.NewInternalError("action execution failed", nil)
	}
	return args.Error(0)
}

func (m *MockActionExecutor) Reset() {
	m.Mu.Lock()
	defer m.Mu.Unlock()
	m.ExecuteCount = 0
	m.LastAction = model.RuleAction{}
	m.LastData = nil
	m.ReturnError = false
	m.ErrorMessage = ""
}

type MockInferenceExecutor struct {
	mock.Mock
	ExecuteCount   int
	LastTask       *model.InferenceTask
	LastModel      *model.AIModel
	Mu             sync.Mutex
	ReturnError    bool
	ErrorMessage   string
	MockResult     string
	ExecutionDelay time.Duration
}

func NewMockInferenceExecutor() *MockInferenceExecutor {
	return &MockInferenceExecutor{
		ExecuteCount: 0,
		ReturnError:  false,
		MockResult:   `{"result": "mock_result", "confidence": 0.95}`,
	}
}

func (m *MockInferenceExecutor) Execute(ctx context.Context, task *model.InferenceTask, aiModel *model.AIModel) (string, error) {
	m.Mu.Lock()
	m.ExecuteCount++
	m.LastTask = task
	m.LastModel = aiModel
	delay := m.ExecutionDelay
	shouldError := m.ReturnError
	errMsg := m.ErrorMessage
	result := m.MockResult
	m.Mu.Unlock()

	if delay > 0 {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(delay):
		}
	}

	m.Called(ctx, task, aiModel)

	if shouldError {
		if errMsg != "" {
			return "", errors.NewInternalError(errMsg, nil)
		}
		return "", errors.NewInternalError("inference execution failed", nil)
	}
	return result, nil
}

func (m *MockInferenceExecutor) Reset() {
	m.Mu.Lock()
	defer m.Mu.Unlock()
	m.ExecuteCount = 0
	m.LastTask = nil
	m.LastModel = nil
	m.ReturnError = false
	m.ErrorMessage = ""
	m.ExecutionDelay = 0
}

type MockEventBus struct {
	mock.Mock
	Events           []events.Event
	Subscriptions    map[string]events.EventHandler
	subscriptionType map[string]events.EventType
	Mu               sync.RWMutex
	closed           bool
}

func NewMockEventBus() *MockEventBus {
	return &MockEventBus{
		Events:           make([]events.Event, 0),
		Subscriptions:  make(map[string]events.EventHandler),
		subscriptionType: make(map[string]events.EventType),
	}
}

func (m *MockEventBus) Publish(ctx context.Context, event events.Event) error {
	m.Mu.Lock()
	defer m.Mu.Unlock()

	m.Events = append(m.Events, event)
	args := m.Called(ctx, event)
	return args.Error(0)
}

func (m *MockEventBus) Subscribe(eventType events.EventType, handler events.EventHandler) string {
	m.Mu.Lock()
	defer m.Mu.Unlock()

	subID := "sub_" + time.Now().Format("20060102150405") + "_" + string(eventType)
	m.Subscriptions[subID] = handler
	m.subscriptionType[subID] = eventType
	m.Called(eventType, handler)
	return subID
}

func (m *MockEventBus) Unsubscribe(subscriptionID string) {
	m.Mu.Lock()
	defer m.Mu.Unlock()

	delete(m.Subscriptions, subscriptionID)
	delete(m.subscriptionType, subscriptionID)
	m.Called(subscriptionID)
}

func (m *MockEventBus) Close() {
	m.Mu.Lock()
	defer m.Mu.Unlock()

	m.closed = true
	m.Called()
}

func (m *MockEventBus) GetEventCount() int {
	m.Mu.Lock()
	defer m.Mu.Unlock()
	return len(m.Events)
}

func (m *MockEventBus) GetLastEvent() interface{} {
	m.Mu.Lock()
	defer m.Mu.Unlock()
	if len(m.Events) == 0 {
		return nil
	}
	return m.Events[len(m.Events)-1]
}

func (m *MockEventBus) Reset() {
	m.Mu.Lock()
	defer m.Mu.Unlock()
	m.Events = make([]events.Event, 0)
	m.Subscriptions = make(map[string]events.EventHandler)
	m.subscriptionType = make(map[string]events.EventType)
	m.closed = false
}

type MockDataAccess struct {
	*data.DataAccess
}

func NewMockDataAccess() *MockDataAccess {
	return &MockDataAccess{
		DataAccess: &data.DataAccess{},
	}
}

func (m *MockDataAccess) DB() *gorm.DB {
	return nil
}

type TestLogger struct {
	Mu     sync.Mutex
	logs   []string
	errors []string
}

func NewTestLogger() *TestLogger {
	return &TestLogger{
		logs:   make([]string, 0),
		errors: make([]string, 0),
	}
}

func (l *TestLogger) Log(msg string) {
	l.Mu.Lock()
	defer l.Mu.Unlock()
	l.logs = append(l.logs, msg)
}

func (l *TestLogger) Error(msg string) {
	l.Mu.Lock()
	defer l.Mu.Unlock()
	l.errors = append(l.errors, msg)
}

func (l *TestLogger) GetLogs() []string {
	l.Mu.Lock()
	defer l.Mu.Unlock()
	logs := make([]string, len(l.logs))
	copy(logs, l.logs)
	return logs
}

func (l *TestLogger) GetErrors() []string {
	l.Mu.Lock()
	defer l.Mu.Unlock()
	errs := make([]string, len(l.errors))
	copy(errs, l.errors)
	return errs
}

func (l *TestLogger) Reset() {
	l.Mu.Lock()
	defer l.Mu.Unlock()
	l.logs = make([]string, 0)
	l.errors = make([]string, 0)
}
