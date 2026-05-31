package domain

import (
	"time"
)

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace  string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version    int32                  `json:"version" gorm:"index"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled" gorm:"index"`
	AppliedAt  time.Time              `json:"applied_at"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (Config) TableName() string {
	return "configs"
}
}
