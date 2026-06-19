package model

import (
	"time"

	"gorm.io/gorm"
)

type ResourceType string

const (
	ResourceTypeSpace     ResourceType = "space"
	ResourceTypeDirectory ResourceType = "directory"
	ResourceTypeDocument  ResourceType = "document"
	ResourceTypeAPI       ResourceType = "api"
)

type Role string

const (
	RoleAdmin    Role = "admin"
	RoleEditor   Role = "editor"
	RoleReviewer Role = "reviewer"
	RoleViewer   Role = "viewer"
)

type SubjectType string

const (
	SubjectTypeUser       SubjectType = "user"
	SubjectTypeGroup      SubjectType = "group"
	SubjectTypeDepartment SubjectType = "department"
)

type PermissionAction string

const (
	ActionView    PermissionAction = "view"
	ActionEdit    PermissionAction = "edit"
	ActionDelete  PermissionAction = "delete"
	ActionAdmin   PermissionAction = "admin"
	ActionCreate  PermissionAction = "create"
	ActionReview  PermissionAction = "review"
)

type Permission struct {
	BaseModel
	TenantScoped
	ResourceType ResourceType   `gorm:"size:20;not null;index:idx_perm_resource,priority:1" json:"resource_type"`
	ResourceID   string         `gorm:"type:uuid;not null;index:idx_perm_resource,priority:2" json:"resource_id"`
	Role         Role           `gorm:"size:20;not null" json:"role"`
	SubjectType  SubjectType    `gorm:"size:20;not null;index:idx_perm_subject,priority:1" json:"subject_type"`
	SubjectID    string         `gorm:"type:uuid;not null;index:idx_perm_subject,priority:2" json:"subject_id"`
	GrantedBy    string         `gorm:"type:uuid" json:"granted_by"`

	Tenant Tenant `gorm:"foreignKey:TenantID" json:"tenant,omitempty"`
}

func (Permission) TableName() string {
	return "permissions"
}

func RoleWeight(role Role) int {
	switch role {
	case RoleAdmin:
		return 4
	case RoleEditor:
		return 3
	case RoleReviewer:
		return 2
	case RoleViewer:
		return 1
	default:
		return 0
	}
}

func HasSufficientRole(required, actual Role) bool {
	return RoleWeight(actual) >= RoleWeight(required)
}

func ActionToRequiredRole(action PermissionAction) Role {
	switch action {
	case ActionAdmin:
		return RoleAdmin
	case ActionEdit, ActionCreate, ActionDelete:
		return RoleEditor
	case ActionReview:
		return RoleReviewer
	case ActionView:
		return RoleViewer
	default:
		return RoleViewer
	}
}

var _ = gorm.DeletedAt{}
var _ = time.Now
