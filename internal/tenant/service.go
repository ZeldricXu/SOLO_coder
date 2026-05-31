package tenant

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type ContextKey string

const TenantKey ContextKey = "tenant_id"

type Service struct {
	db     *gorm.DB
	cache  *MultiLevelCache
	mu     sync.RWMutex
}

type ServiceOption func(*Service)

func WithL2Cache(client L2Client) ServiceOption {
	return func(s *Service) {
		config := DefaultCacheConfig()
		config.L2Enabled = true
		s.cache = NewMultiLevelCache(config, client)
	}
}

func WithCacheConfig(config *CacheConfig, client L2Client) ServiceOption {
	return func(s *Service) {
		s.cache = NewMultiLevelCache(config, client)
	}
}

func NewService(db *gorm.DB, opts ...ServiceOption) *Service {
	service := &Service{
		db:    db,
		cache: NewMultiLevelCache(DefaultCacheConfig(), nil),
	}
	for _, opt := range opts {
		opt(service)
	}
	return service
}

func (s *Service) CreateTenant(ctx context.Context, name, desc string, cfg *models.TenantConfig, quota *models.Quota) (*models.Tenant, error) {
	if name == "" {
		return nil, errors.New("tenant name is required")
	}

	if cfg == nil {
		cfg = &models.TenantConfig{
			Theme:    "default",
			Language: "zh-CN",
			Timezone: "Asia/Shanghai",
			Features: map[string]bool{"basic": true},
		}
	}
	if quota == nil {
		quota = &models.Quota{
			MaxStorageGB:   100,
			MaxUsers:       50,
			MaxWorkflows:   20,
			MaxAPICallsDay: 10000,
		}
	}

	cfgBytes, err := json.Marshal(cfg)
	if err != nil {
		return nil, err
	}
	quotaBytes, err := json.Marshal(quota)
	if err != nil {
		return nil, err
	}

	tenant := &models.Tenant{
		ID:          fmt.Sprintf("tnt_%s", uuid.New().String()[:8]),
		Name:        name,
		Description: desc,
		Config:      cfgBytes,
		Quota:       quotaBytes,
		Status:      "active",
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(tenant).Error; err != nil {
		logger.Error("failed to create tenant", zap.Error(err))
		return nil, err
	}

	s.cache.Put(ctx, tenant)

	logger.Info("tenant created", zap.String("tenant_id", tenant.ID), zap.String("name", name))
	return tenant, nil
}

func (s *Service) GetTenant(ctx context.Context, tenantID string) (*models.Tenant, error) {
	tenant, level, err := s.cache.Get(ctx, tenantID)
	if err == nil && tenant != nil {
		logger.Debug("cache hit", zap.String("tenant_id", tenantID), zap.String("level", string(level)))
		return tenant, nil
	}

	var dbTenant models.Tenant
	if err := s.db.WithContext(ctx).Where("id = ?", tenantID).First(&dbTenant).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("tenant not found")
		}
		return nil, err
	}

	s.cache.Put(ctx, &dbTenant)

	logger.Debug("cache miss, loaded from db", zap.String("tenant_id", tenantID))
	return &dbTenant, nil
}

func (s *Service) ListTenants(ctx context.Context, page, pageSize int) ([]*models.Tenant, int64, error) {
	var tenants []*models.Tenant
	var total int64

	query := s.db.WithContext(ctx).Model(&models.Tenant{})

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&tenants).Error; err != nil {
		return nil, 0, err
	}

	return tenants, total, nil
}

func (s *Service) UpdateTenant(ctx context.Context, tenantID string, updates map[string]interface{}) error {
	tenant := s.db.WithContext(ctx).Model(&models.Tenant{}).Where("id = ?", tenantID).Updates(updates)
	if err := tenant.Error; err != nil {
		logger.Error("failed to update tenant", zap.Error(err), zap.String("tenant_id", tenantID))
		return err
	}

	s.cache.Invalidate(ctx, tenantID)
	logger.Debug("cache invalidated", zap.String("tenant_id", tenantID))

	return nil
}

func (s *Service) DeleteTenant(ctx context.Context, tenantID string) error {
	if err := s.db.WithContext(ctx).Where("id = ?", tenantID).Delete(&models.Tenant{}).Error; err != nil {
		logger.Error("failed to delete tenant", zap.Error(err), zap.String("tenant_id", tenantID))
		return err
	}

	s.cache.Invalidate(ctx, tenantID)
	logger.Debug("cache invalidated", zap.String("tenant_id", tenantID))

	return nil
}

func (s *Service) GetConfig(ctx context.Context, tenantID string) (*models.TenantConfig, error) {
	tenant, err := s.GetTenant(ctx, tenantID)
	if err != nil {
		return nil, err
	}

	var cfg models.TenantConfig
	if err := json.Unmarshal(tenant.Config, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func (s *Service) UpdateConfig(ctx context.Context, tenantID string, cfg *models.TenantConfig) error {
	cfgBytes, err := json.Marshal(cfg)
	if err != nil {
		return err
	}

	return s.UpdateTenant(ctx, tenantID, map[string]interface{}{
		"config":     cfgBytes,
		"updated_at": time.Now(),
	})
}

func (s *Service) GetQuota(ctx context.Context, tenantID string) (*models.Quota, error) {
	tenant, err := s.GetTenant(ctx, tenantID)
	if err != nil {
		return nil, err
	}

	var quota models.Quota
	if err := json.Unmarshal(tenant.Quota, &quota); err != nil {
		return nil, err
	}
	return &quota, nil
}

func (s *Service) UpdateQuota(ctx context.Context, tenantID string, quota *models.Quota) error {
	quotaBytes, err := json.Marshal(quota)
	if err != nil {
		return err
	}

	return s.UpdateTenant(ctx, tenantID, map[string]interface{}{
		"quota":      quotaBytes,
		"updated_at": time.Now(),
	})
}

func (s *Service) CheckQuota(ctx context.Context, tenantID string, resourceType string, current int) (bool, error) {
	quota, err := s.GetQuota(ctx, tenantID)
	if err != nil {
		return false, err
	}

	switch resourceType {
	case "storage":
		return current < int(quota.MaxStorageGB), nil
	case "users":
		return current < int(quota.MaxUsers), nil
	case "workflows":
		return current < int(quota.MaxWorkflows), nil
	case "api_calls":
		return current < int(quota.MaxAPICallsDay), nil
	default:
		return false, errors.New("unknown resource type")
	}
}

func (s *Service) WarmupCache(ctx context.Context) error {
	var tenants []*models.Tenant
	if err := s.db.WithContext(ctx).Find(&tenants).Error; err != nil {
		return err
	}

	s.cache.Warmup(ctx, tenants)
	logger.Info("tenant cache warmup completed", zap.Int("count", len(tenants)))
	return nil
}

func (s *Service) GetCacheStats() *CacheStatsSnapshot {
	return s.cache.GetStats()
}

func (s *Service) ResetCacheStats() {
	s.cache.ResetStats()
}

func (s *Service) InvalidateCache(ctx context.Context, tenantID string) {
	if tenantID == "" {
		s.cache.InvalidateAll(ctx)
		logger.Info("all tenant cache invalidated")
	} else {
		s.cache.Invalidate(ctx, tenantID)
	}
}

func FromContext(ctx context.Context) (string, bool) {
	tenantID, ok := ctx.Value(TenantKey).(string)
	return tenantID, ok
}

func WithTenant(ctx context.Context, tenantID string) context.Context {
	return context.WithValue(ctx, TenantKey, tenantID)
}

func Scope(tenantID string) func(db *gorm.DB) *gorm.DB {
	return func(db *gorm.DB) *gorm.DB {
		return db.Where("tenant_id = ?", tenantID)
	}
}
