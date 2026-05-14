package cache

import (
	"accessguard/models"
	"accessguard/resource"
	"accessguard/role"
	"accessguard/storage"
	"log"
	"sync"
)

type PermissionCache interface {
	GetUserPermissions(userID string) ([]string, bool)
	SetUserPermissions(userID string, permissions []string)
	InvalidateUserPermissions(userID string)
	GetResourcePermissions(resourceID string) ([]string, bool)
	SetResourcePermissions(resourceID string, permissions []string)
	InvalidateResourcePermissions(resourceID string)
	InvalidateAll()
	ListCachedUserIDs() []string
}

type InMemoryPermissionCache struct {
	userPermCache     map[string][]string
	resourcePermCache map[string][]string
	mu                sync.RWMutex
}

func NewInMemoryPermissionCache() *InMemoryPermissionCache {
	return &InMemoryPermissionCache{
		userPermCache:     make(map[string][]string),
		resourcePermCache: make(map[string][]string),
	}
}

func (c *InMemoryPermissionCache) GetUserPermissions(userID string) ([]string, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	perms, exists := c.userPermCache[userID]
	return perms, exists
}

func (c *InMemoryPermissionCache) SetUserPermissions(userID string, permissions []string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.userPermCache[userID] = append([]string(nil), permissions...)
}

func (c *InMemoryPermissionCache) InvalidateUserPermissions(userID string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	delete(c.userPermCache, userID)
}

func (c *InMemoryPermissionCache) GetResourcePermissions(resourceID string) ([]string, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	perms, exists := c.resourcePermCache[resourceID]
	return perms, exists
}

func (c *InMemoryPermissionCache) SetResourcePermissions(resourceID string, permissions []string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.resourcePermCache[resourceID] = append([]string(nil), permissions...)
}

func (c *InMemoryPermissionCache) InvalidateResourcePermissions(resourceID string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	delete(c.resourcePermCache, resourceID)
}

func (c *InMemoryPermissionCache) InvalidateAll() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.userPermCache = make(map[string][]string)
	c.resourcePermCache = make(map[string][]string)
}

func (c *InMemoryPermissionCache) ListCachedUserIDs() []string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	ids := make([]string, 0, len(c.userPermCache))
	for id := range c.userPermCache {
		ids = append(ids, id)
	}
	return ids
}

type PermissionManager struct {
	cache           PermissionCache
	roleService     *role.Service
	resourceService *resource.Service
	userStore       storage.UserStore
}

func NewPermissionManager(cache PermissionCache, roleService *role.Service, resourceService *resource.Service, userStore storage.UserStore) *PermissionManager {
	return &PermissionManager{
		cache:           cache,
		roleService:     roleService,
		resourceService: resourceService,
		userStore:       userStore,
	}
}

func (m *PermissionManager) GetUserPermissions(userID string) ([]string, error) {
	if perms, exists := m.cache.GetUserPermissions(userID); exists {
		return perms, nil
	}

	perms, err := m.roleService.GetAllUserPermissions(userID)
	if err != nil {
		return nil, err
	}

	m.cache.SetUserPermissions(userID, perms)
	return perms, nil
}

func (m *PermissionManager) GetResourcePermissions(resourceID string) ([]string, error) {
	if perms, exists := m.cache.GetResourcePermissions(resourceID); exists {
		return perms, nil
	}

	perms, err := m.resourceService.GetRequiredPermissions(resourceID)
	if err != nil {
		return nil, err
	}

	m.cache.SetResourcePermissions(resourceID, perms)
	return perms, nil
}

func (m *PermissionManager) UpdateUserPermissions(userID string) {
	perms, err := m.roleService.GetAllUserPermissions(userID)
	if err != nil {
		log.Printf("Failed to update permissions for user %s: %v", userID, err)
		return
	}
	m.cache.SetUserPermissions(userID, perms)
}

func (m *PermissionManager) InvalidateUser(userID string) {
	m.cache.InvalidateUserPermissions(userID)
}

func (m *PermissionManager) InvalidateResource(resourceID string) {
	m.cache.InvalidateResourcePermissions(resourceID)
}

func (m *PermissionManager) InvalidateAllUsers() {
	m.cache.InvalidateAll()
}

func (m *PermissionManager) UpdateRoleUsersPermissions(roleID string) {
	users := m.userStore.List()
	for _, user := range users {
		for _, userRoleID := range user.RoleIDs {
			if userRoleID == roleID {
				m.UpdateUserPermissions(user.UserID)
				break
			}
		}
	}
}

func (m *PermissionManager) UpdateResourcePermissions(resourceID string) {
	perms, err := m.resourceService.GetRequiredPermissions(resourceID)
	if err != nil {
		m.cache.InvalidateResourcePermissions(resourceID)
		return
	}
	m.cache.SetResourcePermissions(resourceID, perms)
}

func (m *PermissionManager) CheckPermission(userID, resourceID string) (bool, string, error) {
	userPerms, err := m.GetUserPermissions(userID)
	if err != nil {
		return false, err.Error(), err
	}

	resourcePerms, err := m.GetResourcePermissions(resourceID)
	if err != nil {
		if err == models.ErrResourceNotFound {
			return false, "resource_not_found", err
		}
		return false, err.Error(), err
	}

	if len(resourcePerms) == 0 {
		return false, "no_permission_configured", nil
	}

	userPermSet := make(map[string]bool)
	for _, p := range userPerms {
		userPermSet[p] = true
	}

	for _, requiredPerm := range resourcePerms {
		if !userPermSet[requiredPerm] {
			return false, "permission_missing:" + requiredPerm, nil
		}
	}

	return true, "role_permission_match", nil
}
