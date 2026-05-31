package models

import "time"

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
	Version    int64                  `json:"version"`
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

type RunInstance struct {
	RunID        string     `json:"run_id"`
	EntityID     string     `json:"entity_id"`
	Phase        string     `json:"phase"`
	Progress     float64    `json:"progress"`
	StartedAt    time.Time  `json:"started_at"`
	CompletedAt  *time.Time `json:"completed_at,omitempty"`
	ErrorDetail  *string    `json:"error_detail,omitempty"`
}

type MetricsSnapshot struct {
	SnapshotID string                 `json:"snapshot_id"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics"`
	Dimensions map[string]string      `json:"dimensions"`
}

type AlertRule struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Expression  string                 `json:"expression"`
	Severity    string                 `json:"severity"`
	Enabled     bool                   `json:"enabled"`
	Annotations map[string]string      `json:"annotations"`
	CreatedAt   time.Time              `json:"created_at"`
}

type AlertNotification struct {
	ID        string                 `json:"id"`
	RuleID    string                 `json:"rule_id"`
	Message   string                 `json:"message"`
	Level     string                 `json:"level"`
	Timestamp time.Time              `json:"timestamp"`
}

type Certificate struct {
	ID          string                 `json:"id"`
	CN          string                 `json:"cn"`
	SANs        []string               `json:"sans"`
	Serial      string                 `json:"serial"`
	NotBefore   time.Time              `json:"not_before"`
	NotAfter    time.Time              `json:"not_after"`
	Status      string                 `json:"status"`
	PEM         string                 `json:"-"`
	PrivateKey  string                 `json:"-"`
}

type RotationPolicy struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	AutoRotate  bool                   `json:"auto_rotate"`
	DaysBefore  int                    `json:"days_before"`
}

type CRL struct {
	ID          string                 `json:"id"`
	IssuerCN    string                 `json:"issuer_cn"`
	Revoked     []string               `json:"revoked"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ObjectMetadata struct {
	ID          string                 `json:"id"`
	Bucket      string                 `json:"bucket"`
	Key         string                 `json:"key"`
	Size        int64                  `json:"size"`
	ContentType string                 `json:"content_type"`
	ETag        string                 `json:"etag"`
	Tags        map[string]string      `json:"tags"`
	CreatedAt   time.Time              `json:"created_at"`
}

type SidecarSpec struct {
	ID              string                 `json:"id"`
	Name            string                 `json:"name"`
	Image           string                 `json:"image"`
	InjectionPolicy string                 `json:"injection_policy"`
	Resources       ResourceLimit          `json:"resources"`
	Config          map[string]interface{} `json:"config"`
	HotReload       bool                   `json:"hot_reload"`
}

type ResourceLimit struct {
	CPU    string `json:"cpu"`
	Memory string `json:"memory"`
}

type DNSUpstream struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Address  string `json:"address"`
	Priority int    `json:"priority"`
	Enabled  bool   `json:"enabled"`
}

type DNSCacheEntry struct {
	Domain    string    `json:"domain"`
	Records   []string  `json:"records"`
	TTL       int       `json:"ttl"`
	ExpiresAt time.Time `json:"expires_at"`
}

type FaultScenario struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Type        string                 `json:"type"`
	Target      FaultTarget            `json:"target"`
	Duration    int64                  `json:"duration"`
	AutoRollback bool                  `json:"auto_rollback"`
	Parameters  map[string]interface{} `json:"parameters"`
}

type FaultTarget struct {
	Namespace string   `json:"namespace"`
	Selectors []string `json:"selectors"`
}

type Task struct {
	ID           string    `json:"id"`
	Name         string    `json:"name"`
	Status       string    `json:"status"`
	Dependencies []string  `json:"dependencies"`
	Retries      int       `json:"retries"`
	CreatedAt    time.Time `json:"created_at"`
}

type ImageLayer struct {
	Digest string `json:"digest"`
	Size   int64  `json:"size"`
	URL    string `json:"url"`
}

type ImageManifest struct {
	Registry string       `json:"registry"`
	Repo     string       `json:"repo"`
	Tag      string       `json:"tag"`
	Layers   []ImageLayer `json:"layers"`
}

type RouteRule struct {
	ID          string                 `json:"id"`
	Path        string                 `json:"path"`
	Method      string                 `json:"method"`
	Backend     string                 `json:"backend"`
	Protocol    string                 `json:"protocol"`
	RewritePath string                 `json:"rewrite_path"`
	Timeout     int                    `json:"timeout"`
}
