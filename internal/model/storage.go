package model

import "time"

type FileStatus string

const (
	FileStatusUploading FileStatus = "uploading"
	FileStatusActive    FileStatus = "active"
	FileStatusArchived  FileStatus = "archived"
	FileStatusDeleted   FileStatus = "deleted"
)

type StorageBackend string

const (
	BackendLocal  StorageBackend = "local"
	BackendS3     StorageBackend = "s3"
	BackendMinIO  StorageBackend = "minio"
	BackendOSS    StorageBackend = "oss"
)

type LifecyclePolicy struct {
	ID               string        `json:"id"`
	Name             string        `json:"name"`
	Description      string        `json:"description"`
	Enabled          bool          `json:"enabled"`
	Prefix           string        `json:"prefix"`
	Tags             []string      `json:"tags"`
	TransitionDays   int           `json:"transition_days"`
	ExpirationDays   int           `json:"expiration_days"`
	DeleteAfterDays  int           `json:"delete_after_days"`
}

type FileRecord struct {
	FileID         string                 `json:"file_id" gorm:"primaryKey;type:varchar(64)"`
	Name           string                 `json:"name" gorm:"type:varchar(255);index"`
	Path           string                 `json:"path" gorm:"type:varchar(512);index"`
	SizeBytes      int64                  `json:"size_bytes"`
	ContentType    string                 `json:"content_type" gorm:"type:varchar(128)"`
	Checksum       string                 `json:"checksum" gorm:"type:varchar(64)"`
	Backend        StorageBackend         `json:"backend" gorm:"type:varchar(32);index"`
	Status         FileStatus             `json:"status" gorm:"type:varchar(32);index"`
	Tags           []string               `json:"tags" gorm:"type:jsonb"`
	Metadata       map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	UploadedBy     string                 `json:"uploaded_by" gorm:"type:varchar(64)"`
	ExpiresAt      *time.Time             `json:"expires_at" gorm:"index"`
	ArchivedAt     *time.Time             `json:"archived_at"`
	DownloadCount  int64                  `json:"download_count" gorm:"default:0"`
	LastAccessedAt *time.Time             `json:"last_accessed_at" gorm:"index"`
	CreatedAt      time.Time              `json:"created_at" gorm:"index"`
	UpdatedAt      time.Time              `json:"updated_at" gorm:"index"`
}

func (f *FileRecord) TableName() string {
	return "file_records"
}

type FileUploadRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Path        string                 `json:"path"`
	ContentType string                 `json:"content_type"`
	Backend     StorageBackend         `json:"backend"`
	Tags        []string               `json:"tags"`
	Metadata    map[string]interface{} `json:"metadata"`
	TTLSeconds  int                    `json:"ttl_seconds"`
}

type FileDownloadResponse struct {
	FileID      string    `json:"file_id"`
	Name        string    `json:"name"`
	SizeBytes   int64     `json:"size_bytes"`
	ContentType string    `json:"content_type"`
	DownloadURL string    `json:"download_url"`
	ExpiresAt   time.Time `json:"expires_at"`
}
