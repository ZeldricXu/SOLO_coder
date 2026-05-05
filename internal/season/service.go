package season

import (
	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/models"
	"GameLeaderboard/internal/push"
	"GameLeaderboard/internal/storage"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"gorm.io/gorm"
)

type CreateSeasonRequest struct {
	GameID       string                 `json:"game_id" binding:"required"`
	SeasonName   string                 `json:"season_name" binding:"required"`
	StartTime    time.Time              `json:"start_time" binding:"required"`
	EndTime      time.Time              `json:"end_time" binding:"required"`
	RewardConfig *models.RewardConfig   `json:"reward_config"`
}

type SwitchSeasonRequest struct {
	GameID      string `json:"game_id" binding:"required"`
	NewSeasonID string `json:"new_season_id" binding:"required"`
}

type SeasonInfo struct {
	SeasonID     string                 `json:"season_id"`
	GameID       string                 `json:"game_id"`
	SeasonName   string                 `json:"season_name"`
	StartTime    time.Time              `json:"start_time"`
	EndTime      time.Time              `json:"end_time"`
	Status       models.SeasonStatus    `json:"status"`
	RewardConfig *models.RewardConfig   `json:"reward_config,omitempty"`
	CreatedAt    time.Time              `json:"created_at"`
}

type ArchiveResult struct {
	Success       bool                               `json:"success"`
	ArchiveID     string                             `json:"archive_id,omitempty"`
	PlayerCount   int64                              `json:"player_count"`
	TotalScoreSum int64                              `json:"total_score_sum"`
	Validation    *models.ArchiveValidationResult    `json:"validation,omitempty"`
	ErrorMessage  string                             `json:"error_message,omitempty"`
	ArchivedAt    time.Time                          `json:"archived_at"`
}

type SeasonService struct {
	mysqlStore    *storage.MySQLStore
	redisStore    *storage.RedisStore
	pushService   *push.PushService

	autoSwitchEnabled bool
	checkInterval     time.Duration
	archiveEnabled    bool
	backupTimeout     time.Duration

	stopCh    chan struct{}
	wg        sync.WaitGroup
	isRunning bool
	mu        sync.Mutex
}

func NewSeasonServiceWithConfig(
	mysqlStore *storage.MySQLStore,
	redisStore *storage.RedisStore,
	pushService *push.PushService,
	cfg *config.SeasonConfig,
) *SeasonService {
	if cfg == nil {
		cfg = &config.SeasonConfig{
			AutoSwitchEnabled: true,
			CheckInterval:     time.Minute,
			ArchiveEnabled:    true,
			BackupTimeout:     30 * time.Second,
		}
	}

	service := &SeasonService{
		mysqlStore:         mysqlStore,
		redisStore:         redisStore,
		pushService:        pushService,
		autoSwitchEnabled:  cfg.AutoSwitchEnabled,
		checkInterval:      cfg.CheckInterval,
		archiveEnabled:     cfg.ArchiveEnabled,
		backupTimeout:      cfg.BackupTimeout,
		stopCh:             make(chan struct{}),
	}

	if service.autoSwitchEnabled {
		service.StartScheduler()
	}

	return service
}

func NewSeasonService(
	mysqlStore *storage.MySQLStore,
	redisStore *storage.RedisStore,
	pushService *push.PushService,
) *SeasonService {
	return NewSeasonServiceWithConfig(mysqlStore, redisStore, pushService, nil)
}

func (s *SeasonService) StartScheduler() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.isRunning {
		return
	}

	s.isRunning = true
	s.wg.Add(1)
	go s.runScheduler()
}

func (s *SeasonService) StopScheduler() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.isRunning {
		return
	}

	close(s.stopCh)
	s.wg.Wait()
	s.isRunning = false
}

func (s *SeasonService) runScheduler() {
	defer s.wg.Done()

	ticker := time.NewTicker(s.checkInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			s.checkAndSwitchSeasons()
		case <-s.stopCh:
			return
		}
	}
}

func (s *SeasonService) checkAndSwitchSeasons() {
	activeSeasons, err := s.mysqlStore.BatchGetActiveSeasons()
	if err != nil {
		return
	}

	now := time.Now()

	for _, season := range activeSeasons {
		if now.After(season.EndTime) {
			pendingSeason, err := s.mysqlStore.GetPendingSeason(season.GameID)
			if err != nil {
				continue
			}

			if pendingSeason != nil && now.After(pendingSeason.StartTime) {
				_, err = s.SwitchSeasonWithArchive(&SwitchSeasonRequest{
					GameID:      season.GameID,
					NewSeasonID: pendingSeason.SeasonID,
				})
				if err != nil {
				}
			}
		}
	}
}

func (s *SeasonService) CreateSeason(req *CreateSeasonRequest) (*SeasonInfo, error) {
	if req.GameID == "" {
		return nil, errors.New("game_id is required")
	}
	if req.SeasonName == "" {
		return nil, errors.New("season_name is required")
	}
	if req.StartTime.IsZero() {
		return nil, errors.New("start_time is required")
	}
	if req.EndTime.IsZero() {
		return nil, errors.New("end_time is required")
	}
	if req.EndTime.Before(req.StartTime) {
		return nil, errors.New("end_time must be after start_time")
	}

	activeSeason, err := s.mysqlStore.GetActiveSeason(req.GameID)
	if err != nil {
		return nil, fmt.Errorf("failed to check active season: %w", err)
	}

	var status models.SeasonStatus
	if activeSeason == nil {
		status = models.SeasonStatusActive
	} else {
		if req.StartTime.Before(activeSeason.EndTime) && req.EndTime.After(activeSeason.StartTime) {
			return nil, errors.New("new season overlaps with active season")
		}
		status = models.SeasonStatusPending
	}

	seasonID := generateSeasonID(req.GameID)

	season := &models.Season{
		SeasonID:   seasonID,
		GameID:     req.GameID,
		SeasonName: req.SeasonName,
		StartTime:  req.StartTime,
		EndTime:    req.EndTime,
		Status:     status,
	}

	if req.RewardConfig != nil {
		err = season.SetRewardConfig(req.RewardConfig)
		if err != nil {
			return nil, fmt.Errorf("failed to set reward config: %w", err)
		}
	}

	err = s.mysqlStore.CreateSeason(season)
	if err != nil {
		return nil, fmt.Errorf("failed to create season: %w", err)
	}

	return s.toSeasonInfo(season), nil
}

func (s *SeasonService) SwitchSeason(req *SwitchSeasonRequest) error {
	_, err := s.SwitchSeasonWithArchive(req)
	return err
}

func (s *SeasonService) SwitchSeasonWithArchive(req *SwitchSeasonRequest) (*ArchiveResult, error) {
	if req.GameID == "" {
		return nil, errors.New("game_id is required")
	}
	if req.NewSeasonID == "" {
		return nil, errors.New("new_season_id is required")
	}

	currentSeason, err := s.mysqlStore.GetActiveSeason(req.GameID)
	if err != nil {
		return nil, fmt.Errorf("failed to get current active season: %w", err)
	}

	newSeason, err := s.mysqlStore.GetSeasonByID(req.NewSeasonID)
	if err != nil {
		return nil, fmt.Errorf("failed to get new season: %w", err)
	}
	if newSeason == nil {
		return nil, errors.New("new season not found")
	}
	if newSeason.GameID != req.GameID {
		return nil, errors.New("new season does not belong to this game")
	}

	var archiveResult *ArchiveResult

	if currentSeason != nil && s.archiveEnabled {
		archiveResult, err = s.ArchiveSeason(currentSeason)
		if err != nil {
			return nil, fmt.Errorf("failed to archive current season: %w", err)
		}
		if !archiveResult.Success {
			return archiveResult, fmt.Errorf("archive validation failed: %s", archiveResult.ErrorMessage)
		}
	}

	db := s.mysqlStore.GetDB()
	err = db.Transaction(func(tx *gorm.DB) error {
		if currentSeason != nil {
			currentSeason.Status = models.SeasonStatusEnded
			if err := tx.Save(currentSeason).Error; err != nil {
				return err
			}

			if err := s.markSeasonArchived(tx, currentSeason); err != nil {
				return err
			}
		}

		newSeason.Status = models.SeasonStatusActive
		if err := tx.Save(newSeason).Error; err != nil {
			return err
		}

		return nil
	})

	if err != nil {
		return archiveResult, fmt.Errorf("season switch failed: %w", err)
	}

	if currentSeason != nil {
		err = s.redisStore.ClearLeaderboard(req.GameID, currentSeason.SeasonID)
		if err != nil {
		}

		s.redisStore.PublishSeasonSwitch(req.GameID, currentSeason.SeasonID, newSeason.SeasonID)

		if s.pushService != nil {
			broadcastMsg := &push.BroadcastMessage{
				GameID:    req.GameID,
				SeasonID:  newSeason.SeasonID,
				Type:      "season_switch",
				Timestamp: time.Now(),
				Data: map[string]interface{}{
					"old_season_id": currentSeason.SeasonID,
					"new_season_id": newSeason.SeasonID,
					"archive_result": archiveResult,
				},
			}
			s.pushService.BroadcastToGame(req.GameID, broadcastMsg)
		}
	}

	return archiveResult, nil
}

func (s *SeasonService) ArchiveSeason(season *models.Season) (*ArchiveResult, error) {
	result := &ArchiveResult{
		Success:    false,
		ArchivedAt: time.Now(),
	}

	playerCount, err := s.mysqlStore.CountPlayersInSeason(season.GameID, season.SeasonID)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to count players: %v", err)
		return result, err
	}
	result.PlayerCount = playerCount

	totalScoreSum, err := s.mysqlStore.SumPlayerScores(season.GameID, season.SeasonID)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to sum scores: %v", err)
		return result, err
	}
	result.TotalScoreSum = totalScoreSum

	allScores, err := s.mysqlStore.GetAllPlayerScores(season.GameID, season.SeasonID)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to get all player scores: %v", err)
		return result, err
	}

	archivedScores, err := s.mysqlStore.SerializeArchivedPlayerScores(allScores)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to serialize player scores: %v", err)
		return result, err
	}

	archivedLeaderboards, err := s.mysqlStore.SerializeArchivedLeaderboards(season.GameID, season.SeasonID)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to serialize leaderboards: %v", err)
		return result, err
	}

	archiveID := generateArchiveID(season.GameID, season.SeasonID)

	checksum := s.mysqlStore.CalculateArchiveChecksum(
		season.GameID, season.SeasonID, playerCount, totalScoreSum)

	archive := &models.SeasonArchive{
		ArchiveID:     archiveID,
		GameID:        season.GameID,
		SeasonID:      season.SeasonID,
		SeasonName:    season.SeasonName,
		StartTime:     season.StartTime,
		EndTime:       season.EndTime,
		ArchivedAt:    time.Now(),
		PlayerCount:   playerCount,
		TotalScoreSum: totalScoreSum,
		Checksum:      checksum,
	}

	err = archive.SetPlayerScores(archivedScores)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to set player scores: %v", err)
		return result, err
	}

	err = archive.SetLeaderboardData(archivedLeaderboards)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to set leaderboard data: %v", err)
		return result, err
	}

	err = s.mysqlStore.CreateSeasonArchive(archive)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to create archive: %v", err)
		return result, err
	}

	result.ArchiveID = archiveID

	validation, err := s.mysqlStore.ValidateArchive(archive)
	if err != nil {
		result.ErrorMessage = fmt.Sprintf("failed to validate archive: %v", err)
		return result, err
	}

	result.Validation = validation
	if validation.Valid {
		result.Success = true
	} else {
		result.ErrorMessage = validation.ErrorMessage
	}

	return result, nil
}

func (s *SeasonService) markSeasonArchived(tx *gorm.DB, season *models.Season) error {
	season.Status = models.SeasonStatusArchived
	return tx.Save(season).Error
}

func (s *SeasonService) GetActiveSeason(gameID string) (*SeasonInfo, error) {
	season, err := s.mysqlStore.GetActiveSeason(gameID)
	if err != nil {
		return nil, err
	}
	if season == nil {
		return nil, nil
	}
	return s.toSeasonInfo(season), nil
}

func (s *SeasonService) GetSeasonByID(seasonID string) (*SeasonInfo, error) {
	season, err := s.mysqlStore.GetSeasonByID(seasonID)
	if err != nil {
		return nil, err
	}
	if season == nil {
		return nil, nil
	}
	return s.toSeasonInfo(season), nil
}

func (s *SeasonService) GetAllSeasons(gameID string) ([]*SeasonInfo, error) {
	seasons, err := s.mysqlStore.GetAllSeasons(gameID)
	if err != nil {
		return nil, err
	}

	infos := make([]*SeasonInfo, len(seasons))
	for i, season := range seasons {
		infos[i] = s.toSeasonInfo(season)
	}
	return infos, nil
}

func (s *SeasonService) GetSeasonArchive(seasonID string) (*models.SeasonArchive, error) {
	return s.mysqlStore.GetSeasonArchive(seasonID)
}

func (s *SeasonService) toSeasonInfo(season *models.Season) *SeasonInfo {
	info := &SeasonInfo{
		SeasonID:   season.SeasonID,
		GameID:     season.GameID,
		SeasonName: season.SeasonName,
		StartTime:  season.StartTime,
		EndTime:    season.EndTime,
		Status:     season.Status,
		CreatedAt:  season.CreatedAt,
	}

	if rc, err := season.GetRewardConfig(); err == nil {
		info.RewardConfig = rc
	}

	return info
}

func generateSeasonID(gameID string) string {
	timestamp := time.Now().UnixNano()
	return fmt.Sprintf("season_%s_%d", gameID, timestamp)
}

func generateArchiveID(gameID, seasonID string) string {
	timestamp := time.Now().UnixNano()
	return fmt.Sprintf("archive_%s_%s_%d", gameID, seasonID, timestamp)
}

func (s *SeasonService) QueryHistoricalLeaderboard(gameID, seasonID string, lbType models.LeaderboardType, limit int64) (*models.Leaderboard, error) {
	if lbType == "" {
		lbType = models.LeaderboardTypeTotal
	}

	lb, err := s.mysqlStore.GetLeaderboard(gameID, seasonID, lbType)
	if err != nil {
		return nil, err
	}
	if lb == nil {
		archive, err := s.mysqlStore.GetSeasonArchive(seasonID)
		if err != nil {
			return nil, err
		}
		if archive == nil {
			return nil, errors.New("leaderboard not found")
		}

		archivedLBs, err := archive.GetLeaderboardData()
		if err != nil {
			return nil, err
		}

		for _, alb := range archivedLBs {
			if alb.Type == lbType {
				lb = &models.Leaderboard{
					LeaderboardID: generateLeaderboardIDFromArchive(gameID, seasonID, lbType),
					GameID:        gameID,
					SeasonID:      seasonID,
					Type:          lbType,
					TotalPlayers:  alb.TotalPlayers,
					UpdatedAt:     alb.UpdatedAt,
				}
				lb.SetEntries(alb.Entries)
				break
			}
		}

		if lb == nil {
			return nil, errors.New("leaderboard not found")
		}
		return lb, nil
	}

	return lb, nil
}

func generateLeaderboardIDFromArchive(gameID, seasonID string, lbType models.LeaderboardType) string {
	return fmt.Sprintf("lb_archived_%s_%s_%s", gameID, seasonID, lbType)
}

func (s *SeasonService) UpdateSeasonStatus(seasonID string, status models.SeasonStatus) error {
	season, err := s.mysqlStore.GetSeasonByID(seasonID)
	if err != nil {
		return err
	}
	if season == nil {
		return errors.New("season not found")
	}

	season.Status = status
	return s.mysqlStore.UpdateSeason(season)
}

type SeasonSwitchNotification struct {
	GameID       string    `json:"game_id"`
	OldSeasonID  string    `json:"old_season_id"`
	NewSeasonID  string    `json:"new_season_id"`
	SwitchedAt   time.Time `json:"switched_at"`
}

func (s *SeasonService) CreateSeasonSwitchNotification(gameID, oldSeasonID, newSeasonID string) *SeasonSwitchNotification {
	return &SeasonSwitchNotification{
		GameID:      gameID,
		OldSeasonID: oldSeasonID,
		NewSeasonID: newSeasonID,
		SwitchedAt:  time.Now(),
	}
}

func (n *SeasonSwitchNotification) ToJSON() string {
	data, _ := json.Marshal(n)
	return string(data)
}

func (s *SeasonService) GetSchedulerStatus() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()

	return map[string]interface{}{
		"is_running":         s.isRunning,
		"auto_switch_enabled": s.autoSwitchEnabled,
		"check_interval":     s.checkInterval.String(),
		"archive_enabled":    s.archiveEnabled,
	}
}
