package ports

import (
	"context"
	"github.com/solocoder/session147/internal/bridge/domain"
)

type BridgeRepository interface {
	CreateTransaction(ctx context.Context, tx *domain.BridgeTransaction) error
	GetTransaction(ctx context.Context, id string) (*domain.BridgeTransaction, error)
	GetTransactionByBridgeTxID(ctx context.Context, bridgeTxID string) (*domain.BridgeTransaction, error)
	GetTransactionBySourceHash(ctx context.Context, txHash string) (*domain.BridgeTransaction, error)
	ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.BridgeTransaction, int64, error)
	UpdateTransaction(ctx context.Context, tx *domain.BridgeTransaction) error
	AddSignature(ctx context.Context, txID string, signature string) error

	GetPendingTransactions(ctx context.Context, sourceChainID int64, minConfirmations int) ([]domain.BridgeTransaction, error)
	GetTransactionsToRelay(ctx context.Context, destChainID int64) ([]domain.BridgeTransaction, error)
}

type BridgeService interface {
	InitiateBridge(ctx context.Context, req *domain.BridgeRequest) (*domain.BridgeTransaction, error)
	ConfirmLock(ctx context.Context, bridgeTxID string, sourceTxHash string, blockNumber uint64) error
	VerifyProof(ctx context.Context, proof *domain.BridgeProof) (bool, error)
	MintTokens(ctx context.Context, bridgeTxID string) (string, error)
	CompleteBridge(ctx context.Context, bridgeTxID string, destTxHash string) error
	RefundTransaction(ctx context.Context, bridgeTxID string) error
	RetryTransaction(ctx context.Context, bridgeTxID string) error

	GetTransaction(ctx context.Context, id string) (*domain.BridgeTransaction, error)
	ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.BridgeTransaction, int64, error)
	ProcessPendingTransactions(ctx context.Context) error
}

type MessageVerifier interface {
	VerifyMessage(ctx context.Context, message []byte, signatures []string) (bool, error)
}

type AtomicSwapHandler interface {
	InitiateSwap(ctx context.Context, sourceChain, destChain int64, amount string, secretHash string, timeout time.Time) (string, error)
	RedeemSwap(ctx context.Context, chainID int64, swapID string, secret string) (string, error)
	RefundSwap(ctx context.Context, chainID int64, swapID string) (string, error)
}
