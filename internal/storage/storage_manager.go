package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"sync"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type StorageAdapter interface {
	Put(bucket, key string, data []byte, contentType string) (*models.ObjectMetadata, error)
	Get(bucket, key string) ([]byte, error)
	Delete(bucket, key string) error
	List(bucket string) ([]*models.ObjectMetadata, error)
}

type InMemoryStorage struct {
	mu    sync.RWMutex
	data  map[string]map[string][]byte
	meta  map[string]map[string]*models.ObjectMetadata
}

func NewInMemoryStorage() *InMemoryStorage {
	return &InMemoryStorage{
		data: make(map[string]map[string][]byte),
		meta: make(map[string]map[string]*models.ObjectMetadata),
	}
}

func (s *InMemoryStorage) ensureBucket(bucket string) {
	if _, ok := s.data[bucket]; !ok {
		s.data[bucket] = make(map[string][]byte)
		s.meta[bucket] = make(map[string]*models.ObjectMetadata)
	}
}

func (s *InMemoryStorage) Put(bucket, key string, data []byte, contentType string) (*models.ObjectMetadata, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ensureBucket(bucket)
	sum := sha256.Sum256(data)
	etag := hex.EncodeToString(sum[:])
	meta := &models.ObjectMetadata{
		ID:          utils.GenerateID("obj"),
		Bucket:      bucket,
		Key:         key,
		Size:        int64(len(data)),
		ContentType: contentType,
		ETag:        etag,
		Tags:        make(map[string]string),
		CreatedAt:   utils.Now(),
	}
	s.data[bucket][key] = append([]byte(nil), data...)
	s.meta[bucket][key] = meta
	return meta, nil
}

func (s *InMemoryStorage) Get(bucket, key string) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	b, ok := s.data[bucket]
	if !ok {
		return nil, ErrBucketNotFound
	}
	data, ok := b[key]
	if !ok {
		return nil, ErrObjectNotFound
	}
	return append([]byte(nil), data...), nil
}

func (s *InMemoryStorage) Delete(bucket, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.data[bucket]; !ok {
		return ErrBucketNotFound
	}
	if _, ok := s.data[bucket][key]; !ok {
		return ErrObjectNotFound
	}
	delete(s.data[bucket], key)
	delete(s.meta[bucket], key)
	return nil
}

func (s *InMemoryStorage) List(bucket string) ([]*models.ObjectMetadata, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	b, ok := s.meta[bucket]
	if !ok {
		return nil, ErrBucketNotFound
	}
	result := make([]*models.ObjectMetadata, 0, len(b))
	for _, m := range b {
		result = append(result, m)
	}
	return result, nil
}

type MetadataIndex struct {
	mu    sync.RWMutex
	index map[string]*models.ObjectMetadata
	byTag map[string]map[string][]*models.ObjectMetadata
}

func NewMetadataIndex() *MetadataIndex {
	return &MetadataIndex{
		index: make(map[string]*models.ObjectMetadata),
		byTag: make(map[string]map[string][]*models.ObjectMetadata),
	}
}

func (i *MetadataIndex) Index(meta *models.ObjectMetadata) {
	i.mu.Lock()
	defer i.mu.Unlock()
	i.index[meta.ID] = meta
	for k, v := range meta.Tags {
		if _, ok := i.byTag[k]; !ok {
			i.byTag[k] = make(map[string][]*models.ObjectMetadata)
		}
		i.byTag[k][v] = append(i.byTag[k][v], meta)
	}
}

func (i *MetadataIndex) Get(id string) (*models.ObjectMetadata, bool) {
	i.mu.RLock()
	defer i.mu.RUnlock()
	m, ok := i.index[id]
	return m, ok
}

func (i *MetadataIndex) FindByTag(key, value string) []*models.ObjectMetadata {
	i.mu.RLock()
	defer i.mu.RUnlock()
	if tagMap, ok := i.byTag[key]; ok {
		if result, ok := tagMap[value]; ok {
			return result
		}
	}
	return nil
}

type StorageManager struct {
	adapter StorageAdapter
	index   *MetadataIndex
	cache   map[string][]byte
	mu      sync.RWMutex
}

func NewStorageManager(adapter StorageAdapter) *StorageManager {
	return &StorageManager{
		adapter: adapter,
		index:   NewMetadataIndex(),
		cache:   make(map[string][]byte),
	}
}

func (m *StorageManager) Store(bucket, key string, data []byte, contentType string, tags map[string]string) (*models.ObjectMetadata, error) {
	meta, err := m.adapter.Put(bucket, key, data, contentType)
	if err != nil {
		return nil, err
	}
	if tags != nil {
		for k, v := range tags {
			meta.Tags[k] = v
		}
	}
	m.index.Index(meta)
	cacheKey := bucket + ":" + key
	m.mu.Lock()
	m.cache[cacheKey] = append([]byte(nil), data...)
	m.mu.Unlock()
	return meta, nil
}

func (m *StorageManager) Retrieve(bucket, key string) ([]byte, error) {
	cacheKey := bucket + ":" + key
	m.mu.RLock()
	if data, ok := m.cache[cacheKey]; ok {
		m.mu.RUnlock()
		return append([]byte(nil), data...), nil
	}
	m.mu.RUnlock()
	data, err := m.adapter.Get(bucket, key)
	if err != nil {
		return nil, err
	}
	m.mu.Lock()
	m.cache[cacheKey] = append([]byte(nil), data...)
	m.mu.Unlock()
	return data, nil
}

func (m *StorageManager) Remove(bucket, key string) error {
	err := m.adapter.Delete(bucket, key)
	if err != nil {
		return err
	}
	cacheKey := bucket + ":" + key
	m.mu.Lock()
	delete(m.cache, cacheKey)
	m.mu.Unlock()
	return nil
}

func (m *StorageManager) ListObjects(bucket string) ([]*models.ObjectMetadata, error) {
	return m.adapter.List(bucket)
}

func (m *StorageManager) InvalidateCache(bucket, key string) {
	cacheKey := bucket + ":" + key
	m.mu.Lock()
	delete(m.cache, cacheKey)
	m.mu.Unlock()
}

var (
	ErrBucketNotFound = &storageError{"bucket not found"}
	ErrObjectNotFound = &storageError{"object not found"}
)

type storageError struct {
	msg string
}

func (e *storageError) Error() string { return e.msg }
