package storage

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisConfig struct {
	Address    string
	Password   string
	DB         int
	PoolSize   int
	MaxRetries int
	Timeout    time.Duration
}

type RedisClient struct {
	client *redis.Client
	config RedisConfig
}

func NewRedisClient(cfg RedisConfig) (*RedisClient, error) {
	if cfg.Address == "" {
		return nil, fmt.Errorf("redis address cannot be empty")
	}

	poolSize := cfg.PoolSize
	if poolSize <= 0 {
		poolSize = 10
	}

	maxRetries := cfg.MaxRetries
	if maxRetries <= 0 {
		maxRetries = 3
	}

	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}

	client := redis.NewClient(&redis.Options{
		Addr:         cfg.Address,
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     poolSize,
		MaxRetries:   maxRetries,
		DialTimeout:  timeout,
		ReadTimeout:  timeout,
		WriteTimeout: timeout,
	})

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to redis: %w", err)
	}

	return &RedisClient{
		client: client,
		config: cfg,
	}, nil
}

func (r *RedisClient) Client() *redis.Client {
	return r.client
}

func (r *RedisClient) Get(ctx context.Context, key string) (string, error) {
	return r.client.Get(ctx, key).Result()
}

func (r *RedisClient) Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	return r.client.Set(ctx, key, value, expiration).Err()
}

func (r *RedisClient) Delete(ctx context.Context, key string) error {
	return r.client.Del(ctx, key).Err()
}

func (r *RedisClient) Exists(ctx context.Context, key string) (bool, error) {
	result, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, err
	}
	return result > 0, nil
}

func (r *RedisClient) Incr(ctx context.Context, key string) (int64, error) {
	return r.client.Incr(ctx, key).Result()
}

func (r *RedisClient) Decr(ctx context.Context, key string) (int64, error) {
	return r.client.Decr(ctx, key).Result()
}

func (r *RedisClient) Expire(ctx context.Context, key string, expiration time.Duration) error {
	return r.client.Expire(ctx, key, expiration).Err()
}

func (r *RedisClient) HGet(ctx context.Context, key, field string) (string, error) {
	return r.client.HGet(ctx, key, field).Result()
}

func (r *RedisClient) HSet(ctx context.Context, key string, fields ...interface{}) error {
	return r.client.HSet(ctx, key, fields...).Err()
}

func (r *RedisClient) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return r.client.HGetAll(ctx, key).Result()
}

func (r *RedisClient) ZAdd(ctx context.Context, key string, score float64, member string) error {
	return r.client.ZAdd(ctx, key, redis.Z{Score: score, Member: member}).Err()
}

func (r *RedisClient) ZRangeByScore(ctx context.Context, key string, min, max float64, offset, count int64) ([]string, error) {
	return r.client.ZRangeByScore(ctx, key, &redis.ZRangeBy{
		Min:    fmt.Sprintf("%f", min),
		Max:    fmt.Sprintf("%f", max),
		Offset: offset,
		Count:  count,
	}).Result()
}

func (r *RedisClient) ZRemRangeByScore(ctx context.Context, key string, min, max float64) error {
	return r.client.ZRemRangeByScore(ctx, key, fmt.Sprintf("%f", min), fmt.Sprintf("%f", max)).Err()
}

func (r *RedisClient) Eval(ctx context.Context, script string, keys []string, args ...interface{}) (interface{}, error) {
	return r.client.Eval(ctx, script, keys, args...).Result()
}

func (r *RedisClient) Close() error {
	return r.client.Close()
}

func (r *RedisClient) Ping(ctx context.Context) error {
	return r.client.Ping(ctx).Err()
}
