package types

import (
	"time"

	"github.com/google/uuid"
	"github.com/oklog/ulid/v2"
)

type ID string

func NewID() ID {
	return ID(ulid.Make().String())
}

func NewUUID() string {
	return uuid.NewString()
}

type PipelineStatus string

const (
	PipelineStatusActive   PipelineStatus = "active"
	PipelineStatusInactive PipelineStatus = "inactive"
	PipelineStatusPaused   PipelineStatus = "paused"
)

type ExecutionStatus string

const (
	ExecutionStatusPending   ExecutionStatus = "pending"
	ExecutionStatusQueued    ExecutionStatus = "queued"
	ExecutionStatusRunning   ExecutionStatus = "running"
	ExecutionStatusSuccess   ExecutionStatus = "success"
	ExecutionStatusFailed    ExecutionStatus = "failed"
	ExecutionStatusCancelled ExecutionStatus = "cancelled"
	ExecutionStatusSkipped   ExecutionStatus = "skipped"
	ExecutionStatusTimeout   ExecutionStatus = "timeout"
)

type StageStatus string

const (
	StageStatusPending   StageStatus = "pending"
	StageStatusRunning   StageStatus = "running"
	StageStatusSuccess   StageStatus = "success"
	StageStatusFailed    StageStatus = "failed"
	StageStatusCancelled StageStatus = "cancelled"
	StageStatusSkipped   StageStatus = "skipped"
	StageStatusTimeout   StageStatus = "timeout"
)

type StageType string

const (
	StageTypeScan    StageType = "scan"
	StageTypeBuild   StageType = "build"
	StageTypeTest    StageType = "test"
	StageTypeDeploy  StageType = "deploy"
	StageTypeCustom  StageType = "custom"
)

type EventSource string

const (
	EventSourceGitHub   EventSource = "github"
	EventSourceGitLab   EventSource = "gitlab"
	EventSourceCron     EventSource = "cron"
	EventSourceManual   EventSource = "manual"
	EventSourceAPI      EventSource = "api"
	EventSourceWebhook  EventSource = "webhook"
)

type EventType string

const (
	EventTypePush        EventType = "push"
	EventTypePullRequest EventType = "pull_request"
	EventTypeTag         EventType = "tag"
	EventTypeRelease     EventType = "release"
	EventTypeSchedule    EventType = "schedule"
	EventTypeManual      EventType = "manual"
)

type NotificationChannel string

const (
	NotificationChannelDingTalk NotificationChannel = "dingtalk"
	NotificationChannelFeiShu   NotificationChannel = "feishu"
	NotificationChannelSlack    NotificationChannel = "slack"
	NotificationChannelEmail    NotificationChannel = "email"
)

type NotificationSeverity string

const (
	NotificationSeverityInfo     NotificationSeverity = "info"
	NotificationSeverityWarning  NotificationSeverity = "warning"
	NotificationSeverityError    NotificationSeverity = "error"
	NotificationSeverityCritical NotificationSeverity = "critical"
)

type SecretSource string

const (
	SecretSourceVault SecretSource = "vault"
	SecretSourceEnv   SecretSource = "env"
)

type ArtifactStatus string

const (
	ArtifactStatusUploading ArtifactStatus = "uploading"
	ArtifactStatusUploaded  ArtifactStatus = "uploaded"
	ArtifactStatusFailed    ArtifactStatus = "failed"
	ArtifactStatusExpired   ArtifactStatus = "expired"
)

type PluginStatus string

const (
	PluginStatusActive    PluginStatus = "active"
	PluginStatusInactive  PluginStatus = "inactive"
	PluginStatusError     PluginStatus = "error"
)

type ResourceQuota struct {
	CPU    string `json:"cpu" yaml:"cpu"`
	Memory string `json:"memory" yaml:"memory"`
	Disk   string `json:"disk,omitempty" yaml:"disk,omitempty"`
}

type TimeWindow struct {
	Start string `json:"start" yaml:"start"`
	End   string `json:"end" yaml:"end"`
}

type RetentionPolicy struct {
	Days    int  `json:"days" yaml:"days"`
	KeepLast *int `json:"keep_last,omitempty" yaml:"keep_last,omitempty"`
}

type Variable struct {
	Name  string `json:"name" yaml:"name"`
	Value string `json:"value,omitempty" yaml:"value,omitempty"`
	Secret bool   `json:"secret,omitempty" yaml:"secret,omitempty"`
}

type EnvVar struct {
	Name  string `json:"name" yaml:"name"`
	Value string `json:"value,omitempty" yaml:"value,omitempty"`
	From  string `json:"from,omitempty" yaml:"from,omitempty"`
}

type WebhookConfig struct {
	URL    string            `json:"url" yaml:"url"`
	Secret string            `json:"secret,omitempty" yaml:"secret,omitempty"`
	Events []string          `json:"events,omitempty" yaml:"events,omitempty"`
	Headers map[string]string `json:"headers,omitempty" yaml:"headers,omitempty"`
}

type TriggerCondition struct {
	Branch     string   `json:"branch,omitempty" yaml:"branch,omitempty"`
	Tags       []string `json:"tags,omitempty" yaml:"tags,omitempty"`
	Paths      []string `json:"paths,omitempty" yaml:"paths,omitempty"`
	EventTypes []string `json:"event_types,omitempty" yaml:"event_types,omitempty"`
}

type CronTrigger struct {
	Schedule    string            `json:"schedule" yaml:"schedule"`
	Timezone    string            `json:"timezone,omitempty" yaml:"timezone,omitempty"`
	Variables   map[string]string `json:"variables,omitempty" yaml:"variables,omitempty"`
	Description string            `json:"description,omitempty" yaml:"description,omitempty"`
}

type NotificationRule struct {
	Events    []EventType           `json:"events" yaml:"events"`
	Channels  []NotificationChannel `json:"channels" yaml:"channels"`
	Severity  NotificationSeverity  `json:"severity" yaml:"severity"`
	Condition *TriggerCondition     `json:"condition,omitempty" yaml:"condition,omitempty"`
	Template  string                `json:"template,omitempty" yaml:"template,omitempty"`
}

type PipelineTrigger struct {
	EventSource  EventSource        `json:"event_source" yaml:"event_source"`
	EventType    EventType          `json:"event_type" yaml:"event_type"`
	Webhook      *WebhookConfig     `json:"webhook,omitempty" yaml:"webhook,omitempty"`
	Cron         *CronTrigger       `json:"cron,omitempty" yaml:"cron,omitempty"`
	Condition    *TriggerCondition  `json:"condition,omitempty" yaml:"condition,omitempty"`
}

type PluginReference struct {
	Name    string                 `json:"name" yaml:"name"`
	Version string                 `json:"version,omitempty" yaml:"version,omitempty"`
	Config  map[string]interface{} `json:"config,omitempty" yaml:"config,omitempty"`
}

type StageDefinition struct {
	Name         string                 `json:"name" yaml:"name"`
	Type         StageType              `json:"type" yaml:"type"`
	Description  string                 `json:"description,omitempty" yaml:"description,omitempty"`
	DependsOn    []string               `json:"depends_on,omitempty" yaml:"depends_on,omitempty"`
	Plugin       *PluginReference       `json:"plugin,omitempty" yaml:"plugin,omitempty"`
	Commands     []string               `json:"commands,omitempty" yaml:"commands,omitempty"`
	Image        string                 `json:"image,omitempty" yaml:"image,omitempty"`
	Env          []EnvVar               `json:"env,omitempty" yaml:"env,omitempty"`
	Secrets      []string               `json:"secrets,omitempty" yaml:"secrets,omitempty"`
	Resources    *ResourceQuota         `json:"resources,omitempty" yaml:"resources,omitempty"`
	Timeout      *int64                 `json:"timeout,omitempty" yaml:"timeout,omitempty"`
	AllowFailure bool                   `json:"allow_failure,omitempty" yaml:"allow_failure,omitempty"`
	Condition    string                 `json:"condition,omitempty" yaml:"condition,omitempty"`
	Retry        *RetryPolicy           `json:"retry,omitempty" yaml:"retry,omitempty"`
	Artifacts    *ArtifactConfig        `json:"artifacts,omitempty" yaml:"artifacts,omitempty"`
}

type RetryPolicy struct {
	MaxAttempts int `json:"max_attempts" yaml:"max_attempts"`
	Backoff     int `json:"backoff,omitempty" yaml:"backoff,omitempty"`
}

type ArtifactConfig struct {
	Paths       []string `json:"paths" yaml:"paths"`
	Name        string   `json:"name,omitempty" yaml:"name,omitempty"`
	Retention   *int     `json:"retention_days,omitempty" yaml:"retention_days,omitempty"`
	Compression string   `json:"compression,omitempty" yaml:"compression,omitempty"`
}

type PipelineDefinition struct {
	Name          string               `json:"name" yaml:"name"`
	Version       string               `json:"version,omitempty" yaml:"version,omitempty"`
	Description   string               `json:"description,omitempty" yaml:"description,omitempty"`
	Global        *GlobalConfig        `json:"global,omitempty" yaml:"global,omitempty"`
	Triggers      []PipelineTrigger    `json:"triggers,omitempty" yaml:"triggers,omitempty"`
	Stages        []StageDefinition    `json:"stages" yaml:"stages"`
	Notifications []NotificationRule   `json:"notifications,omitempty" yaml:"notifications,omitempty"`
	Variables     []Variable           `json:"variables,omitempty" yaml:"variables,omitempty"`
	Secrets       []string             `json:"secrets,omitempty" yaml:"secrets,omitempty"`
	Retention     *RetentionPolicy     `json:"retention,omitempty" yaml:"retention,omitempty"`
}

type GlobalConfig struct {
	Image      string            `json:"image,omitempty" yaml:"image,omitempty"`
	Env        []EnvVar          `json:"env,omitempty" yaml:"env,omitempty"`
	Resources  *ResourceQuota    `json:"resources,omitempty" yaml:"resources,omitempty"`
	Timeout    *int64            `json:"timeout,omitempty" yaml:"timeout,omitempty"`
	Retry      *RetryPolicy      `json:"retry,omitempty" yaml:"retry,omitempty"`
	Concurrency *ConcurrencyConfig `json:"concurrency,omitempty" yaml:"concurrency,omitempty"`
}

type ConcurrencyConfig struct {
	MaxParallel int    `json:"max_parallel" yaml:"max_parallel"`
	Strategy    string `json:"strategy,omitempty" yaml:"strategy,omitempty"`
}

type InternalEvent struct {
	ID            ID                 `json:"id"`
	EventSource   EventSource        `json:"event_source"`
	EventType     EventType          `json:"event_type"`
	PipelineID    ID                 `json:"pipeline_id,omitempty"`
	ProjectID     string             `json:"project_id,omitempty"`
	Commit        string             `json:"commit,omitempty"`
	Branch        string             `json:"branch,omitempty"`
	Tag           string             `json:"tag,omitempty"`
	Ref           string             `json:"ref,omitempty"`
	Message       string             `json:"message,omitempty"`
	Author        string             `json:"author,omitempty"`
	AuthorEmail   string             `json:"author_email,omitempty"`
	Payload       map[string]interface{} `json:"payload,omitempty"`
	ReceivedAt    time.Time          `json:"received_at"`
	ProcessedAt   *time.Time         `json:"processed_at,omitempty"`
	DeduplicationKey string          `json:"deduplication_key,omitempty"`
}

type ExecutionContext struct {
	ExecutionID    ID            `json:"execution_id"`
	PipelineID     ID            `json:"pipeline_id"`
	PipelineName   string        `json:"pipeline_name"`
	Event          *InternalEvent `json:"event"`
	Variables      map[string]string `json:"variables"`
	Secrets        map[string]string `json:"-"`
	WorkingDir     string        `json:"working_dir"`
	Env            map[string]string `json:"env"`
}

type LogEntry struct {
	ID          ID        `json:"id"`
	ExecutionID ID        `json:"execution_id"`
	StageID     ID        `json:"stage_id,omitempty"`
	Timestamp   time.Time `json:"timestamp"`
	Level       string    `json:"level"`
	Message     string    `json:"message"`
	Stream      string    `json:"stream,omitempty"`
	LineNumber  int64     `json:"line_number,omitempty"`
}

type Artifact struct {
	ID            ID             `json:"id"`
	ExecutionID   ID             `json:"execution_id"`
	StageID       ID             `json:"stage_id"`
	Name          string         `json:"name"`
	Path          string         `json:"path"`
	Size          int64          `json:"size"`
	ContentType   string         `json:"content_type"`
	Status        ArtifactStatus `json:"status"`
	StorageKey    string         `json:"storage_key"`
	DownloadURL   string         `json:"download_url,omitempty"`
	UploadedAt    *time.Time     `json:"uploaded_at,omitempty"`
	ExpiresAt     *time.Time     `json:"expires_at,omitempty"`
}

type SecretAuditLog struct {
	ID          ID        `json:"id"`
	SecretName  string    `json:"secret_name"`
	ExecutionID ID        `json:"execution_id"`
	PipelineID  ID        `json:"pipeline_id"`
	StageName   string    `json:"stage_name"`
	RequestedBy string    `json:"requested_by"`
	RequestedAt time.Time `json:"requested_at"`
	Success     bool      `json:"success"`
	Reason      string    `json:"reason,omitempty"`
}
