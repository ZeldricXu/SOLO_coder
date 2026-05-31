package adapter

import (
	"context"
	"time"

	"session187/internal/storage"
)

type ObjectStorage interface {
	PutObject(ctx context.Context, bucket, key string, data []byte, metadata map[string]string) error
	GetObject(ctx context.Context, bucket, key string) ([]byte, map[string]string, error)
	DeleteObject(ctx context.Context, bucket, key string) error
	ListObjects(ctx context.Context, bucket, prefix string) ([]storage.ObjectInfo, error)
	GetPresignedURL(ctx context.Context, bucket, key string, expires time.Duration) (string, error)
}
