package models

import "time"

type AccessResult string

const (
	AccessAllowed AccessResult = "allowed"
	AccessDenied  AccessResult = "denied"
)

type AuditRecord struct {
	AuditID      string       `json:"audit_id"`
	UserID       string       `json:"user_id"`
	ResourceID   string       `json:"resource_id"`
	Action       string       `json:"action"`
	AccessResult AccessResult `json:"access_result"`
	IPAddress    string       `json:"ip_address"`
	SessionID    string       `json:"session_id"`
	AccessTime   time.Time    `json:"access_time"`
	Reason       string       `json:"reason"`
}
