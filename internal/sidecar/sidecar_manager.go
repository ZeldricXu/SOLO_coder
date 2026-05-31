package sidecar

import (
	"sync"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type SidecarManager struct {
	mu       sync.RWMutex
	sidecars map[string]*models.SidecarSpec
	injector map[string]string
}

func NewSidecarManager() *SidecarManager {
	return &SidecarManager{
		sidecars: make(map[string]*models.SidecarSpec),
		injector: make(map[string]string),
	}
}

func (m *SidecarManager) RegisterSidecar(name, image, injectionPolicy string, resources models.ResourceLimit, config map[string]interface{}, hotReload bool) *models.SidecarSpec {
	m.mu.Lock()
	defer m.mu.Unlock()
	spec := &models.SidecarSpec{
		ID:              utils.GenerateID("sidecar"),
		Name:            name,
		Image:           image,
		InjectionPolicy: injectionPolicy,
		Resources:       resources,
		Config:          config,
		HotReload:       hotReload,
	}
	m.sidecars[spec.ID] = spec
	return spec
}

func (m *SidecarManager) ListSidecars() []*models.SidecarSpec {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]*models.SidecarSpec, 0, len(m.sidecars))
	for _, s := range m.sidecars {
		result = append(result, s)
	}
	return result
}

func (m *SidecarManager) GetSidecar(id string) (*models.SidecarSpec, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.sidecars[id]
	return s, ok
}

func (m *SidecarManager) UpdateConfig(id string, config map[string]interface{}) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	spec, ok := m.sidecars[id]
	if !ok {
		return ErrSidecarNotFound
	}
	spec.Config = config
	if spec.HotReload {
		m.triggerHotReload(id)
	}
	return nil
}

func (m *SidecarManager) UpdateResources(id string, resources models.ResourceLimit) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	spec, ok := m.sidecars[id]
	if !ok {
		return ErrSidecarNotFound
	}
	spec.Resources = resources
	return nil
}

func (m *SidecarManager) Inject(targetNamespace string, sidecarIDs []string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, id := range sidecarIDs {
		if _, ok := m.sidecars[id]; !ok {
			return ErrSidecarNotFound
		}
		m.injector[targetNamespace+":"+id] = id
	}
	return nil
}

func (m *SidecarManager) Uninject(targetNamespace string, sidecarID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.injector, targetNamespace+":"+sidecarID)
}

func (m *SidecarManager) ListInjections() map[string]string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make(map[string]string)
	for k, v := range m.injector {
		result[k] = v
	}
	return result
}

func (m *SidecarManager) triggerHotReload(id string) {
}

var ErrSidecarNotFound = &sidecarError{"sidecar not found"}

type sidecarError struct {
	msg string
}

func (e *sidecarError) Error() string { return e.msg }
