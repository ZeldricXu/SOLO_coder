package models

import (
	"encoding/json"
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type" gorm:"index"`
	Status     string                 `json:"status" gorm:"index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace" gorm:"index:idx_namespace_version,unique"`
	Version    int64                  `json:"version" gorm:"index:idx_namespace_version,unique"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled" gorm:"default:true"`
	AppliedAt  *time.Time             `json:"applied_at"`
	CreatedAt  time.Time              `json:"created_at"`
}

type RunInstance struct {
	RunID       string     `json:"run_id" gorm:"primaryKey"`
	EntityID    string     `json:"entity_id" gorm:"index"`
	Phase       string     `json:"phase" gorm:"index"`
	Progress    float64    `json:"progress" gorm:"default:0"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at"`
	ErrorDetail *string    `json:"error_detail"`
	CreatedAt   time.Time  `json:"created_at"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
}

type ResourceRequest struct {
	Type   string                 `json:"type" binding:"required"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type ResourceResponse struct {
	Code    int                    `json:"code"`
	Message string                 `json:"message,omitempty"`
	Data    map[string]interface{} `json:"data,omitempty"`
}

type BatchOperation struct {
	Action string   `json:"action" binding:"required"`
	IDs    []string `json:"ids" binding:"required"`
}

type BatchResponse struct {
	Code    int                      `json:"code"`
	Message string                   `json:"message,omitempty"`
	Data    BatchResponseData        `json:"data,omitempty"`
}

type BatchResponseData struct {
	BatchID string                   `json:"batch_id"`
	Results []map[string]interface{} `json:"results"`
}

type DataQualityRule struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Table       string                 `json:"table" gorm:"index"`
	Column      string                 `json:"column"`
	RuleType    string                 `json:"rule_type"`
	Condition   string                 `json:"condition"`
	Params      map[string]interface{} `json:"params" gorm:"serializer:json"`
	Enabled     bool                   `json:"enabled" gorm:"default:true"`
	CronExpr    string                 `json:"cron_expr"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type QualityCheckResult struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	RuleID     string                 `json:"rule_id" gorm:"index"`
	Status     string                 `json:"status"`
	TotalRows  int64                  `json:"total_rows"`
	BadRows    int64                  `json:"bad_rows"`
	ErrorRate  float64                `json:"error_rate"`
	SampleData json.RawMessage        `json:"sample_data" gorm:"type:json"`
	CheckedAt  time.Time              `json:"checked_at" gorm:"index"`
	Duration   time.Duration          `json:"duration"`
	Message    string                 `json:"message"`
}

type TimeSeriesPoint struct {
	Timestamp time.Time              `json:"ts"`
	Value     float64                `json:"v"`
	Tags      map[string]string      `json:"tags,omitempty"`
}

type CompressedBlock struct {
	ID         string    `json:"id"`
	Metric     string    `json:"metric"`
	StartTime  time.Time `json:"start_time"`
	EndTime    time.Time `json:"end_time"`
	Resolution string    `json:"resolution"`
	Data       []byte    `json:"data"`
	Count      int       `json:"count"`
	Min        float64   `json:"min"`
	Max        float64   `json:"max"`
	Sum        float64   `json:"sum"`
}

type StoredFile struct {
	ID            string    `json:"id" gorm:"primaryKey"`
	Path          string    `json:"path" gorm:"uniqueIndex"`
	Size          int64     `json:"size"`
	ContentType   string    `json:"content_type"`
	MD5           string    `json:"md5"`
	StorageClass  string    `json:"storage_class" gorm:"default:'standard'"`
	LifecycleDays int       `json:"lifecycle_days" gorm:"default:30"`
	CreatedAt     time.Time `json:"created_at"`
	LastAccessed  time.Time `json:"last_accessed"`
	Archived      bool      `json:"archived" gorm:"default:false"`
}

type CDCEvent struct {
	ID         string                 `json:"id"`
	EventType  string                 `json:"event_type"`
	Database   string                 `json:"database"`
	Table      string                 `json:"table"`
	PrimaryKey map[string]interface{} `json:"primary_key"`
	OldData    map[string]interface{} `json:"old_data,omitempty"`
	NewData    map[string]interface{} `json:"new_data,omitempty"`
	Timestamp  time.Time              `json:"timestamp"`
	LSN        string                 `json:"lsn"`
}

type RequestLog struct {
	TraceID    string    `json:"trace_id"`
	Method     string    `json:"method"`
	Path       string    `json:"path"`
	StatusCode int       `json:"status_code"`
	Duration   int64     `json:"duration_ms"`
	ClientIP   string    `json:"client_ip"`
	UserAgent  string    `json:"user_agent"`
	RequestAt  time.Time `json:"request_at"`
	Error      string    `json:"error,omitempty"`
}
