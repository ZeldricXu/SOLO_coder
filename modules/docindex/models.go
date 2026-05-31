package docindex

import (
	"time"
)

type Document struct {
	ID          string            `gorm:"primaryKey" json:"id"`
	Source      string            `gorm:"index" json:"source"`
	Title       string            `json:"title"`
	Content     string            `gorm:"type:text" json:"content"`
	Tags        []string          `gorm:"serializer:json" json:"tags"`
	Permissions DocPermissions    `gorm:"serializer:json" json:"permissions"`
	Metadata    map[string]string `gorm:"serializer:json" json:"metadata"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type DocPermissions struct {
	Public    bool     `json:"public"`
	ReadRoles []string `json:"read_roles"`
	ReadUsers []string `json:"read_users"`
	OwnerID   string   `json:"owner_id"`
}

type DocumentSource struct {
	ID          string    `gorm:"primaryKey" json:"id"`
	Name        string    `json:"name"`
	Type        string    `json:"type"`
	Endpoint    string    `json:"endpoint"`
	Credentials string    `json:"credentials"`
	Config      map[string]interface{} `gorm:"serializer:json" json:"config"`
	LastSync    *time.Time `json:"last_sync"`
	Enabled     bool      `json:"enabled"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type SyncJob struct {
	ID          string     `gorm:"primaryKey" json:"id"`
	SourceID    string     `gorm:"index" json:"source_id"`
	Status      string     `json:"status"`
	DocumentCount int      `json:"document_count"`
	Error       *string    `json:"error"`
	StartedAt   time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at"`
}

type SearchQuery struct {
	Query    string   `form:"q" json:"q"`
	Tags     []string `form:"tags" json:"tags"`
	Source   string   `form:"source" json:"source"`
	UserID   string   `form:"user_id" json:"user_id"`
	Roles    []string `form:"roles" json:"roles"`
	Page     int      `form:"page" json:"page"`
	Size     int      `form:"size" json:"size"`
}

type SearchResult struct {
	ID       string   `json:"id"`
	Title    string   `json:"title"`
	Source   string   `json:"source"`
	Tags     []string `json:"tags"`
	Score    float64  `json:"score"`
	Snippet  string   `json:"snippet,omitempty"`
}
