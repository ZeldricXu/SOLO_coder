package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
)

type RedisStore struct {
	client *redis.Client
}

func NewRedisStore(addr, password string, db int) (*RedisStore, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: password,
		DB:       db,
		PoolSize: 100,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, err
	}

	return &RedisStore{client: client}, nil
}

func (s *RedisStore) clientKey(userID common.UserID) string {
	return fmt.Sprintf("online:%s", string(userID))
}

func (s *RedisStore) roomKey(roomID common.RoomID) string {
	return fmt.Sprintf("room:%s", string(roomID))
}

func (s *RedisStore) SetOnline(userID common.UserID, roomID common.RoomID, ttl time.Duration) error {
	ctx := context.Background()
	pipe := s.client.TxPipeline()
	pipe.Set(ctx, s.clientKey(userID), string(roomID), ttl)
	if roomID != "" {
		pipe.SAdd(ctx, "online_users", string(userID))
	}
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) SetOffline(userID common.UserID) error {
	ctx := context.Background()
	pipe := s.client.TxPipeline()
	pipe.Del(ctx, s.clientKey(userID))
	pipe.SRem(ctx, "online_users", string(userID))
	_, err := pipe.Exec(ctx)
	return err
}

func (s *RedisStore) IsOnline(userID common.UserID) (bool, common.RoomID, error) {
	ctx := context.Background()
	val, err := s.client.Get(ctx, s.clientKey(userID)).Result()
	if err == redis.Nil {
		return false, "", nil
	}
	if err != nil {
		return false, "", err
	}
	return true, common.RoomID(val), nil
}

func (s *RedisStore) GetOnlineCount() (int64, error) {
	ctx := context.Background()
	return s.client.SCard(ctx, "online_users").Result()
}

func (s *RedisStore) GetOnlineUsers() ([]common.UserID, error) {
	ctx := context.Background()
	members, err := s.client.SMembers(ctx, "online_users").Result()
	if err != nil {
		return nil, err
	}
	users := make([]common.UserID, len(members))
	for i, m := range members {
		users[i] = common.UserID(m)
	}
	return users, nil
}

func (s *RedisStore) SetRoomState(roomID common.RoomID, state interface{}, ttl time.Duration) error {
	ctx := context.Background()
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	return s.client.Set(ctx, s.roomKey(roomID), data, ttl).Err()
}

func (s *RedisStore) GetRoomState(roomID common.RoomID, out interface{}) error {
	ctx := context.Background()
	data, err := s.client.Get(ctx, s.roomKey(roomID)).Bytes()
	if err != nil {
		return err
	}
	return json.Unmarshal(data, out)
}

func (s *RedisStore) DelRoomState(roomID common.RoomID) error {
	ctx := context.Background()
	return s.client.Del(ctx, s.roomKey(roomID)).Err()
}

func (s *RedisStore) Publish(channel string, msg interface{}) error {
	ctx := context.Background()
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	return s.client.Publish(ctx, channel, data).Err()
}

func (s *RedisStore) Subscribe(channels ...string) *redis.PubSub {
	return s.client.Subscribe(context.Background(), channels...)
}

func (s *RedisStore) Close() error {
	return s.client.Close()
}

func (s *RedisStore) GetClient() *redis.Client {
	return s.client
}
