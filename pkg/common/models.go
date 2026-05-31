package common

import (
	"time"

	"github.com/google/uuid"
)

type Request struct {
	ID        string            `json:"id"`
	TraceID   string            `json:"trace_id"`
	Timestamp time.Time         `json:"timestamp"`
	Operation string            `json:"operation"`
	Payload   interface{}       `json:"payload"`
	Headers   map[string]string `json:"headers"`
	Timeout   time.Duration     `json:"timeout"`
}

type Response struct {
	RequestID string            `json:"request_id"`
	TraceID   string            `json:"trace_id"`
	Success   bool              `json:"success"`
	Code      int               `json:"code"`
	Message   string            `json:"message"`
	Data      interface{}       `json:"data"`
	Headers   map[string]string `json:"headers"`
	Duration  time.Duration     `json:"duration"`
	Error     string            `json:"error,omitempty"`
}

type Metric struct {
	Name      string            `json:"name"`
	Value     float64           `json:"value"`
	Timestamp time.Time         `json:"timestamp"`
	Labels    map[string]string `json:"labels"`
}

type LogEntry struct {
	ID        string                 `json:"id"`
	Timestamp time.Time              `json:"timestamp"`
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	TraceID   string                 `json:"trace_id"`
	Service   string                 `json:"service"`
	Fields    map[string]interface{} `json:"fields"`
}

type SLO struct {
	Name               string        `json:"name"`
	Description        string        `json:"description"`
	SLIType            string        `json:"sli_type"`
	TargetPercent      float64       `json:"target_percent"`
	Period             time.Duration `json:"period"`
	ErrorBudgetPercent float64       `json:"error_budget_percent"`
}

type SLIResult struct {
	Name        string    `json:"name"`
	Value       float64   `json:"value"`
	WindowStart time.Time `json:"window_start"`
	WindowEnd   time.Time `json:"window_end"`
	IsGood      bool      `json:"is_good"`
}

type ErrorBudgetStatus struct {
	SLOName          string    `json:"slo_name"`
	Remaining        float64   `json:"remaining"`
	Consumed         float64   `json:"consumed"`
	ConsumptionRate  float64   `json:"consumption_rate"`
	BurnRate         float64   `json:"burn_rate"`
	EstimatedBurnout time.Time `json:"estimated_burnout"`
}

type AlertRule struct {
	ID         string            `json:"id"`
	Name       string            `json:"name"`
	Expression string            `json:"expression"`
	Severity   string            `json:"severity"`
	For        time.Duration     `json:"for"`
	Labels     map[string]string `json:"labels"`
	Annotations map[string]string `json:"annotations"`
	Enabled    bool              `json:"enabled"`
	CronExpr   string            `json:"cron_expr"`
}

type Alert struct {
	ID         string            `json:"id"`
	RuleID     string            `json:"rule_id"`
	RuleName   string            `json:"rule_name"`
	Severity   string            `json:"severity"`
	Message    string            `json:"message"`
	Labels     map[string]string `json:"labels"`
	Annotations map[string]string `json:"annotations"`
	StartsAt   time.Time         `json:"starts_at"`
	EndsAt     time.Time         `json:"ends_at"`
	Status     string            `json:"status"`
}

type Task struct {
	ID           string            `json:"id"`
	Name         string            `json:"name"`
	Description  string            `json:"description"`
	Status       string            `json:"status"`
	Progress     int               `json:"progress"`
	CreatedAt    time.Time         `json:"created_at"`
	StartedAt    time.Time         `json:"started_at"`
	CompletedAt  time.Time         `json:"completed_at"`
	Error        string            `json:"error"`
	Dependencies []string          `json:"dependencies"`
	Metadata     map[string]string `json:"metadata"`
}

type BackupConfig struct {
	Source        string        `json:"source"`
	Destination   string        `json:"destination"`
	Compression   string        `json:"compression"`
	EncryptionKey string        `json:"encryption_key"`
	RetentionDays int           `json:"retention_days"`
	MaxParallel   int           `json:"max_parallel"`
}

type BackupInfo struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Source     string    `json:"source"`
	Size       int64     `json:"size"`
	CreatedAt  time.Time `json:"created_at"`
	Checksum   string    `json:"checksum"`
	Encrypted  bool      `json:"encrypted"`
	Compressed bool      `json:"compressed"`
}

type WALEntry struct {
	Sequence  int64     `json:"sequence"`
	Operation string    `json:"operation"`
	Key       string    `json:"key"`
	Data      []byte    `json:"data"`
	Timestamp time.Time `json:"timestamp"`
	Checksum  string    `json:"checksum"`
}

type Snapshot struct {
	ID        string                `json:"id"`
	CreatedAt time.Time             `json:"created_at"`
	Data      map[string]*BackupInfo `json:"data"`
	Checksum  string                `json:"checksum"`
}

type CacheEntry struct {
	Key       string      `json:"key"`
	Value     interface{} `json:"value"`
	CreatedAt time.Time   `json:"created_at"`
	ExpiresAt time.Time   `json:"expires_at"`
	HitCount  int64       `json:"hit_count"`
}

type PipelineConfig struct {
	Collectors []CollectorConfig `json:"collectors" yaml:"collectors"`
	Processors []ProcessorConfig `json:"processors" yaml:"processors"`
	Filters    []FilterConfig    `json:"filters" yaml:"filters"`
	Routers    []RouterConfig    `json:"routers" yaml:"routers"`
	Outputs    []OutputConfig    `json:"outputs" yaml:"outputs"`
}

type CollectorConfig struct {
	Type   string                 `json:"type" yaml:"type"`
	Params map[string]interface{} `json:"params" yaml:"params"`
}

type ProcessorConfig struct {
	Type   string                 `json:"type" yaml:"type"`
	Params map[string]interface{} `json:"params" yaml:"params"`
}

type FilterConfig struct {
	Type   string                 `json:"type" yaml:"type"`
	Params map[string]interface{} `json:"params" yaml:"params"`
}

type RouterConfig struct {
	Type   string                 `json:"type" yaml:"type"`
	Params map[string]interface{} `json:"params" yaml:"params"`
}

type OutputConfig struct {
	Name   string                 `json:"name" yaml:"name"`
	Type   string                 `json:"type" yaml:"type"`
	Params map[string]interface{} `json:"params" yaml:"params"`
}

func NewID() string {
	return uuid.New().String()
}

func GenerateTraceID() string {
	return uuid.New().String()
}
