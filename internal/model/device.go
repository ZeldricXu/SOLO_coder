package model

import "time"

type DeviceStatus string

const (
	DeviceStatusPending   DeviceStatus = "pending"
	DeviceStatusActive    DeviceStatus = "active"
	DeviceStatusOnline    DeviceStatus = "online"
	DeviceStatusOffline   DeviceStatus = "offline"
	DeviceStatusSuspended DeviceStatus = "suspended"
	DeviceStatusDeleted   DeviceStatus = "deleted"
)

type Device struct {
	DeviceID    string            `json:"device_id" gorm:"primaryKey;type:varchar(64)"`
	Name        string            `json:"name" gorm:"type:varchar(128)"`
	Type        string            `json:"type" gorm:"type:varchar(64);index"`
	Status      DeviceStatus      `json:"status" gorm:"type:varchar(32);index"`
	FirmwareVersion string        `json:"firmware_version" gorm:"type:varchar(32)"`
	HardwareVersion string        `json:"hardware_version" gorm:"type:varchar(32)"`
	AuthToken   string            `json:"auth_token,omitempty" gorm:"type:varchar(128);index"`
	PublicKey   string            `json:"public_key,omitempty" gorm:"type:text"`
	IPAddress   string            `json:"ip_address" gorm:"type:varchar(64)"`
	Location    string            `json:"location" gorm:"type:varchar(128)"`
	Tags        map[string]string `json:"tags" gorm:"type:jsonb"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	LastHeartbeat *time.Time      `json:"last_heartbeat" gorm:"index"`
	ActivatedAt *time.Time        `json:"activated_at"`
	RegisteredAt time.Time        `json:"registered_at" gorm:"index"`
	LastSeenAt  *time.Time        `json:"last_seen_at" gorm:"index"`
	CreatedAt   time.Time         `json:"created_at" gorm:"index"`
	UpdatedAt   time.Time         `json:"updated_at" gorm:"index"`
}

func (d *Device) TableName() string {
	return "devices"
}

type DeviceRegisterRequest struct {
	Name            string                 `json:"name" binding:"required"`
	Type            string                 `json:"type" binding:"required"`
	FirmwareVersion string                 `json:"firmware_version"`
	HardwareVersion string                 `json:"hardware_version"`
	PublicKey       string                 `json:"public_key"`
	Metadata        map[string]interface{} `json:"metadata"`
}

type DeviceActivateRequest struct {
	DeviceID        string                 `json:"device_id" binding:"required"`
	ActivationCode  string                 `json:"activation_code" binding:"required"`
	FirmwareVersion string                 `json:"firmware_version"`
	IPAddress       string                 `json:"ip_address"`
	Location        string                 `json:"location"`
	Metadata        map[string]interface{} `json:"metadata"`
}

type DeviceAuthRequest struct {
	DeviceID   string `json:"device_id" binding:"required"`
	Signature  string `json:"signature" binding:"required"`
	Timestamp  int64  `json:"timestamp" binding:"required"`
}

type DeviceHeartbeatRequest struct {
	Status          DeviceStatus           `json:"status"`
	FirmwareVersion string                 `json:"firmware_version"`
	Metrics         map[string]float64     `json:"metrics"`
	IPAddress       string                 `json:"ip_address"`
}
