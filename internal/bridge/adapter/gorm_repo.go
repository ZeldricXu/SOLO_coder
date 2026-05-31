package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/bridge/domain"
	"github.com/solocoder/session147/internal/bridge/ports"
	"gorm.io/gorm"
)

type gormBridgeRepo struct {
	db *gorm.DB
}

func NewGormBridgeRepository(db *gorm.DB) ports.BridgeRepository {
	return &gormBridgeRepo{db: db}
}

func (r *gormBridgeRepo) CreateTransaction(ctx context.Context, tx *domain.BridgeTransaction) error {
	return r.db.WithContext(ctx).Create(tx).Error
}

func (r *gormBridgeRepo) GetTransaction(ctx context.Context, id string) (*domain.BridgeTransaction, error) {
	var tx domain.BridgeTransaction
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (r *gormBridgeRepo) GetTransactionByBridgeTxID(ctx context.Context, bridgeTxID string) (*domain.BridgeTransaction, error) {
	var tx domain.BridgeTransaction
	err := r.db.WithContext(ctx).Where("bridge_tx_id = ?", bridgeTxID).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (r *gormBridgeRepo) GetTransactionBySourceHash(ctx context.Context, txHash string) (*domain.BridgeTransaction, error) {
	var tx domain.BridgeTransaction
	err := r.db.WithContext(ctx).Where("source_tx_hash = ?", txHash).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (r *gormBridgeRepo) ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.BridgeTransaction, int64, error) {
	var txs []domain.BridgeTransaction
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.BridgeTransaction{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&txs).Error
	return txs, total, err
}

func (r *gormBridgeRepo) UpdateTransaction(ctx context.Context, tx *domain.BridgeTransaction) error {
	return r.db.WithContext(ctx).Save(tx).Error
}

func (r *gormBridgeRepo) AddSignature(ctx context.Context, txID string, signature string) error {
	return r.db.WithContext(ctx).Model(&domain.BridgeTransaction{}).Where("id = ?", txID).
		UpdateColumn("signatures", gorm.Expr("signatures || ?::jsonb", signature)).Error
}

func (r *gormBridgeRepo) GetPendingTransactions(ctx context.Context, sourceChainID int64, minConfirmations int) ([]domain.BridgeTransaction, error) {
	var txs []domain.BridgeTransaction
	err := r.db.WithContext(ctx).
		Where("source_chain_id = ? AND status = ? AND confirmations >= ?",
			sourceChainID, domain.BridgeStatusLocked, minConfirmations).
		Order("created_at ASC").Find(&txs).Error
	return txs, err
}

func (r *gormBridgeRepo) GetTransactionsToRelay(ctx context.Context, destChainID int64) ([]domain.BridgeTransaction, error) {
	var txs []domain.BridgeTransaction
	err := r.db.WithContext(ctx).
		Where("dest_chain_id = ? AND status = ?", destChainID, domain.BridgeStatusConfirmed).
		Order("created_at ASC").Find(&txs).Error
	return txs, err
}
