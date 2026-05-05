package model

import "time"

type GameEvent struct {
	EventID    string            `json:"event_id" validate:"required"`
	PlayerID   string            `json:"player_id" validate:"required"`
	GameID     string            `json:"game_id" validate:"required"`
	ServerID   string            `json:"server_id" validate:"required"`
	EventType  string            `json:"event_type" validate:"required"`
	EventTime  string            `json:"event_time" validate:"required"`
	EventData  map[string]any    `json:"event_data"`
}

type PlayerProfile struct {
	PlayerID       string   `json:"player_id"`
	ProfileTags    []string `json:"profile_tags"`
	Level          int      `json:"level"`
	VIPLevel       int      `json:"vip_level"`
	TotalPlayTime  int      `json:"total_play_time"`
	PayAmount      float64  `json:"pay_amount"`
	LastActive     string   `json:"last_active"`
	ChurnRisk      string   `json:"churn_risk"`
}

type EventReportRequest struct {
	Events []GameEvent `json:"events" validate:"required,min=1"`
}

type EventReportResponse struct {
	Code int               `json:"code"`
	Data EventReportData   `json:"data"`
}

type EventReportData struct {
	ReceivedCount int `json:"received_count"`
}

type HeartbeatPayload struct {
	PlayerID  string `json:"player_id" validate:"required"`
	GameID    string `json:"game_id" validate:"required"`
	ServerID  string `json:"server_id" validate:"required"`
	Timestamp string `json:"timestamp" validate:"required"`
}

type OnlineStats struct {
	StatID             string         `json:"stat_id"`
	GameID             string         `json:"game_id"`
	OnlineCount        int            `json:"online_count"`
	ServerDistribution map[string]int `json:"server_distribution"`
	SampleTime         time.Time      `json:"sample_time"`
	PeakToday          int            `json:"peak_today"`
}

type TrendPoint struct {
	Time  time.Time `json:"time"`
	Count int       `json:"count"`
}

type TrendResponse struct {
	GameID string       `json:"game_id"`
	Trend  []TrendPoint `json:"trend"`
}

type APIResponse struct {
	Code    int         `json:"code"`
	Data    any         `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
}

func NewSuccessResponse(data any) APIResponse {
	return APIResponse{
		Code: 200,
		Data: data,
	}
}

func NewErrorResponse(code int, message string) APIResponse {
	return APIResponse{
		Code:    code,
		Message: message,
	}
}

type EventConfig struct {
	ID             int64          `json:"id" db:"id"`
	GameID         string         `json:"game_id" db:"game_id" validate:"required"`
	EventType      string         `json:"event_type" db:"event_type" validate:"required"`
	EventName      string         `json:"event_name" db:"event_name"`
	Description    string         `json:"description" db:"description"`
	RequiredFields map[string]string `json:"required_fields" db:"required_fields"`
	OptionalFields map[string]string `json:"optional_fields" db:"optional_fields"`
	IsActive       bool           `json:"is_active" db:"is_active"`
	CreatedAt      time.Time      `json:"created_at" db:"created_at"`
	UpdatedAt      time.Time      `json:"updated_at" db:"updated_at"`
}

type SDKConfig struct {
	Version       string                 `json:"version"`
	GameID        string                 `json:"game_id"`
	ConfigHash    string                 `json:"config_hash"`
	LastUpdated   time.Time              `json:"last_updated"`
	EventConfigs  []EventConfigItem      `json:"event_configs"`
	SDKSettings   SDKSettings            `json:"sdk_settings"`
}

type EventConfigItem struct {
	EventType      string            `json:"event_type"`
	EventName      string            `json:"event_name"`
	Description    string            `json:"description,omitempty"`
	Enabled        bool              `json:"enabled"`
	RequiredFields map[string]string `json:"required_fields,omitempty"`
	OptionalFields map[string]string `json:"optional_fields,omitempty"`
}

type SDKSettings struct {
	BatchSize         int  `json:"batch_size"`
	FlushIntervalMs   int  `json:"flush_interval_ms"`
	MaxRetries        int  `json:"max_retries"`
	HeartbeatIntervalMs int `json:"heartbeat_interval_ms"`
	EnableHeartbeat   bool `json:"enable_heartbeat"`
	EnableLocalCache  bool `json:"enable_local_cache"`
}

type SDKConfigRequest struct {
	GameID    string `form:"game_id" validate:"required"`
	SDKVersion string `form:"sdk_version,omitempty"`
}

type EventConfigCreateRequest struct {
	GameID         string            `json:"game_id" validate:"required"`
	EventType      string            `json:"event_type" validate:"required"`
	EventName      string            `json:"event_name" validate:"required"`
	Description    string            `json:"description"`
	RequiredFields map[string]string `json:"required_fields"`
	OptionalFields map[string]string `json:"optional_fields"`
	IsActive       bool              `json:"is_active"`
}

type EventConfigUpdateRequest struct {
	EventName      string            `json:"event_name"`
	Description    string            `json:"description"`
	RequiredFields map[string]string `json:"required_fields"`
	OptionalFields map[string]string `json:"optional_fields"`
	IsActive       *bool             `json:"is_active"`
}

type SDKConfigResponse struct {
	Code int       `json:"code"`
	Data SDKConfig `json:"data"`
}
