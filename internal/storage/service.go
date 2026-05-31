package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	appErr "session133/pkg/errors"
	"session133/pkg/utils"
)

type StorageService struct {
	db         *gorm.DB
	logger     *zap.Logger
	adapter    StorageAdapter
	defaultBucket string
}

func NewStorageService(db *gorm.DB, logger *zap.Logger, adapter StorageAdapter, defaultBucket string) *StorageService {
	return &StorageService{
		db:            db,
		logger:        logger,
		adapter:       adapter,
		defaultBucket: defaultBucket,
	}
}

type UploadRequest struct {
	Key         string            `json:"key"`
	ContentType string            `json:"content_type"`
	Metadata    map[string]string `json:"metadata"`
	Tags        map[string]string `json:"tags"`
	Bucket      string            `json:"bucket"`
}

func (s *StorageService) Upload(ctx context.Context, req *UploadRequest, data io.Reader, userID string) (*ObjectMetadata, error) {
	bucket := req.Bucket
	if bucket == "" {
		bucket = s.defaultBucket
	}

	result, err := s.adapter.Upload(ctx, bucket, req.Key, data, req.ContentType, req.Metadata)
	if err != nil {
		return nil, appErr.Internal(fmt.Sprintf("上传失败: %v", err))
	}

	now := time.Now()
	metadata := &ObjectMetadata{
		ID:          utils.GenerateID("obj"),
		Key:         req.Key,
		Bucket:      bucket,
		StorageType: s.adapter.GetType(),
		ContentType: result.ContentType,
		SizeBytes:   result.SizeBytes,
		Checksum:    result.Checksum,
		Tags:        req.Tags,
		Metadata:    req.Metadata,
		IsLatest:    true,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	err = s.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&ObjectMetadata{}).
			Where("key = ? AND bucket = ?", req.Key, bucket).
			Update("is_latest", false).Error; err != nil {
			return err
		}
		return tx.Create(metadata).Error
	})

	if err != nil {
		s.logger.Error("保存元数据失败", zap.Error(err))
	}

	return metadata, nil
}

func (s *StorageService) Download(ctx context.Context, bucket, key string) (io.ReadCloser, *ObjectInfo, *ObjectMetadata, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	reader, info, err := s.adapter.Download(ctx, bucket, key)
	if err != nil {
		return nil, nil, nil, appErr.NotFound(fmt.Sprintf("对象不存在: %v", err))
	}

	var metadata ObjectMetadata
	if err := s.db.Where("key = ? AND bucket = ? AND is_latest = ?", key, bucket, true).First(&metadata).Error; err == nil {
		now := time.Now()
		metadata.AccessedAt = &now
		s.db.Save(&metadata)
	}

	return reader, info, &metadata, nil
}

func (s *StorageService) Delete(ctx context.Context, bucket, key string) error {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	if err := s.adapter.Delete(ctx, bucket, key); err != nil {
		return appErr.Internal(fmt.Sprintf("删除失败: %v", err))
	}

	if err := s.db.Where("key = ? AND bucket = ?", key, bucket).Delete(&ObjectMetadata{}).Error; err != nil {
		s.logger.Warn("删除元数据失败", zap.Error(err))
	}

	return nil
}

func (s *StorageService) List(ctx context.Context, bucket, prefix string, page, pageSize int) ([]ObjectMetadata, int64, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	var objects []ObjectMetadata
	var total int64

	query := s.db.WithContext(ctx).Model(&ObjectMetadata{}).
		Where("bucket = ? AND is_latest = ?", bucket, true)

	if prefix != "" {
		query = query.Where("key LIKE ?", prefix+"%")
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&objects).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return objects, total, nil
}

func (s *StorageService) GetMetadata(ctx context.Context, bucket, key string) (*ObjectMetadata, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	var metadata ObjectMetadata
	if err := s.db.WithContext(ctx).
		Where("key = ? AND bucket = ? AND is_latest = ?", key, bucket, true).
		First(&metadata).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("对象元数据")
		}
		return nil, appErr.Internal(err.Error())
	}

	return &metadata, nil
}

func (s *StorageService) GetPresignedURL(ctx context.Context, bucket, key string, expires time.Duration) (string, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	exists, err := s.adapter.Exists(ctx, bucket, key)
	if err != nil {
		return "", appErr.Internal(err.Error())
	}
	if !exists {
		return "", appErr.NotFound("对象")
	}

	url, err := s.adapter.GetPresignedURL(ctx, bucket, key, expires)
	if err != nil {
		return "", appErr.Internal(fmt.Sprintf("生成签名URL失败: %v", err))
	}

	return url, nil
}

func (s *StorageService) Copy(ctx context.Context, srcBucket, srcKey, dstBucket, dstKey string, userID string) (*ObjectMetadata, error) {
	if srcBucket == "" {
		srcBucket = s.defaultBucket
	}
	if dstBucket == "" {
		dstBucket = s.defaultBucket
	}

	if err := s.adapter.Copy(ctx, srcBucket, srcKey, dstBucket, dstKey); err != nil {
		return nil, appErr.Internal(fmt.Sprintf("复制失败: %v", err))
	}

	srcMeta, err := s.GetMetadata(ctx, srcBucket, srcKey)
	if err != nil {
		s.logger.Warn("源对象元数据不存在", zap.Error(err))
	}

	now := time.Now()
	newMeta := &ObjectMetadata{
		ID:          utils.GenerateID("obj"),
		Key:         dstKey,
		Bucket:      dstBucket,
		StorageType: s.adapter.GetType(),
		ContentType: srcMeta.ContentType,
		SizeBytes:   srcMeta.SizeBytes,
		Tags:        srcMeta.Tags,
		Metadata:    srcMeta.Metadata,
		IsLatest:    true,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(newMeta).Error; err != nil {
		s.logger.Warn("保存复制对象元数据失败", zap.Error(err))
	}

	return newMeta, nil
}

func (s *StorageService) UpdateTags(ctx context.Context, bucket, key string, tags map[string]string) error {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	now := time.Now()
	if err := s.db.WithContext(ctx).Model(&ObjectMetadata{}).
		Where("key = ? AND bucket = ? AND is_latest = ?", key, bucket, true).
		Updates(map[string]interface{}{
			"tags":       tags,
			"updated_at": now,
		}).Error; err != nil {
		return appErr.Internal(err.Error())
	}

	return nil
}

func (s *StorageService) SearchByTags(ctx context.Context, bucket string, tags map[string]string, page, pageSize int) ([]ObjectMetadata, int64, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	var objects []ObjectMetadata
	var total int64

	query := s.db.WithContext(ctx).Model(&ObjectMetadata{}).
		Where("bucket = ? AND is_latest = ?", bucket, true)

	for k, v := range tags {
		jsonPath := fmt.Sprintf("tags->>'%s'", k)
		query = query.Where(jsonPath+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&objects).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return objects, total, nil
}

func (s *StorageService) ListVersions(ctx context.Context, bucket, key string, page, pageSize int) ([]ObjectMetadata, int64, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	var versions []ObjectMetadata
	var total int64

	query := s.db.WithContext(ctx).Model(&ObjectMetadata{}).
		Where("key = ? AND bucket = ?", key, bucket)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&versions).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return versions, total, nil
}

func (s *StorageService) BatchDelete(ctx context.Context, bucket string, keys []string) error {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	for _, key := range keys {
		if err := s.adapter.Delete(ctx, bucket, key); err != nil {
			s.logger.Warn("批量删除对象失败", zap.String("key", key), zap.Error(err))
		}
	}

	if err := s.db.WithContext(ctx).
		Where("bucket = ? AND key IN ?", bucket, keys).
		Delete(&ObjectMetadata{}).Error; err != nil {
		return appErr.Internal(err.Error())
	}

	return nil
}

func (s *StorageService) GetStorageStats(ctx context.Context, bucket string) (map[string]interface{}, error) {
	if bucket == "" {
		bucket = s.defaultBucket
	}

	var totalObjects int64
	var totalSize int64

	if err := s.db.WithContext(ctx).Model(&ObjectMetadata{}).
		Where("bucket = ? AND is_latest = ?", bucket, true).
		Count(&totalObjects).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	rows, err := s.db.WithContext(ctx).Model(&ObjectMetadata{}).
		Where("bucket = ? AND is_latest = ?", bucket, true).
		Select("COALESCE(SUM(size_bytes), 0)").Rows()
	if err != nil {
		return nil, appErr.Internal(err.Error())
	}
	defer rows.Close()

	if rows.Next() {
		rows.Scan(&totalSize)
	}

	return map[string]interface{}{
		"bucket":        bucket,
		"total_objects": totalObjects,
		"total_size_bytes": totalSize,
		"total_size_mb":  float64(totalSize) / 1024 / 1024,
		"storage_type":  s.adapter.GetType(),
	}, nil
}

func ParseSize(sizeStr string) (int64, error) {
	sizeStr = strings.ToUpper(strings.TrimSpace(sizeStr))
	multiplier := int64(1)

	if strings.HasSuffix(sizeStr, "GB") {
		multiplier = 1024 * 1024 * 1024
		sizeStr = strings.TrimSuffix(sizeStr, "GB")
	} else if strings.HasSuffix(sizeStr, "MB") {
		multiplier = 1024 * 1024
		sizeStr = strings.TrimSuffix(sizeStr, "MB")
	} else if strings.HasSuffix(sizeStr, "KB") {
		multiplier = 1024
		sizeStr = strings.TrimSuffix(sizeStr, "KB")
	}

	size, err := strconv.ParseInt(strings.TrimSpace(sizeStr), 10, 64)
	if err != nil {
		return 0, err
	}

	return size * multiplier, nil
}
