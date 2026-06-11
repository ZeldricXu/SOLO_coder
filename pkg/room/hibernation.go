package room

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/config"
	"github.com/studio/gameroom/pkg/game"
)

type RoomHibernationManager struct {
	redisClient    *redis.Client
	config         *config.Config
	roomManager    *Manager

	lastActivity   map[common.RoomID]int64
	hibernated     map[common.RoomID]bool
	activityMu     sync.RWMutex

	checkInterval  time.Duration
	idleThreshold  time.Duration
	hibernateTTL   time.Duration

	running        bool
	shutdownCh     chan struct{}
	wg             sync.WaitGroup
}

type SerializedRoomState struct {
	RoomID     common.RoomID           `json:"room_id"`
	Config     *common.RoomConfig      `json:"config"`
	State      common.GameState        `json:"state"`
	CreatedAt  int64                  `json:"created_at"`
	UpdatedAt  int64                  `json:"updated_at"`
	HostID     common.UserID           `json:"host_id"`
	CurrentTurn common.UserID          `json:"current_turn"`
	Players    []*common.Player        `json:"players"`
	GameCtx    *game.GameContext       `json:"game_ctx"`
	Actions    []common.GameAction     `json:"actions"`
	GameType   common.GameType         `json:"game_type"`
	Round      int                    `json:"round"`
	HibernatedAt int64                `json:"hibernated_at"`
}

func NewRoomHibernationManager(
	redisClient *redis.Client,
	cfg *config.Config,
	roomManager *Manager,
) *RoomHibernationManager {
	idleThreshold := 5 * time.Minute
	hibernateTTL := 24 * time.Hour
	checkInterval := 30 * time.Second

	return &RoomHibernationManager{
		redisClient:   redisClient,
		config:        cfg,
		roomManager:   roomManager,
		lastActivity:  make(map[common.RoomID]int64),
		hibernated:    make(map[common.RoomID]bool),
		checkInterval: checkInterval,
		idleThreshold: idleThreshold,
		hibernateTTL:  hibernateTTL,
		shutdownCh:    make(chan struct{}),
	}
}

func (hm *RoomHibernationManager) Start() {
	if hm.running {
		return
	}
	hm.running = true

	hm.wg.Add(1)
	go hm.checkLoop()

	common.LogInfo("room hibernation manager started: idle_threshold=%v, check_interval=%v",
		hm.idleThreshold, hm.checkInterval)
}

func (hm *RoomHibernationManager) Stop() {
	if !hm.running {
		return
	}
	hm.running = false

	close(hm.shutdownCh)
	hm.wg.Wait()

	common.LogInfo("room hibernation manager stopped")
}

func (hm *RoomHibernationManager) TouchActivity(roomID common.RoomID) {
	now := common.NowMs()
	hm.activityMu.Lock()
	hm.lastActivity[roomID] = now
	hm.activityMu.Unlock()
}

func (hm *RoomHibernationManager) SetIdleThreshold(d time.Duration) {
	hm.activityMu.Lock()
	defer hm.activityMu.Unlock()
	hm.idleThreshold = d
}

func (hm *RoomHibernationManager) SetCheckInterval(d time.Duration) {
	hm.activityMu.Lock()
	defer hm.activityMu.Unlock()
	hm.checkInterval = d
}

func (hm *RoomHibernationManager) IsHibernated(roomID common.RoomID) bool {
	hm.activityMu.RLock()
	defer hm.activityMu.RUnlock()
	return hm.hibernated[roomID]
}

func (hm *RoomHibernationManager) GetLastActivity(roomID common.RoomID) int64 {
	hm.activityMu.RLock()
	defer hm.activityMu.RUnlock()
	return hm.lastActivity[roomID]
}

func (hm *RoomHibernationManager) checkLoop() {
	defer hm.wg.Done()

	ticker := time.NewTicker(hm.checkInterval)
	defer ticker.Stop()

	for {
		select {
		case <-hm.shutdownCh:
			return
		case <-ticker.C:
			hm.checkIdleRooms()
		}
	}
}

func (hm *RoomHibernationManager) checkIdleRooms() {
	now := common.NowMs()
	thresholdMs := int64(hm.idleThreshold.Milliseconds())

	activeRooms := hm.roomManager.GetAllRoomIDs()

	hm.activityMu.Lock()
	for _, roomID := range activeRooms {
		if hm.hibernated[roomID] {
			continue
		}

		lastAct, exists := hm.lastActivity[roomID]
		if !exists {
			hm.lastActivity[roomID] = now
			continue
		}

		if now-lastAct >= thresholdMs {
			hm.hibernateRoomLocked(roomID)
		}
	}
	hm.activityMu.Unlock()
}

func (hm *RoomHibernationManager) hibernateRoomLocked(roomID common.RoomID) {
	room, ok := hm.roomManager.GetRoom(roomID)
	if !ok {
		delete(hm.lastActivity, roomID)
		return
	}

	state := &SerializedRoomState{
		RoomID:       room.ID,
		Config:       room.Config,
		State:        room.State,
		CreatedAt:    room.CreatedAt.UnixMilli(),
		UpdatedAt:    room.UpdatedAt.UnixMilli(),
		HostID:       room.HostID,
		CurrentTurn:  room.CurrentTurn,
		Players:      make([]*common.Player, 0, len(room.Players)),
		GameCtx:      room.GameCtx,
		Actions:      room.Actions,
		GameType:     room.Config.GameType,
		Round:        room.GameCtx.Round,
		HibernatedAt: common.NowMs(),
	}

	for _, p := range room.Players {
		state.Players = append(state.Players, p)
	}

	if err := hm.saveToRedis(roomID, state); err != nil {
		common.LogError("failed to hibernate room %s: %v", roomID, err)
		return
	}

	hm.roomManager.RemoveRoom(roomID)
	hm.hibernated[roomID] = true

	common.LogInfo("room %s hibernated after idle, players=%d, actions=%d",
		roomID, len(state.Players), len(state.Actions))
}

func (hm *RoomHibernationManager) WakeUpRoom(roomID common.RoomID, rule game.GameRule) (*Room, error) {
	hm.activityMu.Lock()
	defer hm.activityMu.Unlock()

	if !hm.hibernated[roomID] {
		if room, ok := hm.roomManager.GetRoom(roomID); ok {
			return room, nil
		}
	}

	state, err := hm.loadFromRedis(roomID)
	if err != nil {
		return nil, fmt.Errorf("load hibernated state: %w", err)
	}
	if state == nil {
		return nil, common.ErrRoomNotFound
	}

	room := &Room{
		ID:            state.RoomID,
		Config:        state.Config,
		CreatedAt:     time.UnixMilli(state.CreatedAt),
		UpdatedAt:     time.UnixMilli(state.UpdatedAt),
		State:         state.State,
		Players:       make(map[common.UserID]*common.Player),
		Seats:         make([]common.SeatID, state.Config.MaxPlayers),
		HostID:        state.HostID,
		CurrentTurn:   state.CurrentTurn,
		GameCtx:       state.GameCtx,
		Rule:          rule,
		Observers:     make(map[common.UserID]*Observer),
		Actions:       state.Actions,
		trusteeTimers: make(map[common.UserID]*time.Timer),
	}

	for _, p := range state.Players {
		room.Players[p.UserID] = p
		if int(p.SeatID) < len(room.Seats) && p.SeatID >= 0 {
			room.Seats[p.SeatID] = p.SeatID
		}
	}

	if rule != nil {
		room.Rule = rule
	}

	if err := hm.roomManager.AddRoom(room); err != nil {
		return nil, err
	}

	hm.hibernated[roomID] = false
	hm.lastActivity[roomID] = common.NowMs()

	hm.deleteFromRedis(roomID)

	common.LogInfo("room %s woken up from hibernation, players=%d", roomID, len(room.Players))
	return room, nil
}

func (hm *RoomHibernationManager) saveToRedis(roomID common.RoomID, state *SerializedRoomState) error {
	if hm.redisClient == nil {
		return nil
	}

	data, err := json.Marshal(state)
	if err != nil {
		return err
	}

	ctx := context.Background()
	key := hm.hibernateKey(roomID)

	return hm.redisClient.Set(ctx, key, data, hm.hibernateTTL).Err()
}

func (hm *RoomHibernationManager) loadFromRedis(roomID common.RoomID) (*SerializedRoomState, error) {
	if hm.redisClient == nil {
		return nil, nil
	}

	ctx := context.Background()
	key := hm.hibernateKey(roomID)

	data, err := hm.redisClient.Get(ctx, key).Result()
	if err == redis.Nil {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	var state SerializedRoomState
	if err := json.Unmarshal([]byte(data), &state); err != nil {
		return nil, err
	}

	return &state, nil
}

func (hm *RoomHibernationManager) deleteFromRedis(roomID common.RoomID) {
	if hm.redisClient == nil {
		return
	}

	ctx := context.Background()
	hm.redisClient.Del(ctx, hm.hibernateKey(roomID))
}

func (hm *RoomHibernationManager) hibernateKey(roomID common.RoomID) string {
	return fmt.Sprintf("hibernate:room:%s", roomID)
}

func (hm *RoomHibernationManager) ForceHibernate(roomID common.RoomID) error {
	hm.activityMu.Lock()
	defer hm.activityMu.Unlock()
	hm.hibernateRoomLocked(roomID)
	return nil
}

func (hm *RoomHibernationManager) GetStats() (activeRooms int, hibernatedRooms int, avgIdleMs int64) {
	hm.activityMu.RLock()
	defer hm.activityMu.RUnlock()

	hibernatedCount := 0
	activeCount := 0
	totalIdle := int64(0)
	now := common.NowMs()

	for roomID, hib := range hm.hibernated {
		if hib {
			hibernatedCount++
		} else {
			activeCount++
			if lastAct, ok := hm.lastActivity[roomID]; ok {
				totalIdle += now - lastAct
			}
		}
	}

	avgIdle := int64(0)
	if activeCount > 0 {
		avgIdle = totalIdle / int64(activeCount)
	}

	return activeCount, hibernatedCount, avgIdle
}
