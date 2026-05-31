package model

import (
	"time"
)

type SoftwareCatalog struct {
	ID            string                 `gorm:"primaryKey;column:id" json:"id"`
	Name          string                 `gorm:"column:name;uniqueIndex" json:"name"`
	Type          string                 `gorm:"column:type;index" json:"type"`
	Description   string                 `gorm:"column:description" json:"description"`
	Version       string                 `gorm:"column:version" json:"version"`
	Owner         string                 `gorm:"column:owner;index" json:"owner"`
	Repository    string                 `gorm:"column:repository" json:"repository"`
	Documentation string                 `gorm:"column:documentation" json:"documentation"`
	Tags          []string               `gorm:"column:tags;type:jsonb;serializer:json" json:"tags"`
	Metadata      map[string]interface{} `gorm:"column:metadata;type:jsonb" json:"metadata"`
	Status        string                 `gorm:"column:status;index" json:"status"`
	CreatedAt     time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt     time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (SoftwareCatalog) TableName() string {
	return "software_catalog"
}

type ServiceDependency struct {
	ID           string    `gorm:"primaryKey;column:id" json:"id"`
	ServiceID    string    `gorm:"column:service_id;index" json:"service_id"`
	DependOnID   string    `gorm:"column:depend_on_id;index" json:"depend_on_id"`
	DependencyType string  `gorm:"column:dependency_type" json:"dependency_type"`
	Version      string    `gorm:"column:version" json:"version"`
	IsOptional   bool      `gorm:"column:is_optional" json:"is_optional"`
	Description  string    `gorm:"column:description" json:"description"`
	CreatedAt    time.Time `gorm:"column:created_at" json:"created_at"`
	UpdatedAt    time.Time `gorm:"column:updated_at" json:"updated_at"`
}

func (ServiceDependency) TableName() string {
	return "service_dependencies"
}

type RegisterServiceRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Type          string                 `json:"type" binding:"required"`
	Description   string                 `json:"description"`
	Version       string                 `json:"version"`
	Owner         string                 `json:"owner" binding:"required"`
	Repository    string                 `json:"repository"`
	Documentation string                 `json:"documentation"`
	Tags          []string               `json:"tags"`
	Metadata      map[string]interface{} `json:"metadata"`
}

type SearchCatalogRequest struct {
	Query      string   `form:"q"`
	Type       string   `form:"type"`
	Tags       []string `form:"tags"`
	Owner      string   `form:"owner"`
	Page       int      `form:"page,default=1"`
	PageSize   int      `form:"page_size,default=20"`
}

type ServiceDetail struct {
	SoftwareCatalog
	Dependencies   []ServiceDependency `json:"dependencies"`
	DependedBy     []ServiceDependency `json:"depended_by"`
	TotalDependents int               `json:"total_dependents"`
}

type DependencyGraph struct {
	ServiceID   string                 `json:"service_id"`
	ServiceName string                 `json:"service_name"`
	Nodes       []DependencyNode       `json:"nodes"`
	Edges       []DependencyEdge       `json:"edges"`
}

type DependencyNode struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Type  string `json:"type"`
	Level int    `json:"level"`
}

type DependencyEdge struct {
	From  string `json:"from"`
	To    string `json:"to"`
	Type  string `json:"type"`
}
