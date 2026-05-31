import os
BASE = "."

def w(path, content):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, 'w') as f:
        f.write(content)
    print(f"Created: {path}")

# Storage domain
w("internal/domain/storage.go", '''package domain

import "time"

type BackupStatus string

const (
\tBackupStatusPending  BackupStatus = "pending"
\tBackupStatusRunning BackupStatus = "running"
\tBackupStatusSuccess BackupStatus = "success"
\tBackupStatusFailed  BackupStatus = "failed"
)

type BackupRecord struct {
\tBackupID    string       `json:"backup_id" gorm:"primaryKey;type:varchar(64)"`
\tBackupType  string       `json:"backup_type" gorm:"type:varchar(32);index"`
\tSource      string       `json:"source"`
\tTarget      string       `json:"target"`
\tSizeBytes   int64        `json:"size_bytes"`
\tStatus      BackupStatus `json:"status" gorm:"type:varchar(32);index"`
\tErrorMsg    *string      `json:"error_msg,omitempty" gorm:"type:text"`
\tChecksum    string       `json:"checksum"`
\tStartedAt   time.Time    `json:"started_at"`
\tCompletedAt *time.Time   `json:"completed_at,omitempty"`
\tCreatedAt   time.Time    `json:"created_at"`
}

func (BackupRecord) TableName() string { return "backup_records" }

type RestoreRecord struct {
\tRestoreID   string       `json:"restore_id" gorm:"primaryKey;type:varchar(64)"`
\tBackupID    string       `json:"backup_id" gorm:"type:varchar(64);index"`
\tSource      string       `json:"source"`
\tTarget      string       `json:"target"`
\tStatus      BackupStatus `json:"status" gorm:"type:varchar(32);index"`
\tErrorMsg    *string      `json:"error_msg,omitempty" gorm:"type:text"`
\tStartedAt   time.Time    `json:"started_at"`
\tCompletedAt *time.Time   `json:"completed_at,omitempty"`
\tCreatedAt   time.Time    `json:"created_at"`
}

func (RestoreRecord) TableName() string { return "restore_records" }
''')
