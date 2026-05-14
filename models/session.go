package models

import "time"

type SessionStatus string

const (
	SessionStatusActive   SessionStatus = "active"
	SessionStatusExpired  SessionStatus = "expired"
	SessionStatusRevoked  SessionStatus = "revoked"
)

type Session struct {
	SessionID string        `json:"session_id"`
	UserID    string        `json:"user_id"`
	CreatedAt time.Time     `json:"created_at"`
	ExpiresAt time.Time     `json:"expires_at"`
	IPAddress string        `json:"ip_address"`
	Status    SessionStatus `json:"status"`
}
