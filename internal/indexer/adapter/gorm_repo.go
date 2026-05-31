package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/indexer/domain"
	"github.com/solocoder/session147/internal/indexer/ports"
	"gorm.io/gorm"
)

type gormIndexRepo struct {
	db *gorm.DB
}

func NewGormIndexRepository(db *gorm.DB) ports.IndexRepository {
	return &gormIndexRepo{db: db}
}

func (r *gormIndexRepo) StoreBlock(ctx context.Context, block *domain.BlockIndex) error {
	return r.db.WithContext(ctx).Create(block).Error
}

func (r *gormIndexRepo) GetBlock(ctx context.Context, chainID int64, blockNumber uint64) (*domain.BlockIndex, error) {
	var block domain.BlockIndex
	err := r.db.WithContext(ctx).Where("chain_id = ? AND block_number = ?", chainID, blockNumber).First(&block).Error
	if err != nil {
		return nil, err
	}
	return &block, nil
}

func (r *gormIndexRepo) GetBlockByHash(ctx context.Context, hash string) (*domain.BlockIndex, error) {
	var block domain.BlockIndex
	err := r.db.WithContext(ctx).Where("block_hash = ?", hash).First(&block).Error
	if err != nil {
		return nil, err
	}
	return &block, nil
}

func (r *gormIndexRepo) ListBlocks(ctx context.Context, chainID int64, startBlock, endBlock uint64, limit int) ([]domain.BlockIndex, error) {
	var blocks []domain.BlockIndex
	query := r.db.WithContext(ctx).Where("chain_id = ?", chainID)
	if startBlock > 0 {
		query = query.Where("block_number >= ?", startBlock)
	}
	if endBlock > 0 {
		query = query.Where("block_number <= ?", endBlock)
	}
	err := query.Order("block_number DESC").Limit(limit).Find(&blocks).Error
	return blocks, err
}

func (r *gormIndexRepo) GetLatestIndexedBlock(ctx context.Context, chainID int64) (uint64, error) {
	var block domain.BlockIndex
	err := r.db.WithContext(ctx).Where("chain_id = ?", chainID).
		Order("block_number DESC").First(&block).Error
	if err != nil {
		return 0, err
	}
	return block.BlockNumber, nil
}

func (r *gormIndexRepo) StoreTransaction(ctx context.Context, tx *domain.TransactionIndex) error {
	return r.db.WithContext(ctx).Create(tx).Error
}

func (r *gormIndexRepo) GetTransaction(ctx context.Context, txHash string) (*domain.TransactionIndex, error) {
	var tx domain.TransactionIndex
	err := r.db.WithContext(ctx).Where("tx_hash = ?", txHash).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (r *gormIndexRepo) ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.TransactionIndex, int64, error) {
	var txs []domain.TransactionIndex
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.TransactionIndex{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("block_number DESC, tx_index DESC").Find(&txs).Error
	return txs, total, err
}

func (r *gormIndexRepo) GetTransactionsByAddress(ctx context.Context, chainID int64, address string, limit int) ([]domain.TransactionIndex, error) {
	var txs []domain.TransactionIndex
	err := r.db.WithContext(ctx).Where(`chain_id = ? AND ("from" = ? OR "to" = ?)`, chainID, address, address).
		Order("block_number DESC").Limit(limit).Find(&txs).Error
	return txs, err
}

func (r *gormIndexRepo) StoreLog(ctx context.Context, log *domain.LogIndex) error {
	return r.db.WithContext(ctx).Create(log).Error
}

func (r *gormIndexRepo) GetLogs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.LogIndex, int64, error) {
	var logs []domain.LogIndex
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.LogIndex{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("block_number DESC, log_index DESC").Find(&logs).Error
	return logs, total, err
}

func (r *gormIndexRepo) GetLogsByAddress(ctx context.Context, chainID int64, address string, fromBlock, toBlock uint64, limit int) ([]domain.LogIndex, error) {
	var logs []domain.LogIndex
	query := r.db.WithContext(ctx).Where("chain_id = ? AND address = ?", chainID, address)
	if fromBlock > 0 {
		query = query.Where("block_number >= ?", fromBlock)
	}
	if toBlock > 0 {
		query = query.Where("block_number <= ?", toBlock)
	}
	err := query.Order("block_number DESC").Limit(limit).Find(&logs).Error
	return logs, err
}

func (r *gormIndexRepo) UpdateIndexStatus(ctx context.Context, status *domain.IndexStatus) error {
	return nil
}

func (r *gormIndexRepo) GetIndexStatus(ctx context.Context, chainID int64) (*domain.IndexStatus, error) {
	return &domain.IndexStatus{
		ChainID: chainID,
		Status:  domain.IndexStatusStopped,
	}, nil
}
