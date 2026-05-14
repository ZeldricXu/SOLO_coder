package storage

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/sirupsen/logrus"
)

type RedisQueueConfig struct {
	Host       string
	Port       int
	Password   string
	DB         int
	PoolSize   int
	MaxRetries int
}

type RedisQueue struct {
	client  *redis.Client
	logger  *logrus.Logger
	ctx     context.Context
	cancel  context.CancelFunc
	mu      sync.RWMutex
	prefix  string
}

func DefaultRedisQueueConfig() *RedisQueueConfig {
	return &RedisQueueConfig{
		Host:       "localhost",
		Port:       6379,
		Password:   "",
		DB:         0,
		PoolSize:   10,
		MaxRetries: 3,
	}
}

func NewRedisQueue(config *RedisQueueConfig, logger *logrus.Logger) (*RedisQueue, error) {
	if config == nil {
		config = DefaultRedisQueueConfig()
	}

	client := redis.NewClient(&redis.Options{
		Addr:        fmt.Sprintf("%s:%d", config.Host, config.Port),
		Password:    config.Password,
		DB:          config.DB,
		PoolSize:    config.PoolSize,
		MaxRetries:  config.MaxRetries,
		DialTimeout: 5 * time.Second,
		ReadTimeout: 3 * time.Second,
		WriteTimeout: 3 * time.Second,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		logger.Warnf("Failed to connect to Redis, will use in-memory fallback: %v", err)
		return nil, errors.New("redis connection failed")
	}

	rq := &RedisQueue{
		client: client,
		logger: logger,
		prefix: "cachehub:sync:",
	}

	rq.ctx, rq.cancel = context.WithCancel(context.Background())

	logger.Infof("Redis queue initialized: %s:%d", config.Host, config.Port)
	return rq, nil
}

func (q *RedisQueue) Close() error {
	if q.cancel != nil {
		q.cancel()
	}
	if q.client != nil {
		return q.client.Close()
	}
	return nil
}

func (q *RedisQueue) buildKey(queueName string) string {
	return q.prefix + queueName
}

func (q *RedisQueue) Enqueue(queueName string, data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	key := q.buildKey(queueName)
	result := q.client.RPush(ctx, key, jsonData)
	if result.Err() != nil {
		q.logger.Errorf("Redis enqueue failed: %v", result.Err())
		return result.Err()
	}

	q.logger.Debugf("Enqueued item to %s", key)
	return nil
}

func (q *RedisQueue) Dequeue(queueName string, timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout+5*time.Second)
	defer cancel()

	key := q.buildKey(queueName)
	result := q.client.BLPop(ctx, timeout, key)
	if result.Err() != nil {
		if result.Err() == redis.Nil {
			return nil, nil
		}
		if errors.Is(result.Err(), context.DeadlineExceeded) {
			return nil, nil
		}
		q.logger.Errorf("Redis dequeue failed: %v", result.Err())
		return nil, result.Err()
	}

	vals, err := result.Result()
	if err != nil || len(vals) < 2 {
		return nil, nil
	}

	return []byte(vals[1]), nil
}

func (q *RedisQueue) DequeueNonBlocking(queueName string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(queueName)
	result := q.client.LPop(ctx, key)
	if result.Err() != nil {
		if result.Err() == redis.Nil {
			return nil, nil
		}
		q.logger.Errorf("Redis dequeue non-blocking failed: %v", result.Err())
		return nil, result.Err()
	}

	jsonData, err := result.Bytes()
	if err != nil {
		return nil, err
	}

	return jsonData, nil
}

func (q *RedisQueue) QueueSize(queueName string) (int64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(queueName)
	result := q.client.LLen(ctx, key)
	if result.Err() != nil {
		return 0, result.Err()
	}

	return result.Val(), nil
}

func (q *RedisQueue) ClearQueue(queueName string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(queueName)
	result := q.client.Del(ctx, key)
	if result.Err() != nil {
		return result.Err()
	}

	return nil
}

func (q *RedisQueue) EnqueueToSet(setName string, member string, score float64) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(setName)
	result := q.client.ZAdd(ctx, key, &redis.Z{
		Score:  score,
		Member: member,
	})
	if result.Err() != nil {
		q.logger.Errorf("Redis ZAdd failed: %v", result.Err())
		return result.Err()
	}

	return nil
}

func (q *RedisQueue) GetFromSetByScore(setName string, min, max float64, limit int64) ([]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(setName)
	result := q.client.ZRangeByScore(ctx, key, &redis.ZRangeBy{
		Min:    fmt.Sprintf("%f", min),
		Max:    fmt.Sprintf("%f", max),
		Count:  limit,
	})
	if result.Err() != nil {
		return nil, result.Err()
	}

	return result.Val(), nil
}

func (q *RedisQueue) RemoveFromSet(setName string, member string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(setName)
	result := q.client.ZRem(ctx, key, member)
	if result.Err() != nil {
		return result.Err()
	}

	return nil
}

func (q *RedisQueue) SetSize(setName string) (int64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	key := q.buildKey(setName)
	result := q.client.ZCard(ctx, key)
	if result.Err() != nil {
		return 0, result.Err()
	}

	return result.Val(), nil
}

func (q *RedisQueue) SetHashField(key, field string, value interface{}) error {
	jsonData, err := json.Marshal(value)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	hashKey := q.buildKey(key)
	result := q.client.HSet(ctx, hashKey, field, jsonData)
	if result.Err() != nil {
		return result.Err()
	}

	return nil
}

func (q *RedisQueue) GetHashField(key, field string, dest interface{}) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	hashKey := q.buildKey(key)
	result := q.client.HGet(ctx, hashKey, field)
	if result.Err() != nil {
		if result.Err() == redis.Nil {
			return errors.New("field not found")
		}
		return result.Err()
	}

	jsonData, err := result.Bytes()
	if err != nil {
		return err
	}

	return json.Unmarshal(jsonData, dest)
}

func (q *RedisQueue) GetAllHashFields(key string) (map[string]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	hashKey := q.buildKey(key)
	result := q.client.HGetAll(ctx, hashKey)
	if result.Err() != nil {
		return nil, result.Err()
	}

	return result.Val(), nil
}

func (q *RedisQueue) DeleteHashField(key, field string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	hashKey := q.buildKey(key)
	result := q.client.HDel(ctx, hashKey, field)
	return result.Err()
}

func (q *RedisQueue) IsAvailable() bool {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if q.client == nil {
		return false
	}

	err := q.client.Ping(ctx).Err()
	return err == nil
}

func (q *RedisQueue) ListQueues(pattern string) ([]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	searchPattern := q.prefix + pattern
	result := q.client.Keys(ctx, searchPattern)
	if result.Err() != nil {
		return nil, result.Err()
	}

	return result.Val(), nil
}

func (q *RedisQueue) Publish(channel string, message interface{}) error {
	jsonData, err := json.Marshal(message)
	if err != nil {
		return err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	channelKey := q.prefix + "channel:" + channel
	result := q.client.Publish(ctx, channelKey, jsonData)
	return result.Err()
}

func (q *RedisQueue) Subscribe(channel string) *redis.PubSub {
	channelKey := q.prefix + "channel:" + channel
	return q.client.Subscribe(context.Background(), channelKey)
}

type QueueStats struct {
	PendingQueueSize  int64 `json:"pending_queue_size"`
	RetryQueueSize    int64 `json:"retry_queue_size"`
	TotalProcessed    int64 `json:"total_processed"`
	TotalFailed       int64 `json:"total_failed"`
}

func (q *RedisQueue) GetQueueStats(sourceID string) (*QueueStats, error) {
	stats := &QueueStats{}

	pendingSize, err := q.QueueSize(sourceID + ":pending")
	if err != nil {
		return nil, err
	}
	stats.PendingQueueSize = pendingSize

	retrySize, err := q.SetSize(sourceID + ":retry")
	if err != nil {
		return nil, err
	}
	stats.RetryQueueSize = retrySize

	return stats, nil
}

func (q *RedisQueue) SaveStats(sourceID string, stats *QueueStats) error {
	return q.SetHashField(sourceID+":stats", "data", stats)
}

func (q *RedisQueue) LoadStats(sourceID string, dest *QueueStats) error {
	return q.GetHashField(sourceID+":stats", "data", dest)
}
