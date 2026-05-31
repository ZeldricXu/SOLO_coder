package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/txbuilder/domain"
	"github.com/solocoder/session147/internal/txbuilder/ports"
	"gorm.io/gorm"
)

type gormTxRepo struct {
	db *gorm.DB
}

func NewGormTxRepository(db *gorm.DB) ports.TxRepository {
	return &gormTxRepo{db: db}
}

func (r *gormTxRepo) CreateTx(ctx context.Context, tx *domain.Transaction) error {
	return r.db.WithContext(ctx).Create(tx).Error
}

func (r *gormTxRepo) GetTx(ctx context.Context, id string) (*domain.Transaction, error) {
	var tx domain.Transaction
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (r *gormTxRepo) GetTxByHash(ctx context.Context, hash string) (*domain.Transaction, error) {
	var tx domain.Transaction
	err := r.db.WithContext(ctx).Where("hash = ?", hash).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (r *gormTxRepo) ListTxs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Transaction, int64, error) {
	var txs []domain.Transaction
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.Transaction{})
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

func (r *gormTxRepo) UpdateTx(ctx context.Context, tx *domain.Transaction) error {
	return r.db.WithContext(ctx).Save(tx).Error
}

func (r *gormTxRepo) AddSignature(ctx context.Context, txID string, signature string) error {
	return r.db.WithContext(ctx).Model(&domain.Transaction{}).Where("id = ?", txID).
		UpdateColumn("signatures", gorm.Expr("signatures || ?::jsonb", signature)).Error
}

func (r *gormTxRepo) GetPendingTxs(ctx context.Context, from string) ([]domain.Transaction, error) {
	var txs []domain.Transaction
	err := r.db.WithContext(ctx).Where(`"from" = ? AND status IN (?, ?)`, from, domain.TxStatusPending, domain.TxStatusSigned).
		Order("nonce ASC").Find(&txs).Error
	return txs, err
}
