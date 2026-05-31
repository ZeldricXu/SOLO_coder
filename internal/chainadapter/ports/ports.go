package ports

import (
	"context"
	"math/big"
	"github.com/solocoder/session147/internal/chainadapter/domain"
)

type ChainClient interface {
	GetChainID(ctx context.Context) (int64, error)
	GetBlockNumber(ctx context.Context) (uint64, error)
	GetBlockByNumber(ctx context.Context, blockNumber uint64) (*domain.BlockData, error)
	GetBlockByHash(ctx context.Context, hash string) (*domain.BlockData, error)

	GetBalance(ctx context.Context, address string) (*big.Int, error)
	GetNonce(ctx context.Context, address string) (uint64, error)
	GetTransactionByHash(ctx context.Context, hash string) (*domain.TransactionData, error)
	GetTransactionReceipt(ctx context.Context, hash string) (*domain.TransactionReceipt, error)

	GetBaseFeePerGas(ctx context.Context) (*big.Int, error)
	GetMaxPriorityFeePerGas(ctx context.Context) (*big.Int, error)
	EstimateGas(ctx context.Context, to, data string, value *big.Int) (uint64, error)

	SendRawTransaction(ctx context.Context, rawTx string) (string, error)
	CallContract(ctx context.Context, to, data string) ([]byte, error)

	GetLogs(ctx context.Context, fromBlock, toBlock uint64, addresses []string, topics []string) ([]domain.LogData, error)
	SubscribeNewHeads(ctx context.Context) (<-chan *domain.BlockData, error)
	SubscribeLogs(ctx context.Context, addresses []string, topics []string) (<-chan *domain.LogData, error)
}

type ChainAdapterService interface {
	RegisterChain(config *domain.ChainConfig) error
	GetChain(chainID int64) (ChainClient, error)
	ListChains() []*domain.ChainConfig

	GetBlockNumber(ctx context.Context, chainID int64) (uint64, error)
	GetBlock(ctx context.Context, chainID int64, blockNumber uint64) (*domain.BlockData, error)
	GetBlockByHash(ctx context.Context, chainID int64, hash string) (*domain.BlockData, error)

	GetBalance(ctx context.Context, chainID int64, address string) (*domain.BalanceResponse, error)
	GetTransaction(ctx context.Context, chainID int64, hash string) (*domain.TransactionData, error)
	GetTransactionReceipt(ctx context.Context, chainID int64, hash string) (*domain.TransactionReceipt, error)

	EstimateGas(ctx context.Context, chainID int64, to, data string, value *big.Int) (uint64, error)
	GetGasPrice(ctx context.Context, chainID int64) (*big.Int, *big.Int, *big.Int, error)

	SendTransaction(ctx context.Context, chainID int64, rawTx string) (string, error)
	CallContract(ctx context.Context, chainID int64, to, data string) ([]byte, error)

	GetLogs(ctx context.Context, chainID int64, fromBlock, toBlock uint64, addresses []string, topics []string) ([]domain.LogData, error)
	SubscribeNewHeads(ctx context.Context, chainID int64) (<-chan *domain.BlockData, error)
	SubscribeLogs(ctx context.Context, chainID int64, addresses []string, topics []string) (<-chan *domain.LogData, error)
}
