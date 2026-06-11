package match

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
)

type Pool struct {
	GameType common.GameType
	Requests []*common.MatchRequest
}

type Matcher struct {
	redisClient *redis.Client
	config      *MatcherConfig
	pools       map[common.GameType][]*common.MatchRequest
	notifyCh    chan MatchResult
	mu          sync.RWMutex
}

type MatcherConfig struct {
	EloRangeStart    int
	EloRangeMax      int
	EloRangeStep     int
	WaitStepMs       int
	MaxWaitMs        int
	RobotThresholdMs int
	MinPlayers       int
	MaxPlayers       int
}

type MatchResult struct {
	GameType common.GameType
	Players  []*common.MatchRequest
	IsRobot  bool
	Reason   string
}

func DefaultMatcherConfig(min, max int) *MatcherConfig {
	return &MatcherConfig{
		EloRangeStart:    50,
		EloRangeMax:      500,
		EloRangeStep:     50,
		WaitStepMs:       3000,
		MaxWaitMs:        60000,
		RobotThresholdMs: 20000,
		MinPlayers:       min,
		MaxPlayers:       max,
	}
}

func NewMatcher(redisClient *redis.Client, config *MatcherConfig) *Matcher {
	if config == nil {
		config = DefaultMatcherConfig(3, 4)
	}
	return &Matcher{
		redisClient: redisClient,
		config:      config,
		pools:       make(map[common.GameType][]*common.MatchRequest),
		notifyCh:    make(chan MatchResult, 1024),
	}
}

func (m *Matcher) poolKey(gameType common.GameType) string {
	return fmt.Sprintf("match:pool:%s", gameType)
}

func (m *Matcher) AddRequest(req *common.MatchRequest) error {
	if req.RequestedAt.IsZero() {
		req.RequestedAt = time.Now()
	}
	data, err := json.Marshal(req)
	if err != nil {
		return err
	}

	if m.redisClient != nil {
		ctx := context.Background()
		score := float64(req.RequestedAt.UnixMilli())
		err = m.redisClient.ZAdd(ctx, m.poolKey(req.GameType), &redis.Z{
			Score:  score,
			Member: string(data),
		}).Err()
		if err != nil {
			common.LogWarn("redis add match request failed: %v, fallback to memory", err)
		}
	}

	m.mu.Lock()
	m.pools[req.GameType] = append(m.pools[req.GameType], req)
	m.mu.Unlock()
	common.LogInfo("player %s joined match pool for %s, elo=%.2f", req.UserID, req.GameType, req.Elo)
	return nil
}

func (m *Matcher) RemoveRequest(userID common.UserID, gameType common.GameType) {
	m.mu.Lock()
	m.removeFromMemory(userID, gameType)
	m.mu.Unlock()
	if m.redisClient != nil {
		ctx := context.Background()
		members, err := m.redisClient.ZRange(ctx, m.poolKey(gameType), 0, -1).Result()
		if err == nil {
			for _, member := range members {
				var req common.MatchRequest
				if json.Unmarshal([]byte(member), &req) == nil && req.UserID == userID {
					m.redisClient.ZRem(ctx, m.poolKey(gameType), member)
					break
				}
			}
		}
	}
	common.LogInfo("player %s removed from match pool %s", userID, gameType)
}

func (m *Matcher) removeFromMemory(userID common.UserID, gameType common.GameType) {
	pool := m.pools[gameType]
	for i, req := range pool {
		if req.UserID == userID {
			m.pools[gameType] = append(pool[:i], pool[i+1:]...)
			break
		}
	}
}

func (m *Matcher) TryMatch(gameType common.GameType) []MatchResult {
	m.mu.Lock()
	defer m.mu.Unlock()

	results := make([]MatchResult, 0)
	pool := m.pools[gameType]
	if len(pool) == 0 {
		return results
	}

	remaining := make([]*common.MatchRequest, 0, len(pool))
	matchedGroups := make([][]*common.MatchRequest, 0)

	for _, req := range pool {
		waitMs := time.Since(req.RequestedAt).Milliseconds()
		currentEloRange := m.getCurrentEloRange(waitMs)

		matched := false
		for i, group := range matchedGroups {
			if len(group) >= m.config.MaxPlayers {
				continue
			}
			avgElo := m.averageElo(group)
			if abs(req.Elo-avgElo) <= float64(currentEloRange) {
				matchedGroups[i] = append(group, req)
				matched = true
				break
			}
		}

		if !matched {
			newGroup := []*common.MatchRequest{req}
			matchedGroups = append(matchedGroups, newGroup)
		}
	}

	for _, group := range matchedGroups {
		groupSize := len(group)
		needRobots := m.config.MaxPlayers - groupSize

		maxWait := int64(0)
		for _, req := range group {
			w := time.Since(req.RequestedAt).Milliseconds()
			if w > maxWait {
				maxWait = w
			}
		}

		canMatch := false
		result := MatchResult{
			GameType: gameType,
			Players:  make([]*common.MatchRequest, groupSize),
			IsRobot:  false,
			Reason:   "matched",
		}
		copy(result.Players, group)

		if groupSize >= m.config.MinPlayers {
			canMatch = true
			if needRobots > 0 && maxWait >= int64(m.config.RobotThresholdMs) {
				for i := 0; i < needRobots; i++ {
					robot := &common.MatchRequest{
						UserID:      common.UserID(fmt.Sprintf("robot_%s_%d", gameType, time.Now().UnixNano()+int64(i))),
						GameType:    gameType,
						Elo:         m.averageElo(group),
						Level:       1,
						RequestedAt: time.Now(),
						Priority:    0,
					}
					result.Players = append(result.Players, robot)
				}
				result.IsRobot = true
				result.Reason = "matched_with_robots"
			}
		} else if groupSize > 0 && maxWait >= int64(m.config.RobotThresholdMs) && needRobots > 0 {
			canMatch = true
			robotCount := m.config.MaxPlayers - groupSize
			for i := 0; i < robotCount; i++ {
				robot := &common.MatchRequest{
					UserID:      common.UserID(fmt.Sprintf("robot_%s_%d", gameType, time.Now().UnixNano()+int64(i))),
					GameType:    gameType,
					Elo:         m.averageElo(group),
					Level:       1,
					RequestedAt: time.Now(),
					Priority:    0,
				}
				result.Players = append(result.Players, robot)
			}
			result.IsRobot = true
			result.Reason = "robot_fill_timeout"
		}

		if canMatch && len(result.Players) >= m.config.MinPlayers {
			for _, req := range group {
				m.removeFromMemory(req.UserID, gameType)
			}
			results = append(results, result)
		} else {
			remaining = append(remaining, group...)
		}
	}

	m.pools[gameType] = remaining
	return results
}

func (m *Matcher) getCurrentEloRange(waitMs int64) int {
	steps := int(waitMs / int64(m.config.WaitStepMs))
	eloRange := m.config.EloRangeStart + steps*m.config.EloRangeStep
	if eloRange > m.config.EloRangeMax {
		eloRange = m.config.EloRangeMax
	}
	return eloRange
}

func (m *Matcher) averageElo(group []*common.MatchRequest) float64 {
	if len(group) == 0 {
		return 0
	}
	sum := 0.0
	for _, r := range group {
		sum += r.Elo
	}
	return sum / float64(len(group))
}

func abs(x float64) float64 {
	if x < 0 {
		return -x
	}
	return x
}

func (m *Matcher) CheckTimeouts(gameType common.GameType) []MatchResult {
	results := make([]MatchResult, 0)
	pool := m.pools[gameType]
	remaining := make([]*common.MatchRequest, 0)

	groups := make(map[common.UserID][]*common.MatchRequest)

	for _, req := range pool {
		waitMs := time.Since(req.RequestedAt).Milliseconds()
		if waitMs >= int64(m.config.MaxWaitMs) {
			remaining = append(remaining, req)
			continue
		}
		if waitMs >= int64(m.config.RobotThresholdMs) {
			remaining = append(remaining, req)
			continue
		}
		remaining = append(remaining, req)
	}
	_ = groups

	m.pools[gameType] = remaining
	return results
}

func (m *Matcher) NotifyChannel() <-chan MatchResult {
	return m.notifyCh
}

func (m *Matcher) PoolSize(gameType common.GameType) int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.pools[gameType])
}

func (m *Matcher) StartTicker(gameTypes []common.GameType, interval time.Duration, shutdownCh <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-shutdownCh:
				return
			case <-ticker.C:
				for _, gt := range gameTypes {
					results := m.TryMatch(gt)
					for _, r := range results {
						select {
						case m.notifyCh <- r:
						default:
							common.LogWarn("match notify channel full, dropping result for %s", gt)
						}
					}
				}
			}
		}
	}()
}

func CalculateElo(winnerElo, loserElo float64, k float64) (float64, float64) {
	expectedWinner := 1.0 / (1.0 + pow10((loserElo-winnerElo)/400.0))
	expectedLoser := 1.0 / (1.0 + pow10((winnerElo-loserElo)/400.0))
	newWinner := winnerElo + k*(1.0-expectedWinner)
	newLoser := loserElo + k*(0.0-expectedLoser)
	return newWinner, newLoser
}

func pow10(x float64) float64 {
	result := 1.0
	for i := 0; i < int(x); i++ {
		result *= 10
	}
	return result
}
