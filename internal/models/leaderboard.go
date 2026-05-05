package models

import (
	"encoding/json"
	"time"

	"gorm.io/gorm"
)

type LeaderboardType string

const (
	LeaderboardTypeTotal    LeaderboardType = "total_score"
	LeaderboardTypeWeekly   LeaderboardType = "weekly"
	LeaderboardTypeMonthly  LeaderboardType = "monthly"
	LeaderboardTypeCustom   LeaderboardType = "custom"
)

type Leaderboard struct {
	ID            uint           `json:"-" gorm:"primaryKey"`
	LeaderboardID string        `json:"leaderboard_id" gorm:"uniqueIndex;size:128"`
	GameID        string         `json:"game_id" gorm:"index;size:64"`
	SeasonID      string         `json:"season_id" gorm:"index;size:64"`
	Type          LeaderboardType `json:"type" gorm:"size:32"`
	Entries       string         `json:"-" gorm:"type:longtext"`
	TotalPlayers  int64          `json:"total_players"`
	UpdatedAt     time.Time      `json:"updated_at"`
	CreatedAt     time.Time      `json:"-"`
	DeletedAt     gorm.DeletedAt `json:"-" gorm:"index"`
}

type LeaderboardEntry struct {
	Rank      int64     `json:"rank"`
	PlayerID  string    `json:"player_id"`
	Score     int64     `json:"score"`
	UpdatedAt time.Time `json:"updated_at"`
}

func (Leaderboard) TableName() string {
	return "leaderboards"
}

func (l *Leaderboard) GetEntries() ([]*LeaderboardEntry, error) {
	if l.Entries == "" {
		return []*LeaderboardEntry{}, nil
	}
	var entries []*LeaderboardEntry
	err := json.Unmarshal([]byte(l.Entries), &entries)
	if err != nil {
		return nil, err
	}
	return entries, nil
}

func (l *Leaderboard) SetEntries(entries []*LeaderboardEntry) error {
	if entries == nil {
		l.Entries = ""
		return nil
	}
	data, err := json.Marshal(entries)
	if err != nil {
		return err
	}
	l.Entries = string(data)
	return nil
}
