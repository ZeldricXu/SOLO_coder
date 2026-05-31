package service

import (
	"context"

	"session187/internal/common"
	"session187/internal/storage"
	"session187/internal/storage/adapter"
	storagePolicy "session187/internal/storage/policy"
	"session187/internal/storage/repository"
	"session187/pkg/errors"
)

type StorageService interface {
	CreateBucket(tenantID, name, region, acl string) (*storage.Bucket, error)
	GetBucket(tenantID, name string) (*storage.Bucket, error)
	ListBuckets(tenantID string) ([]storage.Bucket, error)
	PutObject(tenantID, bucketName, objectKey, fileName, contentType string, data []byte, tags map[string]string) (*storage.ObjectMetadata, error)
	GetObject(tenantID, bucketName, objectKey string) ([]byte, *storage.ObjectMetadata, error)
	GetObjectMetadata(tenantID, bucketName, objectKey string) (*storage.ObjectMetadata, error)
	DeleteObject(tenantID, bucketName, objectKey string) error
	ListObjects(tenantID, bucketName, prefix string) ([]storage.ObjectMetadata, error)
	SearchByTags(tenantID string, tags map[string]string) ([]storage.ObjectMetadata, error)
	UpdateObjectMetadata(tenantID, bucketName, objectKey string, customMeta map[string]interface{}) (*storage.ObjectMetadata, error)
	GetPolicyManager() storagePolicy.PolicyManager
}

type storageServiceImpl struct {
	bucketRepo     repository.BucketRepository
	metadataRepo   repository.MetadataRepository
	storage        adapter.ObjectStorage
	policyManager  storagePolicy.PolicyManager
}

func NewStorageService(
	bucketRepo repository.BucketRepository,
	metadataRepo repository.MetadataRepository,
	storage adapter.ObjectStorage,
	policyManager storagePolicy.PolicyManager,
) StorageService {
	return &storageServiceImpl{
		bucketRepo:    bucketRepo,
		metadataRepo:  metadataRepo,
		storage:       storage,
		policyManager: policyManager,
	}
}

func (s *storageServiceImpl) GetPolicyManager() storagePolicy.PolicyManager {
	return s.policyManager
}

func (s *storageServiceImpl) CreateBucket(tenantID, name, region, acl string) (*storage.Bucket, error) {
	bucket := &storage.Bucket{
		TenantID: tenantID,
		Name:     name,
		Region:   region,
		ACL:      acl,
	}
	return s.bucketRepo.Create(bucket)
}

func (s *storageServiceImpl) GetBucket(tenantID, name string) (*storage.Bucket, error) {
	return s.bucketRepo.GetByID(tenantID, name)
}

func (s *storageServiceImpl) ListBuckets(tenantID string) ([]storage.Bucket, error) {
	return s.bucketRepo.List(tenantID)
}

func (s *storageServiceImpl) PutObject(tenantID, bucketName, objectKey, fileName, contentType string, data []byte, tags map[string]string) (*storage.ObjectMetadata, error) {
	bucket, err := s.bucketRepo.GetByID(tenantID, bucketName)
	if err != nil {
		return nil, err
	}
	etag := common.HashString(string(data))
	metadata := map[string]string{
		"filename":     fileName,
		"content_type": contentType,
		"tenant_id":    tenantID,
		"etag":         etag,
	}
	for k, v := range tags {
		metadata[k] = v
	}
	processCtx := &storagePolicy.ProcessContext{
		Context:      context.Background(),
		TenantID:     tenantID,
		BucketName:   bucketName,
		ObjectKey:    objectKey,
		ContentType:  contentType,
		Metadata:     metadata,
		OriginalSize: int64(len(data)),
		Tags:         tags,
	}
	processedData, processCtx, err := s.policyManager.ApplyBeforeUpload(processCtx, data)
	if err != nil {
		return nil, errors.NewWithDetail(500, "应用上传前策略失败", err.Error())
	}
	ctx := context.Background()
	if processedData != nil {
		if err := s.storage.PutObject(ctx, bucket.Name, objectKey, processedData, processCtx.Metadata); err != nil {
			return nil, errors.NewWithDetail(500, "上传对象失败", err.Error())
		}
	}
	if err := s.policyManager.ApplyAfterUpload(processCtx, processedData); err != nil {
		return nil, errors.NewWithDetail(500, "应用上传后策略失败", err.Error())
	}
	objMeta := &storage.ObjectMetadata{
		TenantID:    tenantID,
		Bucket:      bucketName,
		ObjectKey:   objectKey,
		FileName:    fileName,
		ContentType: contentType,
		Size:        int64(len(data)),
		ETag:        etag,
		Tags:        tags,
		CustomMeta:  make(map[string]interface{}),
	}
	for k, v := range processCtx.Metadata {
		objMeta.CustomMeta[k] = v
	}
	return s.metadataRepo.Create(objMeta)
}

func (s *storageServiceImpl) GetObject(tenantID, bucketName, objectKey string) ([]byte, *storage.ObjectMetadata, error) {
	bucket, err := s.bucketRepo.GetByID(tenantID, bucketName)
	if err != nil {
		return nil, nil, err
	}
	processCtx := &storagePolicy.ProcessContext{
		Context:    context.Background(),
		TenantID:   tenantID,
		BucketName: bucketName,
		ObjectKey:  objectKey,
		Metadata:   make(map[string]string),
	}
	ctx := context.Background()
	data, meta, err := s.storage.GetObject(ctx, bucket.Name, objectKey)
	if err != nil {
		cachedData, processCtx, cacheErr := s.policyManager.ApplyBeforeDownload(processCtx, nil)
		if cacheErr == nil && cachedData != nil {
			objMeta, _ := s.metadataRepo.Get(tenantID, bucketName, objectKey)
			return cachedData, objMeta, nil
		}
		return nil, nil, errors.NewWithDetail(500, "下载对象失败", err.Error())
	}
	if meta != nil {
		for k, v := range meta {
			processCtx.Metadata[k] = v
		}
	}
	processedData, processCtx, err := s.policyManager.ApplyBeforeDownload(processCtx, data)
	if err != nil {
		return nil, nil, errors.NewWithDetail(500, "应用下载前策略失败", err.Error())
	}
	if err := s.policyManager.ApplyAfterDownload(processCtx, processedData); err != nil {
		return nil, nil, errors.NewWithDetail(500, "应用下载后策略失败", err.Error())
	}
	objMeta, err := s.metadataRepo.Get(tenantID, bucketName, objectKey)
	if err == nil {
		now := common.TimeNowUTC()
		objMeta.AccessedAt = &now
		s.metadataRepo.Update(objMeta)
	}
	return processedData, objMeta, nil
}

func (s *storageServiceImpl) GetObjectMetadata(tenantID, bucketName, objectKey string) (*storage.ObjectMetadata, error) {
	return s.metadataRepo.Get(tenantID, bucketName, objectKey)
}

func (s *storageServiceImpl) DeleteObject(tenantID, bucketName, objectKey string) error {
	bucket, err := s.bucketRepo.GetByID(tenantID, bucketName)
	if err != nil {
		return err
	}
	ctx := context.Background()
	if err := s.storage.DeleteObject(ctx, bucket.Name, objectKey); err != nil {
		return errors.NewWithDetail(500, "删除对象失败", err.Error())
	}
	if cachePolicy, ok := s.policyManager.GetPolicy("cache").(*storagePolicy.CachePolicy); ok {
		cachePolicy.Invalidate(tenantID, bucketName, objectKey)
	}
	return s.metadataRepo.MarkDeleted(tenantID, bucketName, objectKey)
}

func (s *storageServiceImpl) ListObjects(tenantID, bucketName, prefix string) ([]storage.ObjectMetadata, error) {
	bucket, err := s.bucketRepo.GetByID(tenantID, bucketName)
	if err != nil {
		return nil, err
	}
	ctx := context.Background()
	objs, err := s.storage.ListObjects(ctx, bucket.Name, prefix)
	if err != nil {
		return nil, errors.NewWithDetail(500, "列举对象失败", err.Error())
	}
	var metas []storage.ObjectMetadata
	for _, obj := range objs {
		meta, err := s.metadataRepo.Get(tenantID, bucketName, obj.Key)
		if err == nil && meta != nil {
			metas = append(metas, *meta)
		}
	}
	return metas, nil
}

func (s *storageServiceImpl) SearchByTags(tenantID string, tags map[string]string) ([]storage.ObjectMetadata, error) {
	return s.metadataRepo.SearchByTags(tenantID, tags)
}

func (s *storageServiceImpl) UpdateObjectMetadata(tenantID, bucketName, objectKey string, customMeta map[string]interface{}) (*storage.ObjectMetadata, error) {
	meta, err := s.metadataRepo.Get(tenantID, bucketName, objectKey)
	if err != nil {
		return nil, err
	}
	if customMeta != nil {
		for k, v := range customMeta {
			meta.CustomMeta[k] = v
		}
	}
	return s.metadataRepo.Update(meta)
}
