package model

import (
	"time"
)

type Environment struct {
	ID            string                 `gorm:"primaryKey;column:id" json:"id"`
	Name          string                 `gorm:"column:name;uniqueIndex" json:"name"`
	Type          string                 `gorm:"column:type;index" json:"type"`
	Status        string                 `gorm:"column:status;index" json:"status"`
	Owner         string                 `gorm:"column:owner;index" json:"owner"`
	ProjectID     string                 `gorm:"column:project_id;index" json:"project_id"`
	Configuration map[string]interface{} `gorm:"column:configuration;type:jsonb" json:"configuration"`
	TTL           *time.Duration         `gorm:"column:ttl" json:"ttl"`
	AutoReclaimAt *time.Time             `gorm:"column:auto_reclaim_at" json:"auto_reclaim_at"`
	Resources     map[string]string      `gorm:"column:resources;type:jsonb" json:"resources"`
	CreatedAt     time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt     time.Time              `gorm:"column:updated_at" json:"updated_at"`
	LastActiveAt  *time.Time             `gorm:"column:last_active_at" json:"last_active_at"`
}

func (Environment) TableName() string {
	return "environments"
}

type EnvironmentUsage struct {
	ID            string    `gorm:"primaryKey;column:id" json:"id"`
	EnvironmentID string    `gorm:"column:environment_id;index" json:"environment_id"`
	ResourceType  string    `gorm:"column:resource_type;index" json:"resource_type"`
	UsageValue    float64   `gorm:"column:usage_value" json:"usage_value"`
	RecordedAt    time.Time `gorm:"column:recorded_at;index" json:"recorded_at"`
}

func (EnvironmentUsage) TableName() string {
	return "environment_usage"
}

type CreateEnvironmentRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Type          string                 `json:"type" binding:"required"`
	Owner         string                 `json:"owner" binding:"required"`
	ProjectID     string                 `json:"project_id" binding:"required"`
	Configuration map[string]interface{} `json:"configuration"`
	TTLHours      int                    `json:"ttl_hours"`
}

type EnvironmentStatusResponse struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Type          string     `json:"type"`
	Status        string     `json:"status"`
	Owner         string     `json:"owner"`
	AutoReclaimAt *time.Time `json:"auto_reclaim_at"`
	CreatedAt     time.Time  `json:"created_at"`
	LastActiveAt  *time.Time `json:"last_active_at"`
}

type UsageStatisticsRequest struct {
	EnvironmentID string `form:"environment_id" binding:"required"`
	ResourceType  string `form:"resource_type"`
	StartTime     string `form:"start_time"`
	EndTime       string `form:"end_time"`
}

type UsageStatisticsResponse struct {
	EnvironmentID string             `json:"environment_id"`
	ResourceType  string             `json:"resource_type"`
	Average       float64            `json:"average"`
	Peak          float64            `json:"peak"`
	Records       []EnvironmentUsage `json:"records"`
	StartTime     time.Time          `json:"start_time"`
	EndTime       time.Time          `json:"end_time"`
}

// ===== 监控增强模型

type EnvironmentHealth struct {
	EnvID     string            `json:"env_id"`
	Name      string            `json:"name"`
	Status    string            `json:"status"`
	Healthy   bool              `json:"healthy"`
	LastCheck time.Time         `json:"last_check"`
	Checks    []HealthCheckItem `json:"checks"`
	UptimeSec int64             `json:"uptime_sec"`
}

type HealthCheckItem struct {
	Name    string `json:"name"`
	Healthy bool   `json:"healthy"`
	Detail  string `json:"detail,omitempty"`
}

type EnvironmentTiming struct {
	EnvID     string            `json:"env_id"`
	Operation string            `json:"operation"`
	TotalMs   int64             `json:"total_ms"`
	Breakdown []TimingBreakdown `json:"breakdown"`
	StartTs   time.Time         `json:"start_ts"`
	EndTs     time.Time         `json:"end_ts"`
}

type TimingBreakdown struct {
	Step       string  `json:"step"`
	DurationMs int64   `json:"duration_ms"`
	Percent    float64 `json:"percent"`
}

type ResourceUsageSummary struct {
	EnvironmentID   string    `json:"environment_id"`
	ResourceType    string    `json:"resource_type"`
	Average         float64   `json:"average"`
	Peak            float64   `json:"peak"`
	Minimum         float64   `json:"minimum"`
	StdDev          float64   `json:"std_dev"`
	SampleCount     int       `json:"sample_count"`
	LastSampleValue float64   `json:"last_sample_value"`
	LastSampleTs    time.Time `json:"last_sample_ts"`
}

type EnvironmentStats struct {
	TotalEnvs    int     `json:"total_envs"`
	RunningEnvs  int     `json:"running_envs"`
	StoppedEnvs  int     `json:"stopped_envs"`
	ExpiringSoon int     `json:"expiring_soon"`
	Reclaimed24h int     `json:"reclaimed_24h"`
	AvgUptimeMin float64 `json:"avg_uptime_min"`
}

type EnvironmentLifecycleEvent struct {
	ID        string    `json:"id"`
	EnvID     string    `json:"env_id"`
	EventType string    `json:"event_type"`
	Detail    string    `json:"detail,omitempty"`
	Timestamp time.Time `json:"timestamp"`
}
