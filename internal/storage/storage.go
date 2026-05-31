package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/models"
	"github.com/google/uuid"
)

type StorageType string

const (
	StorageS3     StorageType = "s3"
	StorageLocal  StorageType = "local"
	StorageMemory StorageType = "memory"
	StorageGCS    StorageType = "gcs"
)

type ObjectInfo struct {
	Key          string
	Size         int64
	ContentType  string
	ETag         string
	LastModified time.Time
	Metadata     map[string]string
}

type ObjectStorage interface {
	Put(ctx context.Context, key string, data []byte, metadata map[string]string) error
	Get(ctx context.Context, key string) ([]byte, error)
	Delete(ctx context.Context, key string) error
	List(ctx context.Context, prefix string) ([]ObjectInfo, error)
	Exists(ctx context.Context, key string) (bool, error)
}

type MemoryStorage struct {
	objects map[string][]byte
	meta    map[string]map[string]string
	mu      sync.RWMutex
}

func NewMemoryStorage() *MemoryStorage {
	return &MemoryStorage{
		objects: make(map[string][]byte),
		meta:    make(map[string]map[string]string),
	}
}

func (s *MemoryStorage) Put(ctx context.Context, key string, data []byte, metadata map[string]string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.objects[key] = data
	if metadata != nil {
		s.meta[key] = metadata
	}
	return nil
}

func (s *MemoryStorage) Get(ctx context.Context, key string) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	data, ok := s.objects[key]
	if !ok {
		return nil, fmt.Errorf("object not found: %s", key)
	}
	return data, nil
}

func (s *MemoryStorage) Delete(ctx context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.objects, key)
	delete(s.meta, key)
	return nil
}

func (s *MemoryStorage) List(ctx context.Context, prefix string) ([]ObjectInfo, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]ObjectInfo, 0)
	for key, data := range s.objects {
		if len(prefix) == 0 || len(key) >= len(prefix) && key[:len(prefix)] == prefix {
			info := ObjectInfo{
				Key:          key,
				Size:         int64(len(data)),
				LastModified: time.Now(),
			}
			if meta, ok := s.meta[key]; ok {
				info.Metadata = meta
			}
			result = append(result, info)
		}
	}
	return result, nil
}

func (s *MemoryStorage) Exists(ctx context.Context, key string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, ok := s.objects[key]
	return ok, nil
}

type MetadataIndex struct {
	ID          string                 `json:"id"`
	ObjectKey   string                 `json:"object_key"`
	StorageType StorageType            `json:"storage_type"`
	Tags        map[string]string      `json:"tags"`
	Attributes  map[string]interface{} `json:"attributes"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type StorageManager struct {
	storages    map[StorageType]ObjectStorage
	metadata    map[string]*MetadataIndex
	mu          sync.RWMutex
	defaultType StorageType
}

func NewStorageManager() *StorageManager {
	sm := &StorageManager{
		storages:    make(map[StorageType]ObjectStorage),
		metadata:    make(map[string]*MetadataIndex),
		defaultType: StorageMemory,
	}
	sm.storages[StorageMemory] = NewMemoryStorage()
	return sm
}

func (sm *StorageManager) RegisterStorage(storageType StorageType, storage ObjectStorage) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.storages[storageType] = storage
}

func (sm *StorageManager) SetDefault(storageType StorageType) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.defaultType = storageType
}

func (sm *StorageManager) Store(ctx context.Context, key string, data []byte, tags map[string]string, attributes map[string]interface{}) (*MetadataIndex, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	storage, ok := sm.storages[sm.defaultType]
	if !ok {
		return nil, errors.New("default storage not available")
	}

	if err := storage.Put(ctx, key, data, nil); err != nil {
		return nil, fmt.Errorf("failed to store object: %w", err)
	}

	meta := &MetadataIndex{
		ID:          uuid.New().String(),
		ObjectKey:   key,
		StorageType: sm.defaultType,
		Tags:        tags,
		Attributes:  attributes,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}
	sm.metadata[meta.ID] = meta

	return meta, nil
}

func (sm *StorageManager) Retrieve(ctx context.Context, key string) ([]byte, *MetadataIndex, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	for _, meta := range sm.metadata {
		if meta.ObjectKey == key {
			storage, ok := sm.storages[meta.StorageType]
			if !ok {
				return nil, nil, errors.New("storage type not available")
			}
			data, err := storage.Get(ctx, key)
			if err != nil {
				return nil, nil, err
			}
			return data, meta, nil
		}
	}
	return nil, nil, fmt.Errorf("object not found: %s", key)
}

func (sm *StorageManager) Delete(ctx context.Context, key string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	for id, meta := range sm.metadata {
		if meta.ObjectKey == key {
			storage, ok := sm.storages[meta.StorageType]
			if ok {
				storage.Delete(ctx, key)
			}
			delete(sm.metadata, id)
			return nil
		}
	}
	return fmt.Errorf("object not found: %s", key)
}

func (sm *StorageManager) FindByTag(tagKey, tagValue string) []*MetadataIndex {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	result := make([]*MetadataIndex, 0)
	for _, meta := range sm.metadata {
		if v, ok := meta.Tags[tagKey]; ok && v == tagValue {
			result = append(result, meta)
		}
	}
	return result
}

func (sm *StorageManager) ListMetadata() []*MetadataIndex {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	result := make([]*MetadataIndex, 0, len(sm.metadata))
	for _, meta := range sm.metadata {
		result = append(result, meta)
	}
	return result
}

func (sm *StorageManager) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "storage_manager",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

type StreamWriter struct {
	storage    ObjectStorage
	key        string
	buffer     []byte
	bufferSize int
}

func NewStreamWriter(storage ObjectStorage, key string, bufferSize int) *StreamWriter {
	return &StreamWriter{
		storage:    storage,
		key:        key,
		buffer:     make([]byte, 0, bufferSize),
		bufferSize: bufferSize,
	}
}

func (w *StreamWriter) Write(p []byte) (n int, err error) {
	w.buffer = append(w.buffer, p...)
	if len(w.buffer) >= w.bufferSize {
		if err := w.Flush(); err != nil {
			return 0, err
		}
	}
	return len(p), nil
}

func (w *StreamWriter) Flush() error {
	if len(w.buffer) == 0 {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := w.storage.Put(ctx, w.key, w.buffer, nil); err != nil {
		return err
	}
	w.buffer = w.buffer[:0]
	return nil
}

func (w *StreamWriter) Close() error {
	return w.Flush()
}

type StreamReader struct {
	storage ObjectStorage
	key     string
	data    []byte
	offset  int
}

func NewStreamReader(ctx context.Context, storage ObjectStorage, key string) (*StreamReader, error) {
	data, err := storage.Get(ctx, key)
	if err != nil {
		return nil, err
	}
	return &StreamReader{
		storage: storage,
		key:     key,
		data:    data,
		offset:  0,
	}, nil
}

func (r *StreamReader) Read(p []byte) (n int, err error) {
	if r.offset >= len(r.data) {
		return 0, io.EOF
	}
	n = copy(p, r.data[r.offset:])
	r.offset += n
	return n, nil
}

func (r *StreamReader) Close() error {
	r.data = nil
	return nil
}
