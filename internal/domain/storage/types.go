package storage

import (
	"context"
	"time"
)

type StorageObject struct {
	Key          string            `json:"key"`
	Size         int64             `json:"size"`
	ETag         string            `json:"etag"`
	LastModified time.Time         `json:"last_modified"`
	Metadata     map[string]string `json:"metadata"`
}

type LifecycleRule struct {
	ID         string        `json:"id"`
	Prefix     string        `json:"prefix"`
	ExpireDays int           `json:"expire_days"`
	Status     string        `json:"status"`
}

type StorageBackend interface {
	Upload(ctx context.Context, key string, data []byte, metadata map[string]string) error
	Download(ctx context.Context, key string) ([]byte, error)
	Delete(ctx context.Context, key string) error
	List(ctx context.Context, prefix string) ([]*StorageObject, error)
}

type StorageType string

const (
	StorageTypeS3    StorageType = "s3"
	StorageTypeLocal StorageType = "local"
	StorageTypeMinIO StorageType = "minio"
)
