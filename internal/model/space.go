package model

import (
	"time"

	"github.com/google/uuid"
)

type SpaceStatus string

const (
	SpaceStatusActive   SpaceStatus = "active"
	SpaceStatusArchived SpaceStatus = "archived"
	SpaceStatusDisabled SpaceStatus = "disabled"
)

type SpaceVisibility string

const (
	SpaceVisibilityPublic   SpaceVisibility = "public"
	SpaceVisibilityInternal SpaceVisibility = "internal"
	SpaceVisibilityPrivate  SpaceVisibility = "private"
)

type Space struct {
	BaseModel
	TenantScoped
	Name          string          `gorm:"type:varchar(255);not null" json:"name"`
	Namespace     string          `gorm:"type:varchar(100);not null;uniqueIndex:idx_space_tenant_namespace,priority:2" json:"namespace"`
	Slug          string          `gorm:"type:varchar(255)" json:"slug"`
	Description   string          `gorm:"type:text" json:"description"`
	Icon          string          `gorm:"type:varchar(64)" json:"icon"`
	Color         string          `gorm:"type:varchar(16)" json:"color"`
	AvatarURL     string          `gorm:"type:varchar(500)" json:"avatar_url"`
	Status        SpaceStatus     `gorm:"type:varchar(20);not null;default:'active'" json:"status"`
	Visibility    SpaceVisibility `gorm:"type:varchar(20);not null;default:'private'" json:"visibility"`
	DefaultRole   string          `gorm:"type:varchar(32);default:'viewer'" json:"default_role"`
	OwnerID       uuid.UUID       `gorm:"type:uuid;not null;index" json:"owner_id"`
	SortOrder     int             `gorm:"type:int;default:0" json:"sort_order"`
	Settings      JSONB           `gorm:"type:jsonb" json:"settings"`
	DocumentCount int             `gorm:"type:int;default:0" json:"document_count"`
	StorageUsed   int64           `gorm:"type:bigint;default:0" json:"storage_used"`
}

func (Space) TableName() string {
	return "spaces"
}

type DocumentStatus string

const (
	DocumentStatusDraft     DocumentStatus = "draft"
	DocumentStatusPublished DocumentStatus = "published"
	DocumentStatusArchived  DocumentStatus = "archived"
	DocumentStatusDeleted   DocumentStatus = "deleted"
)

type Document struct {
	BaseModel
	TenantScoped
	SpaceID        uuid.UUID      `gorm:"type:uuid;not null;index" json:"space_id"`
	DirectoryID    *uuid.UUID     `gorm:"type:uuid;index" json:"directory_id,omitempty"`
	Title          string         `gorm:"type:varchar(512);not null" json:"title"`
	Slug           string         `gorm:"type:varchar(512);index" json:"slug"`
	Summary        string         `gorm:"type:text" json:"summary"`
	Content        ProseMirrorDoc `gorm:"type:jsonb" json:"content"`
	PlainText      string         `gorm:"type:text" json:"-"`
	Status         DocumentStatus `gorm:"type:varchar(32);not null;default:'draft'" json:"status"`
	Visibility     string         `gorm:"type:varchar(32);not null;default:'private'" json:"visibility"`
	Tags           StringArray    `gorm:"type:jsonb;default:'[]'" json:"tags"`
	Language       string         `gorm:"type:varchar(16);default:'zh-CN'" json:"language"`
	FormatVersion  int            `gorm:"type:int;not null;default:1" json:"format_version"`
	CurrentVersion int            `gorm:"type:int;not null;default:1" json:"current_version"`
	WordCount      int            `gorm:"type:int;default:0" json:"word_count"`
	ViewCount      int64          `gorm:"type:bigint;default:0" json:"view_count"`
	LikeCount      int64          `gorm:"type:bigint;default:0" json:"like_count"`
	CommentCount   int64          `gorm:"type:bigint;default:0" json:"comment_count"`
	AuthorID       uuid.UUID      `gorm:"type:uuid;not null;index" json:"author_id"`
	LastEditorID   uuid.UUID      `gorm:"type:uuid;index" json:"last_editor_id"`
	PublishedAt    *time.Time     `gorm:"" json:"published_at"`
	SortOrder      int            `gorm:"type:int;default:0" json:"sort_order"`
	IsPinned       bool           `gorm:"type:boolean;default:false" json:"is_pinned"`
	IsTemplate     bool           `gorm:"type:boolean;default:false" json:"is_template"`
	ParentID       *uuid.UUID     `gorm:"type:uuid;index" json:"parent_id"`
	TemplateID     *uuid.UUID     `gorm:"type:uuid;index" json:"template_id,omitempty"`

	CurrentVersionObj *DocumentVersion `gorm:"-" json:"current_version_obj,omitempty"`
	Directory         *Directory       `gorm:"foreignKey:DirectoryID" json:"directory,omitempty"`
	Author            *User            `gorm:"foreignKey:AuthorID" json:"author,omitempty"`
	Attachments       []Attachment     `gorm:"foreignKey:DocumentID" json:"attachments,omitempty"`
}

func (Document) TableName() string {
	return "documents"
}

type DocumentQuery struct {
	TenantID     uuid.UUID
	SpaceID      uuid.UUID
	DirectoryID  *uuid.UUID
	Tag          string
	Status       DocumentStatus
	Keyword      string
	AuthorID     uuid.UUID
	Language     string
	IsPinned     *bool
	Page         int
	PageSize     int
	SortBy       string
	SortOrder    string
}

type Directory struct {
	BaseModel
	TenantScoped
	SpaceID       uuid.UUID  `gorm:"type:uuid;not null;index" json:"space_id"`
	ParentID      *uuid.UUID `gorm:"type:uuid;index" json:"parent_id,omitempty"`
	Name          string     `gorm:"type:varchar(255);not null" json:"name"`
	Description   string     `gorm:"type:text" json:"description"`
	Icon          string     `gorm:"type:varchar(64)" json:"icon"`
	Color         string     `gorm:"type:varchar(16)" json:"color"`
	SortOrder     int        `gorm:"type:int;default:0" json:"sort_order"`
	DocumentCount int        `gorm:"type:int;default:0" json:"document_count"`
	CreatorID     uuid.UUID  `gorm:"type:uuid;not null;index" json:"creator_id"`

	Parent   *Directory   `gorm:"foreignKey:ParentID" json:"parent,omitempty"`
	Children []*Directory `gorm:"foreignKey:ParentID" json:"children,omitempty"`
	Space    *Space       `gorm:"foreignKey:SpaceID" json:"space,omitempty"`
}

func (Directory) TableName() string {
	return "directories"
}

type DocumentVersion struct {
	BaseModel
	TenantScoped
	DocumentID   uuid.UUID      `gorm:"type:uuid;not null;index:idx_ver_doc_version,priority:1" json:"document_id"`
	Version      int            `gorm:"type:int;not null;index:idx_ver_doc_version,priority:2" json:"version"`
	Title        string         `gorm:"type:varchar(512);not null" json:"title"`
	Content      ProseMirrorDoc `gorm:"type:jsonb" json:"content"`
	PlainText    string         `gorm:"type:text" json:"-"`
	Summary      string         `gorm:"type:text" json:"summary"`
	Tags         StringArray    `gorm:"type:jsonb;default:'[]'" json:"tags"`
	EditorID     uuid.UUID      `gorm:"type:uuid;not null;index" json:"editor_id"`
	ChangeLog    string         `gorm:"type:text" json:"change_log"`
	WordCount    int            `gorm:"type:int;default:0" json:"word_count"`
	DiffData     JSONB          `gorm:"type:jsonb" json:"diff_data"`
	SnapshotLabel string        `gorm:"type:varchar(500)" json:"snapshot_label,omitempty"`
	SizeBytes    int64          `gorm:"type:bigint;default:0" json:"size_bytes"`

	Document *Document `gorm:"foreignKey:DocumentID" json:"document,omitempty"`
	Editor   *User     `gorm:"foreignKey:EditorID" json:"editor,omitempty"`
}

func (DocumentVersion) TableName() string {
	return "document_versions"
}

type DocumentTemplate struct {
	BaseModel
	TenantScoped
	SpaceID     uuid.UUID      `gorm:"type:uuid;index" json:"space_id,omitempty"`
	Name        string         `gorm:"type:varchar(255);not null" json:"name"`
	Description string         `gorm:"type:varchar(1000)" json:"description,omitempty"`
	Category    string         `gorm:"type:varchar(100)" json:"category"`
	Content     ProseMirrorDoc `gorm:"type:jsonb;not null" json:"content"`
	Variables   StringArray    `gorm:"type:jsonb;default:'[]'" json:"variables"`
	PreviewHTML string         `gorm:"type:text" json:"preview_html"`
	Thumbnail   string         `gorm:"type:varchar(500)" json:"thumbnail"`
	IsPublic    bool           `gorm:"type:boolean;default:false" json:"is_public"`
	IsSystem    bool           `gorm:"type:boolean;default:false" json:"is_system"`
	UseCount    int            `gorm:"type:int;default:0" json:"use_count"`
	CreatedBy   uuid.UUID      `gorm:"type:uuid;not null" json:"created_by"`
}

func (DocumentTemplate) TableName() string {
	return "document_templates"
}

type Attachment struct {
	BaseModel
	TenantScoped
	SpaceID       uuid.UUID  `gorm:"type:uuid;not null;index" json:"space_id"`
	DocumentID    *uuid.UUID `gorm:"type:uuid;index" json:"document_id,omitempty"`
	FileName      string     `gorm:"type:varchar(512);not null" json:"file_name"`
	OriginalName  string     `gorm:"type:varchar(512);not null" json:"original_name"`
	FilePath      string     `gorm:"type:varchar(1024);not null" json:"file_path"`
	MimeType      string     `gorm:"type:varchar(128)" json:"mime_type"`
	FileSize      int64      `gorm:"type:bigint;not null;default:0" json:"file_size"`
	FileExtension string     `gorm:"type:varchar(32)" json:"file_extension"`
	StorageType   string     `gorm:"type:varchar(32);not null;default:'minio'" json:"storage_type"`
	ETag          string     `gorm:"type:varchar(256)" json:"etag"`
	Hash          string     `gorm:"type:varchar(128)" json:"hash"`
	Width         int        `gorm:"type:int" json:"width"`
	Height        int        `gorm:"type:int" json:"height"`
	IsImage       bool       `gorm:"type:boolean;default:false" json:"is_image"`
	ExtractedText string     `gorm:"type:text" json:"extracted_text,omitempty"`
	UploaderID    uuid.UUID  `gorm:"type:uuid;not null;index" json:"uploader_id"`
	DownloadCount int        `gorm:"type:int;default:0" json:"download_count"`
}

func (Attachment) TableName() string {
	return "attachments"
}
