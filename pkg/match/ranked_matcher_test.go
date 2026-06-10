package match

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/testutil"
)

func TestEloToRank(t *testing.T) {
	tests := []struct {
		elo      float64
		expected common.RankTier
	}{
		{1000, common.RankBronze},
		{1199, common.RankBronze},
		{1200, common.RankSilver},
		{1399, common.RankSilver},
		{1400, common.RankGold},
		{1599, common.RankGold},
		{1600, common.RankDiamond},
		{1799, common.RankDiamond},
		{1800, common.RankMaster},
		{2000, common.RankMaster},
	}

	for _, tt := range tests {
		t.Run(tt.expected.String(), func(t *testing.T) {
			assert.Equal(t, tt.expected, common.EloToRank(tt.elo))
		})
	}
}

func TestRankTierString(t *testing.T) {
	tests := []struct {
		rank     common.RankTier
		expected string
	}{
		{common.RankBronze, "bronze"},
		{common.RankSilver, "silver"},
		{common.RankGold, "gold"},
		{common.RankDiamond, "diamond"},
		{common.RankMaster, "master"},
		{common.RankTier(0), "unknown"},
	}

	for _, tt := range tests {
		t.Run(tt.expected, func(t *testing.T) {
			assert.Equal(t, tt.expected, tt.rank.String())
		})
	}
}

func TestRankedMatcher_SameRankMatch(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      200,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	req1 := f.Gold("gold_1", common.GameTypeLandlord)
	req2 := f.Gold("gold_2", common.GameTypeLandlord)
	req3 := f.Gold("gold_3", common.GameTypeLandlord)

	err := rm.AddRequest(req1)
	assert.NoError(t, err)
	err = rm.AddRequest(req2)
	assert.NoError(t, err)
	err = rm.AddRequest(req3)
	assert.NoError(t, err)

	assert.Equal(t, 3, rm.PoolSize(common.GameTypeLandlord, common.RankGold))
	assert.Equal(t, 3, rm.TotalPoolSize(common.GameTypeLandlord))

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Len(t, results, 1)
	assert.Equal(t, "same_rank", results[0].Reason)
	assert.Len(t, results[0].Players, 3)
	assert.False(t, results[0].IsRobot)

	assert.Equal(t, 0, rm.PoolSize(common.GameTypeLandlord, common.RankGold))
	assert.Equal(t, 0, rm.TotalPoolSize(common.GameTypeLandlord))
}

func TestRankedMatcher_CrossRankMatch(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      500,
		EloRangeStep:     50,
		WaitStepMs:       1000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	now := time.Now()

	goldReq := f.WithRequestedAt(
		f.Gold("gold_1", common.GameTypeLandlord),
		now.Add(-30*time.Second),
	)
	silverReq := f.WithRequestedAt(
		f.Silver("silver_1", common.GameTypeLandlord),
		now.Add(-20*time.Second),
	)
	silverReq2 := f.WithRequestedAt(
		f.Silver("silver_2", common.GameTypeLandlord),
		now.Add(-20*time.Second),
	)

	err := rm.AddRequest(goldReq)
	assert.NoError(t, err)
	err = rm.AddRequest(silverReq)
	assert.NoError(t, err)
	err = rm.AddRequest(silverReq2)
	assert.NoError(t, err)

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Len(t, results, 1)
	assert.Equal(t, "cross_rank", results[0].Reason)
	assert.Len(t, results[0].Players, 3)
}

func TestRankedMatcher_MixedRanksNoCrossTooEarly(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      200,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	now := time.Now()

	goldReq := f.WithRequestedAt(
		f.Gold("gold_1", common.GameTypeLandlord),
		now.Add(-2*time.Second),
	)
	silverReq := f.WithRequestedAt(
		f.Silver("silver_1", common.GameTypeLandlord),
		now.Add(-1*time.Second),
	)
	silverReq2 := f.WithRequestedAt(
		f.Silver("silver_2", common.GameTypeLandlord),
		now.Add(-1*time.Second),
	)

	err := rm.AddRequest(goldReq)
	assert.NoError(t, err)
	err = rm.AddRequest(silverReq)
	assert.NoError(t, err)
	err = rm.AddRequest(silverReq2)
	assert.NoError(t, err)

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Empty(t, results)

	assert.Equal(t, 1, rm.PoolSize(common.GameTypeLandlord, common.RankGold))
	assert.Equal(t, 2, rm.PoolSize(common.GameTypeLandlord, common.RankSilver))
}

func TestRankedMatcher_HighRankNoDownhillToLow(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       2,
		MaxPlayers:       2,
		EloRangeStart:    50,
		EloRangeMax:      1000,
		EloRangeStep:     50,
		WaitStepMs:       100,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	now := time.Now()

	masterReq := f.WithRequestedAt(
		f.Master("master_1", common.GameTypeLandlord),
		now.Add(-5*time.Second),
	)
	silverReq := f.WithRequestedAt(
		f.Silver("silver_1", common.GameTypeLandlord),
		now.Add(-5*time.Second),
	)

	err := rm.AddRequest(silverReq)
	assert.NoError(t, err)
	err = rm.AddRequest(masterReq)
	assert.NoError(t, err)

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Empty(t, results)
}

func TestRankedMatcher_RobotFill(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      200,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 5000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	now := time.Now()

	goldReq := f.WithRequestedAt(
		f.Gold("gold_1", common.GameTypeLandlord),
		now.Add(-10*time.Second),
	)

	err := rm.AddRequest(goldReq)
	assert.NoError(t, err)

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Len(t, results, 1)
	assert.True(t, results[0].IsRobot)
	assert.Equal(t, "robot_fill", results[0].Reason)
	assert.Len(t, results[0].Players, 3)

	robotCount := 0
	for _, p := range results[0].Players {
		if len(p.UserID) > 5 && string(p.UserID[:5]) == "robot" {
			robotCount++
		}
	}
	assert.Equal(t, 2, robotCount)
}

func TestRankedMatcher_RankBucketsSeparation(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       2,
		MaxPlayers:       2,
		EloRangeStart:    50,
		EloRangeMax:      100,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	bronze1 := f.Bronze("bronze_1", common.GameTypeLandlord)
	bronze2 := f.Bronze("bronze_2", common.GameTypeLandlord)
	gold1 := f.Gold("gold_1", common.GameTypeLandlord)
	gold2 := f.Gold("gold_2", common.GameTypeLandlord)

	err := rm.AddRequest(bronze1)
	assert.NoError(t, err)
	err = rm.AddRequest(gold1)
	assert.NoError(t, err)
	err = rm.AddRequest(bronze2)
	assert.NoError(t, err)
	err = rm.AddRequest(gold2)
	assert.NoError(t, err)

	assert.Equal(t, 2, rm.PoolSize(common.GameTypeLandlord, common.RankBronze))
	assert.Equal(t, 2, rm.PoolSize(common.GameTypeLandlord, common.RankGold))
	assert.Equal(t, 4, rm.TotalPoolSize(common.GameTypeLandlord))

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Len(t, results, 2)

	playerRanks := make(map[common.UserID]common.RankTier)
	for _, r := range results {
		for _, p := range r.Players {
			playerRanks[p.UserID] = p.Rank
		}
	}

	assert.Equal(t, common.RankBronze, playerRanks["bronze_1"])
	assert.Equal(t, common.RankBronze, playerRanks["bronze_2"])
	assert.Equal(t, common.RankGold, playerRanks["gold_1"])
	assert.Equal(t, common.RankGold, playerRanks["gold_2"])
}

func TestRankedMatcher_RemoveRequest(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      200,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)
	f := testutil.NewMatchRequestFactory()

	gold1 := f.Gold("gold_1", common.GameTypeLandlord)
	gold2 := f.Gold("gold_2", common.GameTypeLandlord)

	err := rm.AddRequest(gold1)
	assert.NoError(t, err)
	err = rm.AddRequest(gold2)
	assert.NoError(t, err)

	assert.Equal(t, 2, rm.PoolSize(common.GameTypeLandlord, common.RankGold))

	rm.RemoveRequest("gold_1", common.GameTypeLandlord)

	assert.Equal(t, 1, rm.PoolSize(common.GameTypeLandlord, common.RankGold))

	results := rm.TryMatch(common.GameTypeLandlord)
	assert.Empty(t, results)
}

func TestRankedMatcher_GetAllowedCrossRankRange(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      200,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)

	tests := []struct {
		waitMs   int64
		expected int
	}{
		{0, 0},
		{4999, 0},
		{5000, 1},
		{9999, 1},
		{10000, 2},
		{14999, 2},
		{15000, 3},
		{19999, 3},
		{20000, 4},
		{100000, 4},
	}

	for _, tt := range tests {
		t.Run(itoa64(tt.waitMs), func(t *testing.T) {
			rangeVal := rm.getAllowedCrossRankRange(common.RankGold, tt.waitMs)
			assert.Equal(t, tt.expected, rangeVal)
		})
	}
}

func TestRankedMatcher_AutoRankAssignment(t *testing.T) {
	cfg := &MatcherConfig{
		MinPlayers:       3,
		MaxPlayers:       3,
		EloRangeStart:    50,
		EloRangeMax:      200,
		EloRangeStep:     50,
		WaitStepMs:       5000,
		MaxWaitMs:        30000,
		RobotThresholdMs: 60000,
	}

	baseMatcher := NewMatcher(nil, cfg)
	rm := NewRankedMatcher(baseMatcher)

	req := &common.MatchRequest{
		UserID:   "player_1",
		GameType: common.GameTypeLandlord,
		Elo:      1550,
		Level:    10,
	}

	err := rm.AddRequest(req)
	assert.NoError(t, err)

	assert.Equal(t, common.RankGold, req.Rank)
	assert.Equal(t, 1, rm.PoolSize(common.GameTypeLandlord, common.RankGold))
}

func itoa64(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
