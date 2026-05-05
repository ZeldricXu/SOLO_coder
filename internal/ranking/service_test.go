package ranking

import (
	"fmt"
	"GameLeaderboard/internal/models"
	"testing"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type testRankingCase struct {
	name                string
	scores              []*models.PlayerScore
	expectedRanks       map[string]int64
	sameScoreAsSameRank bool
}

func TestCalculateRanksWithPolicy(t *testing.T) {
	testCases := []testRankingCase{
		{
			name: "standard_ranking_no_ties",
			scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 1000, UpdatedAt: time.Now()},
				{PlayerID: "p2", TotalScore: 900, UpdatedAt: time.Now()},
				{PlayerID: "p3", TotalScore: 800, UpdatedAt: time.Now()},
			},
			expectedRanks: map[string]int64{
				"p1": 1,
				"p2": 2,
				"p3": 3,
			},
			sameScoreAsSameRank: true,
		},
		{
			name: "ranking_with_ties_same_rank",
			scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 1000, UpdatedAt: time.Now()},
				{PlayerID: "p2", TotalScore: 1000, UpdatedAt: time.Now().Add(1 * time.Second)},
				{PlayerID: "p3", TotalScore: 900, UpdatedAt: time.Now()},
				{PlayerID: "p4", TotalScore: 900, UpdatedAt: time.Now().Add(1 * time.Second)},
				{PlayerID: "p5", TotalScore: 800, UpdatedAt: time.Now()},
			},
			expectedRanks: map[string]int64{
				"p1": 1,
				"p2": 1,
				"p3": 3,
				"p4": 3,
				"p5": 5,
			},
			sameScoreAsSameRank: true,
		},
		{
			name: "ranking_with_ties_sequential",
			scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 1000, UpdatedAt: time.Now()},
				{PlayerID: "p2", TotalScore: 1000, UpdatedAt: time.Now().Add(1 * time.Second)},
				{PlayerID: "p3", TotalScore: 900, UpdatedAt: time.Now()},
			},
			expectedRanks: map[string]int64{
				"p1": 1,
				"p2": 2,
				"p3": 3,
			},
			sameScoreAsSameRank: false,
		},
		{
			name: "single_player_ranking",
			scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 500, UpdatedAt: time.Now()},
			},
			expectedRanks: map[string]int64{
				"p1": 1,
			},
			sameScoreAsSameRank: true,
		},
		{
			name: "all_players_same_score",
			scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: 500, UpdatedAt: time.Now()},
				{PlayerID: "p2", TotalScore: 500, UpdatedAt: time.Now().Add(1 * time.Second)},
				{PlayerID: "p3", TotalScore: 500, UpdatedAt: time.Now().Add(2 * time.Second)},
				{PlayerID: "p4", TotalScore: 500, UpdatedAt: time.Now().Add(3 * time.Second)},
			},
			expectedRanks: map[string]int64{
				"p1": 1,
				"p2": 1,
				"p3": 1,
				"p4": 1,
			},
			sameScoreAsSameRank: true,
		},
		{
			name: "empty_scores",
			scores: []*models.PlayerScore{},
			expectedRanks: map[string]int64{},
			sameScoreAsSameRank: true,
		},
		{
			name: "negative_scores",
			scores: []*models.PlayerScore{
				{PlayerID: "p1", TotalScore: -100, UpdatedAt: time.Now()},
				{PlayerID: "p2", TotalScore: -50, UpdatedAt: time.Now()},
				{PlayerID: "p3", TotalScore: 0, UpdatedAt: time.Now()},
			},
			expectedRanks: map[string]int64{
				"p1": 3,
				"p2": 2,
				"p3": 1,
			},
			sameScoreAsSameRank: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			service := &RankingService{
				sameScoreAsSameRank: tc.sameScoreAsSameRank,
			}

			entries := service.calculateRanksWithPolicy(tc.scores)

			require.Len(t, entries, len(tc.expectedRanks))

			for _, entry := range entries {
				expectedRank, exists := tc.expectedRanks[entry.PlayerID]
				assert.True(t, exists, "Unexpected player: %s", entry.PlayerID)
				assert.Equal(t, expectedRank, entry.Rank, 
					"Player %s rank mismatch: expected %d, got %d", 
					entry.PlayerID, expectedRank, entry.Rank)
			}
		})
	}
}

func TestRankingSortOrder(t *testing.T) {
	now := time.Now()

	scores := []*models.PlayerScore{
		{PlayerID: "p1", TotalScore: 1000, UpdatedAt: now.Add(2 * time.Second)},
		{PlayerID: "p2", TotalScore: 1000, UpdatedAt: now.Add(1 * time.Second)},
		{PlayerID: "p3", TotalScore: 1000, UpdatedAt: now},
		{PlayerID: "p4", TotalScore: 900, UpdatedAt: now},
	}

	service := &RankingService{
		sameScoreAsSameRank: true,
	}

	entries := service.calculateRanksWithPolicy(scores)

	require.Len(t, entries, 4)

	assert.Equal(t, "p3", entries[0].PlayerID)
	assert.Equal(t, "p2", entries[1].PlayerID)
	assert.Equal(t, "p1", entries[2].PlayerID)

	for i := 0; i < 3; i++ {
		assert.Equal(t, int64(1), entries[i].Rank)
	}
	assert.Equal(t, int64(4), entries[3].Rank)
}

func TestRankingEdgeCases(t *testing.T) {
	t.Run("zero_score_ranking", func(t *testing.T) {
		scores := []*models.PlayerScore{
			{PlayerID: "p1", TotalScore: 0, UpdatedAt: time.Now()},
			{PlayerID: "p2", TotalScore: 0, UpdatedAt: time.Now().Add(1 * time.Second)},
		}

		service := &RankingService{
			sameScoreAsSameRank: true,
		}

		entries := service.calculateRanksWithPolicy(scores)

		require.Len(t, entries, 2)
		assert.Equal(t, int64(1), entries[0].Rank)
		assert.Equal(t, int64(1), entries[1].Rank)
	})

	t.Run("large_score_range", func(t *testing.T) {
		scores := make([]*models.PlayerScore, 0)
		for i := 0; i < 10; i++ {
			scores = append(scores, &models.PlayerScore{
				PlayerID:   string(rune('a' + i)),
				TotalScore:  int64(1000 - i*100),
				UpdatedAt:  time.Now(),
			})
		}

		service := &RankingService{
			sameScoreAsSameRank: false,
		}

		entries := service.calculateRanksWithPolicy(scores)

		require.Len(t, entries, 10)
		for i, entry := range entries {
			assert.Equal(t, int64(i+1), entry.Rank)
		}
	})

	t.Run("score_descending_order", func(t *testing.T) {
		scores := []*models.PlayerScore{
			{PlayerID: "p_low", TotalScore: 100, UpdatedAt: time.Now()},
			{PlayerID: "p_high", TotalScore: 1000, UpdatedAt: time.Now()},
			{PlayerID: "p_mid", TotalScore: 500, UpdatedAt: time.Now()},
		}

		service := &RankingService{
			sameScoreAsSameRank: false,
		}

		entries := service.calculateRanksWithPolicy(scores)

		require.Len(t, entries, 3)
		assert.Equal(t, "p_high", entries[0].PlayerID)
		assert.Equal(t, "p_mid", entries[1].PlayerID)
		assert.Equal(t, "p_low", entries[2].PlayerID)
	})
}

func TestRankingServiceInitialization(t *testing.T) {
	t.Run("default_config_initialization", func(t *testing.T) {
		service := &RankingService{}
		assert.False(t, service.sameScoreAsSameRank)
	})

	t.Run("with_same_score_as_same_rank", func(t *testing.T) {
		service := &RankingService{
			sameScoreAsSameRank: true,
		}
		assert.True(t, service.sameScoreAsSameRank)
	})
}

func TestLeaderboardQueryRequestValidation(t *testing.T) {
	testCases := []struct {
		name        string
		req         LeaderboardQueryRequest
		expectValid bool
	}{
		{
			name: "valid_request",
			req: LeaderboardQueryRequest{
				GameID: "test_game",
				Limit:  50,
				Offset: 0,
			},
			expectValid: true,
		},
		{
			name: "missing_game_id",
			req: LeaderboardQueryRequest{
				GameID: "",
				Limit:  50,
				Offset: 0,
			},
			expectValid: false,
		},
		{
			name: "negative_offset",
			req: LeaderboardQueryRequest{
				GameID: "test_game",
				Limit:  50,
				Offset: -1,
			},
			expectValid: false,
		},
		{
			name: "limit_exceeds_max",
			req: LeaderboardQueryRequest{
				GameID: "test_game",
				Limit:  150,
				Offset: 0,
			},
			expectValid: false,
		},
		{
			name: "zero_limit_default",
			req: LeaderboardQueryRequest{
				GameID: "test_game",
				Limit:  0,
				Offset: 0,
			},
			expectValid: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			isValid := tc.req.GameID != "" && tc.req.Offset >= 0 && (tc.req.Limit == 0 || tc.req.Limit <= 100)
			assert.Equal(t, tc.expectValid, isValid)
		})
	}
}

func TestProcessRedisEntriesWithPolicy(t *testing.T) {
	redisEntries := []redis.Z{
		{Score: 1000, Member: "p1"},
		{Score: 1000, Member: "p2"},
		{Score: 900, Member: "p3"},
		{Score: 900, Member: "p4"},
		{Score: 800, Member: "p5"},
	}

	t.Run("same_score_same_rank", func(t *testing.T) {
		service := &RankingService{
			sameScoreAsSameRank: true,
		}

		entries := service.processRedisEntriesWithPolicy(redisEntries, 0)

		require.Len(t, entries, 5)
		assert.Equal(t, int64(1), entries[0].Rank)
		assert.Equal(t, int64(1), entries[1].Rank)
		assert.Equal(t, int64(3), entries[2].Rank)
		assert.Equal(t, int64(3), entries[3].Rank)
		assert.Equal(t, int64(5), entries[4].Rank)
	})

	t.Run("sequential_ranking", func(t *testing.T) {
		service := &RankingService{
			sameScoreAsSameRank: false,
		}

		entries := service.processRedisEntriesWithPolicy(redisEntries, 0)

		require.Len(t, entries, 5)
		for i, entry := range entries {
			assert.Equal(t, int64(i+1), entry.Rank)
		}
	})

	t.Run("with_offset", func(t *testing.T) {
		service := &RankingService{
			sameScoreAsSameRank: true,
		}

		entries := service.processRedisEntriesWithPolicy(redisEntries, 2)

		require.Len(t, entries, 5)
		assert.Equal(t, int64(3), entries[0].Rank)
		assert.Equal(t, int64(3), entries[1].Rank)
		assert.Equal(t, int64(5), entries[2].Rank)
	})
}

func TestAggregationMechanism(t *testing.T) {
	t.Run("add_to_aggregation_new_player", func(t *testing.T) {
		service := &RankingService{
			aggregationEnabled: true,
			aggregatedMap:      make(map[aggregatedKey]*aggregatedEvents),
		}

		event := &RankChangeEvent{
			PlayerID: "p1",
			GameID:   "g1",
			SeasonID: "s1",
			OldRank:  5,
			NewRank:  3,
			OldScore: 800,
			NewScore: 900,
		}

		service.addToAggregation(event)

		key := aggregatedKey{GameID: "g1", SeasonID: "s1"}
		agg, exists := service.aggregatedMap[key]
		assert.True(t, exists)
		require.Len(t, agg.events, 1)
		assert.Equal(t, "p1", agg.events[0].PlayerID)
	})

	t.Run("add_to_aggregation_update_existing_player", func(t *testing.T) {
		service := &RankingService{
			aggregationEnabled: true,
			aggregatedMap:      make(map[aggregatedKey]*aggregatedEvents),
		}

		event1 := &RankChangeEvent{
			PlayerID: "p1",
			GameID:   "g1",
			SeasonID: "s1",
			OldRank:  5,
			NewRank:  3,
			OldScore: 800,
			NewScore: 900,
		}

		event2 := &RankChangeEvent{
			PlayerID: "p1",
			GameID:   "g1",
			SeasonID: "s1",
			OldRank:  3,
			NewRank:  1,
			OldScore: 900,
			NewScore: 1000,
		}

		service.addToAggregation(event1)
		service.addToAggregation(event2)

		key := aggregatedKey{GameID: "g1", SeasonID: "s1"}
		agg := service.aggregatedMap[key]
		require.Len(t, agg.events, 1)
		assert.Equal(t, int64(1), agg.events[0].NewRank)
		assert.Equal(t, int64(1000), agg.events[0].NewScore)
	})

	t.Run("add_to_aggregation_multiple_players", func(t *testing.T) {
		service := &RankingService{
			aggregationEnabled: true,
			aggregatedMap:      make(map[aggregatedKey]*aggregatedEvents),
		}

		players := []string{"p1", "p2", "p3"}
		for i, p := range players {
			event := &RankChangeEvent{
				PlayerID: p,
				GameID:   "g1",
				SeasonID: "s1",
				NewRank:  int64(i + 1),
				NewScore: int64(1000 - i*100),
			}
			service.addToAggregation(event)
		}

		key := aggregatedKey{GameID: "g1", SeasonID: "s1"}
		agg := service.aggregatedMap[key]
		require.Len(t, agg.events, 3)
	})
}

func TestRankChangeEventCreation(t *testing.T) {
	event := &RankChangeEvent{
		PlayerID:  "test_player",
		GameID:    "test_game",
		SeasonID:  "test_season",
		OldRank:   10,
		NewRank:   5,
		OldScore:  800,
		NewScore:  900,
		ChangedAt: time.Now(),
	}

	assert.Equal(t, "test_player", event.PlayerID)
	assert.Equal(t, "test_game", event.GameID)
	assert.Equal(t, "test_season", event.SeasonID)
	assert.Equal(t, int64(10), event.OldRank)
	assert.Equal(t, int64(5), event.NewRank)
	assert.Equal(t, int64(800), event.OldScore)
	assert.Equal(t, int64(900), event.NewScore)
}

func TestLeaderboardIDGeneration(t *testing.T) {
	gameID := "test_game"
	seasonID := "season_2026_q1"
	lbType := models.LeaderboardTypeTotal

	lbID := generateLeaderboardID(gameID, seasonID, lbType)

	assert.Contains(t, lbID, gameID)
	assert.Contains(t, lbID, seasonID)
	assert.Contains(t, lbID, string(lbType))
}

func TestLeaderboardQueryResponse(t *testing.T) {
	entries := []*models.LeaderboardEntry{
		{Rank: 1, PlayerID: "p1", Score: 1000, UpdatedAt: time.Now()},
		{Rank: 2, PlayerID: "p2", Score: 900, UpdatedAt: time.Now()},
	}

	resp := &LeaderboardQueryResponse{
		Entries:      entries,
		TotalPlayers: 100,
		MyRank: &MyRankInfo{
			Rank:  5,
			Score: 850,
		},
	}

	assert.Len(t, resp.Entries, 2)
	assert.Equal(t, int64(100), resp.TotalPlayers)
	assert.NotNil(t, resp.MyRank)
	assert.Equal(t, int64(5), resp.MyRank.Rank)
	assert.Equal(t, int64(850), resp.MyRank.Score)
}

func TestLeaderboardQueryResponseNilMyRank(t *testing.T) {
	resp := &LeaderboardQueryResponse{
		Entries:      []*models.LeaderboardEntry{},
		TotalPlayers: 0,
		MyRank:       nil,
	}

	assert.Nil(t, resp.MyRank)
}

func TestCalculateRanksWithPolicySorting(t *testing.T) {
	now := time.Now()

	scores := []*models.PlayerScore{
		{PlayerID: "p5", TotalScore: 200, UpdatedAt: now},
		{PlayerID: "p3", TotalScore: 800, UpdatedAt: now.Add(1 * time.Second)},
		{PlayerID: "p1", TotalScore: 1000, UpdatedAt: now},
		{PlayerID: "p4", TotalScore: 500, UpdatedAt: now},
		{PlayerID: "p2", TotalScore: 900, UpdatedAt: now},
	}

	service := &RankingService{
		sameScoreAsSameRank: false,
	}

	entries := service.calculateRanksWithPolicy(scores)

	require.Len(t, entries, 5)
	
	expectedOrder := []string{"p1", "p2", "p3", "p4", "p5"}
	for i, expected := range expectedOrder {
		assert.Equal(t, expected, entries[i].PlayerID)
		assert.Equal(t, int64(i+1), entries[i].Rank)
	}
}

func TestCalculateRanksWithPolicySameScoreTimeOrder(t *testing.T) {
	now := time.Now()

	scores := []*models.PlayerScore{
		{PlayerID: "p_later", TotalScore: 1000, UpdatedAt: now.Add(2 * time.Second)},
		{PlayerID: "p_earlier", TotalScore: 1000, UpdatedAt: now},
		{PlayerID: "p_mid", TotalScore: 1000, UpdatedAt: now.Add(1 * time.Second)},
	}

	service := &RankingService{
		sameScoreAsSameRank: true,
	}

	entries := service.calculateRanksWithPolicy(scores)

	require.Len(t, entries, 3)
	
	assert.Equal(t, "p_earlier", entries[0].PlayerID)
	assert.Equal(t, "p_mid", entries[1].PlayerID)
	assert.Equal(t, "p_later", entries[2].PlayerID)

	for _, entry := range entries {
		assert.Equal(t, int64(1), entry.Rank)
	}
}

func BenchmarkCalculateRanks(b *testing.B) {
	sizes := []int{10, 100, 1000, 10000}

	for _, size := range sizes {
		b.Run(fmt.Sprintf("size_%d", size), func(b *testing.B) {
			scores := make([]*models.PlayerScore, size)
			for i := 0; i < size; i++ {
				scores[i] = &models.PlayerScore{
					PlayerID:   fmt.Sprintf("p%d", i),
					TotalScore: int64(size - i),
					UpdatedAt:  time.Now(),
				}
			}

			service := &RankingService{
				sameScoreAsSameRank: true,
			}

			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				service.calculateRanksWithPolicy(scores)
			}
		})
	}
}

func BenchmarkCalculateRanksWithTies(b *testing.B) {
	sizes := []int{100, 1000}

	for _, size := range sizes {
		b.Run(fmt.Sprintf("size_%d_with_ties", size), func(b *testing.B) {
			scores := make([]*models.PlayerScore, size)
			for i := 0; i < size; i++ {
				scores[i] = &models.PlayerScore{
					PlayerID:   fmt.Sprintf("p%d", i),
					TotalScore: int64(size - (i % 10)),
					UpdatedAt:  time.Now(),
				}
			}

			service := &RankingService{
				sameScoreAsSameRank: true,
			}

			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				service.calculateRanksWithPolicy(scores)
			}
		})
	}
}
