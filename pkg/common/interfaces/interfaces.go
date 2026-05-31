package interfaces

import (
	"context"
	"github.com/solocoder/session136/pkg/common/models"
)

type Classifier interface {
	Classify(ctx context.Context, data map[string]interface{}) (*ClassificationResult, error)
	ApplyPolicy(ctx context.Context, result *ClassificationResult) error
	Scan(ctx context.Context, data []map[string]interface{}) ([]*ClassificationResult, error)
}

type ClassificationResult struct {
	DataID      string
	Sensitivity string
	Category    string
	Level       int
	Policy      string
	Fields      []SensitiveField
}

type SensitiveField struct {
	Name        string
	Type        string
	Sensitivity string
	Masked      bool
}

type PrivacyInjector interface {
	InjectNoise(ctx context.Context, queryResult *QueryResult) (*QueryResult, error)
	ConsumeBudget(ctx context.Context, budget float64) error
	GetRemainingBudget(ctx context.Context) float64
	ResetBudget(ctx context.Context) error
}

type QueryResult struct {
	Data      []map[string]interface{}
	NoiseType string
	Epsilon   float64
	Delta     float64
}

type Monitor interface {
	RecordMetric(ctx context.Context, name string, value float64, dimensions map[string]string)
	GetMetrics(ctx context.Context, name string, startTime, endTime int64) []*models.MetricsSnapshot
	Aggregate(ctx context.Context, metricName string, aggType string, dimensions map[string]string) (float64, error)
	Flush(ctx context.Context) error
}

type Scheduler interface {
	SubmitTask(ctx context.Context, task *Task) (string, error)
	GetTaskStatus(ctx context.Context, taskID string) (*TaskStatus, error)
	CancelTask(ctx context.Context, taskID string) error
	WatchTask(ctx context.Context, taskID string) (<-chan *TaskStatus, error)
}

type Task struct {
	ID       string
	Type     string
	Payload  interface{}
	Priority int
	Timeout  int
}

type TaskStatus struct {
	TaskID    string
	Status    string
	Progress  float64
	Error     string
	StartedAt int64
	EndAt     int64
}

type Notifier interface {
	Send(ctx context.Context, notification *Notification) error
	AddChannel(channel NotificationChannel)
	RenderTemplate(ctx context.Context, templateID string, data map[string]interface{}) (string, error)
}

type Notification struct {
	TemplateID string
	Channel    string
	Recipients []string
	Data       map[string]interface{}
}

type NotificationChannel interface {
	Send(ctx context.Context, notification *Notification) error
	GetName() string
}

type APIGateway interface {
	HandleRequest(ctx context.Context, req *GatewayRequest) (*GatewayResponse, error)
	AddMiddleware(middleware Middleware)
	LogRequest(ctx context.Context, req *GatewayRequest, resp *GatewayResponse, duration int64)
}

type GatewayRequest struct {
	ID        string
	Path      string
	Method    string
	Headers   map[string]string
	Body      interface{}
	TraceID   string
	UserID    string
	Timestamp int64
}

type GatewayResponse struct {
	StatusCode int
	Headers    map[string]string
	Body       interface{}
	TraceID    string
}

type Middleware interface {
	Handle(ctx context.Context, req *GatewayRequest, next HandlerFunc) (*GatewayResponse, error)
}

type HandlerFunc func(ctx context.Context, req *GatewayRequest) (*GatewayResponse, error)

type StorageManager interface {
	Store(ctx context.Context, object *StorageObject) (string, error)
	Retrieve(ctx context.Context, objectID string) (*StorageObject, error)
	Delete(ctx context.Context, objectID string) error
	List(ctx context.Context, prefix string) ([]*StorageObject, error)
	IndexMetadata(ctx context.Context, metadata map[string]interface{}) error
	SearchByMetadata(ctx context.Context, query map[string]interface{}) ([]*StorageObject, error)
}

type StorageObject struct {
	ID       string
	Key      string
	Size     int64
	Checksum string
	Metadata map[string]interface{}
	Data     []byte
}

type FederatedCoordinator interface {
	DistributeTask(ctx context.Context, task *FLTask) error
	AggregateGradients(ctx context.Context, gradients []*Gradient) (*ModelUpdate, error)
	UpdateGlobalModel(ctx context.Context, update *ModelUpdate) error
	GetClientStatus(ctx context.Context, clientID string) (*ClientStatus, error)
}

type FLTask struct {
	TaskID    string
	ModelID   string
	Config    map[string]interface{}
	ClientIDs []string
}

type Gradient struct {
	ClientID string
	TaskID   string
	Weights  []float64
	Nonce    string
}

type ModelUpdate struct {
	ModelID    string
	Version    int
	Weights    []float64
	UpdateTime int64
}

type ClientStatus struct {
	ClientID    string
	Status      string
	LastHeartbeat int64
	CurrentTask string
}

type AuditLogger interface {
	Log(ctx context.Context, entry *AuditEntry) error
	VerifyIntegrity(ctx context.Context, startIndex, endIndex int) (bool, error)
	GetEntry(ctx context.Context, index int) (*AuditEntry, error)
	DetectTampering(ctx context.Context) ([]int, error)
}

type AuditEntry struct {
	Index     int
	Timestamp int64
	UserID    string
	Action    string
	Resource  string
	Payload   string
	PrevHash  string
	Hash      string
	Signature string
}

type DataMasker interface {
	Mask(ctx context.Context, data map[string]interface{}, userRoles []string) (map[string]interface{}, error)
	MaskBatch(ctx context.Context, data []map[string]interface{}, userRoles []string) ([]map[string]interface{}, error)
	RegisterRule(rule MaskingRule)
	RemoveRule(fieldName string)
}

type MaskingRule struct {
	FieldName       string
	Sensitivity     string
	AllowedRoles    []string
	MaskingStrategy string
	MaskChar        string
	KeepPrefix      int
	KeepSuffix      int
}
