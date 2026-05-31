package gas

import (
	"errors"
	"gas-estimator/internal/chain"
	"gas-estimator/pkg/models"
	"math"
	"math/big"
	"sort"
	"sync"
	"time"
)

var (
	ErrInsufficientHistory = errors.New("insufficient historical data")
	ErrNetworkUnavailable   = errors.New("network unavailable")
)

type GasEstimator struct {
	chainAdapter    *chain.ChainAdapter
	historicalData  map[string][]*HistoricalBlock
	currentBaseFee  *big.Int
	currentPriorityFee *big.Int
	historySize     int
	mutex           sync.RWMutex
}

type HistoricalBlock struct {
	BlockNumber uint64
	Timestamp   time.Time
	BaseFee     *big.Int
	PriorityFee *big.Int
	GasUsed     uint64
	GasLimit    uint64
	TxCount     int
}

type GasEstimateConfig struct {
	HistoryBlocks    int
	ConfidenceLevel  float64
	SafetyMultiplier float64
}

func NewGasEstimator(chainAdapter *chain.ChainAdapter) *GasEstimator {
	return &GasEstimator{
		chainAdapter:   chainAdapter,
		historicalData: make(map[string][]*HistoricalBlock),
		historySize:    100,
		mutex:          sync.RWMutex{},
	}
}

func (ge *GasEstimator) EstimateGas(chainID string, tx *models.Transaction, urgency string) (*models.GasEstimate, error) {
	ge.mutex.Lock()
	defer ge.mutex.Unlock()
	
	if err := ge.ensureChainData(chainID); err != nil {
		return nil, err
	}
	
	currentChain := ge.chainAdapter.GetCurrentChain()
	if currentChain != chainID {
		if err := ge.chainAdapter.SwitchChain(chainID); err != nil {
			return nil, err
		}
	}
	
	latestBlock, err := ge.chainAdapter.GetLatestBlock()
	if err != nil {
		return nil, ErrNetworkUnavailable
	}
	
	ge.updateHistoricalData(chainID, latestBlock)
	
	var gasLimit uint64
	if tx != nil {
		gasLimit, err = ge.chainAdapter.EstimateGas(tx)
		if err != nil {
			gasLimit = 21000
		}
	} else {
		gasLimit = 21000
	}
	
	baseFee := ge.calculateBaseFee(chainID, urgency)
	priorityFee := ge.calculatePriorityFee(chainID, urgency)
	maxFee := ge.calculateMaxFee(baseFee, priorityFee)
	confidence := ge.calculateConfidence(chainID)
	
	estimatedTotal := new(big.Int).Mul(maxFee, big.NewInt(int64(gasLimit)))
	
	return &models.GasEstimate{
		ChainID:        chainID,
		GasLimit:       gasLimit,
		BaseFee:        baseFee,
		PriorityFee:    priorityFee,
		MaxFee:         maxFee,
		EstimatedTotal: estimatedTotal,
		Confidence:     confidence,
		Timestamp:      time.Now(),
	}, nil
}

func (ge *GasEstimator) EstimateGasWithConfig(chainID string, tx *models.Transaction, config GasEstimateConfig) (*models.GasEstimate, error) {
	ge.mutex.Lock()
	defer ge.mutex.Unlock()
	
	if err := ge.ensureChainData(chainID); err != nil {
		return nil, err
	}
	
	currentChain := ge.chainAdapter.GetCurrentChain()
	if currentChain != chainID {
		if err := ge.chainAdapter.SwitchChain(chainID); err != nil {
			return nil, err
		}
	}
	
	latestBlock, err := ge.chainAdapter.GetLatestBlock()
	if err != nil {
		return nil, ErrNetworkUnavailable
	}
	
	ge.updateHistoricalData(chainID, latestBlock)
	
	var gasLimit uint64
	if tx != nil {
		gasLimit, err = ge.chainAdapter.EstimateGas(tx)
		if err != nil {
			gasLimit = 21000
		}
	} else {
		gasLimit = 21000
	}
	
	history, ok := ge.historicalData[chainID]
	if !ok || len(history) < 5 {
		return nil, ErrInsufficientHistory
	}
	
	baseFees := make([]*big.Int, 0, len(history))
	priorityFees := make([]*big.Int, 0, len(history))
	
	for _, block := range history {
		if block.BaseFee != nil {
			baseFees = append(baseFees, block.BaseFee)
		}
		if block.PriorityFee != nil {
			priorityFees = append(priorityFees, block.PriorityFee)
		}
	}
	
	if len(baseFees) == 0 || len(priorityFees) == 0 {
		return nil, ErrInsufficientHistory
	}
	
	sort.Slice(baseFees, func(i, j int) bool {
		return baseFees[i].Cmp(baseFees[j]) < 0
	})
	
	sort.Slice(priorityFees, func(i, j int) bool {
		return priorityFees[i].Cmp(priorityFees[j]) < 0
	})
	
	baseFeeIndex := int(float64(len(baseFees)) * config.ConfidenceLevel)
	if baseFeeIndex >= len(baseFees) {
		baseFeeIndex = len(baseFees) - 1
	}
	
	priorityFeeIndex := int(float64(len(priorityFees)) * config.ConfidenceLevel)
	if priorityFeeIndex >= len(priorityFees) {
		priorityFeeIndex = len(priorityFees) - 1
	}
	
	baseFee := new(big.Int).Set(baseFees[baseFeeIndex])
	priorityFee := new(big.Int).Set(priorityFees[priorityFeeIndex])
	
	baseFee.Mul(baseFee, bigNewFloatToInt(config.SafetyMultiplier))
	priorityFee.Mul(priorityFee, bigNewFloatToInt(config.SafetyMultiplier))
	
	maxFee := new(big.Int).Add(baseFee, priorityFee)
	estimatedTotal := new(big.Int).Mul(maxFee, big.NewInt(int64(gasLimit)))
	
	return &models.GasEstimate{
		ChainID:        chainID,
		GasLimit:       gasLimit,
		BaseFee:        baseFee,
		PriorityFee:    priorityFee,
		MaxFee:         maxFee,
		EstimatedTotal: estimatedTotal,
		Confidence:     config.ConfidenceLevel,
		Timestamp:      time.Now(),
	}, nil
}

func (ge *GasEstimator) GetHistoricalData(chainID string, limit int) ([]*HistoricalBlock, error) {
	ge.mutex.RLock()
	defer ge.mutex.RUnlock()
	
	history, ok := ge.historicalData[chainID]
	if !ok {
		return nil, ErrInsufficientHistory
	}
	
	if limit <= 0 || limit > len(history) {
		limit = len(history)
	}
	
	result := make([]*HistoricalBlock, limit)
	copy(result, history[len(history)-limit:])
	
	return result, nil
}

func (ge *GasEstimator) UpdateHistoricalData(chainID string, blocks []*models.Block) error {
	ge.mutex.Lock()
	defer ge.mutex.Unlock()
	
	if err := ge.ensureChainData(chainID); err != nil {
		return err
	}
	
	for _, block := range blocks {
		historicalBlock := &HistoricalBlock{
			BlockNumber: block.Number,
			Timestamp:   block.Timestamp,
			BaseFee:     block.BaseFee,
			GasUsed:     block.GasUsed,
			GasLimit:    block.GasLimit,
			TxCount:     len(block.Transactions),
		}
		
		if len(block.Transactions) > 0 {
			historicalBlock.PriorityFee = ge.estimatePriorityFeeFromBlock(block)
		}
		
		ge.historicalData[chainID] = append(ge.historicalData[chainID], historicalBlock)
	}
	
	if len(ge.historicalData[chainID]) > ge.historySize {
		ge.historicalData[chainID] = ge.historicalData[chainID][len(ge.historicalData[chainID])-ge.historySize:]
	}
	
	return nil
}

func (ge *GasEstimator) GetNetworkStatus(chainID string) (map[string]interface{}, error) {
	ge.mutex.RLock()
	defer ge.mutex.RUnlock()
	
	history, ok := ge.historicalData[chainID]
	if !ok || len(history) == 0 {
		return nil, ErrInsufficientHistory
	}
	
	latestBlock := history[len(history)-1]
	
	stats := map[string]interface{}{
		"latest_block":       latestBlock.BlockNumber,
		"timestamp":          latestBlock.Timestamp,
		"base_fee":           latestBlock.BaseFee,
		"gas_used":           latestBlock.GasUsed,
		"gas_limit":          latestBlock.GasLimit,
		"utilization":        float64(latestBlock.GasUsed) / float64(latestBlock.GasLimit),
		"tx_count":           latestBlock.TxCount,
		"historical_blocks":  len(history),
	}
	
	if len(history) >= 10 {
		recentBlocks := history[len(history)-10:]
		avgBaseFee := big.NewInt(0)
		avgPriorityFee := big.NewInt(0)
		avgTxCount := 0
		
		for _, block := range recentBlocks {
			if block.BaseFee != nil {
				avgBaseFee.Add(avgBaseFee, block.BaseFee)
			}
			if block.PriorityFee != nil {
				avgPriorityFee.Add(avgPriorityFee, block.PriorityFee)
			}
			avgTxCount += block.TxCount
		}
		
		avgBaseFee.Div(avgBaseFee, big.NewInt(int64(len(recentBlocks))))
		avgPriorityFee.Div(avgPriorityFee, big.NewInt(int64(len(recentBlocks))))
		
		stats["avg_base_fee_10"] = avgBaseFee
		stats["avg_priority_fee_10"] = avgPriorityFee
		stats["avg_tx_count_10"] = float64(avgTxCount) / float64(len(recentBlocks))
	}
	
	return stats, nil
}

func (ge *GasEstimator) ensureChainData(chainID string) error {
	if _, ok := ge.historicalData[chainID]; !ok {
		ge.historicalData[chainID] = make([]*HistoricalBlock, 0, ge.historySize)
	}
	return nil
}

func (ge *GasEstimator) updateHistoricalData(chainID string, block *models.Block) {
	if block == nil {
		return
	}
	
	historicalBlock := &HistoricalBlock{
		BlockNumber: block.Number,
		Timestamp:   block.Timestamp,
		BaseFee:     block.BaseFee,
		GasUsed:     block.GasUsed,
		GasLimit:    block.GasLimit,
		TxCount:     len(block.Transactions),
	}
	
	if len(block.Transactions) > 0 {
		historicalBlock.PriorityFee = ge.estimatePriorityFeeFromBlock(block)
	}
	
	ge.historicalData[chainID] = append(ge.historicalData[chainID], historicalBlock)
	
	if len(ge.historicalData[chainID]) > ge.historySize {
		ge.historicalData[chainID] = ge.historicalData[chainID][len(ge.historicalData[chainID])-ge.historySize:]
	}
}

func (ge *GasEstimator) calculateBaseFee(chainID string, urgency string) *big.Int {
	history, ok := ge.historicalData[chainID]
	if !ok || len(history) == 0 {
		return big.NewInt(1000000000)
	}
	
	latest := history[len(history)-1]
	if latest.BaseFee == nil {
		return big.NewInt(1000000000)
	}
	
	multiplier := ge.getMultiplier(urgency)
	baseFee := new(big.Int).Set(latest.BaseFee)
	
	adjustment := bigNewFloatToInt(multiplier)
	baseFee.Mul(baseFee, adjustment)
	
	return baseFee
}

func (ge *GasEstimator) calculatePriorityFee(chainID string, urgency string) *big.Int {
	history, ok := ge.historicalData[chainID]
	if !ok || len(history) < 5 {
		return big.NewInt(100000000)
	}
	
	recentBlocks := history
	if len(recentBlocks) > 20 {
		recentBlocks = recentBlocks[len(recentBlocks)-20:]
	}
	
	priorityFees := make([]*big.Int, 0)
	for _, block := range recentBlocks {
		if block.PriorityFee != nil {
			priorityFees = append(priorityFees, block.PriorityFee)
		}
	}
	
	if len(priorityFees) == 0 {
		return big.NewInt(100000000)
	}
	
	sort.Slice(priorityFees, func(i, j int) bool {
		return priorityFees[i].Cmp(priorityFees[j]) < 0
	})
	
	var index int
	switch urgency {
	case "low":
		index = int(float64(len(priorityFees)) * 0.25)
	case "medium":
		index = int(float64(len(priorityFees)) * 0.5)
	case "high":
		index = int(float64(len(priorityFees)) * 0.75)
	case "urgent":
		index = int(float64(len(priorityFees)) * 0.95)
	default:
		index = int(float64(len(priorityFees)) * 0.5)
	}
	
	if index >= len(priorityFees) {
		index = len(priorityFees) - 1
	}
	
	return new(big.Int).Set(priorityFees[index])
}

func (ge *GasEstimator) calculateMaxFee(baseFee, priorityFee *big.Int) *big.Int {
	maxBaseFee := new(big.Int).Mul(baseFee, big.NewInt(2))
	return new(big.Int).Add(maxBaseFee, priorityFee)
}

func (ge *GasEstimator) calculateConfidence(chainID string) float64 {
	history, ok := ge.historicalData[chainID]
	if !ok {
		return 0.5
	}
	
	historyLen := len(history)
	if historyLen >= 100 {
		return 0.95
	} else if historyLen >= 50 {
		return 0.85
	} else if historyLen >= 20 {
		return 0.70
	} else if historyLen >= 10 {
		return 0.50
	}
	
	return 0.30
}

func (ge *GasEstimator) estimatePriorityFeeFromBlock(block *models.Block) *big.Int {
	if len(block.Transactions) == 0 {
		return big.NewInt(0)
	}
	
	totalPriorityFee := big.NewInt(0)
	count := 0
	
	for _, tx := range block.Transactions {
		if tx.GasPrice != nil && block.BaseFee != nil {
			priorityFee := new(big.Int).Sub(tx.GasPrice, block.BaseFee)
			if priorityFee.Cmp(big.NewInt(0)) > 0 {
				totalPriorityFee.Add(totalPriorityFee, priorityFee)
				count++
			}
		}
	}
	
	if count == 0 {
		return big.NewInt(100000000)
	}
	
	return totalPriorityFee.Div(totalPriorityFee, big.NewInt(int64(count)))
}

func (ge *GasEstimator) getMultiplier(urgency string) float64 {
	switch urgency {
	case "low":
		return 0.9
	case "medium":
		return 1.0
	case "high":
		return 1.1
	case "urgent":
		return 1.2
	default:
		return 1.0
	}
}

func (ge *GasEstimator) CalculateFeeTrend(chainID string) (string, float64, error) {
	ge.mutex.RLock()
	defer ge.mutex.RUnlock()
	
	history, ok := ge.historicalData[chainID]
	if !ok || len(history) < 10 {
		return "", 0, ErrInsufficientHistory
	}
	
	recentBlocks := history[len(history)-10:]
	
	oldAvg := big.NewInt(0)
	newAvg := big.NewInt(0)
	
	for i := 0; i < 5; i++ {
		if recentBlocks[i].BaseFee != nil {
			oldAvg.Add(oldAvg, recentBlocks[i].BaseFee)
		}
	}
	
	for i := 5; i < 10; i++ {
		if recentBlocks[i].BaseFee != nil {
			newAvg.Add(newAvg, recentBlocks[i].BaseFee)
		}
	}
	
	oldAvg.Div(oldAvg, big.NewInt(5))
	newAvg.Div(newAvg, big.NewInt(5))
	
	if oldAvg.Cmp(big.NewInt(0)) == 0 {
		return "stable", 0, nil
	}
	
	diff := new(big.Int).Sub(newAvg, oldAvg)
	ratio := float64(diff.Int64()) / float64(oldAvg.Int64())
	
	if math.Abs(ratio) < 0.01 {
		return "stable", ratio, nil
	} else if ratio > 0 {
		return "rising", ratio, nil
	}
	
	return "falling", ratio, nil
}

func bigNewFloatToInt(f float64) *big.Int {
	scale := big.NewInt(1000000)
	scaled := int64(f * 1000000)
	return big.NewInt(scaled).Div(big.NewInt(scaled), scale)
}
