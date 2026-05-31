package cache

import (
	"context"
	"depguard/config"
	"fmt"
	"github.com/go-redis/redis/v9"
	"sync"
	"time"
)

var (
	client *redis.Client
	once   sync.Once
)

func Get() *redis.Client {
	once.Do(func() {
		cfg := config.Get().Redis
		client = redis.NewClient(&redis.Options{
			Addr:     fmt.Sprintf("%s:%s", cfg.Host, cfg.Port),
			Password: cfg.Password,
			DB:       cfg.DB,
		})
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := client.Ping(ctx).Err(); err != nil {
			panic("failed to connect redis: " + err.Error())
		}
	})
	return client
}

func Close() {
	if client != nil {
		_ = client.Close()
	}
}
