package role

import (
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"log"
	"time"
)

type CacheInvalidator interface {
	InvalidateUser(userID string)
	InvalidateAllUsers()
	UpdateUserPermissions(userID string)
	UpdateRoleUsersPermissions(roleID string)
}

type PermissionValidator interface {
	IsValidPermission(name string) bool
	ValidatePermissions(names []string) ([]string, []string)
}

type Service struct {
	roleStore           storage.RoleStore
	userStore           storage.UserStore
	cacheInvalidator    CacheInvalidator
	permissionValidator PermissionValidator
}

func NewService(roleStore storage.RoleStore, userStore storage.UserStore) *Service {
	return &Service{
		roleStore: roleStore,
		userStore: userStore,
	}
}

func (s *Service) SetPermissionValidator(validator PermissionValidator) {
	s.permissionValidator = validator
}

func (s *Service) validatePermissions(permissions []string) []string {
	if s.permissionValidator == nil {
		return s.deduplicatePermissions(permissions)
	}

	valid, invalid := s.permissionValidator.ValidatePermissions(permissions)
	if len(invalid) > 0 {
		log.Printf("Warning: Ignoring invalid permissions: %v", invalid)
	}
	return valid
}

func (s *Service) SetCacheInvalidator(invalidator CacheInvalidator) {
	s.cacheInvalidator = invalidator
}

func (s *Service) updateRoleUsers(roleID string) {
	if s.cacheInvalidator != nil {
		s.cacheInvalidator.UpdateRoleUsersPermissions(roleID)
	}
}

func (s *Service) updateUser(userID string) {
	if s.cacheInvalidator != nil {
		s.cacheInvalidator.UpdateUserPermissions(userID)
	}
}

func (s *Service) CreateRole(req *models.CreateRoleRequest) (*models.Role, error) {
	if req.RoleName == "" {
		return nil, models.ErrInvalidRequest
	}

	if req.ParentRole != nil && *req.ParentRole != "" {
		_, err := s.roleStore.GetByID(*req.ParentRole)
		if err != nil {
			return nil, err
		}
	}

	validPermissions := s.validatePermissions(req.Permissions)

	role := &models.Role{
		RoleID:      utils.GenerateRoleID(),
		RoleName:    req.RoleName,
		Permissions: validPermissions,
		ParentRole:  req.ParentRole,
		CreatedAt:   time.Now(),
	}

	err := s.roleStore.Create(role)
	if err != nil {
		return nil, err
	}

	return role, nil
}

func (s *Service) GetRoleByID(roleID string) (*models.Role, error) {
	return s.roleStore.GetByID(roleID)
}

func (s *Service) UpdateRole(roleID string, req *models.UpdateRoleRequest) (*models.Role, error) {
	role, err := s.roleStore.GetByID(roleID)
	if err != nil {
		return nil, err
	}

	permissionsChanged := false

	if req.RoleName != nil {
		role.RoleName = *req.RoleName
	}

	if req.Permissions != nil {
		newPerms := s.validatePermissions(*req.Permissions)
		if !s.permissionsEqual(role.Permissions, newPerms) {
			permissionsChanged = true
			role.Permissions = newPerms
		}
	}

	if req.ParentRole != nil {
		parentRole := *req.ParentRole
		if parentRole != nil && *parentRole != "" {
			_, err := s.roleStore.GetByID(*parentRole)
			if err != nil {
				return nil, err
			}
			if *parentRole == roleID {
				return nil, models.ErrInvalidRequest
			}
		}
		role.ParentRole = parentRole
	}

	err = s.roleStore.Update(role)
	if err != nil {
		return nil, err
	}

	if permissionsChanged {
		s.updateRoleUsers(roleID)
	}

	return role, nil
}

func (s *Service) DeleteRole(roleID string) error {
	s.updateRoleUsers(roleID)
	return s.roleStore.Delete(roleID)
}

func (s *Service) ListRoles() []*models.Role {
	return s.roleStore.List()
}

func (s *Service) AssignRoleToUser(userID, roleID string) error {
	_, err := s.roleStore.GetByID(roleID)
	if err != nil {
		return err
	}

	err = s.userStore.AssignRole(userID, roleID)
	if err == nil {
		s.updateUser(userID)
	}
	return err
}

func (s *Service) RemoveRoleFromUser(userID, roleID string) error {
	err := s.userStore.RemoveRole(userID, roleID)
	if err == nil {
		s.updateUser(userID)
	}
	return err
}

func (s *Service) GetUserRoles(userID string) ([]*models.Role, error) {
	user, err := s.userStore.GetByID(userID)
	if err != nil {
		return nil, err
	}

	return s.roleStore.GetByIDs(user.RoleIDs), nil
}

func (s *Service) GetAllUserPermissions(userID string) ([]string, error) {
	roles, err := s.GetUserRoles(userID)
	if err != nil {
		return nil, err
	}

	permissionsMap := make(map[string]bool)
	for _, role := range roles {
		for _, perm := range role.Permissions {
			permissionsMap[perm] = true
		}
	}

	permissions := make([]string, 0, len(permissionsMap))
	for perm := range permissionsMap {
		permissions = append(permissions, perm)
	}

	return permissions, nil
}

func (s *Service) AddPermissionToRole(roleID, permission string) error {
	role, err := s.roleStore.GetByID(roleID)
	if err != nil {
		return err
	}

	for _, p := range role.Permissions {
		if p == permission {
			return nil
		}
	}

	role.Permissions = append(role.Permissions, permission)
	err = s.roleStore.Update(role)
	if err == nil {
		s.updateRoleUsers(roleID)
	}
	return err
}

func (s *Service) RemovePermissionFromRole(roleID, permission string) error {
	role, err := s.roleStore.GetByID(roleID)
	if err != nil {
		return err
	}

	found := false
	newPermissions := make([]string, 0, len(role.Permissions))
	for _, p := range role.Permissions {
		if p == permission {
			found = true
		} else {
			newPermissions = append(newPermissions, p)
		}
	}

	if !found {
		return nil
	}

	role.Permissions = newPermissions
	err = s.roleStore.Update(role)
	if err == nil {
		s.updateRoleUsers(roleID)
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
