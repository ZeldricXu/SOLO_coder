package models

import (
	"time"

	"gorm.io/gorm"
)

type PlayerScore struct {
	ID          uint           `json:"-" gorm:"primaryKey"`
	PlayerID    string         `json:"player_id" gorm:"uniqueIndex:idx_game_season_player;size:64"`
	GameID      string         `json:"game_id" gorm:"uniqueIndex:idx_game_season_player;size:64"`
	SeasonID    string         `json:"season_id" gorm:"uniqueIndex:idx_game_season_player;size:64"`
	TotalScore  int64          `json:"total_score" gorm:"index:idx_game_season_score,priority:3"`
	UpdatedAt   time.Time      `json:"updated_at"`
	CreatedAt   time.Time      `json:"-"`
	DeletedAt   gorm.DeletedAt `json:"-" gorm:"index"`
}

func (PlayerScore) TableName() string {
	return "player_scores"
}
