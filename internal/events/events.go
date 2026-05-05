package events

import (
	"time"
)

type RankChangeEvent struct {
	PlayerID    string    `json:"player_id"`
	GameID      string    `json:"game_id"`
	SeasonID    string    `json:"season_id"`
	OldRank     int64     `json:"old_rank"`
	NewRank     int64     `json:"new_rank"`
	OldScore    int64     `json:"old_score"`
	NewScore    int64     `json:"new_score"`
	ChangedAt   time.Time `json:"changed_at"`
}

type SeasonSwitchEvent struct {
	GameID       string    `json:"game_id"`
	OldSeasonID  string    `json:"old_season_id"`
	NewSeasonID  string    `json:"new_season_id"`
	SwitchedAt   time.Time `json:"switched_at"`
}

type ScoreReportEvent struct {
	PlayerID    string    `json:"player_id"`
	GameID      string    `json:"game_id"`
	SeasonID    string    `json:"season_id"`
	ScoreChange int64     `json:"score_change"`
	NewScore    int64     `json:"new_score"`
	ScoreType   string    `json:"score_type"`
	ReportedAt  time.Time `json:"reported_at"`
}
