package domain

import "time"

type BackupStatus string

const (
	BackupStatusPending  BackupStatus = "pending"
	BackupStatusRunning BackupStatus = "running"
	BackupStatusSuccess BackupStatus = "success"
	BackupStatusFailed  BackupStatus = "failed"
)

type BackupRecord struct {
	BackupID    string       `json:"backup_id" gorm:"primaryKey;type:varchar(64)"`
	BackupType  string       `json:"backup_type" gorm:"type:varchar(32);index"`
	Source      string       `json:"source"`
	Target      string       `json:"target"`
	SizeBytes   int64        `json:"size_bytes"`
	Status      BackupStatus `json:"status" gorm:"type:varchar(32);index"`
	ErrorMsg    *string      `json:"error_msg,omitempty" gorm:"type:text"`
	Checksum    string       `json:"checksum"`
	StartedAt   time.Time    `json:"started_at"`
	CompletedAt *time.Time   `json:"completed_at,omitempty"`
	CreatedAt   time.Time    `json:"created_at"`
}

func (BackupRecord) TableName() string { return "backup_records" }
