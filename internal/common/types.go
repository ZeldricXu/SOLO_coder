package common

import "time"

type CloudProvider string

const (
	ProviderAWS   CloudProvider = "aws"
	ProviderAzure CloudProvider = "azure"
	ProviderGCP   CloudProvider = "gcp"
)

type ResourceType string

const (
	ResourceCompute    ResourceType = "compute"
	ResourceStorage    ResourceType = "storage"
	ResourceNetwork    ResourceType = "network"
	ResourceDatabase   ResourceType = "database"
	ResourceKubernetes ResourceType = "kubernetes"
	ResourceSecurity   ResourceType = "security"
)

type ResourceAction string

const (
	ActionCreate ResourceAction = "create"
	ActionUpdate ResourceAction = "update"
	ActionDelete ResourceAction = "delete"
	ActionNoop   ResourceAction = "noop"
)

type ResourceStatus string

const (
	StatusPending  ResourceStatus = "pending"
	StatusCreating ResourceStatus = "creating"
	StatusRunning  ResourceStatus = "running"
	StatusUpdating ResourceStatus = "updating"
	StatusDeleting ResourceStatus = "deleting"
	StatusFailed   ResourceStatus = "failed"
	StatusDeleted  ResourceStatus = "deleted"
	StatusUnknown  ResourceStatus = "unknown"
)

type Resource struct {
	ID         string                 `json:"id"`
	Name       string                 `json:"name"`
	Type       ResourceType           `json:"type"`
	Provider   CloudProvider          `json:"provider"`
	Region     string                 `json:"region"`
	Properties map[string]interface{} `json:"properties"`
	Tags       map[string]string      `json:"tags"`
	Status     ResourceStatus         `json:"status"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
	Metadata   map[string]interface{} `json:"metadata,omitempty"`
}

type ResourceConfig struct {
	Name       string                 `json:"name"`
	Type       ResourceType           `json:"type"`
	Provider   CloudProvider          `json:"provider"`
	Region     string                 `json:"region"`
	Properties map[string]interface{} `json:"properties"`
	Tags       map[string]string      `json:"tags"`
	DependsOn  []string               `json:"depends_on,omitempty"`
}

type Change struct {
	ResourceID   string              `json:"resource_id"`
	ResourceName string              `json:"resource_name"`
	Action       ResourceAction      `json:"action"`
	Old          *Resource           `json:"old,omitempty"`
	New          *ResourceConfig     `json:"new,omitempty"`
	Diff         map[string]DiffItem `json:"diff,omitempty"`
}

type DiffItem struct {
	Old        interface{} `json:"old"`
	New        interface{} `json:"new"`
	Path       string      `json:"path"`
	ChangeType string      `json:"change_type"`
}

type Credential struct {
	Provider       CloudProvider `json:"provider"`
	AccessKey      string        `json:"access_key"`
	SecretKey      string        `json:"secret_key,omitempty"`
	SessionToken   string        `json:"session_token,omitempty"`
	TenantID       string        `json:"tenant_id,omitempty"`
	SubscriptionID string        `json:"subscription_id,omitempty"`
	ClientID       string        `json:"client_id,omitempty"`
	ClientSecret   string        `json:"client_secret,omitempty"`
	ProjectID      string        `json:"project_id,omitempty"`
	Region         string        `json:"region,omitempty"`
	ExpiresAt      *time.Time    `json:"expires_at,omitempty"`
}

type AuditLog struct {
	ID        string                 `json:"id"`
	User      string                 `json:"user"`
	Action    string                 `json:"action"`
	Resource  string                 `json:"resource"`
	Provider  CloudProvider          `json:"provider"`
	Status    string                 `json:"status"`
	Message   string                 `json:"message"`
	Metadata  map[string]interface{} `json:"metadata,omitempty"`
	Timestamp time.Time              `json:"timestamp"`
}
