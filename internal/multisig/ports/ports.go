package ports

import (
	"context"
	"github.com/solocoder/session147/internal/common/routing"
	"github.com/solocoder/session147/internal/multisig/domain"
)

type WalletRepository interface {
	CreateWallet(ctx context.Context, wallet *domain.Wallet) error
	GetWallet(ctx context.Context, id string) (*domain.Wallet, error)
	GetWalletByAddress(ctx context.Context, address string) (*domain.Wallet, error)
	ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Wallet, int64, error)
	UpdateWallet(ctx context.Context, wallet *domain.Wallet) error
	DeleteWallet(ctx context.Context, id string) error
	IncrementNonce(ctx context.Context, walletID string) error
}

type ProposalRepository interface {
	CreateProposal(ctx context.Context, proposal *domain.Proposal) error
	GetProposal(ctx context.Context, id string) (*domain.Proposal, error)
	ListProposals(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Proposal, int64, error)
	UpdateProposal(ctx context.Context, proposal *domain.Proposal) error
	DeleteProposal(ctx context.Context, id string) error
	AddSignature(ctx context.Context, proposalID string, signature domain.Signature) error
	GetPendingProposals(ctx context.Context, walletID string) ([]domain.Proposal, error)
}

type MultisigService interface {
	CreateWallet(ctx context.Context, wallet *domain.Wallet) (*domain.Wallet, error)
	GetWallet(ctx context.Context, id string) (*domain.Wallet, error)
	ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Wallet, int64, error)
	UpdateWallet(ctx context.Context, wallet *domain.Wallet) (*domain.Wallet, error)
	AddSigner(ctx context.Context, walletID string, signer domain.Signer) error
	RemoveSigner(ctx context.Context, walletID string, address string) error

	CreateProposal(ctx context.Context, req *domain.CreateProposalRequest, createdBy string) (*domain.Proposal, error)
	GetProposal(ctx context.Context, id string) (*domain.Proposal, error)
	ListProposals(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Proposal, int64, error)
	SignProposal(ctx context.Context, req *domain.SignProposalRequest) (*domain.Proposal, error)
	ExecuteProposal(ctx context.Context, req *domain.ExecuteProposalRequest) (string, error)
	CancelProposal(ctx context.Context, proposalID string) error

	VerifySignature(ctx context.Context, proposal *domain.Proposal, signature domain.Signature) (bool, error)
	CheckThreshold(ctx context.Context, proposal *domain.Proposal) (bool, int, error)

	SetReadWriteMode(mode routing.ReadWriteMode)
	GetRouter() *routing.ReadWriteRouter
}

type ChainAdapter interface {
	GetNonce(ctx context.Context, address string) (uint64, error)
	SendTransaction(ctx context.Context, signedTx []byte) (string, error)
	EstimateGas(ctx context.Context, to string, data []byte, value string) (uint64, error)
}
