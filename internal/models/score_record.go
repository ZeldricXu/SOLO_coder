package models

import (
	"time"

	"gorm.io/gorm"
)

type ScoreRecord struct {
	ID          uint           `json:"-" gorm:"primaryKey"`
	ScoreID     string         `json:"score_id" gorm:"uniqueIndex;size:64"`
	PlayerID    string         `json:"player_id" gorm:"index;size:64"`
	GameID      string         `json:"game_id" gorm:"index;size:64"`
	ScoreChange int64          `json:"score_change"`
	TotalScore  int64          `json:"total_score"`
	SeasonID    string         `json:"season_id" gorm:"index;size:64"`
	ScoreType   string         `json:"score_type" gorm:"size:64"`
	RecordedAt  time.Time      `json:"recorded_at"`
	CreatedAt   time.Time      `json:"-"`
	UpdatedAt   time.Time      `json:"-"`
	DeletedAt   gorm.DeletedAt `json:"-" gorm:"index"`
}

func (ScoreRecord) TableName() string {
	return "score_records"
}
