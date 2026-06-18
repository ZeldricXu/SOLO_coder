package model

import (
	"time"

	"github.com/google/uuid"
)

type UserStatus string

const (
	UserStatusActive    UserStatus = "active"
	UserStatusInactive  UserStatus = "inactive"
	UserStatusSuspended UserStatus = "suspended"
	UserStatusPending   UserStatus = "pending"
)

type User struct {
	BaseModel
	TenantScoped
	DepartmentID  *uuid.UUID   `gorm:"type:uuid;index" json:"department_id,omitempty"`
	Username      string       `gorm:"type:varchar(128);not null;uniqueIndex:idx_users_tenant_username,priority:2" json:"username"`
	Email         string       `gorm:"type:varchar(255);not null;index" json:"email"`
	Phone         string       `gorm:"type:varchar(32)" json:"phone"`
	PasswordHash  string       `gorm:"type:varchar(512);not null" json:"-"`
	FullName      string       `gorm:"type:varchar(255);not null" json:"full_name"`
	AvatarURL     string       `gorm:"type:varchar(512)" json:"avatar_url"`
	Status        UserStatus   `gorm:"type:varchar(32);not null;default:'pending'" json:"status"`
	IsSuperAdmin  bool         `gorm:"default:false" json:"is_super_admin"`
	Language      string       `gorm:"type:varchar(16);default:'zh-CN'" json:"language"`
	Timezone      string       `gorm:"type:varchar(64);default:'Asia/Shanghai'" json:"timezone"`
	LastLoginAt   *time.Time   `gorm:"" json:"last_login_at,omitempty"`
	LastLoginIP   string       `gorm:"type:varchar(50)" json:"last_login_ip,omitempty"`

	Department *Department  `gorm:"foreignKey:DepartmentID" json:"department,omitempty"`
	Groups     []*UserGroup `gorm:"many2many:user_group_members" json:"groups,omitempty"`
}

func (User) TableName() string {
	return "users"
}

type UserGroup struct {
	BaseModel
	TenantScoped
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	Code        string `gorm:"type:varchar(100)" json:"code"`
	Description string `gorm:"type:text" json:"description"`
	Type        string `gorm:"type:varchar(32);not null;default:'custom'" json:"type"`
	ParentID    *uuid.UUID `gorm:"type:uuid;index" json:"parent_id,omitempty"`
	CreatedBy   uuid.UUID  `gorm:"type:uuid" json:"created_by"`

	Users []*User `gorm:"many2many:user_group_members" json:"users,omitempty"`
}

func (UserGroup) TableName() string {
	return "user_groups"
}

type UserGroupMember struct {
	ID         uuid.UUID `gorm:"type:uuid;primaryKey;default:gen_random_uuid()" json:"id"`
	GroupID    uuid.UUID `gorm:"type:uuid;index:idx_ugm_group,priority:1;not null" json:"group_id"`
	UserID     uuid.UUID `gorm:"type:uuid;index:idx_ugm_user,priority:1;not null" json:"user_id"`
	TenantID   uuid.UUID `gorm:"type:uuid;index;not null" json:"tenant_id"`
	JoinedAt   time.Time `gorm:"not null;default:now()" json:"joined_at"`
	AddedBy    uuid.UUID `gorm:"type:uuid" json:"added_by"`
}

func (UserGroupMember) TableName() string {
	return "user_group_members"
}

type Department struct {
	BaseModel
	TenantScoped
	ParentID    *uuid.UUID `gorm:"type:uuid;index" json:"parent_id,omitempty"`
	Name        string     `gorm:"type:varchar(255);not null" json:"name"`
	Code        string     `gorm:"type:varchar(100)" json:"code"`
	Description string     `gorm:"type:text" json:"description"`
	ManagerID   *uuid.UUID `gorm:"type:uuid;index" json:"manager_id,omitempty"`
	SortOrder   int        `gorm:"type:int;default:0" json:"sort_order"`
	CreatedBy   uuid.UUID  `gorm:"type:uuid" json:"created_by"`

	Parent   *Department  `gorm:"foreignKey:ParentID" json:"parent,omitempty"`
	Children []Department `gorm:"foreignKey:ParentID" json:"children,omitempty"`
	Manager  *User        `gorm:"foreignKey:ManagerID" json:"manager,omitempty"`
}

func (Department) TableName() string {
	return "departments"
}
