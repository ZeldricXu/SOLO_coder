package adapter

import (
	"context"
	"fmt"
	"sync"
	"time"

	"session187/internal/storage"
)

type InMemoryStorage struct {
	mu      sync.RWMutex
	objects map[string]map[string][]byte
	metas   map[string]map[string]map[string]string
}

func NewInMemoryStorage() *InMemoryStorage {
	return &InMemoryStorage{
		objects: make(map[string]map[string][]byte),
		metas:   make(map[string]map[string]map[string]string),
	}
}

func (a *InMemoryStorage) PutObject(ctx context.Context, bucket, key string, data []byte, metadata map[string]string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, ok := a.objects[bucket]; !ok {
		a.objects[bucket] = make(map[string][]byte)
		a.metas[bucket] = make(map[string]map[string]string)
	}
	a.objects[bucket][key] = data
	a.metas[bucket][key] = metadata
	return nil
}

func (a *InMemoryStorage) GetObject(ctx context.Context, bucket, key string) ([]byte, map[string]string, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	if _, ok := a.objects[bucket]; !ok {
		return nil, nil, fmt.Errorf("bucket not found")
	}
	data, ok := a.objects[bucket][key]
	if !ok {
		return nil, nil, fmt.Errorf("object not found")
	}
	meta := a.metas[bucket][key]
	return data, meta, nil
}

func (a *InMemoryStorage) DeleteObject(ctx context.Context, bucket, key string) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if _, ok := a.objects[bucket]; ok {
		delete(a.objects[bucket], key)
		delete(a.metas[bucket], key)
	}
	return nil
}

func (a *InMemoryStorage) ListObjects(ctx context.Context, bucket, prefix string) ([]storage.ObjectInfo, error) {
	a.mu.RLock()
	defer a.mu.RUnlock()
	var result []storage.ObjectInfo
	if objs, ok := a.objects[bucket]; ok {
		for k, v := range objs {
			if len(prefix) == 0 || (len(k) >= len(prefix) && k[:len(prefix)] == prefix) {
				result = append(result, storage.ObjectInfo{
					Key:          k,
					Size:         int64(len(v)),
					LastModified: time.Now(),
				})
			}
		}
	}
	return result, nil
}

func (a *InMemoryStorage) GetPresignedURL(ctx context.Context, bucket, key string, expires time.Duration) (string, error) {
	return fmt.Sprintf("https://%s.s3.amazonaws.com/%s?expires=%d", bucket, key, expires.Seconds()), nil
}
