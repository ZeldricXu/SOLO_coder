package models

import "time"

type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
	MFACode  string `json:"mfa_code"`
}

type LoginResponse struct {
	SessionID string    `json:"session_id"`
	ExpiresAt time.Time `json:"expires_at"`
	UserID    string    `json:"user_id"`
}

type PermissionCheckRequest struct {
	SessionID  string `json:"session_id"`
	ResourceID string `json:"resource_id"`
	Action     string `json:"action"`
}

type PermissionCheckResponse struct {
	Allowed bool   `json:"allowed"`
	Reason  string `json:"reason"`
}

type AuditQueryRequest struct {
	UserID    string    `json:"user_id"`
	StartTime time.Time `json:"start_time"`
	EndTime   time.Time `json:"end_time"`
	Limit     int       `json:"limit"`
	Offset    int       `json:"offset"`
}

type AuditQueryResponse struct {
	AuditRecords []*AuditRecord `json:"audit_records"`
	Total        int            `json:"total"`
}

type CreateUserRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
	Email    string `json:"email"`
}

type UpdateUserRequest struct {
	Email    *string     `json:"email"`
	Status   *UserStatus `json:"status"`
	Password *string     `json:"password"`
}

type CreateRoleRequest struct {
	RoleName    string   `json:"role_name"`
	Permissions []string `json:"permissions"`
	ParentRole  *string  `json:"parent_role"`
}

type UpdateRoleRequest struct {
	RoleName    *string   `json:"role_name"`
	Permissions *[]string `json:"permissions"`
	ParentRole  **string  `json:"parent_role"`
}

type AssignRoleRequest struct {
	UserID string `json:"user_id"`
	RoleID string `json:"role_id"`
}

type CreateResourceRequest struct {
	ResourceName        string   `json:"resource_name"`
	ResourceType        string   `json:"resource_type"`
	RequiredPermissions []string `json:"required_permissions"`
	Owner               string   `json:"owner"`
}

type UpdateResourceRequest struct {
	ResourceName        *string   `json:"resource_name"`
	ResourceType        *string   `json:"resource_type"`
	RequiredPermissions *[]string `json:"required_permissions"`
	Owner               *string   `json:"owner"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}
