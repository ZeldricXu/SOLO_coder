package transaction

import (
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"gas-estimator/internal/domain"
	"gas-estimator/internal/infra/cache"
	"math/big"
	"sync"
	"time"
)

var (
	ErrCacheBuildFailed = errors.New("failed to build cached transaction")
)

type CachedTransactionBuilder struct {
	baseBuilder domain.TransactionService
	cache       *cache.MultiLevelCache
	warmupKeys  []string
	mutex       sync.RWMutex
}

type CachedTransactionConfig struct {
	LocalCacheSize    int
	LocalTTL          time.Duration
	RedisAddr         string
	RedisPassword     string
	RedisDB           int
	DistributedTTL    time.Duration
	EnableWarming     bool
	WarmingKeys       []string
}

func NewCachedTransactionBuilder(baseBuilder domain.TransactionService, config *CachedTransactionConfig) (domain.TransactionService, error) {
	if config == nil {
		config = &CachedTransactionConfig{
			LocalCacheSize: 1000,
			LocalTTL:       5 * time.Minute,
			DistributedTTL: 30 * time.Minute,
		}
	}

	cacheConfig := &cache.CacheConfig{
		LocalCacheSize: config.LocalCacheSize,
		LocalTTL:       config.LocalTTL,
		RedisAddr:      config.RedisAddr,
		RedisPassword:  config.RedisPassword,
		RedisDB:        config.RedisDB,
		DistributedTTL: config.DistributedTTL,
		EnableWarming:  config.EnableWarming,
		WarmingKeys:    config.WarmingKeys,
	}

	mlCache, err := cache.NewMultiLevelCache(cacheConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create multi-level cache: %w", err)
	}

	builder := &CachedTransactionBuilder{
		baseBuilder: baseBuilder,
		cache:       mlCache,
		warmupKeys:  config.WarmingKeys,
		mutex:       sync.RWMutex{},
	}

	if config.EnableWarming {
		go builder.warmup(context.Background())
	}

	return builder, nil
}

func (c *CachedTransactionBuilder) Build(params domain.TransactionParams) (*domain.Transaction, error) {
	cacheKey := c.generateBuildKey(params)
	
	ctx := context.Background()
	
	cachedValue, err := c.cache.Get(ctx, cacheKey)
	if err == nil {
		if tx, ok := cachedValue.(*domain.Transaction); ok {
			return tx, nil
		}
	}

	tx, err := c.baseBuilder.Build(params)
	if err != nil {
		return nil, err
	}

	c.cache.Set(ctx, cacheKey, tx, 5*time.Minute)

	return tx, nil
}

func (c *CachedTransactionBuilder) Validate(tx *domain.Transaction) error {
	return c.baseBuilder.Validate(tx)
}

func (c *CachedTransactionBuilder) Sign(tx *domain.Transaction, privateKey *ecdsa.PrivateKey, weight uint32) error {
	return c.baseBuilder.Sign(tx, privateKey, weight)
}

func (c *CachedTransactionBuilder) VerifySignature(tx *domain.Transaction, sig domain.Signature) bool {
	return c.baseBuilder.VerifySignature(tx, sig)
}

func (c *CachedTransactionBuilder) Serialize(tx *domain.Transaction) ([]byte, error) {
	cacheKey := c.generateSerializeKey(tx)
	
	ctx := context.Background()
	
	cachedValue, err := c.cache.Get(ctx, cacheKey)
	if err == nil {
		if serialized, ok := cachedValue.([]byte); ok {
			return serialized, nil
		}
	}

	serialized, err := c.baseBuilder.Serialize(tx)
	if err != nil {
		return nil, err
	}

	c.cache.Set(ctx, cacheKey, serialized, 5*time.Minute)

	return serialized, nil
}

func (c *CachedTransactionBuilder) AddTimestamp(tx *domain.Transaction) error {
	return c.baseBuilder.AddTimestamp(tx)
}

func (c *CachedTransactionBuilder) OptimizeGas(tx *domain.Transaction, baseFee *big.Int, priorityFee *big.Int) error {
	return c.baseBuilder.OptimizeGas(tx, baseFee, priorityFee)
}

func (c *CachedTransactionBuilder) InvalidateCache(ctx context.Context, pattern string) error {
	return c.cache.Invalidate(ctx, pattern)
}

func (c *CachedTransactionBuilder) GetCacheStats() *cache.CacheStats {
	return c.cache.GetStats()
}

func (c *CachedTransactionBuilder) warmup(ctx context.Context) {
	if len(c.warmupKeys) == 0 {
		return
	}

	for _, key := range c.warmupKeys {
		params, err := c.parseWarmupKey(key)
		if err != nil {
			continue
		}
		
		tx, err := c.baseBuilder.Build(params)
		if err == nil {
			c.cache.Set(ctx, key, tx, 30*time.Minute)
		}
	}
}

func (c *CachedTransactionBuilder) generateBuildKey(params domain.TransactionParams) string {
	data, _ := json.Marshal(params)
	hash := sha256.Sum256(data)
	return fmt.Sprintf("tx:build:%s", hex.EncodeToString(hash[:]))
}

func (c *CachedTransactionBuilder) generateSerializeKey(tx *domain.Transaction) string {
	data := fmt.Sprintf("%d:%d:%d:%x:%x", 
		tx.ChainID.Int64(), 
		tx.Nonce, 
		tx.GasLimit, 
		tx.To, 
		tx.Data,
	)
	hash := sha256.Sum256([]byte(data))
	return fmt.Sprintf("tx:serialize:%s", hex.EncodeToString(hash[:]))
}

func (c *CachedTransactionBuilder) parseWarmupKey(key string) (domain.TransactionParams, error) {
	return domain.TransactionParams{}, nil
}

func (c *CachedTransactionBuilder) Close() error {
	if c.cache != nil {
		return c.cache.Close()
	}
	return nil
}
