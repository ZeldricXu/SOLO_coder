package storage

import (
	"accessguard/models"
	"sync"
)

type ResourceStore interface {
	Create(resource *models.Resource) error
	GetByID(resourceID string) (*models.Resource, error)
	Update(resource *models.Resource) error
	Delete(resourceID string) error
	List() []*models.Resource
}

type InMemoryResourceStore struct {
	resources map[string]*models.Resource
	mu        sync.RWMutex
}

func NewInMemoryResourceStore() *InMemoryResourceStore {
	return &InMemoryResourceStore{
		resources: make(map[string]*models.Resource),
	}
}

func (s *InMemoryResourceStore) Create(resource *models.Resource) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.resources[resource.ResourceID]; exists {
		return models.ErrResourceAlreadyExists
	}

	s.resources[resource.ResourceID] = resource
	return nil
}

func (s *InMemoryResourceStore) GetByID(resourceID string) (*models.Resource, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	resource, exists := s.resources[resourceID]
	if !exists {
		return nil, models.ErrResourceNotFound
	}
	return resource, nil
}

func (s *InMemoryResourceStore) Update(resource *models.Resource) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.resources[resource.ResourceID]; !exists {
		return models.ErrResourceNotFound
	}

	s.resources[resource.ResourceID] = resource
	return nil
}

func (s *InMemoryResourceStore) Delete(resourceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.resources[resourceID]; !exists {
		return models.ErrResourceNotFound
	}

	delete(s.resources, resourceID)
	return nil
}

func (s *InMemoryResourceStore) List() []*models.Resource {
	s.mu.RLock()
	defer s.mu.RUnlock()

	resources := make([]*models.Resource, 0, len(s.resources))
	for _, resource := range s.resources {
		resources = append(resources, resource)
	}
	return resources
}
