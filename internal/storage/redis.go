package storage

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/go-redis/redis/v8"

	"github.com/datateam/loganalyzer/internal/config"
)

type WindowStats struct {
	Count       int64
	ErrorCount  int64
	Sum         float64
	SumSquares  float64
	Values      []float64
}

var windowStatsScript = redis.NewScript(`
local keyPrefix = KEYS[1]
local startWindow = tonumber(ARGV[1])
local endWindow = tonumber(ARGV[2])
local step = tonumber(ARGV[3])

local count = 0
local errorCount = 0
local sum = 0.0
local sumSquares = 0.0
local values = {}

for w = startWindow, endWindow, step do
    local windowKey = keyPrefix .. ":" .. tostring(w)
    local results = redis.call("ZRANGE", windowKey, 0, -1)
    for _, r in ipairs(results) do
        local v = tonumber(r)
        if v then
            count = count + 1
            sum = sum + v
            sumSquares = sumSquares + v * v
            table.insert(values, v)
            if v > 0 then
                errorCount = errorCount + 1
            end
        end
    end
end

return {count, errorCount, tostring(sum), tostring(sumSquares), values}
`)

type RedisClient struct {
	client *redis.Client
	cfg    config.RedisConfig
	mock   *MockRedisClient
}

func (r *RedisClient) Mock() *MockRedisClient {
	return r.mock
}

func NewRedisClient(cfg config.RedisConfig) (*RedisClient, error) {
	client := redis.NewClient(&redis.Options{
		Addr:         cfg.Address,
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     cfg.PoolSize,
		DialTimeout:  cfg.DialTimeout,
		ReadTimeout:  cfg.ReadTimeout,
		WriteTimeout: cfg.WriteTimeout,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("failed to connect to redis: %w", err)
	}

	return &RedisClient{
		client: client,
		cfg:    cfg,
	}, nil
}

func (r *RedisClient) IncrementWindow(ctx context.Context, key string, window time.Time, value float64) error {
	if r.mock != nil {
		return r.mock.IncrementWindow(ctx, key, window, value)
	}

	windowKey := fmt.Sprintf("window:%s:%d", key, window.Unix())
	return r.client.ZIncrBy(ctx, windowKey, value, "count").Err()
}

func (r *RedisClient) AddToWindow(ctx context.Context, key string, window time.Time, value float64) error {
	if r.mock != nil {
		return r.mock.AddWindowValue(ctx, key, window, value)
	}

	windowKey := fmt.Sprintf("window:%s:%d", key, window.Unix())
	now := float64(time.Now().Unix())
	return r.client.ZAdd(ctx, windowKey, &redis.Z{
		Score:  now,
		Member: value,
	}).Err()
}

func (r *RedisClient) GetWindowValues(ctx context.Context, key string, startTime, endTime time.Time) ([]float64, error) {
	if r.mock != nil {
		return r.mock.GetWindowValues(ctx, key, startTime, endTime)
	}

	var values []float64

	startWindow := startTime.Truncate(time.Minute)
	endWindow := endTime.Truncate(time.Minute)

	for w := startWindow; !w.After(endWindow); w = w.Add(time.Minute) {
		windowKey := fmt.Sprintf("window:%s:%d", key, w.Unix())
		results, err := r.client.ZRange(ctx, windowKey, 0, -1).Result()
		if err != nil {
			return nil, err
		}
		for _, r := range results {
			var v float64
			if _, err := fmt.Sscanf(r, "%f", &v); err == nil {
				values = append(values, v)
			}
		}
	}

	return values, nil
}

func (r *RedisClient) GetWindowStats(ctx context.Context, key string, startTime, endTime time.Time) (*WindowStats, error) {
	if r.mock != nil {
		return r.mock.GetWindowStats(ctx, key, startTime, endTime)
	}

	startWindow := startTime.Truncate(time.Minute)
	endWindow := endTime.Truncate(time.Minute)

	keyPrefix := fmt.Sprintf("window:%s", key)

	res, err := windowStatsScript.Run(ctx, r.client, []string{keyPrefix},
		startWindow.Unix(), endWindow.Unix(), int64(time.Minute/time.Second)).Result()
	if err != nil {
		return nil, err
	}

	arr, ok := res.([]interface{})
	if !ok || len(arr) < 5 {
		return nil, fmt.Errorf("unexpected lua script result format")
	}

	stats := &WindowStats{}

	if count, ok := arr[0].(int64); ok {
		stats.Count = count
	}
	if errCount, ok := arr[1].(int64); ok {
		stats.ErrorCount = errCount
	}
	if sumStr, ok := arr[2].(string); ok {
		stats.Sum, _ = strconv.ParseFloat(sumStr, 64)
	}
	if sumSqStr, ok := arr[3].(string); ok {
		stats.SumSquares, _ = strconv.ParseFloat(sumSqStr, 64)
	}
	if vals, ok := arr[4].([]interface{}); ok {
		stats.Values = make([]float64, 0, len(vals))
		for _, v := range vals {
			if s, ok := v.(string); ok {
				if f, err := strconv.ParseFloat(s, 64); err == nil {
					stats.Values = append(stats.Values, f)
				}
			}
		}
	}

	return stats, nil
}

func (r *RedisClient) GetWindowCount(ctx context.Context, key string, startTime, endTime time.Time) (int64, error) {
	if r.mock != nil {
		return r.mock.GetWindowCount(ctx, key, startTime, endTime)
	}

	var count int64

	startWindow := startTime.Truncate(time.Minute)
	endWindow := endTime.Truncate(time.Minute)

	for w := startWindow; !w.After(endWindow); w = w.Add(time.Minute) {
		windowKey := fmt.Sprintf("window:%s:%d", key, w.Unix())
		c, err := r.client.ZCard(ctx, windowKey).Result()
		if err != nil {
			return 0, err
		}
		count += c
	}

	return count, nil
}

func (r *RedisClient) ExpireWindow(ctx context.Context, key string, window time.Time, ttl time.Duration) error {
	if r.mock != nil {
		return r.mock.ExpireWindow(ctx, key, window, ttl)
	}

	windowKey := fmt.Sprintf("window:%s:%d", key, window.Unix())
	return r.client.Expire(ctx, windowKey, ttl).Err()
}

func (r *RedisClient) CleanOldWindows(ctx context.Context, key string, olderThan time.Time) error {
	if r.mock != nil {
		return r.mock.CleanOldWindows(ctx, key, olderThan)
	}

	pattern := fmt.Sprintf("window:%s:*", key)
	var cursor uint64

	for {
		keys, nextCursor, err := r.client.Scan(ctx, cursor, pattern, 100).Result()
		if err != nil {
			return err
		}

		for _, k := range keys {
			var windowTs int64
			if _, err := fmt.Sscanf(k, "window:%*[^:]:%d", &windowTs); err == nil {
				windowTime := time.Unix(windowTs, 0)
				if windowTime.Before(olderThan) {
					r.client.Del(ctx, k)
				}
			}
		}

		cursor = nextCursor
		if cursor == 0 {
			break
		}
	}

	return nil
}

func (r *RedisClient) SetDeduplication(ctx context.Context, key string, value interface{}, ttl time.Duration) (bool, error) {
	if r.mock != nil {
		strValue, ok := value.(string)
		if !ok {
			strValue = fmt.Sprintf("%v", value)
		}
		return r.mock.SetDeduplication(ctx, key, strValue, ttl)
	}

	existing, err := r.client.SetNX(ctx, fmt.Sprintf("dedup:%s", key), value, ttl).Result()
	if err != nil {
		return false, err
	}
	return existing, nil
}

func (r *RedisClient) GetDeduplication(ctx context.Context, key string) (string, error) {
	if r.mock != nil {
		return r.mock.GetDeduplication(ctx, key)
	}

	return r.client.Get(ctx, fmt.Sprintf("dedup:%s", key)).Result()
}

func (r *RedisClient) DeleteDeduplication(ctx context.Context, key string) error {
	if r.mock != nil {
		return r.mock.DeleteDeduplication(ctx, key)
	}

	return r.client.Del(ctx, fmt.Sprintf("dedup:%s", key)).Err()
}

func (r *RedisClient) SetIncident(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	if r.mock != nil {
		return r.mock.SetIncident(ctx, key, value, ttl)
	}

	return r.client.Set(ctx, fmt.Sprintf("incident:%s", key), value, ttl).Err()
}

func (r *RedisClient) GetIncident(ctx context.Context, key string) (string, error) {
	return r.client.Get(ctx, fmt.Sprintf("incident:%s", key)).Result()
}

func (r *RedisClient) DeleteIncident(ctx context.Context, key string) error {
	if r.mock != nil {
		return r.mock.DeleteIncident(ctx, key)
	}

	return r.client.Del(ctx, fmt.Sprintf("incident:%s", key)).Err()
}

func (r *RedisClient) Publish(ctx context.Context, channel string, message interface{}) error {
	return r.client.Publish(ctx, channel, message).Err()
}

func (r *RedisClient) Subscribe(ctx context.Context, channel string) *redis.PubSub {
	return r.client.Subscribe(ctx, channel)
}

func (r *RedisClient) Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	return r.client.Set(ctx, key, value, expiration).Err()
}

func (r *RedisClient) Get(ctx context.Context, key string) (string, error) {
	return r.client.Get(ctx, key).Result()
}

func (r *RedisClient) Del(ctx context.Context, key string) error {
	return r.client.Del(ctx, key).Err()
}

func (r *RedisClient) HSet(ctx context.Context, key string, values ...interface{}) error {
	return r.client.HSet(ctx, key, values...).Err()
}

func (r *RedisClient) HGetAll(ctx context.Context, key string) (map[string]string, error) {
	return r.client.HGetAll(ctx, key).Result()
}

func (r *RedisClient) LPush(ctx context.Context, key string, values ...interface{}) error {
	return r.client.LPush(ctx, key, values...).Err()
}

func (r *RedisClient) RPop(ctx context.Context, key string) (string, error) {
	return r.client.RPop(ctx, key).Result()
}

func (r *RedisClient) LRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	return r.client.LRange(ctx, key, start, stop).Result()
}

func (r *RedisClient) Close() error {
	if r.mock != nil {
		return r.mock.Close()
	}

	return r.client.Close()
}
