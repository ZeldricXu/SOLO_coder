package ports

import (
	"context"
	"github.com/solocoder/session147/internal/indexer/domain"
)

type IndexRepository interface {
	StoreBlock(ctx context.Context, block *domain.BlockIndex) error
	GetBlock(ctx context.Context, chainID int64, blockNumber uint64) (*domain.BlockIndex, error)
	GetBlockByHash(ctx context.Context, hash string) (*domain.BlockIndex, error)
	ListBlocks(ctx context.Context, chainID int64, startBlock, endBlock uint64, limit int) ([]domain.BlockIndex, error)
	GetLatestIndexedBlock(ctx context.Context, chainID int64) (uint64, error)

	StoreTransaction(ctx context.Context, tx *domain.TransactionIndex) error
	GetTransaction(ctx context.Context, txHash string) (*domain.TransactionIndex, error)
	ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.TransactionIndex, int64, error)
	GetTransactionsByAddress(ctx context.Context, chainID int64, address string, limit int) ([]domain.TransactionIndex, error)

	StoreLog(ctx context.Context, log *domain.LogIndex) error
	GetLogs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.LogIndex, int64, error)
	GetLogsByAddress(ctx context.Context, chainID int64, address string, fromBlock, toBlock uint64, limit int) ([]domain.LogIndex, error)

	UpdateIndexStatus(ctx context.Context, status *domain.IndexStatus) error
	GetIndexStatus(ctx context.Context, chainID int64) (*domain.IndexStatus, error)
}

type IndexerService interface {
	Start(ctx context.Context, config *domain.IndexConfig) error
	Stop(ctx context.Context, chainID int64) error
	Pause(ctx context.Context, chainID int64) error
	Resume(ctx context.Context, chainID int64) error
	IndexBlock(ctx context.Context, chainID int64, blockNumber uint64) error
	IndexBlockRange(ctx context.Context, chainID int64, startBlock, endBlock uint64) error

	GetBlock(ctx context.Context, chainID int64, blockNumber uint64) (*domain.BlockIndex, error)
	GetTransaction(ctx context.Context, txHash string) (*domain.TransactionIndex, error)
	ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.TransactionIndex, int64, error)
	GetLogs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.LogIndex, int64, error)
	GetIndexStatus(ctx context.Context, chainID int64) (*domain.IndexStatus, error)
}

type ChainDataFetcher interface {
	GetBlockNumber(ctx context.Context) (uint64, error)
	GetBlockByNumber(ctx context.Context, blockNumber uint64) (interface{}, error)
	GetTransactionReceipt(ctx context.Context, txHash string) (interface{}, error)
	GetLogs(ctx context.Context, fromBlock, toBlock uint64, addresses []string, topics []string) ([]interface{}, error)
}
