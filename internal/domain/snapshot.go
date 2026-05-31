package domain

import (
	"time"
)

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]float64     `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (Snapshot) TableName() {

}

func (Snapshot) TableName() string {
	return "snapshots"
}
