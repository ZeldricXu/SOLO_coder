package cache

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"session189/internal/infrastructure/logger"
)

var Client *redis.Client

type Config struct {
	Addr     string
	Password string
	DB       int
	PoolSize int
}

func Init(cfg Config) error {
	poolSize := cfg.PoolSize
	if poolSize <= 0 {
		poolSize = 10
	}

	Client = redis.NewClient(&redis.Options{
		Addr:     cfg.Addr,
		Password: cfg.Password,
		DB:       cfg.DB,
		PoolSize: poolSize,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := Client.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("ping redis failed: %w", err)
	}

	logger.Info("Redis connected successfully")
	return nil
}

func GetClient() *redis.Client {
	return Client
}

func Close() error {
	if Client != nil {
		return Client.Close()
	}
	return nil
}

func Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	return Client.Set(ctx, key, value, expiration).Err()
}

func Get(ctx context.Context, key string) (string, error) {
	return Client.Get(ctx, key).Result()
}

func Del(ctx context.Context, keys ...string) error {
	return Client.Del(ctx, keys...).Err()
}

func Exists(ctx context.Context, key string) (bool, error) {
	result, err := Client.Exists(ctx, key).Result()
	return result > 0, err
}

func Incr(ctx context.Context, key string) (int64, error) {
	return Client.Incr(ctx, key).Result()
}

func Decr(ctx context.Context, key string) (int64, error) {
	return Client.Decr(ctx, key).Result()
}

func Expire(ctx context.Context, key string, expiration time.Duration) error {
	return Client.Expire(ctx, key, expiration).Err()
}

func TTL(ctx context.Context, key string) (time.Duration, error) {
	return Client.TTL(ctx, key).Result()
}

func HSet(ctx context.Context, key string, values ...interface{}) error {
	return Client.HSet(ctx, key, values...).Err()
}

func HGet(ctx context.Context, key, field string) (string, error) {
	return Client.HGet(ctx, key, field).Result()
}

func HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return Client.HGetAll(ctx, key).Result()
}

func HDel(ctx context.Context, key string, fields ...string) error {
	return Client.HDel(ctx, key, fields...).Err()
}

func LPush(ctx context.Context, key string, values ...interface{}) error {
	return Client.LPush(ctx, key, values...).Err()
}

func RPop(ctx context.Context, key string) (string, error) {
	return Client.RPop(ctx, key).Result()
}

func LLen(ctx context.Context, key string) (int64, error) {
	return Client.LLen(ctx, key).Result()
}

func SAdd(ctx context.Context, key string, members ...interface{}) error {
	return Client.SAdd(ctx, key, members...).Err()
}

func SMembers(ctx context.Context, key string) ([]string, error) {
	return Client.SMembers(ctx, key).Result()
}

func SRem(ctx context.Context, key string, members ...interface{}) error {
	return Client.SRem(ctx, key, members...).Err()
}

func ZAdd(ctx context.Context, key string, members ...redis.Z) error {
	return Client.ZAdd(ctx, key, members...).Err()
}

func ZRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return Client.ZRange(ctx, key, start, stop).Result()
}

func ZRem(ctx context.Context, key string, members ...interface{}) error {
	return Client.ZRem(ctx, key, members...).Err()
}
