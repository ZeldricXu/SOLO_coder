package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/multisig/domain"
	"github.com/solocoder/session147/internal/multisig/ports"
	"gorm.io/gorm"
)

type gormWalletRepo struct {
	db *gorm.DB
}

func NewGormWalletRepository(db *gorm.DB) ports.WalletRepository {
	return &gormWalletRepo{db: db}
}

func (r *gormWalletRepo) CreateWallet(ctx context.Context, wallet *domain.Wallet) error {
	return r.db.WithContext(ctx).Create(wallet).Error
}

func (r *gormWalletRepo) GetWallet(ctx context.Context, id string) (*domain.Wallet, error) {
	var wallet domain.Wallet
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&wallet).Error
	if err != nil {
		return nil, err
	}
	return &wallet, nil
}

func (r *gormWalletRepo) GetWalletByAddress(ctx context.Context, address string) (*domain.Wallet, error) {
	var wallet domain.Wallet
	err := r.db.WithContext(ctx).Where("address = ?", address).First(&wallet).Error
	if err != nil {
		return nil, err
	}
	return &wallet, nil
}

func (r *gormWalletRepo) ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Wallet, int64, error) {
	var wallets []domain.Wallet
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.Wallet{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&wallets).Error
	return wallets, total, err
}

func (r *gormWalletRepo) UpdateWallet(ctx context.Context, wallet *domain.Wallet) error {
	return r.db.WithContext(ctx).Save(wallet).Error
}

func (r *gormWalletRepo) DeleteWallet(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&domain.Wallet{}, "id = ?", id).Error
}

func (r *gormWalletRepo) IncrementNonce(ctx context.Context, walletID string) error {
	return r.db.WithContext(ctx).Model(&domain.Wallet{}).Where("id = ?", walletID).
		UpdateColumn("nonce", gorm.Expr("nonce + 1")).Error
}

type gormProposalRepo struct {
	db *gorm.DB
}

func NewGormProposalRepository(db *gorm.DB) ports.ProposalRepository {
	return &gormProposalRepo{db: db}
}

func (r *gormProposalRepo) CreateProposal(ctx context.Context, proposal *domain.Proposal) error {
	return r.db.WithContext(ctx).Create(proposal).Error
}

func (r *gormProposalRepo) GetProposal(ctx context.Context, id string) (*domain.Proposal, error) {
	var proposal domain.Proposal
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&proposal).Error
	if err != nil {
		return nil, err
	}
	return &proposal, nil
}

func (r *gormProposalRepo) ListProposals(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Proposal, int64, error) {
	var proposals []domain.Proposal
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.Proposal{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&proposals).Error
	return proposals, total, err
}

func (r *gormProposalRepo) UpdateProposal(ctx context.Context, proposal *domain.Proposal) error {
	return r.db.WithContext(ctx).Save(proposal).Error
}

func (r *gormProposalRepo) DeleteProposal(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&domain.Proposal{}, "id = ?", id).Error
}

func (r *gormProposalRepo) AddSignature(ctx context.Context, proposalID string, signature domain.Signature) error {
	return r.db.WithContext(ctx).Model(&domain.Proposal{}).Where("id = ?", proposalID).
		UpdateColumn("signatures", gorm.Expr("signatures || ?::jsonb", signature)).Error
}

func (r *gormProposalRepo) GetPendingProposals(ctx context.Context, walletID string) ([]domain.Proposal, error) {
	var proposals []domain.Proposal
	err := r.db.WithContext(ctx).Where("wallet_id = ? AND status = ?", walletID, domain.ProposalStatusPending).
		Order("created_at DESC").Find(&proposals).Error
	return proposals, err
}
