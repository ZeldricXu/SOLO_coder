package repository

import (
	"gorm.io/gorm"
	"session187/internal/common"
	"session187/internal/storage"
	"session187/pkg/errors"
)

type BucketRepository interface {
	Create(bucket *storage.Bucket) (*storage.Bucket, error)
	GetByID(tenantID, name string) (*storage.Bucket, error)
	List(tenantID string) ([]storage.Bucket, error)
	Update(bucket *storage.Bucket) (*storage.Bucket, error)
	Delete(tenantID, name string) error
}

type GormBucketRepository struct {
	db *gorm.DB
}

func NewBucketRepository(db *gorm.DB) BucketRepository {
	return &GormBucketRepository{db: db}
}

func (r *GormBucketRepository) Create(bucket *storage.Bucket) (*storage.Bucket, error) {
	if bucket.ID == "" {
		bucket.ID = common.GenerateID("bkt")
	}
	if bucket.Status == "" {
		bucket.Status = "active"
	}
	now := common.TimeNowUTC()
	bucket.CreatedAt = now
	bucket.UpdatedAt = now
	if err := r.db.Create(bucket).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建存储桶失败", err.Error())
	}
	return bucket, nil
}

func (r *GormBucketRepository) GetByID(tenantID, name string) (*storage.Bucket, error) {
	var bucket storage.Bucket
	err := r.db.Where("tenant_id = ? AND name = ?", tenantID, name).First(&bucket).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询存储桶失败", err.Error())
	}
	return &bucket, nil
}

func (r *GormBucketRepository) List(tenantID string) ([]storage.Bucket, error) {
	var buckets []storage.Bucket
	err := r.db.Where("tenant_id = ?", tenantID).Find(&buckets).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询存储桶列表失败", err.Error())
	}
	return buckets, nil
}

func (r *GormBucketRepository) Update(bucket *storage.Bucket) (*storage.Bucket, error) {
	bucket.UpdatedAt = common.TimeNowUTC()
	if err := r.db.Save(bucket).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新存储桶失败", err.Error())
	}
	return bucket, nil
}

func (r *GormBucketRepository) Delete(tenantID, name string) error {
	bucket, err := r.GetByID(tenantID, name)
	if err != nil {
		return err
	}
	bucket.Status = "deleted"
	bucket.UpdatedAt = common.TimeNowUTC()
	return r.db.Save(bucket).Error
}
