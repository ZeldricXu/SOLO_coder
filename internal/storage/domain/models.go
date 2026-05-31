package domain

import (
	"time"
)

type StoredContent struct {
	ID            string                 `json:"id" gorm:"primaryKey"`
	ContentID     string                 `json:"content_id" gorm:"index"`
	StorageType   string                 `json:"storage_type" gorm:"index"`
	Network       string                 `json:"network"`
	ContentHash   string                 `json:"content_hash"`
	Size          int64                  `json:"size"`
	MimeType      string                 `json:"mime_type"`
	OriginalName  string                 `json:"original_name"`
	PinStatus     string                 `json:"pin_status" gorm:"index"`
	PinTimestamp  *time.Time             `json:"pin_timestamp,omitempty"`
	ExpiresAt     *time.Time             `json:"expires_at,omitempty"`
	Replication   int                    `json:"replication"`
	URLs          []string               `json:"urls" gorm:"type:jsonb"`
	Encrypted     bool                   `json:"encrypted"`
	EncryptionKey string                 `json:"encryption_key,omitempty"`
	CreatedBy     string                 `json:"created_by"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
	Metadata      map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type PinOperation struct {
	ID            string                 `json:"id" gorm:"primaryKey"`
	ContentID     string                 `json:"content_id" gorm:"index"`
	StorageType   string                 `json:"storage_type"`
	Operation     string                 `json:"operation"`
	Status        string                 `json:"status" gorm:"index"`
	RequestID     string                 `json:"request_id"`
	Error         string                 `json:"error,omitempty"`
	StartedAt     time.Time              `json:"started_at"`
	CompletedAt   *time.Time             `json:"completed_at,omitempty"`
	Metadata      map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type StoreRequest struct {
	Content       []byte                 `json:"content"`
	FileName      string                 `json:"file_name"`
	StorageType   string                 `json:"storage_type" binding:"required"`
	Pin           bool                   `json:"pin"`
	Encrypt       bool                   `json:"encrypt"`
	EncryptionKey string                 `json:"encryption_key"`
	ExpiresAt     *time.Time             `json:"expires_at"`
	Metadata      map[string]interface{} `json:"metadata"`
}

type RetrieveRequest struct {
	ContentID   string `json:"content_id" binding:"required"`
	StorageType string `json:"storage_type" binding:"required"`
}

type StoreResponse struct {
	ContentID   string   `json:"content_id"`
	StorageType string   `json:"storage_type"`
	ContentHash string   `json:"content_hash"`
	Size        int64    `json:"size"`
	URLs        []string `json:"urls"`
	PinStatus   string   `json:"pin_status"`
}

type PinRequest struct {
	ContentID   string `json:"content_id" binding:"required"`
	StorageType string `json:"storage_type" binding:"required"`
	Name        string `json:"name"`
}

const (
	StorageTypeIPFS     = "ipfs"
	StorageTypeArweave  = "arweave"
	StorageTypeFilecoin = "filecoin"

	PinStatusPinned   = "pinned"
	PinStatusPinning  = "pinning"
	PinStatusFailed   = "failed"
	PinStatusUnpinned = "unpinned"
)
