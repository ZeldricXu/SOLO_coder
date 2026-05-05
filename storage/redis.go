package storage

import (
	"context"
	"socialfeed/config"

	"github.com/redis/go-redis/v9"
)

type RedisDB struct {
	Client *redis.Client
}

func NewRedisDB(cfg *config.RedisConfig) (*RedisDB, error) {
	client := redis.NewClient(&redis.Options{
		Addr:     cfg.Addr,
		Password: cfg.Password,
		DB:       cfg.DB,
	})

	if _, err := client.Ping(context.Background()).Result(); err != nil {
		return nil, err
	}

	return &RedisDB{
		Client: client,
	}, nil
}

func (r *RedisDB) Close() error {
	return r.Client.Close()
}
