package match

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
)

type HeartbeatBatchConfig struct {
	BatchIntervalMs   int
	FlushThreshold    int
	MaxPendingPerUser int
	RedisKeyPrefix    string
}

func DefaultHeartbeatBatchConfig() *HeartbeatBatchConfig {
	return &HeartbeatBatchConfig{
		BatchIntervalMs:   10000,
		FlushThreshold:    500,
		MaxPendingPerUser: 3,
		RedisKeyPrefix:    "hb",
	}
}

type HeartbeatEntry struct {
	UserID    common.UserID
	Timestamp int64
	GameType  common.GameType
}

type BatchHeartbeatManager struct {
	redisClient  *redis.Client
	config       *HeartbeatBatchConfig

	pendingMu    sync.Mutex
	pending      []HeartbeatEntry
	userCounts   map[common.UserID]int

	lastFlushTs  int64
	totalFlushed uint64
	totalDropped uint64

	running      bool
	shutdownCh   chan struct{}
	wg           sync.WaitGroup
}

func NewBatchHeartbeatManager(redisClient *redis.Client, config *HeartbeatBatchConfig) *BatchHeartbeatManager {
	if config == nil {
		config = DefaultHeartbeatBatchConfig()
	}

	return &BatchHeartbeatManager{
		redisClient: redisClient,
		config:      config,
		pending:     make([]HeartbeatEntry, 0, 1024),
		userCounts:  make(map[common.UserID]int),
		shutdownCh:  make(chan struct{}),
	}
}

func (bhm *BatchHeartbeatManager) Start() {
	if bhm.running {
		return
	}
	bhm.running = true

	bhm.wg.Add(1)
	go bhm.flushLoop()

	common.LogInfo("batch heartbeat manager started: interval=%dms, threshold=%d",
		bhm.config.BatchIntervalMs, bhm.config.FlushThreshold)
}

func (bhm *BatchHeartbeatManager) Stop() {
	if !bhm.running {
		return
	}
	bhm.running = false

	close(bhm.shutdownCh)
	bhm.wg.Wait()

	bhm.Flush()

	common.LogInfo("batch heartbeat manager stopped: flushed=%d, dropped=%d",
		bhm.totalFlushed, bhm.totalDropped)
}

func (bhm *BatchHeartbeatManager) RecordHeartbeat(userID common.UserID, gameType common.GameType) {
	bhm.pendingMu.Lock()

	count := bhm.userCounts[userID]
	if count >= bhm.config.MaxPendingPerUser {
		bhm.totalDropped++
		bhm.pendingMu.Unlock()
		return
	}

	bhm.pending = append(bhm.pending, HeartbeatEntry{
		UserID:    userID,
		Timestamp: common.NowMs(),
		GameType:  gameType,
	})
	bhm.userCounts[userID] = count + 1
	pendingLen := len(bhm.pending)

	bhm.pendingMu.Unlock()

	if pendingLen >= bhm.config.FlushThreshold {
		go bhm.Flush()
	}
}

func (bhm *BatchHeartbeatManager) flushLoop() {
	defer bhm.wg.Done()

	interval := time.Duration(bhm.config.BatchIntervalMs) * time.Millisecond
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-bhm.shutdownCh:
			return
		case <-ticker.C:
			bhm.Flush()
		}
	}
}

func (bhm *BatchHeartbeatManager) Flush() {
	bhm.pendingMu.Lock()

	if len(bhm.pending) == 0 {
		bhm.pendingMu.Unlock()
		return
	}

	entries := bhm.pending
	bhm.pending = make([]HeartbeatEntry, 0, len(entries))
	for uid := range bhm.userCounts {
		delete(bhm.userCounts, uid)
	}
	bhm.lastFlushTs = common.NowMs()

	bhm.pendingMu.Unlock()

	if bhm.redisClient == nil {
		return
	}

	if err := bhm.flushToRedis(entries); err != nil {
		common.LogWarn("batch heartbeat flush failed: %v", err)
		return
	}

	bhm.totalFlushed += uint64(len(entries))
}

func (bhm *BatchHeartbeatManager) flushToRedis(entries []HeartbeatEntry) error {
	ctx := context.Background()

	byGameType := make(map[common.GameType][]HeartbeatEntry)
	for _, e := range entries {
		byGameType[e.GameType] = append(byGameType[e.GameType], e)
	}

	pipe := bhm.redisClient.Pipeline()

	for gameType, gameEntries := range byGameType {
		onlineKey := fmt.Sprintf("%s:%s:online", bhm.config.RedisKeyPrefix, gameType)
		lastSeenKey := fmt.Sprintf("%s:%s:last_seen", bhm.config.RedisKeyPrefix, gameType)

		uniqueUsers := make(map[common.UserID]int64, len(gameEntries))
		for _, e := range gameEntries {
			if existing, ok := uniqueUsers[e.UserID]; !ok || e.Timestamp > existing {
				uniqueUsers[e.UserID] = e.Timestamp
			}
		}

		z := make([]*redis.Z, 0, len(uniqueUsers))
		hashData := make(map[string]interface{}, len(uniqueUsers))
		for uid, ts := range uniqueUsers {
			z = append(z, &redis.Z{
				Score:  float64(ts),
				Member: string(uid),
			})
			hashData[string(uid)] = ts
		}

		if len(z) > 0 {
			pipe.ZAdd(ctx, onlineKey, z...)
			pipe.Expire(ctx, onlineKey, 10*time.Minute)
		}
		if len(hashData) > 0 {
			pipe.HSet(ctx, lastSeenKey, hashData)
			pipe.Expire(ctx, lastSeenKey, 30*time.Minute)
		}
	}

	_, err := pipe.Exec(ctx)
	return err
}

func (bhm *BatchHeartbeatManager) GetOnlineUsers(gameType common.GameType) ([]common.UserID, error) {
	if bhm.redisClient == nil {
		return nil, nil
	}

	ctx := context.Background()
	key := fmt.Sprintf("%s:%s:online", bhm.config.RedisKeyPrefix, gameType)

	members, err := bhm.redisClient.ZRevRange(ctx, key, 0, 9999).Result()
	if err != nil {
		return nil, err
	}

	result := make([]common.UserID, 0, len(members))
	for _, m := range members {
		result = append(result, common.UserID(m))
	}
	return result, nil
}

func (bhm *BatchHeartbeatManager) IsUserOnline(userID common.UserID, gameType common.GameType) (bool, error) {
	if bhm.redisClient == nil {
		return false, nil
	}

	ctx := context.Background()
	key := fmt.Sprintf("%s:%s:online", bhm.config.RedisKeyPrefix, gameType)

	score, err := bhm.redisClient.ZScore(ctx, key, string(userID)).Result()
	if err == redis.Nil {
		return false, nil
	}
	if err != nil {
		return false, err
	}

	now := common.NowMs()
	if now-int64(score) > 60000 {
		return false, nil
	}

	return true, nil
}

func (bhm *BatchHeartbeatManager) Stats() (pendingCount int, totalFlushed uint64, totalDropped uint64) {
	bhm.pendingMu.Lock()
	defer bhm.pendingMu.Unlock()

	return len(bhm.pending), bhm.totalFlushed, bhm.totalDropped
}

func (bhm *BatchHeartbeatManager) CleanupExpired(gameType common.GameType, timeoutMs int64) (int, error) {
	if bhm.redisClient == nil {
		return 0, nil
	}

	ctx := context.Background()
	key := fmt.Sprintf("%s:%s:online", bhm.config.RedisKeyPrefix, gameType)

	cutoff := float64(common.NowMs() - timeoutMs)
	removed, err := bhm.redisClient.ZRemRangeByScore(ctx, key, "-inf", fmt.Sprintf("%f", cutoff)).Result()
	if err != nil {
		return 0, err
	}

	return int(removed), nil
}
