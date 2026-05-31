package gas

import (
	"context"
	"encoding/json"
	"math/big"
	"math/rand"
	"sort"
	"sync"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

type EstimateRequest struct {
	ChainID         string `json:"chain_id"`
	ContractAddress string `json:"contract_address"`
	MethodSig       string `json:"method_sig"`
	Data            []byte `json:"data"`
}

type EstimateResponse struct {
	ID              string    `json:"id"`
	ChainID         string    `json:"chain_id"`
	ContractAddress string    `json:"contract_address"`
	MethodSig       string    `json:"method_sig"`
	EstimatedGas    uint64    `json:"estimated_gas"`
	GasPriceLow     string    `json:"gas_price_low"`
	GasPriceAvg     string    `json:"gas_price_avg"`
	GasPriceHigh    string    `json:"gas_price_high"`
	PriorityFeeLow  string    `json:"priority_fee_low"`
	PriorityFeeAvg  string    `json:"priority_fee_avg"`
	PriorityFeeHigh string    `json:"priority_fee_high"`
	Confidence      float64   `json:"confidence"`
	CreatedAt       time.Time `json:"created_at"`
}

type HistoricalGasData struct {
	BlockNumber uint64 `json:"block_number"`
	GasUsed     uint64 `json:"gas_used"`
	GasLimit    uint64 `json:"gas_limit"`
	BaseFee     string `json:"base_fee"`
	PriorityFee string `json:"priority_fee"`
	Timestamp   int64  `json:"timestamp"`
}

type NetworkStatus struct {
	PendingTxCount    int     `json:"pending_tx_count"`
	BlockTime         float64 `json:"block_time"`
	GasUtilization    float64 `json:"gas_utilization"`
	BaseFeeTrend      string  `json:"base_fee_trend"`
	CongestionLevel   string  `json:"congestion_level"`
}

type Service struct {
	estimateRepo  repository.GasEstimateRepository
	chainService  interface {
		GetBlockNumber(ctx context.Context, chainID string) (uint64, error)
		GetBaseFee(ctx context.Context, chainID string) (string, error)
		GetPendingTxCount(ctx context.Context, chainID string) (int, error)
	}
	historicalData map[string][]*HistoricalGasData
	dataMu         sync.RWMutex
}

func NewService(estimateRepo repository.GasEstimateRepository) *Service {
	return &Service{
		estimateRepo:   estimateRepo,
		historicalData: make(map[string][]*HistoricalGasData),
	}
}

func (s *Service) SetChainService(cs interface{}) { s.chainService = cs }

func (s *Service) Estimate(ctx context.Context, chainID, contract, method string, data []byte) (uint64, error) {
	baseGas := uint64(21000)
	if len(data) > 0 {
		baseGas += uint64(len(data)) * 68
	}
	if contract != "" {
		baseGas += 50000
	}
	return baseGas, nil
}

func (s *Service) EstimateDetailed(ctx context.Context, req *EstimateRequest) (*EstimateResponse, error) {
	estimatedGas, _ := s.Estimate(ctx, req.ChainID, req.ContractAddress, req.MethodSig, req.Data)
	prices := s.analyzeGasPrices(req.ChainID)
	networkStatus := s.getNetworkStatus(req.ChainID)
	confidence := s.calculateConfidence(req.ChainID, req.ContractAddress, req.MethodSig)

	statusBytes, _ := json.Marshal(networkStatus)
	historicalBytes, _ := json.Marshal(s.getHistoricalData(req.ChainID, 100))

	estimate := &model.GasEstimate{
		ID:              common.GenerateID("gas"),
		ChainID:         req.ChainID,
		ContractAddress: req.ContractAddress,
		MethodSig:       req.MethodSig,
		EstimatedGas:    estimatedGas,
		GasPriceLow:     prices["low"],
		GasPriceAvg:     prices["avg"],
		GasPriceHigh:    prices["high"],
		PriorityFeeLow:  prices["priority_low"],
		PriorityFeeAvg:  prices["priority_avg"],
		PriorityFeeHigh: prices["priority_high"],
		Confidence:      confidence,
		HistoricalData:  historicalBytes,
		NetworkStatus:   statusBytes,
		CreatedAt:       time.Now(),
	}

	if err := s.estimateRepo.Create(ctx, estimate); err != nil {
		logger.L().Error("failed to save gas estimate", zap.Error(err))
	}

	return &EstimateResponse{
		ID:              estimate.ID,
		ChainID:         estimate.ChainID,
		ContractAddress: estimate.ContractAddress,
		MethodSig:       estimate.MethodSig,
		EstimatedGas:    estimate.EstimatedGas,
		GasPriceLow:     estimate.GasPriceLow,
		GasPriceAvg:     estimate.GasPriceAvg,
		GasPriceHigh:    estimate.GasPriceHigh,
		PriorityFeeLow:  estimate.PriorityFeeLow,
		PriorityFeeAvg:  estimate.PriorityFeeAvg,
		PriorityFeeHigh: estimate.PriorityFeeHigh,
		Confidence:      estimate.Confidence,
		CreatedAt:       estimate.CreatedAt,
	}, nil
}

func (s *Service) GetLatestPrices(ctx context.Context, chainID string) (map[string]string, error) {
	prices := s.analyzeGasPrices(chainID)
	return prices, nil
}

func (s *Service) analyzeGasPrices(chainID string) map[string]string {
	s.dataMu.RLock()
	data := s.historicalData[chainID]
	s.dataMu.RUnlock()

	if len(data) == 0 {
		base := big.NewInt(30000000000)
		return map[string]string{
			"low":          new(big.Int).Mul(base, big.NewInt(80)).Div(base, big.NewInt(100)).String(),
			"avg":          base.String(),
			"high":         new(big.Int).Mul(base, big.NewInt(150)).Div(base, big.NewInt(100)).String(),
			"max_fee":      new(big.Int).Mul(base, big.NewInt(200)).Div(base, big.NewInt(100)).String(),
			"priority_low": "1000000000",
			"priority_avg": "2000000000",
			"priority_high": "5000000000",
		}
	}

	fees := make([]*big.Int, 0, len(data))
	for _, d := range data {
		bf, _ := new(big.Int).SetString(d.BaseFee, 10)
		fees = append(fees, bf)
	}

	sort.Slice(fees, func(i, j int) bool { return fees[i].Cmp(fees[j]) < 0 })

	low := fees[0]
	avg := fees[len(fees)/2]
	high := fees[len(fees)-1]

	return map[string]string{
		"low":          low.String(),
		"avg":          avg.String(),
		"high":         high.String(),
		"max_fee":      new(big.Int).Mul(high, big.NewInt(2)).String(),
		"priority_low": "1000000000",
		"priority_avg": "2000000000",
		"priority_high": "5000000000",
	}
}

func (s *Service) getNetworkStatus(chainID string) *NetworkStatus {
	pendingCount := rand.Intn(500)
	utilization := 0.5 + rand.Float64()*0.4

	level := "low"
	trend := "stable"
	if pendingCount > 300 {
		level = "high"
		trend = "increasing"
	} else if pendingCount > 100 {
		level = "medium"
	}

	return &NetworkStatus{
		PendingTxCount:  pendingCount,
		BlockTime:       12.0 + rand.Float64()*4,
		GasUtilization:  utilization,
		BaseFeeTrend:    trend,
		CongestionLevel: level,
	}
}

func (s *Service) calculateConfidence(chainID, contract, method string) float64 {
	s.dataMu.RLock()
	defer s.dataMu.RUnlock()

	if data, ok := s.historicalData[chainID]; ok && len(data) > 50 {
		return 0.95
	}
	return 0.75
}

func (s *Service) getHistoricalData(chainID string, limit int) []*HistoricalGasData {
	s.dataMu.RLock()
	defer s.dataMu.RUnlock()

	data := s.historicalData[chainID]
	if len(data) > limit {
		return data[len(data)-limit:]
	}
	return data
}

func (s *Service) CollectHistoricalData(ctx context.Context, chainID string) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			hd := &HistoricalGasData{
				BlockNumber: uint64(time.Now().Unix()),
				GasUsed:     15000000 + rand.Uint64()%5000000,
				GasLimit:    30000000,
				BaseFee:     big.NewInt(25000000000 + rand.Int63n(10000000000)).String(),
				PriorityFee: big.NewInt(1000000000 + rand.Int63n(3000000000)).String(),
				Timestamp:   time.Now().Unix(),
			}

			s.dataMu.Lock()
			s.historicalData[chainID] = append(s.historicalData[chainID], hd)
			if len(s.historicalData[chainID]) > 1000 {
				s.historicalData[chainID] = s.historicalData[chainID][len(s.historicalData[chainID])-1000:]
			}
			s.dataMu.Unlock()
		}
	}
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.GasEstimate, error) {
	est, err := s.estimateRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("gas estimate", id)
	}
	return est, nil
}
