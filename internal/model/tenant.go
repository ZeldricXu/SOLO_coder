package model

import (
	"database/sql/driver"
	"encoding/json"
	"fmt"
	"time"
)

type ProseMirrorDoc struct {
	Type    string                   `json:"type"`
	Content []map[string]interface{} `json:"content,omitempty"`
	Attrs   map[string]interface{}   `json:"attrs,omitempty"`
}

func (p ProseMirrorDoc) Value() (driver.Value, error) {
	return json.Marshal(p)
}

func (p *ProseMirrorDoc) Scan(value interface{}) error {
	bytes, ok := value.([]byte)
	if !ok {
		return fmt.Errorf("failed to unmarshal ProseMirrorDoc value: %v", value)
	}
	return json.Unmarshal(bytes, p)
}

type Tenant struct {
	BaseModel
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	Domain      string `gorm:"type:varchar(255);uniqueIndex:idx_tenants_domain" json:"domain"`
	Namespace   string `gorm:"type:varchar(100);uniqueIndex:idx_tenants_namespace" json:"namespace"`
	Description string `gorm:"type:text" json:"description"`
	LogoURL     string `gorm:"type:varchar(512)" json:"logo_url"`
	Status      string `gorm:"type:varchar(32);not null;default:'active'" json:"status"`
	Settings    JSONB  `gorm:"type:jsonb" json:"settings"`
}

func (Tenant) TableName() string {
	return "tenants"
}

type Space struct {
	BaseModel
	TenantScoped
	Name          string `gorm:"type:varchar(255);not null" json:"name"`
	Namespace     string `gorm:"type:varchar(100);not null" json:"namespace"`
	Description   string `gorm:"type:text" json:"description"`
	Icon          string `gorm:"type:varchar(64)" json:"icon"`
	Color         string `gorm:"type:varchar(16)" json:"color"`
	Visibility    string `gorm:"type:varchar(32);not null;default:'private'" json:"visibility"`
	DefaultRoleID string `gorm:"type:uuid" json:"default_role_id"`
	OwnerID       string `gorm:"type:uuid;not null;index" json:"owner_id"`
	AvatarURL     string `gorm:"type:varchar(500)" json:"avatar_url"`
	Status        string `gorm:"type:varchar(32);default:'active'" json:"status"`
}

func (Space) TableName() string {
	return "spaces"
}

type User struct {
	BaseModel
	TenantScoped
	Username            string     `gorm:"type:varchar(128);not null;uniqueIndex:idx_users_tenant_username" json:"username"`
	Email               string     `gorm:"type:varchar(255);not null;index" json:"email"`
	Phone               string     `gorm:"type:varchar(32)" json:"phone"`
	PasswordHash        string     `gorm:"type:varchar(512);not null" json:"-"`
	FullName            string     `gorm:"type:varchar(255);not null" json:"full_name"`
	AvatarURL           string     `gorm:"type:varchar(512)" json:"avatar_url"`
	Status              string     `gorm:"type:varchar(32);not null;default:'active'" json:"status"`
	Language            string     `gorm:"type:varchar(16);default:'zh-CN'" json:"language"`
	Timezone            string     `gorm:"type:varchar(64);default:'Asia/Shanghai'" json:"timezone"`
	IsSuperAdmin        bool       `gorm:"default:false" json:"is_super_admin"`
	LastLoginAt         *time.Time `gorm:"" json:"last_login_at"`
	LastLoginIP         string     `gorm:"type:varchar(50)" json:"last_login_ip"`
	PasswordResetToken  string     `gorm:"type:varchar(255)" json:"-"`
	PasswordResetExpires *time.Time `json:"-"`
	DepartmentID        string     `gorm:"type:uuid;index" json:"department_id"`
}

func (User) TableName() string {
	return "users"
}

type UserGroup struct {
	BaseModel
	TenantScoped
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	Description string `gorm:"type:text" json:"description"`
	Type        string `gorm:"type:varchar(32);not null;default:'custom'" json:"type"`
	ParentID    string `gorm:"type:uuid;index" json:"parent_id"`
}

func (UserGroup) TableName() string {
	return "user_groups"
}

type UserGroupMember struct {
	BaseModel
	TenantScoped
	GroupID string `gorm:"type:uuid;not null;index" json:"group_id"`
	UserID  string `gorm:"type:uuid;not null;index" json:"user_id"`
}

func (UserGroupMember) TableName() string {
	return "user_group_members"
}

type Department struct {
	BaseModel
	TenantScoped
	Name        string `gorm:"type:varchar(255);not null" json:"name"`
	Description string `gorm:"type:text" json:"description"`
	ParentID    string `gorm:"type:uuid;index" json:"parent_id"`
	ManagerID   string `gorm:"type:uuid;index" json:"manager_id"`
	SortOrder   int    `gorm:"type:int;default:0" json:"sort_order"`
}

func (Department) TableName() string {
	return "departments"
}

type JSONB map[string]interface{}

func (j JSONB) Value() (driver.Value, error) {
	return json.Marshal(j)
}

func (j *JSONB) Scan(value interface{}) error {
	bytes, ok := value.([]byte)
	if !ok {
		return fmt.Errorf("failed to unmarshal JSONB value: %v", value)
	}
	return json.Unmarshal(bytes, j)
}

type DocumentQuery struct {
	Keyword     string   `json:"keyword"`
	Category    string   `json:"category"`
	Tags        []string `json:"tags"`
	Status      string   `json:"status"`
	CreatedBy   string   `json:"created_by"`
	DirectoryID string   `json:"directory_id"`
	IsPublic    *bool    `json:"is_public"`
	SortBy      string   `json:"sort_by"`
	SortOrder   string   `json:"sort_order"`
}
