package model

import (
	"time"
)

type DeviceShadow struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);uniqueIndex"`
	Version     int64                  `json:"version" gorm:"default:1"`
	Desired     map[string]interface{} `json:"desired" gorm:"type:jsonb"`
	Reported    map[string]interface{} `json:"reported" gorm:"type:jsonb"`
	Delta       map[string]interface{} `json:"delta" gorm:"type:jsonb"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	Timestamp   time.Time              `json:"timestamp"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ShadowOperation struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Operation   string                 `json:"operation" gorm:"type:varchar(32);index"`
	State       string                 `json:"state" gorm:"type:varchar(16)"`
	Payload     map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	ClientToken string                 `json:"client_token" gorm:"type:varchar(128)"`
	Status      string                 `json:"status" gorm:"type:varchar(32);index"`
	Version     int64                  `json:"version"`
	ErrorCode   *string                `json:"error_code"`
	ErrorMessage *string               `json:"error_message"`
	Timestamp   time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
}

type ShadowHistory struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Version     int64                  `json:"version" gorm:"index"`
	Desired     map[string]interface{} `json:"desired" gorm:"type:jsonb"`
	Reported    map[string]interface{} `json:"reported" gorm:"type:jsonb"`
	ChangeType  string                 `json:"change_type" gorm:"type:varchar(32)"`
	ChangedBy   string                 `json:"changed_by" gorm:"type:varchar(64)"`
	Timestamp   time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
}

const (
	StateDesired  = "desired"
	StateReported = "reported"
)

const (
	ShadowOpStatusPending   = "pending"
	ShadowOpStatusAccepted  = "accepted"
	ShadowOpStatusRejected  = "rejected"
	ShadowOpStatusDelivered = "delivered"
	ShadowOpStatusTimeout   = "timeout"
)

const (
	OpUpdate  = "update"
	OpGet     = "get"
	OpDelete  = "delete"
)
