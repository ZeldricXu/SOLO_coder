package storage

import "time"

type ObjectInfo struct {
	Key          string
	Size         int64
	LastModified time.Time
	ETag         string
	Metadata     map[string]string
}

type ObjectMetadata struct {
	ID           string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID     string                 `json:"tenant_id" gorm:"type:varchar(64);index"`
	Bucket       string                 `json:"bucket" gorm:"type:varchar(64);index"`
	ObjectKey    string                 `json:"object_key" gorm:"type:varchar(512);index"`
	FileName     string                 `json:"file_name" gorm:"type:varchar(256)"`
	ContentType  string                 `json:"content_type" gorm:"type:varchar(128)"`
	Size         int64                  `json:"size"`
	ETag         string                 `json:"etag" gorm:"type:varchar(64)"`
	Tags         map[string]string      `json:"tags" gorm:"type:jsonb"`
	CustomMeta   map[string]interface{} `json:"custom_meta" gorm:"type:jsonb"`
	Version      int                    `json:"version" gorm:"default:1"`
	Status       string                 `json:"status" gorm:"type:varchar(32);index"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
	AccessedAt   *time.Time             `json:"accessed_at"`
}

type Bucket struct {
	ID        string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	TenantID  string    `json:"tenant_id" gorm:"type:varchar(64);index"`
	Name      string    `json:"name" gorm:"type:varchar(64);uniqueIndex"`
	Region    string    `json:"region" gorm:"type:varchar(32)"`
	ACL       string    `json:"acl" gorm:"type:varchar(32)"`
	Status    string    `json:"status" gorm:"type:varchar(32);index"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (o *ObjectMetadata) TableName() string {
	return "object_metadata"
}

func (b *Bucket) TableName() string {
	return "buckets"
}
