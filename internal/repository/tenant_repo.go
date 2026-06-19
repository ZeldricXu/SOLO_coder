package repository

import (
	"context"
	"errors"
	"fmt"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type TenantRepo struct {
	*BaseRepo
}

func NewTenantRepo(db *gorm.DB) *TenantRepo {
	return &TenantRepo{BaseRepo: NewBaseRepo(db)}
}

func (r *TenantRepo) Create(ctx context.Context, tenant *model.Tenant) error {
	return r.DB.WithContext(ctx).Create(tenant).Error
}

func (r *TenantRepo) GetByID(ctx context.Context, id uuid.UUID) (*model.Tenant, error) {
	var tenant model.Tenant
	err := r.DB.WithContext(ctx).Where("id = ?", id.String()).First(&tenant).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &tenant, nil
}

func (r *TenantRepo) GetByDomain(ctx context.Context, domain string) (*model.Tenant, error) {
	var tenant model.Tenant
	err := r.DB.WithContext(ctx).Where("domain = ?", domain).First(&tenant).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &tenant, nil
}

func (r *TenantRepo) GetByNamespace(ctx context.Context, ns string) (*model.Tenant, error) {
	var tenant model.Tenant
	err := r.DB.WithContext(ctx).Where("namespace = ?", ns).First(&tenant).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &tenant, nil
}

func (r *TenantRepo) List(ctx context.Context, page, pageSize int) ([]*model.Tenant, int64, error) {
	var tenants []*model.Tenant
	var total int64

	db := r.DB.WithContext(ctx).Model(&model.Tenant{})
	if err := db.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := db.Scopes(r.Paginate(page, pageSize)).Find(&tenants).Error; err != nil {
		return nil, 0, err
	}

	return tenants, total, nil
}

func (r *TenantRepo) Update(ctx context.Context, tenant *model.Tenant) error {
	return r.DB.WithContext(ctx).Save(tenant).Error
}

func (r *TenantRepo) Delete(ctx context.Context, id uuid.UUID) error {
	return r.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		tenantID := id.String()

		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.SearchIndex{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Attachment{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.DocumentVersion{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Document{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Directory{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Permission{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.ApiToken{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.I18nDoc{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.TranslationMemory{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Quota{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Theme{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.UserGroupMember{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.UserGroup{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Department{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.User{}).Error; err != nil {
			return err
		}
		if err := tx.Where("tenant_id = ?", tenantID).Delete(&model.Space{}).Error; err != nil {
			return err
		}

		if err := tx.Where("id = ?", tenantID).Delete(&model.Tenant{}).Error; err != nil {
			return err
		}

		return nil
	})
}

func (r *TenantRepo) GetQuota(ctx context.Context, tenantID uuid.UUID, resourceType string) (*model.Quota, error) {
	var quota model.Quota
	err := r.DB.WithContext(ctx).Where("tenant_id = ?", tenantID.String()).First(&quota).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &quota, nil
}

func (r *TenantRepo) UpdateQuotaUsed(ctx context.Context, tenantID uuid.UUID, resourceType string, delta int64) error {
	tid := tenantID.String()
	var field string

	switch resourceType {
	case "storage":
		field = "storage_used"
	case "documents":
		field = "doc_count"
	case "users":
		field = "user_count"
	case "api_calls":
		field = "api_call_count"
	default:
		return fmt.Errorf("unknown resource type: %s", resourceType)
	}

	result := r.DB.WithContext(ctx).Model(&model.Quota{}).
		Where("tenant_id = ?", tid).
		UpdateColumn(field, gorm.Expr(field+" + ?", delta))

	if result.Error != nil {
		return result.Error
	}

	if result.RowsAffected == 0 {
		quota := &model.Quota{
			TenantScoped: model.TenantScoped{TenantID: tid},
		}
		switch resourceType {
		case "storage":
			quota.StorageUsed = delta
		case "documents":
			quota.DocCount = int(delta)
		case "users":
			quota.UserCount = int(delta)
		case "api_calls":
			quota.ApiCallCount = delta
		}
		return r.DB.WithContext(ctx).Create(quota).Error
	}

	return nil
}
