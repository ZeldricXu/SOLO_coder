package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/data"
	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/events"
	"github.com/edgeplatform/session306/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type StorageBackend interface {
	Upload(ctx context.Context, fileID string, content io.Reader, size int64) (string, int64, error)
	Download(ctx context.Context, fileID string) (io.ReadCloser, error)
	Delete(ctx context.Context, fileID string) error
	GetURL(ctx context.Context, fileID string, expiresAt time.Time) (string, error)
}

type LocalStorageBackend struct {
	baseDir string
	logger  *zap.Logger
}

func NewLocalStorageBackend(baseDir string, logger *zap.Logger) (*LocalStorageBackend, error) {
	if err := os.MkdirAll(baseDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create storage directory: %w", err)
	}
	return &LocalStorageBackend{
		baseDir: baseDir,
		logger:  logger,
	}, nil
}

func (b *LocalStorageBackend) Upload(ctx context.Context, fileID string, content io.Reader, size int64) (string, int64, error) {
	filePath := filepath.Join(b.baseDir, fileID)
	if err := os.MkdirAll(filepath.Dir(filePath), 0755); err != nil {
		return "", 0, err
	}

	f, err := os.Create(filePath)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()

	hasher := sha256.New()
	writer := io.MultiWriter(f, hasher)

	written, err := io.CopyN(writer, content, size)
	if err != nil && err != io.EOF {
		return "", 0, err
	}

	checksum := hex.EncodeToString(hasher.Sum(nil))
	return checksum, written, nil
}

func (b *LocalStorageBackend) Download(ctx context.Context, fileID string) (io.ReadCloser, error) {
	filePath := filepath.Join(b.baseDir, fileID)
	return os.Open(filePath)
}

func (b *LocalStorageBackend) Delete(ctx context.Context, fileID string) error {
	filePath := filepath.Join(b.baseDir, fileID)
	return os.Remove(filePath)
}

func (b *LocalStorageBackend) GetURL(ctx context.Context, fileID string, expiresAt time.Time) (string, error) {
	return fmt.Sprintf("/api/v1/storage/%s?expires=%d", fileID, expiresAt.Unix()), nil
}

type StorageManager struct {
	da              *data.DataAccess
	eventBus        events.EventBus
	logger          *zap.Logger
	backends        map[model.StorageBackend]StorageBackend
	lifecyclePolicies []model.LifecyclePolicy
	mu              sync.RWMutex
	defaultBackend  model.StorageBackend
}

func NewStorageManager(da *data.DataAccess, eb events.EventBus, log *zap.Logger) *StorageManager {
	return &StorageManager{
		da:             da,
		eventBus:       eb,
		logger:         log,
		backends:       make(map[model.StorageBackend]StorageBackend),
		defaultBackend: model.BackendLocal,
	}
}

func (m *StorageManager) RegisterBackend(backendType model.StorageBackend, backend StorageBackend) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.backends[backendType] = backend
	m.logger.Info("Storage backend registered", zap.String("backend", string(backendType)))
}

func (m *StorageManager) Start(ctx context.Context) error {
	localBackend, err := NewLocalStorageBackend("./data/storage", m.logger)
	if err != nil {
		return err
	}
	m.RegisterBackend(model.BackendLocal, localBackend)

	m.registerDefaultPolicies()

	go m.runLifecycleManager(ctx)

	m.logger.Info("Storage manager started")
	return nil
}

func (m *StorageManager) registerDefaultPolicies() {
	m.lifecyclePolicies = []model.LifecyclePolicy{
		{
			ID:              "policy_default",
			Name:            "Default Policy",
			Description:     "Default lifecycle policy",
			Enabled:         true,
			Prefix:          "",
			TransitionDays:  30,
			ExpirationDays:  180,
			DeleteAfterDays: 365,
		},
		{
			ID:              "policy_temp",
			Name:            "Temporary Files",
			Description:     "Temporary file policy",
			Enabled:         true,
			Prefix:          "temp/",
			TransitionDays:  0,
			ExpirationDays:  7,
			DeleteAfterDays: 7,
		},
	}
}

func (m *StorageManager) UploadFile(ctx context.Context, req *model.FileUploadRequest, content io.Reader, size int64, uploadedBy string) (*model.FileRecord, error) {
	backend := req.Backend
	if backend == "" {
		backend = m.defaultBackend
	}

	m.mu.RLock()
	storage, ok := m.backends[backend]
	m.mu.RUnlock()

	if !ok {
		return nil, errors.NewValidationError(fmt.Sprintf("unsupported storage backend: %s", backend))
	}

	fileID := utils.GenerateID("file")
	path := req.Path
	if path == "" {
		path = fileID
	}

	checksum, written, err := storage.Upload(ctx, fileID, content, size)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to upload file")
	}

	record := &model.FileRecord{
		FileID:      fileID,
		Name:        req.Name,
		Path:        path,
		SizeBytes:   written,
		ContentType: req.ContentType,
		Checksum:    checksum,
		Backend:     backend,
		Status:      model.FileStatusActive,
		Tags:        req.Tags,
		Metadata:    req.Metadata,
		UploadedBy:  uploadedBy,
		CreatedAt:   utils.NowUTC(),
		UpdatedAt:   utils.NowUTC(),
	}

	if req.TTLSeconds > 0 {
		expiresAt := utils.NowUTC().Add(time.Duration(req.TTLSeconds) * time.Second)
		record.ExpiresAt = &expiresAt
	}

	if err := m.da.DB().WithContext(ctx).Create(record).Error; err != nil {
		_ = storage.Delete(ctx, fileID)
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to create file record")
	}

	m.logger.Info("File uploaded",
		zap.String("file_id", fileID),
		zap.String("name", req.Name),
		zap.Int64("size", written),
		zap.String("backend", string(backend)),
	)
	return record, nil
}

func (m *StorageManager) GetFile(ctx context.Context, fileID string) (*model.FileRecord, error) {
	var record model.FileRecord
	err := m.da.DB().WithContext(ctx).Where("file_id = ? AND status != ?", fileID, model.FileStatusDeleted).First(&record).Error
	if err == gorm.ErrRecordNotFound {
		return nil, errors.NewNotFoundError("file not found")
	}
	return &record, err
}

func (m *StorageManager) DownloadFile(ctx context.Context, fileID string) (io.ReadCloser, *model.FileRecord, error) {
	record, err := m.GetFile(ctx, fileID)
	if err != nil {
		return nil, nil, err
	}

	if record.ExpiresAt != nil && record.ExpiresAt.Before(utils.NowUTC()) {
		return nil, nil, errors.NewNotFoundError("file has expired")
	}

	m.mu.RLock()
	storage, ok := m.backends[record.Backend]
	m.mu.RUnlock()

	if !ok {
		return nil, nil, errors.NewInternalError("storage backend not available", nil)
	}

	reader, err := storage.Download(ctx, fileID)
	if err != nil {
		return nil, nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to download file")
	}

	now := utils.NowUTC()
	m.da.DB().WithContext(ctx).Model(record).
		Updates(map[string]interface{}{
			"download_count":   gorm.Expr("download_count + 1"),
			"last_accessed_at": now,
			"updated_at":       now,
		})

	return reader, record, nil
}

func (m *StorageManager) GetDownloadURL(ctx context.Context, fileID string, ttl time.Duration) (*model.FileDownloadResponse, error) {
	record, err := m.GetFile(ctx, fileID)
	if err != nil {
		return nil, err
	}

	m.mu.RLock()
	storage, ok := m.backends[record.Backend]
	m.mu.RUnlock()

	if !ok {
		return nil, errors.NewInternalError("storage backend not available", nil)
	}

	expiresAt := utils.NowUTC().Add(ttl)
	url, err := storage.GetURL(ctx, fileID, expiresAt)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal, "failed to generate download URL")
	}

	return &model.FileDownloadResponse{
		FileID:      record.FileID,
		Name:        record.Name,
		SizeBytes:   record.SizeBytes,
		ContentType: record.ContentType,
		DownloadURL: url,
		ExpiresAt:   expiresAt,
	}, nil
}

func (m *StorageManager) ListFiles(ctx context.Context, prefix string, backend model.StorageBackend, status model.FileStatus, offset, limit int) ([]model.FileRecord, int64, error) {
	var records []model.FileRecord
	var total int64

	query := m.da.DB().WithContext(ctx).Model(&model.FileRecord{}).
		Where("status != ?", model.FileStatusDeleted)

	if prefix != "" {
		query = query.Where("path LIKE ?", prefix+"%")
	}
	if backend != "" {
		query = query.Where("backend = ?", backend)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if limit > 0 {
		query = query.Offset(offset).Limit(limit)
	}
	err := query.Order("created_at DESC").Find(&records).Error
	return records, total, err
}

func (m *StorageManager) DeleteFile(ctx context.Context, fileID string) error {
	record, err := m.GetFile(ctx, fileID)
	if err != nil {
		return err
	}

	m.mu.RLock()
	storage, ok := m.backends[record.Backend]
	m.mu.RUnlock()

	if ok {
		if err := storage.Delete(ctx, fileID); err != nil {
			m.logger.Warn("Failed to delete file from storage",
				zap.String("file_id", fileID),
				zap.Error(err),
			)
		}
	}

	now := utils.NowUTC()
	return m.da.DB().WithContext(ctx).Model(record).
		Updates(map[string]interface{}{
			"status":     model.FileStatusDeleted,
			"updated_at": now,
		}).Error
}

func (m *StorageManager) AddLifecyclePolicy(ctx context.Context, policy model.LifecyclePolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	policy.ID = utils.GenerateID("policy")
	m.lifecyclePolicies = append(m.lifecyclePolicies, policy)

	m.logger.Info("Lifecycle policy added",
		zap.String("policy_id", policy.ID),
		zap.String("name", policy.Name),
	)
	return nil
}

func (m *StorageManager) runLifecycleManager(ctx context.Context) {
	ticker := time.NewTicker(24 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			m.applyLifecyclePolicies(ctx)
		}
	}
}

func (m *StorageManager) applyLifecyclePolicies(ctx context.Context) {
	m.mu.RLock()
	policies := make([]model.LifecyclePolicy, len(m.lifecyclePolicies))
	copy(policies, m.lifecyclePolicies)
	m.mu.RUnlock()

	now := utils.NowUTC()

	for _, policy := range policies {
		if !policy.Enabled {
			continue
		}

		m.applyPolicy(ctx, policy, now)
	}

	m.deleteExpiredFiles(ctx, now)
}

func (m *StorageManager) applyPolicy(ctx context.Context, policy model.LifecyclePolicy, now time.Time) {
	var records []model.FileRecord
	query := m.da.DB().WithContext(ctx).
		Where("status = ? AND path LIKE ?", model.FileStatusActive, policy.Prefix+"%")

	if len(policy.Tags) > 0 {
		for _, tag := range policy.Tags {
			query = query.Where("tags @> ?", fmt.Sprintf(`["%s"]`, tag))
		}
	}

	query.Find(&records)

	for _, record := range records {
		age := now.Sub(record.CreatedAt).Hours() / 24

		if policy.DeleteAfterDays > 0 && age > float64(policy.DeleteAfterDays) {
			_ = m.DeleteFile(ctx, record.FileID)
			m.logger.Info("File deleted by lifecycle policy",
				zap.String("file_id", record.FileID),
				zap.String("policy", policy.Name),
			)
		} else if policy.TransitionDays > 0 && age > float64(policy.TransitionDays) && record.Status == model.FileStatusActive {
			now := utils.NowUTC()
			m.da.DB().WithContext(ctx).Model(&record).
				Updates(map[string]interface{}{
					"status":     model.FileStatusArchived,
					"archived_at": now,
					"updated_at": now,
				})
			m.logger.Info("File archived by lifecycle policy",
				zap.String("file_id", record.FileID),
				zap.String("policy", policy.Name),
			)
		}
	}
}

func (m *StorageManager) deleteExpiredFiles(ctx context.Context, now time.Time) {
	var expired []model.FileRecord
	m.da.DB().WithContext(ctx).
		Where("expires_at IS NOT NULL AND expires_at < ? AND status != ?", now, model.FileStatusDeleted).
		Find(&expired)

	for _, record := range expired {
		_ = m.DeleteFile(ctx, record.FileID)
		m.logger.Info("Expired file deleted", zap.String("file_id", record.FileID))
	}
}

func (m *StorageManager) GetMetrics(ctx context.Context) (map[string]interface{}, error) {
	var totalFiles int64
	var totalSize int64
	var activeFiles int64
	var archivedFiles int64

	m.da.DB().WithContext(ctx).Model(&model.FileRecord{}).
		Where("status != ?", model.FileStatusDeleted).
		Count(&totalFiles)

	m.da.DB().WithContext(ctx).Model(&model.FileRecord{}).
		Where("status != ?", model.FileStatusDeleted).
		Select("COALESCE(SUM(size_bytes), 0)").Scan(&totalSize)

	m.da.DB().WithContext(ctx).Model(&model.FileRecord{}).
		Where("status = ?", model.FileStatusActive).
		Count(&activeFiles)

	m.da.DB().WithContext(ctx).Model(&model.FileRecord{}).
		Where("status = ?", model.FileStatusArchived).
		Count(&archivedFiles)

	return map[string]interface{}{
		"total_files":    totalFiles,
		"total_size_bytes": totalSize,
		"active_files":   activeFiles,
		"archived_files": archivedFiles,
	}, nil
}
