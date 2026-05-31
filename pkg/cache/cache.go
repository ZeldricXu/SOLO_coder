package cache

import (
	"context"
	"time"

	"github.com/datatransform/platform/pkg/config"
	"github.com/go-redis/redis/v8"
)

var (
	Client *redis.Client
	ctx    = context.Background()
)

func Init(cfg *config.RedisConfig) error {
	Client = redis.NewClient(&redis.Options{
		Addr:     cfg.Addr,
		Password: cfg.Password,
		DB:       cfg.DB,
	})

	_, err := Client.Ping(ctx).Result()
	return err
}

func GetClient() *redis.Client {
	return Client
}

func Close() error {
	if Client == nil {
		return nil
	}
	return Client.Close()
}

func Set(key string, value interface{}, expiration time.Duration) error {
	return Client.Set(ctx, key, value, expiration).Err()
}

func Get(key string) (string, error) {
	return Client.Get(ctx, key).Result()
}

func Del(key string) error {
	return Client.Del(ctx, key).Err()
}

func Exists(key string) (bool, error) {
	count, err := Client.Exists(ctx, key).Result()
	return count > 0, err
}

func Publish(channel string, message interface{}) error {
	return Client.Publish(ctx, channel, message).Err()
}

func Subscribe(channel string) *redis.PubSub {
	return Client.Subscribe(ctx, channel)
}
