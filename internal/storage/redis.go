package storage

import (
	"context"
	"encoding/json"
	"time"

	"github.com/go-redis/redis/v8"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

type RedisStore struct {
	config *config.RedisConfig
	client *redis.Client
	ctx    context.Context
}

func NewRedisStore(cfg *config.RedisConfig) (*RedisStore, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     cfg.Address,
		Password: cfg.Password,
		DB:       cfg.DB,
	})

	ctx := context.Background()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, err
	}

	return &RedisStore{
		config: cfg,
		client: client,
		ctx:    ctx,
	}, nil
}

func (r *RedisStore) SetWindowState(key string, state interface{}, ttl time.Duration) error {
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	return r.client.Set(r.ctx, "window:"+key, data, ttl).Err()
}

func (r *RedisStore) GetWindowState(key string, result interface{}) error {
	data, err := r.client.Get(r.ctx, "window:"+key).Bytes()
	if err != nil {
		return err
	}
	return json.Unmarshal(data, result)
}

func (r *RedisStore) DeleteWindowState(key string) error {
	return r.client.Del(r.ctx, "window:"+key).Err()
}

func (r *RedisStore) Deduplicate(key string, value string, ttl time.Duration) (bool, error) {
	exists, err := r.client.SetNX(r.ctx, "dedup:"+key, value, ttl).Result()
	if err != nil {
		return false, err
	}
	return exists, nil
}

func (r *RedisStore) IsDuplicate(key string) (bool, error) {
	exists, err := r.client.Exists(r.ctx, "dedup:"+key).Result()
	if err != nil {
		return false, err
	}
	return exists > 0, nil
}

func (r *RedisStore) CacheLog(log *models.LogEntry, ttl time.Duration) error {
	data, err := json.Marshal(log)
	if err != nil {
		return err
	}
	return r.client.Set(r.ctx, "log:"+log.ID, data, ttl).Err()
}

func (r *RedisStore) GetLog(id string) (*models.LogEntry, error) {
	data, err := r.client.Get(r.ctx, "log:"+id).Bytes()
	if err != nil {
		return nil, err
	}

	var log models.LogEntry
	if err := json.Unmarshal(data, &log); err != nil {
		return nil, err
	}
	return &log, nil
}

func (r *RedisStore) IncrementCounter(key string) (int64, error) {
	return r.client.Incr(r.ctx, "counter:"+key).Result()
}

func (r *RedisStore) GetCounter(key string) (int64, error) {
	val, err := r.client.Get(r.ctx, "counter:"+key).Int64()
	if err == redis.Nil {
		return 0, nil
	}
	return val, err
}

func (r *RedisStore) SetCounter(key string, value int64, ttl time.Duration) error {
	return r.client.Set(r.ctx, "counter:"+key, value, ttl).Err()
}

func (r *RedisStore) AddToSet(key string, members ...string) error {
	interfaceMembers := make([]interface{}, len(members))
	for i, m := range members {
		interfaceMembers[i] = m
	}
	return r.client.SAdd(r.ctx, "set:"+key, interfaceMembers...).Err()
}

func (r *RedisStore) IsMember(key string, member string) (bool, error) {
	return r.client.SIsMember(r.ctx, "set:"+key, member).Result()
}

func (r *RedisStore) Publish(channel string, message interface{}) error {
	data, err := json.Marshal(message)
	if err != nil {
		return err
	}
	return r.client.Publish(r.ctx, channel, data).Err()
}

func (r *RedisStore) Subscribe(channel string) *redis.PubSub {
	return r.client.Subscribe(r.ctx, channel)
}

func (r *RedisStore) LPush(key string, values ...interface{}) error {
	return r.client.LPush(r.ctx, "list:"+key, values...).Err()
}

func (r *RedisStore) RPop(key string) (string, error) {
	return r.client.RPop(r.ctx, "list:"+key).Result()
}

func (r *RedisStore) LLen(key string) (int64, error) {
	return r.client.LLen(r.ctx, "list:"+key).Result()
}

func (r *RedisStore) SetWithTTL(key string, value interface{}, ttl time.Duration) error {
	return r.client.Set(r.ctx, key, value, ttl).Err()
}

func (r *RedisStore) Get(key string) (string, error) {
	return r.client.Get(r.ctx, key).Result()
}

func (r *RedisStore) Delete(key string) error {
	return r.client.Del(r.ctx, key).Err()
}

func (r *RedisStore) Keys(pattern string) ([]string, error) {
	return r.client.Keys(r.ctx, pattern).Result()
}

func (r *RedisStore) Close() error {
	return r.client.Close()
}
