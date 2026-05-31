package domain

import (
	"time"
)

type EventSubscription struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	ChainID     int64                  `json:"chain_id" gorm:"index"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Contract    string                 `json:"contract" gorm:"index"`
	EventName   string                 `json:"event_name" gorm:"index"`
	EventSig    string                 `json:"event_sig"`
	Topic0      string                 `json:"topic0"`
	Topics      []string               `json:"topics,omitempty" gorm:"type:jsonb"`
	FromBlock   uint64                 `json:"from_block"`
	ToBlock     *uint64                `json:"to_block,omitempty"`
	CallbackURL string                 `json:"callback_url"`
	CallbackType string                `json:"callback_type"`
	Filter      map[string]interface{} `json:"filter,omitempty" gorm:"type:jsonb"`
	Status      string                 `json:"status" gorm:"index"`
	Abi         string                 `json:"abi,omitempty"`
	CreatedBy   string                 `json:"created_by"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	LastTriggered *time.Time           `json:"last_triggered_at,omitempty"`
	TriggerCount  uint64               `json:"trigger_count"`
}

type EventLogEntry struct {
	ID              string                 `json:"id" gorm:"primaryKey"`
	SubscriptionID  string                 `json:"subscription_id" gorm:"index"`
	ChainID         int64                 `json:"chain_id" gorm:"index"`
	BlockNumber     uint64                `json:"block_number" gorm:"index"`
	BlockHash       string                `json:"block_hash"`
	TxHash          string                `json:"tx_hash" gorm:"index"`
	TxIndex         uint                  `json:"tx_index"`
	LogIndex        uint                  `json:"log_index"`
	Contract        string                `json:"contract" gorm:"index"`
	EventName       string                `json:"event_name"`
	Topics          []string              `json:"topics" gorm:"type:jsonb"`
	Data            string                `json:"data"`
	DecodedParams   map[string]interface{} `json:"decoded_params,omitempty" gorm:"type:jsonb"`
	Timestamp       time.Time             `json:"timestamp" gorm:"index"`
	CallbackStatus  string                `json:"callback_status" gorm:"index"`
	CallbackError   string                `json:"callback_error,omitempty"`
	CallbackTime    *time.Time            `json:"callback_time,omitempty"`
	CreatedAt       time.Time             `json:"created_at"`
}

type CreateSubscriptionRequest struct {
	ChainID     int64                  `json:"chain_id" binding:"required"`
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Contract    string                 `json:"contract" binding:"required"`
	EventName   string                 `json:"event_name" binding:"required"`
	EventSig    string                 `json:"event_sig"`
	Topics      []string               `json:"topics"`
	FromBlock   uint64                 `json:"from_block"`
	ToBlock     *uint64                `json:"to_block"`
	CallbackURL string                 `json:"callback_url" binding:"required"`
	CallbackType string                `json:"callback_type"`
	Filter      map[string]interface{} `json:"filter"`
	Abi         string                 `json:"abi"`
}

type EventCallbackResponse struct {
	SubscriptionID string                 `json:"subscription_id"`
	Event          string                 `json:"event"`
	Params         map[string]interface{} `json:"params"`
	TxHash         string                 `json:"tx_hash"`
	BlockNumber    uint64                 `json:"block_number"`
	Timestamp      time.Time              `json:"timestamp"`
}

const (
	SubscriptionStatusActive   = "active"
	SubscriptionStatusPaused   = "paused"
	SubscriptionStatusStopped  = "stopped"
	SubscriptionStatusError    = "error"

	CallbackStatusPending  = "pending"
	CallbackStatusSuccess  = "success"
	CallbackStatusFailed   = "failed"
	CallbackStatusRetrying = "retrying"
)
