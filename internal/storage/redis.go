package storage

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"go.uber.org/zap"
)

var redisClient *redis.Client

type RedisClient struct {
	client *redis.Client
}

func InitRedis(cfg *config.RedisConfig) error {
	logger.Info("connecting to redis",
		zap.String("host", cfg.Host),
		zap.Int("port", cfg.Port),
		zap.Int("db", cfg.DB),
	)

	opts := &redis.Options{
		Addr:     cfg.Addr(),
		Password: cfg.Password,
		DB:       cfg.DB,
		PoolSize: cfg.PoolSize,
	}

	client := redis.NewClient(opts)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("failed to connect redis: %w", err)
	}

	redisClient = client
	logger.Info("redis connected successfully")
	return nil
}

func GetRedis() *redis.Client {
	if redisClient == nil {
		logger.Fatal("redis not initialized")
	}
	return redisClient
}

func CloseRedis() error {
	if redisClient != nil {
		return redisClient.Close()
	}
	return nil
}

func (r *RedisClient) AcquireLock(ctx context.Context, key string, value string, ttl time.Duration) (bool, error) {
	ok, err := redisClient.SetNX(ctx, key, value, ttl).Result()
	if err != nil {
		return false, err
	}
	return ok, nil
}

func (r *RedisClient) ReleaseLock(ctx context.Context, key string, value string) (bool, error) {
	script := `
		if redis.call("GET", KEYS[1]) == ARGV[1] then
			return redis.call("DEL", KEYS[1])
		else
			return 0
		end
	`
	result, err := redisClient.Eval(ctx, script, []string{key}, value).Int64()
	if err != nil {
		return false, err
	}
	return result == 1, nil
}

func (r *RedisClient) Deduplicate(ctx context.Context, key string, ttl time.Duration) (bool, error) {
	ok, err := redisClient.SetNX(ctx, "dedup:"+key, "1", ttl).Result()
	if err != nil {
		return false, err
	}
	return ok, nil
}

func (r *RedisClient) Enqueue(ctx context.Context, queue string, payload string) error {
	return redisClient.LPush(ctx, "queue:"+queue, payload).Err()
}

func (r *RedisClient) Dequeue(ctx context.Context, queue string, timeout time.Duration) (string, error) {
	result, err := redisClient.BRPop(ctx, timeout, "queue:"+queue).Result()
	if err != nil {
		if err == redis.Nil {
			return "", nil
		}
		return "", err
	}
	if len(result) < 2 {
		return "", nil
	}
	return result[1], nil
}

func (r *RedisClient) Publish(ctx context.Context, channel string, payload string) error {
	return redisClient.Publish(ctx, channel, payload).Err()
}

func (r *RedisClient) Subscribe(ctx context.Context, channel string) *redis.PubSub {
	return redisClient.Subscribe(ctx, channel)
}
