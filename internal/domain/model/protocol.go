package model

import (
	"time"
)

type ProtocolDriver struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name" gorm:"type:varchar(128)"`
	Protocol    string                 `json:"protocol" gorm:"type:varchar(64);index"`
	Version     string                 `json:"version" gorm:"type:varchar(64)"`
	Description string                 `json:"description" gorm:"type:text"`
	DriverType  string                 `json:"driver_type" gorm:"type:varchar(32)"`
	LibraryPath string                 `json:"library_path" gorm:"type:varchar(512)"`
	ConfigSchema map[string]interface{} `json:"config_schema" gorm:"type:jsonb"`
	DataFormat  map[string]interface{} `json:"data_format" gorm:"type:jsonb"`
	SupportedDevices []string           `json:"supported_devices" gorm:"type:text[]"`
	Parameters  map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	IsEnabled   bool                   `json:"is_enabled" gorm:"default:true"`
	LoadedAt    *time.Time             `json:"loaded_at"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ProtocolAdapter struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name" gorm:"type:varchar(128)"`
	DriverID    string                 `json:"driver_id" gorm:"type:varchar(64);index"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Protocol    string                 `json:"protocol" gorm:"type:varchar(64);index"`
	Status      string                 `json:"status" gorm:"type:varchar(32);index"`
	ConnectionConfig map[string]interface{} `json:"connection_config" gorm:"type:jsonb"`
	DataMapping map[string]string      `json:"data_mapping" gorm:"type:jsonb"`
	PollingInterval int                `json:"polling_interval"`
	Timeout     int                    `json:"timeout"`
	RetryCount  int                    `json:"retry_count" gorm:"default:3"`
	LastConnected *time.Time           `json:"last_connected"`
	LastDisconnected *time.Time        `json:"last_disconnected"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
}

type ProtocolDataRecord struct {
	ID          string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	AdapterID   string                 `json:"adapter_id" gorm:"type:varchar(64);index"`
	DeviceID    string                 `json:"device_id" gorm:"type:varchar(64);index"`
	Protocol    string                 `json:"protocol" gorm:"type:varchar(64);index"`
	RawData     string                 `json:"raw_data" gorm:"type:text"`
	NormalizedData map[string]interface{} `json:"normalized_data" gorm:"type:jsonb"`
	DataPoints  map[string]interface{} `json:"data_points" gorm:"type:jsonb"`
	Quality     int                    `json:"quality"`
	Timestamp   time.Time              `json:"timestamp" gorm:"index"`
	CreatedAt   time.Time              `json:"created_at"`
}

const (
	ProtocolStatusLoaded    = "loaded"
	ProtocolStatusLoading   = "loading"
	ProtocolStatusError     = "error"
	ProtocolStatusUnloaded  = "unloaded"
)

const (
	AdapterStatusConnected    = "connected"
	AdapterStatusDisconnected = "disconnected"
	AdapterStatusConnecting   = "connecting"
	AdapterStatusError        = "error"
)

const (
	ProtocolModbus   = "modbus"
	ProtocolOPCUA    = "opcua"
	ProtocolMQTT     = "mqtt"
	ProtocolHTTP     = "http"
	ProtocolBACnet   = "bacnet"
	ProtocolProfibus = "profibus"
)
