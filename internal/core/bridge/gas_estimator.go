package bridge

import (
	"context"
	"math/big"
	"sync"
	"time"

	"go.uber.org/zap"
)

const (
	defaultGasLimitETH     = 21000
	defaultGasLimitERC20   = 65000
	defaultGasLimitNFT     = 100000
	historyLookupWindow    = 100
	cacheTTL               = 10 * time.Second
)

type feeTierConfig struct {
	multiplier float64
	confidence float64
}

var feeTierConfigs = map[string]feeTierConfig{
	"slow":   {multiplier: 0.9, confidence: 0.5},
	"standard": {multiplier: 1.0, confidence: 0.8},
	"fast":   {multiplier: 1.2, confidence: 0.95},
}

type gasCacheEntry struct {
	estimate   *GasEstimate
	timestamp  time.Time
}

type HistoricalGasEstimator struct {
	repo          BridgeRepository
	logger        *zap.Logger
	cache         map[ChainID]gasCacheEntry
	cacheMu       sync.RWMutex
	gasLimitMap   map[AssetType]uint64
}

type GasEstimatorDependencies struct {
	Repository BridgeRepository
	Logger     *zap.Logger
}

func NewHistoricalGasEstimator(deps GasEstimatorDependencies) GasEstimator {
	return &HistoricalGasEstimator{
		repo:        deps.Repository,
		logger:      deps.Logger,
		cache:       make(map[ChainID]gasCacheEntry),
		gasLimitMap: map[AssetType]uint64{
			AssetTypeETH:   defaultGasLimitETH,
			AssetTypeERC20: defaultGasLimitERC20,
			AssetTypeNFT:   defaultGasLimitNFT,
		},
	}
}

func (g *HistoricalGasEstimator) EstimateGas(
	ctx context.Context,
	chainID ChainID,
	assetType AssetType,
	feeTier string,
) (*GasEstimate, error) {
	if cached := g.getFromCache(chainID); cached != nil {
		return g.adjustForAssetType(cached, assetType, feeTier), nil
	}

	networkStatus, err := g.repo.GetNetworkStatus(ctx, chainID)
	if err != nil {
		g.logger.Warn("Failed to get network status, using historical data",
			zap.Uint64("chain_id", uint64(chainID)),
			zap.Error(err))
		return g.estimateFromHistory(ctx, chainID, assetType, feeTier)
	}

	historicalData, err := g.repo.GetHistoricalData(ctx, chainID, historyLookupWindow)
	if err != nil {
		g.logger.Warn("Failed to get historical data, using network status only",
			zap.Uint64("chain_id", uint64(chainID)),
			zap.Error(err))
		return g.estimateFromNetworkStatus(networkStatus, assetType, feeTier), nil
	}

	estimate := g.calculateEstimate(networkStatus, historicalData, assetType, feeTier)
	g.addToCache(chainID, estimate)

	return estimate, nil
}

func (g *HistoricalGasEstimator) GetHistoricalData(
	ctx context.Context,
	chainID ChainID,
	lookback time.Duration,
) ([]HistoricalGasData, error) {
	return g.repo.GetHistoricalData(ctx, chainID, lookback)
}

func (g *HistoricalGasEstimator) GetNetworkStatus(
	ctx context.Context,
	chainID ChainID,
) (*NetworkStatus, error) {
	return g.repo.GetNetworkStatus(ctx, chainID)
}

func (g *HistoricalGasEstimator) calculateEstimate(
	network *NetworkStatus,
	history []HistoricalGasData,
	assetType AssetType,
	feeTier string,
) *GasEstimate {
	avgGasPrice := g.calculateWeightedAvgGasPrice(history)
	baseFee := network.BaseFee
	if baseFee == nil {
		baseFee = avgGasPrice
	}

	priorityFee := g.calculatePriorityFee(history, network.CongestionLevel)
	gasLimit := g.getGasLimit(assetType)

	tierConfig := g.getFeeTierConfig(feeTier)

	gasPrice := new(big.Int).Add(baseFee, priorityFee)
	gasPrice = new(big.Int).Mul(gasPrice, big.NewInt(int64(tierConfig.multiplier*100)))
	gasPrice = new(big.Int).Div(gasPrice, big.NewInt(100))

	totalCost := new(big.Int).Mul(gasPrice, new(big.Int).SetUint64(gasLimit))

	return &GasEstimate{
		GasLimit:    new(big.Int).SetUint64(gasLimit),
		GasPrice:    gasPrice,
		PriorityFee: priorityFee,
		BaseFee:     baseFee,
		TotalCost:   totalCost,
		Confidence:  tierConfig.confidence,
		Source:      "hybrid_network_history",
	}
}

func (g *HistoricalGasEstimator) calculateWeightedAvgGasPrice(history []HistoricalGasData) *big.Int {
	if len(history) == 0 {
		return big.NewInt(20000000000)
	}

	var weightedSum big.Int
	var weightSum big.Int

	for i, data := range history {
		weight := big.NewInt(int64(len(history) - i))
		weightedSum.Add(&weightedSum, new(big.Int).Mul(data.GasPrice, weight))
		weightSum.Add(&weightSum, weight)
	}

	return new(big.Int).Div(&weightedSum, &weightSum)
}

func (g *HistoricalGasEstimator) calculatePriorityFee(
	history []HistoricalGasData,
	congestionLevel float64,
) *big.Int {
	basePriority := big.NewInt(1000000000)

	congestionMultiplier := int64(1 + congestionLevel)
	return new(big.Int).Mul(basePriority, big.NewInt(congestionMultiplier))
}

func (g *HistoricalGasEstimator) estimateFromHistory(
	ctx context.Context,
	chainID ChainID,
	assetType AssetType,
	feeTier string,
) (*GasEstimate, error) {
	history, err := g.repo.GetHistoricalData(ctx, chainID, historyLookupWindow)
	if err != nil {
		return g.fallbackEstimate(assetType, feeTier), nil
	}

	avgGasPrice := g.calculateWeightedAvgGasPrice(history)
	gasLimit := g.getGasLimit(assetType)
	tierConfig := g.getFeeTierConfig(feeTier)

	gasPrice := new(big.Int).Mul(avgGasPrice, big.NewInt(int64(tierConfig.multiplier*100)))
	gasPrice = new(big.Int).Div(gasPrice, big.NewInt(100))
	totalCost := new(big.Int).Mul(gasPrice, new(big.Int).SetUint64(gasLimit))

	return &GasEstimate{
		GasLimit:    new(big.Int).SetUint64(gasLimit),
		GasPrice:    gasPrice,
		PriorityFee: big.NewInt(1000000000),
		BaseFee:     avgGasPrice,
		TotalCost:   totalCost,
		Confidence:  tierConfig.confidence * 0.7,
		Source:      "historical_only",
	}, nil
}

func (g *HistoricalGasEstimator) estimateFromNetworkStatus(
	network *NetworkStatus,
	assetType AssetType,
	feeTier string,
) *GasEstimate {
	gasLimit := g.getGasLimit(assetType)
	tierConfig := g.getFeeTierConfig(feeTier)

	gasPrice := new(big.Int).Mul(network.AvgGasPrice, big.NewInt(int64(tierConfig.multiplier*100)))
	gasPrice = new(big.Int).Div(gasPrice, big.NewInt(100))
	totalCost := new(big.Int).Mul(gasPrice, new(big.Int).SetUint64(gasLimit))

	return &GasEstimate{
		GasLimit:    new(big.Int).SetUint64(gasLimit),
		GasPrice:    gasPrice,
		PriorityFee: big.NewInt(1000000000),
		BaseFee:     network.BaseFee,
		TotalCost:   totalCost,
		Confidence:  tierConfig.confidence * 0.8,
		Source:      "network_only",
	}
}

func (g *HistoricalGasEstimator) fallbackEstimate(assetType AssetType, feeTier string) *GasEstimate {
	gasLimit := g.getGasLimit(assetType)
	tierConfig := g.getFeeTierConfig(feeTier)
	gasPrice := big.NewInt(20000000000)
	gasPrice = new(big.Int).Mul(gasPrice, big.NewInt(int64(tierConfig.multiplier*100)))
	gasPrice = new(big.Int).Div(gasPrice, big.NewInt(100))
	totalCost := new(big.Int).Mul(gasPrice, new(big.Int).SetUint64(gasLimit))

	return &GasEstimate{
		GasLimit:    new(big.Int).SetUint64(gasLimit),
		GasPrice:    gasPrice,
		PriorityFee: big.NewInt(1000000000),
		BaseFee:     big.NewInt(20000000000),
		TotalCost:   totalCost,
		Confidence:  0.3,
		Source:      "fallback_default",
	}
}

func (g *HistoricalGasEstimator) getGasLimit(assetType AssetType) uint64 {
	if limit, ok := g.gasLimitMap[assetType]; ok {
		return limit
	}
	return defaultGasLimitETH
}

func (g *HistoricalGasEstimator) getFeeTierConfig(feeTier string) feeTierConfig {
	if cfg, ok := feeTierConfigs[feeTier]; ok {
		return cfg
	}
	return feeTierConfigs["standard"]
}

func (g *HistoricalGasEstimator) adjustForAssetType(
	base *GasEstimate,
	assetType AssetType,
	feeTier string,
) *GasEstimate {
	gasLimit := new(big.Int).SetUint64(g.getGasLimit(assetType))
	tierConfig := g.getFeeTierConfig(feeTier)

	gasPrice := new(big.Int).Mul(base.GasPrice, big.NewInt(int64(tierConfig.multiplier*100)))
	gasPrice = new(big.Int).Div(gasPrice, big.NewInt(100))

	totalCost := new(big.Int).Mul(gasPrice, gasLimit)

	return &GasEstimate{
		GasLimit:    gasLimit,
		GasPrice:    gasPrice,
		PriorityFee: base.PriorityFee,
		BaseFee:     base.BaseFee,
		TotalCost:   totalCost,
		Confidence:  base.Confidence * tierConfig.confidence,
		Source:      base.Source + "_cached",
	}
}

func (g *HistoricalGasEstimator) getFromCache(chainID ChainID) *GasEstimate {
	g.cacheMu.RLock()
	defer g.cacheMu.RUnlock()

	entry, ok := g.cache[chainID]
	if !ok {
		return nil
	}

	if time.Since(entry.timestamp) > cacheTTL {
		return nil
	}

	return entry.estimate
}

func (g *HistoricalGasEstimator) addToCache(chainID ChainID, estimate *GasEstimate) {
	g.cacheMu.Lock()
	defer g.cacheMu.Unlock()

	g.cache[chainID] = gasCacheEntry{
		estimate:  estimate,
		timestamp: time.Now(),
	}
}
