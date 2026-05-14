package models

import "time"

type ChannelType string

const (
	ChannelTypeSMS   ChannelType = "sms"
	ChannelTypeEmail ChannelType = "email"
	ChannelTypeApp   ChannelType = "app"
)

type ChannelStatus string

const (
	ChannelStatusActive   ChannelStatus = "active"
	ChannelStatusInactive ChannelStatus = "inactive"
	ChannelStatusError    ChannelStatus = "error"
)

type ChannelConfig struct {
	ChannelID     string                 `json:"channel_id"`
	ChannelType   ChannelType            `json:"channel_type"`
	ChannelName   string                 `json:"channel_name"`
	ChannelConfig map[string]interface{} `json:"channel_config"`
	Status        ChannelStatus          `json:"status"`
	Priority      int                    `json:"priority"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}
