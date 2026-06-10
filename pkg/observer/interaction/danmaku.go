package interaction

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
)

type Danmaku struct {
	ID        string          `json:"id"`
	RoomID    common.RoomID   `json:"room_id"`
	UserID    common.UserID   `json:"user_id"`
	Nickname  string          `json:"nickname"`
	Content   string          `json:"content"`
	Color     string          `json:"color,omitempty"`
	Timestamp int64           `json:"timestamp"`
	Level     int             `json:"level"`
}

type DanmakuManager struct {
	redis       *redis.Client
	maxHistory  int
	roomCtx     map[common.RoomID]*danmakuContext
}

type danmakuContext struct {
	lastCleanup int64
}

func NewDanmakuManager(redisClient *redis.Client) *DanmakuManager {
	return &DanmakuManager{
		redis:      redisClient,
		maxHistory: 1000,
		roomCtx:    make(map[common.RoomID]*danmakuContext),
	}
}

func (dm *DanmakuManager) Send(roomID common.RoomID, userID common.UserID, nickname string, content string, color string, level int) (*Danmaku, error) {
	if len(content) == 0 || len(content) > 100 {
		return nil, fmt.Errorf("invalid content length: %d", len(content))
	}

	d := &Danmaku{
		ID:        common.GenerateID(),
		RoomID:    roomID,
		UserID:    userID,
		Nickname:  nickname,
		Content:   content,
		Color:     color,
		Timestamp: common.NowMs(),
		Level:     level,
	}

	if dm.redis != nil {
		ctx := context.Background()
		data, err := json.Marshal(d)
		if err != nil {
			return nil, err
		}

		key := dm.danmakuKey(roomID)
		pipe := dm.redis.TxPipeline()
		pipe.ZAdd(ctx, key, &redis.Z{
			Score:  float64(d.Timestamp),
			Member: string(data),
		})
		pipe.ZRemRangeByRank(ctx, key, 0, -int64(dm.maxHistory)-1)
		_, err = pipe.Exec(ctx)
		if err != nil {
			common.LogWarn("redis save danmaku failed: %v, fallback to memory only", err)
		}
	}

	return d, nil
}

func (dm *DanmakuManager) GetHistory(roomID common.RoomID, sinceTs int64, limit int) ([]*Danmaku, error) {
	if limit <= 0 || limit > dm.maxHistory {
		limit = 50
	}

	if dm.redis != nil {
		ctx := context.Background()
		key := dm.danmakuKey(roomID)

		var members []string
		var err error

		if sinceTs > 0 {
			members, err = dm.redis.ZRangeByScore(ctx, key, &redis.ZRangeBy{
				Min:    fmt.Sprintf("%d", sinceTs+1),
				Max:    "+inf",
				Offset: 0,
				Count:  int64(limit),
			}).Result()
		} else {
			members, err = dm.redis.ZRevRange(ctx, key, 0, int64(limit)-1).Result()
		}

		if err != nil {
			return nil, err
		}

		result := make([]*Danmaku, 0, len(members))
		for _, m := range members {
			var d Danmaku
			if err := json.Unmarshal([]byte(m), &d); err == nil {
				result = append(result, &d)
			}
		}

		if sinceTs > 0 {
			return result, nil
		}
		for i, j := 0, len(result)-1; i < j; i, j = i+1, j-1 {
			result[i], result[j] = result[j], result[i]
		}
		return result, nil
	}

	return []*Danmaku{}, nil
}

func (dm *DanmakuManager) Cleanup(roomID common.RoomID) {
	if dm.redis != nil {
		ctx := context.Background()
		dm.redis.Del(ctx, dm.danmakuKey(roomID))
	}
	delete(dm.roomCtx, roomID)
}

func (dm *DanmakuManager) CleanupExpired(ttl time.Duration) {
	if dm.redis != nil {
		return
	}
	now := common.NowMs()
	cutoff := now - ttl.Milliseconds()

	for roomID, ctx := range dm.roomCtx {
		if ctx.lastCleanup < cutoff {
			dm.Cleanup(roomID)
		}
	}
}

func (dm *DanmakuManager) danmakuKey(roomID common.RoomID) string {
	return fmt.Sprintf("danmaku:%s", roomID)
}
