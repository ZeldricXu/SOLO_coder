package domain

import (
	"time"
)

type APIKey struct {
	KeyID      string    `json:"key_id" gorm:"primaryKey;type:varchar(64)"`
	AppID      string    `json:"app_id" gorm:"type:varchar(64);index"`
	KeyHash    string    `json:"key_hash" gorm:"type:varchar(256)"`
	Scopes     []string  `json:"scopes" gorm:"type:text[]"`
	RateLimit  int32     `json:"rate_limit"`
	Enabled    bool      `json:"enabled" gorm:"index"`
	ExpiresAt  *time.Time `json:"expires_at,omitempty"`
	CreatedAt  time.Time `json:"created_at"`
	UpdatedAt  time.Time `json:"updated_at"`
}

func (APIKey) TableName() string {
	return "api_keys"
}

type RateLimitBucket struct {
	ID         uint64    `json:"id" gorm:"primaryKey"`
	BucketKey  string    `json:"bucket_key" gorm:"type:varchar(128);uniqueIndex"`
	Tokens     int32     `json:"tokens"`
	LastRefill time.Time `json:"last_refill"`
	UpdatedAt  time.Time `json:"updated_at"`
}

func (RateLimitBucket) TableName() string {
	return "rate_limit_buckets"
}
