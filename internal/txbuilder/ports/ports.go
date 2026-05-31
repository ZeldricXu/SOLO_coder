package ports

import (
	"context"
	"math/big"
	"github.com/solocoder/session147/internal/common/plugin"
	"github.com/solocoder/session147/internal/txbuilder/domain"
)

type TxRepository interface {
	CreateTx(ctx context.Context, tx *domain.Transaction) error
	GetTx(ctx context.Context, id string) (*domain.Transaction, error)
	GetTxByHash(ctx context.Context, hash string) (*domain.Transaction, error)
	ListTxs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Transaction, int64, error)
	UpdateTx(ctx context.Context, tx *domain.Transaction) error
	AddSignature(ctx context.Context, txID string, signature string) error
	GetPendingTxs(ctx context.Context, from string) ([]domain.Transaction, error)
}

type TxBuilderService interface {
	BuildTransaction(ctx context.Context, req *domain.BuildRequest) (*domain.Transaction, error)
	SignTransaction(ctx context.Context, req *domain.SignRequest) (*domain.Transaction, error)
	BroadcastTransaction(ctx context.Context, txID string) (*domain.BroadcastResponse, error)
	GetTransaction(ctx context.Context, id string) (*domain.Transaction, error)
	ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Transaction, int64, error)
	EstimateGas(ctx context.Context, chainID int64, to, data string, value *big.Int) (uint64, error)
	OptimizeGas(ctx context.Context, tx *domain.Transaction, strategy string) error
	CancelTransaction(ctx context.Context, txID string) error
	ReplaceTransaction(ctx context.Context, txID string, newGasPrice string) (*domain.Transaction, error)

	RegisterPlugin(p plugin.Plugin, config map[string]interface{}) error
	UnregisterPlugin(pluginID string) error
	ListPlugins() []plugin.PluginInfo
	EnablePlugin(pluginID string) error
	DisablePlugin(pluginID string) error
	GetPluginManager() *plugin.PluginManager
}

type ChainBroadcaster interface {
	Broadcast(ctx context.Context, rawTx string) (string, error)
	GetTransactionReceipt(ctx context.Context, hash string) (interface{}, error)
	GetNonce(ctx context.Context, address string) (uint64, error)
}

type GasProvider interface {
	GetOptimalGasPrice(ctx context.Context, chainID int64, strategy string) (*big.Int, *big.Int, error)
}
