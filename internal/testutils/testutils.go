package testutils

import (
	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/models"
	"math/rand"
	"strconv"
	"time"
)

func init() {
	rand.Seed(time.Now().UnixNano())
}

func GenerateTestConfig() *config.Config {
	return &config.Config{
		Server: config.ServerConfig{
			Port: 8081,
			Mode: "test",
		},
		MySQL: config.MySQLConfig{
			Host:     "localhost",
			Port:     3306,
			Username: "root",
			Password: "password",
			Database: "game_leaderboard_test",
			Charset:  "utf8mb4",
		},
		Redis: config.RedisConfig{
			Host:     "localhost",
			Port:     6379,
			Password: "",
			DB:       1,
		},
		WebSocket: config.WebSocketConfig{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			WriteWait:       10,
			PongWait:        60,
			PingPeriod:      54,
		},
		Season: config.SeasonConfig{
			AutoSwitchEnabled: true,
			CheckInterval:     1 * time.Second,
			DefaultDuration:   1 * time.Hour,
			ArchiveEnabled:    true,
			BackupTimeout:     10 * time.Second,
		},
		Ranking: config.RankingConfig{
			IncrementalUpdate:      true,
			SnapshotInterval:       5 * time.Minute,
			PushAggregationEnabled: true,
			PushAggregationWindow:  1 * time.Second,
			SameScoreAsSameRank:    true,
		},
	}
}

func GeneratePlayerID(prefix string, index int) string {
	if prefix == "" {
		prefix = "player"
	}
	return prefix + "_" + strconv.Itoa(index)
}

func GenerateGameID(name string) string {
	if name == "" {
		name = "game"
	}
	return name + "_" + strconv.FormatInt(time.Now().Unix(), 10)
}

func GenerateSeasonID(gameID string) string {
	return "season_" + gameID + "_" + strconv.FormatInt(time.Now().UnixNano(), 10)
}

func CreateTestSeason(gameID string, offsetDays int, durationDays int) *models.Season {
	now := time.Now()
	startTime := now.AddDate(0, 0, offsetDays)
	endTime := startTime.AddDate(0, 0, durationDays)

	return &models.Season{
		SeasonID:   GenerateSeasonID(gameID),
		GameID:     gameID,
		SeasonName: "Test Season " + strconv.Itoa(offsetDays),
		StartTime:  startTime,
		EndTime:    endTime,
		Status:     models.SeasonStatusPending,
	}
}

func CreateActiveTestSeason(gameID string) *models.Season {
	now := time.Now()
	startTime := now.AddDate(0, 0, -7)
	endTime := now.AddDate(0, 0, 7)

	return &models.Season{
		SeasonID:   GenerateSeasonID(gameID),
		GameID:     gameID,
		SeasonName: "Active Test Season",
		StartTime:  startTime,
		EndTime:    endTime,
		Status:     models.SeasonStatusActive,
	}
}

func CreateEndedTestSeason(gameID string) *models.Season {
	now := time.Now()
	startTime := now.AddDate(0, 0, -14)
	endTime := now.AddDate(0, 0, -7)

	return &models.Season{
		SeasonID:   GenerateSeasonID(gameID),
		GameID:     gameID,
		SeasonName: "Ended Test Season",
		StartTime:  startTime,
		EndTime:    endTime,
		Status:     models.SeasonStatusEnded,
	}
}

func CreateTestPlayerScore(gameID, seasonID, playerID string, score int64) *models.PlayerScore {
	return &models.PlayerScore{
		PlayerID:   playerID,
		GameID:     gameID,
		SeasonID:   seasonID,
		TotalScore: score,
		UpdatedAt:  time.Now(),
	}
}

func CreateTestScoreRecord(gameID, seasonID, playerID string, scoreChange int64, totalScore int64) *models.ScoreRecord {
	return &models.ScoreRecord{
		ScoreID:     "score_" + strconv.FormatInt(time.Now().UnixNano(), 10),
		PlayerID:    playerID,
		GameID:      gameID,
		ScoreChange: scoreChange,
		TotalScore:  totalScore,
		SeasonID:    seasonID,
		ScoreType:   "test_type",
		RecordedAt:  time.Now(),
	}
}

func GenerateRandomScores(count int, minScore, maxScore int64) []int64 {
	scores := make([]int64, count)
	for i := 0; i < count; i++ {
		scores[i] = minScore + rand.Int63n(maxScore-minScore+1)
	}
	return scores
}

func GenerateRankingTestData(gameID, seasonID string, playerCount int, withTies bool) []*models.PlayerScore {
	scores := make([]*models.PlayerScore, 0, playerCount)

	if withTies {
		for i := 0; i < playerCount; i++ {
			var score int64
			if i < 3 {
				score = 1000
			} else if i < 6 {
				score = 900
			} else if i < 9 {
				score = 800
			} else {
				score = int64(1000 - (i-9)*50)
			}

			scores = append(scores, CreateTestPlayerScore(
				gameID,
				seasonID,
				GeneratePlayerID("p", i),
				score,
			))
		}
	} else {
		for i := 0; i < playerCount; i++ {
			score := int64(1000 - i*10)
			scores = append(scores, CreateTestPlayerScore(
				gameID,
				seasonID,
				GeneratePlayerID("p", i),
				score,
			))
		}
	}

	return scores
}

func CreateLeaderboardEntry(rank int64, playerID string, score int64) *models.LeaderboardEntry {
	return &models.LeaderboardEntry{
		Rank:      rank,
		PlayerID:  playerID,
		Score:     score,
		UpdatedAt: time.Now(),
	}
}

type TestRankCase struct {
	Name           string
	Scores         []*models.PlayerScore
	ExpectedRanks  map[string]int64
	SameScoreAsSameRank bool
}

func GetTestRankCases() []TestRankCase {
	return []TestRankCase{
		{
			Name: "standard_ranking_no_ties",
			Scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 1000},
				{PlayerID: "p2", TotalScore: 900},
				{PlayerID: "p3", TotalScore: 800},
			},
			ExpectedRanks: map[string]int64{
				"p1": 1,
				"p2": 2,
				"p3": 3,
			},
			SameScoreAsSameRank: true,
		},
		{
			Name: "ranking_with_ties_same_rank",
			Scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 1000},
				{PlayerID: "p2", TotalScore: 1000},
				{PlayerID: "p3", TotalScore: 900},
				{PlayerID: "p4", TotalScore: 900},
				{PlayerID: "p5", TotalScore: 800},
			},
			ExpectedRanks: map[string]int64{
				"p1": 1,
				"p2": 1,
				"p3": 3,
				"p4": 3,
				"p5": 5,
			},
			SameScoreAsSameRank: true,
		},
		{
			Name: "ranking_with_ties_dense",
			Scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 1000},
				{PlayerID: "p2", TotalScore: 1000},
				{PlayerID: "p3", TotalScore: 900},
			},
			ExpectedRanks: map[string]int64{
				"p1": 1,
				"p2": 2,
				"p3": 3,
			},
			SameScoreAsSameRank: false,
		},
		{
			Name: "single_player_ranking",
			Scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 500},
			},
			ExpectedRanks: map[string]int64{
				"p1": 1,
			},
			SameScoreAsSameRank: true,
		},
		{
			Name: "all_players_same_score",
			Scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 500},
				{PlayerID: "p2", TotalScore: 500},
				{PlayerID: "p3", TotalScore: 500},
				{PlayerID: "p4", TotalScore: 500},
			},
			ExpectedRanks: map[string]int64{
				"p1": 1,
				"p2": 1,
				"p3": 1,
				"p4": 1,
			},
			SameScoreAsSameRank: true,
		},
	}
}

type TestSeasonSwitchCase struct {
	Name               string
	CurrentSeason      *models.Season
	NewSeason          *models.Season
	PlayerScores       []*models.PlayerScore
	ExpectedStatus     models.SeasonStatus
	ExpectArchive      bool
	ExpectError        bool
}

func GetTestSeasonSwitchCases() []TestSeasonSwitchCase {
	gameID := "test_game_switch"

	return []TestSeasonSwitchCase{
		{
			Name: "normal_season_switch",
			CurrentSeason: &models.Season{
				SeasonID:   "season_current",
				GameID:     gameID,
				SeasonName: "Current Season",
				StartTime:  time.Now().AddDate(0, 0, -7),
				EndTime:    time.Now().AddDate(0, 0, -1),
				Status:     models.SeasonStatusActive,
			},
			NewSeason: &models.Season{
				SeasonID:   "season_new",
				GameID:     gameID,
				SeasonName: "New Season",
				StartTime:  time.Now(),
				EndTime:    time.Now().AddDate(0, 0, 7),
				Status:     models.SeasonStatusPending,
			},
			PlayerScores: []*models.PlayerScore{
				{PlayerID: "p1", GameID: gameID, SeasonID: "season_current", TotalScore: 1000},
				{PlayerID: "p2", GameID: gameID, SeasonID: "season_current", TotalScore: 900},
				{PlayerID: "p3", GameID: gameID, SeasonID: "season_current", TotalScore: 800},
			},
			ExpectedStatus: models.SeasonStatusActive,
			ExpectArchive:  true,
			ExpectError:    false,
		},
		{
			Name: "season_switch_with_no_players",
			CurrentSeason: &models.Season{
				SeasonID:   "season_empty",
				GameID:     gameID,
				SeasonName: "Empty Season",
				StartTime:  time.Now().AddDate(0, 0, -7),
				EndTime:    time.Now().AddDate(0, 0, -1),
				Status:     models.SeasonStatusActive,
			},
			NewSeason: &models.Season{
				SeasonID:   "season_new_empty",
				GameID:     gameID,
				SeasonName: "New Empty Season",
				StartTime:  time.Now(),
				EndTime:    time.Now().AddDate(0, 0, 7),
				Status:     models.SeasonStatusPending,
			},
			PlayerScores:   []*models.PlayerScore{},
			ExpectedStatus: models.SeasonStatusActive,
			ExpectArchive:  true,
			ExpectError:    false,
		},
	}
}

func StringPtr(s string) *string {
	return &s
}

func Int64Ptr(i int64) *int64 {
	return &i
}

func BoolPtr(b bool) *bool {
	return &b
}
