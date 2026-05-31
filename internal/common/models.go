package common

import (
	"context"
	"crypto/x509"
	"time"
)

type StrategyType string

const (
	StrategyRoundRobin StrategyType = "round_robin"
	StrategyFastest    StrategyType = "fastest"
	StrategyFailover   StrategyType = "failover"
	StrategyWeighted   StrategyType = "weighted"
)

type ResourceStatus string

const (
	StatusPending      ResourceStatus = "pending"
	StatusProvisioning ResourceStatus = "provisioning"
	StatusRunning      ResourceStatus = "running"
	StatusCompleted    ResourceStatus = "completed"
	StatusFailed       ResourceStatus = "failed"
	StatusCancelled    ResourceStatus = "cancelled"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     ResourceStatus         `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID       string     `json:"run_id" gorm:"primaryKey"`
	EntityID    string     `json:"entity_id"`
	Phase       string     `json:"phase"`
	Progress    float64    `json:"progress"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail string     `json:"error_detail,omitempty"`
}

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
	EntityID   string                 `json:"entity_id"`
	Version    int64                  `json:"version"`
	State      map[string]interface{} `json:"state" gorm:"serializer:json"`
}

type CreateResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type Resource struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	Status     ResourceStatus         `json:"status"`
	Config     map[string]interface{} `json:"config"`
	Labels     map[string]string      `json:"labels"`
	CreatedAt  time.Time              `json:"created_at"`
}

type ResourceStatus struct {
	ID       string         `json:"id"`
	Status   ResourceStatus `json:"status"`
	Progress float64        `json:"progress"`
}

type Operation struct {
	Action string            `json:"action"`
	ID     string            `json:"id"`
	Params map[string]string `json:"params"`
}

type BatchResult struct {
	BatchID string                 `json:"batch_id"`
	Results []*OperationResult     `json:"results"`
}

type OperationResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}

type DNSUpstream struct {
	Name      string `json:"name"`
	Address   string `json:"address"`
	Port      int    `json:"port"`
	Weight    int    `json:"weight"`
	Enabled   bool   `json:"enabled"`
	TimeoutMs int    `json:"timeout_ms"`
}

type DNSResponse struct {
	Domain    string   `json:"domain"`
	Records   []string `json:"records"`
	TTL       int      `json:"ttl"`
	Upstream  string   `json:"upstream"`
	CacheHit  bool     `json:"cache_hit"`
	LatencyMs int64    `json:"latency_ms"`
}

type CacheStats struct {
	Hits        int64   `json:"hits"`
	Misses      int64   `json:"misses"`
	HitRate     float64 `json:"hit_rate"`
	Size        int     `json:"size"`
	MaxSize     int     `json:"max_size"`
	Evictions   int64   `json:"evictions"`
}

type Certificate struct {
	CertID     string    `json:"cert_id" gorm:"primaryKey"`
	Namespace  string    `json:"namespace"`
	CommonName string    `json:"common_name"`
	DNSNames   []string  `json:"dns_names" gorm:"serializer:json"`
	IssuedAt   time.Time `json:"issued_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	Revoked    bool      `json:"revoked"`
	RevokedAt  *time.Time `json:"revoked_at,omitempty"`
	Serial     string    `json:"serial"`
	CertPEM    string    `json:"cert_pem"`
	KeyPEM     string    `json:"key_pem"`
}

type CertificateRequest struct {
	CommonName string        `json:"common_name"`
	DNSNames   []string      `json:"dns_names"`
	Namespace  string        `json:"namespace"`
	Validity   time.Duration `json:"validity"`
	KeySize    int           `json:"key_size"`
}

type RotationPolicy struct {
	Namespace       string        `json:"namespace"`
	AutoRotate      bool          `json:"auto_rotate"`
	RotationWindow  time.Duration `json:"rotation_window"`
	ValidityPeriod  time.Duration `json:"validity_period"`
	AlertBeforeDays int           `json:"alert_before_days"`
}

type CRL struct {
	IssuerCN    string            `json:"issuer_cn"`
	RevokedCerts []*RevokedCert   `json:"revoked_certs"`
	LastUpdate  time.Time         `json:"last_update"`
	NextUpdate  time.Time         `json:"next_update"`
	PEM         string            `json:"pem"`
}

type RevokedCert struct {
	Serial     string    `json:"serial"`
	CommonName string    `json:"common_name"`
	RevokedAt  time.Time `json:"revoked_at"`
	Reason     string    `json:"reason"`
}

type ChaosInjector interface {
	Inject(ctx context.Context, scope *InjectionScope, params map[string]interface{}) error
	Rollback(ctx context.Context, runID string) error
	Type() string
}

type ChaosScenario struct {
	ScenarioID  string                 `json:"scenario_id" gorm:"primaryKey"`
	Name        string                 `json:"name"`
	Namespace   string                 `json:"namespace"`
	Description string                 `json:"description"`
	InjectorType string                `json:"injector_type"`
	Parameters  map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Duration    time.Duration          `json:"duration"`
	AutoRollback bool                  `json:"auto_rollback"`
	RollbackTimeout time.Duration      `json:"rollback_timeout"`
	CreatedAt   time.Time              `json:"created_at"`
	Enabled     bool                   `json:"enabled"`
}

type InjectionScope struct {
	Namespace    string            `json:"namespace"`
	Selector     map[string]string `json:"selector"`
	Targets      []string          `json:"targets"`
	Percentage   int               `json:"percentage"`
	ExcludeHosts []string          `json:"exclude_hosts"`
}

type CanaryConfig struct {
	PolicyID       string            `json:"policy_id"`
	Namespace      string            `json:"namespace"`
	Service        string            `json:"service"`
	PrimaryVersion string            `json:"primary_version"`
	CanaryVersion  string            `json:"canary_version"`
	TrafficWeight  int32             `json:"traffic_weight"`
	Headers        map[string]string `json:"headers"`
	Cookies        map[string]string `json:"cookies"`
	RollbackPolicy RollbackPolicy    `json:"rollback_policy"`
}

type BlueGreenConfig struct {
	PolicyID    string `json:"policy_id"`
	Namespace   string `json:"namespace"`
	Service     string `json:"service"`
	BlueVersion string `json:"blue_version"`
	GreenVersion string `json:"green_version"`
	Active      string `json:"active"`
}

type MirrorConfig struct {
	PolicyID     string            `json:"policy_id"`
	Namespace    string            `json:"namespace"`
	Service      string            `json:"service"`
	Source       string            `json:"source"`
	Target       string            `json:"target"`
	Filter       map[string]string `json:"filter"`
	SampleRate   float64           `json:"sample_rate"`
}

type CircuitBreakerConfig struct {
	PolicyID          string        `json:"policy_id"`
	Namespace         string        `json:"namespace"`
	Service           string        `json:"service"`
	ErrorThreshold    int           `json:"error_threshold"`
	Timeout           time.Duration `json:"timeout"`
	HalfOpenRequests  int           `json:"half_open_requests"`
	SuccessThreshold  int           `json:"success_threshold"`
	FallbackService   string        `json:"fallback_service"`
}

type RollbackPolicy struct {
	ErrorThreshold  float64       `json:"error_threshold"`
	LatencyP99      int64         `json:"latency_p99"`
	CheckInterval   time.Duration `json:"check_interval"`
	AutoRollback    bool          `json:"auto_rollback"`
}

type TrafficPolicy struct {
	PolicyID   string                 `json:"policy_id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Namespace  string                 `json:"namespace"`
	Service    string                 `json:"service"`
	Config     map[string]interface{} `json:"config" gorm:"serializer:json"`
	Status     string                 `json:"status"`
	Enabled    bool                   `json:"enabled"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

type DomainEvent struct {
	EventID     string                 `json:"event_id" gorm:"primaryKey"`
	EntityID    string                 `json:"entity_id"`
	EventType   string                 `json:"event_type"`
	Version     int64                  `json:"version"`
	Payload     map[string]interface{} `json:"payload" gorm:"serializer:json"`
	Timestamp   time.Time              `json:"timestamp"`
	Metadata    map[string]string      `json:"metadata" gorm:"serializer:json"`
}

type EventStats struct {
	TotalEvents     int64            `json:"total_events"`
	EventsPerType   map[string]int64 `json:"events_per_type"`
	SnapshotCount   int64            `json:"snapshot_count"`
	LastEventTime   time.Time        `json:"last_event_time"`
}

type ImagePullResult struct {
	Ref        string        `json:"ref"`
	Layers     []string      `json:"layers"`
	TotalSize  int64         `json:"total_size"`
	PulledSize int64         `json:"pulled_size"`
	CachedSize int64         `json:"cached_size"`
	Duration   time.Duration `json:"duration"`
	Success    bool          `json:"success"`
	Error      string        `json:"error,omitempty"`
}

type ImageSyncResult struct {
	SourceRef  string        `json:"source_ref"`
	TargetRef  string        `json:"target_ref"`
	TotalSize  int64         `json:"total_size"`
	SyncedSize int64         `json:"synced_size"`
	Duration   time.Duration `json:"duration"`
	Success    bool          `json:"success"`
	Error      string        `json:"error,omitempty"`
}

type P2PStatus struct {
	ImageRef   string   `json:"image_ref"`
	Nodes      []string `json:"nodes"`
	Seeders    int      `json:"seeders"`
	Leechers   int      `json:"leechers"`
	Enabled    bool     `json:"enabled"`
	Throughput int64    `json:"throughput"`
}

type ImageManifest struct {
	Digest     string            `json:"digest"`
	SchemaV    int               `json:"schema_version"`
	Layers     []*ImageLayer     `json:"layers"`
	Config     *ImageConfig      `json:"config"`
	Labels     map[string]string `json:"labels"`
	CreatedAt  time.Time         `json:"created_at"`
}

type ImageLayer struct {
	Digest    string `json:"digest"`
	Size      int64  `json:"size"`
	MediaType string `json:"media_type"`
	Cached    bool   `json:"cached"`
}

type ImageConfig struct {
	Entrypoint []string          `json:"entrypoint"`
	Cmd        []string          `json:"cmd"`
	Env        []string          `json:"env"`
	Labels     map[string]string `json:"labels"`
	User       string            `json:"user"`
	WorkingDir string            `json:"working_dir"`
}

type ImageInfo struct {
	Ref        string    `json:"ref"`
	Registry   string    `json:"registry"`
	Repository string    `json:"repository"`
	Tag        string    `json:"tag"`
	Digest     string    `json:"digest"`
	Size       int64     `json:"size"`
	PushedAt   time.Time `json:"pushed_at"`
}

type InjectionTarget struct {
	Kind      string            `json:"kind"`
	Name      string            `json:"name"`
	Namespace string            `json:"namespace"`
	Labels    map[string]string `json:"labels"`
}

type SidecarConfig struct {
	Image           string            `json:"image"`
	Args            []string          `json:"args"`
	Env             map[string]string `json:"env"`
	ConfigMountPath string            `json:"config_mount_path"`
	LogPath         string            `json:"log_path"`
	NetworkMode     string            `json:"network_mode"`
}

type ResourceLimits struct {
	CPURequest    string `json:"cpu_request"`
	CPULimit      string `json:"cpu_limit"`
	MemoryRequest string `json:"memory_request"`
	MemoryLimit   string `json:"memory_limit"`
	EphemeralStorage string `json:"ephemeral_storage"`
}

type SidecarInstance struct {
	InstanceID string         `json:"instance_id" gorm:"primaryKey"`
	Target     string         `json:"target"`
	Namespace  string         `json:"namespace"`
	Config     *SidecarConfig `json:"config" gorm:"serializer:json"`
	Limits     *ResourceLimits `json:"limits" gorm:"serializer:json"`
	Status     string         `json:"status"`
	PodName    string         `json:"pod_name"`
	ContainerID string        `json:"container_id"`
	InjectedAt time.Time      `json:"injected_at"`
	UpdatedAt  time.Time      `json:"updated_at"`
}

type InjectionPolicy struct {
	Namespace     string            `json:"namespace"`
	Selector      map[string]string `json:"selector"`
	DefaultConfig *SidecarConfig    `json:"default_config"`
	DefaultLimits *ResourceLimits   `json:"default_limits"`
	AutoInject    bool              `json:"auto_inject"`
}

type Command struct {
	CommandID   string                 `json:"command_id" gorm:"primaryKey"`
	CommandType string                 `json:"command_type"`
	EntityID    string                 `json:"entity_id"`
	Payload     map[string]interface{} `json:"payload" gorm:"serializer:json"`
	IssuedBy    string                 `json:"issued_by"`
	IssuedAt    time.Time              `json:"issued_at"`
	Status      string                 `json:"status"`
	Result      map[string]interface{} `json:"result,omitempty" gorm:"serializer:json"`
	Error       string                 `json:"error,omitempty"`
	EventIDs    []string               `json:"event_ids,omitempty" gorm:"serializer:json"`
}

type CommandFilter struct {
	CommandType string     `json:"command_type"`
	EntityID    string     `json:"entity_id"`
	IssuedBy    string     `json:"issued_by"`
	Status      string     `json:"status"`
	FromTime    *time.Time `json:"from_time"`
	ToTime      *time.Time `json:"to_time"`
	Limit       int        `json:"limit"`
	Offset      int        `json:"offset"`
}

type AuditEntry struct {
	EntryID    string                 `json:"entry_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	UserID     string                 `json:"user_id"`
	Action     string                 `json:"action"`
	ResourceType string               `json:"resource_type"`
	ResourceID string                 `json:"resource_id"`
	CommandID  string                 `json:"command_id,omitempty"`
	EventIDs   []string               `json:"event_ids,omitempty" gorm:"serializer:json"`
	Before     map[string]interface{} `json:"before,omitempty" gorm:"serializer:json"`
	After      map[string]interface{} `json:"after,omitempty" gorm:"serializer:json"`
	Metadata   map[string]string      `json:"metadata,omitempty" gorm:"serializer:json"`
}

type AuditFilter struct {
	UserID       string     `json:"user_id"`
	Action       string     `json:"action"`
	ResourceType string     `json:"resource_type"`
	ResourceID   string     `json:"resource_id"`
	FromTime     *time.Time `json:"from_time"`
	ToTime       *time.Time `json:"to_time"`
	Limit        int        `json:"limit"`
	Offset       int        `json:"offset"`
}

type ComplianceRequest struct {
	ReportType   string     `json:"report_type"`
	Framework    string     `json:"framework"`
	Namespace    string     `json:"namespace"`
	FromTime     *time.Time `json:"from_time"`
	ToTime       *time.Time `json:"to_time"`
	IncludeEvents bool      `json:"include_events"`
}

type ComplianceReport struct {
	ReportID    string     `json:"report_id"`
	ReportType  string     `json:"report_type"`
	Framework   string     `json:"framework"`
	GeneratedAt time.Time  `json:"generated_at"`
	PeriodStart time.Time  `json:"period_start"`
	PeriodEnd   time.Time  `json:"period_end"`
	Summary     *ReportSummary `json:"summary"`
	Findings    []*ReportFinding `json:"findings"`
	Data        map[string]interface{} `json:"data,omitempty" gorm:"serializer:json"`
}

type ReportSummary struct {
	TotalCommands   int `json:"total_commands"`
	TotalEvents     int `json:"total_events"`
	TotalAuditLogs  int `json:"total_audit_logs"`
	FailedCommands  int `json:"failed_commands"`
	PolicyViolations int `json:"policy_violations"`
}

type ReportFinding struct {
	Severity string `json:"severity"`
	Rule     string `json:"rule"`
	Message  string `json:"message"`
	Resource string `json:"resource"`
	Evidence map[string]interface{} `json:"evidence,omitempty" gorm:"serializer:json"`
}

type CACert struct {
	Cert       *x509.Certificate
	PrivateKey interface{}
	CertPEM    string
	KeyPEM     string
}

type CacheEntry struct {
	Key        string
	Value      interface{}
	Expiration time.Time
	HitCount   int64
}
