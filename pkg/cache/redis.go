package cache

import (
	"context"
	"fmt"
	"github.com/redis/go-redis/v9"
	"github.com/solocoder/session138/pkg/config"
	"time"
)

var Client *redis.Client
var Ctx = context.Background()

func Init(cfg *config.RedisConfig) error {
	Client = redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Password: cfg.Password,
		DB:       cfg.DB,
	})

	return Client.Ping(Ctx).Err()
}

func Get(key string) (string, error) {
	return Client.Get(Ctx, key).Result()
}

func Set(key string, value interface{}, expiration time.Duration) error {
	return Client.Set(Ctx, key, value, expiration).Err()
}

func Delete(key string) error {
	return Client.Del(Ctx, key).Err()
}

func Publish(channel string, message interface{}) error {
	return Client.Publish(Ctx, channel, message).Err()
}
