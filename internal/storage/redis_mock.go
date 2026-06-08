package storage

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/datateam/loganalyzer/internal/config"
)

type MockRedisClient struct {
	data        map[string]interface{}
	windowData  map[string]map[int64][]float64
	setNXData   map[string]bool
	mu          sync.RWMutex
}

func NewMockRedisClient() *RedisClient {
	mock := &MockRedisClient{
		data:       make(map[string]interface{}),
		windowData: make(map[string]map[int64][]float64),
		setNXData:  make(map[string]bool),
	}

	return &RedisClient{
		client: nil,
		cfg: config.RedisConfig{
			Address: "mock:6379",
			DB:      0,
		},
		mock: mock,
	}
}

func (m *MockRedisClient) GetWindowValues(ctx context.Context, key string, startTime, endTime time.Time) ([]float64, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	values := make([]float64, 0)

	startWindow := startTime.Truncate(time.Minute)
	endWindow := endTime.Truncate(time.Minute)

	if windows, ok := m.windowData[key]; ok {
		for w := startWindow; !w.After(endWindow); w = w.Add(time.Minute) {
			windowUnix := w.Unix()
			if windowValues, ok := windows[windowUnix]; ok {
				values = append(values, windowValues...)
			}
		}
	}

	return values, nil
}

func (m *MockRedisClient) AddWindowValue(ctx context.Context, key string, timestamp time.Time, value float64) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	window := timestamp.Truncate(time.Minute)
	windowUnix := window.Unix()

	if _, ok := m.windowData[key]; !ok {
		m.windowData[key] = make(map[int64][]float64)
	}
	if _, ok := m.windowData[key][windowUnix]; !ok {
		m.windowData[key][windowUnix] = make([]float64, 0)
	}
	m.windowData[key][windowUnix] = append(m.windowData[key][windowUnix], value)

	return nil
}

func (m *MockRedisClient) SetDeduplication(ctx context.Context, key string, value string, ttl time.Duration) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	dedupKey := fmt.Sprintf("dedup:%s", key)
	if m.setNXData[dedupKey] {
		return false, nil
	}

	m.setNXData[dedupKey] = true
	m.data[dedupKey] = value

	return true, nil
}

func (m *MockRedisClient) Get(ctx context.Context, key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if val, ok := m.data[key]; ok {
		if str, ok := val.(string); ok {
			return str, nil
		}
		return fmt.Sprintf("%v", val), nil
	}

	return "", nil
}

func (m *MockRedisClient) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.data[key] = value
	return nil
}

func (m *MockRedisClient) Del(ctx context.Context, keys ...string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, key := range keys {
		delete(m.data, key)
		delete(m.setNXData, fmt.Sprintf("dedup:%s", key))
	}

	return nil
}

func (m *MockRedisClient) Exists(ctx context.Context, key string) (bool, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	_, ok := m.data[key]
	return ok, nil
}

func (m *MockRedisClient) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.data = make(map[string]interface{})
	m.setNXData = make(map[string]bool)
	return nil
}

func (m *MockRedisClient) SetWindowData(key string, values []float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	windowKey := fmt.Sprintf("window:%s", key)
	m.data[windowKey] = values
}

func (m *MockRedisClient) ExpireWindow(ctx context.Context, key string, window time.Time, ttl time.Duration) error {
	return nil
}

func (m *MockRedisClient) CleanOldWindows(ctx context.Context, key string, olderThan time.Time) error {
	return nil
}

func (m *MockRedisClient) GetDeduplication(ctx context.Context, key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	dedupKey := fmt.Sprintf("dedup:%s", key)
	if val, ok := m.data[dedupKey]; ok {
		if str, ok := val.(string); ok {
			return str, nil
		}
		return fmt.Sprintf("%v", val), nil
	}
	return "", nil
}

func (m *MockRedisClient) DeleteDeduplication(ctx context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	dedupKey := fmt.Sprintf("dedup:%s", key)
	delete(m.data, dedupKey)
	delete(m.setNXData, dedupKey)
	return nil
}

func (m *MockRedisClient) SetIncident(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	incidentKey := fmt.Sprintf("incident:%s", key)
	m.data[incidentKey] = value
	return nil
}

func (m *MockRedisClient) GetIncident(ctx context.Context, key string) (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	incidentKey := fmt.Sprintf("incident:%s", key)
	if val, ok := m.data[incidentKey]; ok {
		if str, ok := val.(string); ok {
			return str, nil
		}
		return fmt.Sprintf("%v", val), nil
	}
	return "", nil
}

func (m *MockRedisClient) DeleteIncident(ctx context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	incidentKey := fmt.Sprintf("incident:%s", key)
	delete(m.data, incidentKey)
	return nil
}

func (m *MockRedisClient) GetWindowCount(ctx context.Context, key string, startTime, endTime time.Time) (int64, error) {
	values, err := m.GetWindowValues(ctx, key, startTime, endTime)
	if err != nil {
		return 0, err
	}
	return int64(len(values)), nil
}

func (m *MockRedisClient) IncrementWindow(ctx context.Context, key string, window time.Time, value float64) error {
	return m.AddWindowValue(ctx, key, window, value)
}
