package cache

import (
	"context"
	"fmt"
	"log"
	"time"

	"depguard/internal/config"

	"github.com/go-redis/redis/v8"
)

var RedisClient *redis.Client
var Ctx = context.Background()

func Init() {
	RedisClient = redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%s", config.AppConfig.RedisHost, config.AppConfig.RedisPort),
		Password: config.AppConfig.RedisPassword,
		DB:       config.AppConfig.RedisDB,
	})

	if err := RedisClient.Ping(Ctx).Err(); err != nil {
		log.Printf("Warning: Failed to connect to Redis: %v", err)
	} else {
		log.Println("Redis connection established")
	}
}

func Get(key string) (string, error) {
	return RedisClient.Get(Ctx, key).Result()
}

func Set(key string, value interface{}, expiration time.Duration) error {
	return RedisClient.Set(Ctx, key, value, expiration).Err()
}

func Delete(key string) error {
	return RedisClient.Del(Ctx, key).Err()
}

func Exists(key string) bool {
	result, err := RedisClient.Exists(Ctx, key).Result()
	return err == nil && result > 0
}
