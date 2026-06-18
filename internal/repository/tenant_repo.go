package repository

import (
	"context"
	"errors"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type TenantRepository struct {
	db *gorm.DB
}

func NewTenantRepository(db *gorm.DB) *TenantRepository {
	return &TenantRepository{db: db}
}

func (r *TenantRepository) Create(ctx context.Context, tenant *model.Tenant) error {
	tenant.CreatedAt = time.Now().UTC()
	tenant.UpdatedAt = time.Now().UTC()
	if tenant.ID == uuid.Nil {
		tenant.ID = uuid.New()
	}
	err := r.db.WithContext(ctx).Create(tenant).Error
	if err != nil {
		if IsUniqueViolation(err) {
			return ErrAlreadyExists
		}
		return err
	}
	return nil
}

func (r *TenantRepository) GetByID(ctx context.Context, id uuid.UUID) (*model.Tenant, error) {
	var tenant model.Tenant
	err := r.db.WithContext(ctx).First(&tenant, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &tenant, nil
}

func (r *TenantRepository) GetByDomain(ctx context.Context, domain string) (*model.Tenant, error) {
	var tenant model.Tenant
	err := r.db.WithContext(ctx).Where("domain = ?", domain).First(&tenant).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &tenant, nil
}

func (r *TenantRepository) GetByNamespace(ctx context.Context, namespace string) (*model.Tenant, error) {
	var tenant model.Tenant
	err := r.db.WithContext(ctx).Where("namespace = ?", namespace).First(&tenant).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &tenant, nil
}

func (r *TenantRepository) Update(ctx context.Context, tenant *model.Tenant) error {
	tenant.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Save(tenant)
	if result.Error != nil {
		if IsUniqueViolation(result.Error) {
			return ErrAlreadyExists
		}
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *TenantRepository) Delete(ctx context.Context, id uuid.UUID) error {
	result := r.db.WithContext(ctx).Delete(&model.Tenant{}, id)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *TenantRepository) List(ctx context.Context, page, pageSize int, status model.TenantStatus, keyword string) (*database.PaginatedResult, error) {
	query := r.db.WithContext(ctx).Model(&model.Tenant{})
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if keyword != "" {
		query = query.Where("name ILIKE ? OR domain ILIKE ? OR namespace ILIKE ?",
			"%"+keyword+"%", "%"+keyword+"%", "%"+keyword+"%")
	}

	var tenants []model.Tenant
	pr, err := database.Paginate(query.Order("created_at DESC"), page, pageSize, &tenants)
	if err != nil {
		return nil, err
	}
	pr.Data = tenants
	return pr, nil
}

func (r *TenantRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status model.TenantStatus) error {
	result := r.db.WithContext(ctx).Model(&model.Tenant{}).
		Where("id = ?", id).
		UpdateColumns(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now().UTC(),
		})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *TenantRepository) CheckQuota(ctx context.Context, tenantID uuid.UUID, resourceType string) (*model.Quota, error) {
	var quota model.Quota
	err := r.db.WithContext(ctx).
		Where("tenant_id = ? AND resource_type = ?", tenantID, resourceType).
		First(&quota).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &quota, nil
}

func (r *TenantRepository) CheckQuotaAndIncrement(ctx context.Context, tenantID uuid.UUID, resourceType string, amount int64) (bool, error) {
	quota, err := r.CheckQuota(ctx, tenantID, resourceType)
	if err != nil {
		return false, err
	}
	if quota == nil || quota.Limit == -1 {
		return true, nil
	}

	if !quota.Enabled {
		return true, nil
	}

	if quota.ResetAt != nil && time.Now().UTC().After(*quota.ResetAt) {
		_ = r.resetQuotaUsage(ctx, quota.ID)
		quota.Used = 0
	}

	if quota.Used+amount > quota.Limit {
		return false, nil
	}

	result := r.db.WithContext(ctx).Model(&model.Quota{}).
		Where("id = ?", quota.ID).
		UpdateColumn("used", gorm.Expr("used + ?", amount))
	if result.Error != nil {
		return false, result.Error
	}
	return true, nil
}

func (r *TenantRepository) resetQuotaUsage(ctx context.Context, quotaID uuid.UUID) error {
	now := time.Now().UTC()
	var nextResetAt time.Time
	if quota, err := r.GetQuota(ctx, quotaID); err == nil && quota != nil {
		switch quota.Period {
		case "daily":
			nextResetAt = now.AddDate(0, 0, 1)
		case "weekly":
			nextResetAt = now.AddDate(0, 0, 7)
		case "monthly":
			nextResetAt = now.AddDate(0, 1, 0)
		default:
			return nil
		}
	} else {
		return err
	}

	return r.db.WithContext(ctx).Model(&model.Quota{}).
		Where("id = ?", quotaID).
		UpdateColumns(map[string]interface{}{
			"used":      0,
			"reset_at":  nextResetAt,
			"updated_at": now,
		}).Error
}

func (r *TenantRepository) GetQuota(ctx context.Context, id uuid.UUID) (*model.Quota, error) {
	var quota model.Quota
	err := r.db.WithContext(ctx).First(&quota, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &quota, nil
}

func (r *TenantRepository) CreateQuota(ctx context.Context, quota *model.Quota) error {
	quota.CreatedAt = time.Now().UTC()
	quota.UpdatedAt = time.Now().UTC()
	if quota.ID == uuid.Nil {
		quota.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(quota).Error
}

func (r *TenantRepository) ListQuotas(ctx context.Context, tenantID uuid.UUID) ([]model.Quota, error) {
	var quotas []model.Quota
	err := r.db.WithContext(ctx).Where("tenant_id = ?", tenantID).Find(&quotas).Error
	return quotas, err
}

func (r *TenantRepository) UpdateQuota(ctx context.Context, quota *model.Quota) error {
	quota.UpdatedAt = time.Now().UTC()
	result := r.db.WithContext(ctx).Save(quota)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *TenantRepository) GetTheme(ctx context.Context, tenantID uuid.UUID, id uuid.UUID) (*model.Theme, error) {
	var theme model.Theme
	err := r.db.WithContext(ctx).Scopes(database.TenantScope(ctx)).First(&theme, id).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return &theme, nil
}

func (r *TenantRepository) CreateTheme(ctx context.Context, theme *model.Theme) error {
	theme.CreatedAt = time.Now().UTC()
	theme.UpdatedAt = time.Now().UTC()
	if theme.ID == uuid.Nil {
		theme.ID = uuid.New()
	}
	return r.db.WithContext(ctx).Create(theme).Error
}

func (r *TenantRepository) ListThemes(ctx context.Context, tenantID uuid.UUID) ([]model.Theme, error) {
	var themes []model.Theme
	err := r.db.WithContext(ctx).Where("tenant_id = ?", tenantID).Find(&themes).Error
	return themes, err
}

func (r *TenantRepository) SetDefaultTheme(ctx context.Context, tenantID, themeID uuid.UUID) error {
	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&model.Theme{}).
			Where("tenant_id = ? AND is_default = ?", tenantID, true).
			Update("is_default", false).Error; err != nil {
			return err
		}
		if err := tx.Model(&model.Theme{}).
			Where("id = ? AND tenant_id = ?", themeID, tenantID).
			Update("is_default", true).Error; err != nil {
			return err
		}
		return nil
	})
	return err
}
