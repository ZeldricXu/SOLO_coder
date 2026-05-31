package domain

import (
	"time"
)

type Entity struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string    `json:"run_id"`
	EntityID    string    `json:"entity_id"`
	Phase       string    `json:"phase"`
	Progress    float64   `json:"progress"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at"`
	ErrorDetail *string   `json:"error_detail"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics"`
	Dimensions map[string]string      `json:"dimensions"`
}

type Task struct {
	ID             string                 `json:"id"`
	Name           string                 `json:"name"`
	Type           string                 `json:"type"`
	Status         string                 `json:"status"`
	Priority       int                    `json:"priority"`
	Parameters     map[string]interface{} `json:"parameters"`
	CreatedAt      time.Time              `json:"created_at"`
	StartedAt      *time.Time             `json:"started_at"`
	CompletedAt    *time.Time             `json:"completed_at"`
	Error          *string                `json:"error"`
	RetryCount     int                    `json:"retry_count"`
	MaxRetries     int                    `json:"max_retries"`
	TimeoutSeconds int                    `json:"timeout_seconds"`
}

type LogEntry struct {
	Timestamp time.Time              `json:"timestamp"`
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	TraceID   string                 `json:"trace_id"`
	Fields    map[string]interface{} `json:"fields"`
}

type RequestLog struct {
	TraceID    string                 `json:"trace_id"`
	Method     string                 `json:"method"`
	Path       string                 `json:"path"`
	StatusCode int                    `json:"status_code"`
	DurationMs int64                  `json:"duration_ms"`
	ClientIP   string                 `json:"client_ip"`
	UserAgent  string                 `json:"user_agent"`
	RequestID  string                 `json:"request_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Headers    map[string]string      `json:"headers"`
}

type Document struct {
	ID        string                 `json:"id"`
	Content   string                 `json:"content"`
	Metadata  map[string]interface{} `json:"metadata"`
	Format    string                 `json:"format"`
	Chunks    []DocumentChunk        `json:"chunks"`
	CreatedAt time.Time              `json:"created_at"`
}

type DocumentChunk struct {
	ID        string    `json:"id"`
	Content   string    `json:"content"`
	Embedding []float64 `json:"embedding"`
	Index     int       `json:"index"`
	StartPos  int       `json:"start_pos"`
	EndPos    int       `json:"end_pos"`
}

type PromptVersion struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Content     string                 `json:"content"`
	Version     string                 `json:"version"`
	Variables   map[string]interface{} `json:"variables"`
	CreatedAt   time.Time              `json:"created_at"`
	CreatedBy   string                 `json:"created_by"`
	Description string                 `json:"description"`
}

type ABExperiment struct {
	ID            string                 `json:"id"`
	Name          string                 `json:"name"`
	Description   string                 `json:"description"`
	PromptA       string                 `json:"prompt_a"`
	PromptB       string                 `json:"prompt_b"`
	TrafficSplit  float64                `json:"traffic_split"`
	Status        string                 `json:"status"`
	Metrics       map[string]float64     `json:"metrics"`
	StartDate     time.Time              `json:"start_date"`
	EndDate       *time.Time             `json:"end_date"`
	CreatedAt     time.Time              `json:"created_at"`
}

type GPUResource struct {
	ID          string            `json:"id"`
	NodeID      string            `json:"node_id"`
	DeviceIndex int               `json:"device_index"`
	TotalMemory int64             `json:"total_memory"`
	UsedMemory  int64             `json:"used_memory"`
	Utilization float64           `json:"utilization"`
	Status      string            `json:"status"`
	Labels      map[string]string `json:"labels"`
}

type GPUTask struct {
	ID             string            `json:"id"`
	Name           string            `json:"name"`
	Priority       int               `json:"priority"`
	RequiredMemory int64             `json:"required_memory"`
	Status         string            `json:"status"`
	AssignedGPU    string            `json:"assigned_gpu"`
	QueueTime      time.Time         `json:"queue_time"`
	StartTime      *time.Time        `json:"start_time"`
	EndTime        *time.Time        `json:"end_time"`
	Labels         map[string]string `json:"labels"`
}

type DataRecord struct {
	ID         string                 `json:"id"`
	RawData    map[string]interface{} `json:"raw_data"`
	Normalized map[string]interface{} `json:"normalized"`
	Schema     string                 `json:"schema"`
	Timestamp  time.Time              `json:"timestamp"`
	Source     string                 `json:"source"`
}
