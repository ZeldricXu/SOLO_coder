package models

import "time"

type Role struct {
	RoleID      string    `json:"role_id"`
	RoleName    string    `json:"role_name"`
	Permissions []string  `json:"permissions"`
	ParentRole  *string   `json:"parent_role"`
	CreatedAt   time.Time `json:"created_at"`
}
