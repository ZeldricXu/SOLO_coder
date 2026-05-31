package cache

import (
	"context"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"

	"llmgateway/internal/infrastructure/logger"
)

type Config struct {
	Addr     string
	Password string
	DB       int
	PoolSize int
}

var (
	client *redis.Client
	ctx    = context.Background()
)

func Init(cfg Config) error {
	client = redis.NewClient(&redis.Options{
		Addr:     cfg.Addr,
		Password: cfg.Password,
		DB:       cfg.DB,
		PoolSize: cfg.PoolSize,
	})

	if err := client.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("failed to connect redis: %w", err)
	}

	logger.Info("redis connected successfully")
	return nil
}

func Client() *redis.Client {
	return client
}

func Get(ctx context.Context, key string) (string, error) {
	return client.Get(ctx, key).Result()
}

func Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	return client.Set(ctx, key, value, expiration).Err()
}

func Del(ctx context.Context, key string) error {
	return client.Del(ctx, key).Err()
}

func Exists(ctx context.Context, key string) (bool, error) {
	n, err := client.Exists(ctx, key).Result()
	return n > 0, err
}

func HGet(ctx context.Context, key, field string) (string, error) {
	return client.HGet(ctx, key, field).Result()
}

func HSet(ctx context.Context, key string, values ...interface{}) error {
	return client.HSet(ctx, key, values...).Err()
}

func HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return client.HGetAll(ctx, key).Result()
}

func LPush(ctx context.Context, key string, values ...interface{}) error {
	return client.LPush(ctx, key, values...).Err()
}

func RPop(ctx context.Context, key string) (string, error) {
	return client.RPop(ctx, key).Result()
}

func ZAdd(ctx context.Context, key string, score float64, member string) error {
	return client.ZAdd(ctx, key, &redis.Z{Score: score, Member: member}).Err()
}

func ZRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return client.ZRange(ctx, key, start, stop).Result()
}

func Publish(ctx context.Context, channel string, message interface{}) error {
	return client.Publish(ctx, channel, message).Err()
}

func Subscribe(ctx context.Context, channels ...string) *redis.PubSub {
	return client.Subscribe(ctx, channels...)
}

func Close() error {
	if client != nil {
		return client.Close()
	}
	return nil
}
