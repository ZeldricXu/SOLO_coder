package season

import (
	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/models"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockMySQLStore struct{}
type mockRedisStore struct{}
type mockPushService struct{}

func TestSeasonCreation(t *testing.T) {
	t.Run("valid_season_creation", func(t *testing.T) {
		gameID := "test_game_001"
		now := time.Now()

		req := &CreateSeasonRequest{
			GameID:     gameID,
			SeasonName: "Test Season 1",
			StartTime:  now.AddDate(0, 0, 1),
			EndTime:    now.AddDate(0, 0, 8),
		}

		assert.Equal(t, gameID, req.GameID)
		assert.Equal(t, "Test Season 1", req.SeasonName)
		assert.True(t, req.EndTime.After(req.StartTime))
	})

	t.Run("season_creation_validation", func(t *testing.T) {
		testCases := []struct {
			name        string
			req         *CreateSeasonRequest
			expectValid bool
		}{
			{
				name: "valid_season",
				req: &CreateSeasonRequest{
					GameID:     "game1",
					SeasonName: "Season 1",
					StartTime:  time.Now(),
					EndTime:    time.Now().AddDate(0, 0, 7),
				},
				expectValid: true,
			},
			{
				name: "missing_game_id",
				req: &CreateSeasonRequest{
					GameID:     "",
					SeasonName: "Season 1",
					StartTime:  time.Now(),
					EndTime:    time.Now().AddDate(0, 0, 7),
				},
				expectValid: false,
			},
			{
				name: "missing_season_name",
				req: &CreateSeasonRequest{
					GameID:     "game1",
					SeasonName: "",
					StartTime:  time.Now(),
					EndTime:    time.Now().AddDate(0, 0, 7),
				},
				expectValid: false,
			},
			{
				name: "end_time_before_start_time",
				req: &CreateSeasonRequest{
					GameID:     "game1",
					SeasonName: "Season 1",
					StartTime:  time.Now().AddDate(0, 0, 7),
					EndTime:    time.Now(),
				},
				expectValid: false,
			},
		}

		for _, tc := range testCases {
			t.Run(tc.name, func(t *testing.T) {
				isValid := tc.req.GameID != "" &&
					tc.req.SeasonName != "" &&
					!tc.req.StartTime.IsZero() &&
					!tc.req.EndTime.IsZero() &&
					tc.req.EndTime.After(tc.req.StartTime)

				assert.Equal(t, tc.expectValid, isValid)
			})
		}
	})
}

func TestSeasonIDGeneration(t *testing.T) {
	gameID := "test_game"

	id1 := generateSeasonID(gameID)
	id2 := generateSeasonID(gameID)

	assert.Contains(t, id1, gameID)
	assert.Contains(t, id2, gameID)
	assert.NotEqual(t, id1, id2)
}

func TestArchiveIDGeneration(t *testing.T) {
	gameID := "test_game"
	seasonID := "season_2026_q1"

	archiveID1 := generateArchiveID(gameID, seasonID)
	archiveID2 := generateArchiveID(gameID, seasonID)

	assert.Contains(t, archiveID1, gameID)
	assert.Contains(t, archiveID1, seasonID)
	assert.Contains(t, archiveID2, gameID)
	assert.Contains(t, archiveID2, seasonID)
	assert.NotEqual(t, archiveID1, archiveID2)
}

func TestSeasonStatusTransitions(t *testing.T) {
	testCases := []struct {
		name           string
		initialStatus  models.SeasonStatus
		transitionFunc func(*models.Season)
		expectedStatus models.SeasonStatus
	}{
		{
			name:          "pending_to_active",
			initialStatus: models.SeasonStatusPending,
			transitionFunc: func(s *models.Season) {
				s.Status = models.SeasonStatusActive
			},
			expectedStatus: models.SeasonStatusActive,
		},
		{
			name:          "active_to_ended",
			initialStatus: models.SeasonStatusActive,
			transitionFunc: func(s *models.Season) {
				s.Status = models.SeasonStatusEnded
			},
			expectedStatus: models.SeasonStatusEnded,
		},
		{
			name:          "ended_to_archived",
			initialStatus: models.SeasonStatusEnded,
			transitionFunc: func(s *models.Season) {
				s.Status = models.SeasonStatusArchived
			},
			expectedStatus: models.SeasonStatusArchived,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			season := &models.Season{
				SeasonID:   "test_season",
				GameID:     "test_game",
				SeasonName: "Test Season",
				StartTime:  time.Now(),
				EndTime:    time.Now().AddDate(0, 0, 7),
				Status:     tc.initialStatus,
			}

			tc.transitionFunc(season)

			assert.Equal(t, tc.expectedStatus, season.Status)
		})
	}
}

func TestSeasonInfoConversion(t *testing.T) {
	now := time.Now()

	season := &models.Season{
		SeasonID:   "season_001",
		GameID:     "game_001",
		SeasonName: "2026 Q1 Season",
		StartTime:  now.AddDate(0, 0, -7),
		EndTime:    now.AddDate(0, 0, 7),
		Status:     models.SeasonStatusActive,
		CreatedAt:  now.AddDate(0, 0, -14),
	}

	rewardConfig := &models.RewardConfig{
		Top10: map[string]interface{}{"reward": "gold"},
	}
	season.SetRewardConfig(rewardConfig)

	service := &SeasonService{}
	info := service.toSeasonInfo(season)

	assert.Equal(t, season.SeasonID, info.SeasonID)
	assert.Equal(t, season.GameID, info.GameID)
	assert.Equal(t, season.SeasonName, info.SeasonName)
	assert.Equal(t, season.StartTime, info.StartTime)
	assert.Equal(t, season.EndTime, info.EndTime)
	assert.Equal(t, season.Status, info.Status)
	assert.Equal(t, season.CreatedAt, info.CreatedAt)

	assert.NotNil(t, info.RewardConfig)
	assert.Equal(t, "gold", info.RewardConfig.Top10["reward"])
}

func TestSeasonSwitchRequestValidation(t *testing.T) {
	testCases := []struct {
		name        string
		req         *SwitchSeasonRequest
		expectValid bool
	}{
		{
			name: "valid_request",
			req: &SwitchSeasonRequest{
				GameID:      "game1",
				NewSeasonID: "season_new",
			},
			expectValid: true,
		},
		{
			name: "missing_game_id",
			req: &SwitchSeasonRequest{
				GameID:      "",
				NewSeasonID: "season_new",
			},
			expectValid: false,
		},
		{
			name: "missing_new_season_id",
			req: &SwitchSeasonRequest{
				GameID:      "game1",
				NewSeasonID: "",
			},
			expectValid: false,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			isValid := tc.req.GameID != "" && tc.req.NewSeasonID != ""
			assert.Equal(t, tc.expectValid, isValid)
		})
	}
}

func TestArchiveResultValidation(t *testing.T) {
	t.Run("successful_archive_result", func(t *testing.T) {
		result := &ArchiveResult{
			Success:       true,
			ArchiveID:     "archive_001",
			PlayerCount:   100,
			TotalScoreSum: 50000,
			Validation: &models.ArchiveValidationResult{
				Valid:               true,
				ExpectedPlayerCount: 100,
				ActualPlayerCount:   100,
				ExpectedTotalScore:  50000,
				ActualTotalScore:    50000,
			},
			ArchivedAt: time.Now(),
		}

		assert.True(t, result.Success)
		assert.NotEmpty(t, result.ArchiveID)
		assert.Equal(t, int64(100), result.PlayerCount)
		assert.Equal(t, int64(50000), result.TotalScoreSum)
		assert.True(t, result.Validation.Valid)
	})

	t.Run("failed_archive_result", func(t *testing.T) {
		result := &ArchiveResult{
			Success:      false,
			ErrorMessage: "Validation failed: player count mismatch",
			ArchivedAt:   time.Now(),
		}

		assert.False(t, result.Success)
		assert.Contains(t, result.ErrorMessage, "Validation failed")
	})
}

func TestSeasonSwitchNotification(t *testing.T) {
	gameID := "test_game"
	oldSeasonID := "season_old"
	newSeasonID := "season_new"

	service := &SeasonService{}
	notification := service.CreateSeasonSwitchNotification(gameID, oldSeasonID, newSeasonID)

	assert.Equal(t, gameID, notification.GameID)
	assert.Equal(t, oldSeasonID, notification.OldSeasonID)
	assert.Equal(t, newSeasonID, notification.NewSeasonID)
	assert.True(t, notification.SwitchedAt.Before(time.Now()) || notification.SwitchedAt.Equal(time.Now()))

	jsonStr := notification.ToJSON()
	assert.Contains(t, jsonStr, gameID)
	assert.Contains(t, jsonStr, oldSeasonID)
	assert.Contains(t, jsonStr, newSeasonID)
}

func TestSchedulerStatus(t *testing.T) {
	service := &SeasonService{
		autoSwitchEnabled: true,
		checkInterval:     30 * time.Second,
		archiveEnabled:    true,
		isRunning:         false,
	}

	status := service.GetSchedulerStatus()

	assert.False(t, status["is_running"].(bool))
	assert.True(t, status["auto_switch_enabled"].(bool))
	assert.Equal(t, "30s", status["check_interval"])
	assert.True(t, status["archive_enabled"].(bool))
}

func TestSeasonTimeBoundaries(t *testing.T) {
	now := time.Now()

	t.Run("season_is_active", func(t *testing.T) {
		season := &models.Season{
			StartTime: now.AddDate(0, 0, -7),
			EndTime:   now.AddDate(0, 0, 7),
			Status:    models.SeasonStatusActive,
		}

		assert.True(t, now.After(season.StartTime))
		assert.True(t, now.Before(season.EndTime))
		assert.Equal(t, models.SeasonStatusActive, season.Status)
	})

	t.Run("season_has_ended", func(t *testing.T) {
		season := &models.Season{
			StartTime: now.AddDate(0, 0, -14),
			EndTime:   now.AddDate(0, 0, -7),
			Status:    models.SeasonStatusEnded,
		}

		assert.True(t, now.After(season.EndTime))
		assert.Equal(t, models.SeasonStatusEnded, season.Status)
	})

	t.Run("season_not_started", func(t *testing.T) {
		season := &models.Season{
			StartTime: now.AddDate(0, 0, 7),
			EndTime:   now.AddDate(0, 0, 14),
			Status:    models.SeasonStatusPending,
		}

		assert.True(t, now.Before(season.StartTime))
		assert.Equal(t, models.SeasonStatusPending, season.Status)
	})
}

func TestRewardConfigSerialization(t *testing.T) {
	season := &models.Season{}

	rewardConfig := &models.RewardConfig{
		Top10:  map[string]interface{}{"title": "Champion", "reward": "1000_gems"},
		Top100: map[string]interface{}{"reward": "100_gems"},
		Custom: map[string]interface{}{"special": map[string]interface{}{"rank": 1, "reward": "exclusive_skin"}},
	}

	err := season.SetRewardConfig(rewardConfig)
	require.NoError(t, err)

	retrievedConfig, err := season.GetRewardConfig()
	require.NoError(t, err)

	assert.Equal(t, rewardConfig.Top10["title"], retrievedConfig.Top10["title"])
	assert.Equal(t, rewardConfig.Top10["reward"], retrievedConfig.Top10["reward"])
	assert.Equal(t, rewardConfig.Top100["reward"], retrievedConfig.Top100["reward"])
}

func TestEmptyRewardConfig(t *testing.T) {
	season := &models.Season{}

	config, err := season.GetRewardConfig()
	require.NoError(t, err)
	assert.NotNil(t, config)

	err = season.SetRewardConfig(nil)
	require.NoError(t, err)

	config, err = season.GetRewardConfig()
	require.NoError(t, err)
	assert.NotNil(t, config)
}

func TestLeaderboardIDFromArchive(t *testing.T) {
	gameID := "test_game"
	seasonID := "season_archived_001"
	lbType := models.LeaderboardTypeTotal

	lbID := generateLeaderboardIDFromArchive(gameID, seasonID, lbType)

	assert.Contains(t, lbID, "archived")
	assert.Contains(t, lbID, gameID)
	assert.Contains(t, lbID, seasonID)
	assert.Contains(t, lbID, string(lbType))
}

func TestSeasonInfoWithNilRewardConfig(t *testing.T) {
	season := &models.Season{
		SeasonID:   "test_season",
		GameID:     "test_game",
		SeasonName: "Test Season",
		StartTime:  time.Now(),
		EndTime:    time.Now().AddDate(0, 0, 7),
		Status:     models.SeasonStatusActive,
	}

	service := &SeasonService{}
	info := service.toSeasonInfo(season)

	assert.Nil(t, info.RewardConfig)
}

func TestSeasonSwitchWithArchiveFlow(t *testing.T) {
	t.Run("archive_then_switch_flow", func(t *testing.T) {
		gameID := "test_game_flow"

		currentSeason := &models.Season{
			SeasonID:   "season_current",
			GameID:     gameID,
			SeasonName: "Current Season",
			StartTime:  time.Now().AddDate(0, 0, -7),
			EndTime:    time.Now().AddDate(0, 0, -1),
			Status:     models.SeasonStatusActive,
		}

		newSeason := &models.Season{
			SeasonID:   "season_new",
			GameID:     gameID,
			SeasonName: "New Season",
			StartTime:  time.Now(),
			EndTime:    time.Now().AddDate(0, 0, 7),
			Status:     models.SeasonStatusPending,
		}

		assert.Equal(t, models.SeasonStatusActive, currentSeason.Status)
		assert.Equal(t, models.SeasonStatusPending, newSeason.Status)

		currentSeason.Status = models.SeasonStatusEnded
		newSeason.Status = models.SeasonStatusActive

		assert.Equal(t, models.SeasonStatusEnded, currentSeason.Status)
		assert.Equal(t, models.SeasonStatusActive, newSeason.Status)
	})
}

func TestConfigInitialization(t *testing.T) {
	cfg := &config.SeasonConfig{
		AutoSwitchEnabled: true,
		CheckInterval:     1 * time.Minute,
		DefaultDuration:   168 * time.Hour,
		ArchiveEnabled:    true,
		BackupTimeout:     30 * time.Second,
	}

	assert.True(t, cfg.AutoSwitchEnabled)
	assert.Equal(t, 1*time.Minute, cfg.CheckInterval)
	assert.Equal(t, 168*time.Hour, cfg.DefaultDuration)
	assert.True(t, cfg.ArchiveEnabled)
	assert.Equal(t, 30*time.Second, cfg.BackupTimeout)
}

func TestServiceInitializationWithConfig(t *testing.T) {
	cfg := &config.SeasonConfig{
		AutoSwitchEnabled: false,
		CheckInterval:     5 * time.Second,
		ArchiveEnabled:    false,
	}

	service := &SeasonService{
		autoSwitchEnabled: cfg.AutoSwitchEnabled,
		checkInterval:     cfg.CheckInterval,
		archiveEnabled:    cfg.ArchiveEnabled,
	}

	assert.False(t, service.autoSwitchEnabled)
	assert.Equal(t, 5*time.Second, service.checkInterval)
	assert.False(t, service.archiveEnabled)
}

func TestSeasonServiceDefaultConfig(t *testing.T) {
	service := &SeasonService{
		autoSwitchEnabled: true,
		checkInterval:     time.Minute,
		archiveEnabled:    true,
	}

	assert.True(t, service.autoSwitchEnabled)
	assert.Equal(t, time.Minute, service.checkInterval)
	assert.True(t, service.archiveEnabled)
}
