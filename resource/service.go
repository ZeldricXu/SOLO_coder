package resource

import (
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"time"
)

type ResourceCacheInvalidator interface {
	InvalidateResource(resourceID string)
	UpdateResourcePermissions(resourceID string)
}

type Service struct {
	store            storage.ResourceStore
	cacheInvalidator ResourceCacheInvalidator
}

func NewService(store storage.ResourceStore) *Service {
	return &Service{store: store}
}

func (s *Service) SetCacheInvalidator(invalidator ResourceCacheInvalidator) {
	s.cacheInvalidator = invalidator
}

func (s *Service) updateResource(resourceID string) {
	if s.cacheInvalidator != nil {
		s.cacheInvalidator.UpdateResourcePermissions(resourceID)
	}
}

func (s *Service) CreateResource(req *models.CreateResourceRequest) (*models.Resource, error) {
	if req.ResourceName == "" || req.ResourceType == "" {
		return nil, models.ErrInvalidRequest
	}

	resource := &models.Resource{
		ResourceID:          utils.GenerateResourceID(),
		ResourceName:        req.ResourceName,
		ResourceType:        req.ResourceType,
		RequiredPermissions: s.deduplicatePermissions(req.RequiredPermissions),
		Owner:               req.Owner,
		CreatedAt:           time.Now(),
	}

	err := s.store.Create(resource)
	if err != nil {
		return nil, err
	}

	return resource, nil
}

func (s *Service) GetResourceByID(resourceID string) (*models.Resource, error) {
	return s.store.GetByID(resourceID)
}

func (s *Service) UpdateResource(resourceID string, req *models.UpdateResourceRequest) (*models.Resource, error) {
	resource, err := s.store.GetByID(resourceID)
	if err != nil {
		return nil, err
	}

	permissionsChanged := false

	if req.ResourceName != nil {
		resource.ResourceName = *req.ResourceName
	}

	if req.ResourceType != nil {
		resource.ResourceType = *req.ResourceType
	}

	if req.RequiredPermissions != nil {
		newPerms := s.deduplicatePermissions(*req.RequiredPermissions)
		if !s.permissionsEqual(resource.RequiredPermissions, newPerms) {
			permissionsChanged = true
			resource.RequiredPermissions = newPerms
		}
	}

	if req.Owner != nil {
		resource.Owner = *req.Owner
	}

	err = s.store.Update(resource)
	if err != nil {
		return nil, err
	}

	if permissionsChanged {
		s.updateResource(resourceID)
	}

	return resource, nil
}

func (s *Service) DeleteResource(resourceID string) error {
	err := s.store.Delete(resourceID)
	if err == nil {
		s.updateResource(resourceID)
	}
	return err
}

func (s *Service) ListResources() []*models.Resource {
	return s.store.List()
}

func (s *Service) GetRequiredPermissions(resourceID string) ([]string, error) {
	resource, err := s.store.GetByID(resourceID)
	if err != nil {
		return nil, err
	}
	return resource.RequiredPermissions, nil
}

func (s *Service) AddRequiredPermission(resourceID, permission string) error {
	resource, err := s.store.GetByID(resourceID)
	if err != nil {
		return err
	}

	for _, p := range resource.RequiredPermissions {
		if p == permission {
			return nil
		}
	}

	resource.RequiredPermissions = append(resource.RequiredPermissions, permission)
	err = s.store.Update(resource)
	if err == nil {
		s.updateResource(resourceID)
	}
	return err
}

func (s *Service) RemoveRequiredPermission(resourceID, permission string) error {
	resource, err := s.store.GetByID(resourceID)
	if err != nil {
		return err
	}

	found := false
	newPermissions := make([]string, 0, len(resource.RequiredPermissions))
	for _, p := range resource.RequiredPermissions {
		if p == permission {
			found = true
		} else {
			newPermissions = append(newPermissions, p)
		}
	}

	if !found {
		return nil
	}

	resource.RequiredPermissions = newPermissions
	err = s.store.Update(resource)
	if err == nil {
		s.updateResource(resourceID)
	}
	return err
}

func (s *Service) deduplicatePermissions(permissions []string) []string {
	if permissions == nil {
		return []string{}
	}

	seen := make(map[string]bool)
	result := make([]string, 0, len(permissions))
	for _, p := range permissions {
		if p != "" && !seen[p] {
			seen[p] = true
			result = append(result, p)
		}
	}
	return result
}

func (s *Service) permissionsEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}

	seen := make(map[string]bool)
	for _, p := range a {
		seen[p] = true
	}

	for _, p := range b {
		if !seen[p] {
			return false
		}
	}

	return true
}
