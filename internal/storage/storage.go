package storage

import (
	"context"
	"io"
	"time"
)

type StorageType string

const (
	StorageTypeS3     StorageType = "s3"
	StorageTypeOSS    StorageType = "oss"
	StorageTypeLocal  StorageType = "local"
	StorageTypeMinIO  StorageType = "minio"
)

type ObjectMetadata struct {
	ID          string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Key         string            `gorm:"type:varchar(512);uniqueIndex;not null" json:"key"`
	Bucket      string            `gorm:"type:varchar(128);index;not null" json:"bucket"`
	StorageType StorageType       `gorm:"type:varchar(32);index" json:"storage_type"`
	ContentType string            `gorm:"type:varchar(128)" json:"content_type"`
	SizeBytes   int64             `json:"size_bytes"`
	Checksum    string            `gorm:"type:varchar(128)" json:"checksum"`
	Tags        map[string]string `gorm:"type:jsonb;serializer:json" json:"tags"`
	Metadata    map[string]string `gorm:"type:jsonb;serializer:json" json:"metadata"`
	VersionID   string            `gorm:"type:varchar(128)" json:"version_id,omitempty"`
	IsLatest    bool              `json:"is_latest"`
	CreatedBy   string            `gorm:"type:varchar(64)" json:"created_by"`
	AccessedAt  *time.Time        `json:"accessed_at,omitempty"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type UploadResult struct {
	Key         string `json:"key"`
	SizeBytes   int64  `json:"size_bytes"`
	Checksum    string `json:"checksum"`
	ContentType string `json:"content_type"`
}

type ObjectInfo struct {
	Key          string            `json:"key"`
	SizeBytes    int64             `json:"size_bytes"`
	ContentType  string            `json:"content_type"`
	LastModified time.Time         `json:"last_modified"`
	Metadata     map[string]string `json:"metadata"`
}

type StorageAdapter interface {
	Upload(ctx context.Context, bucket, key string, data io.Reader, contentType string, metadata map[string]string) (*UploadResult, error)
	Download(ctx context.Context, bucket, key string) (io.ReadCloser, *ObjectInfo, error)
	Delete(ctx context.Context, bucket, key string) error
	List(ctx context.Context, bucket, prefix string, maxKeys int) ([]ObjectInfo, error)
	GetPresignedURL(ctx context.Context, bucket, key string, expires time.Duration) (string, error)
	Copy(ctx context.Context, srcBucket, srcKey, dstBucket, dstKey string) error
	Exists(ctx context.Context, bucket, key string) (bool, error)
	GetType() StorageType
}

type StorageConfig struct {
	Type          StorageType
	Endpoint      string
	AccessKey     string
	SecretKey     string
	Region        string
	Bucket        string
	UseSSL        bool
	LocalBasePath string
}
