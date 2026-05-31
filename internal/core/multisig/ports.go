package multisig

import (
	"context"
	"math/big"
	"time"
)

type ProposalStatus string

const (
	ProposalStatusPending   ProposalStatus = "pending"
	ProposalStatusApproved  ProposalStatus = "approved"
	ProposalStatusRejected  ProposalStatus = "rejected"
	ProposalStatusExecuted  ProposalStatus = "executed"
	ProposalStatusFailed    ProposalStatus = "failed"
	ProposalStatusCancelled ProposalStatus = "cancelled"
)

type Signature struct {
	Signer    string
	Signature string
	Timestamp time.Time
}

type MultisigWallet struct {
	Address      string
	ChainID      uint64
	Signers      []string
	Threshold    int
	Version      int
	Nonce        *big.Int
	LastUsedAt   time.Time
}

type Proposal struct {
	ProposalID    string
	WalletAddress string
	ChainID       uint64
	Nonce         *big.Int
	To            string
	Value         *big.Int
	Data          string
	Description   string
	Status        ProposalStatus
	Signatures    []Signature
	CreatedAt     time.Time
	ExecutedAt    *time.Time
	ExpiresAt     *time.Time
	TxHash        string
}

type ProposalRequest struct {
	TraceID     string
	WalletAddress string
	ChainID     uint64
	To          string
	Value       *big.Int
	Data        string
	Description string
	ExpiresAt   *time.Time
	Creator     string
}

type ProposalResult struct {
	ProposalID string
	Status     ProposalStatus
	Nonce      *big.Int
	TxHash     string
	Error      string
}

type NonceManager interface {
	GetNextNonce(ctx context.Context, walletAddress string, chainID uint64) (*big.Int, error)
	ConsumeNonce(ctx context.Context, walletAddress string, chainID uint64, nonce *big.Int) error
	ReleaseNonce(ctx context.Context, walletAddress string, chainID uint64, nonce *big.Int) error
	GetCurrentNonce(ctx context.Context, walletAddress string, chainID uint64) (*big.Int, error)
}

type SignatureCollector interface {
	AddSignature(ctx context.Context, proposalID string, signer string, signature string) (*Proposal, error)
	ValidateSignature(ctx context.Context, proposal *Proposal, signer string, signature string) (bool, error)
	HasSigned(proposal *Proposal, signer string) bool
	IsThresholdReached(proposal *Proposal, threshold int) bool
}

type ProposalRepository interface {
	CreateProposal(ctx context.Context, req *ProposalRequest, nonce *big.Int) (proposalID string, err error)
	GetProposal(ctx context.Context, proposalID string) (*Proposal, error)
	UpdateProposalStatus(ctx context.Context, proposalID string, status ProposalStatus, txHash string) error
	AddProposalSignature(ctx context.Context, proposalID string, signature *Signature) error
	ListProposals(ctx context.Context, walletAddress string, status ProposalStatus) ([]*Proposal, error)
	GetActiveProposalCount(ctx context.Context, walletAddress string, chainID uint64) (int, error)
	GetWallet(ctx context.Context, walletAddress string, chainID uint64) (*MultisigWallet, error)
}

type TransactionExecutor interface {
	ExecuteTransaction(ctx context.Context, proposal *Proposal, wallet *MultisigWallet) (txHash string, err error)
	EstimateGas(ctx context.Context, proposal *Proposal) (*big.Int, error)
}

type MultisigCoordinator interface {
	CreateProposal(ctx context.Context, req *ProposalRequest) (*ProposalResult, error)
	ApproveProposal(ctx context.Context, proposalID string, signer string, signature string) (*ProposalResult, error)
	RejectProposal(ctx context.Context, proposalID string, signer string) (*ProposalResult, error)
	ExecuteProposal(ctx context.Context, proposalID string) (*ProposalResult, error)
	CancelProposal(ctx context.Context, proposalID string, signer string) (*ProposalResult, error)
	GetProposal(ctx context.Context, proposalID string) (*Proposal, error)
	ListProposals(ctx context.Context, walletAddress string, status ProposalStatus) ([]*Proposal, error)
}
