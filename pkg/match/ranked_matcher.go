package match

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"time"

	"github.com/studio/gameroom/pkg/common"
)

type RankConfig struct {
	Rank              common.RankTier
	EloMin            float64
	EloMax            float64
	BaseWaitMs        int64
	CrossRankWaitMs   int64
	DownhillOnly      bool
}

var defaultRankConfigs = map[common.RankTier]RankConfig{
	common.RankBronze: {
		Rank:            common.RankBronze,
		EloMin:          0,
		EloMax:          1200,
		BaseWaitMs:      3000,
		CrossRankWaitMs: 10000,
		DownhillOnly:    false,
	},
	common.RankSilver: {
		Rank:            common.RankSilver,
		EloMin:          1200,
		EloMax:          1400,
		BaseWaitMs:      4000,
		CrossRankWaitMs: 15000,
		DownhillOnly:    true,
	},
	common.RankGold: {
		Rank:            common.RankGold,
		EloMin:          1400,
		EloMax:          1600,
		BaseWaitMs:      5000,
		CrossRankWaitMs: 20000,
		DownhillOnly:    true,
	},
	common.RankDiamond: {
		Rank:            common.RankDiamond,
		EloMin:          1600,
		EloMax:          1800,
		BaseWaitMs:      6000,
		CrossRankWaitMs: 30000,
		DownhillOnly:    true,
	},
	common.RankMaster: {
		Rank:            common.RankMaster,
		EloMin:          1800,
		EloMax:          9999,
		BaseWaitMs:      8000,
		CrossRankWaitMs: 45000,
		DownhillOnly:    true,
	},
}

type RankedMatcher struct {
	matcher       *Matcher
	rankPools     map[common.RankTier][]*common.MatchRequest
	rankConfigs   map[common.RankTier]RankConfig
}

func NewRankedMatcher(matcher *Matcher) *RankedMatcher {
	return &RankedMatcher{
		matcher:     matcher,
		rankPools:   make(map[common.RankTier][]*common.MatchRequest),
		rankConfigs: defaultRankConfigs,
	}
}

func (rm *RankedMatcher) AddRequest(req *common.MatchRequest) error {
	if req.Rank == 0 {
		req.Rank = common.EloToRank(req.Elo)
	}
	if req.RequestedAt.IsZero() {
		req.RequestedAt = time.Now()
	}

	rm.matcher.mu.Lock()
	rm.rankPools[req.Rank] = append(rm.rankPools[req.Rank], req)
	rm.matcher.mu.Unlock()

	return rm.matcher.AddRequest(req)
}

func (rm *RankedMatcher) RemoveRequest(userID common.UserID, gameType common.GameType) {
	rm.matcher.mu.Lock()

	for rank := range rm.rankPools {
		pool := rm.rankPools[rank]
		for i, req := range pool {
			if req.UserID == userID {
				rm.rankPools[rank] = append(pool[:i], pool[i+1:]...)
				break
			}
		}
	}
	rm.matcher.removeFromMemory(userID, gameType)

	rm.matcher.mu.Unlock()

	if rm.matcher.redisClient != nil {
		ctx := context.Background()
		members, err := rm.matcher.redisClient.ZRange(ctx, rm.matcher.poolKey(gameType), 0, -1).Result()
		if err == nil {
			for _, member := range members {
				var req common.MatchRequest
				if json.Unmarshal([]byte(member), &req) == nil && req.UserID == userID {
					rm.matcher.redisClient.ZRem(ctx, rm.matcher.poolKey(gameType), member)
					break
				}
			}
		}
	}
	common.LogInfo("player %s removed from ranked match pool %s", userID, gameType)
}

func (rm *RankedMatcher) TryMatch(gameType common.GameType) []MatchResult {
	rm.matcher.mu.Lock()
	defer rm.matcher.mu.Unlock()

	allPlayers := rm.collectAllPlayers(gameType)
	if len(allPlayers) == 0 {
		return nil
	}

	results := make([]MatchResult, 0)
	matched := make(map[common.UserID]bool)

	rankOrder := []common.RankTier{
		common.RankMaster,
		common.RankDiamond,
		common.RankGold,
		common.RankSilver,
		common.RankBronze,
	}

	for _, rank := range rankOrder {
		pool := rm.rankPools[rank]
		if len(pool) == 0 {
			continue
		}

		unmatched := make([]*common.MatchRequest, 0, len(pool))
		for _, req := range pool {
			if matched[req.UserID] {
				continue
			}
			unmatched = append(unmatched, req)
		}

		if len(unmatched) >= rm.matcher.config.MinPlayers {
			groups := rm.groupBySameRank(unmatched, gameType)
			for _, group := range groups {
				if len(group) >= rm.matcher.config.MinPlayers {
					matchResult := rm.buildMatchResult(group, gameType, "same_rank")
					results = append(results, matchResult)
					for _, p := range group {
						matched[p.UserID] = true
					}
				}
			}
		}

		for _, req := range unmatched {
			if matched[req.UserID] {
				continue
			}

			waitMs := time.Since(req.RequestedAt).Milliseconds()
			crossRankRange := rm.getAllowedCrossRankRange(req.Rank, waitMs)

			if crossRankRange > 0 {
				crossGroup := rm.tryCrossRankMatch(req, allPlayers, matched, crossRankRange)
				if len(crossGroup) >= rm.matcher.config.MinPlayers {
					matchResult := rm.buildMatchResult(crossGroup, gameType, "cross_rank")
					results = append(results, matchResult)
					for _, p := range crossGroup {
						matched[p.UserID] = true
					}
				}
			}
		}
	}

	remainingPool := make([]*common.MatchRequest, 0)
	for rank := range rm.rankPools {
		pool := rm.rankPools[rank]
		newPool := make([]*common.MatchRequest, 0, len(pool))
		for _, req := range pool {
			if matched[req.UserID] {
				rm.matcher.removeFromMemory(req.UserID, gameType)
			} else {
				newPool = append(newPool, req)
				remainingPool = append(remainingPool, req)
			}
		}
		rm.rankPools[rank] = newPool
	}

	for _, req := range remainingPool {
		waitMs := time.Since(req.RequestedAt).Milliseconds()
		if waitMs >= int64(rm.matcher.config.RobotThresholdMs) && len(remainingPool) >= 1 {
			needRobots := rm.matcher.config.MaxPlayers - len(remainingPool)
			if needRobots > 0 {
				group := make([]*common.MatchRequest, len(remainingPool))
				copy(group, remainingPool)
				for i := 0; i < needRobots; i++ {
					robot := &common.MatchRequest{
						UserID:      common.UserID(fmt.Sprintf("robot_%s_%d", gameType, time.Now().UnixNano()+int64(i))),
						GameType:    gameType,
						Elo:         1500,
						Rank:        common.RankGold,
						Level:       1,
						RequestedAt: time.Now(),
					}
					group = append(group, robot)
				}
				matchResult := rm.buildMatchResult(group, gameType, "robot_fill")
				matchResult.IsRobot = true
				results = append(results, matchResult)
				for _, p := range remainingPool {
					rm.matcher.removeFromMemory(p.UserID, gameType)
					rank := p.Rank
					pool := rm.rankPools[rank]
					for i, rp := range pool {
						if rp.UserID == p.UserID {
							rm.rankPools[rank] = append(pool[:i], pool[i+1:]...)
							break
						}
					}
				}
				break
			}
		}
	}

	return results
}

func (rm *RankedMatcher) collectAllPlayers(gameType common.GameType) []*common.MatchRequest {
	all := make([]*common.MatchRequest, 0)
	for rank := range rm.rankPools {
		all = append(all, rm.rankPools[rank]...)
	}
	return all
}

func (rm *RankedMatcher) groupBySameRank(players []*common.MatchRequest, gameType common.GameType) [][]*common.MatchRequest {
	groups := make([][]*common.MatchRequest, 0)
	current := make([]*common.MatchRequest, 0, rm.matcher.config.MaxPlayers)

	for _, req := range players {
		if len(current) >= rm.matcher.config.MaxPlayers {
			groups = append(groups, current)
			current = make([]*common.MatchRequest, 0, rm.matcher.config.MaxPlayers)
		}

		if len(current) == 0 {
			current = append(current, req)
			continue
		}

		avgElo := rm.averageElo(current)
		if math.Abs(req.Elo-avgElo) <= float64(rm.matcher.config.EloRangeStart) {
			current = append(current, req)
		} else if len(current) >= rm.matcher.config.MinPlayers {
			groups = append(groups, current)
			current = []*common.MatchRequest{req}
		}
	}

	if len(current) >= rm.matcher.config.MinPlayers {
		groups = append(groups, current)
	}

	return groups
}

func (rm *RankedMatcher) getAllowedCrossRankRange(rank common.RankTier, waitMs int64) int {
	cfg := rm.rankConfigs[rank]

	waitSteps := waitMs / cfg.BaseWaitMs
	rangeExpansion := int(waitSteps)

	if rangeExpansion > 4 {
		rangeExpansion = 4
	}

	return rangeExpansion
}

func (rm *RankedMatcher) tryCrossRankMatch(
	req *common.MatchRequest,
	allPlayers []*common.MatchRequest,
	matched map[common.UserID]bool,
	maxRange int,
) []*common.MatchRequest {

	cfg := rm.rankConfigs[req.Rank]
	group := []*common.MatchRequest{req}

	for _, other := range allPlayers {
		if matched[other.UserID] || other.UserID == req.UserID {
			continue
		}

		rankDiff := int(req.Rank) - int(other.Rank)
		if cfg.DownhillOnly && rankDiff < 0 {
			continue
		}

		rankDiffAbs := rankDiff
		if rankDiffAbs < 0 {
			rankDiffAbs = -rankDiffAbs
		}
		if rankDiffAbs > maxRange {
			continue
		}

		waitMs := time.Since(other.RequestedAt).Milliseconds()
		otherCfg := rm.rankConfigs[other.Rank]
		if waitMs < otherCfg.CrossRankWaitMs {
			continue
		}

		avgElo := rm.averageElo(group)
		if math.Abs(other.Elo-avgElo) <= float64(rm.matcher.config.EloRangeMax) {
			group = append(group, other)
			if len(group) >= rm.matcher.config.MaxPlayers {
				break
			}
		}
	}

	if len(group) >= rm.matcher.config.MinPlayers {
		return group
	}
	return nil
}

func (rm *RankedMatcher) buildMatchResult(players []*common.MatchRequest, gameType common.GameType, reason string) MatchResult {
	return MatchResult{
		GameType: gameType,
		Players:  players,
		IsRobot:  false,
		Reason:   reason,
	}
}

func (rm *RankedMatcher) averageElo(group []*common.MatchRequest) float64 {
	if len(group) == 0 {
		return 0
	}
	sum := 0.0
	for _, r := range group {
		sum += r.Elo
	}
	return sum / float64(len(group))
}

func (rm *RankedMatcher) PoolSize(gameType common.GameType, rank common.RankTier) int {
	rm.matcher.mu.RLock()
	defer rm.matcher.mu.RUnlock()
	return len(rm.rankPools[rank])
}

func (rm *RankedMatcher) TotalPoolSize(gameType common.GameType) int {
	rm.matcher.mu.RLock()
	defer rm.matcher.mu.RUnlock()
	total := 0
	for rank := range rm.rankPools {
		total += len(rm.rankPools[rank])
	}
	return total
}

func (rm *RankedMatcher) StartTicker(gameTypes []common.GameType, interval time.Duration, shutdownCh <-chan struct{}) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-shutdownCh:
				return
			case <-ticker.C:
				for _, gt := range gameTypes {
					results := rm.TryMatch(gt)
					for _, r := range results {
						select {
						case rm.matcher.notifyCh <- r:
						default:
							common.LogWarn("ranked match notify channel full, dropping result for %s", gt)
						}
					}
				}
			}
		}
	}()
}
