package indexer

import (
	"errors"
	"gas-estimator/internal/chain"
	"gas-estimator/pkg/models"
	"sync"
	"time"
)

var (
	ErrIndexOutOfRange = errors.New("index out of range")
	ErrBlockNotIndexed = errors.New("block not indexed")
)

type BlockIndexer struct {
	chainAdapter    *chain.ChainAdapter
	blockIndex      map[string][]*models.Block
	blockByNumber   map[string]map[uint64]*models.Block
	blockByHash     map[string]map[string]*models.Block
	transactionIndex map[string]map[string]*models.Transaction
	addressIndex    map[string]map[string][]uint64
	latestBlock     map[string]uint64
	indexSize       int
	mutex           sync.RWMutex
}

func NewBlockIndexer(chainAdapter *chain.ChainAdapter) *BlockIndexer {
	return &BlockIndexer{
		chainAdapter:    chainAdapter,
		blockIndex:      make(map[string][]*models.Block),
		blockByNumber:   make(map[string]map[uint64]*models.Block),
		blockByHash:     make(map[string]map[string]*models.Block),
		transactionIndex: make(map[string]map[string]*models.Transaction),
		addressIndex:    make(map[string]map[string][]uint64),
		latestBlock:     make(map[string]uint64),
		indexSize:       1000,
		mutex:           sync.RWMutex{},
	}
}

func (bi *BlockIndexer) IndexBlock(chainID string, block *models.Block) error {
	bi.mutex.Lock()
	defer bi.mutex.Unlock()
	
	bi.ensureChainIndexes(chainID)
	
	hashStr := string(block.Hash)
	
	if existing, ok := bi.blockByHash[chainID][hashStr]; ok {
		return nil
	}
	
	bi.blockIndex[chainID] = append(bi.blockIndex[chainID], block)
	bi.blockByNumber[chainID][block.Number] = block
	bi.blockByHash[chainID][hashStr] = block
	
	for _, tx := range block.Transactions {
		txHash := string(tx.Data)
		bi.transactionIndex[chainID][txHash] = &tx
		
		if len(tx.To) > 0 {
			toAddr := string(tx.To)
			bi.addressIndex[chainID][toAddr] = append(bi.addressIndex[chainID][toAddr], block.Number)
			
			if len(bi.addressIndex[chainID][toAddr]) > bi.indexSize {
				bi.addressIndex[chainID][toAddr] = bi.addressIndex[chainID][toAddr][len(bi.addressIndex[chainID][toAddr])-bi.indexSize:]
			}
		}
	}
	
	if block.Number > bi.latestBlock[chainID] {
		bi.latestBlock[chainID] = block.Number
	}
	
	bi.cleanupOldData(chainID)
	
	return nil
}

func (bi *BlockIndexer) IndexLatestBlocks(chainID string, count int) error {
	currentChain := bi.chainAdapter.GetCurrentChain()
	if currentChain != chainID {
		if err := bi.chainAdapter.SwitchChain(chainID); err != nil {
			return err
		}
	}
	
	latestBlock, err := bi.chainAdapter.GetLatestBlock()
	if err != nil {
		return err
	}
	
	for i := 0; i < count; i++ {
		blockNumber := latestBlock.Number - uint64(count) + uint64(i) + 1
		
		block, err := bi.chainAdapter.GetBlockByNumber(blockNumber)
		if err != nil {
			continue
		}
		
		if err := bi.IndexBlock(chainID, block); err != nil {
			continue
		}
	}
	
	return nil
}

func (bi *BlockIndexer) GetBlockByNumber(chainID string, number uint64) (*models.Block, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	blocks, ok := bi.blockByNumber[chainID]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	block, ok := blocks[number]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	return block, nil
}

func (bi *BlockIndexer) GetBlockByHash(chainID string, hash []byte) (*models.Block, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	blocks, ok := bi.blockByHash[chainID]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	block, ok := blocks[string(hash)]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	return block, nil
}

func (bi *BlockIndexer) GetLatestBlock(chainID string) (*models.Block, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	latest, ok := bi.latestBlock[chainID]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	blocks, ok := bi.blockByNumber[chainID]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	block, ok := blocks[latest]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	return block, nil
}

func (bi *BlockIndexer) GetBlocksInRange(chainID string, start, end uint64) ([]*models.Block, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	blocks, ok := bi.blockByNumber[chainID]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	result := make([]*models.Block, 0)
	
	for num := start; num <= end; num++ {
		if block, ok := blocks[num]; ok {
			result = append(result, block)
		}
	}
	
	return result, nil
}

func (bi *BlockIndexer) GetTransaction(chainID string, txHash []byte) (*models.Transaction, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	txs, ok := bi.transactionIndex[chainID]
	if !ok {
		return nil, errors.New("transaction not found")
	}
	
	tx, ok := txs[string(txHash)]
	if !ok {
		return nil, errors.New("transaction not found")
	}
	
	return tx, nil
}

func (bi *BlockIndexer) GetTransactionsByAddress(chainID string, address []byte) ([]uint64, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	addresses, ok := bi.addressIndex[chainID]
	if !ok {
		return nil, errors.New("address not found")
	}
	
	blocks, ok := addresses[string(address)]
	if !ok {
		return nil, errors.New("address not found")
	}
	
	return blocks, nil
}

func (bi *BlockIndexer) GetBlockStats(chainID string) (map[string]interface{}, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	blocks, ok := bi.blockIndex[chainID]
	if !ok || len(blocks) == 0 {
		return nil, ErrBlockNotIndexed
	}
	
	stats := map[string]interface{}{
		"total_blocks":      len(blocks),
		"latest_block":      bi.latestBlock[chainID],
		"indexed_from":      blocks[0].Number,
		"indexed_to":        blocks[len(blocks)-1].Number,
		"total_transactions": 0,
	}
	
	totalGasUsed := uint64(0)
	totalGasLimit := uint64(0)
	
	for _, block := range blocks {
		stats["total_transactions"] = stats["total_transactions"].(int) + len(block.Transactions)
		totalGasUsed += block.GasUsed
		totalGasLimit += block.GasLimit
	}
	
	stats["avg_gas_used"] = totalGasUsed / uint64(len(blocks))
	stats["avg_gas_limit"] = totalGasLimit / uint64(len(blocks))
	
	return stats, nil
}

func (bi *BlockIndexer) SearchTransactions(chainID string, filter func(*models.Transaction) bool) []*models.Transaction {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	txs, ok := bi.transactionIndex[chainID]
	if !ok {
		return []*models.Transaction{}
	}
	
	result := make([]*models.Transaction, 0)
	
	for _, tx := range txs {
		if filter(tx) {
			result = append(result, tx)
		}
	}
	
	return result
}

func (bi *BlockIndexer) GetIndexedBlocks(chainID string, limit int) ([]*models.Block, error) {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	blocks, ok := bi.blockIndex[chainID]
	if !ok {
		return nil, ErrBlockNotIndexed
	}
	
	if limit <= 0 || limit > len(blocks) {
		limit = len(blocks)
	}
	
	result := make([]*models.Block, limit)
	start := len(blocks) - limit
	copy(result, blocks[start:])
	
	return result, nil
}

func (bi *BlockIndexer) IsBlockIndexed(chainID string, blockNumber uint64) bool {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	blocks, ok := bi.blockByNumber[chainID]
	if !ok {
		return false
	}
	
	_, ok = blocks[blockNumber]
	return ok
}

func (bi *BlockIndexer) GetIndexInfo() map[string]interface{} {
	bi.mutex.RLock()
	defer bi.mutex.RUnlock()
	
	info := map[string]interface{}{
		"chains":         make(map[string]interface{}),
		"index_size":     bi.indexSize,
	}
	
	for chainID, blocks := range bi.blockIndex {
		chainInfo := map[string]interface{}{
			"block_count":       len(blocks),
			"latest_block":      bi.latestBlock[chainID],
			"transaction_count": len(bi.transactionIndex[chainID]),
			"address_count":     len(bi.addressIndex[chainID]),
		}
		
		if len(blocks) > 0 {
			chainInfo["oldest_block"] = blocks[0].Number
		}
		
		info["chains"].(map[string]interface{})[chainID] = chainInfo
	}
	
	return info
}

func (bi *BlockIndexer) StartContinuousIndexing(chainID string, interval time.Duration) {
	ticker := time.NewTicker(interval)
	go func() {
		for range ticker.C {
			bi.IndexLatestBlocks(chainID, 1)
		}
	}()
}

func (bi *BlockIndexer) ensureChainIndexes(chainID string) {
	if _, ok := bi.blockIndex[chainID]; !ok {
		bi.blockIndex[chainID] = make([]*models.Block, 0, bi.indexSize)
	}
	
	if _, ok := bi.blockByNumber[chainID]; !ok {
		bi.blockByNumber[chainID] = make(map[uint64]*models.Block)
	}
	
	if _, ok := bi.blockByHash[chainID]; !ok {
		bi.blockByHash[chainID] = make(map[string]*models.Block)
	}
	
	if _, ok := bi.transactionIndex[chainID]; !ok {
		bi.transactionIndex[chainID] = make(map[string]*models.Transaction)
	}
	
	if _, ok := bi.addressIndex[chainID]; !ok {
		bi.addressIndex[chainID] = make(map[string][]uint64)
	}
}

func (bi *BlockIndexer) cleanupOldData(chainID string) {
	if len(bi.blockIndex[chainID]) <= bi.indexSize {
		return
	}
	
	excess := len(bi.blockIndex[chainID]) - bi.indexSize
	oldBlocks := bi.blockIndex[chainID][:excess]
	
	for _, block := range oldBlocks {
		delete(bi.blockByNumber[chainID], block.Number)
		delete(bi.blockByHash[chainID], string(block.Hash))
		
		for _, tx := range block.Transactions {
			delete(bi.transactionIndex[chainID], string(tx.Data))
		}
	}
	
	bi.blockIndex[chainID] = bi.blockIndex[chainID][excess:]
}
