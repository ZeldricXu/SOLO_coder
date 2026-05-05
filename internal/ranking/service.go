package ranking

import (
	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/models"
	"GameLeaderboard/internal/push"
	"GameLeaderboard/internal/storage"
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
)

type RankChangeEvent struct {
	PlayerID    string    `json:"player_id"`
	GameID      string    `json:"game_id"`
	SeasonID    string    `json:"season_id"`
	OldRank     int64     `json:"old_rank"`
	NewRank     int64     `json:"new_rank"`
	OldScore    int64     `json:"old_score"`
	NewScore    int64     `json:"new_score"`
	ChangedAt   time.Time `json:"changed_at"`
}

type LeaderboardQueryRequest struct {
	GameID      string                  `form:"game_id" binding:"required"`
	SeasonID    string                  `form:"season_id"`
	Type        models.LeaderboardType  `form:"type"`
	PlayerID    string                  `form:"player_id"`
	Limit       int64                   `form:"limit"`
	Offset      int64                   `form:"offset"`
}

type LeaderboardQueryResponse struct {
	Entries      []*models.LeaderboardEntry `json:"entries"`
	TotalPlayers int64                       `json:"total_players"`
	MyRank       *MyRankInfo                 `json:"my_rank,omitempty"`
}

type MyRankInfo struct {
	Rank      int64 `json:"rank"`
	Score     int64 `json:"score"`
}

type PlayerWithScore struct {
	PlayerID  string
	Score     int64
	UpdatedAt time.Time
}

type aggregatedKey struct {
	GameID   string
	SeasonID string
}

type aggregatedEvents struct {
	events   []*RankChangeEvent
	lastPush time.Time
}

type RankingService struct {
	mysqlStore   *storage.MySQLStore
	redisStore   *storage.RedisStore
	pushService  *push.PushService
	rankEventCh  chan *RankChangeEvent
	wg           sync.WaitGroup
	stopCh       chan struct{}

	aggregationEnabled bool
	aggregationWindow  time.Duration
	aggregatedMap      map[aggregatedKey]*aggregatedEvents
	aggregatedMu       sync.Mutex

	sameScoreAsSameRank bool
}

func NewRankingServiceWithConfig(
	mysqlStore *storage.MySQLStore,
	redisStore *storage.RedisStore,
	pushService *push.PushService,
	cfg *config.RankingConfig,
) *RankingService {
	if cfg == nil {
		cfg = &config.RankingConfig{
			IncrementalUpdate:     true,
			PushAggregationEnabled: true,
			PushAggregationWindow:  time.Second,
			SameScoreAsSameRank:    true,
		}
	}

	service := &RankingService{
		mysqlStore:           mysqlStore,
		redisStore:           redisStore,
		pushService:          pushService,
		rankEventCh:          make(chan *RankChangeEvent, 10000),
		stopCh:               make(chan struct{}),
		aggregationEnabled:   cfg.PushAggregationEnabled,
		aggregationWindow:    cfg.PushAggregationWindow,
		aggregatedMap:        make(map[aggregatedKey]*aggregatedEvents),
		sameScoreAsSameRank:  cfg.SameScoreAsSameRank,
	}

	service.wg.Add(1)
	go service.processRankEvents()

	if service.aggregationEnabled {
		service.wg.Add(1)
		go service.processAggregatedEvents()
	}

	return service
}

func NewRankingService(
	mysqlStore *storage.MySQLStore,
	redisStore *storage.RedisStore,
	pushService *push.PushService,
) *RankingService {
	return NewRankingServiceWithConfig(mysqlStore, redisStore, pushService, nil)
}

func (s *RankingService) processRankEvents() {
	defer s.wg.Done()

	for {
		select {
		case event, ok := <-s.rankEventCh:
			if !ok {
				return
			}
			if s.aggregationEnabled {
				s.addToAggregation(event)
			} else {
				s.broadcastRankChange(event)
			}
		case <-s.stopCh:
			return
		}
	}
}

func (s *RankingService) addToAggregation(event *RankChangeEvent) {
	key := aggregatedKey{
		GameID:   event.GameID,
		SeasonID: event.SeasonID,
	}

	s.aggregatedMu.Lock()
	defer s.aggregatedMu.Unlock()

	agg, exists := s.aggregatedMap[key]
	if !exists {
		agg = &aggregatedEvents{
			events:   make([]*RankChangeEvent, 0),
			lastPush: time.Now(),
		}
		s.aggregatedMap[key] = agg
	}

	existingIdx := -1
	for i, e := range agg.events {
		if e.PlayerID == event.PlayerID {
			existingIdx = i
			break
		}
	}

	if existingIdx >= 0 {
		agg.events[existingIdx].NewRank = event.NewRank
		agg.events[existingIdx].NewScore = event.NewScore
		agg.events[existingIdx].ChangedAt = event.ChangedAt
	} else {
		agg.events = append(agg.events, event)
	}
}

func (s *RankingService) processAggregatedEvents() {
	defer s.wg.Done()

	ticker := time.NewTicker(s.aggregationWindow)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			s.flushAggregatedEvents()
		case <-s.stopCh:
			s.flushAggregatedEvents()
			return
		}
	}
}

func (s *RankingService) flushAggregatedEvents() {
	s.aggregatedMu.Lock()
	defer s.aggregatedMu.Unlock()

	for key, agg := range s.aggregatedMap {
		if len(agg.events) == 0 {
			continue
		}

		if s.pushService != nil {
			eventData := make([]map[string]interface{}, 0, len(agg.events))
			for _, e := range agg.events {
				eventData = append(eventData, map[string]interface{}{
					"player_id": e.PlayerID,
					"old_rank":  e.OldRank,
					"new_rank":  e.NewRank,
					"old_score": e.OldScore,
					"new_score": e.NewScore,
				})
			}

			broadcastMsg := &push.BroadcastMessage{
				GameID:    key.GameID,
				SeasonID:  key.SeasonID,
				Type:      "batch_rank_change",
				Timestamp: time.Now(),
				Data: map[string]interface{}{
					"events": eventData,
					"count":  len(agg.events),
				},
			}
			s.pushService.BroadcastToGame(key.GameID, broadcastMsg)
		}

		for _, event := range agg.events {
			message, err := json.Marshal(event)
			if err == nil {
				s.redisStore.PublishRankChange(event.GameID, event.SeasonID, string(message))
			}
		}

		agg.events = make([]*RankChangeEvent, 0)
		agg.lastPush = time.Now()
	}
}

func (s *RankingService) HandleRankChange(event *RankChangeEvent) {
	select {
	case s.rankEventCh <- event:
	default:
	}
}

func (s *RankingService) broadcastRankChange(event *RankChangeEvent) {
	message, err := json.Marshal(event)
	if err != nil {
		return
	}

	s.redisStore.PublishRankChange(event.GameID, event.SeasonID, string(message))

	if s.pushService != nil {
		broadcastMsg := &push.BroadcastMessage{
			GameID:    event.GameID,
			SeasonID:  event.SeasonID,
			Type:      "rank_change",
			Timestamp: event.ChangedAt,
			Data: map[string]interface{}{
				"player_id": event.PlayerID,
				"old_rank":  event.OldRank,
				"new_rank":  event.NewRank,
				"old_score": event.OldScore,
				"new_score": event.NewScore,
			},
		}
		s.pushService.BroadcastToGame(event.GameID, broadcastMsg)
	}
}

func (s *RankingService) UpdatePlayerScoreIncremental(gameID, seasonID, playerID string, scoreChange int64) (*RankChangeEvent, error) {
	oldScore, err := s.redisStore.GetPlayerScore(gameID, seasonID, playerID)
	if err != nil {
		return nil, fmt.Errorf("failed to get old score: %w", err)
	}

	oldRank, err := s.redisStore.GetPlayerRank(gameID, seasonID, playerID)
	if err != nil {
		return nil, fmt.Errorf("failed to get old rank: %w", err)
	}

	newScore := oldScore + scoreChange

	err = s.redisStore.UpdatePlayerScore(gameID, seasonID, playerID, newScore)
	if err != nil {
		return nil, fmt.Errorf("failed to update redis score: %w", err)
	}

	newRank, err := s.redisStore.GetPlayerRank(gameID, seasonID, playerID)
	if err != nil {
		return nil, fmt.Errorf("failed to get new rank: %w", err)
	}

	if oldRank != newRank || oldScore != newScore {
		return &RankChangeEvent{
			PlayerID:  playerID,
			GameID:    gameID,
			SeasonID:  seasonID,
			OldRank:   oldRank,
			NewRank:   newRank,
			OldScore:  oldScore,
			NewScore:  newScore,
			ChangedAt: time.Now(),
		}, nil
	}

	return nil, nil
}

func (s *RankingService) CalculateLeaderboardSnapshot(gameID, seasonID string, lbType models.LeaderboardType) error {
	if seasonID == "" {
		season, err := s.mysqlStore.GetActiveSeason(gameID)
		if err != nil {
			return err
		}
		if season == nil {
			return fmt.Errorf("no active season found")
		}
		seasonID = season.SeasonID
	}

	scores, err := s.mysqlStore.GetTopPlayerScores(gameID, seasonID, 10000)
	if err != nil {
		return err
	}

	entries := s.calculateRanksWithPolicy(scores)

	totalPlayers, err := s.mysqlStore.CountPlayersInSeason(gameID, seasonID)
	if err != nil {
		return err
	}

	leaderboard := &models.Leaderboard{
		LeaderboardID: generateLeaderboardID(gameID, seasonID, lbType),
		GameID:        gameID,
		SeasonID:      seasonID,
		Type:          lbType,
		TotalPlayers:  totalPlayers,
		UpdatedAt:     time.Now(),
	}

	err = leaderboard.SetEntries(entries)
	if err != nil {
		return err
	}

	existingLB, err := s.mysqlStore.GetLeaderboard(gameID, seasonID, lbType)
	if err != nil {
		return err
	}

	if existingLB == nil {
		return s.mysqlStore.CreateLeaderboard(leaderboard)
	}

	existingLB.Entries = leaderboard.Entries
	existingLB.TotalPlayers = totalPlayers
	existingLB.UpdatedAt = time.Now()
	return s.mysqlStore.UpdateLeaderboard(existingLB)
}

func (s *RankingService) calculateRanksWithPolicy(scores []*models.PlayerScore) []*models.LeaderboardEntry {
	if len(scores) == 0 {
		return []*models.LeaderboardEntry{}
	}

	sortedScores := make([]*models.PlayerScore, len(scores))
	copy(sortedScores, scores)

	sort.Slice(sortedScores, func(i, j int) bool {
		if sortedScores[i].TotalScore == sortedScores[j].TotalScore {
			return sortedScores[i].UpdatedAt.Before(sortedScores[j].UpdatedAt)
		}
		return sortedScores[i].TotalScore > sortedScores[j].TotalScore
	})

	entries := make([]*models.LeaderboardEntry, 0, len(sortedScores))

	if s.sameScoreAsSameRank {
		var currentRank int64 = 1
		var prevScore int64 = -1

		for i, ps := range sortedScores {
			if ps.TotalScore != prevScore {
				currentRank = int64(i) + 1
				prevScore = ps.TotalScore
			}

			entry := &models.LeaderboardEntry{
				Rank:      currentRank,
				PlayerID:  ps.PlayerID,
				Score:     ps.TotalScore,
				UpdatedAt: ps.UpdatedAt,
			}
			entries = append(entries, entry)
		}
	} else {
		for i, ps := range sortedScores {
			entry := &models.LeaderboardEntry{
				Rank:      int64(i) + 1,
				PlayerID:  ps.PlayerID,
				Score:     ps.TotalScore,
				UpdatedAt: ps.UpdatedAt,
			}
			entries = append(entries, entry)
		}
	}

	return entries
}

func (s *RankingService) QueryLeaderboard(req *LeaderboardQueryRequest) (*LeaderboardQueryResponse, error) {
	if req.GameID == "" {
		return nil, fmt.Errorf("game_id is required")
	}

	seasonID := req.SeasonID
	if seasonID == "" {
		season, err := s.mysqlStore.GetActiveSeason(req.GameID)
		if err != nil {
			return nil, err
		}
		if season == nil {
			return nil, fmt.Errorf("no active season found")
		}
		seasonID = season.SeasonID
	}

	lbType := req.Type
	if lbType == "" {
		lbType = models.LeaderboardTypeTotal
	}

	limit := req.Limit
	if limit <= 0 || limit > 100 {
		limit = 100
	}

	offset := req.Offset
	if offset < 0 {
		offset = 0
	}

	totalPlayers, err := s.redisStore.GetTotalPlayers(req.GameID, seasonID)
	if err != nil {
		return nil, err
	}

	redisEntries, err := s.redisStore.GetTopPlayers(req.GameID, seasonID, offset, offset+limit-1)
	if err != nil {
		return nil, err
	}

	entries := s.processRedisEntriesWithPolicy(redisEntries, offset)

	response := &LeaderboardQueryResponse{
		Entries:      entries,
		TotalPlayers: totalPlayers,
	}

	if req.PlayerID != "" {
		rank, err := s.redisStore.GetPlayerRank(req.GameID, seasonID, req.PlayerID)
		if err != nil {
			return nil, err
		}

		if rank > 0 {
			score, err := s.redisStore.GetPlayerScore(req.GameID, seasonID, req.PlayerID)
			if err != nil {
				return nil, err
			}

			response.MyRank = &MyRankInfo{
				Rank:  rank,
				Score: score,
			}
		}
	}

	return response, nil
}

func (s *RankingService) processRedisEntriesWithPolicy(redisEntries []redis.Z, offset int64) []*models.LeaderboardEntry {
	entries := make([]*models.LeaderboardEntry, 0, len(redisEntries))

	if s.sameScoreAsSameRank {
		var currentRank int64 = offset + 1
		var prevScore float64 = -1

		for i, z := range redisEntries {
			playerID, ok := z.Member.(string)
			if !ok {
				continue
			}

			if z.Score != prevScore {
				currentRank = offset + int64(i) + 1
				prevScore = z.Score
			}

			entry := &models.LeaderboardEntry{
				Rank:      currentRank,
				PlayerID:  playerID,
				Score:     int64(z.Score),
				UpdatedAt: time.Now(),
			}
			entries = append(entries, entry)
		}
	} else {
		for i, z := range redisEntries {
			playerID, ok := z.Member.(string)
			if !ok {
				continue
			}

			entry := &models.LeaderboardEntry{
				Rank:      offset + int64(i) + 1,
				PlayerID:  playerID,
				Score:     int64(z.Score),
				UpdatedAt: time.Now(),
			}
			entries = append(entries, entry)
		}
	}

	return entries
}

func (s *RankingService) GetPlayerRank(gameID, seasonID, playerID string) (int64, error) {
	return s.redisStore.GetPlayerRank(gameID, seasonID, playerID)
}

func (s *RankingService) GetPlayerScore(gameID, seasonID, playerID string) (int64, error) {
	return s.redisStore.GetPlayerScore(gameID, seasonID, playerID)
}

func (s *RankingService) Close() {
	close(s.stopCh)
	s.wg.Wait()
	close(s.rankEventCh)
}

func generateLeaderboardID(gameID, seasonID string, lbType models.LeaderboardType) string {
	return fmt.Sprintf("lb_%s_%s_%s", gameID, seasonID, lbType)
}
