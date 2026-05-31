package gasestimator

import (
	"context"
	"encoding/json"
	"fmt"
	"math/big"
	"sort"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"gorm.io/gorm"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
)

type GasEstimateResult struct {
	ChainID           uint64   `json:"chain_id"`
	Slow              uint64   `json:"slow"`
	Standard          uint64   `json:"standard"`
	Fast              uint64   `json:"fast"`
	BaseFee           uint64   `json:"base_fee,omitempty"`
	PriorityFeeSlow   uint64   `json:"priority_fee_slow,omitempty"`
	PriorityFeeStandard uint64 `json:"priority_fee_standard,omitempty"`
	PriorityFeeFast   uint64   `json:"priority_fee_fast,omitempty"`
	EstimatedAt       time.Time `json:"estimated_at"`
	BlockNumber       uint64   `json:"block_number"`
}

type GasEstimator struct {
	db            *gorm.DB
	redisClient   *redis.Client
	chainRPC      map[uint64]ChainRPCInterface
	cache         *MultiLevelCache
	cacheWarmer   *CacheWarmer
	cacheInvalidator *CacheInvalidator
	mu            sync.RWMutex
}

type ChainRPCInterface interface {
	GetLatestBlockNumber(ctx context.Context, chainID uint64) (uint64, error)
	GetBlockByNumberForGasEstimator(ctx context.Context, chainID uint64, blockNumber uint64) (*BlockData, error)
	GetBaseFeePerGas(ctx context.Context, chainID uint64) (*big.Int, error)
	GetMaxPriorityFeePerGas(ctx context.Context, chainID uint64) (*big.Int, error)
}

type BlockData struct {
	Number       uint64
	Timestamp    time.Time
	BaseFeePerGas *big.Int
	Transactions []TransactionData
}

type TransactionData struct {
	GasPrice             *big.Int
	MaxFeePerGas         *big.Int
	MaxPriorityFeePerGas *big.Int
	GasUsed              uint64
}

func NewGasEstimator(db *gorm.DB, redisClient *redis.Client) *GasEstimator {
	l1TTL := 5 * time.Second
	l2TTL := time.Duration(config.AppConfig.GasEstimator.CacheTTL) * time.Second
	l1MaxSize := 1000

	cache := NewMultiLevelCache(redisClient, l1TTL, l2TTL, l1MaxSize)
	cacheInvalidator := NewCacheInvalidator(cache)

	return &GasEstimator{
		db:               db,
		redisClient:      redisClient,
		chainRPC:         make(map[uint64]ChainRPCInterface),
		cache:            cache,
		cacheInvalidator: cacheInvalidator,
	}
}

func (ge *GasEstimator) RegisterChainRPC(chainID uint64, rpc ChainRPCInterface) {
	ge.mu.Lock()
	defer ge.mu.Unlock()
	ge.chainRPC[chainID] = rpc
}

func (ge *GasEstimator) EstimateGas(ctx context.Context, chainID uint64) (*GasEstimateResult, error) {
	cacheKey := fmt.Sprintf("gas:estimate:%d", chainID)

	cachedData, err := ge.cache.Get(ctx, cacheKey)
	if err == nil {
		var result GasEstimateResult
		if err := json.Unmarshal(cachedData, &result); err == nil {
			return &result, nil
		}
	}

	ge.mu.RLock()
	rpc, exists := ge.chainRPC[chainID]
	ge.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	result, err := ge.calculateGasEstimate(ctx, chainID, rpc)
	if err != nil {
		logger.Log.Error("Gas estimation failed", zap.Uint64("chain_id", chainID), zap.Error(err))
		return nil, errors.ErrGasEstimationFailed
	}

	if data, err := json.Marshal(result); err == nil {
		ge.cache.Set(ctx, cacheKey, data)
	}

	if err := ge.persistGasRecord(chainID, result); err != nil {
		logger.Log.Warn("Failed to persist gas record", zap.Error(err))
	}

	return result, nil
}

func (ge *GasEstimator) calculateGasEstimate(ctx context.Context, chainID uint64, rpc ChainRPCInterface) (*GasEstimateResult, error) {
	latestBlock, err := rpc.GetLatestBlockNumber(ctx, chainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get latest block: %w", err)
	}

	historyBlocks := config.AppConfig.GasEstimator.HistoryBlocks
	allGasPrices := make([]uint64, 0, historyBlocks*10)
	allPriorityFees := make([]uint64, 0, historyBlocks*10)

	var baseFee uint64
	baseFeeBig, err := rpc.GetBaseFeePerGas(ctx, chainID)
	if err == nil && baseFeeBig != nil {
		baseFee = baseFeeBig.Uint64()
	}

	for i := 0; i < historyBlocks; i++ {
		blockNum := latestBlock - uint64(i)
		block, err := rpc.GetBlockByNumberForGasEstimator(ctx, chainID, blockNum)
		if err != nil {
			continue
		}

		for _, tx := range block.Transactions {
			if tx.GasPrice != nil && tx.GasUsed > 0 {
				allGasPrices = append(allGasPrices, tx.GasPrice.Uint64())
			}
			if tx.MaxPriorityFeePerGas != nil {
				allPriorityFees = append(allPriorityFees, tx.MaxPriorityFeePerGas.Uint64())
			}
		}
	}

	if len(allGasPrices) == 0 {
		defaultPrice := uint64(1_000_000_000)
		return &GasEstimateResult{
			ChainID:     chainID,
			Slow:        defaultPrice,
			Standard:    defaultPrice * 2,
			Fast:        defaultPrice * 3,
			BaseFee:     baseFee,
			EstimatedAt: time.Now(),
			BlockNumber: latestBlock,
		}, nil
	}

	sort.Slice(allGasPrices, func(i, j int) bool {
		return allGasPrices[i] < allGasPrices[j]
	})

	percentile := config.AppConfig.GasEstimator.Percentile
	slow := ge.percentile(allGasPrices, percentile-20)
	standard := ge.percentile(allGasPrices, percentile)
	fast := ge.percentile(allGasPrices, percentile+20)

	var prioritySlow, priorityStandard, priorityFast uint64
	if len(allPriorityFees) > 0 {
		sort.Slice(allPriorityFees, func(i, j int) bool {
			return allPriorityFees[i] < allPriorityFees[j]
		})
		prioritySlow = ge.percentile(allPriorityFees, percentile-20)
		priorityStandard = ge.percentile(allPriorityFees, percentile)
		priorityFast = ge.percentile(allPriorityFees, percentile+20)
	}

	return &GasEstimateResult{
		ChainID:             chainID,
		Slow:                slow,
		Standard:            standard,
		Fast:                fast,
		BaseFee:             baseFee,
		PriorityFeeSlow:     prioritySlow,
		PriorityFeeStandard: priorityStandard,
		PriorityFeeFast:     priorityFast,
		EstimatedAt:         time.Now(),
		BlockNumber:         latestBlock,
	}, nil
}

func (ge *GasEstimator) percentile(sorted []uint64, p float64) uint64 {
	if len(sorted) == 0 {
		return 0
	}
	if p <= 0 {
		return sorted[0]
	}
	if p >= 100 {
		return sorted[len(sorted)-1]
	}

	index := int(float64(len(sorted)-1) * p / 100)
	return sorted[index]
}

func (ge *GasEstimator) persistGasRecord(chainID uint64, result *GasEstimateResult) error {
	record := &models.GasPriceRecord{
		ChainID:     chainID,
		BlockNumber: result.BlockNumber,
		BlockTime:   result.EstimatedAt,
		Low:         result.Slow,
		Average:     result.Standard,
		High:        result.Fast,
		BaseFee:     result.BaseFee,
	}
	return ge.db.Create(record).Error
}

func (ge *GasEstimator) GetHistory(ctx context.Context, chainID uint64, startTime, endTime time.Time) ([]models.GasPriceRecord, error) {
	var records []models.GasPriceRecord
	err := ge.db.Where("chain_id = ? AND block_time BETWEEN ? AND ?", chainID, startTime, endTime).
		Order("block_time DESC").
		Find(&records).Error
	return records, err
}

func (ge *GasEstimator) StartCollector(ctx context.Context, chainID uint64, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_, err := ge.EstimateGas(ctx, chainID)
			if err != nil {
				logger.Log.Error("Gas collection failed", zap.Uint64("chain_id", chainID), zap.Error(err))
			}
		}
	}
}

func (ge *GasEstimator) StartCacheWarmer(chainIDs []uint64, interval time.Duration) {
	if ge.cacheWarmer != nil {
		ge.cacheWarmer.Stop()
	}
	ge.cacheWarmer = NewCacheWarmer(ge.cache, ge, chainIDs, interval)
	ge.cacheWarmer.Start()
}

func (ge *GasEstimator) StopCacheWarmer() {
	if ge.cacheWarmer != nil {
		ge.cacheWarmer.Stop()
		ge.cacheWarmer = nil
	}
}

func (ge *GasEstimator) InvalidateCache(ctx context.Context, chainID uint64) {
	if ge.cacheInvalidator != nil {
		ge.cacheInvalidator.ManualInvalidate(ctx, chainID)
	}
}

func (ge *GasEstimator) OnNewBlock(ctx context.Context, chainID uint64, blockNumber uint64) {
	if ge.cacheInvalidator != nil {
		ge.cacheInvalidator.OnNewBlock(ctx, chainID, blockNumber)
	}
}

func (ge *GasEstimator) GetCacheStats() map[string]interface{} {
	if ge.cacheInvalidator != nil {
		return ge.cacheInvalidator.GetStats()
	}
	return nil
}

func (ge *GasEstimator) ClearCache(ctx context.Context) {
	if ge.cache != nil {
		ge.cache.Clear(ctx)
		logger.Log.Info("Gas cache cleared")
	}
}

func (ge *GasEstimator) EstimateGasWithoutCache(ctx context.Context, chainID uint64) (*GasEstimateResult, error) {
	ge.mu.RLock()
	rpc, exists := ge.chainRPC[chainID]
	ge.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	result, err := ge.calculateGasEstimate(ctx, chainID, rpc)
	if err != nil {
		logger.Log.Error("Gas estimation without cache failed", zap.Uint64("chain_id", chainID), zap.Error(err))
		return nil, errors.ErrGasEstimationFailed
	}

	return result, nil
}
