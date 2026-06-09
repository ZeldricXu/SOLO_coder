package cache

import (
	"context"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
	"pointcloud-platform/config"
)

var Client *redis.Client
var ctx = context.Background()

func Init(cfg *config.RedisConfig) error {
	Client = redis.NewClient(&redis.Options{
		Addr:     cfg.Addr(),
		Password: cfg.Password,
		DB:       cfg.DB,
	})

	if err := Client.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("failed to connect to redis: %w", err)
	}

	return nil
}

func Get(key string) (string, error) {
	if Client == nil {
		return "", fmt.Errorf("redis client not initialized")
	}
	return Client.Get(ctx, key).Result()
}

func Set(key string, value interface{}, ttl int) error {
	if Client == nil {
		return fmt.Errorf("redis client not initialized")
	}
	return Client.Set(ctx, key, value, time.Duration(ttl)*time.Second).Err()
}

func SetBytes(key string, value []byte, ttl int) error {
	if Client == nil {
		return fmt.Errorf("redis client not initialized")
	}
	return Client.Set(ctx, key, value, time.Duration(ttl)*time.Second).Err()
}

func GetBytes(key string) ([]byte, error) {
	if Client == nil {
		return nil, fmt.Errorf("redis client not initialized")
	}
	return Client.Get(ctx, key).Bytes()
}

func Delete(key string) error {
	if Client == nil {
		return fmt.Errorf("redis client not initialized")
	}
	return Client.Del(ctx, key).Err()
}

func Exists(key string) (bool, error) {
	if Client == nil {
		return false, fmt.Errorf("redis client not initialized")
	}
	result, err := Client.Exists(ctx, key).Result()
	return result > 0, err
}

func Close() {
	if Client != nil {
		Client.Close()
	}
}

func TileKey(datasetID string, lod int, x, y, z int64) string {
	return fmt.Sprintf("tile:%s:%d:%d:%d:%d", datasetID, lod, x, y, z)
}

func HotTileKey(datasetID string) string {
	return fmt.Sprintf("hot_tiles:%s", datasetID)
}

func IncrementTileHit(datasetID string, lod int, x, y, z int64) error {
	if Client == nil {
		return fmt.Errorf("redis client not initialized")
	}
	key := HotTileKey(datasetID)
	field := fmt.Sprintf("%d:%d:%d:%d", lod, x, y, z)
	return Client.ZIncrBy(ctx, key, 1, field).Err()
}

func GetHotTiles(datasetID string, count int) ([]string, error) {
	if Client == nil {
		return nil, fmt.Errorf("redis client not initialized")
	}
	key := HotTileKey(datasetID)
	result, err := Client.ZRevRange(ctx, key, 0, int64(count-1)).Result()
	return result, err
}
