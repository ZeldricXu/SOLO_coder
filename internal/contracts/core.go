package contracts

import (
	"context"
	"time"

	"github.com/solocoder/task-scheduler/internal/models"
)

type ValidationError struct {
	Details map[string]string
}

func (e *ValidationError) Error() string {
	return "validation failed"
}

type TimeoutError struct {
	Message string
}

func (e *TimeoutError) Error() string {
	return e.Message
}

type ProcessRequest struct {
	TraceID   string                 `json:"trace_id"`
	Namespace string                 `json:"namespace"`
	Params    map[string]interface{} `json:"params"`
	Payload   map[string]interface{} `json:"payload"`
	EntityID  string                 `json:"entity_id"`
	Scene     string                 `json:"scene,omitempty"`
}

type ProcessResult struct {
	Success    bool                   `json:"success"`
	Data       map[string]interface{} `json:"data,omitempty"`
	Error      string                 `json:"error,omitempty"`
	StatusCode int                    `json:"status_code"`
}

type ProcessingRules struct {
	Timeout        time.Duration
	MaxRetries     int
	Validation     map[string]interface{}
	PostProcessors []string
	Scene          string
}

type SceneStrategy string

const (
	SceneDefault    SceneStrategy = "default"
	SceneHighPrio   SceneStrategy = "high_priority"
	SceneBatch      SceneStrategy = "batch"
	SceneRealTime   SceneStrategy = "realtime"
	SceneBackground SceneStrategy = "background"
)

type SceneConfig struct {
	Scene        SceneStrategy
	Timeout      time.Duration
	MaxRetries   int
	PoolSize     int
	Priority     int
	RateLimit    int
	EnableRetry  bool
	EnableFallback bool
	CustomRules  map[string]interface{}
}

type ConfigChangeListener interface {
	OnConfigChanged(namespace string, oldConfig, newConfig *models.ConfigDefinition)
	OnSceneChanged(namespace string, oldScene, newScene SceneStrategy)
}

type DynamicConfigLoader interface {
	ConfigLoader
	UpdateConfig(ctx context.Context, namespace string, config *models.ConfigDefinition) error
	GetSceneConfig(ctx context.Context, namespace string, scene SceneStrategy) (*SceneConfig, error)
	SetSceneConfig(ctx context.Context, namespace string, scene SceneStrategy, config *SceneConfig) error
	GetCurrentScene(ctx context.Context, namespace string) SceneStrategy
	SetCurrentScene(ctx context.Context, namespace string, scene SceneStrategy) error
	AddChangeListener(listener ConfigChangeListener)
	RemoveChangeListener(listener ConfigChangeListener)
	ReloadConfig(ctx context.Context, namespace string) error
}

type ParameterValidator interface {
	Validate(params map[string]interface{}) error
}

type ResourcePool interface {
	Acquire(ctx context.Context) (struct{}, error)
	Release()
	Used() int
	Total() int
	Resize(newSize int) error
}

type MetricsCollector interface {
	Record(latency time.Duration, success bool)
	Snapshot() map[string]interface{}
}

type RunInstanceManager interface {
	Create(ctx context.Context, req *ProcessRequest, config *models.ConfigDefinition) (runID string)
	UpdatePhase(ctx context.Context, runID string, phase models.RunPhase, errorDetail string)
	UpdateProgress(ctx context.Context, runID string, progress float64) error
}

type ResultPersister interface {
	Persist(ctx context.Context, result map[string]interface{}, entityID string) error
}

type EventPublisher interface {
	PublishTaskStarted(ctx context.Context, entityID, runID string)
	PublishTaskCompleted(ctx context.Context, entityID string, result map[string]interface{}, runID string, duration float64)
	PublishProgressUpdate(ctx context.Context, runID string, progress float64)
}

type TaskProcessor interface {
	Process(ctx context.Context, payload map[string]interface{}, rules *ProcessingRules) (map[string]interface{}, error)
}

type TaskExecutor interface {
	Execute(ctx context.Context, req *ProcessRequest) *ProcessResult
}

type RuleExtractor interface {
	ExtractRules(config *models.ConfigDefinition) *ProcessingRules
	ExtractRulesForScene(config *models.ConfigDefinition, scene SceneStrategy) *ProcessingRules
}
