package models

import "time"

type UserStatus string

const (
	UserStatusActive   UserStatus = "active"
	UserStatusInactive UserStatus = "inactive"
	UserStatusDisabled UserStatus = "disabled"
)

type User struct {
	UserID        string     `json:"user_id"`
	Username      string     `json:"username"`
	PasswordHash  string     `json:"password_hash"`
	Email         string     `json:"email"`
	Status        UserStatus `json:"status"`
	MFAEnabled    bool       `json:"mfa_enabled"`
	RoleIDs       []string   `json:"role_ids"`
	CreatedAt     time.Time  `json:"created_at"`
	LastLogin     *time.Time `json:"last_login"`
}
