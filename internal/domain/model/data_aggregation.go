package model

import (
	"time"
)

type DataStream struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Name        string                 `json:"name" gorm:"type:varchar(128)"`
	Description string                 `json:"description" gorm:"type:text"`
	MetricNames []string               `json:"metric_names" gorm:"type:text[]"`
	AggregationStrategy string        `json:"aggregation_strategy" gorm:"type:varchar(64)"`
	WindowSize  int                    `json:"window_size"`
	WindowUnit  string                 `json:"window_unit" gorm:"type:varchar(16)"`
	CompressionEnabled bool            `json:"compression_enabled" gorm:"default:true"`
	SamplingRate float64               `json:"sampling_rate" gorm:"default:1.0"`
	Thresholds  map[string]float64     `json:"thresholds" gorm:"type:jsonb"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	IsEnabled   bool                   `json:"is_enabled" gorm:"default:true"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type AggregatedData struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	StreamID    string                 `json:"stream_id" gorm:"type:varchar(64);index"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Metric      string                 `json:"metric" gorm:"type:varchar(64);index"`
	Value       float64                `json:"value"`
	Aggregation string                 `json:"aggregation" gorm:"type:varchar(32)"`
	Min         float64                `json:"min"`
	Max         float64                `json:"max"`
	Avg         float64                `json:"avg"`
	Sum         float64                `json:"sum"`
	Count       int64                  `json:"count"`
	StdDev      float64                `json:"stddev"`
	StartTime   time.Time              `json:"start_time"`
	EndTime     time.Time              `json:"end_time"`
	WindowStart time.Time              `json:"window_start" gorm:"index"`
	WindowEnd   time.Time              `json:"window_end"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt   time.Time              `json:"created_at"`
}

type RawDataPoint struct {
	ID        string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID  string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Metric    string                 `json:"metric" gorm:"type:varchar(64);index"`
	Value     float64                `json:"value"`
	Tags      map[string]string      `json:"tags" gorm:"type:jsonb"`
	Timestamp time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt time.Time              `json:"created_at"`
}

const (
	AggregationAvg   = "avg"
	AggregationSum   = "sum"
	AggregationMin   = "min"
	AggregationMax   = "max"
	AggregationCount = "count"
	AggregationStdDev = "stddev"
	AggregationAll   = "all"
)

const (
	WindowUnitSecond = "second"
	WindowUnitMinute = "minute"
	WindowUnitHour   = "hour"
	WindowUnitDay    = "day"
)
