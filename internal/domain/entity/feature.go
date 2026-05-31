package entity

import (
	"time"
)

type Feature struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Type        string                 `gorm:"type:varchar(64);not null" json:"type"`
	ValueType   string                 `gorm:"type:varchar(64);not null" json:"value_type"`
	Dimensions  int                    `json:"dimensions"`
	Entity      string                 `gorm:"type:varchar(128);not null" json:"entity"`
	Source      string                 `gorm:"type:varchar(512)" json:"source"`
	Owner       string                 `gorm:"type:varchar(128)" json:"owner"`
	Tags        []string               `gorm:"type:jsonb" json:"tags,omitempty"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Status      string                 `gorm:"type:varchar(64);not null" json:"status"`
	Version     int                    `gorm:"not null" json:"version"`
	CreatedAt   time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"not null" json:"updated_at"`
}

type FeatureValue struct {
	ID         string      `gorm:"primaryKey;type:varchar(64)" json:"id"`
	FeatureID  string      `gorm:"type:varchar(64);not null;index:idx_feature_entity,priority:1" json:"feature_id"`
	EntityKey  string      `gorm:"type:varchar(256);not null;index:idx_feature_entity,priority:2" json:"entity_key"`
	Value      interface{} `gorm:"type:jsonb;not null" json:"value"`
	Version    int         `gorm:"not null" json:"version"`
	Timestamp  time.Time   `gorm:"not null;index" json:"timestamp"`
	TTL        *time.Time  `json:"ttl,omitempty"`
	CreatedAt  time.Time   `gorm:"not null" json:"created_at"`
}

type FeatureSet struct {
	ID          string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Entity      string                 `gorm:"type:varchar(128);not null" json:"entity"`
	FeatureIDs  []string               `gorm:"type:jsonb" json:"feature_ids"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Status      string                 `gorm:"type:varchar(64);not null" json:"status"`
	CreatedBy   string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt   time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt   time.Time              `gorm:"not null" json:"updated_at"`
}

type FeatureView struct {
	ID            string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name          string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Description   string                 `gorm:"type:text" json:"description"`
	FeatureSetID  string                 `gorm:"type:varchar(64);not null" json:"feature_set_id"`
	QueryTemplate string                 `gorm:"type:text" json:"query_template"`
	Materialized  bool                   `gorm:"default:false" json:"materialized"`
	RefreshRate   string                 `json:"refresh_rate"`
	TTL           string                 `json:"ttl"`
	Config        map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Status        string                 `gorm:"type:varchar(64);not null" json:"status"`
	CreatedBy     string                 `gorm:"type:varchar(128)" json:"created_by"`
	CreatedAt     time.Time              `gorm:"not null" json:"created_at"`
	UpdatedAt     time.Time              `gorm:"not null" json:"updated_at"`
}

type OnlineStore struct {
	ID       string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name     string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Type     string                 `gorm:"type:varchar(64);not null" json:"type"`
	Endpoint string                 `gorm:"type:varchar(512);not null" json:"endpoint"`
	Config   map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Enabled  bool                   `gorm:"default:true" json:"enabled"`
}

type OfflineStore struct {
	ID       string                 `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name     string                 `gorm:"type:varchar(256);not null;uniqueIndex" json:"name"`
	Type     string                 `gorm:"type:varchar(64);not null" json:"type"`
	Location string                 `gorm:"type:varchar(512);not null" json:"location"`
	Config   map[string]interface{} `gorm:"type:jsonb" json:"config,omitempty"`
	Enabled  bool                   `gorm:"default:true" json:"enabled"`
}

type FeatureType string

const (
	FeatureTypeScalar   FeatureType = "scalar"
	FeatureTypeVector   FeatureType = "vector"
	FeatureTypeEmbedding FeatureType = "embedding"
	FeatureTypeSet      FeatureType = "set"
	FeatureTypeMap      FeatureType = "map"
	FeatureTypeSequence FeatureType = "sequence"
)

type ValueType string

const (
	ValueTypeInt     ValueType = "int"
	ValueTypeFloat   ValueType = "float"
	ValueTypeString  ValueType = "string"
	ValueTypeBool    ValueType = "bool"
	ValueTypeBytes   ValueType = "bytes"
	ValueTypeJSON    ValueType = "json"
)

type FeatureStatus string

const (
	FeatureStatusDraft      FeatureStatus = "draft"
	FeatureStatusActive     FeatureStatus = "active"
	FeatureStatusDeprecated FeatureStatus = "deprecated"
	FeatureStatusArchived   FeatureStatus = "archived"
)
