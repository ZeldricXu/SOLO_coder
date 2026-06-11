package room

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/config"
	"github.com/studio/gameroom/pkg/game"
	_ "github.com/studio/gameroom/pkg/games/landlord"
	"github.com/stretchr/testify/assert"
)

func mustRedis(t *testing.T) *redis.Client {
	addr := "127.0.0.1:6379"
	cli := redis.NewClient(&redis.Options{Addr: addr})
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
	defer cancel()
	if err := cli.Ping(ctx).Err(); err != nil {
		cli.Close()
		t.Skipf("redis not available at %s: %v", addr, err)
	}
	return cli
}

func TestSerializedRoomState_RoundTrip(t *testing.T) {
	state := &SerializedRoomState{
		RoomID:       "r_serial_1",
		Config:       &common.RoomConfig{GameType: common.GameTypeLandlord, BaseScore: 100},
		State:        common.StatePlaying,
		HostID:       "host_1",
		CurrentTurn:  "p1",
		Players:      []*common.Player{{UserID: "p1"}},
		GameCtx:      &game.GameContext{RoomID: "r_serial_1", Round: 2},
		Actions:      nil,
		GameType:     common.GameTypeLandlord,
		Round:        2,
		HibernatedAt: time.Now().UnixMilli(),
	}

	data, err := json.Marshal(state)
	assert.NoError(t, err)

	var restored SerializedRoomState
	err = json.Unmarshal(data, &restored)
	assert.NoError(t, err)
	assert.Equal(t, state.RoomID, restored.RoomID)
	assert.Equal(t, state.HostID, restored.HostID)
	assert.Equal(t, int64(100), restored.Config.BaseScore)
	assert.Equal(t, 2, restored.GameCtx.Round)
}

func TestRoomHibernation_ForceHibernateAndWakeUp(t *testing.T) {
	cli := mustRedis(t)
	defer cli.Close()

	rm := NewManager()
	cfg := config.DefaultConfig()
	hm := NewRoomHibernationManager(cli, cfg, rm)

	rule, _ := game.GetRule(common.GameTypeLandlord)
	host := &common.Player{UserID: "h1", Nickname: "host"}
	r, err := rm.CreateRoom(&common.RoomConfig{GameType: common.GameTypeLandlord}, host)
	assert.NoError(t, err)

	hm.TouchActivity(r.ID)

	err = hm.ForceHibernate(r.ID)
	assert.NoError(t, err)

	_, ok := rm.GetRoom(r.ID)
	assert.False(t, ok, "room must be removed from manager after hibernation")
	assert.True(t, hm.IsHibernated(r.ID))

	woken, err := hm.WakeUpRoom(r.ID, rule)
	assert.NoError(t, err)
	assert.NotNil(t, woken)
	assert.Equal(t, r.ID, woken.ID)
	assert.Equal(t, "h1", woken.HostID)
	assert.False(t, hm.IsHibernated(r.ID))
}

func TestRoomHibernation_CheckLoopHibernatesIdle(t *testing.T) {
	cli := mustRedis(t)
	defer cli.Close()

	rm := NewManager()
	cfg := config.DefaultConfig()
	hm := NewRoomHibernationManager(cli, cfg, rm)

	hm.SetIdleThreshold(60 * time.Millisecond)
	hm.SetCheckInterval(40 * time.Millisecond)
	hm.Start()
	defer hm.Stop()

	host := &common.Player{UserID: "host_idle"}
	r, err := rm.CreateRoom(&common.RoomConfig{GameType: common.GameTypeLandlord}, host)
	assert.NoError(t, err)

	hm.TouchActivity(r.ID)

	time.Sleep(250 * time.Millisecond)

	assert.True(t, hm.IsHibernated(r.ID), "room should be hibernated after idle timeout")
}

func TestRoomHibernation_WakeupMissingRoom(t *testing.T) {
	cli := mustRedis(t)
	defer cli.Close()

	rm := NewManager()
	cfg := config.DefaultConfig()
	hm := NewRoomHibernationManager(cli, cfg, rm)

	rule, _ := game.GetRule(common.GameTypeLandlord)
	_, err := hm.WakeUpRoom("room_never_existed", rule)
	assert.ErrorIs(t, err, common.ErrRoomNotFound)
}

func TestRoomHibernation_GetStats(t *testing.T) {
	cli := mustRedis(t)
	defer cli.Close()

	rm := NewManager()
	cfg := config.DefaultConfig()
	hm := NewRoomHibernationManager(cli, cfg, rm)

	host := &common.Player{UserID: "h_stats"}
	_, err := rm.CreateRoom(&common.RoomConfig{GameType: common.GameTypeLandlord}, host)
	assert.NoError(t, err)

	active, hib, _ := hm.GetStats()
	assert.Equal(t, 1, active)
	assert.Equal(t, 0, hib)
}
