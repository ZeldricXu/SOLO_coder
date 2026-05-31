package storage

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

type LocalStorageBackend struct {
	basePath string
}

func NewLocalStorageBackend(basePath string) (*LocalStorageBackend, error) {
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to create storage directory")
	}

	return &LocalStorageBackend{
		basePath: basePath,
	}, nil
}

func (s *LocalStorageBackend) Upload(ctx context.Context, key string, data []byte, metadata map[string]string) error {
	fullPath := filepath.Join(s.basePath, key)

	dir := filepath.Dir(fullPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to create directory")
	}

	if err := ioutil.WriteFile(fullPath, data, 0644); err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to write file")
	}

	if metadata != nil {
		metaPath := fullPath + ".meta"
		metaContent := ""
		for k, v := range metadata {
			metaContent += fmt.Sprintf("%s: %s\n", k, v)
		}
		ioutil.WriteFile(metaPath, []byte(metaContent), 0644)
	}

	return nil
}

func (s *LocalStorageBackend) Download(ctx context.Context, key string) ([]byte, error) {
	fullPath := filepath.Join(s.basePath, key)

	data, err := ioutil.ReadFile(fullPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, errors.New(errors.ErrCodeNotFound, "file not found")
		}
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to read file")
	}

	return data, nil
}

func (s *LocalStorageBackend) Delete(ctx context.Context, key string) error {
	fullPath := filepath.Join(s.basePath, key)

	if err := os.Remove(fullPath); err != nil {
		if os.IsNotExist(err) {
			return errors.New(errors.ErrCodeNotFound, "file not found")
		}
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to delete file")
	}

	metaPath := fullPath + ".meta"
	os.Remove(metaPath)

	return nil
}

func (s *LocalStorageBackend) List(ctx context.Context, prefix string) ([]*StorageObject, error) {
	fullPath := filepath.Join(s.basePath, prefix)

	var result []*StorageObject

	err := filepath.Walk(fullPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		if info.IsDir() {
			return nil
		}

		if strings.HasSuffix(path, ".meta") {
			return nil
		}

		relPath, _ := filepath.Rel(s.basePath, path)
		data, _ := ioutil.ReadFile(path)
		hash := md5.Sum(data)

		result = append(result, &StorageObject{
			Key:          relPath,
			Size:         info.Size(),
			ETag:         hex.EncodeToString(hash[:]),
			LastModified: info.ModTime(),
			Metadata:     make(map[string]string),
		})

		return nil
	})

	if err != nil {
		if os.IsNotExist(err) {
			return []*StorageObject{}, nil
		}
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to list files")
	}

	return result, nil
}

type StorageManagerImpl struct {
	backend       StorageBackend
	lifecycleRules []*LifecycleRule
	mu            sync.RWMutex
	logger        domain.Logger
}

func NewStorageManagerImpl(backend StorageBackend, logger domain.Logger) *StorageManagerImpl {
	return &StorageManagerImpl{
		backend:        backend,
		lifecycleRules: make([]*LifecycleRule, 0),
		logger:         logger,
	}
}

func (m *StorageManagerImpl) Upload(ctx context.Context, key string, data []byte, metadata map[string]string) error {
	m.logger.Debug("Uploading file", domain.String("key", key), domain.Int("size", len(data)))
	return m.backend.Upload(ctx, key, data, metadata)
}

func (m *StorageManagerImpl) Download(ctx context.Context, key string) ([]byte, error) {
	m.logger.Debug("Downloading file", domain.String("key", key))
	return m.backend.Download(ctx, key)
}

func (m *StorageManagerImpl) Delete(ctx context.Context, key string) error {
	m.logger.Debug("Deleting file", domain.String("key", key))
	return m.backend.Delete(ctx, key)
}

func (m *StorageManagerImpl) List(ctx context.Context, prefix string) ([]*StorageObject, error) {
	return m.backend.List(ctx, prefix)
}

func (m *StorageManagerImpl) SetLifecycle(ctx context.Context, rule *LifecycleRule) error {
	if rule == nil {
		return errors.New(errors.ErrCodeValidation, "rule cannot be nil")
	}
	if rule.ID == "" {
		return errors.New(errors.ErrCodeValidation, "rule ID required")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	for i, existing := range m.lifecycleRules {
		if existing.ID == rule.ID {
			m.lifecycleRules[i] = rule
			return nil
		}
	}

	m.lifecycleRules = append(m.lifecycleRules, rule)
	m.logger.Info("Lifecycle rule set",
		domain.String("rule_id", rule.ID),
		domain.String("prefix", rule.Prefix),
		domain.Int("expire_days", rule.ExpireDays),
	)

	return nil
}

func (m *StorageManagerImpl) ApplyLifecycle(ctx context.Context) error {
	m.mu.RLock()
	rules := make([]*LifecycleRule, len(m.lifecycleRules))
	copy(rules, m.lifecycleRules)
	m.mu.RUnlock()

	for _, rule := range rules {
		if rule.Status != "enabled" {
			continue
		}

		objects, err := m.List(ctx, rule.Prefix)
		if err != nil {
			return err
		}

		cutoff := time.Now().AddDate(0, 0, -rule.ExpireDays)
		for _, obj := range objects {
			if obj.LastModified.Before(cutoff) {
				if err := m.Delete(ctx, obj.Key); err != nil {
					m.logger.Warn("Failed to delete expired object",
						domain.String("key", obj.Key),
						domain.Error(err),
					)
				} else {
					m.logger.Info("Deleted expired object",
						domain.String("key", obj.Key),
					)
				}
			}
		}
	}

	return nil
}
