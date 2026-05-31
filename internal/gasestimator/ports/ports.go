package ports

import (
	"context"
	"math/big"
	"github.com/solocoder/session147/internal/common/eventbus"
	"github.com/solocoder/session147/internal/gasestimator/domain"
)

type GasDataRepository interface {
	StoreGasData(ctx context.Context, data *domain.GasPriceData) error
	GetGasData(ctx context.Context, chainID int64, blockNumber uint64) (*domain.GasPriceData, error)
	ListGasData(ctx context.Context, chainID int64, startBlock, endBlock uint64, limit int) ([]domain.GasPriceData, error)
	GetLatestGasData(ctx context.Context, chainID int64, limit int) ([]domain.GasPriceData, error)
	GetHistoricalStats(ctx context.Context, chainID int64, timeWindow string) (*domain.HistoricalGasStats, error)
}

type GasEstimatorService interface {
	EstimateGas(ctx context.Context, req *domain.EstimateRequest) (*domain.EstimateResponse, error)
	GetCurrentGasPrice(ctx context.Context, chainID int64) (*domain.GasEstimate, error)
	GetHistoricalStats(ctx context.Context, chainID int64, timeWindow string) (*domain.HistoricalGasStats, error)
	CollectBlockData(ctx context.Context, chainID int64) error
	PredictNextBaseFee(ctx context.Context, chainID int64) (*big.Int, float64)
	CalculateOptimalGasPrice(ctx context.Context, chainID int64, urgency string) (*big.Int, *big.Int, error)

	SubscribeToGasUpdates(ctx context.Context, handler eventbus.EventHandler) string
	SubscribeToGasAlerts(ctx context.Context, handler eventbus.EventHandler) string
	RegisterNotificationChannel(channel *eventbus.NotificationChannel)
	SetAlertThreshold(alertType string, threshold float64)
	GetEventBus() *eventbus.EventBus
}

type ChainDataProvider interface {
	GetLatestBlockNumber(ctx context.Context) (uint64, error)
	GetBlockByNumber(ctx context.Context, blockNumber uint64) (interface{}, error)
	GetBaseFeePerGas(ctx context.Context) (*big.Int, error)
	GetMaxPriorityFeePerGas(ctx context.Context) (*big.Int, error)
}
