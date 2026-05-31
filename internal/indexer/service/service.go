package service

import (
	"context"
	"sync"
	"time"

	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/indexer/domain"
	"github.com/solocoder/session147/internal/indexer/ports"
	"go.uber.org/zap"
)

type indexerService struct {
	repo      ports.IndexRepository
	fetcher   ports.ChainDataFetcher
	mu        sync.RWMutex
	statuses  map[int64]*domain.IndexStatus
	stopChans map[int64]chan struct{}
}

func NewIndexerService(repo ports.IndexRepository, fetcher ports.ChainDataFetcher) ports.IndexerService {
	return &indexerService{
		repo:      repo,
		fetcher:   fetcher,
		statuses:  make(map[int64]*domain.IndexStatus),
		stopChans: make(map[int64]chan struct{}),
	}
}

func (s *indexerService) Start(ctx context.Context, config *domain.IndexConfig) error {
	logger.Info("starting indexer", zap.Int64("chain_id", config.ChainID))

	s.mu.Lock()
	if _, exists := s.statuses[config.ChainID]; exists {
		s.mu.Unlock()
		return nil
	}

	stopChan := make(chan struct{})
	s.stopChans[config.ChainID] = stopChan
	status := &domain.IndexStatus{
		ChainID: config.ChainID,
		Status:  domain.IndexStatusRunning,
	}
	s.statuses[config.ChainID] = status
	s.mu.Unlock()

	go s.runIndexer(ctx, config, stopChan)
	return nil
}

func (s *indexerService) Stop(ctx context.Context, chainID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if stopChan, exists := s.stopChans[chainID]; exists {
		close(stopChan)
		delete(s.stopChans, chainID)
	}
	if status, exists := s.statuses[chainID]; exists {
		status.Status = domain.IndexStatusStopped
	}
	return nil
}

func (s *indexerService) Pause(ctx context.Context, chainID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if status, exists := s.statuses[chainID]; exists {
		status.Status = domain.IndexStatusPaused
	}
	return nil
}

func (s *indexerService) Resume(ctx context.Context, chainID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if status, exists := s.statuses[chainID]; exists {
		status.Status = domain.IndexStatusRunning
	}
	return nil
}

func (s *indexerService) IndexBlock(ctx context.Context, chainID int64, blockNumber uint64) error {
	logger.Debug("indexing block", zap.Int64("chain_id", chainID), zap.Uint64("block", blockNumber))

	rawBlock, err := s.fetcher.GetBlockByNumber(ctx, blockNumber)
	if err != nil {
		return err
	}

	block := &domain.BlockIndex{
		ID:            utils.GenerateID("blk"),
		ChainID:       chainID,
		BlockNumber:   blockNumber,
		Timestamp:     time.Now(),
		Status:        "indexed",
		CreatedAt:     time.Now(),
		IndexedAt:     time.Now(),
	}

	if err := s.repo.StoreBlock(ctx, block); err != nil {
		return err
	}

	s.updateStatus(chainID, blockNumber, 1, 0, 0)
	return nil
}

func (s *indexerService) IndexBlockRange(ctx context.Context, chainID int64, startBlock, endBlock uint64) error {
	logger.Info("indexing block range", zap.Int64("chain_id", chainID),
		zap.Uint64("start", startBlock), zap.Uint64("end", endBlock))

	for blockNum := startBlock; blockNum <= endBlock; blockNum++ {
		if err := s.IndexBlock(ctx, chainID, blockNum); err != nil {
			logger.Error("failed to index block", zap.Uint64("block", blockNum), zap.Error(err))
			continue
		}
	}
	return nil
}

func (s *indexerService) GetBlock(ctx context.Context, chainID int64, blockNumber uint64) (*domain.BlockIndex, error) {
	return s.repo.GetBlock(ctx, chainID, blockNumber)
}

func (s *indexerService) GetTransaction(ctx context.Context, txHash string) (*domain.TransactionIndex, error) {
	return s.repo.GetTransaction(ctx, txHash)
}

func (s *indexerService) ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.TransactionIndex, int64, error) {
	return s.repo.ListTransactions(ctx, filter, page, pageSize)
}

func (s *indexerService) GetLogs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.LogIndex, int64, error) {
	return s.repo.GetLogs(ctx, filter, page, pageSize)
}

func (s *indexerService) GetIndexStatus(ctx context.Context, chainID int64) (*domain.IndexStatus, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if status, exists := s.statuses[chainID]; exists {
		return status, nil
	}
	return s.repo.GetIndexStatus(ctx, chainID)
}

func (s *indexerService) runIndexer(ctx context.Context, config *domain.IndexConfig, stopChan <-chan struct{}) {
	latestBlock, err := s.fetcher.GetBlockNumber(ctx)
	if err != nil {
		logger.Error("failed to get latest block", zap.Error(err))
		return
	}

	fromBlock := config.StartBlock
	if fromBlock == 0 {
		lastIndexed, _ := s.repo.GetLatestIndexedBlock(ctx, config.ChainID)
		if lastIndexed > 0 {
			fromBlock = lastIndexed + 1
		}
	}

	for {
		select {
		case <-stopChan:
			logger.Info("indexer stopped", zap.Int64("chain_id", config.ChainID))
			return
		case <-ctx.Done():
			return
		default:
		}

		if fromBlock > latestBlock {
			time.Sleep(time.Second * 5)
			latestBlock, _ = s.fetcher.GetBlockNumber(ctx)
			continue
		}

		s.mu.RLock()
		status := s.statuses[config.ChainID]
		s.mu.RUnlock()
		if status.Status == domain.IndexStatusPaused {
			time.Sleep(time.Second)
			continue
		}

		if err := s.IndexBlock(ctx, config.ChainID, fromBlock); err != nil {
			logger.Error("index block failed", zap.Uint64("block", fromBlock), zap.Error(err))
			s.updateError(config.ChainID)
			time.Sleep(time.Second)
			continue
		}

		fromBlock++
	}
}

func (s *indexerService) updateStatus(chainID int64, block uint64, blocks, txs, logs uint64) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if status, exists := s.statuses[chainID]; exists {
		if block > status.IndexedBlock {
			status.IndexedBlock = block
		}
		status.TotalBlocks += blocks
		status.TotalTxs += txs
		status.TotalLogs += logs
		status.LastIndexedAt = time.Now()
	}
}

func (s *indexerService) updateError(chainID int64) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if status, exists := s.statuses[chainID]; exists {
		status.Errors++
	}
}
