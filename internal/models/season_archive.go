package models

import (
	"encoding/json"
	"time"

	"gorm.io/gorm"
)

type SeasonArchive struct {
	ID              uint           `json:"-" gorm:"primaryKey"`
	ArchiveID       string         `json:"archive_id" gorm:"uniqueIndex;size:64"`
	GameID          string         `json:"game_id" gorm:"index;size:64"`
	SeasonID        string         `json:"season_id" gorm:"uniqueIndex:idx_season_archive;size:64"`
	SeasonName      string         `json:"season_name" gorm:"size:128"`
	StartTime       time.Time      `json:"start_time"`
	EndTime         time.Time      `json:"end_time"`
	ArchivedAt      time.Time      `json:"archived_at"`
	PlayerCount     int64          `json:"player_count"`
	TotalScoreSum   int64          `json:"total_score_sum"`
	PlayerScores    string         `json:"-" gorm:"type:longtext"`
	LeaderboardData string         `json:"-" gorm:"type:longtext"`
	Checksum        string         `json:"checksum" gorm:"size:64"`
	CreatedAt       time.Time      `json:"-"`
	DeletedAt       gorm.DeletedAt `json:"-" gorm:"index"`
}

type ArchivedPlayerScore struct {
	PlayerID   string    `json:"player_id"`
	TotalScore int64     `json:"total_score"`
	Rank       int64     `json:"rank"`
	UpdatedAt  time.Time `json:"updated_at"`
}

type ArchivedLeaderboard struct {
	Type         models.LeaderboardType    `json:"type"`
	TotalPlayers int64                      `json:"total_players"`
	Entries      []*models.LeaderboardEntry `json:"entries"`
	UpdatedAt    time.Time                  `json:"updated_at"`
}

func (SeasonArchive) TableName() string {
	return "season_archives"
}

func (a *SeasonArchive) GetPlayerScores() ([]*ArchivedPlayerScore, error) {
	if a.PlayerScores == "" {
		return []*ArchivedPlayerScore{}, nil
	}
	var scores []*ArchivedPlayerScore
	err := json.Unmarshal([]byte(a.PlayerScores), &scores)
	if err != nil {
		return nil, err
	}
	return scores, nil
}

func (a *SeasonArchive) SetPlayerScores(scores []*ArchivedPlayerScore) error {
	if scores == nil {
		a.PlayerScores = ""
		return nil
	}
	data, err := json.Marshal(scores)
	if err != nil {
		return err
	}
	a.PlayerScores = string(data)
	return nil
}

func (a *SeasonArchive) GetLeaderboardData() ([]*ArchivedLeaderboard, error) {
	if a.LeaderboardData == "" {
		return []*ArchivedLeaderboard{}, nil
	}
	var lbs []*ArchivedLeaderboard
	err := json.Unmarshal([]byte(a.LeaderboardData), &lbs)
	if err != nil {
		return nil, err
	}
	return lbs, nil
}

func (a *SeasonArchive) SetLeaderboardData(lbs []*ArchivedLeaderboard) error {
	if lbs == nil {
		a.LeaderboardData = ""
		return nil
	}
	data, err := json.Marshal(lbs)
	if err != nil {
		return err
	}
	a.LeaderboardData = string(data)
	return nil
}

type ArchiveValidationResult struct {
	Valid              bool   `json:"valid"`
	ExpectedPlayerCount int64 `json:"expected_player_count"`
	ActualPlayerCount   int64 `json:"actual_player_count"`
	ExpectedTotalScore  int64 `json:"expected_total_score"`
	ActualTotalScore    int64 `json:"actual_total_score"`
	ErrorMessage        string `json:"error_message,omitempty"`
}
