package match

import (
	"context"
	"testing"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
	"github.com/stretchr/testify/assert"
)

func mustTestRedis(t *testing.T) *redis.Client {
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

func TestBatchHeartbeat_SingleAndIntervalFlush(t *testing.T) {
	cli := mustTestRedis(t)
	defer cli.Close()

	cfg := DefaultHeartbeatBatchConfig()
	cfg.BatchIntervalMs = 200
	cfg.FlushThreshold = 500

	bhm := NewBatchHeartbeatManager(cli, cfg)
	bhm.Start()
	defer bhm.Stop()

	u1 := common.UserID("u100")
	bhm.RecordHeartbeat(u1, common.GameTypeLandlord)

	pending, _, _ := bhm.Stats()
	assert.Equal(t, 1, pending)

	time.Sleep(400 * time.Millisecond)
	pending, flushed, _ := bhm.Stats()
	assert.Equal(t, 0, pending, "after interval flush pending should be 0")
	assert.True(t, flushed > 0, "Flush count should be > 0")
}

func TestBatchHeartbeat_ThresholdFlush(t *testing.T) {
	cli := mustTestRedis(t)
	defer cli.Close()

	cfg := DefaultHeartbeatBatchConfig()
	cfg.BatchIntervalMs = 100000
	cfg.FlushThreshold = 3
	cfg.MaxPendingPerUser = 10

	bhm := NewBatchHeartbeatManager(cli, cfg)
	bhm.Start()
	defer bhm.Stop()

	bhm.RecordHeartbeat(common.UserID("u1"), common.GameTypeLandlord)
	bhm.RecordHeartbeat(common.UserID("u2"), common.GameTypeLandlord)
	pending, _, _ := bhm.Stats()
	assert.Equal(t, 2, pending)

	bhm.RecordHeartbeat(common.UserID("u3"), common.GameTypeLandlord)

	time.Sleep(250 * time.Millisecond)
	pending, flushed, _ := bhm.Stats()
	assert.True(t, flushed >= 1, "threshold flush must happen")
	assert.Equal(t, 0, pending)
}

func TestBatchHeartbeat_ThrottlePerUser(t *testing.T) {
	cli := mustTestRedis(t)
	defer cli.Close()

	cfg := DefaultHeartbeatBatchConfig()
	cfg.FlushThreshold = 100
	cfg.MaxPendingPerUser = 2

	bhm := NewBatchHeartbeatManager(cli, cfg)
	bhm.Start()
	defer bhm.Stop()

	u1 := common.UserID("u1")
	bhm.RecordHeartbeat(u1, common.GameTypeLandlord)
	bhm.RecordHeartbeat(u1, common.GameTypeLandlord)
	bhm.RecordHeartbeat(u1, common.GameTypeLandlord)
	bhm.RecordHeartbeat(u1, common.GameTypeLandlord)

	pending, _, dropped := bhm.Stats()
	assert.Equal(t, 2, pending, "MaxPendingPerUser limits duplicates")
	assert.True(t, dropped > 0)
}

func TestBatchHeartbeat_IsUserOnline(t *testing.T) {
	cli := mustTestRedis(t)
	defer cli.Close()

	cfg := DefaultHeartbeatBatchConfig()
	cfg.FlushThreshold = 1
	cfg.MaxPendingPerUser = 100
	cfg.BatchIntervalMs = 20

	bhm := NewBatchHeartbeatManager(cli, cfg)
	bhm.Start()
	defer bhm.Stop()

	u1 := common.UserID("u_online_1")
	u2 := common.UserID("u_online_2")
	bhm.RecordHeartbeat(u1, common.GameTypeLandlord)
	bhm.RecordHeartbeat(u2, common.GameTypeLandlord)

	time.Sleep(350 * time.Millisecond)

	online, err := bhm.IsUserOnline(u1, common.GameTypeLandlord)
	assert.NoError(t, err)
	assert.True(t, online, "recently heartbeated user should be online")

	off, err := bhm.IsUserOnline(common.UserID("nobody"), common.GameTypeLandlord)
	assert.NoError(t, err)
	assert.False(t, off)
}

func TestBatchHeartbeat_ManualFlush(t *testing.T) {
	cli := mustTestRedis(t)
	defer cli.Close()

	cfg := DefaultHeartbeatBatchConfig()
	cfg.FlushThreshold = 9999
	cfg.BatchIntervalMs = 99999

	bhm := NewBatchHeartbeatManager(cli, cfg)
	bhm.Start()
	defer bhm.Stop()

	for i := 0; i < 10; i++ {
		bhm.RecordHeartbeat(common.UserID(common.GenerateID()), common.GameTypeLandlord)
	}

	pending, _, _ := bhm.Stats()
	assert.Equal(t, 10, pending)
	bhm.Flush()
	time.Sleep(150 * time.Millisecond)
	pending, _, _ = bhm.Stats()
	assert.Equal(t, 0, pending)
}
