package model

import (
	"time"

	"github.com/google/uuid"
)

type TenantStatus string

const (
	TenantStatusActive   TenantStatus = "active"
	TenantStatusInactive TenantStatus = "inactive"
	TenantStatusSuspended TenantStatus = "suspended"
)

type Tenant struct {
	BaseModel
	Name        string       `gorm:"type:varchar(255);not null" json:"name"`
	Domain      string       `gorm:"type:varchar(255);uniqueIndex:idx_tenants_domain" json:"domain"`
	Namespace   string       `gorm:"type:varchar(100);uniqueIndex:idx_tenants_namespace" json:"namespace"`
	Description string       `gorm:"type:text" json:"description"`
	LogoURL     string       `gorm:"type:varchar(512)" json:"logo_url"`
	Status      TenantStatus `gorm:"type:varchar(32);not null;default:'active'" json:"status"`
	Settings    JSONB        `gorm:"type:jsonb" json:"settings"`
}

func (Tenant) TableName() string {
	return "tenants"
}

type TenantTheme struct {
	PrimaryColor    string `json:"primary_color"`
	SecondaryColor  string `json:"secondary_color"`
	BackgroundColor string `json:"background_color"`
	TextColor       string `json:"text_color"`
	FontFamily      string `json:"font_family"`
	LogoURL         string `json:"logo_url"`
	FaviconURL      string `json:"favicon_url"`
	CustomCSS       string `json:"custom_css"`
	CustomJS        string `json:"custom_js"`
}

type TenantQuota struct {
	MaxDocuments      int64 `json:"max_documents"`
	MaxStorageBytes   int64 `json:"max_storage_bytes"`
	MaxUsers          int   `json:"max_users"`
	MaxSpaces         int   `json:"max_spaces"`
	MaxAPICallsPerDay int64 `json:"max_api_calls_per_day"`
	MaxVersionsPerDoc int   `json:"max_versions_per_doc"`
	MaxAttachments    int   `json:"max_attachments"`
	MaxAttachmentSize int64 `json:"max_attachment_size"`
}

type TenantNavItem struct {
	ID       string          `json:"id"`
	Type     string          `json:"type"`
	Label    string          `json:"label"`
	URL      string          `json:"url"`
	Icon     string          `json:"icon"`
	Children []TenantNavItem `json:"children,omitempty"`
}

type TenantCustomNav struct {
	Items       []TenantNavItem `json:"items"`
	ShowDefault bool            `json:"show_default"`
}

type Quota struct {
	BaseModel
	TenantScoped
	SubjectType  string     `gorm:"type:varchar(32);not null;index" json:"subject_type"`
	SubjectID    uuid.UUID  `gorm:"type:uuid;not null;index" json:"subject_id"`
	ResourceType string    `gorm:"type:varchar(64);not null;index" json:"resource_type"`
	Limit        int64      `gorm:"type:bigint;not null;default:-1" json:"limit"`
	Used         int64      `gorm:"type:bigint;not null;default:0" json:"used"`
	Period       string     `gorm:"type:varchar(32);not null;default:'total'" json:"period"`
	ResetAt      *time.Time `gorm:"" json:"reset_at"`
	Enabled      bool       `gorm:"type:boolean;default:true" json:"enabled"`
}

func (Quota) TableName() string {
	return "quotas"
}

type Theme struct {
	BaseModel
	TenantScoped
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	Description string `gorm:"type:text" json:"description"`
	IsDefault   bool   `gorm:"type:boolean;default:false" json:"is_default"`
	IsSystem    bool   `gorm:"type:boolean;default:false" json:"is_system"`
	Config      JSONB  `gorm:"type:jsonb;not null" json:"config"`
	CreatedByID uuid.UUID `gorm:"type:uuid" json:"created_by_id"`
}

func (Theme) TableName() string {
	return "themes"
}
