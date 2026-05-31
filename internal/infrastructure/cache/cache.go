package cache

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/edgevision/edgevision/internal/infrastructure/config"
	"github.com/redis/go-redis/v9"
)

var (
	client *redis.Client
	once   sync.Once
)

type RedisClient struct {
	Client *redis.Client
}

func New(cfg config.CacheConfig) (*RedisClient, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Password: cfg.Password,
		DB:       cfg.DB,
		PoolSize: cfg.PoolSize,
	})

	if err := client.Ping(context.Background()).Err(); err != nil {
		return nil, fmt.Errorf("redis ping failed: %w", err)
	}

	return &RedisClient{Client: client}, nil
}

func (r *RedisClient) Close() error {
	return r.Client.Close()
}

type Cache struct {
	Client *redis.Client
}

func NewCache(redisClient *redis.Client) *Cache {
	return &Cache{Client: redisClient}
}

func (c *Cache) Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	return c.Client.Set(ctx, key, value, expiration).Err()
}

func (c *Cache) Get(ctx context.Context, key string) (string, error) {
	return c.Client.Get(ctx, key).Result()
}

func (c *Cache) Del(ctx context.Context, keys ...string) error {
	return c.Client.Del(ctx, keys...).Err()
}

func (c *Cache) Exists(ctx context.Context, key string) (bool, error) {
	result, err := c.Client.Exists(ctx, key).Result()
	return result > 0, err
}

func (c *Cache) HSet(ctx context.Context, key string, values ...interface{}) error {
	return c.Client.HSet(ctx, key, values...).Err()
}

func (c *Cache) HGet(ctx context.Context, key, field string) (string, error) {
	return c.Client.HGet(ctx, key, field).Result()
}

func (c *Cache) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return c.Client.HGetAll(ctx, key).Result()
}

func (c *Cache) Publish(ctx context.Context, channel string, message interface{}) error {
	return c.Client.Publish(ctx, channel, message).Err()
}

func (c *Cache) Subscribe(ctx context.Context, channels ...string) *redis.PubSub {
	return c.Client.Subscribe(ctx, channels...)
}

func (c *Cache) Pipeline() redis.Pipeliner {
	return c.Client.Pipeline()
}

func Init(cfg *config.CacheConfig) {
	once.Do(func() {
		client = redis.NewClient(&redis.Options{
			Addr:     fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
			Password: cfg.Password,
			DB:       cfg.DB,
			PoolSize: cfg.PoolSize,
		})
	})
}

func GetClient() *redis.Client {
	return client
}

func Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	return client.Set(ctx, key, value, expiration).Err()
}

func Get(ctx context.Context, key string) (string, error) {
	return client.Get(ctx, key).Result()
}

func Del(ctx context.Context, keys ...string) error {
	return client.Del(ctx, keys...).Err()
}

func Exists(ctx context.Context, key string) (bool, error) {
	result, err := client.Exists(ctx, key).Result()
	return result > 0, err
}

func HSet(ctx context.Context, key string, values ...interface{}) error {
	return client.HSet(ctx, key, values...).Err()
}

func HGet(ctx context.Context, key, field string) (string, error) {
	return client.HGet(ctx, key, field).Result()
}

func HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return client.HGetAll(ctx, key).Result()
}

func Publish(ctx context.Context, channel string, message interface{}) error {
	return client.Publish(ctx, channel, message).Err()
}

func Subscribe(ctx context.Context, channels ...string) *redis.PubSub {
	return client.Subscribe(ctx, channels...)
}

func Pipeline() redis.Pipeliner {
	return client.Pipeline()
}
