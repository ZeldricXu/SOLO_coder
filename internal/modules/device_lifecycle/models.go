package device_lifecycle

import (
	"time"

	"edgescheduler/internal/common/models"
)

type DeviceStatus string

const (
	DeviceStatusRegistered DeviceStatus = "registered"
	DeviceStatusActivated  DeviceStatus = "activated"
	DeviceStatusOnline     DeviceStatus = "online"
	DeviceStatusOffline    DeviceStatus = "offline"
	DeviceStatusDeactivated DeviceStatus = "deactivated"
)

type Device struct {
	models.BaseModel
	DeviceID        string                 `gorm:"type:varchar(50);not null;uniqueIndex" json:"device_id"`
	Name            string                 `gorm:"type:varchar(100);not null" json:"name"`
	Type            string                 `gorm:"type:varchar(50);not null;index" json:"type"`
	Status          DeviceStatus           `gorm:"type:varchar(20);not null;index" json:"status"`
	Model           string                 `gorm:"type:varchar(100)" json:"model"`
	Manufacturer    string                 `gorm:"type:varchar(100)" json:"manufacturer"`
	FirmwareVersion string                 `gorm:"type:varchar(50)" json:"firmware_version"`
	IPAddress       string                 `gorm:"type:varchar(45)" json:"ip_address"`
	Location        string                 `gorm:"type:varchar(200)" json:"location"`
	AuthToken       string                 `gorm:"type:varchar(255)" json:"auth_token,omitempty"`
	LastHeartbeatAt *time.Time             `json:"last_heartbeat_at,omitempty"`
	ActivatedAt     *time.Time             `json:"activated_at,omitempty"`
	DeactivatedAt   *time.Time             `json:"deactivated_at,omitempty"`
	Metadata        map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	Labels          map[string]string      `gorm:"type:jsonb" json:"labels"`
}

type DeviceRegistrationRequest struct {
	DeviceID     string                 `json:"device_id" binding:"required"`
	Name         string                 `json:"name" binding:"required"`
	Type         string                 `json:"type" binding:"required"`
	Model        string                 `json:"model"`
	Manufacturer string                 `json:"manufacturer"`
	IPAddress    string                 `json:"ip_address"`
	Location     string                 `json:"location"`
	Metadata     map[string]interface{} `json:"metadata"`
	Labels       map[string]string      `json:"labels"`
}

type DeviceActivationRequest struct {
	DeviceID string `json:"device_id" binding:"required"`
	Secret   string `json:"secret" binding:"required"`
}

type DeviceHeartbeatRequest struct {
	DeviceID        string                 `json:"device_id" binding:"required"`
	FirmwareVersion string                 `json:"firmware_version"`
	Status          string                 `json:"status"`
	Metrics         map[string]interface{} `json:"metrics"`
}

type DeviceStatusResponse struct {
	DeviceID        string                 `json:"device_id"`
	Status          DeviceStatus           `json:"status"`
	LastHeartbeatAt *time.Time             `json:"last_heartbeat_at,omitempty"`
	FirmwareVersion string                 `json:"firmware_version"`
	Metrics         map[string]interface{} `json:"metrics,omitempty"`
}
