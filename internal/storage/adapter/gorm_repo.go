package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/storage/domain"
	"github.com/solocoder/session147/internal/storage/ports"
	"gorm.io/gorm"
)

type gormStorageRepo struct {
	db *gorm.DB
}

func NewGormStorageRepository(db *gorm.DB) ports.StorageRepository {
	return &gormStorageRepo{db: db}
}

func (r *gormStorageRepo) StoreContent(ctx context.Context, content *domain.StoredContent) error {
	return r.db.WithContext(ctx).Create(content).Error
}

func (r *gormStorageRepo) GetContent(ctx context.Context, id string) (*domain.StoredContent, error) {
	var content domain.StoredContent
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&content).Error
	if err != nil {
		return nil, err
	}
	return &content, nil
}

func (r *gormStorageRepo) GetContentByCID(ctx context.Context, cid string, storageType string) (*domain.StoredContent, error) {
	var content domain.StoredContent
	err := r.db.WithContext(ctx).Where("content_id = ? AND storage_type = ?", cid, storageType).First(&content).Error
	if err != nil {
		return nil, err
	}
	return &content, nil
}

func (r *gormStorageRepo) ListContents(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.StoredContent, int64, error) {
	var contents []domain.StoredContent
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.StoredContent{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&contents).Error
	return contents, total, err
}

func (r *gormStorageRepo) UpdateContent(ctx context.Context, content *domain.StoredContent) error {
	return r.db.WithContext(ctx).Save(content).Error
}

func (r *gormStorageRepo) DeleteContent(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&domain.StoredContent{}, "id = ?", id).Error
}

func (r *gormStorageRepo) CreatePinOperation(ctx context.Context, op *domain.PinOperation) error {
	return r.db.WithContext(ctx).Create(op).Error
}

func (r *gormStorageRepo) GetPinOperation(ctx context.Context, id string) (*domain.PinOperation, error) {
	var op domain.PinOperation
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&op).Error
	if err != nil {
		return nil, err
	}
	return &op, nil
}

func (r *gormStorageRepo) UpdatePinOperation(ctx context.Context, op *domain.PinOperation) error {
	return r.db.WithContext(ctx).Save(op).Error
}
