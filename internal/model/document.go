package model

import (
	"time"

	"gorm.io/gorm"
)

type Directory struct {
	BaseModel
	TenantScoped
	SpaceID     string `gorm:"type:uuid;not null;index" json:"space_id"`
	ParentID    string `gorm:"type:uuid;index" json:"parent_id"`
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	Description string `gorm:"type:text" json:"description"`
	SortOrder   int    `gorm:"type:int;default:0" json:"sort_order"`
	CreatedBy   string `gorm:"type:uuid;not null" json:"created_by"`
}

func (Directory) TableName() string {
	return "directories"
}

type Document struct {
	BaseModel
	TenantScoped
	SpaceID              string         `gorm:"type:uuid;not null;index" json:"space_id"`
	DirectoryID          string         `gorm:"type:uuid;index" json:"directory_id"`
	Title                string         `gorm:"type:varchar(500);not null" json:"title"`
	Slug                 string         `gorm:"type:varchar(500);index" json:"slug"`
	Summary              string         `gorm:"type:text" json:"summary"`
	Content              ProseMirrorDoc `gorm:"type:jsonb" json:"content"`
	ContentText          string         `gorm:"type:text" json:"content_text"`
	ContentType          string         `gorm:"type:varchar(50);default:'markdown'" json:"content_type"`
	LangCode             string         `gorm:"type:varchar(16);default:'zh-CN'" json:"lang_code"`
	Category             string         `gorm:"type:varchar(100);index" json:"category"`
	Tags                 []string       `gorm:"type:text[]" json:"tags"`
	Status               string         `gorm:"type:varchar(32);not null;default:'draft'" json:"status"`
	Priority             int            `gorm:"type:int;default:0" json:"priority"`
	Version              int            `gorm:"type:int;default:1" json:"version"`
	ViewCount            int64          `gorm:"type:bigint;default:0" json:"view_count"`
	LikeCount            int64          `gorm:"type:bigint;default:0" json:"like_count"`
	CommentCount         int64          `gorm:"type:bigint;default:0" json:"comment_count"`
	IsPublic             bool           `gorm:"type:boolean;default:false" json:"is_public"`
	IsPinned             bool           `gorm:"type:boolean;default:false" json:"is_pinned"`
	PublishedAt          *time.Time     `json:"published_at"`
	CreatedBy            string         `gorm:"type:uuid;not null" json:"created_by"`
	UpdatedBy            string         `gorm:"type:uuid" json:"updated_by"`
	ParentDocID          string         `gorm:"type:uuid;index" json:"parent_doc_id"`
	OriginalID           string         `gorm:"type:uuid;index" json:"original_id"`
	Metadata             JSONB          `gorm:"type:jsonb" json:"metadata"`
	IsBaseLang           bool           `gorm:"type:boolean;default:true" json:"is_base_lang"`
	BaseDocID            string         `gorm:"type:uuid;index" json:"base_doc_id"`
	BaseVersion          int            `gorm:"type:int;default:0" json:"base_version"`
	NeedsReTranslation   bool           `gorm:"type:boolean;default:false" json:"needs_retranslation"`
	LastTranslationAt    *time.Time     `json:"last_translation_at"`
	TranslationProgress  int            `gorm:"type:int;default:0" json:"translation_progress"`
}

func (Document) TableName() string {
	return "documents"
}

type DocumentVersion struct {
	BaseModel
	TenantScoped
	DocID        string         `gorm:"type:uuid;not null;index" json:"doc_id"`
	SpaceID      string         `gorm:"type:uuid;not null;index" json:"space_id"`
	Title        string         `gorm:"type:varchar(500)" json:"title"`
	Content      ProseMirrorDoc `gorm:"type:jsonb" json:"content"`
	ContentText  string         `gorm:"type:text" json:"content_text"`
	Version      int            `gorm:"type:int;not null" json:"version"`
	ChangeLog    string         `gorm:"type:text" json:"change_log"`
	CreatedBy    string         `gorm:"type:uuid;not null" json:"created_by"`
	Operations   JSONB          `gorm:"type:jsonb" json:"operations"`
}

func (DocumentVersion) TableName() string {
	return "document_versions"
}

type Attachment struct {
	BaseModel
	TenantScoped
	SpaceID       string `gorm:"type:uuid;not null;index" json:"space_id"`
	DocID         string `gorm:"type:uuid;index" json:"doc_id"`
	FileName      string `gorm:"type:varchar(500);not null" json:"file_name"`
	OriginalName  string `gorm:"type:varchar(500)" json:"original_name"`
	FileType      string `gorm:"type:varchar(100);index" json:"file_type"`
	FileSize      int64  `gorm:"type:bigint;not null" json:"file_size"`
	StoragePath   string `gorm:"type:varchar(1000)" json:"storage_path"`
	StorageType    string `gorm:"type:varchar(50);default:'minio'" json:"storage_type"`
	Md5Hash       string `gorm:"type:varchar(64);index" json:"md5_hash"`
	DownloadCount int64  `gorm:"type:bigint;default:0" json:"download_count"`
	UploadedBy   string `gorm:"type:uuid;not null" json:"uploaded_by"`
	IsParsed     bool   `gorm:"type:boolean;default:false" json:"is_parsed"`
	ParseStatus  string `gorm:"type:varchar(32);default:'pending'" json:"parse_status"`
	ExtractedText string `gorm:"type:text" json:"extracted_text"`
}

func (Attachment) TableName() string {
	return "attachments"
}

type ApiToken struct {
	BaseModel
	TenantScoped
	Name        string    `gorm:"type:varchar(255);not null" json:"name"`
	Token       string    `gorm:"type:varchar(500);not null;uniqueIndex" json:"token"`
	TokenHash   string    `gorm:"type:varchar(255);index" json:"token_hash"`
	Description string    `gorm:"type:text" json:"description"`
	UserID      string    `gorm:"type:uuid;not null;index" json:"user_id"`
	Permissions []string  `gorm:"type:text[]" json:"permissions"`
	ExpiresAt   *time.Time `json:"expires_at"`
	LastUsedAt  *time.Time `json:"last_used_at"`
	IsActive    bool      `gorm:"type:boolean;default:true" json:"is_active"`
}

func (ApiToken) TableName() string {
	return "api_tokens"
}

type I18nDoc struct {
	BaseModel
	TenantScoped
	SourceDocID          string `gorm:"type:uuid;not null;index" json:"source_doc_id"`
	SourceLang           string `gorm:"type:varchar(16);not null" json:"source_lang"`
	TargetLang           string `gorm:"type:varchar(16);not null" json:"target_lang"`
	TargetDocID          string `gorm:"type:uuid;index" json:"target_doc_id"`
	Status               string `gorm:"type:varchar(32);default:'draft'" json:"status"`
	Progress             int    `gorm:"type:int;default:0" json:"progress"`
	TranslatedBy         string `gorm:"type:uuid" json:"translated_by"`
	ReviewedBy           string `gorm:"type:uuid" json:"reviewed_by"`
	BaseVersionForked    int    `gorm:"type:int;default:0" json:"base_version_forked"`
	LastBaseVersionSynced int   `gorm:"type:int;default:0" json:"last_base_version_synced"`
	DiffToBase           string `gorm:"type:text" json:"diff_to_base"`
	Metadata             JSONB  `gorm:"type:jsonb" json:"metadata"`
}

func (I18nDoc) TableName() string {
	return "i18n_docs"
}

type TranslationMemory struct {
	BaseModel
	TenantScoped
	SourceLang    string `gorm:"type:varchar(16);not null;index:idx_tm_lang" json:"source_lang"`
	TargetLang    string `gorm:"type:varchar(16);not null;index:idx_tm_lang" json:"target_lang"`
	SourceText  string `gorm:"type:text;not null" json:"source_text"`
	TargetText  string `gorm:"type:text;not null" json:"target_text"`
	SourceDocID string `gorm:"type:uuid;index" json:"source_doc_id"`
	Domain        string `gorm:"type:varchar(100);index" json:"domain"`
	UsageCount  int64  `gorm:"type:bigint;default:0" json:"usage_count"`
	Quality     float64 `gorm:"type:float;default:1.0" json:"quality"`
	SourceMD5   string `gorm:"type:varchar(64);index" json:"source_md5"`
}

func (TranslationMemory) TableName() string {
	return "translation_memories"
}

type Quota struct {
	BaseModel
	TenantScoped
	UserID          string `gorm:"type:uuid;index" json:"user_id"`
	StorageLimit    int64  `gorm:"type:bigint;default:0" json:"storage_limit"`
	StorageUsed     int64  `gorm:"type:bigint;default:0" json:"storage_used"`
	DocLimit        int    `gorm:"type:int;default:0" json:"doc_limit"`
	DocCount         int    `gorm:"type:int;default:0" json:"doc_count"`
	UserLimit       int    `gorm:"type:int;default:0" json:"user_limit"`
	UserCount      int    `gorm:"type:int;default:0" json:"user_count"`
	ApiCallLimit   int64  `gorm:"type:bigint;default:0" json:"api_call_limit"`
	ApiCallCount  int64  `gorm:"type:bigint;default:0" json:"api_call_count"`
	PlanType       string `gorm:"type:varchar(50);default:'free'" json:"plan_type"`
}

func (Quota) TableName() string {
	return "quotas"
}

type Theme struct {
	BaseModel
	TenantScoped
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	IsDefault   bool   `gorm:"type:boolean;default:false" json:"is_default"`
	PrimaryColor    string `gorm:"type:varchar(32)" json:"primary_color"`
	SecondaryColor string `gorm:"type:varchar(32)" json:"secondary_color"`
	AccentColor  string `gorm:"type:varchar(32)" json:"accent_color"`
	LogoURL       string `gorm:"type:varchar(500)" json:"logo_url"`
	FaviconURL    string `gorm:"type:varchar(500)" json:"favicon_url"`
	CSS           string `gorm:"type:text" json:"css"`
	Config        JSONB  `gorm:"type:jsonb" json:"config"`
	IsSystem       bool   `gorm:"type:boolean;default:false" json:"is_system"`
}

func (Theme) TableName() string {
	return "themes"
}

type SearchIndex struct {
	BaseModel
	TenantScoped
	IndexName    string    `gorm:"type:varchar(255);not null;index" json:"index_name"`
	IndexType string    `gorm:"type:varchar(50);not null" json:"index_type"`
	DocCount   int64     `gorm:"type:bigint;default:0" json:"doc_count"`
	LastSyncAt *time.Time `json:"last_sync_at"`
	Status       string    `gorm:"type:varchar(32);default:'ready'" json:"status"`
	SizeBytes  int64     `gorm:"type:bigint;default:0" json:"size_bytes"`
	Config     JSONB     `gorm:"type:jsonb" json:"config"`
	ErrorMsg   string    `gorm:"type:text" json:"error_msg"`
}

func (SearchIndex) TableName() string {
	return "search_indices"
}

type SnapshotPolicy struct {
	BaseModel
	TenantScoped
	SpaceID            string     `gorm:"type:uuid;not null;index" json:"space_id"`
	Name               string     `gorm:"type:varchar(255);not null" json:"name"`
	Frequency          string     `gorm:"type:varchar(32);not null;default:'daily'" json:"frequency"`
	CronExpr           string     `gorm:"type:varchar(128)" json:"cron_expr"`
	Hour               int        `gorm:"type:int;default:2" json:"hour"`
	DayOfWeek          int        `gorm:"type:int;default:0" json:"day_of_week"`
	DayOfMonth         int        `gorm:"type:int;default:1" json:"day_of_month"`
	RetentionDays      int        `gorm:"type:int;default:90" json:"retention_days"`
	IncludeAttachments bool       `gorm:"type:boolean;default:true" json:"include_attachments"`
	IncludeDeleted     bool       `gorm:"type:boolean;default:false" json:"include_deleted"`
	IsEnabled          bool       `gorm:"type:boolean;default:true" json:"is_enabled"`
	LastRunAt          *time.Time `json:"last_run_at"`
	NextRunAt          *time.Time `json:"next_run_at"`
	CreatedBy          string     `gorm:"type:uuid;not null" json:"created_by"`
}

func (SnapshotPolicy) TableName() string { return "snapshot_policies" }

type SpaceSnapshot struct {
	BaseModel
	TenantScoped
	SpaceID         string     `gorm:"type:uuid;not null;index" json:"space_id"`
	PolicyID        string     `gorm:"type:uuid;index" json:"policy_id"`
	Name            string     `gorm:"type:varchar(500);not null" json:"name"`
	Description     string     `gorm:"type:text" json:"description"`
	SnapshotType    string     `gorm:"type:varchar(32);default:'automatic'" json:"snapshot_type"`
	Status          string     `gorm:"type:varchar(32);default:'pending'" json:"status"`
	StorageType     string     `gorm:"type:varchar(32);default:'minio'" json:"storage_type"`
	StoragePath     string     `gorm:"type:varchar(1000)" json:"storage_path"`
	ArchiveSize     int64      `gorm:"type:bigint;default:0" json:"archive_size"`
	DocCount        int        `gorm:"type:int;default:0" json:"doc_count"`
	VersionCount    int        `gorm:"type:int;default:0" json:"version_count"`
	AttachmentCount int        `gorm:"type:int;default:0" json:"attachment_count"`
	DirCount        int        `gorm:"type:int;default:0" json:"dir_count"`
	ChecksumSHA256  string     `gorm:"type:varchar(64)" json:"checksum_sha256"`
	ErrorMsg        string     `gorm:"type:text" json:"error_msg"`
	ExpireAt        *time.Time `json:"expire_at"`
	CreatedBy       string     `gorm:"type:uuid" json:"created_by"`
	CompletedAt     *time.Time `json:"completed_at"`
}

func (SpaceSnapshot) TableName() string { return "space_snapshots" }

var _ = gorm.DeletedAt{}
