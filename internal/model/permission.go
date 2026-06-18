package model

import (
	"time"

	"github.com/google/uuid"
)

type ResourceType string

const (
	ResourceTypeTenant    ResourceType = "tenant"
	ResourceTypeSpace     ResourceType = "space"
	ResourceTypeDirectory ResourceType = "directory"
	ResourceTypeDocument  ResourceType = "document"
	ResourceTypeTemplate  ResourceType = "template"
	ResourceTypeUser      ResourceType = "user"
	ResourceTypeGroup     ResourceType = "group"
	ResourceTypeAPI       ResourceType = "api"
)

type Role string

const (
	RoleAdmin      Role = "admin"
	RoleEditor     Role = "editor"
	RoleReviewer   Role = "reviewer"
	RoleCommenter  Role = "commenter"
	RoleViewer     Role = "viewer"
	RoleOwner      Role = "owner"
)

type SubjectType string

const (
	SubjectTypeUser       SubjectType = "user"
	SubjectTypeGroup      SubjectType = "group"
	SubjectTypeDepartment SubjectType = "department"
)

type PermissionAction string

const (
	ActionCreate   PermissionAction = "create"
	ActionRead     PermissionAction = "read"
	ActionUpdate   PermissionAction = "update"
	ActionDelete   PermissionAction = "delete"
	ActionManage   PermissionAction = "manage"
	ActionShare    PermissionAction = "share"
	ActionExport   PermissionAction = "export"
	ActionImport   PermissionAction = "import"
	ActionComment  PermissionAction = "comment"
	ActionReview   PermissionAction = "review"
	ActionAdmin    PermissionAction = "admin"
)

type Permission struct {
	BaseModel
	TenantScoped
	ResourceType ResourceType     `gorm:"type:varchar(32);not null;index:idx_perm_resource,priority:1" json:"resource_type"`
	ResourceID   uuid.UUID        `gorm:"type:uuid;not null;index:idx_perm_resource,priority:2" json:"resource_id"`
	Role         Role             `gorm:"type:varchar(32);not null" json:"role"`
	SubjectType  SubjectType      `gorm:"type:varchar(32);not null;index:idx_perm_subject,priority:1" json:"subject_type"`
	SubjectID    uuid.UUID        `gorm:"type:uuid;not null;index:idx_perm_subject,priority:2" json:"subject_id"`
	Actions      StringArray      `gorm:"type:jsonb;default:'[]'" json:"actions"`
	Effect       string           `gorm:"type:varchar(16);not null;default:'allow'" json:"effect"`
	Conditions   JSONB            `gorm:"type:jsonb" json:"conditions"`
	GrantedBy    uuid.UUID        `gorm:"type:uuid" json:"granted_by"`
	ExpiresAt    *time.Time       `gorm:"" json:"expires_at"`
}

func (Permission) TableName() string {
	return "permissions"
}

func RoleWeight(role Role) int {
	switch role {
	case RoleOwner:
		return 100
	case RoleAdmin:
		return 80
	case RoleEditor:
		return 60
	case RoleReviewer:
		return 40
	case RoleCommenter:
		return 30
	case RoleViewer:
		return 20
	default:
		return 0
	}
}

func HasSufficientRole(required, actual Role) bool {
	return RoleWeight(actual) >= RoleWeight(required)
}

func RoleCan(role Role, action PermissionAction) bool {
	switch role {
	case RoleOwner, RoleAdmin:
		return true
	case RoleEditor:
		return action == ActionCreate || action == ActionRead ||
			action == ActionUpdate || action == ActionDelete ||
			action == ActionComment || action == ActionExport ||
			action == ActionImport
	case RoleReviewer:
		return action == ActionRead || action == ActionReview ||
			action == ActionComment || action == ActionExport
	case RoleCommenter:
		return action == ActionRead || action == ActionComment ||
			action == ActionExport
	case RoleViewer:
		return action == ActionRead || action == ActionExport
	default:
		return false
	}
}

type ApiTokenStatus string

const (
	ApiTokenStatusActive  ApiTokenStatus = "active"
	ApiTokenStatusRevoked ApiTokenStatus = "revoked"
	ApiTokenStatusExpired ApiTokenStatus = "expired"
)

type ApiToken struct {
	BaseModel
	TenantScoped
	UserID      uuid.UUID      `gorm:"type:uuid;not null;index" json:"user_id"`
	Name        string         `gorm:"type:varchar(255);not null" json:"name"`
	TokenHash   string         `gorm:"type:varchar(255);uniqueIndex;not null" json:"-"`
	TokenPrefix string         `gorm:"type:varchar(20)" json:"token_prefix"`
	Scopes      StringArray    `gorm:"type:jsonb;default:'[]'" json:"scopes"`
	IPWhitelist StringArray    `gorm:"type:jsonb;default:'[]'" json:"ip_whitelist"`
	ExpiresAt   *time.Time     `json:"expires_at,omitempty"`
	RateLimit   int            `gorm:"default:1000" json:"rate_limit"`
	LastUsedAt  *time.Time     `json:"last_used_at,omitempty"`
	LastUsedIP  string         `gorm:"type:varchar(50)" json:"last_used_ip"`
	UseCount    int64          `gorm:"default:0" json:"use_count"`
	Status      ApiTokenStatus `gorm:"type:varchar(20);default:'active'" json:"status"`
	CreatedBy   uuid.UUID      `gorm:"type:uuid" json:"created_by"`

	User *User `gorm:"foreignKey:UserID" json:"user,omitempty"`
}

func (ApiToken) TableName() string {
	return "api_tokens"
}

const (
	ScopeSpaceRead     = "space:read"
	ScopeSpaceWrite    = "space:write"
	ScopeSpaceAdmin    = "space:admin"
	ScopeDocumentRead  = "document:read"
	ScopeDocumentWrite = "document:write"
	ScopeDocumentAdmin = "document:admin"
	ScopeUserRead      = "user:read"
	ScopeUserWrite     = "user:write"
	ScopeUserAdmin     = "user:admin"
	ScopePermissionRead  = "permission:read"
	ScopePermissionWrite = "permission:write"
	ScopeSearchRead     = "search:read"
	ScopeExportRead     = "export:read"
	ScopeImportWrite    = "import:write"
	ScopeAll            = "*"
)

func (t *ApiToken) HasScope(scope string) bool {
	if t.Scopes == nil {
		return false
	}
	for _, s := range t.Scopes {
		if s == ScopeAll || s == scope {
			return true
		}
	}
	return false
}

func (t *ApiToken) IsExpired() bool {
	if t.ExpiresAt == nil {
		return false
	}
	return time.Now().After(*t.ExpiresAt)
}

func (t *ApiToken) IsActive() bool {
	return t.Status == ApiTokenStatusActive && !t.IsExpired()
}

type I18nDoc struct {
	BaseModel
	TenantScoped
	SourceDocID      uuid.UUID      `gorm:"type:uuid;not null;index" json:"source_doc_id"`
	Language         string         `gorm:"type:varchar(16);not null;index:idx_i18n_lang,priority:1" json:"language"`
	Title            string         `gorm:"type:varchar(512);not null" json:"title"`
	Content          ProseMirrorDoc `gorm:"type:jsonb" json:"content"`
	PlainText        string         `gorm:"type:text" json:"-"`
	Summary          string         `gorm:"type:text" json:"summary"`
	Status           string         `gorm:"type:varchar(32);not null;default:'draft'" json:"status"`
	TranslationType  string         `gorm:"type:varchar(32);not null;default:'manual'" json:"translation_type"`
	TranslatorID     uuid.UUID      `gorm:"type:uuid;index" json:"translator_id"`
	ProofreaderID    uuid.UUID      `gorm:"type:uuid;index" json:"proofreader_id"`
	WordCount        int            `gorm:"type:int;default:0" json:"word_count"`
	TranslationScore float64        `gorm:"type:float" json:"translation_score"`
	Version          int            `gorm:"type:int;not null;default:1" json:"version"`
	PublishedAt      *time.Time     `gorm:"" json:"published_at"`
}

func (I18nDoc) TableName() string {
	return "i18n_docs"
}

type TranslationMemory struct {
	BaseModel
	TenantScoped
	SourceLanguage string     `gorm:"type:varchar(16);not null;index:idx_tm_lang_pair,priority:1" json:"source_language"`
	TargetLanguage string     `gorm:"type:varchar(16);not null;index:idx_tm_lang_pair,priority:2" json:"target_language"`
	SourceText     string     `gorm:"type:text;not null" json:"source_text"`
	TargetText     string     `gorm:"type:text;not null" json:"target_text"`
	SourceHash     string     `gorm:"type:varchar(64);index" json:"source_hash"`
	SourceDocID    uuid.UUID  `gorm:"type:uuid;index" json:"source_doc_id"`
	TranslatorID   uuid.UUID  `gorm:"type:uuid;index" json:"translator_id"`
	QualityScore   float64    `gorm:"type:float" json:"quality_score"`
	UsageCount     int64      `gorm:"type:bigint;default:0" json:"usage_count"`
	IsApproved     bool       `gorm:"type:boolean;default:false" json:"is_approved"`
	Domain         string     `gorm:"type:varchar(128);index" json:"domain"`
	Tags           StringArray `gorm:"type:jsonb;default:'[]'" json:"tags"`
	LastUsedAt     *time.Time `gorm:"" json:"last_used_at"`
}

func (TranslationMemory) TableName() string {
	return "translation_memories"
}
