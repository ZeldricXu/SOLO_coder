package domain

import (
	"context"
	"time"
)

type ResourceStatus string

const (
	StatusPending     ResourceStatus = "pending"
	StatusRunning     ResourceStatus = "running"
	StatusCompleted   ResourceStatus = "completed"
	StatusFailed      ResourceStatus = "failed"
	StatusPreempted   ResourceStatus = "preempted"
	StatusProvisioning ResourceStatus = "provisioning"
)

type Entity struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	Status     ResourceStatus         `json:"status"`
	Attributes map[string]interface{} `json:"attributes"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID  string                 `json:"config_id"`
	Namespace string                 `json:"namespace"`
	Version   int                    `json:"version"`
	Params    map[string]interface{} `json:"parameters"`
	Enabled   bool                   `json:"enabled"`
	AppliedAt time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID        string     `json:"run_id"`
	EntityID     string     `json:"entity_id"`
	Phase        string     `json:"phase"`
	Progress     float64    `json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at"`
	ErrorDetail  *string    `json:"error_detail"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics"`
	Dimensions map[string]string      `json:"dimensions"`
}

type GPUScheduler interface {
	SubmitTask(ctx context.Context, task *GPUTask) (*GPUTask, error)
	CancelTask(ctx context.Context, taskID string) error
	GetTaskStatus(ctx context.Context, taskID string) (*GPUTask, error)
	PreemptTasks(ctx context.Context, minVRAM uint64) ([]*GPUTask, error)
	GetAvailableResources(ctx context.Context) ([]*GPUResource, error)
	Shutdown(ctx context.Context) error
}

type GPUResourceManager interface {
	Acquire(ctx context.Context, req *GPUResourceRequest) (*GPUResource, error)
	Release(ctx context.Context, resourceID string) error
	List(ctx context.Context) ([]*GPUResource, error)
	UpdateStatus(ctx context.Context, resourceID string, status GPUStatus) error
}

type DataProcessor interface {
	Process(ctx context.Context, payload interface{}, rules []*TransformRule) (interface{}, error)
	Validate(ctx context.Context, payload interface{}) error
	Transform(ctx context.Context, data interface{}, rule *TransformRule) (interface{}, error)
	Normalize(ctx context.Context, data interface{}, schema *Schema) (interface{}, error)
}

type InferenceGateway interface {
	Route(ctx context.Context, req *InferenceRequest) (*InferenceResponse, error)
	RegisterProvider(provider ModelProvider) error
	RemoveProvider(name string) error
	ListProviders() []string
	GetFallbackProvider(priority int) (ModelProvider, error)
}

type ModelProvider interface {
	Name() string
	Capabilities() []string
	Healthy(ctx context.Context) bool
	Infer(ctx context.Context, req *InferenceRequest) (*InferenceResponse, error)
}

type LoadBalancer interface {
	Select(ctx context.Context, providers []ModelProvider) (ModelProvider, error)
	RecordSuccess(provider ModelProvider)
	RecordFailure(provider ModelProvider)
}

type AdversarialGenerator interface {
	Generate(ctx context.Context, strategy AttackStrategy, basePrompt string) ([]*AdversarialSample, error)
	Evaluate(ctx context.Context, samples []*AdversarialSample) (*AttackEvaluation, error)
	ListStrategies() []AttackStrategy
}

type TaskScheduler interface {
	Schedule(ctx context.Context, job *ScheduledJob) (string, error)
	Unschedule(ctx context.Context, jobID string) error
	List(ctx context.Context) ([]*ScheduledJob, error)
	Trigger(ctx context.Context, jobID string) error
	Shutdown(ctx context.Context) error
}

type Logger interface {
	Debug(msg string, fields ...Field)
	Info(msg string, fields ...Field)
	Warn(msg string, fields ...Field)
	Error(msg string, fields ...Field)
	Fatal(msg string, fields ...Field)
	SetLevel(level LogLevel)
	GetLevel() LogLevel
	With(fields ...Field) Logger
	Sync() error
}

type DataRepository interface {
	Migrate(ctx context.Context, version string) error
	Rollback(ctx context.Context, version string) error
	GetSchemaVersion(ctx context.Context) (string, error)
	ListMigrations(ctx context.Context) ([]*Migration, error)
	RegisterMigration(migration *Migration) error
}

type Notifier interface {
	Send(ctx context.Context, notification *Notification) error
	AddChannel(channel NotificationChannel)
	RemoveChannel(name string)
	ListChannels() []string
}

type StorageManager interface {
	Upload(ctx context.Context, key string, data []byte, metadata map[string]string) error
	Download(ctx context.Context, key string) ([]byte, error)
	Delete(ctx context.Context, key string) error
	List(ctx context.Context, prefix string) ([]*StorageObject, error)
	SetLifecycle(ctx context.Context, rule *LifecycleRule) error
}

type PromptExperimentManager interface {
	CreateVersion(ctx context.Context, exp *PromptExperiment) (*PromptVersion, error)
	GetVersion(ctx context.Context, versionID string) (*PromptVersion, error)
	ListVersions(ctx context.Context, expID string) ([]*PromptVersion, error)
	StartABTest(ctx context.Context, config *ABTestConfig) (*ABTest, error)
	StopABTest(ctx context.Context, testID string) error
	Evaluate(ctx context.Context, testID string, metrics map[string]float64) (*ABTestResult, error)
}

type Field struct {
	Key   string
	Value interface{}
}

type LogLevel int

const (
	LogLevelDebug LogLevel = iota
	LogLevelInfo
	LogLevelWarn
	LogLevelError
	LogLevelFatal
)
