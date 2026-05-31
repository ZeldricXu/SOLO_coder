package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/gasestimator/domain"
	"github.com/solocoder/session147/internal/gasestimator/ports"
	"gorm.io/gorm"
)

type gormGasDataRepo struct {
	db *gorm.DB
}

func NewGormGasDataRepository(db *gorm.DB) ports.GasDataRepository {
	return &gormGasDataRepo{db: db}
}

func (r *gormGasDataRepo) StoreGasData(ctx context.Context, data *domain.GasPriceData) error {
	return r.db.WithContext(ctx).Create(data).Error
}

func (r *gormGasDataRepo) GetGasData(ctx context.Context, chainID int64, blockNumber uint64) (*domain.GasPriceData, error) {
	var data domain.GasPriceData
	err := r.db.WithContext(ctx).Where("chain_id = ? AND block_number = ?", chainID, blockNumber).First(&data).Error
	if err != nil {
		return nil, err
	}
	return &data, nil
}

func (r *gormGasDataRepo) ListGasData(ctx context.Context, chainID int64, startBlock, endBlock uint64, limit int) ([]domain.GasPriceData, error) {
	var data []domain.GasPriceData
	query := r.db.WithContext(ctx).Where("chain_id = ?", chainID)
	if startBlock > 0 {
		query = query.Where("block_number >= ?", startBlock)
	}
	if endBlock > 0 {
		query = query.Where("block_number <= ?", endBlock)
	}
	err := query.Order("block_number DESC").Limit(limit).Find(&data).Error
	return data, err
}

func (r *gormGasDataRepo) GetLatestGasData(ctx context.Context, chainID int64, limit int) ([]domain.GasPriceData, error) {
	var data []domain.GasPriceData
	err := r.db.WithContext(ctx).Where("chain_id = ?", chainID).
		Order("block_number DESC").Limit(limit).Find(&data).Error
	return data, err
}

func (r *gormGasDataRepo) GetHistoricalStats(ctx context.Context, chainID int64, timeWindow string) (*domain.HistoricalGasStats, error) {
	return &domain.HistoricalGasStats{
		ChainID:    chainID,
		TimeWindow: timeWindow,
	}, nil
}
