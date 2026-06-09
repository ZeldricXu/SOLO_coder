package artifact

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/storage"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type ArtifactManager struct {
	cfg   *config.ArtifactConfig
	minio *storage.MinIOClient
	db    *gorm.DB
}

func NewArtifactManager(cfg *config.ArtifactConfig, minio *storage.MinIOClient, db *gorm.DB) *ArtifactManager {
	return &ArtifactManager{
		cfg:   cfg,
		minio: minio,
		db:    db,
	}
}

func (m *ArtifactManager) UploadArtifact(ctx context.Context, exec *models.PipelineExecution, se *models.StageExecution, artifactPath string) (*models.ArtifactRecord, error) {
	logger.Info("starting artifact upload",
		zap.String("execution_id", string(exec.ID)),
		zap.String("stage_id", string(se.ID)),
		zap.String("path", artifactPath),
	)

	info, err := os.Stat(artifactPath)
	if err != nil {
		return nil, fmt.Errorf("failed to stat artifact path: %w", err)
	}

	artifactName := filepath.Base(artifactPath)
	fileName := artifactName
	var uploadPath string
	var size int64
	var digest string
	contentType := "application/octet-stream"

	if info.IsDir() {
		fileName = artifactName + ".tar.gz"
		contentType = "application/gzip"
		uploadPath, size, digest, err = m.compressDirectory(artifactPath)
		if err != nil {
			return nil, fmt.Errorf("failed to compress directory: %w", err)
		}
		defer os.Remove(uploadPath)
	} else {
		uploadPath = artifactPath
		size = info.Size()
		digest, err = m.computeDigest(artifactPath)
		if err != nil {
			return nil, fmt.Errorf("failed to compute digest: %w", err)
		}
	}

	if m.cfg.MaxSizeMB > 0 && size > int64(m.cfg.MaxSizeMB)*1024*1024 {
		return nil, fmt.Errorf("artifact size %d exceeds maximum allowed %d MB", size, m.cfg.MaxSizeMB)
	}

	storageKey := fmt.Sprintf("artifacts/%s/%s/%s", exec.ProjectID, exec.ID, fileName)

	record := &models.ArtifactRecord{
		ID:            types.NewID(),
		ExecutionID:   exec.ID,
		StageID:       se.ID,
		ProjectID:     exec.ProjectID,
		Name:          artifactName,
		FileName:      fileName,
		Size:          size,
		ContentType:   contentType,
		Status:        types.ArtifactStatusUploading,
		StorageBucket: m.minio.Bucket(),
		StorageKey:    storageKey,
		Digest:        digest,
	}

	if err := m.db.Create(record).Error; err != nil {
		return nil, fmt.Errorf("failed to create artifact record: %w", err)
	}

	file, err := os.Open(uploadPath)
	if err != nil {
		m.updateRecordStatus(record.ID, types.ArtifactStatusFailed)
		return nil, fmt.Errorf("failed to open artifact file: %w", err)
	}
	defer file.Close()

	if err := m.minio.Upload(ctx, storageKey, file, size, contentType); err != nil {
		m.updateRecordStatus(record.ID, types.ArtifactStatusFailed)
		return nil, fmt.Errorf("failed to upload to minio: %w", err)
	}

	now := time.Now()
	record.Status = types.ArtifactStatusUploaded
	record.UploadedAt = &now

	if m.cfg.RetentionDays > 0 {
		expiresAt := now.AddDate(0, 0, m.cfg.RetentionDays)
		record.ExpiresAt = &expiresAt
	}

	if err := m.db.Save(record).Error; err != nil {
		logger.Error("failed to update artifact record", zap.Error(err))
	}

	logger.Info("artifact uploaded successfully",
		zap.String("artifact_id", string(record.ID)),
		zap.String("storage_key", storageKey),
		zap.Int64("size", size),
	)

	return record, nil
}

func (m *ArtifactManager) DownloadArtifact(ctx context.Context, artifactID types.ID) (string, error) {
	var record models.ArtifactRecord
	if err := m.db.First(&record, "id = ?", artifactID).Error; err != nil {
		return "", fmt.Errorf("artifact not found: %w", err)
	}

	if record.Status != types.ArtifactStatusUploaded {
		return "", fmt.Errorf("artifact is not available for download: %s", record.Status)
	}

	reader, err := m.minio.Download(ctx, record.StorageKey)
	if err != nil {
		return "", fmt.Errorf("failed to download from minio: %w", err)
	}
	defer reader.Close()

	tmpFile, err := os.CreateTemp("", "artifact-*"+filepath.Ext(record.FileName))
	if err != nil {
		return "", fmt.Errorf("failed to create temp file: %w", err)
	}
	defer tmpFile.Close()

	if _, err := io.Copy(tmpFile, reader); err != nil {
		os.Remove(tmpFile.Name())
		return "", fmt.Errorf("failed to write temp file: %w", err)
	}

	now := time.Now()
	m.db.Model(&record).Updates(map[string]interface{}{
		"download_count": gorm.Expr("download_count + ?", 1),
		"last_download":  &now,
	})

	logger.Info("artifact downloaded",
		zap.String("artifact_id", string(artifactID)),
		zap.String("file", tmpFile.Name()),
	)

	return tmpFile.Name(), nil
}

func (m *ArtifactManager) GetPresignedURL(ctx context.Context, artifactID types.ID, expires time.Duration) (string, error) {
	var record models.ArtifactRecord
	if err := m.db.First(&record, "id = ?", artifactID).Error; err != nil {
		return "", fmt.Errorf("artifact not found: %w", err)
	}

	if record.Status != types.ArtifactStatusUploaded {
		return "", fmt.Errorf("artifact is not available: %s", record.Status)
	}

	url, err := m.minio.PresignedGetURL(ctx, record.StorageKey, expires)
	if err != nil {
		return "", fmt.Errorf("failed to generate presigned url: %w", err)
	}

	now := time.Now()
	m.db.Model(&record).Updates(map[string]interface{}{
		"download_count": gorm.Expr("download_count + ?", 1),
		"last_download":  &now,
	})

	logger.Info("presigned url generated",
		zap.String("artifact_id", string(artifactID)),
		zap.Duration("expires", expires),
	)

	return url, nil
}

func (m *ArtifactManager) CleanupExpired(ctx context.Context) (int, error) {
	logger.Info("starting artifact cleanup")
	cleanedCount := 0

	now := time.Now()
	var expiredRecords []models.ArtifactRecord

	query := m.db.Where("status = ? AND expires_at IS NOT NULL AND expires_at < ?",
		types.ArtifactStatusUploaded, now)

	if err := query.Find(&expiredRecords).Error; err != nil {
		return 0, fmt.Errorf("failed to query expired artifacts: %w", err)
	}

	for _, record := range expiredRecords {
		if err := m.minio.Delete(ctx, record.StorageKey); err != nil {
			logger.Error("failed to delete artifact from minio",
				zap.String("artifact_id", string(record.ID)),
				zap.Error(err),
			)
			continue
		}

		if err := m.db.Model(&record).Update("status", types.ArtifactStatusExpired).Error; err != nil {
			logger.Error("failed to update artifact status",
				zap.String("artifact_id", string(record.ID)),
				zap.Error(err),
			)
			continue
		}

		cleanedCount++
		logger.Info("artifact expired and cleaned",
			zap.String("artifact_id", string(record.ID)),
			zap.String("storage_key", record.StorageKey),
		)
	}

	if m.cfg.KeepLast > 0 {
		projectIDs, err := m.getDistinctProjectIDs()
		if err != nil {
			logger.Error("failed to get project IDs", zap.Error(err))
		} else {
			for _, projectID := range projectIDs {
				count, err := m.cleanupByCount(ctx, projectID)
				if err != nil {
					logger.Error("failed to cleanup by count",
						zap.String("project_id", projectID),
						zap.Error(err),
					)
					continue
				}
				cleanedCount += count
			}
		}
	}

	logger.Info("artifact cleanup completed", zap.Int("cleaned_count", cleanedCount))
	return cleanedCount, nil
}

func (m *ArtifactManager) compressDirectory(dirPath string) (string, int64, string, error) {
	tmpFile, err := os.CreateTemp("", "artifact-*.tar.gz")
	if err != nil {
		return "", 0, "", fmt.Errorf("failed to create temp file: %w", err)
	}
	defer tmpFile.Close()

	sha256Hash := sha256.New()
	multiWriter := io.MultiWriter(tmpFile, sha256Hash)

	gzipWriter := gzip.NewWriter(multiWriter)
	defer gzipWriter.Close()

	tarWriter := tar.NewWriter(gzipWriter)
	defer tarWriter.Close()

	baseDir := filepath.Dir(dirPath)

	err = filepath.Walk(dirPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		if info.IsDir() {
			return nil
		}

		relPath, err := filepath.Rel(baseDir, path)
		if err != nil {
			return err
		}

		header, err := tar.FileInfoHeader(info, relPath)
		if err != nil {
			return err
		}
		header.Name = relPath

		if err := tarWriter.WriteHeader(header); err != nil {
			return err
		}

		file, err := os.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()

		if _, err := io.Copy(tarWriter, file); err != nil {
			return err
		}

		return nil
	})

	if err != nil {
		os.Remove(tmpFile.Name())
		return "", 0, "", fmt.Errorf("failed to walk directory: %w", err)
	}

	if err := tarWriter.Close(); err != nil {
		os.Remove(tmpFile.Name())
		return "", 0, "", err
	}
	if err := gzipWriter.Close(); err != nil {
		os.Remove(tmpFile.Name())
		return "", 0, "", err
	}

	fileInfo, err := tmpFile.Stat()
	if err != nil {
		os.Remove(tmpFile.Name())
		return "", 0, "", err
	}

	digest := hex.EncodeToString(sha256Hash.Sum(nil))

	return tmpFile.Name(), fileInfo.Size(), digest, nil
}

func (m *ArtifactManager) computeDigest(filePath string) (string, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", err
	}
	defer file.Close()

	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}

	return hex.EncodeToString(hash.Sum(nil)), nil
}

func (m *ArtifactManager) updateRecordStatus(id types.ID, status types.ArtifactStatus) {
	if err := m.db.Model(&models.ArtifactRecord{}).
		Where("id = ?", id).
		Update("status", status).Error; err != nil {
		logger.Error("failed to update artifact status",
			zap.String("artifact_id", string(id)),
			zap.Error(err),
		)
	}
}

func (m *ArtifactManager) getDistinctProjectIDs() ([]string, error) {
	var projectIDs []string
	err := m.db.Model(&models.ArtifactRecord{}).
		Where("status = ?", types.ArtifactStatusUploaded).
		Distinct("project_id").
		Pluck("project_id", &projectIDs).Error
	return projectIDs, err
}

func (m *ArtifactManager) cleanupByCount(ctx context.Context, projectID string) (int, error) {
	var totalCount int64
	err := m.db.Model(&models.ArtifactRecord{}).
		Where("project_id = ? AND status = ?", projectID, types.ArtifactStatusUploaded).
		Count(&totalCount).Error
	if err != nil {
		return 0, err
	}

	if int(totalCount) <= m.cfg.KeepLast {
		return 0, nil
	}

	offset := m.cfg.KeepLast
	var toDelete []models.ArtifactRecord
	err = m.db.Where("project_id = ? AND status = ?", projectID, types.ArtifactStatusUploaded).
		Order("created_at DESC").
		Offset(offset).
		Find(&toDelete).Error
	if err != nil {
		return 0, err
	}

	count := 0
	for _, record := range toDelete {
		if err := m.minio.Delete(ctx, record.StorageKey); err != nil {
			logger.Error("failed to delete artifact from minio",
				zap.String("artifact_id", string(record.ID)),
				zap.Error(err),
			)
			continue
		}

		if err := m.db.Model(&record).Update("status", types.ArtifactStatusExpired).Error; err != nil {
			logger.Error("failed to update artifact status",
				zap.String("artifact_id", string(record.ID)),
				zap.Error(err),
			)
			continue
		}

		count++
		logger.Info("artifact cleaned by retention count",
			zap.String("artifact_id", string(record.ID)),
			zap.String("project_id", projectID),
		)
	}

	return count, nil
}
