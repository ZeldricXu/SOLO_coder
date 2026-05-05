package storage

import (
	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/models"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type MySQLStore struct {
	db *gorm.DB
}

func NewMySQLStore(cfg *config.MySQLConfig) (*MySQLStore, error) {
	dsn := cfg.DSN()

	db, err := gorm.Open(mysql.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to mysql: %w", err)
	}

	err = db.AutoMigrate(
		&models.ScoreRecord{},
		&models.PlayerScore{},
		&models.Season{},
		&models.Leaderboard{},
		&models.SeasonArchive{},
	)
	if err != nil {
		return nil, fmt.Errorf("failed to auto migrate: %w", err)
	}

	return &MySQLStore{db: db}, nil
}

func (s *MySQLStore) GetDB() *gorm.DB {
	return s.db
}

func (s *MySQLStore) CreateScoreRecord(record *models.ScoreRecord) error {
	return s.db.Create(record).Error
}

func (s *MySQLStore) GetPlayerScore(playerID, gameID, seasonID string) (*models.PlayerScore, error) {
	var score models.PlayerScore
	err := s.db.Where("player_id = ? AND game_id = ? AND season_id = ?", playerID, gameID, seasonID).First(&score).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &score, nil
}

func (s *MySQLStore) CreatePlayerScore(score *models.PlayerScore) error {
	return s.db.Create(score).Error
}

func (s *MySQLStore) UpdatePlayerScore(score *models.PlayerScore) error {
	return s.db.Save(score).Error
}

func (s *MySQLStore) GetActiveSeason(gameID string) (*models.Season, error) {
	var season models.Season
	err := s.db.Where("game_id = ? AND status = ?", gameID, models.SeasonStatusActive).First(&season).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &season, nil
}

func (s *MySQLStore) CreateSeason(season *models.Season) error {
	return s.db.Create(season).Error
}

func (s *MySQLStore) UpdateSeason(season *models.Season) error {
	return s.db.Save(season).Error
}

func (s *MySQLStore) GetSeasonByID(seasonID string) (*models.Season, error) {
	var season models.Season
	err := s.db.Where("season_id = ?", seasonID).First(&season).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &season, nil
}

func (s *MySQLStore) GetAllSeasons(gameID string) ([]*models.Season, error) {
	var seasons []*models.Season
	err := s.db.Where("game_id = ?", gameID).Order("start_time DESC").Find(&seasons).Error
	return seasons, err
}

func (s *MySQLStore) GetLeaderboard(gameID, seasonID string, lbType models.LeaderboardType) (*models.Leaderboard, error) {
	var lb models.Leaderboard
	err := s.db.Where("game_id = ? AND season_id = ? AND type = ?", gameID, seasonID, lbType).First(&lb).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &lb, nil
}

func (s *MySQLStore) CreateLeaderboard(lb *models.Leaderboard) error {
	return s.db.Create(lb).Error
}

func (s *MySQLStore) UpdateLeaderboard(lb *models.Leaderboard) error {
	return s.db.Save(lb).Error
}

func (s *MySQLStore) GetTopPlayerScores(gameID, seasonID string, limit int) ([]*models.PlayerScore, error) {
	var scores []*models.PlayerScore
	err := s.db.Where("game_id = ? AND season_id = ?", gameID, seasonID).
		Order("total_score DESC, updated_at ASC").
		Limit(limit).
		Find(&scores).Error
	return scores, err
}

func (s *MySQLStore) CountPlayersInSeason(gameID, seasonID string) (int64, error) {
	var count int64
	err := s.db.Model(&models.PlayerScore{}).
		Where("game_id = ? AND season_id = ?", gameID, seasonID).
		Count(&count).Error
	return count, err
}

func (s *MySQLStore) GetPlayerRank(gameID, seasonID, playerID string) (int64, error) {
	var playerScore models.PlayerScore
	err := s.db.Where("game_id = ? AND season_id = ? AND player_id = ?", gameID, seasonID, playerID).First(&playerScore).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return 0, nil
		}
		return 0, err
	}

	var count int64
	err = s.db.Model(&models.PlayerScore{}).
		Where("game_id = ? AND season_id = ? AND total_score > ?", gameID, seasonID, playerScore.TotalScore).
		Count(&count).Error
	if err != nil {
		return 0, err
	}

	return count + 1, nil
}

func (s *MySQLStore) CreateSeasonArchive(archive *models.SeasonArchive) error {
	return s.db.Create(archive).Error
}

func (s *MySQLStore) GetSeasonArchive(seasonID string) (*models.SeasonArchive, error) {
	var archive models.SeasonArchive
	err := s.db.Where("season_id = ?", seasonID).First(&archive).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &archive, nil
}

func (s *MySQLStore) GetAllPlayerScores(gameID, seasonID string) ([]*models.PlayerScore, error) {
	var scores []*models.PlayerScore
	err := s.db.Where("game_id = ? AND season_id = ?", gameID, seasonID).
		Order("total_score DESC, updated_at ASC").
		Find(&scores).Error
	return scores, err
}

func (s *MySQLStore) SumPlayerScores(gameID, seasonID string) (int64, error) {
	var totalSum int64
	err := s.db.Model(&models.PlayerScore{}).
		Where("game_id = ? AND season_id = ?", gameID, seasonID).
		Select("COALESCE(SUM(total_score), 0)").
		Scan(&totalSum).Error
	return totalSum, err
}

func (s *MySQLStore) CalculateArchiveChecksum(gameID, seasonID string, playerCount, totalScoreSum int64) string {
	data := fmt.Sprintf("%s:%s:%d:%d:%d",
		gameID, seasonID, playerCount, totalScoreSum, time.Now().Unix())
	hash := sha256.Sum256([]byte(data))
	return hex.EncodeToString(hash[:])
}

func (s *MySQLStore) ValidateArchive(archive *models.SeasonArchive) (*models.ArchiveValidationResult, error) {
	result := &models.ArchiveValidationResult{
		Valid: false,
	}

	playerCount, err := s.CountPlayersInSeason(archive.GameID, archive.SeasonID)
	if err != nil {
		return nil, err
	}

	totalScoreSum, err := s.SumPlayerScores(archive.GameID, archive.SeasonID)
	if err != nil {
		return nil, err
	}

	result.ExpectedPlayerCount = archive.PlayerCount
	result.ActualPlayerCount = playerCount
	result.ExpectedTotalScore = archive.TotalScoreSum
	result.ActualTotalScore = totalScoreSum

	if playerCount != archive.PlayerCount {
		result.ErrorMessage = fmt.Sprintf("player count mismatch: expected %d, actual %d", archive.PlayerCount, playerCount)
		return result, nil
	}

	if totalScoreSum != archive.TotalScoreSum {
		result.ErrorMessage = fmt.Sprintf("total score sum mismatch: expected %d, actual %d", archive.TotalScoreSum, totalScoreSum)
		return result, nil
	}

	archivedScores, err := archive.GetPlayerScores()
	if err != nil {
		return nil, err
	}

	if int64(len(archivedScores)) != playerCount {
		result.ErrorMessage = fmt.Sprintf("archived player count mismatch: stored %d, actual %d", len(archivedScores), playerCount)
		return result, nil
	}

	result.Valid = true
	return result, nil
}

func (s *MySQLStore) DeletePlayerScoresBySeason(gameID, seasonID string) error {
	return s.db.Where("game_id = ? AND season_id = ?", gameID, seasonID).
		Delete(&models.PlayerScore{}).Error
}

func (s *MySQLStore) GetAllGames() ([]string, error) {
	var games []string
	err := s.db.Model(&models.Season{}).
		Distinct("game_id").
		Pluck("game_id", &games).Error
	return games, err
}

func (s *MySQLStore) BatchGetActiveSeasons() ([]*models.Season, error) {
	var seasons []*models.Season
	err := s.db.Where("status = ?", models.SeasonStatusActive).
		Find(&seasons).Error
	return seasons, err
}

func (s *MySQLStore) GetPendingSeason(gameID string) (*models.Season, error) {
	var season models.Season
	err := s.db.Where("game_id = ? AND status = ?", gameID, models.SeasonStatusPending).
		Order("start_time ASC").
		First(&season).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &season, nil
}

func (s *MySQLStore) GetSeasonForArchive(gameID string, currentTime time.Time) (*models.Season, error) {
	var season models.Season
	err := s.db.Where("game_id = ? AND status = ? AND end_time <= ?", 
		gameID, models.SeasonStatusActive, currentTime).
		First(&season).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return &season, nil
}

func (s *MySQLStore) SerializeArchivedPlayerScores(scores []*models.PlayerScore) ([]*models.ArchivedPlayerScore, error) {
	if len(scores) == 0 {
		return []*models.ArchivedPlayerScore{}, nil
	}

	sortedScores := make([]*models.PlayerScore, len(scores))
	copy(sortedScores, scores)

	for i := range sortedScores {
		for j := i + 1; j < len(sortedScores); j++ {
			if sortedScores[i].TotalScore < sortedScores[j].TotalScore {
				sortedScores[i], sortedScores[j] = sortedScores[j], sortedScores[i]
			} else if sortedScores[i].TotalScore == sortedScores[j].TotalScore {
				if sortedScores[i].UpdatedAt.After(sortedScores[j].UpdatedAt) {
					sortedScores[i], sortedScores[j] = sortedScores[j], sortedScores[i]
				}
			}
		}
	}

	archived := make([]*models.ArchivedPlayerScore, 0, len(sortedScores))
	var currentRank int64 = 1
	var prevScore int64 = -1

	for i, ps := range sortedScores {
		if ps.TotalScore != prevScore {
			currentRank = int64(i) + 1
			prevScore = ps.TotalScore
		}

		archived = append(archived, &models.ArchivedPlayerScore{
			PlayerID:   ps.PlayerID,
			TotalScore: ps.TotalScore,
			Rank:       currentRank,
			UpdatedAt:  ps.UpdatedAt,
		})
	}

	return archived, nil
}

func (s *MySQLStore) SerializeArchivedLeaderboards(gameID, seasonID string) ([]*models.ArchivedLeaderboard, error) {
	lbTypes := []models.LeaderboardType{
		models.LeaderboardTypeTotal,
		models.LeaderboardTypeWeekly,
		models.LeaderboardTypeMonthly,
	}

	result := make([]*models.ArchivedLeaderboard, 0, len(lbTypes))

	for _, lbType := range lbTypes {
		lb, err := s.GetLeaderboard(gameID, seasonID, lbType)
		if err != nil {
			return nil, err
		}
		if lb == nil {
			continue
		}

		entries, err := lb.GetEntries()
		if err != nil {
			return nil, err
		}

		result = append(result, &models.ArchivedLeaderboard{
			Type:         lbType,
			TotalPlayers: lb.TotalPlayers,
			Entries:      entries,
			UpdatedAt:    lb.UpdatedAt,
		})
	}

	return result, nil
}

func (s *MySQLStore) ArchiveToJSON(v interface{}) (string, error) {
	data, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
