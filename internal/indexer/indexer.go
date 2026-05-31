package indexer

import (
	"context"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
)

type ChainRPCInterface interface {
	GetLatestBlockNumber(ctx context.Context) (uint64, error)
	GetBlockByNumber(ctx context.Context, blockNumber uint64) (*BlockData, error)
	GetTransactionReceipt(ctx context.Context, txHash string) (*types.Receipt, error)
}

type BlockData struct {
	Number       uint64
	Hash         common.Hash
	ParentHash   common.Hash
	Timestamp    uint64
	Transactions []TransactionData
	GasUsed      uint64
	GasLimit     uint64
	Size         uint64
}

type TransactionData struct {
	Hash             common.Hash
	From             common.Address
	To               *common.Address
	Value            string
	Gas              uint64
	GasPrice         uint64
	MaxFeePerGas     uint64
	MaxPriorityFeePerGas uint64
	Input            []byte
	Nonce            uint64
}

type BlockIndexer struct {
	db          *gorm.DB
	chainRPC    ChainRPCInterface
	chainID     uint64
	workerCount int
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
	indexing    bool
	mu          sync.Mutex
}

func NewBlockIndexer(db *gorm.DB, chainRPC ChainRPCInterface, chainID uint64) *BlockIndexer {
	ctx, cancel := context.WithCancel(context.Background())
	return &BlockIndexer{
		db:          db,
		chainRPC:    chainRPC,
		chainID:     chainID,
		workerCount: config.AppConfig.Indexer.ConcurrentWorkers,
		ctx:         ctx,
		cancel:      cancel,
	}
}

func (bi *BlockIndexer) Start() error {
	bi.mu.Lock()
	defer bi.mu.Unlock()

	if bi.indexing {
		return fmt.Errorf("indexer already running")
	}

	bi.indexing = true
	go bi.indexLoop()

	logger.Log.Info("Block indexer started", zap.Uint64("chain_id", bi.chainID))
	return nil
}

func (bi *BlockIndexer) Stop() {
	bi.mu.Lock()
	defer bi.mu.Unlock()

	if !bi.indexing {
		return
	}

	bi.cancel()
	bi.wg.Wait()
	bi.indexing = false

	logger.Log.Info("Block indexer stopped", zap.Uint64("chain_id", bi.chainID))
}

func (bi *BlockIndexer) indexLoop() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-bi.ctx.Done():
			return
		case <-ticker.C:
			if err := bi.indexNextBatch(); err != nil {
				logger.Log.Error("Index batch failed", zap.Error(err))
			}
		}
	}
}

func (bi *BlockIndexer) indexNextBatch() error {
	latestIndexed, err := bi.getLatestIndexedBlock()
	if err != nil {
		return err
	}

	latestChain, err := bi.chainRPC.GetLatestBlockNumber(bi.ctx)
	if err != nil {
		return err
	}

	if latestIndexed >= latestChain {
		return nil
	}

	startBlock := latestIndexed + 1
	endBlock := startBlock + uint64(config.AppConfig.Indexer.BatchSize) - 1
	if endBlock > latestChain {
		endBlock = latestChain
	}

	logger.Log.Info("Indexing blocks",
		zap.Uint64("from", startBlock),
		zap.Uint64("to", endBlock),
		zap.Uint64("remaining", latestChain-endBlock))

	blockChan := make(chan uint64, bi.workerCount)
	resultChan := make(chan *indexResult, bi.workerCount)

	for i := 0; i < bi.workerCount; i++ {
		bi.wg.Add(1)
		go bi.worker(blockChan, resultChan)
	}

	go func() {
		for blockNum := startBlock; blockNum <= endBlock; blockNum++ {
			blockChan <- blockNum
		}
		close(blockChan)
	}()

	go func() {
		bi.wg.Wait()
		close(resultChan)
	}()

	results := make([]*indexResult, 0, endBlock-startBlock+1)
	for result := range resultChan {
		results = append(results, result)
	}

	return bi.persistResults(results)
}

func (bi *BlockIndexer) worker(blockChan <-chan uint64, resultChan chan<- *indexResult) {
	defer bi.wg.Done()

	for blockNum := range blockChan {
		select {
		case <-bi.ctx.Done():
			return
		default:
			result := &indexResult{blockNumber: blockNum}

			block, err := bi.chainRPC.GetBlockByNumber(bi.ctx, blockNum)
			if err != nil {
				result.err = err
				resultChan <- result
				continue
			}

			result.block = block
			result.txs = make([]*IndexedTxResult, 0, len(block.Transactions))

			for _, tx := range block.Transactions {
				txResult := &IndexedTxResult{tx: tx}
				receipt, err := bi.chainRPC.GetTransactionReceipt(bi.ctx, tx.Hash.Hex())
				if err == nil {
					txResult.receipt = receipt
				}
				result.txs = append(result.txs, txResult)
			}

			resultChan <- result
		}
	}
}

type indexResult struct {
	blockNumber uint64
	block       *BlockData
	txs         []*IndexedTxResult
	err         error
}

type IndexedTxResult struct {
	tx      TransactionData
	receipt *types.Receipt
}

func (bi *BlockIndexer) persistResults(results []*indexResult) error {
	tx := bi.db.Begin()
	if tx.Error != nil {
		return tx.Error
	}

	for _, result := range results {
		if result.err != nil {
			logger.Log.Warn("Skipping failed block",
				zap.Uint64("block", result.blockNumber),
				zap.Error(result.err))
			continue
		}

		block := result.block
		indexedBlock := &models.IndexedBlock{
			ChainID:     bi.chainID,
			BlockNumber: block.Number,
			BlockHash:   block.Hash.Hex(),
			ParentHash:  block.ParentHash.Hex(),
			BlockTime:   time.Unix(int64(block.Timestamp), 0),
			TxCount:     len(block.Transactions),
			GasUsed:     block.GasUsed,
			GasLimit:    block.GasLimit,
			Size:        int(block.Size),
			Indexed:     true,
		}

		if err := tx.Create(indexedBlock).Error; err != nil {
			tx.Rollback()
			return err
		}

		for _, txResult := range result.txs {
			txData := txResult.tx
			status := uint64(0)
			gasUsed := uint64(0)
			if txResult.receipt != nil {
				status = txResult.receipt.Status
				gasUsed = txResult.receipt.GasUsed
			}

			var toAddr string
			if txData.To != nil {
				toAddr = txData.To.Hex()
			}

			indexedTx := &models.IndexedTransaction{
				ChainID:     bi.chainID,
				BlockNumber: block.Number,
				TxHash:      txData.Hash.Hex(),
				FromAddress: txData.From.Hex(),
				ToAddress:   toAddr,
				Value:       txData.Value,
				Gas:         txData.Gas,
				GasPrice:    txData.GasPrice,
				GasUsed:     gasUsed,
				Nonce:       txData.Nonce,
				Data:        txData.Input,
				Status:      int(status),
			}

			if err := tx.Create(indexedTx).Error; err != nil {
				tx.Rollback()
				return err
			}
		}
	}

	return tx.Commit().Error
}

func (bi *BlockIndexer) getLatestIndexedBlock() (uint64, error) {
	var maxBlock models.IndexedBlock
	err := bi.db.Where("chain_id = ?", bi.chainID).
		Order("block_number DESC").
		First(&maxBlock).Error

	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return config.AppConfig.Indexer.StartBlock - 1, nil
		}
		return 0, err
	}

	return maxBlock.BlockNumber, nil
}

func (bi *BlockIndexer) GetBlock(ctx context.Context, blockNumber uint64) (*models.IndexedBlock, error) {
	var block models.IndexedBlock
	err := bi.db.Where("chain_id = ? AND block_number = ?", bi.chainID, blockNumber).First(&block).Error
	if err != nil {
		return nil, err
	}
	return &block, nil
}

func (bi *BlockIndexer) GetTransaction(ctx context.Context, txHash string) (*models.IndexedTransaction, error) {
	var tx models.IndexedTransaction
	err := bi.db.Where("tx_hash = ?", txHash).First(&tx).Error
	if err != nil {
		return nil, err
	}
	return &tx, nil
}

func (bi *BlockIndexer) GetTransactionsByAddress(ctx context.Context, address string, offset, limit int) ([]models.IndexedTransaction, int64, error) {
	var txs []models.IndexedTransaction
	var total int64

	query := bi.db.Model(&models.IndexedTransaction{}).
		Where("from_address = ? OR to_address = ?", address, address)

	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("block_number DESC").Find(&txs).Error

	return txs, total, err
}

func (bi *BlockIndexer) GetBlocks(ctx context.Context, offset, limit int) ([]models.IndexedBlock, int64, error) {
	var blocks []models.IndexedBlock
	var total int64

	query := bi.db.Model(&models.IndexedBlock{}).Where("chain_id = ?", bi.chainID)
	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("block_number DESC").Find(&blocks).Error

	return blocks, total, err
}

func (bi *BlockIndexer) GetIndexingStatus(ctx context.Context) (uint64, uint64, bool, error) {
	latestIndexed, err := bi.getLatestIndexedBlock()
	if err != nil {
		return 0, 0, false, err
	}

	latestChain, err := bi.chainRPC.GetLatestBlockNumber(ctx)
	if err != nil {
		return latestIndexed, 0, bi.indexing, err
	}

	return latestIndexed, latestChain, bi.indexing, nil
}
