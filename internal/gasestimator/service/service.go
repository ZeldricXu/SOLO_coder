package service

import (
	"context"
	"math/big"
	"sync"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/eventbus"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/gasestimator/domain"
	"github.com/solocoder/session147/internal/gasestimator/ports"
	"go.uber.org/zap"
)

const (
	defaultHistoricalLimit = 50
	defaultPredictionLimit = 100
	defaultMinDataPoints   = 2

	alertTypeSpike   = "spike"
	alertTypeDrop    = "drop"
	alertTypeHighGas = "high_gas"

	defaultAlertSpike    = 0.3
	defaultAlertDrop     = 0.3
	defaultAlertHighGas  = 100.0

	gasLimitDefault = 21000
)

var (
	weiToGwei        = new(big.Float).SetInt(big.NewInt(1e9))
	bigIntZero       = big.NewInt(0)
	bigFloatZero     = big.NewFloat(0)
	bigIntHundred    = big.NewInt(100)
)

type gasEstimatorService struct {
	repo      ports.GasDataRepository
	chain     ports.ChainDataProvider
	eventBus  *eventbus.EventBus

	mu              sync.RWMutex
	alertThresholds map[string]float64
	lastEstimate    map[int64]*domain.GasEstimate
}

func NewGasEstimatorService(
	repo ports.GasDataRepository,
	chain ports.ChainDataProvider,
	eventBus *eventbus.EventBus,
) ports.GasEstimatorService {
	if eventBus == nil {
		eventBus = eventbus.GetEventBus()
	}

	return &gasEstimatorService{
		repo:      repo,
		chain:     chain,
		eventBus:  eventBus,
		alertThresholds: map[string]float64{
			alertTypeSpike:   defaultAlertSpike,
			alertTypeDrop:    defaultAlertDrop,
			alertTypeHighGas: defaultAlertHighGas,
		},
		lastEstimate: make(map[int64]*domain.GasEstimate),
	}
}

func (s *gasEstimatorService) EstimateGas(ctx context.Context, req *domain.EstimateRequest) (*domain.EstimateResponse, error) {
	logger.Info("estimating gas", zap.Int64("chain_id", req.ChainID))

	estimate, err := s.GetCurrentGasPrice(ctx, req.ChainID)
	if err != nil {
		return nil, err
	}

	gasLimit := req.GasLimit
	if gasLimit == 0 {
		gasLimit = gasLimitDefault
	}

	gasLimitBig := new(big.Int).SetUint64(gasLimit)

	return &domain.EstimateResponse{
		Estimate:      *estimate,
		GasLimit:      gasLimit,
		TotalCostLow:  new(big.Int).Mul(estimate.GasPriceLow, gasLimitBig).String(),
		TotalCostAvg:  new(big.Int).Mul(estimate.GasPriceAvg, gasLimitBig).String(),
		TotalCostHigh: new(big.Int).Mul(estimate.GasPriceHigh, gasLimitBig).String(),
	}, nil
}

func (s *gasEstimatorService) GetCurrentGasPrice(ctx context.Context, chainID int64) (*domain.GasEstimate, error) {
	baseFee, err := s.chain.GetBaseFeePerGas(ctx)
	if err != nil || baseFee == nil {
		baseFee = big.NewInt(1000000000)
	}

	priorityFee, err := s.chain.GetMaxPriorityFeePerGas(ctx)
	if err != nil || priorityFee == nil {
		priorityFee = big.NewInt(1000000000)
	}

	historicalData, err := s.repo.GetLatestGasData(ctx, chainID, defaultHistoricalLimit)
	if err != nil || len(historicalData) == 0 {
		estimate := s.createDefaultEstimate(chainID, baseFee, priorityFee)
		s.checkAndPublishAlertsAsync(ctx, chainID, estimate)
		s.storeLastEstimate(chainID, estimate)
		return estimate, nil
	}

	baseFees, priorityFees := s.parseHistoricalData(historicalData)

	avgBaseFee := calculateAverage(baseFees)
	avgPriorityFee := calculateAverage(priorityFees)

	priorityLow := scaleBigInt(avgPriorityFee, 80)
	priorityHigh := scaleBigInt(avgPriorityFee, 150)

	gasPriceLow := new(big.Int).Add(baseFee, priorityLow)
	gasPriceAvg := new(big.Int).Add(baseFee, avgPriorityFee)
	gasPriceHigh := new(big.Int).Add(baseFee, priorityHigh)

	trend := analyzeTrend(baseFees)
	confidence := calculateConfidence(baseFees)

	nextBaseFee, _ := s.predictNextBaseFeeFromData(baseFees)

	baseFeeGwei, _ := new(big.Float).Quo(new(big.Float).SetInt(baseFee), weiToGwei).Float64()

	estimate := &domain.GasEstimate{
		ChainID:         chainID,
		BaseFee:         baseFee,
		BaseFeeGwei:     baseFeeGwei,
		PriorityFeeLow:  priorityLow,
		PriorityFeeAvg:  avgPriorityFee,
		PriorityFeeHigh: priorityHigh,
		GasPriceLow:     gasPriceLow,
		GasPriceAvg:     gasPriceAvg,
		GasPriceHigh:    gasPriceHigh,
		MaxFeePerGas:    gasPriceHigh,
		EstimatedAt:     time.Now(),
		Confidence:      confidence,
		Trend:           trend,
		NextBlockBaseFee: nextBaseFee,
	}

	s.checkAndPublishAlertsAsync(ctx, chainID, estimate)
	s.storeLastEstimate(chainID, estimate)

	s.publishGasPriceUpdatedAsync(ctx, chainID, baseFee, gasPriceAvg, trend, confidence)

	return estimate, nil
}

func (s *gasEstimatorService) checkAndPublishAlertsAsync(ctx context.Context, chainID int64, current *domain.GasEstimate) {
	go func() {
		last := s.getLastEstimate(chainID)
		if last == nil {
			return
		}

		lastAvg := new(big.Float).SetInt(last.GasPriceAvg)
		currentAvg := new(big.Float).SetInt(current.GasPriceAvg)
		if lastAvg.Cmp(bigFloatZero) == 0 {
			return
		}

		diff := new(big.Float).Sub(currentAvg, lastAvg)
		ratio, _ := new(big.Float).Quo(diff, lastAvg).Float64()

		thresholds := s.getAlertThresholds()

		if ratio > thresholds[alertTypeSpike] {
			s.eventBus.Publish(ctx, eventbus.NewEvent(
				eventbus.EventTypeGasPriceAlert,
				"gas_estimator",
				map[string]interface{}{
					"chain_id":   chainID,
					"alert_type": alertTypeSpike,
					"previous":   last.GasPriceAvg.String(),
					"current":    current.GasPriceAvg.String(),
					"change_pct": ratio * 100,
					"message":    "Gas price spike detected",
				},
			))
			logger.Warn("gas price spike detected",
				zap.Int64("chain_id", chainID),
				zap.Float64("change_pct", ratio*100))
		}

		if ratio < -thresholds[alertTypeDrop] {
			s.eventBus.Publish(ctx, eventbus.NewEvent(
				eventbus.EventTypeGasPriceAlert,
				"gas_estimator",
				map[string]interface{}{
					"chain_id":   chainID,
					"alert_type": alertTypeDrop,
					"previous":   last.GasPriceAvg.String(),
					"current":    current.GasPriceAvg.String(),
					"change_pct": ratio * 100,
					"message":    "Gas price drop detected",
				},
			))
			logger.Info("gas price drop detected",
				zap.Int64("chain_id", chainID),
				zap.Float64("change_pct", ratio*100))
		}

		if current.BaseFeeGwei > thresholds[alertTypeHighGas] {
			s.eventBus.Publish(ctx, eventbus.NewEvent(
				eventbus.EventTypeGasPriceAlert,
				"gas_estimator",
				map[string]interface{}{
					"chain_id":     chainID,
					"alert_type":   alertTypeHighGas,
					"base_fee_gwei": current.BaseFeeGwei,
					"threshold":    thresholds[alertTypeHighGas],
					"message":      "High gas price environment",
				},
			))
		}
	}()
}

func (s *gasEstimatorService) publishGasPriceUpdatedAsync(
	ctx context.Context,
	chainID int64,
	baseFee, gasPriceAvg *big.Int,
	trend string,
	confidence float64,
) {
	go func() {
		s.eventBus.Publish(ctx, eventbus.NewEvent(
			eventbus.EventTypeGasPriceUpdated,
			"gas_estimator",
			map[string]interface{}{
				"chain_id":      chainID,
				"base_fee":      baseFee.String(),
				"gas_price_avg": gasPriceAvg.String(),
				"trend":         trend,
				"confidence":    confidence,
			},
		))
	}()
}

func (s *gasEstimatorService) GetHistoricalStats(ctx context.Context, chainID int64, timeWindow string) (*domain.HistoricalGasStats, error) {
	return s.repo.GetHistoricalStats(ctx, chainID, timeWindow)
}

func (s *gasEstimatorService) CollectBlockData(ctx context.Context, chainID int64) error {
	blockNumber, err := s.chain.GetLatestBlockNumber(ctx)
	if err != nil {
		return errors.Internal("failed to get latest block", err)
	}

	baseFee, _ := s.chain.GetBaseFeePerGas(ctx)
	priorityFee, _ := s.chain.GetMaxPriorityFeePerGas(ctx)

	if baseFee == nil {
		baseFee = bigIntZero
	}
	if priorityFee == nil {
		priorityFee = bigIntZero
	}

	data := &domain.GasPriceData{
		ID:             utils.GenerateID("gas"),
		ChainID:        chainID,
		BlockNumber:    blockNumber,
		BaseFee:        baseFee.String(),
		PriorityFee:    priorityFee.String(),
		GasUtilization: 0.5,
		NumTxs:         0,
		AvgGasPrice:    baseFee.String(),
		Timestamp:      time.Now(),
	}

	if err := s.repo.StoreGasData(ctx, data); err != nil {
		return err
	}

	go func() {
		s.eventBus.Publish(ctx, eventbus.NewEvent(
			eventbus.EventTypeBlockIndexed,
			"gas_estimator",
			map[string]interface{}{
				"chain_id":     chainID,
				"block_number": blockNumber,
				"base_fee":     baseFee.String(),
				"priority_fee": priorityFee.String(),
			},
		))
	}()

	return nil
}

func (s *gasEstimatorService) PredictNextBaseFee(ctx context.Context, chainID int64) (*big.Int, float64) {
	historicalData, err := s.repo.GetLatestGasData(ctx, chainID, defaultPredictionLimit)
	if err != nil || len(historicalData) < defaultMinDataPoints {
		return big.NewInt(1000000000), 0.5
	}

	baseFees, _ := s.parseHistoricalData(historicalData)
	if len(baseFees) < defaultMinDataPoints {
		return big.NewInt(1000000000), 0.5
	}

	return s.predictNextBaseFeeFromData(baseFees)
}

func (s *gasEstimatorService) predictNextBaseFeeFromData(baseFees []*big.Int) (*big.Int, float64) {
	if len(baseFees) < defaultMinDataPoints {
		return big.NewInt(1000000000), 0.5
	}

	current := baseFees[0]
	avgChange := s.calculateAvgChange(baseFees)

	predicted := new(big.Int).Add(current, avgChange)
	if predicted.Sign() < 0 {
		predicted = big.NewInt(100000000)
	}

	volatility := calculateVolatility(baseFees)
	confidence := 1.0 - volatility
	if confidence < 0.3 {
		confidence = 0.3
	}

	return predicted, confidence
}

func (s *gasEstimatorService) calculateAvgChange(baseFees []*big.Int) *big.Int {
	count := min(10, len(baseFees)-1)
	if count <= 0 {
		return bigIntZero
	}

	avgChange := big.NewInt(0)
	for i := 0; i < count; i++ {
		diff := new(big.Int).Sub(baseFees[i], baseFees[i+1])
		avgChange.Add(avgChange, diff)
	}
	avgChange.Div(avgChange, big.NewInt(int64(count)))
	return avgChange
}

func (s *gasEstimatorService) CalculateOptimalGasPrice(ctx context.Context, chainID int64, urgency string) (*big.Int, *big.Int, error) {
	estimate, err := s.GetCurrentGasPrice(ctx, chainID)
	if err != nil {
		return nil, nil, err
	}

	switch urgency {
	case "low":
		return estimate.GasPriceLow, estimate.PriorityFeeLow, nil
	case "high":
		return estimate.GasPriceHigh, estimate.PriorityFeeHigh, nil
	default:
		return estimate.GasPriceAvg, estimate.PriorityFeeAvg, nil
	}
}

func (s *gasEstimatorService) SubscribeToGasUpdates(ctx context.Context, handler eventbus.EventHandler) string {
	return s.eventBus.Subscribe(eventbus.EventTypeGasPriceUpdated, handler, true)
}

func (s *gasEstimatorService) SubscribeToGasAlerts(ctx context.Context, handler eventbus.EventHandler) string {
	return s.eventBus.Subscribe(eventbus.EventTypeGasPriceAlert, handler, true)
}

func (s *gasEstimatorService) RegisterNotificationChannel(channel *eventbus.NotificationChannel) {
	s.eventBus.RegisterChannel(channel)
}

func (s *gasEstimatorService) SetAlertThreshold(alertType string, threshold float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.alertThresholds[alertType] = threshold
}

func (s *gasEstimatorService) GetEventBus() *eventbus.EventBus {
	return s.eventBus
}

func (s *gasEstimatorService) storeLastEstimate(chainID int64, estimate *domain.GasEstimate) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lastEstimate[chainID] = estimate
}

func (s *gasEstimatorService) getLastEstimate(chainID int64) *domain.GasEstimate {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastEstimate[chainID]
}

func (s *gasEstimatorService) getAlertThresholds() map[string]float64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	thresholds := make(map[string]float64, len(s.alertThresholds))
	for k, v := range s.alertThresholds {
		thresholds[k] = v
	}
	return thresholds
}

func (s *gasEstimatorService) parseHistoricalData(data []domain.GasPriceData) ([]*big.Int, []*big.Int) {
	baseFees := make([]*big.Int, 0, len(data))
	priorityFees := make([]*big.Int, 0, len(data))

	for _, d := range data {
		bf, ok := new(big.Int).SetString(d.BaseFee, 10)
		if ok && bf != nil {
			baseFees = append(baseFees, bf)
		}

		pf, ok := new(big.Int).SetString(d.PriorityFee, 10)
		if ok && pf != nil {
			priorityFees = append(priorityFees, pf)
		}
	}

	return baseFees, priorityFees
}

func (s *gasEstimatorService) createDefaultEstimate(chainID int64, baseFee, priorityFee *big.Int) *domain.GasEstimate {
	priorityLow := scaleBigInt(priorityFee, 80)
	priorityHigh := scaleBigInt(priorityFee, 150)

	gasPriceLow := new(big.Int).Add(baseFee, priorityLow)
	gasPriceAvg := new(big.Int).Add(baseFee, priorityFee)
	gasPriceHigh := new(big.Int).Add(baseFee, priorityHigh)

	baseFeeGwei, _ := new(big.Float).Quo(new(big.Float).SetInt(baseFee), weiToGwei).Float64()

	return &domain.GasEstimate{
		ChainID:         chainID,
		BaseFee:         baseFee,
		BaseFeeGwei:     baseFeeGwei,
		PriorityFeeLow:  priorityLow,
		PriorityFeeAvg:  priorityFee,
		PriorityFeeHigh: priorityHigh,
		GasPriceLow:     gasPriceLow,
		GasPriceAvg:     gasPriceAvg,
		GasPriceHigh:    gasPriceHigh,
		MaxFeePerGas:    gasPriceHigh,
		EstimatedAt:     time.Now(),
		Confidence:      0.7,
		Trend:           domain.TrendUnknown,
	}
}

func scaleBigInt(value *big.Int, percent int64) *big.Int {
	result := new(big.Int).Mul(value, big.NewInt(percent))
	return result.Div(result, bigIntHundred)
}

func calculateAverage(values []*big.Int) *big.Int {
	if len(values) == 0 {
		return bigIntZero
	}
	sum := big.NewInt(0)
	for _, v := range values {
		sum.Add(sum, v)
	}
	return sum.Div(sum, big.NewInt(int64(len(values))))
}

func analyzeTrend(values []*big.Int) string {
	if len(values) < 3 {
		return domain.TrendUnknown
	}

	recent := values[:min(10, len(values))]
	mid := len(recent) / 2
	if mid == 0 {
		return domain.TrendUnknown
	}

	firstHalf := recent[:mid]
	secondHalf := recent[mid:]

	avgFirst := calculateAverage(firstHalf)
	avgSecond := calculateAverage(secondHalf)

	diff := new(big.Int).Sub(avgSecond, avgFirst)
	threshold := new(big.Int).Div(avgFirst, big.NewInt(50))

	if diff.Cmp(threshold) > 0 {
		return domain.TrendUp
	} else if diff.Cmp(new(big.Int).Neg(threshold)) < 0 {
		return domain.TrendDown
	}
	return domain.TrendStable
}

func calculateConfidence(values []*big.Int) float64 {
	volatility := calculateVolatility(values)
	confidence := 1.0 - volatility*2
	switch {
	case confidence < 0.3:
		return 0.3
	case confidence > 0.98:
		return 0.98
	default:
		return confidence
	}
}

func calculateVolatility(values []*big.Int) float64 {
	if len(values) < 2 {
		return 0.1
	}

	avg := calculateAverage(values)
	if avg.Cmp(bigIntZero) == 0 {
		return 0.1
	}

	varianceSum := big.NewInt(0)
	for _, v := range values {
		diff := new(big.Int).Sub(v, avg)
		varianceSum.Add(varianceSum, new(big.Int).Mul(diff, diff))
	}
	variance := new(big.Int).Div(varianceSum, big.NewInt(int64(len(values))))
	stdDev := new(big.Int).Sqrt(variance)

	ratio := new(big.Rat).SetFrac(stdDev, avg)
	volatility, _ := ratio.Float64()
	return volatility
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}
