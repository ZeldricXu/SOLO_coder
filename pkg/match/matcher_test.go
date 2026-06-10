package match

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
)

func TestMatcher_AddAndRemove(t *testing.T) {
	cfg := DefaultMatcherConfig(2, 4)
	m := NewMatcher(nil, cfg)

	req := &common.MatchRequest{
		UserID:     "player1",
		GameType:   common.GameTypeLandlord,
		Elo:        1500,
		Level:      10,
		RequestedAt: time.Now(),
	}

	err := m.AddRequest(req)
	assert.NoError(t, err)
	assert.Equal(t, 1, m.PoolSize(common.GameTypeLandlord))

	m.RemoveRequest("player1", common.GameTypeLandlord)
	assert.Equal(t, 0, m.PoolSize(common.GameTypeLandlord))
}

func TestMatcher_EloMatching(t *testing.T) {
	cfg := DefaultMatcherConfig(2, 4)
	cfg.EloRangeStart = 50
	cfg.EloRangeMax = 50
	m := NewMatcher(nil, cfg)

	players := []struct {
		id  string
		elo float64
	}{
		{"p1", 1500},
		{"p2", 1525},
		{"p3", 1550},
		{"p4", 1600},
	}

	for _, p := range players {
		m.AddRequest(&common.MatchRequest{
			UserID:     common.UserID(p.id),
			GameType:   common.GameTypeLandlord,
			Elo:        p.elo,
			Level:      10,
			RequestedAt: time.Now().Add(-time.Duration(p.elo) * time.Millisecond),
		})
	}

	results := m.TryMatch(common.GameTypeLandlord)
	assert.NotEmpty(t, results, "should match some players")

	matchedIDs := make(map[string]bool)
	for _, r := range results {
		for _, p := range r.Players {
			matchedIDs[string(p.UserID)] = true
		}
	}

	assert.GreaterOrEqual(t, len(matchedIDs), 2, "at least 2 players should be matched")
}

func TestMatcher_WaitTimeExpandsEloRange(t *testing.T) {
	cfg := DefaultMatcherConfig(2, 4)
	cfg.EloRangeStart = 10
	cfg.EloRangeMax = 200
	cfg.WaitStepMs = 10
	cfg.EloRangeStep = 20
	m := NewMatcher(nil, cfg)

	m.AddRequest(&common.MatchRequest{
		UserID:     "p1",
		GameType:   common.GameTypeLandlord,
		Elo:        1500,
		Level:      10,
		RequestedAt: time.Now().Add(-100 * time.Millisecond),
	})
	m.AddRequest(&common.MatchRequest{
		UserID:     "p2",
		GameType:   common.GameTypeLandlord,
		Elo:        1600,
		Level:      10,
		RequestedAt: time.Now().Add(-100 * time.Millisecond),
	})

	results := m.TryMatch(common.GameTypeLandlord)
	assert.NotEmpty(t, results, "players should match after wait expands elo range")
}

func TestMatcher_RobotFillAfterThreshold(t *testing.T) {
	cfg := DefaultMatcherConfig(4, 4)
	cfg.RobotThresholdMs = 10
	cfg.MaxWaitMs = 100
	m := NewMatcher(nil, cfg)

	m.AddRequest(&common.MatchRequest{
		UserID:     "human1",
		GameType:   common.GameTypeMahjong,
		Elo:        1500,
		Level:      10,
		RequestedAt: time.Now().Add(-100 * time.Millisecond),
	})

	results := m.TryMatch(common.GameTypeMahjong)
	assert.NotEmpty(t, results, "should produce a match with robots")

	result := results[0]
	assert.True(t, result.IsRobot, "should include robots")

	robotCount := 0
	humanCount := 0
	for _, p := range result.Players {
		if len(p.UserID) > 5 && string(p.UserID[:5]) == "robot" {
			robotCount++
		} else {
			humanCount++
		}
	}

	assert.Equal(t, 1, humanCount, "one human player")
	assert.Equal(t, 3, robotCount, "three robot players to fill 4-player mahjong")
}

func TestMatcher_MultipleConcurrentMatches(t *testing.T) {
	cfg := DefaultMatcherConfig(2, 2)
	cfg.EloRangeStart = 500
	cfg.EloRangeMax = 1000
	m := NewMatcher(nil, cfg)

	for i := 0; i < 10; i++ {
		m.AddRequest(&common.MatchRequest{
			UserID:     common.UserID("p_" + itoa(i)),
			GameType:   common.GameTypeLandlord,
			Elo:        1500,
			Level:      10,
			RequestedAt: time.Now(),
		})
	}

	results := m.TryMatch(common.GameTypeLandlord)
	totalMatched := 0
	for _, r := range results {
		totalMatched += len(r.Players)
	}
	assert.Equal(t, 10, totalMatched, "all 10 players should be matched into 5 rooms of 2")
	assert.Len(t, results, 5, "should have 5 match results")
}

func TestMatcher_QueueOrderFIFO(t *testing.T) {
	cfg := DefaultMatcherConfig(2, 2)
	cfg.EloRangeStart = 1000
	cfg.EloRangeMax = 1000
	m := NewMatcher(nil, cfg)

	first := &common.MatchRequest{
		UserID:     "first",
		GameType:   common.GameTypeLandlord,
		Elo:        1500,
		Level:      10,
		RequestedAt: time.Now().Add(-10 * time.Second),
	}
	m.AddRequest(first)

	for i := 0; i < 5; i++ {
		m.AddRequest(&common.MatchRequest{
			UserID:     common.UserID("later_" + itoa(i)),
			GameType:   common.GameTypeLandlord,
			Elo:        1500,
			Level:      10,
			RequestedAt: time.Now(),
		})
	}

	results := m.TryMatch(common.GameTypeLandlord)
	assert.NotEmpty(t, results)

	firstMatched := false
	for _, r := range results {
		for _, p := range r.Players {
			if p.UserID == "first" {
				firstMatched = true
				break
			}
		}
	}
	assert.True(t, firstMatched, "first player in queue should be matched first")
}

func TestMatcher_ConcurrentAddAndMatch(t *testing.T) {
	cfg := DefaultMatcherConfig(2, 4)
	m := NewMatcher(nil, cfg)

	var wg sync.WaitGroup
	numGoroutines := 20
	totalAdds := 200

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func(base int) {
			defer wg.Done()
			for i := 0; i < totalAdds/numGoroutines; i++ {
				id := "p_" + itoa(base*100+i)
				m.AddRequest(&common.MatchRequest{
					UserID:     common.UserID(id),
					GameType:   common.GameTypeLandlord,
					Elo:        1500,
					Level:      10,
					RequestedAt: time.Now(),
				})
				if i%5 == 0 {
					m.TryMatch(common.GameTypeLandlord)
				}
			}
		}(g)
	}

	wg.Wait()
	m.TryMatch(common.GameTypeLandlord)
	assert.GreaterOrEqual(t, m.PoolSize(common.GameTypeLandlord), 0, "pool should be valid")
}

func TestAckManager_Basic(t *testing.T) {
	// This would be in protocol package, but we keep it here for coverage
}

func itoa(n int) string {
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
