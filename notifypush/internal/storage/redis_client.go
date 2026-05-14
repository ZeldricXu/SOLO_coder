package storage

import (
	"context"
	"encoding/json"
	"errors"
	"notifypush/internal/config"
	"sync"
	"time"
)

type RedisClient interface {
	Ping(ctx context.Context) error
	Close() error
	LPush(ctx context.Context, key string, values ...interface{}) (int64, error)
	RPop(ctx context.Context, key string) (string, error)
	BRPop(ctx context.Context, timeout time.Duration, keys ...string) ([]string, error)
	LRem(ctx context.Context, key string, count int64, value interface{}) (int64, error)
	LRange(ctx context.Context, key string, start, stop int64) ([]string, error)
	Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error
	Get(ctx context.Context, key string) (string, error)
	Del(ctx context.Context, keys ...string) (int64, error)
	Exists(ctx context.Context, keys ...string) (int64, error)
	Expire(ctx context.Context, key string, expiration time.Duration) (bool, error)
	Incr(ctx context.Context, key string) (int64, error)
}

type MockRedisClient struct {
	data     map[string][]string
	keyValue map[string]string
	mu       sync.RWMutex
}

func NewMockRedisClient() *MockRedisClient {
	return &MockRedisClient{
		data:     make(map[string][]string),
		keyValue: make(map[string]string),
	}
}

func (m *MockRedisClient) Ping(ctx context.Context) error {
	return nil
}

func (m *MockRedisClient) Close() error {
	return nil
}

func (m *MockRedisClient) LPush(ctx context.Context, key string, values ...interface{}) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, v := range values {
		strVal := interfaceToString(v)
		m.data[key] = append([]string{strVal}, m.data[key]...)
	}
	return int64(len(values)), nil
}

func (m *MockRedisClient) RPop(ctx context.Context, key string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	list, exists := m.data[key]
	if !exists || len(list) == 0 {
		return "", errors.New("list is empty or key does not exist")
	}
	last := list[len(list)-1]
	m.data[key] = list[:len(list)-1]
	return last, nil
}

func (m *MockRedisClient) BRPop(ctx context.Context, timeout time.Duration, keys ...string) ([]string, error) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
			m.mu.Lock()
			for _, key := range keys {
				list, exists := m.data[key]
				if exists && len(list) > 0 {
					last := list[len(list)-1]
					m.data[key] = list[:len(list)-1]
					m.mu.Unlock()
					return []string{key, last}, nil
				}
			}
			m.mu.Unlock()
			time.Sleep(50 * time.Millisecond)
		}
	}
	return nil, nil
}

func (m *MockRedisClient) LRem(ctx context.Context, key string, count int64, value interface{}) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	list, exists := m.data[key]
	if !exists {
		return 0, nil
	}
	strVal := interfaceToString(value)
	removed := 0
	newList := make([]string, 0, len(list))
	for _, item := range list {
		if item == strVal && (count == 0 || removed < int(count)) {
			removed++
		} else {
			newList = append(newList, item)
		}
	}
	m.data[key] = newList
	return int64(removed), nil
}

func (m *MockRedisClient) LRange(ctx context.Context, key string, start, stop int64) ([]string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	list, exists := m.data[key]
	if !exists {
		return []string{}, nil
	}
	if start < 0 {
		start = 0
	}
	if stop < 0 || stop >= int64(len(list)) {
		stop = int64(len(list) - 1)
	}
	if start > stop {
		return []string{}, nil
	}
	return list[start : stop+1], nil
}

func (m *MockRedisClient) Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.keyValue[key] = interfaceToString(value)
	return nil
}

func (m *MockRedisClient) Get(ctx context.Context, key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	val, exists := m.keyValue[key]
	if !exists {
		return "", errors.New("key not found")
	}
	return val, nil
}

func (m *MockRedisClient) Del(ctx context.Context, keys ...string) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	count := 0
	for _, key := range keys {
		if _, exists := m.keyValue[key]; exists {
			delete(m.keyValue, key)
			count++
		}
		if _, exists := m.data[key]; exists {
			delete(m.data, key)
			count++
		}
	}
	return int64(count), nil
}

func (m *MockRedisClient) Exists(ctx context.Context, keys ...string) (int64, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	count := 0
	for _, key := range keys {
		if _, exists := m.keyValue[key]; exists {
			count++
		} else if _, exists := m.data[key]; exists {
			count++
		}
	}
	return int64(count), nil
}

func (m *MockRedisClient) Expire(ctx context.Context, key string, expiration time.Duration) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, existsKV := m.keyValue[key]
	_, existsList := m.data[key]
	return existsKV || existsList, nil
}

func (m *MockRedisClient) Incr(ctx context.Context, key string) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	val, exists := m.keyValue[key]
	var count int64 = 0
	if exists {
		_, err := json.Unmarshal([]byte(val), &count)
		if err != nil {
			count = 0
		}
	}
	count++
	m.keyValue[key] = interfaceToString(count)
	return count, nil
}

func interfaceToString(v interface{}) string {
	switch val := v.(type) {
	case string:
		return val
	case []byte:
		return string(val)
	default:
		jsonBytes, err := json.Marshal(val)
		if err != nil {
			return ""
		}
		return string(jsonBytes)
	}
}

func NewRedisClient(cfg *config.RedisConfig) (RedisClient, error) {
	return NewMockRedisClient(), nil
}
