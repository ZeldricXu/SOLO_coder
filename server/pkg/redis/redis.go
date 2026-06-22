package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	goredis "github.com/redis/go-redis/v9"

	"onboarding-server/internal/config"
)

type Client struct {
	client *goredis.Client
}

var DefaultClient *Client

func Connect(cfg *config.Config) (*Client, error) {
	rdb := goredis.NewClient(&goredis.Options{
		Addr:         cfg.RedisAddr(),
		DB:           0,
		DialTimeout:  5 * time.Second,
		ReadTimeout:  3 * time.Second,
		WriteTimeout: 3 * time.Second,
		PoolSize:     10,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := rdb.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("redis ping: %w", err)
	}

	c := &Client{client: rdb}
	DefaultClient = c
	return c, nil
}

func (c *Client) Close() error {
	return c.client.Close()
}

func (c *Client) Raw() *goredis.Client {
	return c.client
}

type Task struct {
	ID        string          `json:"id"`
	Queue     string          `json:"queue"`
	Payload   json.RawMessage `json:"payload"`
	Retry     int             `json:"retry,omitempty"`
	MaxRetry  int             `json:"max_retry,omitempty"`
	EnqueuedAt time.Time      `json:"enqueued_at"`
}

func (c *Client) Enqueue(ctx context.Context, queue string, task *Task) error {
	task.Queue = queue
	task.EnqueuedAt = time.Now()

	data, err := json.Marshal(task)
	if err != nil {
		return fmt.Errorf("marshal task: %w", err)
	}

	key := queueKey(queue)
	if err := c.client.RPush(ctx, key, data).Err(); err != nil {
		return fmt.Errorf("rpush task: %w", err)
	}

	return nil
}

func (c *Client) Dequeue(ctx context.Context, queue string, timeout time.Duration) (*Task, error) {
	key := queueKey(queue)
	result, err := c.client.BLPop(ctx, timeout, key).Result()
	if err != nil {
		if err == goredis.Nil {
			return nil, nil
		}
		return nil, fmt.Errorf("blpop task: %w", err)
	}

	if len(result) < 2 {
		return nil, nil
	}

	var task Task
	if err := json.Unmarshal([]byte(result[1]), &task); err != nil {
		return nil, fmt.Errorf("unmarshal task: %w", err)
	}

	return &task, nil
}

func (c *Client) Schedule(ctx context.Context, queue string, task *Task, executeAt time.Time) error {
	task.Queue = queue
	task.EnqueuedAt = time.Now()

	data, err := json.Marshal(task)
	if err != nil {
		return fmt.Errorf("marshal task: %w", err)
	}

	z := goredis.Z{
		Score:  float64(executeAt.Unix()),
		Member: data,
	}

	scheduleKey := scheduleKey(queue)
	if err := c.client.ZAdd(ctx, scheduleKey, z).Err(); err != nil {
		return fmt.Errorf("zadd scheduled task: %w", err)
	}

	return nil
}

func (c *Client) ProcessScheduled(ctx context.Context, queue string) error {
	scheduleKey := scheduleKey(queue)
	queueKey := queueKey(queue)

	now := float64(time.Now().Unix())

	jobs, err := c.client.ZRangeByScore(ctx, scheduleKey, &goredis.ZRangeBy{
		Min: "-inf",
		Max: fmt.Sprintf("%f", now),
	}).Result()
	if err != nil {
		return fmt.Errorf("zrangebyscore: %w", err)
	}

	for _, job := range jobs {
		removed, err := c.client.ZRem(ctx, scheduleKey, job).Result()
		if err != nil {
			return fmt.Errorf("zrem: %w", err)
		}
		if removed > 0 {
			if err := c.client.RPush(ctx, queueKey, job).Err(); err != nil {
				return fmt.Errorf("rpush scheduled task: %w", err)
			}
		}
	}

	return nil
}

func (c *Client) QueueSize(ctx context.Context, queue string) (int64, error) {
	return c.client.LLen(ctx, queueKey(queue)).Result()
}

func (c *Client) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	return c.client.Set(ctx, key, value, ttl).Err()
}

func (c *Client) Get(ctx context.Context, key string) (string, error) {
	return c.client.Get(ctx, key).Result()
}

func (c *Client) Del(ctx context.Context, keys ...string) error {
	return c.client.Del(ctx, keys...).Err()
}

func queueKey(queue string) string {
	return "queue:" + queue
}

func scheduleKey(queue string) string {
	return "schedule:" + queue
}
