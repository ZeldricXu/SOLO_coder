package protocol_adapter

import (
	"time"

	"edgescheduler/internal/common/models"
)

type ProtocolType string
type DriverStatus string
type ConnectionStatus string

const (
	ProtocolModbus ProtocolType = "modbus"
	ProtocolOPCUA  ProtocolType = "opcua"
	ProtocolMQTT   ProtocolType = "mqtt"
	ProtocolHTTP   ProtocolType = "http"
	ProtocolTCP    ProtocolType = "tcp"
	ProtocolUDP    ProtocolType = "udp"

	DriverStatusLoaded   DriverStatus = "loaded"
	DriverStatusActive   DriverStatus = "active"
	DriverStatusInactive DriverStatus = "inactive"
	DriverStatusError    DriverStatus = "error"

	ConnectionStatusConnected    ConnectionStatus = "connected"
	ConnectionStatusDisconnected ConnectionStatus = "disconnected"
	ConnectionStatusConnecting   ConnectionStatus = "connecting"
)

type ProtocolDriver struct {
	models.BaseModel
	DriverID   string       `gorm:"type:varchar(50);not null;uniqueIndex" json:"driver_id"`
	Name       string       `gorm:"type:varchar(100);not null" json:"name"`
	Protocol   ProtocolType `gorm:"type:varchar(30);not null;index" json:"protocol"`
	Version    string       `gorm:"type:varchar(50);not null" json:"version"`
	Status     DriverStatus `gorm:"type:varchar(20);index" json:"status"`
	Config     map[string]interface{} `gorm:"type:jsonb" json:"config"`
	Enabled    bool         `gorm:"default:true" json:"enabled"`
	LoadedAt   *time.Time   `json:"loaded_at,omitempty"`
	ErrorMsg   string       `gorm:"type:text" json:"error_msg,omitempty"`
}

type DeviceProtocolConfig struct {
	models.BaseModel
	ConfigID      string            `gorm:"type:varchar(50);not null;uniqueIndex" json:"config_id"`
	DeviceID      string            `gorm:"type:varchar(50);not null;index" json:"device_id"`
	DriverID      string            `gorm:"type:varchar(50);not null;index" json:"driver_id"`
	Protocol      ProtocolType      `gorm:"type:varchar(30);not null;index" json:"protocol"`
	Endpoint      string            `gorm:"type:varchar(500);not null" json:"endpoint"`
	Parameters    map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	PollInterval  int               `gorm:"default:5000" json:"poll_interval_ms"`
	ConnectionStatus ConnectionStatus `gorm:"type:varchar(20);index" json:"connection_status"`
	LastConnected *time.Time        `json:"last_connected,omitempty"`
	LastDisconnected *time.Time     `json:"last_disconnected,omitempty"`
	Enabled       bool              `gorm:"default:true" json:"enabled"`
}

type ProtocolData struct {
	ID          string                 `json:"id"`
	DeviceID    string                 `json:"device_id"`
	Protocol    ProtocolType           `json:"protocol"`
	Timestamp   time.Time              `json:"timestamp"`
	RawData     interface{}            `json:"raw_data"`
	NormalizedData map[string]interface{} `json:"normalized_data"`
}

type ForwardRule struct {
	models.BaseModel
	RuleID      string            `gorm:"type:varchar(50);not null;uniqueIndex" json:"rule_id"`
	Name        string            `gorm:"type:varchar(100);not null" json:"name"`
	SourceProtocol ProtocolType  `gorm:"type:varchar(30);not null" json:"source_protocol"`
	TargetProtocol ProtocolType  `gorm:"type:varchar(30);not null" json:"target_protocol"`
	SourceFilter map[string]interface{} `gorm:"type:jsonb" json:"source_filter"`
	TargetEndpoint string          `gorm:"type:varchar(500)" json:"target_endpoint"`
	Transform   map[string]interface{} `gorm:"type:jsonb" json:"transform"`
	Enabled     bool              `gorm:"default:true" json:"enabled"`
}

type DriverLoadRequest struct {
	Name     string                 `json:"name" binding:"required"`
	Protocol ProtocolType           `json:"protocol" binding:"required"`
	Version  string                 `json:"version" binding:"required"`
	Config   map[string]interface{} `json:"config"`
}

type DeviceConfigRequest struct {
	DeviceID     string                 `json:"device_id" binding:"required"`
	DriverID     string                 `json:"driver_id" binding:"required"`
	Endpoint     string                 `json:"endpoint" binding:"required"`
	Parameters   map[string]interface{} `json:"parameters"`
	PollInterval int                    `json:"poll_interval_ms"`
}
