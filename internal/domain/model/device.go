package model

import (
	"time"
)

type Device struct {
	ID           string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name         string                 `json:"name" gorm:"type:varchar(128)"`
	Type         string                 `json:"type" gorm:"type:varchar(64);index"`
	Model        string                 `json:"model" gorm:"type:varchar(64)"`
	SerialNumber string                 `json:"serial_number" gorm:"type:varchar(128);uniqueIndex"`
	Status       string                 `json:"status" gorm:"type:varchar(32);index"`
	IPAddress    string                 `json:"ip_address" gorm:"type:varchar(64)"`
	Location     string                 `json:"location" gorm:"type:varchar(256)"`
	FirmwareVersion string             `json:"firmware_version" gorm:"type:varchar(64)"`
	HardwareVersion string             `json:"hardware_version" gorm:"type:varchar(64)"`
	Protocol     string                 `json:"protocol" gorm:"type:varchar(32)"`
	AuthToken    string                 `json:"auth_token" gorm:"type:varchar(256)"`
	SecretKey    string                 `json:"secret_key" gorm:"type:varchar(256)"`
	Tags         map[string]string      `json:"tags" gorm:"type:jsonb"`
	Metadata     map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	LastSeenAt   *time.Time             `json:"last_seen_at"`
	ActivatedAt  *time.Time             `json:"activated_at"`
	RegisteredAt time.Time              `json:"registered_at"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
}

type DeviceSession struct {
	ID          string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID    string    `json:"device_id" gorm:"type:varchar(64);index"`
	SessionID   string    `json:"session_id" gorm:"type:varchar(128);index"`
	Status      string    `json:"status" gorm:"type:varchar(32);index"`
	ConnectedAt time.Time `json:"connected_at"`
	DisconnectedAt *time.Time `json:"disconnected_at"`
	RemoteAddr  string    `json:"remote_addr" gorm:"type:varchar(64)"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type DeviceEvent struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	DeviceID   string                 `json:"device_id" gorm:"type:varchar(64);index"`
	EventType  string                 `json:"event_type" gorm:"type:varchar(64);index"`
	Severity   string                 `json:"severity" gorm:"type:varchar(16)"`
	Message    string                 `json:"message" gorm:"type:text"`
	Payload    map[string]interface{} `json:"payload" gorm:"type:jsonb"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt  time.Time              `json:"created_at"`
}

const (
	DeviceStatusInactive   = "inactive"
	DeviceStatusActive     = "active"
	DeviceStatusOffline    = "offline"
	DeviceStatusUpdating   = "updating"
	DeviceStatusError      = "error"
	DeviceStatusDeprecated = "deprecated"
)

const (
	DeviceEventTypeOnline       = "online"
	DeviceEventTypeOffline      = "offline"
	DeviceEventTypeRegistered   = "registered"
	DeviceEventTypeActivated    = "activated"
	DeviceEventTypeError        = "error"
	DeviceEventTypeConfigChange = "config_change"
)
