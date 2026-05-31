package repository

import (
	"fmt"

	"gorm.io/gorm"
	"session187/internal/common"
	"session187/internal/storage"
	"session187/pkg/errors"
)

type MetadataRepository interface {
	Create(meta *storage.ObjectMetadata) (*storage.ObjectMetadata, error)
	Get(tenantID, bucket, key string) (*storage.ObjectMetadata, error)
	Update(meta *storage.ObjectMetadata) (*storage.ObjectMetadata, error)
	List(tenantID, bucket string) ([]storage.ObjectMetadata, error)
	SearchByTags(tenantID string, tags map[string]string) ([]storage.ObjectMetadata, error)
	MarkDeleted(tenantID, bucket, key string) error
}

type GormMetadataRepository struct {
	db *gorm.DB
}

func NewMetadataRepository(db *gorm.DB) MetadataRepository {
	return &GormMetadataRepository{db: db}
}

func (r *GormMetadataRepository) Create(meta *storage.ObjectMetadata) (*storage.ObjectMetadata, error) {
	if meta.ID == "" {
		meta.ID = common.GenerateID("obj")
	}
	if meta.Status == "" {
		meta.Status = "active"
	}
	if meta.CustomMeta == nil {
		meta.CustomMeta = make(map[string]interface{})
	}
	now := common.TimeNowUTC()
	meta.CreatedAt = now
	meta.UpdatedAt = now
	if err := r.db.Create(meta).Error; err != nil {
		return nil, errors.NewWithDetail(500, "保存元数据失败", err.Error())
	}
	return meta, nil
}

func (r *GormMetadataRepository) Get(tenantID, bucket, key string) (*storage.ObjectMetadata, error) {
	var meta storage.ObjectMetadata
	err := r.db.Where("tenant_id = ? AND bucket = ? AND object_key = ?",
		tenantID, bucket, key).Order("version desc").First(&meta).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询元数据失败", err.Error())
	}
	return &meta, nil
}

func (r *GormMetadataRepository) Update(meta *storage.ObjectMetadata) (*storage.ObjectMetadata, error) {
	meta.Version++
	meta.UpdatedAt = common.TimeNowUTC()
	if err := r.db.Save(meta).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新元数据失败", err.Error())
	}
	return meta, nil
}

func (r *GormMetadataRepository) List(tenantID, bucket string) ([]storage.ObjectMetadata, error) {
	var metas []storage.ObjectMetadata
	err := r.db.Where("tenant_id = ? AND bucket = ? AND status = ?",
		tenantID, bucket, "active").Find(&metas).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询元数据列表失败", err.Error())
	}
	return metas, nil
}

func (r *GormMetadataRepository) SearchByTags(tenantID string, tags map[string]string) ([]storage.ObjectMetadata, error) {
	var metas []storage.ObjectMetadata
	query := r.db.Where("tenant_id = ? AND status = ?", tenantID, "active")
	for k, v := range tags {
		tagKey := fmt.Sprintf("tags->>'%s'", k)
		query = query.Where(fmt.Sprintf("%s = ?", tagKey), v)
	}
	err := query.Find(&metas).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "按标签搜索失败", err.Error())
	}
	return metas, nil
}

func (r *GormMetadataRepository) MarkDeleted(tenantID, bucket, key string) error {
	return r.db.Model(&storage.ObjectMetadata{}).
		Where("tenant_id = ? AND bucket = ? AND object_key = ?", tenantID, bucket, key).
		Updates(map[string]interface{}{
			"status":     "deleted",
			"updated_at": common.TimeNowUTC(),
		}).Error
}
