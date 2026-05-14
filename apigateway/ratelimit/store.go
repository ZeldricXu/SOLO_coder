package ratelimit

import (
	"apigateway/models"
	"context"
	"fmt"
	"sync"
	"time"
)

type RateLimitStore interface {
	Get(ctx context.Context, key string) (*RateLimitState, error)
	Set(ctx context.Context, key string, state *RateLimitState, ttl time.Duration) error
	Incr(ctx context.Context, key string, field string) (int64, error)
	Decr(ctx context.Context, key string, field string) (int64, error)
	Expire(ctx context.Context, key string, ttl time.Duration) error
	Delete(ctx context.Context, key string) error
	Close() error
	Ping(ctx context.Context) error
	GetType() string
}

type RateLimitState struct {
	Tokens       int64
	LastRefill   time.Time
	WaterLevel   int64
	LastLeak     time.Time
	RequestCount int64
	WindowStart  time.Time
}

type InMemoryStore struct {
	data map[string]*RateLimitState
	mu   sync.RWMutex
}

func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{
		data: make(map[string]*RateLimitState),
	}
}

func (s *InMemoryStore) GetType() string {
	return "in_memory"
}

func (s *InMemoryStore) Ping(ctx context.Context) error {
	return nil
}

func (s *InMemoryStore) Get(ctx context.Context, key string) (*RateLimitState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	state, exists := s.data[key]
	if !exists {
		return nil, nil
	}
	clone := *state
	return &clone, nil
}

func (s *InMemoryStore) Set(ctx context.Context, key string, state *RateLimitState, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	clone := *state
	s.data[key] = &clone
	return nil
}

func (s *InMemoryStore) Incr(ctx context.Context, key string, field string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	state, exists := s.data[key]
	if !exists {
		state = &RateLimitState{}
		s.data[key] = state
	}

	switch field {
	case "tokens":
		state.Tokens++
		return state.Tokens, nil
	case "water_level":
		state.WaterLevel++
		return state.WaterLevel, nil
	case "request_count":
		state.RequestCount++
		return state.RequestCount, nil
	default:
		state.RequestCount++
		return state.RequestCount, nil
	}
}

func (s *InMemoryStore) Decr(ctx context.Context, key string, field string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	state, exists := s.data[key]
	if !exists {
		return 0, nil
	}

	switch field {
	case "tokens":
		if state.Tokens > 0 {
			state.Tokens--
		}
		return state.Tokens, nil
	case "water_level":
		if state.WaterLevel > 0 {
			state.WaterLevel--
		}
		return state.WaterLevel, nil
	default:
		return 0, nil
	}
}

func (s *InMemoryStore) Expire(ctx context.Context, key string, ttl time.Duration) error {
	return nil
}

func (s *InMemoryStore) Delete(ctx context.Context, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.data, key)
	return nil
}

func (s *InMemoryStore) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data = make(map[string]*RateLimitState)
	return nil
}

type MockRedisStore struct {
	data           map[string]*RateLimitState
	mu             sync.RWMutex
	failNext       int
	shouldFail     bool
	failureCount   int
	allowFallback  bool
	clusterMode    bool
	sentinelMode   bool
}

func NewMockRedisStore() *MockRedisStore {
	return &MockRedisStore{
		data:          make(map[string]*RateLimitState),
		allowFallback: true,
	}
}

func (m *MockRedisStore) GetType() string {
	if m.clusterMode {
		return "redis_cluster"
	}
	if m.sentinelMode {
		return "redis_sentinel"
	}
	return "redis_single"
}

func (m *MockRedisStore) SetClusterMode(enabled bool) {
	m.clusterMode = enabled
}

func (m *MockRedisStore) SetSentinelMode(enabled bool) {
	m.sentinelMode = enabled
}

func (m *MockRedisStore) Ping(ctx context.Context) error {
	if m.shouldFail || m.failureCount < m.failNext {
		m.failureCount++
		return ErrRedisConnectionFailed
	}
	return nil
}

func (m *MockRedisStore) Get(ctx context.Context, key string) (*RateLimitState, error) {
	if m.shouldFail || m.failureCount < m.failNext {
		m.failureCount++
		if !m.allowFallback {
			return nil, ErrRedisConnectionFailed
		}
	}

	m.mu.RLock()
	defer m.mu.RUnlock()
	state, exists := m.data[key]
	if !exists {
		return nil, nil
	}
	clone := *state
	return &clone, nil
}

func (m *MockRedisStore) Set(ctx context.Context, key string, state *RateLimitState, ttl time.Duration) error {
	if m.shouldFail || m.failureCount < m.failNext {
		m.failureCount++
		if !m.allowFallback {
			return ErrRedisConnectionFailed
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	clone := *state
	m.data[key] = &clone
	return nil
}

func (m *MockRedisStore) Incr(ctx context.Context, key string, field string) (int64, error) {
	if m.shouldFail || m.failureCount < m.failNext {
		m.failureCount++
		if !m.allowFallback {
			return 0, ErrRedisConnectionFailed
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	state, exists := m.data[key]
	if !exists {
		state = &RateLimitState{}
		m.data[key] = state
	}

	switch field {
	case "tokens":
		state.Tokens++
		return state.Tokens, nil
	case "water_level":
		state.WaterLevel++
		return state.WaterLevel, nil
	case "request_count":
		state.RequestCount++
		return state.RequestCount, nil
	default:
		state.RequestCount++
		return state.RequestCount, nil
	}
}

func (m *MockRedisStore) Decr(ctx context.Context, key string, field string) (int64, error) {
	if m.shouldFail || m.failureCount < m.failNext {
		m.failureCount++
		if !m.allowFallback {
			return 0, ErrRedisConnectionFailed
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	state, exists := m.data[key]
	if !exists {
		return 0, nil
	}

	switch field {
	case "tokens":
		if state.Tokens > 0 {
			state.Tokens--
		}
		return state.Tokens, nil
	case "water_level":
		if state.WaterLevel > 0 {
			state.WaterLevel--
		}
		return state.WaterLevel, nil
	default:
		return 0, nil
	}
}

func (m *MockRedisStore) Expire(ctx context.Context, key string, ttl time.Duration) error {
	return nil
}

func (m *MockRedisStore) Delete(ctx context.Context, key string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.data, key)
	return nil
}

func (m *MockRedisStore) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.data = make(map[string]*RateLimitState)
	return nil
}

func (m *MockRedisStore) SetFailureMode(fail bool) {
	m.shouldFail = fail
	m.failureCount = 0
}

func (m *MockRedisStore) SetFailNext(count int) {
	m.failNext = count
	m.failureCount = 0
}

func (m *MockRedisStore) SetAllowFallback(allow bool) {
	m.allowFallback = allow
}

func (m *MockRedisStore) GetData() map[string]*RateLimitState {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make(map[string]*RateLimitState)
	for k, v := range m.data {
		clone := *v
		result[k] = &clone
	}
	return result
}

type RedisClusterStore struct {
	config      models.RedisConfig
	mockStore   *MockRedisStore
	fallback    *InMemoryStore
	useFallback bool
	mu          sync.RWMutex
}

func NewRedisClusterStore(config models.RedisConfig) *RedisClusterStore {
	mock := NewMockRedisStore()
	mock.SetClusterMode(config.UseCluster)
	return &RedisClusterStore{
		config:      config,
		mockStore:   mock,
		fallback:    NewInMemoryStore(),
		useFallback: true,
	}
}

func (s *RedisClusterStore) GetType() string {
	return "redis_cluster"
}

func (s *RedisClusterStore) Ping(ctx context.Context) error {
	return s.mockStore.Ping(ctx)
}

func (s *RedisClusterStore) withFallback(
	redisOp func() (interface{}, error),
	fallbackOp func() (interface{}, error),
) (interface{}, error) {
	result, err := redisOp()
	if err != nil && IsRedisError(err) {
		if s.useFallback {
			s.mu.Lock()
			if s.useFallback {
				s.mu.Unlock()
				return fallbackOp()
			}
			s.mu.Unlock()
		}
		return nil, err
	}
	return result, err
}

func (s *RedisClusterStore) Get(ctx context.Context, key string) (*RateLimitState, error) {
	result, err := s.withFallback(
		func() (interface{}, error) {
			return s.mockStore.Get(ctx, key)
		},
		func() (interface{}, error) {
			return s.fallback.Get(ctx, key)
		},
	)
	if err != nil {
		return nil, err
	}
	if state, ok := result.(*RateLimitState); ok {
		return state, nil
	}
	return nil, nil
}

func (s *RedisClusterStore) Set(ctx context.Context, key string, state *RateLimitState, ttl time.Duration) error {
	_, err := s.withFallback(
		func() (interface{}, error) {
			return nil, s.mockStore.Set(ctx, key, state, ttl)
		},
		func() (interface{}, error) {
			return nil, s.fallback.Set(ctx, key, state, ttl)
		},
	)
	return err
}

func (s *RedisClusterStore) Incr(ctx context.Context, key string, field string) (int64, error) {
	result, err := s.withFallback(
		func() (interface{}, error) {
			return s.mockStore.Incr(ctx, key, field)
		},
		func() (interface{}, error) {
			return s.fallback.Incr(ctx, key, field)
		},
	)
	if err != nil {
		return 0, err
	}
	if val, ok := result.(int64); ok {
		return val, nil
	}
	return 0, nil
}

func (s *RedisClusterStore) Decr(ctx context.Context, key string, field string) (int64, error) {
	result, err := s.withFallback(
		func() (interface{}, error) {
			return s.mockStore.Decr(ctx, key, field)
		},
		func() (interface{}, error) {
			return s.fallback.Decr(ctx, key, field)
		},
	)
	if err != nil {
		return 0, err
	}
	if val, ok := result.(int64); ok {
		return val, nil
	}
	return 0, nil
}

func (s *RedisClusterStore) Expire(ctx context.Context, key string, ttl time.Duration) error {
	_, err := s.withFallback(
		func() (interface{}, error) {
			return nil, s.mockStore.Expire(ctx, key, ttl)
		},
		func() (interface{}, error) {
			return nil, s.fallback.Expire(ctx, key, ttl)
		},
	)
	return err
}

func (s *RedisClusterStore) Delete(ctx context.Context, key string) error {
	_, err := s.withFallback(
		func() (interface{}, error) {
			return nil, s.mockStore.Delete(ctx, key)
		},
		func() (interface{}, error) {
			return nil, s.fallback.Delete(ctx, key)
		},
	)
	return err
}

func (s *RedisClusterStore) Close() error {
	s.mockStore.Close()
	s.fallback.Close()
	return nil
}

func (s *RedisClusterStore) SetMockFailureMode(fail bool) {
	s.mockStore.SetFailureMode(fail)
}

func (s *RedisClusterStore) EnableFallback(enable bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.useFallback = enable
}

type RedisSentinelStore struct {
	config       models.RedisConfig
	mockStore    *MockRedisStore
	fallback     *InMemoryStore
	useFallback  bool
	masterDown   bool
	failoverInProgress bool
	mu           sync.RWMutex
}

func NewRedisSentinelStore(config models.RedisConfig) *RedisSentinelStore {
	mock := NewMockRedisStore()
	mock.SetSentinelMode(config.UseSentinel)
	return &RedisSentinelStore{
		config:      config,
		mockStore:   mock,
		fallback:    NewInMemoryStore(),
		useFallback: true,
		masterDown:  false,
	}
}

func (s *RedisSentinelStore) GetType() string {
	return "redis_sentinel"
}

func (s *RedisSentinelStore) Ping(ctx context.Context) error {
	s.mu.RLock()
	if s.masterDown {
		s.mu.RUnlock()
		return ErrRedisConnectionFailed
	}
	s.mu.RUnlock()
	return s.mockStore.Ping(ctx)
}

func (s *RedisSentinelStore) simulateFailover() {
	s.mu.Lock()
	s.masterDown = true
	s.failoverInProgress = true
	s.mu.Unlock()

	time.Sleep(100 * time.Millisecond)

	s.mu.Lock()
	s.masterDown = false
	s.failoverInProgress = false
	s.mu.Unlock()
}

func (s *RedisSentinelStore) SimulateMasterFailure(duration time.Duration) {
	go func() {
		s.mu.Lock()
		s.masterDown = true
		s.mu.Unlock()

		time.Sleep(duration)

		s.mu.Lock()
		s.masterDown = false
		s.mu.Unlock()
	}()
}

func (s *RedisSentinelStore) withFallback(
	redisOp func() (interface{}, error),
	fallbackOp func() (interface{}, error),
) (interface{}, error) {
	s.mu.RLock()
	masterDown := s.masterDown
	s.mu.RUnlock()

	if masterDown {
		if s.useFallback {
			return fallbackOp()
		}
		return nil, ErrRedisConnectionFailed
	}

	result, err := redisOp()
	if err != nil && IsRedisError(err) {
		if s.useFallback {
			return fallbackOp()
		}
		return nil, err
	}
	return result, err
}

func (s *RedisSentinelStore) Get(ctx context.Context, key string) (*RateLimitState, error) {
	result, err := s.withFallback(
		func() (interface{}, error) {
			return s.mockStore.Get(ctx, key)
		},
		func() (interface{}, error) {
			return s.fallback.Get(ctx, key)
		},
	)
	if err != nil {
		return nil, err
	}
	if state, ok := result.(*RateLimitState); ok {
		return state, nil
	}
	return nil, nil
}

func (s *RedisSentinelStore) Set(ctx context.Context, key string, state *RateLimitState, ttl time.Duration) error {
	_, err := s.withFallback(
		func() (interface{}, error) {
			return nil, s.mockStore.Set(ctx, key, state, ttl)
		},
		func() (interface{}, error) {
			return nil, s.fallback.Set(ctx, key, state, ttl)
		},
	)
	return err
}

func (s *RedisSentinelStore) Incr(ctx context.Context, key string, field string) (int64, error) {
	result, err := s.withFallback(
		func() (interface{}, error) {
			return s.mockStore.Incr(ctx, key, field)
		},
		func() (interface{}, error) {
			return s.fallback.Incr(ctx, key, field)
		},
	)
	if err != nil {
		return 0, err
	}
	if val, ok := result.(int64); ok {
		return val, nil
	}
	return 0, nil
}

func (s *RedisSentinelStore) Decr(ctx context.Context, key string, field string) (int64, error) {
	result, err := s.withFallback(
		func() (interface{}, error) {
			return s.mockStore.Decr(ctx, key, field)
		},
		func() (interface{}, error) {
			return s.fallback.Decr(ctx, key, field)
		},
	)
	if err != nil {
		return 0, err
	}
	if val, ok := result.(int64); ok {
		return val, nil
	}
	return 0, nil
}

func (s *RedisSentinelStore) Expire(ctx context.Context, key string, ttl time.Duration) error {
	_, err := s.withFallback(
		func() (interface{}, error) {
			return nil, s.mockStore.Expire(ctx, key, ttl)
		},
		func() (interface{}, error) {
			return nil, s.fallback.Expire(ctx, key, ttl)
		},
	)
	return err
}

func (s *RedisSentinelStore) Delete(ctx context.Context, key string) error {
	_, err := s.withFallback(
		func() (interface{}, error) {
			return nil, s.mockStore.Delete(ctx, key)
		},
		func() (interface{}, error) {
			return nil, s.fallback.Delete(ctx, key)
		},
	)
	return err
}

func (s *RedisSentinelStore) Close() error {
	s.mockStore.Close()
	s.fallback.Close()
	return nil
}

func (s *RedisSentinelStore) EnableFallback(enable bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.useFallback = enable
}

func (s *RedisSentinelStore) IsMasterDown() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.masterDown
}

var ErrRedisConnectionFailed = &RedisError{
	Op:  "connection",
	Err: "redis connection failed",
}

var ErrRedisMasterDown = &RedisError{
	Op:  "master",
	Err: "redis master is down",
}

var ErrRedisFailoverInProgress = &RedisError{
	Op:  "failover",
	Err: "redis sentinel failover in progress",
}

type RedisError struct {
	Op  string
	Err string
}

func (e *RedisError) Error() string {
	return fmt.Sprintf("redis %s: %s", e.Op, e.Err)
}

func IsRedisError(err error) bool {
	if err == nil {
		return false
	}
	_, ok := err.(*RedisError)
	return ok
}

func NewRateLimitStore(config models.RedisConfig) RateLimitStore {
	if config.UseCluster && len(config.Addresses) > 0 {
		return NewRedisClusterStore(config)
	}
	if config.UseSentinel && config.MasterName != "" {
		return NewRedisSentinelStore(config)
	}
	if len(config.Addresses) > 0 {
		store := NewMockRedisStore()
		return store
	}
	return NewInMemoryStore()
}
