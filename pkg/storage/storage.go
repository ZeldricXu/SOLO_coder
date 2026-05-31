package storage

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type ObjectStorageAdapter interface {
	Store(ctx context.Context, obj *interfaces.StorageObject) error
	Retrieve(ctx context.Context, key string) (*interfaces.StorageObject, error)
	Delete(ctx context.Context, key string) error
	List(ctx context.Context, prefix string) ([]*interfaces.StorageObject, error)
}

type InMemoryStorage struct {
	objects map[string]*interfaces.StorageObject
	logger  *zap.Logger
	mu      sync.RWMutex
}

func NewInMemoryStorage() *InMemoryStorage {
	return &InMemoryStorage{
		objects: make(map[string]*interfaces.StorageObject),
		logger:  utils.GetLogger(),
	}
}

func (s *InMemoryStorage) Store(ctx context.Context, obj *interfaces.StorageObject) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.objects[obj.Key] = obj
	s.logger.Debug("Object stored in memory", zap.String("key", obj.Key))
	return nil
}

func (s *InMemoryStorage) Retrieve(ctx context.Context, key string) (*interfaces.StorageObject, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	obj, exists := s.objects[key]
	if !exists {
		return nil, utils.ErrNotFound
	}
	return obj, nil
}

func (s *InMemoryStorage) Delete(ctx context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.objects[key]; !exists {
		return utils.ErrNotFound
	}

	delete(s.objects, key)
	s.logger.Debug("Object deleted from memory", zap.String("key", key))
	return nil
}

func (s *InMemoryStorage) List(ctx context.Context, prefix string) ([]*interfaces.StorageObject, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var result []*interfaces.StorageObject
	for key, obj := range s.objects {
		if strings.HasPrefix(key, prefix) {
			result = append(result, obj)
		}
	}
	return result, nil
}

type DefaultStorageManager struct {
	adapter    ObjectStorageAdapter
	metadata   map[string]map[string]interface{}
	index      map[string][]string
	logger     *zap.Logger
	mu         sync.RWMutex
}

func NewDefaultStorageManager(adapter ObjectStorageAdapter) *DefaultStorageManager {
	if adapter == nil {
		adapter = NewInMemoryStorage()
	}

	return &DefaultStorageManager{
		adapter:  adapter,
		metadata: make(map[string]map[string]interface{}),
		index:    make(map[string][]string),
		logger:   utils.GetLogger(),
	}
}

func (m *DefaultStorageManager) Store(ctx context.Context, obj *interfaces.StorageObject) (string, error) {
	if obj.Key == "" {
		obj.Key = utils.GenerateID("obj")
	}
	if obj.ID == "" {
		obj.ID = utils.GenerateID("id")
	}

	obj.Checksum = utils.CalculateHash(obj.Data)
	obj.Size = int64(len(obj.Data))

	if err := m.adapter.Store(ctx, obj); err != nil {
		return "", fmt.Errorf("failed to store object: %w", err)
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if obj.Metadata != nil {
		m.metadata[obj.ID] = obj.Metadata
		m.updateIndex(obj)
	}

	m.logger.Info("Object stored",
		zap.String("id", obj.ID),
		zap.String("key", obj.Key),
		zap.Int64("size", obj.Size),
	)

	return obj.ID, nil
}

func (m *DefaultStorageManager) Retrieve(ctx context.Context, objectID string) (*interfaces.StorageObject, error) {
	m.mu.RLock()
	metadata, hasMetadata := m.metadata[objectID]
	m.mu.RUnlock()

	var key string
	if hasMetadata {
		if k, ok := metadata["key"].(string); ok {
			key = k
		}
	}

	if key == "" {
		key = objectID
	}

	obj, err := m.adapter.Retrieve(ctx, key)
	if err != nil {
		return nil, err
	}

	if hasMetadata {
		obj.Metadata = metadata
	}

	return obj, nil
}

func (m *DefaultStorageManager) Delete(ctx context.Context, objectID string) error {
	m.mu.RLock()
	metadata, hasMetadata := m.metadata[objectID]
	m.mu.RUnlock()

	var key string
	if hasMetadata {
		if k, ok := metadata["key"].(string); ok {
			key = k
		}
	}

	if key == "" {
		key = objectID
	}

	if err := m.adapter.Delete(ctx, key); err != nil {
		return err
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.metadata, objectID)
	m.removeFromIndex(objectID)

	m.logger.Info("Object deleted", zap.String("id", objectID))
	return nil
}

func (m *DefaultStorageManager) List(ctx context.Context, prefix string) ([]*interfaces.StorageObject, error) {
	objects, err := m.adapter.List(ctx, prefix)
	if err != nil {
		return nil, err
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, obj := range objects {
		if metadata, ok := m.metadata[obj.ID]; ok {
			obj.Metadata = metadata
		}
	}

	return objects, nil
}

func (m *DefaultStorageManager) IndexMetadata(ctx context.Context, metadata map[string]interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	objectID, ok := metadata["id"].(string)
	if !ok {
		return fmt.Errorf("metadata missing 'id' field")
	}

	m.metadata[objectID] = metadata

	obj := &interfaces.StorageObject{
		ID:       objectID,
		Metadata: metadata,
	}
	m.updateIndex(obj)

	m.logger.Debug("Metadata indexed", zap.String("object_id", objectID))
	return nil
}

func (m *DefaultStorageManager) SearchByMetadata(ctx context.Context, query map[string]interface{}) ([]*interfaces.StorageObject, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var candidateIDs []string
	first := true

	for k, v := range query {
		indexKey := fmt.Sprintf("%s:%v", k, v)
		ids, exists := m.index[indexKey]
		if !exists {
			return nil, nil
		}

		if first {
			candidateIDs = ids
			first = false
		} else {
			candidateIDs = intersect(candidateIDs, ids)
		}

		if len(candidateIDs) == 0 {
			return nil, nil
		}
	}

	var results []*interfaces.StorageObject
	for _, id := range candidateIDs {
		if metadata, ok := m.metadata[id]; ok {
			obj := &interfaces.StorageObject{
				ID:       id,
				Metadata: metadata,
			}
			if key, ok := metadata["key"].(string); ok {
				obj.Key = key
			}
			results = append(results, obj)
		}
	}

	return results, nil
}

func (m *DefaultStorageManager) updateIndex(obj *interfaces.StorageObject) {
	if obj.Metadata == nil {
		return
	}

	for k, v := range obj.Metadata {
		indexKey := fmt.Sprintf("%s:%v", k, v)
		m.index[indexKey] = append(m.index[indexKey], obj.ID)
	}
}

func (m *DefaultStorageManager) removeFromIndex(objectID string) {
	for key, ids := range m.index {
		for i, id := range ids {
			if id == objectID {
				m.index[key] = append(ids[:i], ids[i+1:]...)
				break
			}
		}
	}
}

func intersect(a, b []string) []string {
	set := make(map[string]bool)
	for _, s := range a {
		set[s] = true
	}

	var result []string
	for _, s := range b {
		if set[s] {
			result = append(result, s)
		}
	}
	return result
}

type S3Storage struct {
	bucket string
	logger *zap.Logger
}

func NewS3Storage(bucket string) *S3Storage {
	return &S3Storage{
		bucket: bucket,
		logger: utils.GetLogger(),
	}
}

func (s *S3Storage) Store(ctx context.Context, obj *interfaces.StorageObject) error {
	s.logger.Info("S3: Simulating object store",
		zap.String("bucket", s.bucket),
		zap.String("key", obj.Key),
		zap.Int64("size", obj.Size),
	)
	return nil
}

func (s *S3Storage) Retrieve(ctx context.Context, key string) (*interfaces.StorageObject, error) {
	s.logger.Info("S3: Simulating object retrieve",
		zap.String("bucket", s.bucket),
		zap.String("key", key),
	)
	return nil, utils.ErrNotFound
}

func (s *S3Storage) Delete(ctx context.Context, key string) error {
	s.logger.Info("S3: Simulating object delete",
		zap.String("bucket", s.bucket),
		zap.String("key", key),
	)
	return nil
}

func (s *S3Storage) List(ctx context.Context, prefix string) ([]*interfaces.StorageObject, error) {
	s.logger.Info("S3: Simulating object list",
		zap.String("bucket", s.bucket),
		zap.String("prefix", prefix),
	)
	return nil, nil
}
