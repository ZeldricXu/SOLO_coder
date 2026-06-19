package service

import (
	"context"
	"errors"
	"fmt"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
)

type TenantRepository interface {
	Create(ctx context.Context, tenant *model.Tenant) error
	GetByID(ctx context.Context, id uuid.UUID) (*model.Tenant, error)
	GetByDomain(ctx context.Context, domain string) (*model.Tenant, error)
	GetByNamespace(ctx context.Context, ns string) (*model.Tenant, error)
	List(ctx context.Context, page, pageSize int) ([]*model.Tenant, int64, error)
	Update(ctx context.Context, tenant *model.Tenant) error
	Delete(ctx context.Context, id uuid.UUID) error
	GetQuota(ctx context.Context, tenantID uuid.UUID, resourceType string) (*model.Quota, error)
	UpdateQuotaUsed(ctx context.Context, tenantID uuid.UUID, resourceType string, delta int64) error
}

type PermissionRepository interface {
	Grant(ctx context.Context, perm *model.Permission) error
	Revoke(ctx context.Context, permID uuid.UUID) error
	CheckPermission(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType, action model.PermissionAction) (bool, error)
	GetUserRole(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType) (model.Role, error)
	CheckByGroups(ctx context.Context, userID, resourceID uuid.UUID, resourceType model.ResourceType, action model.PermissionAction) (bool, error)
}

type CreateTenantRequest struct {
	Name        string                 `json:"name"`
	Domain      string                 `json:"domain"`
	Namespace   string                 `json:"namespace"`
	Description string                 `json:"description"`
	LogoURL     string                 `json:"logo_url"`
	Settings    map[string]interface{} `json:"settings"`
}

type TenantService struct {
	tenantRepo     TenantRepository
	permissionRepo PermissionRepository
}

func NewTenantService(tenantRepo TenantRepository, permissionRepo PermissionRepository) *TenantService {
	return &TenantService{
		tenantRepo:     tenantRepo,
		permissionRepo: permissionRepo,
	}
}

func (s *TenantService) CreateTenant(ctx context.Context, req CreateTenantRequest) (*model.Tenant, error) {
	if req.Name == "" {
		return nil, errors.New("tenant name is required")
	}
	if req.Domain == "" {
		return nil, errors.New("tenant domain is required")
	}
	if req.Namespace == "" {
		return nil, errors.New("tenant namespace is required")
	}

	existing, err := s.tenantRepo.GetByDomain(ctx, req.Domain)
	if err != nil {
		return nil, fmt.Errorf("check domain: %w", err)
	}
	if existing != nil {
		return nil, fmt.Errorf("domain %s already exists", req.Domain)
	}

	existing, err = s.tenantRepo.GetByNamespace(ctx, req.Namespace)
	if err != nil {
		return nil, fmt.Errorf("check namespace: %w", err)
	}
	if existing != nil {
		return nil, fmt.Errorf("namespace %s already exists", req.Namespace)
	}

	tenant := &model.Tenant{
		Name:        req.Name,
		Domain:      req.Domain,
		Namespace:   req.Namespace,
		Description: req.Description,
		LogoURL:     req.LogoURL,
		Status:      "active",
		Settings:    model.JSONB(req.Settings),
	}

	if err := s.tenantRepo.Create(ctx, tenant); err != nil {
		return nil, fmt.Errorf("create tenant: %w", err)
	}

	return tenant, nil
}

func (s *TenantService) GetTenantByDomain(ctx context.Context, domain string) (*model.Tenant, error) {
	return s.tenantRepo.GetByDomain(ctx, domain)
}

func (s *TenantService) DeleteTenant(ctx context.Context, tenantID uuid.UUID) error {
	tenant, err := s.tenantRepo.GetByID(ctx, tenantID)
	if err != nil {
		return fmt.Errorf("get tenant: %w", err)
	}
	if tenant == nil {
		return errors.New("tenant not found")
	}

	return s.tenantRepo.Delete(ctx, tenantID)
}

func (s *TenantService) CheckQuota(ctx context.Context, tenantID uuid.UUID, resourceType string, needed int64) (bool, error) {
	quota, err := s.tenantRepo.GetQuota(ctx, tenantID, resourceType)
	if err != nil {
		return false, fmt.Errorf("get quota: %w", err)
	}
	if quota == nil {
		return true, nil
	}

	var limit, used int64
	switch resourceType {
	case "storage":
		limit = quota.StorageLimit
		used = quota.StorageUsed
	case "documents":
		limit = int64(quota.DocLimit)
		used = int64(quota.DocCount)
	case "users":
		limit = int64(quota.UserLimit)
		used = int64(quota.UserCount)
	case "api_calls":
		limit = quota.ApiCallLimit
		used = quota.ApiCallCount
	default:
		return false, fmt.Errorf("unknown resource type: %s", resourceType)
	}

	if limit <= 0 {
		return true, nil
	}

	return used+needed <= limit, nil
}

func (s *TenantService) ConsumeQuota(ctx context.Context, tenantID uuid.UUID, resourceType string, amount int64) error {
	ok, err := s.CheckQuota(ctx, tenantID, resourceType, amount)
	if err != nil {
		return err
	}
	if !ok {
		return fmt.Errorf("quota exceeded for resource: %s", resourceType)
	}

	return s.tenantRepo.UpdateQuotaUsed(ctx, tenantID, resourceType, amount)
}
