package tenant

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
)

type RedisClient interface {
	Get(ctx context.Context, key string) (string, error)
	Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error
	Del(ctx context.Context, keys ...string) error
	Publish(ctx context.Context, channel string, message interface{}) error
	Subscribe(ctx context.Context, channels ...string) <-chan *RedisMessage
	DelPattern(ctx context.Context, pattern string) error
	Ping(ctx context.Context) error
}

type RedisMessage struct {
	Channel string
	Payload string
}

type RedisL2Client struct {
	client RedisClient
}

func NewRedisL2Client(client RedisClient) L2Client {
	return &RedisL2Client{client: client}
}

func (r *RedisL2Client) Get(ctx context.Context, key string) (string, error) {
	return r.client.Get(ctx, key)
}

func (r *RedisL2Client) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	return r.client.Set(ctx, key, value, ttl)
}

func (r *RedisL2Client) Del(ctx context.Context, key string) error {
	return r.client.Del(ctx, key)
}

func (r *RedisL2Client) DelPattern(ctx context.Context, pattern string) error {
	return r.client.DelPattern(ctx, pattern)
}

func (r *RedisL2Client) Publish(ctx context.Context, channel string, message interface{}) error {
	return r.client.Publish(ctx, channel, message)
}

func (r *RedisL2Client) Subscribe(ctx context.Context, channel string) (<-chan string, error) {
	msgChan := r.client.Subscribe(ctx, channel)
	resultChan := make(chan string, 100)

	go func() {
		defer close(resultChan)
		for msg := range msgChan {
			select {
			case resultChan <- msg.Payload:
			case <-ctx.Done():
				return
			}
		}
	}()

	return resultChan, nil
}

type MockRedisClient struct {
	data   map[string]string
	expiry map[string]time.Time
	pubsub map[string][]chan *RedisMessage
	mu     sync.RWMutex
}

func NewMockRedisClient() *MockRedisClient {
	return &MockRedisClient{
		data:   make(map[string]string),
		expiry: make(map[string]time.Time),
		pubsub: make(map[string][]chan *RedisMessage),
	}
}

func (m *MockRedisClient) Get(ctx context.Context, key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	value, exists := m.data[key]
	if !exists {
		return "", nil
	}

	if exp, ok := m.expiry[key]; ok && time.Now().After(exp) {
		return "", nil
	}

	return value, nil
}

func (m *MockRedisClient) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.data[key] = fmt.Sprintf("%v", value)
	if ttl > 0 {
		m.expiry[key] = time.Now().Add(ttl)
	}
	return nil
}

func (m *MockRedisClient) Del(ctx context.Context, keys ...string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, key := range keys {
		delete(m.data, key)
		delete(m.expiry, key)
	}
	return nil
}

func (m *MockRedisClient) DelPattern(ctx context.Context, pattern string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for key := range m.data {
		if matchPattern(pattern, key) {
			delete(m.data, key)
			delete(m.expiry, key)
		}
	}
	return nil
}

func (m *MockRedisClient) Publish(ctx context.Context, channel string, message interface{}) error {
	m.mu.RLock()
	defer m.mu.RUnlock()

	channels, exists := m.pubsub[channel]
	if !exists {
		return nil
	}

	payload := fmt.Sprintf("%v", message)
	msg := &RedisMessage{
		Channel: channel,
		Payload: payload,
	}

	for _, ch := range channels {
		select {
		case ch <- msg:
		default:
			logger.Warn("pubsub channel full, message dropped", zap.String("channel", channel))
		}
	}
	return nil
}

func (m *MockRedisClient) Subscribe(ctx context.Context, channels ...string) <-chan *RedisMessage {
	m.mu.Lock()
	defer m.mu.Unlock()

	msgChan := make(chan *RedisMessage, 100)

	for _, channel := range channels {
		m.pubsub[channel] = append(m.pubsub[channel], msgChan)
	}

	go func() {
		<-ctx.Done()
		m.mu.Lock()
		defer m.mu.Unlock()
		for _, channel := range channels {
			if chans, exists := m.pubsub[channel]; exists {
				for i, ch := range chans {
					if ch == msgChan {
						m.pubsub[channel] = append(chans[:i], chans[i+1:]...)
						break
					}
				}
			}
		}
		close(msgChan)
	}()

	return msgChan
}

func (m *MockRedisClient) Ping(ctx context.Context) error {
	return nil
}

func matchPattern(pattern, key string) bool {
	if len(pattern) == 0 {
		return true
	}
	if pattern[len(pattern)-1] == '*' {
		prefix := pattern[:len(pattern)-1]
		return len(key) >= len(prefix) && key[:len(prefix)] == prefix
	}
	return pattern == key
}

type GoRedisAdapter struct {
	client *redis.Client
}

func NewGoRedisAdapter(client *redis.Client) RedisClient {
	return &GoRedisAdapter{client: client}
}

func (a *GoRedisAdapter) Get(ctx context.Context, key string) (string, error) {
	result, err := a.client.Get(ctx, key).Result()
	if err == redis.Nil {
		return "", nil
	}
	return result, err
}

func (a *GoRedisAdapter) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	return a.client.Set(ctx, key, value, ttl).Err()
}

func (a *GoRedisAdapter) Del(ctx context.Context, keys ...string) error {
	return a.client.Del(ctx, keys...).Err()
}

func (a *GoRedisAdapter) Publish(ctx context.Context, channel string, message interface{}) error {
	return a.client.Publish(ctx, channel, message).Err()
}

func (a *GoRedisAdapter) Subscribe(ctx context.Context, channels ...string) <-chan *RedisMessage {
	pubsub := a.client.Subscribe(ctx, channels...)
	outChan := make(chan *RedisMessage, 100)

	go func() {
		defer close(outChan)
		defer pubsub.Close()

		ch := pubsub.Channel()
		for {
			select {
			case <-ctx.Done():
				return
			case msg, ok := <-ch:
				if !ok {
					return
				}
				outChan <- &RedisMessage{
					Channel: msg.Channel,
					Payload: msg.Payload,
				}
			}
		}
	}()

	return outChan
}

func (a *GoRedisAdapter) DelPattern(ctx context.Context, pattern string) error {
	keys, err := a.client.Keys(ctx, pattern).Result()
	if err != nil {
		return err
	}
	if len(keys) == 0 {
		return nil
	}
	return a.client.Del(ctx, keys...).Err()
}

func (a *GoRedisAdapter) Ping(ctx context.Context) error {
	return a.client.Ping(ctx).Err()
}
