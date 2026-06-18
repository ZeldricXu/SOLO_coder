package repository

import (
	"context"
	"errors"
	"time"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

var (
	ErrAttachmentNotFound = errors.New("attachment not found")
)

type AttachmentRepository interface {
	Create(ctx context.Context, att *model.Attachment) error
	Update(ctx context.Context, att *model.Attachment) error
	Delete(ctx context.Context, tenantID, id uuid.UUID) error
	GetByID(ctx context.Context, tenantID, id uuid.UUID) (*model.Attachment, error)
	ListByDoc(ctx context.Context, tenantID, docID uuid.UUID, page, pageSize int) ([]*model.Attachment, int64, error)
	ListBySpace(ctx context.Context, tenantID, spaceID uuid.UUID, page, pageSize int) ([]*model.Attachment, int64, error)
	IncrementDownload(ctx context.Context, tenantID, id uuid.UUID) error
}

type gormAttachmentRepository struct {
	db *gorm.DB
}

func NewAttachmentRepository(db *gorm.DB) AttachmentRepository {
	return &gormAttachmentRepository{db: db}
}

func (r *gormAttachmentRepository) Create(ctx context.Context, att *model.Attachment) error {
	if att.CreatedAt.IsZero() {
		att.CreatedAt = time.Now()
	}
	att.UpdatedAt = time.Now()
	return r.db.WithContext(ctx).Create(att).Error
}

func (r *gormAttachmentRepository) Update(ctx context.Context, att *model.Attachment) error {
	att.UpdatedAt = time.Now()
	result := r.db.WithContext(ctx).Model(&model.Attachment{}).
		Where("id = ? AND tenant_id = ?", att.ID, att.TenantID).
		Updates(map[string]interface{}{
			"file_name":      att.FileName,
			"original_name":  att.OriginalName,
			"description":    "",
			"updated_at":     time.Now(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrAttachmentNotFound
	}
	return nil
}

func (r *gormAttachmentRepository) Delete(ctx context.Context, tenantID, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).Delete(&model.Attachment{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrAttachmentNotFound
	}
	return nil
}

func (r *gormAttachmentRepository) GetByID(ctx context.Context, tenantID, id uuid.UUID) (*model.Attachment, error) {
	var att model.Attachment
	result := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).First(&att)
	if result.Error != nil {
		if errors.Is(result.Error, gorm.ErrRecordNotFound) {
			return nil, ErrAttachmentNotFound
		}
		return nil, result.Error
	}
	return &att, nil
}

func (r *gormAttachmentRepository) ListByDoc(ctx context.Context, tenantID, docID uuid.UUID, page, pageSize int) ([]*model.Attachment, int64, error) {
	var atts []*model.Attachment
	var total int64

	query := r.db.WithContext(ctx).Model(&model.Attachment{}).
		Where("tenant_id = ? AND document_id = ?", tenantID, docID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").
		Offset(offset).Limit(pageSize).Find(&atts).Error; err != nil {
		return nil, 0, err
	}

	return atts, total, nil
}

func (r *gormAttachmentRepository) ListBySpace(ctx context.Context, tenantID, spaceID uuid.UUID, page, pageSize int) ([]*model.Attachment, int64, error) {
	var atts []*model.Attachment
	var total int64

	query := r.db.WithContext(ctx).Model(&model.Attachment{}).
		Where("tenant_id = ? AND space_id = ?", tenantID, spaceID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").
		Offset(offset).Limit(pageSize).Find(&atts).Error; err != nil {
		return nil, 0, err
	}

	return atts, total, nil
}

func (r *gormAttachmentRepository) IncrementDownload(ctx context.Context, tenantID, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Model(&model.Attachment{}).
		Where("id = ? AND tenant_id = ?", id, tenantID).
		UpdateColumn("download_count", gorm.Expr("download_count + 1"))
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrAttachmentNotFound
	}
	return nil
}
