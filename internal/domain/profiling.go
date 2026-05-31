package domain

import (
	"time"
)

type ProfileType string

const (
	ProfileTypeCPU    ProfileType = "cpu"
	ProfileTypeMemory ProfileType = "memory"
	ProfileTypeGoroutine ProfileType = "goroutine"
)

type ProfileSample struct {
	SampleID   string                 `json:"sample_id" gorm:"primaryKey;type:varchar(64)"`
	ProfileType ProfileType           `json:"profile_type" gorm:"type:varchar(32);index"`
	Data       []byte                 `json:"data" gorm:"type:bytea"`
	Metadata   map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	Duration   int64                  `json:"duration_ns"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (ProfileSample) TableName() string {
	return "profile_samples"
}

type FlameGraph struct {
	GraphID    string                 `json:"graph_id" gorm:"primaryKey;type:varchar(64)"`
	ProfileID  string                 `json:"profile_id" gorm:"type:varchar(64);index"`
	Title      string                 `json:"title"`
	SVGData    []byte                 `json:"svg_data" gorm:"type:bytea"`
	Metadata   map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (FlameGraph) TableName() string {
	return "flame_graphs"
}
