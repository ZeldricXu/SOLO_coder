package apigateway

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
	"golang.org/x/time/rate"
)

type RateLimitStrategy interface {
	Name() string
	Allow(ctx context.Context, key string) (bool, *RateLimitResult, error)
	UpdateConfig(config *RateLimitConfig)
	Cleanup(ctx context.Context) error
}

type TokenBucketStrategy struct {
	config       *RateLimitConfig
	redisClient  *redis.Client
	logger       *zap.Logger
	memoryStore  map[string]*rate.Limiter
	mu           sync.RWMutex
	useRedis     bool
}

func NewTokenBucketStrategy(config *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *TokenBucketStrategy {
	return &TokenBucketStrategy{
		config:      config,
		redisClient: redisClient,
		logger:      logger,
		memoryStore: make(map[string]*rate.Limiter),
		useRedis:    redisClient != nil,
	}
}

func (s *TokenBucketStrategy) Name() string {
	return string(StrategyTokenBucket)
}

func (s *TokenBucketStrategy) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	fullKey := fmt.Sprintf("%s:%s", s.config.RedisPrefix, key)

	if s.useRedis {
		result, err := s.redisTokenBucket(ctx, fullKey)
		if err != nil {
			s.logger.Warn("Redis rate limit failed, falling back to memory", zap.Error(err))
			return s.memoryTokenBucket(key)
		}
		return result.Allowed, result, nil
	}

	return s.memoryTokenBucket(key)
}

func (s *TokenBucketStrategy) redisTokenBucket(ctx context.Context, key string) (*RateLimitResult, error) {
	now := time.Now().Unix()
	window := s.config.Window.Seconds()
	limit := s.config.Limit

	pipe := s.redisClient.TxPipeline()
	incr := pipe.IncrBy(ctx, key, 1)
	pipe.Expire(ctx, key, time.Duration(window)*time.Second)
	_, err := pipe.Exec(ctx)
	if err != nil {
		return nil, err
	}

	count := incr.Val()
	allowed := count <= int64(limit)
	remaining := limit - int(count)
	if remaining < 0 {
		remaining = 0
	}

	return &RateLimitResult{
		Limit:     limit,
		Remaining: remaining,
		Reset:     time.Now().Add(time.Duration(window) * time.Second),
		Allowed:   allowed,
	}, nil
}

func (s *TokenBucketStrategy) memoryTokenBucket(key string) (bool, *RateLimitResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	limiter, exists := s.memoryStore[key]
	if !exists {
		limiter = rate.NewLimiter(rate.Limit(s.config.Limit), s.config.Burst)
		s.memoryStore[key] = limiter
	}

	allowed := limiter.Allow()
	remaining := int(limiter.Tokens())

	return allowed, &RateLimitResult{
		Limit:     s.config.Limit,
		Remaining: remaining,
		Reset:     time.Now().Add(time.Second),
		Allowed:   allowed,
	}, nil
}

func (s *TokenBucketStrategy) UpdateConfig(config *RateLimitConfig) {
	s.config = config
}

func (s *TokenBucketStrategy) Cleanup(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.memoryStore = make(map[string]*rate.Limiter)
	return nil
}

type SlidingWindowStrategy struct {
	config       *RateLimitConfig
	redisClient  *redis.Client
	logger       *zap.Logger
	memoryStore  map[string][]int64
	mu           sync.RWMutex
	useRedis     bool
}

func NewSlidingWindowStrategy(config *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *SlidingWindowStrategy {
	return &SlidingWindowStrategy{
		config:      config,
		redisClient: redisClient,
		logger:      logger,
		memoryStore: make(map[string][]int64),
		useRedis:    redisClient != nil,
	}
}

func (s *SlidingWindowStrategy) Name() string {
	return string(StrategySlidingWindow)
}

func (s *SlidingWindowStrategy) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	fullKey := fmt.Sprintf("%s:sw:%s", s.config.RedisPrefix, key)

	if s.useRedis {
		result, err := s.redisSlidingWindow(ctx, fullKey)
		if err != nil {
			s.logger.Warn("Redis sliding window failed, falling back to memory", zap.Error(err))
			return s.memorySlidingWindow(key)
		}
		return result.Allowed, result, nil
	}

	return s.memorySlidingWindow(key)
}

func (s *SlidingWindowStrategy) redisSlidingWindow(ctx context.Context, key string) (*RateLimitResult, error) {
	now := time.Now().Unix()
	window := s.config.Window.Seconds()
	limit := s.config.Limit

	windowStart := now - int64(window)
	member := fmt.Sprintf("%d", now)

	pipe := s.redisClient.TxPipeline()
	pipe.ZRemRangeByScore(ctx, key, "0", fmt.Sprintf("%d", windowStart))
	pipe.ZAdd(ctx, key, &redis.Z{Score: float64(now), Member: member})
	pipe.Expire(ctx, key, time.Duration(window)*time.Second)
	countCmd := pipe.ZCard(ctx, key)
	_, err := pipe.Exec(ctx)
	if err != nil {
		return nil, err
	}

	count := countCmd.Val()
	allowed := count <= int64(limit)
	remaining := limit - int(count)
	if remaining < 0 {
		remaining = 0
	}

	return &RateLimitResult{
		Limit:     limit,
		Remaining: remaining,
		Reset:     time.Now().Add(time.Duration(window) * time.Second),
		Allowed:   allowed,
	}, nil
}

func (s *SlidingWindowStrategy) memorySlidingWindow(key string) (bool, *RateLimitResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UnixNano()
	windowNano := s.config.Window.Nanoseconds()
	limit := s.config.Limit

	windowStart := now - windowNano
	requests, exists := s.memoryStore[key]
	if !exists {
		requests = make([]int64, 0)
	}

	var validRequests []int64
	for _, t := range requests {
		if t > windowStart {
			validRequests = append(validRequests, t)
		}
	}

	if len(validRequests) >= limit {
		return false, &RateLimitResult{
			Limit:     limit,
			Remaining: 0,
			Reset:     time.Unix(0, validRequests[0]+windowNano),
			Allowed:   false,
		}, nil
	}

	validRequests = append(validRequests, now)
	s.memoryStore[key] = validRequests

	remaining := limit - len(validRequests)
	return true, &RateLimitResult{
		Limit:     limit,
		Remaining: remaining,
		Reset:     time.Now().Add(s.config.Window),
		Allowed:   true,
	}, nil
}

func (s *SlidingWindowStrategy) UpdateConfig(config *RateLimitConfig) {
	s.config = config
}

func (s *SlidingWindowStrategy) Cleanup(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.memoryStore = make(map[string][]int64)
	return nil
}

type FixedWindowStrategy struct {
	config       *RateLimitConfig
	redisClient  *redis.Client
	logger       *zap.Logger
	memoryStore  map[string]*windowCounter
	mu           sync.RWMutex
	useRedis     bool
}

type windowCounter struct {
	Count    int
	Start    time.Time
}

func NewFixedWindowStrategy(config *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *FixedWindowStrategy {
	return &FixedWindowStrategy{
		config:      config,
		redisClient: redisClient,
		logger:      logger,
		memoryStore: make(map[string]*windowCounter),
		useRedis:    redisClient != nil,
	}
}

func (s *FixedWindowStrategy) Name() string {
	return string(StrategyFixedWindow)
}

func (s *FixedWindowStrategy) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	fullKey := fmt.Sprintf("%s:fw:%s", s.config.RedisPrefix, key)

	if s.useRedis {
		result, err := s.redisFixedWindow(ctx, fullKey)
		if err != nil {
			s.logger.Warn("Redis fixed window failed, falling back to memory", zap.Error(err))
			return s.memoryFixedWindow(key)
		}
		return result.Allowed, result, nil
	}

	return s.memoryFixedWindow(key)
}

func (s *FixedWindowStrategy) redisFixedWindow(ctx context.Context, key string) (*RateLimitResult, error) {
	window := s.config.Window.Seconds()
	limit := s.config.Limit
	now := time.Now().Unix()
	windowStart := now - (now % int64(window))

	windowKey := fmt.Sprintf("%s:%d", key, windowStart)

	pipe := s.redisClient.TxPipeline()
	incr := pipe.IncrBy(ctx, windowKey, 1)
	pipe.Expire(ctx, windowKey, time.Duration(window)*time.Second)
	_, err := pipe.Exec(ctx)
	if err != nil {
		return nil, err
	}

	count := incr.Val()
	allowed := count <= int64(limit)
	remaining := limit - int(count)
	if remaining < 0 {
		remaining = 0
	}

	return &RateLimitResult{
		Limit:     limit,
		Remaining: remaining,
		Reset:     time.Unix(windowStart+int64(window), 0),
		Allowed:   allowed,
	}, nil
}

func (s *FixedWindowStrategy) memoryFixedWindow(key string) (bool, *RateLimitResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	limit := s.config.Limit
	window := s.config.Window
	now := time.Now()

	counter, exists := s.memoryStore[key]
	if !exists || now.Sub(counter.Start) >= window {
		counter = &windowCounter{
			Count: 0,
			Start: now,
		}
		s.memoryStore[key] = counter
	}

	if counter.Count >= limit {
		return false, &RateLimitResult{
			Limit:     limit,
			Remaining: 0,
			Reset:     counter.Start.Add(window),
			Allowed:   false,
		}, nil
	}

	counter.Count++
	remaining := limit - counter.Count

	return true, &RateLimitResult{
		Limit:     limit,
		Remaining: remaining,
		Reset:     counter.Start.Add(window),
		Allowed:   true,
	}, nil
}

func (s *FixedWindowStrategy) UpdateConfig(config *RateLimitConfig) {
	s.config = config
}

func (s *FixedWindowStrategy) Cleanup(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.memoryStore = make(map[string]*windowCounter)
	return nil
}
