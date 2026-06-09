package models

import (
	"encoding/json"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

type Pipeline struct {
	ID          types.ID         `gorm:"type:varchar(26);primaryKey" json:"id"`
	Name        string           `gorm:"type:varchar(255);not null;index" json:"name"`
	ProjectID   string           `gorm:"type:varchar(100);not null;index" json:"project_id"`
	Description string           `gorm:"type:text" json:"description,omitempty"`
	Definition  datatypes.JSON   `gorm:"type:jsonb;not null" json:"definition"`
	Status      types.PipelineStatus `gorm:"type:varchar(20);not null;default:'active'" json:"status"`
	Version     int              `gorm:"type:integer;not null;default:1" json:"version"`
	Labels      datatypes.JSON   `gorm:"type:jsonb" json:"labels,omitempty"`
	CreatedBy   string           `gorm:"type:varchar(100)" json:"created_by,omitempty"`
	UpdatedBy   string           `gorm:"type:varchar(100)" json:"updated_by,omitempty"`
	CreatedAt   time.Time        `gorm:"autoCreateTime" json:"created_at"`
	UpdatedAt   time.Time        `gorm:"autoUpdateTime" json:"updated_at"`
	DeletedAt   gorm.DeletedAt   `gorm:"index" json:"-"`
}

func (p *Pipeline) GetDefinition() (*types.PipelineDefinition, error) {
	var def types.PipelineDefinition
	if err := json.Unmarshal(p.Definition, &def); err != nil {
		return nil, err
	}
	return &def, nil
}

func (p *Pipeline) SetDefinition(def *types.PipelineDefinition) error {
	data, err := json.Marshal(def)
	if err != nil {
		return err
	}
	p.Definition = datatypes.JSON(data)
	return nil
}

type PipelineExecution struct {
	ID            types.ID              `gorm:"type:varchar(26);primaryKey" json:"id"`
	PipelineID    types.ID              `gorm:"type:varchar(26);not null;index" json:"pipeline_id"`
	PipelineName  string                `gorm:"type:varchar(255);not null" json:"pipeline_name"`
	Status        types.ExecutionStatus `gorm:"type:varchar(20);not null;default:'pending';index" json:"status"`
	ProjectID     string                `gorm:"type:varchar(100);not null;index" json:"project_id"`
	TriggerSource types.EventSource     `gorm:"type:varchar(20);not null" json:"trigger_source"`
	TriggerType   types.EventType       `gorm:"type:varchar(30);not null" json:"trigger_type"`
	Commit        string                `gorm:"type:varchar(64)" json:"commit,omitempty"`
	Branch        string                `gorm:"type:varchar(255);index" json:"branch,omitempty"`
	Tag           string                `gorm:"type:varchar(100);index" json:"tag,omitempty"`
	Ref           string                `gorm:"type:varchar(255)" json:"ref,omitempty"`
	Message       string                `gorm:"type:text" json:"message,omitempty"`
	Author        string                `gorm:"type:varchar(255)" json:"author,omitempty"`
	AuthorEmail   string                `gorm:"type:varchar(255)" json:"author_email,omitempty"`
	EventID       types.ID              `gorm:"type:varchar(26);index" json:"event_id,omitempty"`
	Variables     datatypes.JSON        `gorm:"type:jsonb" json:"variables,omitempty"`
	Parameters    datatypes.JSON        `gorm:"type:jsonb" json:"parameters,omitempty"`
	QueuedAt      *time.Time            `json:"queued_at,omitempty"`
	StartedAt     *time.Time            `json:"started_at,omitempty"`
	CompletedAt   *time.Time            `json:"completed_at,omitempty"`
	DurationSec   *int64                `json:"duration_sec,omitempty"`
	TimeoutAt     *time.Time            `json:"timeout_at,omitempty"`
	CancelRequested bool                `gorm:"default:false" json:"cancel_requested,omitempty"`
	CanceledAt    *time.Time            `json:"canceled_at,omitempty"`
	CanceledBy    string                `gorm:"type:varchar(100)" json:"canceled_by,omitempty"`
	Error         string                `gorm:"type:text" json:"error,omitempty"`
	Stages        []StageExecution      `gorm:"foreignKey:ExecutionID" json:"stages,omitempty"`
	CreatedAt     time.Time             `gorm:"autoCreateTime" json:"created_at"`
	UpdatedAt     time.Time             `gorm:"autoUpdateTime" json:"updated_at"`
}

type StageExecution struct {
	ID            types.ID            `gorm:"type:varchar(26);primaryKey" json:"id"`
	ExecutionID   types.ID            `gorm:"type:varchar(26);not null;index" json:"execution_id"`
	StageName     string              `gorm:"type:varchar(255);not null" json:"stage_name"`
	StageType     types.StageType     `gorm:"type:varchar(20);not null" json:"stage_type"`
	Status        types.StageStatus   `gorm:"type:varchar(20);not null;default:'pending';index" json:"status"`
	PluginName    string              `gorm:"type:varchar(100)" json:"plugin_name,omitempty"`
	PluginVersion string              `gorm:"type:varchar(50)" json:"plugin_version,omitempty"`
	Image         string              `gorm:"type:varchar(500)" json:"image,omitempty"`
	DependsOn     datatypes.JSON      `gorm:"type:jsonb" json:"depends_on,omitempty"`
	Env           datatypes.JSON      `gorm:"type:jsonb" json:"env,omitempty"`
	Commands      datatypes.JSON      `gorm:"type:jsonb" json:"commands,omitempty"`
	Attempt       int                 `gorm:"default:1" json:"attempt"`
	MaxAttempts   int                 `gorm:"default:1" json:"max_attempts"`
	AllowFailure  bool                `gorm:"default:false" json:"allow_failure"`
	WorkerID      string              `gorm:"type:varchar(100)" json:"worker_id,omitempty"`
	NodeID        string              `gorm:"type:varchar(100)" json:"node_id,omitempty"`
	StartedAt     *time.Time          `json:"started_at,omitempty"`
	CompletedAt   *time.Time          `json:"completed_at,omitempty"`
	DurationSec   *int64              `json:"duration_sec,omitempty"`
	TimeoutAt     *time.Time          `json:"timeout_at,omitempty"`
	ExitCode      *int                `json:"exit_code,omitempty"`
	Error         string              `gorm:"type:text" json:"error,omitempty"`
	Output        datatypes.JSON      `gorm:"type:jsonb" json:"output,omitempty"`
	Resources     datatypes.JSON      `gorm:"type:jsonb" json:"resources,omitempty"`
	CreatedAt     time.Time           `gorm:"autoCreateTime" json:"created_at"`
	UpdatedAt     time.Time           `gorm:"autoUpdateTime" json:"updated_at"`
}

type WebhookEvent struct {
	ID                types.ID            `gorm:"type:varchar(26);primaryKey" json:"id"`
	Source            types.EventSource   `gorm:"type:varchar(20);not null;index" json:"source"`
	EventType         types.EventType     `gorm:"type:varchar(30);not null;index" json:"event_type"`
	DeduplicationKey  string              `gorm:"type:varchar(255);index" json:"deduplication_key,omitempty"`
	ProjectID         string              `gorm:"type:varchar(100);index" json:"project_id,omitempty"`
	RepoName          string              `gorm:"type:varchar(255)" json:"repo_name,omitempty"`
	RepoURL           string              `gorm:"type:varchar(500)" json:"repo_url,omitempty"`
	Commit            string              `gorm:"type:varchar(64)" json:"commit,omitempty"`
	Branch            string              `gorm:"type:varchar(255)" json:"branch,omitempty"`
	Tag               string              `gorm:"type:varchar(100)" json:"tag,omitempty"`
	Ref               string              `gorm:"type:varchar(255)" json:"ref,omitempty"`
	Message           string              `gorm:"type:text" json:"message,omitempty"`
	Author            string              `gorm:"type:varchar(255)" json:"author,omitempty"`
	AuthorEmail       string              `gorm:"type:varchar(255)" json:"author_email,omitempty"`
	PullRequestID     string              `gorm:"type:varchar(100)" json:"pull_request_id,omitempty"`
	PullRequestTitle  string              `gorm:"type:varchar(500)" json:"pull_request_title,omitempty"`
	Payload           datatypes.JSON      `gorm:"type:jsonb" json:"payload,omitempty"`
	Headers           datatypes.JSON      `gorm:"type:jsonb" json:"headers,omitempty"`
	Signature         string              `gorm:"type:varchar(255)" json:"signature,omitempty"`
	SignatureValid    *bool               `json:"signature_valid,omitempty"`
	Processed         bool                `gorm:"default:false;index" json:"processed"`
	ProcessingError   string              `gorm:"type:text" json:"processing_error,omitempty"`
	ProcessedAt       *time.Time          `json:"processed_at,omitempty"`
	MatchedPipelines  datatypes.JSON      `gorm:"type:jsonb" json:"matched_pipelines,omitempty"`
	CreatedAt         time.Time           `gorm:"autoCreateTime;index" json:"created_at"`
}

type Plugin struct {
	ID          types.ID         `gorm:"type:varchar(26);primaryKey" json:"id"`
	Name        string           `gorm:"type:varchar(100);not null;uniqueIndex:idx_plugin_name_version" json:"name"`
	Version     string           `gorm:"type:varchar(50);not null;uniqueIndex:idx_plugin_name_version" json:"version"`
	Type        types.StageType  `gorm:"type:varchar(20);not null;index" json:"type"`
	Description string           `gorm:"type:text" json:"description,omitempty"`
	Author      string           `gorm:"type:varchar(255)" json:"author,omitempty"`
	Icon        string           `gorm:"type:varchar(500)" json:"icon,omitempty"`
	BinaryPath  string           `gorm:"type:varchar(500);not null" json:"binary_path"`
	Command     string           `gorm:"type:varchar(255)" json:"command,omitempty"`
	Args        datatypes.JSON   `gorm:"type:jsonb" json:"args,omitempty"`
	Env         datatypes.JSON   `gorm:"type:jsonb" json:"env,omitempty"`
	ConfigSchema datatypes.JSON  `gorm:"type:jsonb" json:"config_schema,omitempty"`
	Status      types.PluginStatus `gorm:"type:varchar(20);not null;default:'active'" json:"status"`
	HealthCheckEndpoint string   `gorm:"type:varchar(255)" json:"health_check_endpoint,omitempty"`
	LastHealthCheck *time.Time    `json:"last_health_check,omitempty"`
	HealthStatus  string          `gorm:"type:varchar(20)" json:"health_status,omitempty"`
	Tags        datatypes.JSON   `gorm:"type:jsonb" json:"tags,omitempty"`
	CreatedAt   time.Time        `gorm:"autoCreateTime" json:"created_at"`
	UpdatedAt   time.Time        `gorm:"autoUpdateTime" json:"updated_at"`
}

type Secret struct {
	ID            types.ID       `gorm:"type:varchar(26);primaryKey" json:"id"`
	Name          string         `gorm:"type:varchar(255);not null;uniqueIndex" json:"name"`
	Description   string         `gorm:"type:text" json:"description,omitempty"`
	Source        types.SecretSource `gorm:"type:varchar(20);not null" json:"source"`
	VaultPath     string         `gorm:"type:varchar(500)" json:"vault_path,omitempty"`
	VaultKey      string         `gorm:"type:varchar(100)" json:"vault_key,omitempty"`
	EnvVarName    string         `gorm:"type:varchar(255)" json:"env_var_name,omitempty"`
	ProjectID     string         `gorm:"type:varchar(100);index" json:"project_id,omitempty"`
	AllowedPipelines datatypes.JSON `gorm:"type:jsonb" json:"allowed_pipelines,omitempty"`
	AllowedStages datatypes.JSON `gorm:"type:jsonb" json:"allowed_stages,omitempty"`
	RotatedAt     *time.Time     `json:"rotated_at,omitempty"`
	ExpiresAt     *time.Time     `json:"expires_at,omitempty"`
	Version       int            `gorm:"default:1" json:"version"`
	CreatedBy     string         `gorm:"type:varchar(100)" json:"created_by,omitempty"`
	CreatedAt     time.Time      `gorm:"autoCreateTime" json:"created_at"`
	UpdatedAt     time.Time      `gorm:"autoUpdateTime" json:"updated_at"`
}

type SecretUsageLog struct {
	ID          types.ID    `gorm:"type:varchar(26);primaryKey" json:"id"`
	SecretID    types.ID    `gorm:"type:varchar(26);not null;index" json:"secret_id"`
	SecretName  string      `gorm:"type:varchar(255);not null" json:"secret_name"`
	ExecutionID types.ID    `gorm:"type:varchar(26);not null;index" json:"execution_id"`
	PipelineID  types.ID    `gorm:"type:varchar(26);not null;index" json:"pipeline_id"`
	StageName   string      `gorm:"type:varchar(255)" json:"stage_name,omitempty"`
	RequestedBy string      `gorm:"type:varchar(100)" json:"requested_by,omitempty"`
	IPAddress   string      `gorm:"type:varchar(45)" json:"ip_address,omitempty"`
	Success     bool        `gorm:"not null" json:"success"`
	Reason      string      `gorm:"type:text" json:"reason,omitempty"`
	CreatedAt   time.Time   `gorm:"autoCreateTime;index" json:"created_at"`
}

type ArtifactRecord struct {
	ID            types.ID            `gorm:"type:varchar(26);primaryKey" json:"id"`
	ExecutionID   types.ID            `gorm:"type:varchar(26);not null;index" json:"execution_id"`
	StageID       types.ID            `gorm:"type:varchar(26);index" json:"stage_id,omitempty"`
	ProjectID     string              `gorm:"type:varchar(100);index" json:"project_id,omitempty"`
	Name          string              `gorm:"type:varchar(255);not null" json:"name"`
	FileName      string              `gorm:"type:varchar(500);not null" json:"file_name"`
	Size          int64               `gorm:"type:bigint;not null" json:"size"`
	ContentType   string              `gorm:"type:varchar(100)" json:"content_type"`
	Status        types.ArtifactStatus `gorm:"type:varchar(20);not null;default:'uploading'" json:"status"`
	StorageBucket string              `gorm:"type:varchar(100);not null" json:"storage_bucket"`
	StorageKey    string              `gorm:"type:varchar(500);not null;index" json:"storage_key"`
	Digest        string              `gorm:"type:varchar(64)" json:"digest,omitempty"`
	Labels        datatypes.JSON      `gorm:"type:jsonb" json:"labels,omitempty"`
	UploadedBy    string              `gorm:"type:varchar(100)" json:"uploaded_by,omitempty"`
	UploadedAt    *time.Time          `json:"uploaded_at,omitempty"`
	ExpiresAt     *time.Time          `index:"idx_artifacts_expires_at" json:"expires_at,omitempty"`
	DownloadCount int                 `gorm:"default:0" json:"download_count"`
	LastDownload  *time.Time          `json:"last_download,omitempty"`
	CreatedAt     time.Time           `gorm:"autoCreateTime" json:"created_at"`
}

type LogRecord struct {
	ID          types.ID   `gorm:"type:varchar(26);primaryKey" json:"id"`
	ExecutionID types.ID   `gorm:"type:varchar(26);not null;index" json:"execution_id"`
	StageID     types.ID   `gorm:"type:varchar(26);index" json:"stage_id,omitempty"`
	Timestamp   time.Time  `gorm:"not null;index" json:"timestamp"`
	Level       string     `gorm:"type:varchar(20);not null;default:'INFO'" json:"level"`
	Message     string     `gorm:"type:text;not null" json:"message"`
	Stream      string     `gorm:"type:varchar(20)" json:"stream,omitempty"`
	LineNumber  int64      `gorm:"type:bigint" json:"line_number,omitempty"`
	PluginName  string     `gorm:"type:varchar(100)" json:"plugin_name,omitempty"`
	Data        datatypes.JSON `gorm:"type:jsonb" json:"data,omitempty"`
	CreatedAt   time.Time  `gorm:"autoCreateTime" json:"created_at"`
}

type ScheduledTrigger struct {
	ID             types.ID       `gorm:"type:varchar(26);primaryKey" json:"id"`
	PipelineID     types.ID       `gorm:"type:varchar(26);not null;index" json:"pipeline_id"`
	CronExpression string         `gorm:"type:varchar(100);not null" json:"cron_expression"`
	Timezone       string         `gorm:"type:varchar(50);not null;default:'UTC'" json:"timezone"`
	Variables      datatypes.JSON `gorm:"type:jsonb" json:"variables,omitempty"`
	NextRun        time.Time      `gorm:"not null;index" json:"next_run"`
	LastRun        *time.Time     `json:"last_run,omitempty"`
	Enabled        bool           `gorm:"not null;default:true;index" json:"enabled"`
	CreatedAt      time.Time      `gorm:"autoCreateTime" json:"created_at"`
	UpdatedAt      time.Time      `gorm:"autoUpdateTime" json:"updated_at"`
}
