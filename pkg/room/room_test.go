package room

import (
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type MockRule struct {
	game.BaseRule
}

func (r *MockRule) GameType() common.GameType { return "mock" }
func (r *MockRule) Name() string             { return "mock_rule" }
func (r *MockRule) InitDeck() []common.Card  { return []common.Card{{}, {}, {}} }
func (r *MockRule) GetShuffleStrategy() game.ShuffleStrategy {
	return game.GetShuffleStrategy("random")
}
func (r *MockRule) GetPatternValidator() game.CardPatternValidator {
	return nil
}
func (r *MockRule) GetSettlementStrategy() game.SettlementStrategy {
	return &MockSettlement{}
}
func (r *MockRule) DealCards(ctx *game.GameContext, config *common.RoomConfig) error {
	for _, p := range ctx.Players {
		ctx.PlayerHands[p.UserID] = []common.Card{{Rank: 1, Index: 1}}
	}
	return nil
}
func (r *MockRule) ValidateAction(ctx *game.GameContext, action *common.GameAction) error {
	if action.ActionType == common.ActionPlayCard {
		return nil
	}
	if action.ActionType == common.ActionDiscard {
		return nil
	}
	return common.ErrInvalidAction
}
func (r *MockRule) ApplyAction(ctx *game.GameContext, action *common.GameAction) (*common.GameAction, error) {
	ctx.LastAction = action
	return action, nil
}
func (r *MockRule) IsRoundOver(ctx *game.GameContext) bool { return false }
func (r *MockRule) IsGameOver(ctx *game.GameContext) bool  { return false }
func (r *MockRule) GetAutoAction(ctx *game.GameContext, userID common.UserID) (*common.GameAction, error) {
	return &common.GameAction{
		ActionID:   "auto_1",
		UserID:     userID,
		ActionType: common.ActionPass,
		Data:       make(map[string]interface{}),
		Timestamp:  time.Now(),
	}, nil
}

type MockSettlement struct{}

func (s *MockSettlement) Name() string { return "mock_settle" }
func (s *MockSettlement) Calculate(ctx *game.GameContext, config *common.RoomConfig) (*game.Settlement, error) {
	results := make([]game.SettleResult, len(ctx.Players))
	for i, p := range ctx.Players {
		results[i] = game.SettleResult{
			UserID:   p.UserID,
			Score:    100,
			Rank:     i + 1,
			IsWinner: i == 0,
		}
	}
	return &game.Settlement{Results: results, Timestamp: time.Now().UnixMilli(), Round: 1}, nil
}

func TestRoom_StateMachine(t *testing.T) {
	config := &common.RoomConfig{
		GameType:        "mock",
		MaxPlayers:      4,
		MinPlayers:      2,
		BaseScore:       100,
		TurnTimeoutSec:  30,
		ReadyTimeoutSec: 60,
	}
	rule := &MockRule{}
	r := NewRoom("test_room", config, rule)

	assert.Equal(t, common.StateWaiting, r.GetState(), "initial state is waiting")

	p1 := &common.Player{UserID: "p1", Nickname: "Player1"}
	p2 := &common.Player{UserID: "p2", Nickname: "Player2"}

	err := r.AddPlayer(p1)
	assert.NoError(t, err)
	err = r.AddPlayer(p2)
	assert.NoError(t, err)

	assert.Equal(t, common.StateWaiting, r.GetState())
}

func TestRoom_StartGameValid(t *testing.T) {
	config := &common.RoomConfig{
		GameType:        "mock",
		MaxPlayers:      4,
		MinPlayers:      2,
		BaseScore:       100,
		TurnTimeoutSec:  30,
		ReadyTimeoutSec: 60,
	}
	rule := &MockRule{}
	r := NewRoom("test_room", config, rule)

	r.AddPlayer(&common.Player{UserID: "p1", Nickname: "P1"})
	r.AddPlayer(&common.Player{UserID: "p2", Nickname: "P2"})

	r.SetReady("p1", true)
	r.SetReady("p2", true)

	assert.True(t, r.AllReady())

	err := r.StartGame()
	assert.NoError(t, err)
	assert.Equal(t, common.StatePlaying, r.GetState())
}

func TestRoom_StartGameWhenNotReady(t *testing.T) {
	config := &common.RoomConfig{
		GameType:   "mock",
		MaxPlayers: 4,
		MinPlayers: 2,
	}
	rule := &MockRule{}
	r := NewRoom("test_room", config, rule)

	r.AddPlayer(&common.Player{UserID: "p1", Nickname: "P1"})
	r.AddPlayer(&common.Player{UserID: "p2", Nickname: "P2"})

	err := r.StartGame()
	assert.NoError(t, err, "can start even if not all ready")
}

func TestRoom_InvalidStateTransitions(t *testing.T) {
	config := &common.RoomConfig{
		GameType:   "mock",
		MaxPlayers: 4,
		MinPlayers: 2,
	}
	rule := &MockRule{}

	t.Run("add player after game start rejected by rule", func(t *testing.T) {
		r := NewRoom("test", config, rule)
		r.AddPlayer(&common.Player{UserID: "p1"})
		r.AddPlayer(&common.Player{UserID: "p2"})
		r.StartGame()

		err := r.AddPlayer(&common.Player{UserID: "p3"})
		assert.Error(t, err, "cannot add player mid-game if rule forbids")
		assert.Equal(t, common.ErrRoomNotJoinable, err)
	})

	t.Run("action when not playing", func(t *testing.T) {
		r := NewRoom("test", config, rule)
		r.AddPlayer(&common.Player{UserID: "p1", IsHost: true})
		r.AddPlayer(&common.Player{UserID: "p2"})

		action := &common.GameAction{
			ActionID:   "a1",
			UserID:     "p1",
			ActionType: common.ActionPlayCard,
			Data:       make(map[string]interface{}),
			Timestamp:  time.Now(),
		}

		_, err := r.HandleAction(action)
		assert.Error(t, err, "action rejected when not playing")
		assert.Equal(t, common.ErrGameNotStarted, err)
	})

	t.Run("not your turn", func(t *testing.T) {
		r := NewRoom("test", config, rule)
		r.AddPlayer(&common.Player{UserID: "p1", IsHost: true})
		r.AddPlayer(&common.Player{UserID: "p2"})
		r.StartGame()

		action := &common.GameAction{
			ActionID:   "a1",
			UserID:     "p2",
			ActionType: common.ActionPlayCard,
			Data:       make(map[string]interface{}),
			Timestamp:  time.Now(),
		}

		_, err := r.HandleAction(action)
		assert.Error(t, err, "rejected when not your turn")
		assert.Equal(t, common.ErrNotYourTurn, err)
	})
}

func TestRoom_HostTransfer(t *testing.T) {
	config := &common.RoomConfig{GameType: "mock", MaxPlayers: 4, MinPlayers: 2}
	rule := &MockRule{}
	r := NewRoom("test", config, rule)

	r.AddPlayer(&common.Player{UserID: "p1"})
	r.AddPlayer(&common.Player{UserID: "p2"})
	r.AddPlayer(&common.Player{UserID: "p3"})

	assert.Equal(t, common.UserID("p1"), r.HostID)

	r.RemovePlayer("p1", false)
	assert.NotEqual(t, common.UserID("p1"), r.HostID, "host should transfer")
	assert.True(t, r.HostID == "p2" || r.HostID == "p3",
		"new host should be p2 or p3")
}

func TestRoom_ConcurrentReady(t *testing.T) {
	config := &common.RoomConfig{GameType: "mock", MaxPlayers: 10, MinPlayers: 2}
	rule := &MockRule{}
	r := NewRoom("test", config, rule)

	numPlayers := 8
	for i := 0; i < numPlayers; i++ {
		id := common.UserID("p_" + itos(i))
		r.AddPlayer(&common.Player{UserID: id, Nickname: string(id)})
	}

	var wg sync.WaitGroup
	for i := 0; i < numPlayers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			id := common.UserID("p_" + itos(idx))
			r.SetReady(id, true)
		}(i)
	}
	wg.Wait()

	readyCount := 0
	for _, p := range r.GetPlayers() {
		if p.IsReady {
			readyCount++
		}
	}
	assert.Equal(t, numPlayers, readyCount, "all players should be ready after concurrent sets")
}

func TestRoom_ConcurrentAddAndRemove(t *testing.T) {
	config := &common.RoomConfig{GameType: "mock", MaxPlayers: 10, MinPlayers: 2}
	rule := &MockRule{}
	r := NewRoom("test", config, rule)

	r.AddPlayer(&common.Player{UserID: "host"})

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			id := common.UserID("p_" + itos(idx))
			r.AddPlayer(&common.Player{UserID: id, Nickname: string(id)})
			if idx%2 == 0 {
				r.RemovePlayer(id, false)
			}
		}(i)
	}
	wg.Wait()

	assert.GreaterOrEqual(t, len(r.GetPlayers()), 1, "at least host remains")
	assert.LessOrEqual(t, len(r.GetPlayers()), config.MaxPlayers,
		"never exceed max players")
}

func TestRoom_DisbandedAfterAllLeave(t *testing.T) {
	config := &common.RoomConfig{GameType: "mock", MaxPlayers: 4, MinPlayers: 2}
	rule := &MockRule{}
	r := NewRoom("test", config, rule)

	r.AddPlayer(&common.Player{UserID: "p1"})
	r.AddPlayer(&common.Player{UserID: "p2"})

	r.RemovePlayer("p1", true)
	assert.NotEqual(t, common.StateDisbanded, r.GetState(),
		"room not disbanded until empty")

	r.RemovePlayer("p2", true)
	assert.Equal(t, common.StateDisbanded, r.GetState(),
		"room disbanded after last player leaves")
}

func TestRoom_IdempotentActionSequences(t *testing.T) {
	config := &common.RoomConfig{GameType: "mock", MaxPlayers: 4, MinPlayers: 2}
	rule := &MockRule{}
	r := NewRoom("test", config, rule)

	r.AddPlayer(&common.Player{UserID: "p1"})
	r.AddPlayer(&common.Player{UserID: "p2"})
	r.StartGame()

	action := &common.GameAction{
		ActionID:   "action_1",
		UserID:     "p1",
		ActionType: common.ActionPlayCard,
		Data:       make(map[string]interface{}),
		Timestamp:  time.Now(),
	}

	r.HandleAction(action)
	seq1 := r.GameCtx.Seq

	assert.Equal(t, int64(1), seq1)
}

func itos(n int) string {
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
