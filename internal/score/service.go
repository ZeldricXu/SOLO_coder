package score

import (
	"GameLeaderboard/internal/models"
	"GameLeaderboard/internal/ranking"
	"GameLeaderboard/internal/storage"
	"errors"
	"fmt"
	"time"
)

type ScoreReportRequest struct {
	PlayerID    string `json:"player_id" binding:"required"`
	GameID      string `json:"game_id" binding:"required"`
	ScoreChange int64  `json:"score_change" binding:"required"`
	ScoreType   string `json:"score_type" binding:"required"`
}

type ScoreReportResponse struct {
	TotalScore  int64 `json:"total_score"`
	CurrentRank int64 `json:"current_rank"`
}

type ScoreService struct {
	mysqlStore  *storage.MySQLStore
	redisStore  *storage.RedisStore
	rankService *ranking.RankingService
}

func NewScoreService(mysqlStore *storage.MySQLStore, redisStore *storage.RedisStore, rankService *ranking.RankingService) *ScoreService {
	return &ScoreService{
		mysqlStore:  mysqlStore,
		redisStore:  redisStore,
		rankService: rankService,
	}
}

func (s *ScoreService) ReportScore(req *ScoreReportRequest) (*ScoreReportResponse, error) {
	if req.PlayerID == "" {
		return nil, errors.New("player_id is required")
	}
	if req.GameID == "" {
		return nil, errors.New("game_id is required")
	}
	if req.ScoreChange <= 0 {
		return nil, errors.New("score_change must be a positive number")
	}
	if req.ScoreType == "" {
		return nil, errors.New("score_type is required")
	}

	season, err := s.mysqlStore.GetActiveSeason(req.GameID)
	if err != nil {
		return nil, fmt.Errorf("failed to get active season: %w", err)
	}
	if season == nil {
		return nil, errors.New("no active season found for this game")
	}

	if time.Now().After(season.EndTime) {
		return nil, errors.New("current season has ended")
	}

	playerScore, err := s.mysqlStore.GetPlayerScore(req.PlayerID, req.GameID, season.SeasonID)
	if err != nil {
		return nil, fmt.Errorf("failed to get player score: %w", err)
	}

	var oldScore int64
	var oldRank int64

	if playerScore != nil {
		oldScore = playerScore.TotalScore
		oldRank, err = s.redisStore.GetPlayerRank(req.GameID, season.SeasonID, req.PlayerID)
		if err != nil {
			return nil, fmt.Errorf("failed to get player rank: %w", err)
		}
	}

	newScore := oldScore + req.ScoreChange

	if playerScore == nil {
		playerScore = &models.PlayerScore{
			PlayerID:   req.PlayerID,
			GameID:     req.GameID,
			SeasonID:   season.SeasonID,
			TotalScore: newScore,
			UpdatedAt:  time.Now(),
		}
		err = s.mysqlStore.CreatePlayerScore(playerScore)
		if err != nil {
			return nil, fmt.Errorf("failed to create player score: %w", err)
		}
	} else {
		playerScore.TotalScore = newScore
		playerScore.UpdatedAt = time.Now()
		err = s.mysqlStore.UpdatePlayerScore(playerScore)
		if err != nil {
			return nil, fmt.Errorf("failed to update player score: %w", err)
		}
	}

	scoreID := generateScoreID()
	scoreRecord := &models.ScoreRecord{
		ScoreID:     scoreID,
		PlayerID:    req.PlayerID,
		GameID:      req.GameID,
		ScoreChange: req.ScoreChange,
		TotalScore:  newScore,
		SeasonID:    season.SeasonID,
		ScoreType:   req.ScoreType,
		RecordedAt:  time.Now(),
	}
	err = s.mysqlStore.CreateScoreRecord(scoreRecord)
	if err != nil {
		return nil, fmt.Errorf("failed to create score record: %w", err)
	}

	err = s.redisStore.UpdatePlayerScore(req.GameID, season.SeasonID, req.PlayerID, newScore)
	if err != nil {
		return nil, fmt.Errorf("failed to update redis score: %w", err)
	}

	newRank, err := s.redisStore.GetPlayerRank(req.GameID, season.SeasonID, req.PlayerID)
	if err != nil {
		return nil, fmt.Errorf("failed to get new player rank: %w", err)
	}

	if oldRank != newRank {
		rankEvent := &RankChangeEvent{
			PlayerID:  req.PlayerID,
			GameID:    req.GameID,
			SeasonID:  season.SeasonID,
			OldRank:   oldRank,
			NewRank:   newRank,
			OldScore:  oldScore,
			NewScore:  newScore,
			ChangedAt: time.Now(),
		}
		s.rankService.HandleRankChange(rankEvent)
	}

	return &ScoreReportResponse{
		TotalScore:  newScore,
		CurrentRank: newRank,
	}, nil
}

func (s *ScoreService) GetPlayerScore(playerID, gameID, seasonID string) (*models.PlayerScore, error) {
	return s.mysqlStore.GetPlayerScore(playerID, gameID, seasonID)
}

func generateScoreID() string {
	timestamp := time.Now().UnixNano()
	return fmt.Sprintf("score_%d", timestamp)
}
