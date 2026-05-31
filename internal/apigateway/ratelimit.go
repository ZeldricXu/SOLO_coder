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

type RateLimitStrategy string

const (
	StrategyTokenBucket RateLimitStrategy = "token_bucket"
	StrategySlidingWindow RateLimitStrategy = "sliding_window"
	StrategyFixedWindow RateLimitStrategy = "fixed_window"
)

type RateLimitConfig struct {
	Strategy      RateLimitStrategy
	Limit         int
	Window        time.Duration
	Burst         int
	HeaderKey     string
	RedisPrefix   string
}

type RateLimiter struct {
	config       *RateLimitConfig
	redisClient  *redis.Client
	logger       *zap.Logger
	memoryStore  map[string]*rate.Limiter
	mu           sync.RWMutex
	useRedis     bool
}

func NewRateLimiter(config *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *RateLimiter {
	rl := &RateLimiter{
		config:      config,
		redisClient: redisClient,
		logger:      logger,
		memoryStore: make(map[string]*rate.Limiter),
		useRedis:    redisClient != nil,
	}
	return rl
}

func (rl *RateLimiter) Allow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	switch rl.config.Strategy {
	case StrategyTokenBucket:
		return rl.allowTokenBucket(ctx, key)
	case StrategySlidingWindow:
		return rl.allowSlidingWindow(ctx, key)
	case StrategyFixedWindow:
		return rl.allowFixedWindow(ctx, key)
	default:
		return rl.allowTokenBucket(ctx, key)
	}
}

type RateLimitResult struct {
	Limit     int
	Remaining int
	Reset     time.Time
	Allowed   bool
}

func (rl *RateLimiter) allowTokenBucket(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	fullKey := fmt.Sprintf("%s:%s", rl.config.RedisPrefix, key)

	if rl.useRedis {
		result, err := rl.redisTokenBucket(ctx, fullKey)
		if err != nil {
			rl.logger.Warn("Redis rate limit failed, falling back to memory", zap.Error(err))
			return rl.memoryTokenBucket(key)
		}
		return result.Allowed, result, nil
	}

	return rl.memoryTokenBucket(key)
}

func (rl *RateLimiter) redisTokenBucket(ctx context.Context, key string) (*RateLimitResult, error) {
	now := time.Now().Unix()
	windowStart := now - int64(rl.config.Window.Seconds())

	pipe := rl.redisClient.Pipeline()
	zremPipe := pipe.ZRemRangeByScore(ctx, key, "0", fmt.Sprintf("%d", windowStart))
	zaddPipe := pipe.ZAdd(ctx, key, &redis.Z{Score: float64(now), Member: now})
	zcardPipe := pipe.ZCard(ctx, key)
	expirePipe := pipe.Expire(ctx, key, rl.config.Window)

	_, err := pipe.Exec(ctx)
	if err != nil {
		return nil, err
	}

	_ = zremPipe
	_ = zaddPipe

	count, err := zcardPipe.Result()
	if err != nil {
		return nil, err
	}

	_ = expirePipe

	allowed := int(count) <= rl.config.Limit
	remaining := rl.config.Limit - int(count)
	if remaining < 0 {
		remaining = 0
	}

	return &RateLimitResult{
		Limit:     rl.config.Limit,
		Remaining: remaining,
		Reset:     time.Now().Add(rl.config.Window),
		Allowed:   allowed,
	}, nil
}

func (rl *RateLimiter) memoryTokenBucket(key string) (bool, *RateLimitResult, error) {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	limiter, exists := rl.memoryStore[key]
	if !exists {
		r := rate.Limit(float64(rl.config.Limit) / rl.config.Window.Seconds())
		limiter = rate.NewLimiter(r, rl.config.Burst)
		rl.memoryStore[key] = limiter
	}

	allowed := limiter.Allow()
	remaining := limiter.Tokens()

	return allowed, &RateLimitResult{
		Limit:     rl.config.Limit,
		Remaining: int(remaining),
		Reset:     time.Now().Add(rl.config.Window),
		Allowed:   allowed,
	}, nil
}

func (rl *RateLimiter) allowSlidingWindow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	return rl.allowTokenBucket(ctx, key)
}

func (rl *RateLimiter) allowFixedWindow(ctx context.Context, key string) (bool, *RateLimitResult, error) {
	fullKey := fmt.Sprintf("%s:fw:%s:%d", rl.config.RedisPrefix, key, time.Now().Unix()/int64(rl.config.Window.Seconds()))

	if rl.useRedis {
		count, err := rl.redisClient.Incr(ctx, fullKey).Result()
		if err != nil {
			rl.logger.Warn("Redis fixed window failed", zap.Error(err))
			return true, &RateLimitResult{Allowed: true}, nil
		}

		if count == 1 {
			rl.redisClient.Expire(ctx, fullKey, rl.config.Window)
		}

		allowed := count <= int64(rl.config.Limit)
		remaining := rl.config.Limit - int(count)
		if remaining < 0 {
			remaining = 0
		}

		return allowed, &RateLimitResult{
			Limit:     rl.config.Limit,
			Remaining: remaining,
			Reset:     time.Now().Add(rl.config.Window),
			Allowed:   allowed,
		}, nil
	}

	rl.mu.Lock()
	defer rl.mu.Unlock()

	limiter, exists := rl.memoryStore[fullKey]
	if !exists {
		r := rate.Limit(float64(rl.config.Limit) / rl.config.Window.Seconds())
		limiter = rate.NewLimiter(r, rl.config.Limit)
		rl.memoryStore[fullKey] = limiter
	}

	allowed := limiter.Allow()
	return allowed, &RateLimitResult{Allowed: allowed}, nil
}

type UserRateLimit struct {
	UserID   string
	Limit    int
	Window   time.Duration
	Strategy RateLimitStrategy
}

type RateLimitManager struct {
	limiter    *RateLimiter
	userLimits map[string]*UserRateLimit
	mu         sync.RWMutex
	defaultCfg *RateLimitConfig
}

func NewRateLimitManager(defaultCfg *RateLimitConfig, redisClient *redis.Client, logger *zap.Logger) *RateLimitManager {
	return &RateLimitManager{
		limiter:    NewRateLimiter(defaultCfg, redisClient, logger),
		userLimits: make(map[string]*UserRateLimit),
		defaultCfg: defaultCfg,
	}
}

func (m *RateLimitManager) SetUserLimit(userID string, limit int, window time.Duration, strategy RateLimitStrategy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.userLimits[userID] = &UserRateLimit{
		UserID:   userID,
		Limit:    limit,
		Window:   window,
		Strategy: strategy,
	}
}

func (m *RateLimitManager) Allow(ctx context.Context, userID string, path string) (bool, *RateLimitResult, error) {
	m.mu.RLock()
	userLimit, hasCustom := m.userLimits[userID]
	m.mu.RUnlock()

	key := fmt.Sprintf("%s:%s", userID, path)

	if hasCustom {
		customLimiter := NewRateLimiter(&RateLimitConfig{
			Strategy:    userLimit.Strategy,
			Limit:       userLimit.Limit,
			Window:      userLimit.Window,
			Burst:       userLimit.Limit,
			RedisPrefix: "rl:custom",
		}, m.limiter.redisClient, m.limiter.logger)
		return customLimiter.Allow(ctx, key)
	}

	return m.limiter.Allow(ctx, key)
}

func (m *RateLimitManager) CleanupOld() {
	m.limiter.mu.Lock()
	defer m.limiter.mu.Unlock()

	for key, limiter := range m.limiter.memoryStore {
		if limiter.Tokens() == float64(m.limiter.config.Burst) {
			delete(m.limiter.memoryStore, key)
		}
	}
}
