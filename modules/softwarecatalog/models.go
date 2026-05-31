package softwarecatalog

import (
	"time"
)

type Service struct {
	ID           string                 `gorm:"primaryKey" json:"id"`
	Name         string                 `gorm:"uniqueIndex" json:"name"`
	Description  string                 `json:"description"`
	Type         string                 `gorm:"index" json:"type"`
	Language     string                 `json:"language"`
	OwnerTeam    string                 `json:"owner_team"`
	OwnerEmails  []string               `gorm:"serializer:json" json:"owner_emails"`
	Tags         []string               `gorm:"serializer:json" json:"tags"`
	Status       string                 `gorm:"index" json:"status"`
	Repository   string                 `json:"repository"`
	Documentation string               `json:"documentation"`
	Endpoints    map[string]string      `gorm:"serializer:json" json:"endpoints"`
	Metadata     map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type Library struct {
	ID            string                 `gorm:"primaryKey" json:"id"`
	Name          string                 `gorm:"uniqueIndex:idx_lib_name_version" json:"name"`
	Version       string                 `gorm:"uniqueIndex:idx_lib_name_version" json:"version"`
	Description   string                 `json:"description"`
	Language      string                 `gorm:"index" json:"language"`
	License       string                 `json:"license"`
	Repository    string                 `json:"repository"`
	Homepage      string                 `json:"homepage"`
	Tags          []string               `gorm:"serializer:json" json:"tags"`
	Metadata      map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	PublishedAt   *time.Time             `json:"published_at"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

type Dependency struct {
	ID              string    `gorm:"primaryKey" json:"id"`
	FromID          string    `gorm:"index" json:"from_id"`
	FromType        string    `gorm:"index" json:"from_type"`
	ToID            string    `gorm:"index" json:"to_id"`
	ToType          string    `gorm:"index" json:"to_type"`
	VersionConstraint string   `json:"version_constraint"`
	Scope           string    `json:"scope"`
	CreatedAt       time.Time `json:"created_at"`
}

type ServiceVersion struct {
	ID          string                 `gorm:"primaryKey" json:"id"`
	ServiceID   string                 `gorm:"index" json:"service_id"`
	Version     string                 `json:"version"`
	Status      string                 `json:"status"`
	Commit      string                 `json:"commit"`
	Branch      string                 `json:"branch"`
	Changelog   string                 `gorm:"type:text" json:"changelog"`
	Metadata    map[string]interface{} `gorm:"serializer:json" json:"metadata"`
	ReleasedAt  *time.Time             `json:"released_at"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ServiceHealth struct {
	ID          string    `gorm:"primaryKey" json:"id"`
	ServiceID   string    `gorm:"uniqueIndex" json:"service_id"`
	Status      string    `json:"status"`
	Uptime      float64   `json:"uptime"`
	LastCheck   time.Time `json:"last_check"`
	LatencyP99  int       `json:"latency_p99"`
	ErrorRate   float64   `json:"error_rate"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type SearchQuery struct {
	Query     string   `form:"q" json:"q"`
	Type      string   `form:"type" json:"type"`
	Language  string   `form:"language" json:"language"`
	Tags      []string `form:"tags" json:"tags"`
	Status    string   `form:"status" json:"status"`
	OwnerTeam string   `form:"owner_team" json:"owner_team"`
	Page      int      `form:"page" json:"page"`
	Size      int      `form:"size" json:"size"`
}

type DependencyGraph struct {
	Nodes []DependencyNode `json:"nodes"`
	Edges []DependencyEdge `json:"edges"`
}

type DependencyNode struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Type     string `json:"type"`
	Version  string `json:"version,omitempty"`
	Language string `json:"language,omitempty"`
}

type DependencyEdge struct {
	From    string `json:"from"`
	To      string `json:"to"`
	Version string `json:"version,omitempty"`
	Scope   string `json:"scope,omitempty"`
}
