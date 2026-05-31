package featurestore

import (
	"time"
)

type FeatureType string

const (
	FeatureTypeInt64   FeatureType = "int64"
	FeatureTypeFloat64 FeatureType = "float64"
	FeatureTypeString  FeatureType = "string"
	FeatureTypeBool    FeatureType = "bool"
	FeatureTypeInt64List FeatureType = "int64_list"
	FeatureTypeFloat64List FeatureType = "float64_list"
	FeatureTypeStringList FeatureType = "string_list"
)

type FeatureValueType interface{}

type Feature struct {
	Name        string      `json:"name"`
	Type        FeatureType `json:"type"`
	Value       interface{} `json:"value"`
	Timestamp   time.Time   `json:"timestamp"`
}

type FeatureGroup struct {
	ID          string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name        string            `gorm:"type:varchar(128);uniqueIndex;not null" json:"name"`
	Description string            `gorm:"type:text" json:"description"`
	EntityType  string            `gorm:"type:varchar(64);not null" json:"entity_type"`
	Version     int               `json:"version"`
	Features    []FeatureSchema   `gorm:"type:jsonb;serializer:json" json:"features"`
	Labels      map[string]string `gorm:"type:jsonb;serializer:json" json:"labels"`
	Online      bool              `json:"online"`
	CreatedBy   string            `gorm:"type:varchar(64)" json:"created_by"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type FeatureSchema struct {
	Name        string      `json:"name"`
	Type        FeatureType `json:"type"`
	Description string      `json:"description"`
	Nullable    bool        `json:"nullable"`
	Default     interface{} `json:"default,omitempty"`
}

type FeatureValue struct {
	ID             string      `gorm:"primaryKey;type:varchar(64)" json:"id"`
	FeatureGroupID string      `gorm:"type:varchar(64);index;not null" json:"feature_group_id"`
	EntityID       string      `gorm:"type:varchar(128);index;not null" json:"entity_id"`
	FeatureName    string      `gorm:"type:varchar(128);index;not null" json:"feature_name"`
	Value          interface{} `gorm:"type:jsonb;serializer:json" json:"value"`
	Timestamp      time.Time   `gorm:"index" json:"timestamp"`
	EventTime      time.Time   `gorm:"index" json:"event_time"`
}

type FeatureView struct {
	ID              string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name            string            `gorm:"type:varchar(128);uniqueIndex;not null" json:"name"`
	Description     string            `gorm:"type:text" json:"description"`
	FeatureGroups   []string          `gorm:"type:jsonb;serializer:json" json:"feature_groups"`
	Features        []string          `gorm:"type:jsonb;serializer:json" json:"features"`
	Labels          map[string]string `gorm:"type:jsonb;serializer:json" json:"labels"`
	Online          bool              `json:"online"`
	TTL             time.Duration     `json:"ttl"`
	CreatedBy       string            `gorm:"type:varchar(64)" json:"created_by"`
	CreatedAt       time.Time         `json:"created_at"`
	UpdatedAt       time.Time         `json:"updated_at"`
}

type TrainingDataset struct {
	ID            string            `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Name          string            `gorm:"type:varchar(128);not null" json:"name"`
	Description   string            `gorm:"type:text" json:"description"`
	FeatureViewID string            `gorm:"type:varchar(64);index;not null" json:"feature_view_id"`
	StartTime     time.Time         `json:"start_time"`
	EndTime       time.Time         `json:"end_time"`
	EntityIDs     []string          `gorm:"type:jsonb;serializer:json" json:"entity_ids"`
	Labels        map[string]string `gorm:"type:jsonb;serializer:json" json:"labels"`
	Status        string            `gorm:"type:varchar(32)" json:"status"`
	RowCount      int64             `json:"row_count"`
	StoragePath   string            `gorm:"type:varchar(512)" json:"storage_path"`
	CreatedBy     string            `gorm:"type:varchar(64)" json:"created_by"`
	CreatedAt     time.Time         `json:"created_at"`
}

type CreateFeatureGroupRequest struct {
	Name        string            `json:"name" binding:"required"`
	Description string            `json:"description"`
	EntityType  string            `json:"entity_type" binding:"required"`
	Features    []FeatureSchema   `json:"features" binding:"required"`
	Labels      map[string]string `json:"labels"`
	Online      bool              `json:"online"`
}

type InsertFeatureValuesRequest struct {
	EntityID string                 `json:"entity_id" binding:"required"`
	Values   map[string]interface{} `json:"values" binding:"required"`
	EventTime time.Time            `json:"event_time"`
}

type GetOnlineFeaturesRequest struct {
	FeatureView string   `json:"feature_view" binding:"required"`
	EntityIDs   []string `json:"entity_ids" binding:"required"`
	Features    []string `json:"features"`
}
