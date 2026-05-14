package models

import "time"

type SendStatus string
type DeliveryStatus string

const (
	SendStatusPending  SendStatus = "pending"
	SendStatusSuccess  SendStatus = "success"
	SendStatusFailed   SendStatus = "failed"
	SendStatusRetrying SendStatus = "retrying"
)

const (
	DeliveryStatusPending   DeliveryStatus = "pending"
	DeliveryStatusDelivered DeliveryStatus = "delivered"
	DeliveryStatusFailed    DeliveryStatus = "failed"
	DeliveryStatusUnknown   DeliveryStatus = "unknown"
)

type SendStatusRecord struct {
	StatusID       string         `json:"status_id"`
	NotifyID       string         `json:"notify_id"`
	Channel        string         `json:"channel"`
	SendStatus     SendStatus     `json:"send_status"`
	SendTime       *time.Time     `json:"send_time,omitempty"`
	DeliveryStatus DeliveryStatus `json:"delivery_status"`
	DeliveryTime   *time.Time     `json:"delivery_time,omitempty"`
	ErrorMessage   string         `json:"error_message,omitempty"`
	RetryAttempt   int            `json:"retry_attempt"`
	CreatedAt      time.Time      `json:"created_at"`
}
