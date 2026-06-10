package room

import (
	"sync"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type Room struct {
	ID         common.RoomID
	Config     *common.RoomConfig
	CreatedAt  time.Time
	UpdatedAt  time.Time

	State      common.GameState
	Players    map[common.UserID]*common.Player
	Seats      []common.SeatID
	HostID     common.UserID
	CurrentTurn common.UserID

	GameCtx    *game.GameContext
	Rule       game.GameRule

	Observers  map[common.UserID]*Observer
	Actions    []common.GameAction

	mu         sync.RWMutex
	turnTimer  *time.Timer
	readyTimer *time.Timer
	trusteeTimers map[common.UserID]*time.Timer
}

type Observer struct {
	UserID    common.UserID
	Nickname  string
	JoinedAt  time.Time
}

func NewRoom(roomID common.RoomID, config *common.RoomConfig, rule game.GameRule) *Room {
	if config.InviteCode == "" && config.IsFriendRoom {
		config.InviteCode = common.GenerateInviteCode()
	}
	return &Room{
		ID:            roomID,
		Config:        config,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
		State:         rule.GetInitialState(),
		Players:       make(map[common.UserID]*common.Player),
		Seats:         make([]common.SeatID, config.MaxPlayers),
		Observers:     make(map[common.UserID]*Observer),
		Actions:       make([]common.GameAction, 0),
		GameCtx:       game.NewGameContext(roomID),
		Rule:          rule,
		trusteeTimers: make(map[common.UserID]*time.Timer),
	}
}

func (r *Room) touch() {
	r.UpdatedAt = time.Now()
}

func (r *Room) getAvailableSeat() common.SeatID {
	for i := 0; i < r.Config.MaxPlayers; i++ {
		occupied := false
		for _, p := range r.Players {
			if p.SeatID == common.SeatID(i) {
				occupied = true
				break
			}
		}
		if !occupied {
			return common.SeatID(i)
		}
	}
	return common.SeatID(-1)
}

func (r *Room) AddPlayer(player *common.Player) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.State != common.StateWaiting && !r.Rule.CanJoinMidGame(r.GameCtx) {
		return common.ErrRoomNotJoinable
	}
	if _, exists := r.Players[player.UserID]; exists {
		return common.ErrPlayerAlreadyInRoom
	}
	if len(r.Players) >= r.Config.MaxPlayers {
		return common.ErrRoomFull
	}

	player.SeatID = r.getAvailableSeat()
	player.JoinedAt = time.Now()
	player.IsReady = false
	player.IsOnline = true

	if len(r.Players) == 0 {
		player.IsHost = true
		r.HostID = player.UserID
	}

	r.Players[player.UserID] = player
	r.GameCtx.Players = append(r.GameCtx.Players, player)
	r.touch()
	return nil
}

func (r *Room) RemovePlayer(userID common.UserID, disbandIfEmpty bool) (*common.Player, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	player, exists := r.Players[userID]
	if !exists {
		return nil, common.ErrPlayerNotFound
	}

	if t, ok := r.trusteeTimers[userID]; ok && t != nil {
		t.Stop()
		delete(r.trusteeTimers, userID)
	}

	delete(r.Players, userID)

	for i, p := range r.GameCtx.Players {
		if p.UserID == userID {
			r.GameCtx.Players = append(r.GameCtx.Players[:i], r.GameCtx.Players[i+1:]...)
			break
		}
	}

	if player.IsHost && len(r.Players) > 0 {
		r.transferHost()
	}

	if disbandIfEmpty && len(r.Players) == 0 {
		r.State = common.StateDisbanded
		r.stopAllTimers()
	}

	r.touch()
	return player, nil
}

func (r *Room) transferHost() {
	var firstPlayer *common.Player
	for _, p := range r.Players {
		if firstPlayer == nil || p.JoinedAt.Before(firstPlayer.JoinedAt) {
			firstPlayer = p
		}
	}
	if firstPlayer != nil {
		firstPlayer.IsHost = true
		r.HostID = firstPlayer.UserID
	}
}

func (r *Room) SetReady(userID common.UserID, ready bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	player, exists := r.Players[userID]
	if !exists {
		return common.ErrPlayerNotFound
	}
	if r.State != common.StateWaiting {
		return common.ErrGameAlreadyStarted
	}
	player.IsReady = ready
	r.touch()
	return nil
}

func (r *Room) AllReady() bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if len(r.Players) < r.Config.MinPlayers {
		return false
	}
	for _, p := range r.Players {
		if !p.IsReady && !p.IsRobot {
			return false
		}
	}
	return true
}

func (r *Room) StartGame() error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.State != common.StateWaiting {
		return common.ErrGameAlreadyStarted
	}
	if len(r.Players) < r.Config.MinPlayers {
		return common.ErrRoomNotJoinable
	}

	r.State = common.StatePlaying
	r.GameCtx.State = common.StatePlaying

	deck := r.Rule.InitDeck()
	shuffler := r.Rule.GetShuffleStrategy()
	r.GameCtx.Deck = shuffler.Shuffle(deck)

	if err := r.Rule.DealCards(r.GameCtx, r.Config); err != nil {
		return err
	}

	if len(r.GameCtx.Players) > 0 {
		r.CurrentTurn = r.GameCtx.Players[0].UserID
		r.GameCtx.CurrentTurn = r.CurrentTurn
		r.scheduleTurnTimer()
	}

	r.touch()
	return nil
}

func (r *Room) HandleAction(action *common.GameAction) (*common.GameAction, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.State != common.StatePlaying {
		return nil, common.ErrGameNotStarted
	}
	if action.UserID != r.CurrentTurn {
		return nil, common.ErrNotYourTurn
	}

	if err := r.Rule.ValidateAction(r.GameCtx, action); err != nil {
		return nil, err
	}

	applied, err := r.Rule.ApplyAction(r.GameCtx, action)
	if err != nil {
		return nil, err
	}

	applied.Seq = r.GameCtx.NextSeq()
	applied.RoomID = r.ID
	applied.Timestamp = time.Now()
	r.Actions = append(r.Actions, *applied)

	if r.turnTimer != nil {
		r.turnTimer.Stop()
	}

	if r.Rule.IsRoundOver(r.GameCtx) {
		r.handleRoundOver()
	} else if r.Rule.IsGameOver(r.GameCtx) {
		r.handleGameOver()
	} else {
		next := r.GameCtx.GetNextPlayer(r.CurrentTurn)
		if next != nil {
			r.CurrentTurn = next.UserID
			r.GameCtx.CurrentTurn = next.UserID
			r.scheduleTurnTimer()
		}
	}

	r.touch()
	return applied, nil
}

func (r *Room) scheduleTurnTimer() {
	timeout := r.Rule.GetTurnTimeout(r.GameCtx)
	if timeout <= 0 {
		timeout = r.Config.TurnTimeoutSec
	}
	if timeout <= 0 {
		timeout = 15
	}
	if r.turnTimer != nil {
		r.turnTimer.Stop()
	}
	r.turnTimer = time.AfterFunc(time.Duration(timeout)*time.Second, func() {
		r.onTurnTimeout()
	})
}

func (r *Room) onTurnTimeout() {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.State != common.StatePlaying {
		return
	}

	player, exists := r.Players[r.CurrentTurn]
	if !exists {
		return
	}

	autoAction, err := r.Rule.GetAutoAction(r.GameCtx, r.CurrentTurn)
	if err != nil {
		common.LogWarn("failed to get auto action for player %s: %v", r.CurrentTurn, err)
		return
	}

	applied, err := r.Rule.ApplyAction(r.GameCtx, autoAction)
	if err != nil {
		common.LogWarn("failed to apply auto action: %v", err)
		return
	}

	applied.Seq = r.GameCtx.NextSeq()
	applied.Timestamp = time.Now()
	r.Actions = append(r.Actions, *applied)

	if r.Rule.IsRoundOver(r.GameCtx) {
		r.handleRoundOver()
	} else if r.Rule.IsGameOver(r.GameCtx) {
		r.handleGameOver()
	} else {
		next := r.GameCtx.GetNextPlayer(r.CurrentTurn)
		if next != nil {
			r.CurrentTurn = next.UserID
			r.GameCtx.CurrentTurn = next.UserID
			r.scheduleTurnTimer()
		}
	}

	common.LogInfo("player %s auto-played due to timeout, isRobot=%v", player.UserID, player.IsRobot)
}

func (r *Room) handleRoundOver() {
	r.State = common.StateSettling
	r.GameCtx.State = common.StateSettling
}

func (r *Room) handleGameOver() {
	r.State = common.StateFinished
	r.GameCtx.State = common.StateFinished
	r.stopAllTimers()
}

func (r *Room) SetPlayerOnline(userID common.UserID, online bool, trusteeDelaySec int) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	player, exists := r.Players[userID]
	if !exists {
		return common.ErrPlayerNotFound
	}
	player.IsOnline = online

	if t, ok := r.trusteeTimers[userID]; ok && t != nil {
		t.Stop()
		delete(r.trusteeTimers, userID)
	}

	if !online && r.State == common.StatePlaying && trusteeDelaySec > 0 {
		r.trusteeTimers[userID] = time.AfterFunc(time.Duration(trusteeDelaySec)*time.Second, func() {
			r.onPlayerTrustee(userID)
		})
	}
	return nil
}

func (r *Room) onPlayerTrustee(userID common.UserID) {
	r.mu.Lock()
	defer r.mu.Unlock()

	player, exists := r.Players[userID]
	if !exists || player.IsOnline {
		return
	}

	if r.CurrentTurn == userID && r.State == common.StatePlaying {
		autoAction, err := r.Rule.GetAutoAction(r.GameCtx, userID)
		if err == nil && autoAction != nil {
			applied, err := r.Rule.ApplyAction(r.GameCtx, autoAction)
			if err == nil {
				applied.Seq = r.GameCtx.NextSeq()
				applied.Timestamp = time.Now()
				r.Actions = append(r.Actions, *applied)
			}
		}
	}
}

func (r *Room) AddObserver(obs *Observer) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.Config.AllowObserver {
		return common.ErrObserverNotAllowed
	}
	if _, exists := r.Observers[obs.UserID]; exists {
		return nil
	}
	obs.JoinedAt = time.Now()
	r.Observers[obs.UserID] = obs
	return nil
}

func (r *Room) RemoveObserver(userID common.UserID) {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.Observers, userID)
}

func (r *Room) Settle() (*game.Settlement, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	settler := r.Rule.GetSettlementStrategy()
	settlement, err := settler.Calculate(r.GameCtx, r.Config)
	if err != nil {
		return nil, err
	}

	for _, result := range settlement.Results {
		if p, ok := r.Players[result.UserID]; ok {
			p.Score += result.Score
		}
	}

	r.GameCtx.Round++
	return settlement, nil
}

func (r *Room) Disband(reason string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.State = common.StateDisbanded
	r.GameCtx.State = common.StateDisbanded
	r.stopAllTimers()
	r.touch()
}

func (r *Room) stopAllTimers() {
	if r.turnTimer != nil {
		r.turnTimer.Stop()
		r.turnTimer = nil
	}
	if r.readyTimer != nil {
		r.readyTimer.Stop()
		r.readyTimer = nil
	}
	for _, t := range r.trusteeTimers {
		if t != nil {
			t.Stop()
		}
	}
	r.trusteeTimers = make(map[common.UserID]*time.Timer)
}

func (r *Room) GetPlayer(userID common.UserID) (*common.Player, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	p, ok := r.Players[userID]
	return p, ok
}

func (r *Room) GetPlayers() []*common.Player {
	r.mu.RLock()
	defer r.mu.RUnlock()
	players := make([]*common.Player, 0, len(r.Players))
	for _, p := range r.Players {
		players = append(players, p)
	}
	return players
}

func (r *Room) GetState() common.GameState {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.State
}
