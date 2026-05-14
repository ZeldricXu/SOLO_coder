package models

import "time"

type Resource struct {
	ResourceID          string    `json:"resource_id"`
	ResourceName        string    `json:"resource_name"`
	ResourceType        string    `json:"resource_type"`
	RequiredPermissions []string  `json:"required_permissions"`
	Owner               string    `json:"owner"`
	CreatedAt           time.Time `json:"created_at"`
}
