package redis

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisClient struct {
	client *redis.Client
	mu     sync.RWMutex
}

func NewRedisClient(addr, password string, db int) (*RedisClient, error) {
	if addr == "" {
		return nil, fmt.Errorf("redis address is required")
	}

	client := redis.NewClient(&redis.Options{
		Addr:         addr,
		Password:     password,
		DB:           db,
		PoolSize:     50,
		MinIdleConns: 10,
		MaxRetries:   3,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
		PoolTimeout:  4 * time.Second,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to ping redis: %w", err)
	}

	return &RedisClient{
		client: client,
	}, nil
}

func (r *RedisClient) GetClient() *redis.Client {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.client
}

func (r *RedisClient) Ping() error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	r.mu.RLock()
	defer r.mu.RUnlock()

	return r.client.Ping(ctx).Err()
}

func (r *RedisClient) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.client != nil {
		return r.client.Close()
	}
	return nil
}
