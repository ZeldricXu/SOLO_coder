package permission

import (
	"accessguard/audit"
	"accessguard/auth"
	"accessguard/cache"
	"accessguard/models"
	"accessguard/resource"
	"accessguard/role"
)

type Service struct {
	authService        *auth.Service
	roleService        *role.Service
	resourceService    *resource.Service
	auditService       audit.Service
	permissionManager  *cache.PermissionManager
}

func NewService(authService *auth.Service, roleService *role.Service, resourceService *resource.Service, auditService audit.Service, permissionManager *cache.PermissionManager) *Service {
	return &Service{
		authService:        authService,
		roleService:        roleService,
		resourceService:    resourceService,
		auditService:       auditService,
		permissionManager:  permissionManager,
	}
}

func (s *Service) CheckPermission(req *models.PermissionCheckRequest, ipAddress string) (*models.PermissionCheckResponse, error) {
	if req.SessionID == "" || req.ResourceID == "" {
		return nil, models.ErrInvalidRequest
	}

	session, err := s.authService.ValidateSession(req.SessionID)
	if err != nil {
		result := &models.PermissionCheckResponse{
			Allowed: false,
			Reason:  err.Error(),
		}
		if session != nil {
			s.auditService.RecordAccess(session.UserID, req.ResourceID, req.Action, ipAddress, req.SessionID, models.AccessDenied, err.Error())
		}
		return result, err
	}

	allowed, reason, checkErr := s.permissionManager.CheckPermission(session.UserID, req.ResourceID)

	var accessResult models.AccessResult
	if allowed {
		accessResult = models.AccessAllowed
	} else {
		accessResult = models.AccessDenied
	}

	s.auditService.RecordAccess(session.UserID, req.ResourceID, req.Action, ipAddress, req.SessionID, accessResult, reason)

	if checkErr != nil {
		return &models.PermissionCheckResponse{
			Allowed: allowed,
			Reason:  reason,
		}, checkErr
	}

	return &models.PermissionCheckResponse{
		Allowed: allowed,
		Reason:  reason,
	}, nil
}

func (s *Service) CheckUserHasPermission(userID string, permissions []string) (bool, string) {
	userPerms, err := s.roleService.GetAllUserPermissions(userID)
	if err != nil {
		return false, err.Error()
	}

	userPermSet := make(map[string]bool)
	for _, p := range userPerms {
		userPermSet[p] = true
	}

	for _, requiredPerm := range permissions {
		if !userPermSet[requiredPerm] {
			return false, "permission_missing:" + requiredPerm
		}
	}

	return true, "permission_granted"
}

func (s *Service) GetUserPermissions(sessionID string) ([]string, error) {
	session, err := s.authService.ValidateSession(sessionID)
	if err != nil {
		return nil, err
	}
	return s.permissionManager.GetUserPermissions(session.UserID)
}
