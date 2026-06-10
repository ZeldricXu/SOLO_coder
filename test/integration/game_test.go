//go:build integration

package integration

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/room"
	"github.com/studio/gameroom/pkg/storage"
	"github.com/studio/gameroom/pkg/game"

	_ "github.com/studio/gameroom/pkg/games/landlord"
)

const (
	mongoURI = "mongodb://localhost:27017"
	mongoDB  = "gameroom_test"
	redisAddr = "localhost:6379"
)

func TestFullGameFlow_TwoHumansTwoRobots(t *testing.T) {
	mongo, err := storage.NewMongoStore(mongoURI, mongoDB)
	require.NoError(t, err, "MongoDB should be available")
	defer mongo.Close()

	ctx := context.Background()
	mongo.GetClient().Database(mongoDB).Drop(ctx)

	roomManager := room.NewManager()
	statsAgg := storage.NewStatsAggregator(mongo)

	config := &common.RoomConfig{
		GameType:        common.GameTypeLandlord,
		MaxPlayers:      3,
		MinPlayers:      3,
		IsFriendRoom:    false,
		BaseScore:       10,
		TurnTimeoutSec:  30,
		ReadyTimeoutSec: 60,
		AllowObserver:   true,
		PlaybackEnabled: true,
	}

	human1 := &common.Player{
		UserID:   "human_1",
		Nickname: "玩家A",
		Level:    10,
		Elo:      1500.0,
		IsRobot:  false,
	}
	human2 := &common.Player{
		UserID:   "human_2",
		Nickname: "玩家B",
		Level:    12,
		Elo:      1520.0,
		IsRobot:  false,
	}
	robot1 := &common.Player{
		UserID:   "robot_1",
		Nickname: "AI-地主",
		Level:    1,
		Elo:      1500.0,
		IsRobot:  true,
		IsReady:  true,
	}

	r, err := roomManager.CreateRoom(config, human1)
	require.NoError(t, err)

	err = r.AddPlayer(human2)
	require.NoError(t, err)
	err = r.AddPlayer(robot1)
	require.NoError(t, err)

	assert.Equal(t, 3, len(r.GetPlayers()), "3 players in room")
	assert.Equal(t, common.UserID("human_1"), r.HostID, "first player is host")

	r.SetReady("human_1", true)
	r.SetReady("human_2", true)

	assert.True(t, r.AllReady(), "all players ready")

	err = r.StartGame()
	require.NoError(t, err)
	assert.Equal(t, common.StatePlaying, r.GetState(), "game started")

	assert.NotEmpty(t, r.GameCtx.PlayerHands["human_1"], "human1 has cards")
	assert.NotEmpty(t, r.GameCtx.PlayerHands["human_2"], "human2 has cards")
	assert.NotEmpty(t, r.GameCtx.PlayerHands["robot_1"], "robot has cards")

	totalCards := 0
	for _, hand := range r.GameCtx.PlayerHands {
		totalCards += len(hand)
	}
	assert.Equal(t, 17*3, totalCards, "17 cards per player in landlord")

	action := &common.GameAction{
		ActionID:   common.GenerateID(),
		UserID:     r.CurrentTurn,
		ActionType: common.ActionPlayCard,
		Data: map[string]interface{}{
			"cards": []common.Card{},
		},
		Timestamp: time.Now(),
	}
	_, err = r.HandleAction(action)
	assert.NoError(t, err)

	initialTurn := r.CurrentTurn

	action2 := &common.GameAction{
		ActionID:   common.GenerateID(),
		UserID:     r.CurrentTurn,
		ActionType: common.ActionPass,
		Data:       map[string]interface{}{},
		Timestamp:  time.Now(),
	}
	_, err = r.HandleAction(action2)
	assert.NoError(t, err)

	assert.NotEqual(t, initialTurn, r.CurrentTurn, "turn advances after action")

	snap := &storage.RoomSnapshot{
		RoomID:    r.ID,
		Config:    r.Config,
		State:     r.GetState(),
		Players:   r.GetPlayers(),
		HostID:    r.HostID,
		CreatedAt: r.CreatedAt,
		UpdatedAt: time.Now(),
		Extra:     make(map[string]interface{}),
	}
	err = mongo.SaveRoomSnapshot(snap)
	assert.NoError(t, err)

	settlement, err := r.Settle()
	require.NoError(t, err)
	assert.Len(t, settlement.Results, 3, "3 settlement results")

	totalScore := int64(0)
	for _, res := range settlement.Results {
		totalScore += res.Score
	}
	assert.Equal(t, int64(0), totalScore, "sum of all scores should be zero (zero-sum game)")

	rec := &storage.GameRecord{
		RoomID:    r.ID,
		GameType:  r.Config.GameType,
		StartTime: r.CreatedAt,
		EndTime:   time.Now(),
		Players:   make([]storage.PlayerRecord, 0),
		Actions:   r.Actions,
		IsFinished: true,
	}
	for _, res := range settlement.Results {
		pr := storage.PlayerRecord{
			UserID:   res.UserID,
			Score:    res.Score,
			Rank:     res.Rank,
			IsWinner: res.IsWinner,
		}
		if p, ok := r.GetPlayer(res.UserID); ok {
			pr.Nickname = p.Nickname
			pr.IsRobot = p.IsRobot
		}
		rec.Players = append(rec.Players, pr)
		if res.IsWinner {
			rec.Winners = append(rec.Winners, res.UserID)
		}
	}

	err = mongo.SaveGameRecord(rec)
	assert.NoError(t, err)

	for _, res := range settlement.Results {
		err := mongo.UpdatePlayerStats(res.UserID, r.Config.GameType,
			storage.PlayerRecord{
				Score:    res.Score,
				IsWinner: res.IsWinner,
			})
		assert.NoError(t, err)

		err = mongo.RecordDailyStats(res.UserID, r.Config.GameType, res.IsWinner, res.Score)
		assert.NoError(t, err)
	}

	stats, err := mongo.GetPlayerStats("human_1", common.GameTypeLandlord)
	assert.NoError(t, err)
	assert.Equal(t, int64(1), stats.TotalGames, "one game played")

	actions, err := mongo.GetActionsForPlayback(r.ID)
	assert.NoError(t, err)
	assert.GreaterOrEqual(t, len(actions), 2, "at least 2 actions recorded")

	playback := storage.NewPlaybackPlayer(mongo)
	state, err := playback.Load(r.ID)
	assert.NoError(t, err)
	assert.Equal(t, len(actions), state.Total(), "playback has all actions")
	_ = statsAgg
}

func TestDisconnectAndReconnect(t *testing.T) {
	mongo, err := storage.NewMongoStore(mongoURI, mongoDB)
	require.NoError(t, err)
	defer mongo.Close()

	ctx := context.Background()
	mongo.GetClient().Database(mongoDB).Drop(ctx)

	roomManager := room.NewManager()

	config := &common.RoomConfig{
		GameType:        common.GameTypeLandlord,
		MaxPlayers:      3,
		MinPlayers:      3,
		BaseScore:       10,
		TurnTimeoutSec:  30,
		ReadyTimeoutSec: 60,
		AllowObserver:   true,
		PlaybackEnabled: true,
	}

	human1 := &common.Player{UserID: "human_1", Nickname: "玩家A", Elo: 1500}
	human2 := &common.Player{UserID: "human_2", Nickname: "玩家B", Elo: 1520}
	robot1 := &common.Player{UserID: "robot_1", Nickname: "AI", Elo: 1500, IsRobot: true, IsReady: true}

	r, err := roomManager.CreateRoom(config, human1)
	require.NoError(t, err)
	r.AddPlayer(human2)
	r.AddPlayer(robot1)

	r.SetReady("human_1", true)
	r.SetReady("human_2", true)
	r.StartGame()

	t.Log("human_1 disconnects")
	r.SetPlayerOnline("human_1", false, 60)

	p1, ok := r.GetPlayer("human_1")
	require.True(t, ok)
	assert.False(t, p1.IsOnline, "player marked offline")

	if r.CurrentTurn == "human_1" {
		action := &common.GameAction{
			ActionID:   common.GenerateID(),
			UserID:     "human_1",
			ActionType: common.ActionPass,
			Data:       map[string]interface{}{},
			Timestamp:  time.Now(),
		}
		_, err := r.HandleAction(action)
		assert.NoError(t, err, "trustee plays on behalf of disconnected player")
	}

	t.Log("human_1 reconnects")
	r.SetPlayerOnline("human_1", true, 60)

	p1, ok = r.GetPlayer("human_1")
	require.True(t, ok)
	assert.True(t, p1.IsOnline, "player back online")

	actionCountBefore := len(r.Actions)
	if r.CurrentTurn == "human_1" {
		action := &common.GameAction{
			ActionID:   common.GenerateID(),
			UserID:     "human_1",
			ActionType: common.ActionPass,
			Data:       map[string]interface{}{},
			Timestamp:  time.Now(),
		}
		_, err := r.HandleAction(action)
		assert.NoError(t, err, "human plays after reconnect")
		actionCountBefore++
	}
	assert.Equal(t, actionCountBefore, len(r.Actions),
		"actions persist, trustee actions cannot be undone")
}

func TestPlaybackReplay(t *testing.T) {
	mongo, err := storage.NewMongoStore(mongoURI, mongoDB)
	require.NoError(t, err)
	defer mongo.Close()

	ctx := context.Background()
	mongo.GetClient().Database(mongoDB).Drop(ctx)

	roomManager := room.NewManager()
	config := &common.RoomConfig{
		GameType:       common.GameTypeLandlord,
		MaxPlayers:     3,
		MinPlayers:     3,
		BaseScore:      10,
		TurnTimeoutSec: 30,
	}

	p1 := &common.Player{UserID: "p1", Nickname: "P1", Elo: 1500}
	p2 := &common.Player{UserID: "p2", Nickname: "P2", Elo: 1500}
	p3 := &common.Player{UserID: "p3", Nickname: "P3", Elo: 1500, IsRobot: true, IsReady: true}

	r, _ := roomManager.CreateRoom(config, p1)
	r.AddPlayer(p2)
	r.AddPlayer(p3)
	r.SetReady("p1", true)
	r.SetReady("p2", true)
	r.StartGame()

	for i := 0; i < 5; i++ {
		action := &common.GameAction{
			ActionID:   "act_" + itostr(i),
			UserID:     r.CurrentTurn,
			ActionType: common.ActionPass,
			Data:       map[string]interface{}{},
			Timestamp:  time.Now(),
		}
		r.HandleAction(action)
	}

	rec := &storage.GameRecord{
		RoomID:     r.ID,
		GameType:   r.Config.GameType,
		StartTime:  r.CreatedAt,
		EndTime:    time.Now(),
		Actions:    r.Actions,
		IsFinished: true,
	}
	mongo.SaveGameRecord(rec)

	playback := storage.NewPlaybackPlayer(mongo)
	state, err := playback.Load(r.ID)
	require.NoError(t, err)
	assert.Equal(t, 5, state.Total(), "5 actions in playback")

	state.Reset()
	count := 0
	for {
		_, ok := state.Next()
		if !ok {
			break
		}
		count++
	}
	assert.Equal(t, 5, count, "can iterate through all actions")

	state.Reset()
	state.Seek(3)
	assert.Equal(t, 3, state.Current, "seek to seq 3")
}

func itostr(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}
