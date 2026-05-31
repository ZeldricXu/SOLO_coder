package chainadapter

import (
	"context"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/ethclient"
	"github.com/ethereum/go-ethereum/rpc"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/gasestimator"
)

type ChainConfig struct {
	ChainID  uint64
	Name     string
	RPCURL   string
	Explorer string
}

type BlockInfo struct {
	Number       uint64
	Hash         string
	ParentHash   string
	Timestamp    time.Time
	Transactions []string
	GasUsed      uint64
	GasLimit     uint64
	BaseFee      *big.Int
	Size         uint64
}

type TransactionInfo struct {
	Hash             string
	BlockNumber      uint64
	From             string
	To               string
	Value            *big.Int
	Gas              uint64
	GasPrice         *big.Int
	MaxFeePerGas     *big.Int
	MaxPriorityFeePerGas *big.Int
	Input            []byte
	Nonce            uint64
	Status           uint64
	Logs             []LogInfo
}

type LogInfo struct {
	Address     string
	Topics      []string
	Data        []byte
	BlockNumber uint64
	TxHash      string
	Index       uint
}

type SendBatchResult struct {
	TxHashes     []string `json:"tx_hashes"`
	SuccessCount int      `json:"success_count"`
	FailCount    int      `json:"fail_count"`
	Errors       []string `json:"errors,omitempty"`
}

type ChainAdapter struct {
	clients      map[uint64]*ethclient.Client
	rpcClients   map[uint64]*rpc.Client
	configs      map[uint64]ChainConfig
	metrics      *MetricsCollector
	monitors     map[uint64]*ChainMonitor
	mu           sync.RWMutex
}

func NewChainAdapter() *ChainAdapter {
	return &ChainAdapter{
		clients:    make(map[uint64]*ethclient.Client),
		rpcClients: make(map[uint64]*rpc.Client),
		configs:    make(map[uint64]ChainConfig),
		metrics:    NewMetricsCollector(),
		monitors:   make(map[uint64]*ChainMonitor),
	}
}

func (ca *ChainAdapter) AddChain(cfg ChainConfig) error {
	ca.mu.Lock()
	defer ca.mu.Unlock()

	rpcClient, err := rpc.Dial(cfg.RPCURL)
	if err != nil {
		return fmt.Errorf("failed to connect to RPC: %w", err)
	}

	ethClient := ethclient.NewClient(rpcClient)

	chainID, err := ethClient.ChainID(context.Background())
	if err != nil {
		rpcClient.Close()
		return fmt.Errorf("failed to get chain ID: %w", err)
	}

	if chainID.Uint64() != cfg.ChainID {
		rpcClient.Close()
		return fmt.Errorf("chain ID mismatch: expected %d, got %d", cfg.ChainID, chainID.Uint64())
	}

	ca.clients[cfg.ChainID] = ethClient
	ca.rpcClients[cfg.ChainID] = rpcClient
	ca.configs[cfg.ChainID] = cfg
	ca.monitors[cfg.ChainID] = NewChainMonitor(cfg.ChainID, ca.metrics, 1000)

	logger.Log.Info("Chain added", zap.Uint64("chain_id", cfg.ChainID), zap.String("name", cfg.Name))
	return nil
}

func (ca *ChainAdapter) GetLatestBlockNumber(ctx context.Context, chainID uint64) (uint64, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	monitor, monitorExists := ca.monitors[chainID]
	ca.mu.RUnlock()

	if !exists {
		return 0, errors.ErrChainNotSupported
	}

	timer := ca.metrics.StartRPCTimer(chainID, "GetLatestBlockNumber")
	start := time.Now()

	header, err := client.HeaderByNumber(ctx, nil)
	duration := time.Since(start)

	if err != nil {
		timer.ObserveError("header_fetch_failed")
		if monitorExists {
			monitor.RecordRequest(duration, false, err.Error())
		}
		return 0, fmt.Errorf("failed to get latest block: %w", err)
	}

	timer.Observe("success")
	if monitorExists {
		monitor.RecordRequest(duration, true, "")
		ca.metrics.SetChainHeight(chainID, header.Number.Uint64())
	}

	return header.Number.Uint64(), nil
}

func (ca *ChainAdapter) GetBaseFeePerGas(ctx context.Context, chainID uint64) (*big.Int, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	header, err := client.HeaderByNumber(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to get header: %w", err)
	}

	if header.BaseFee == nil {
		return big.NewInt(0), nil
	}

	return header.BaseFee, nil
}

func (ca *ChainAdapter) GetMaxPriorityFeePerGas(ctx context.Context, chainID uint64) (*big.Int, error) {
	ca.mu.RLock()
	rpcClient, exists := ca.rpcClients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	var result string
	err := rpcClient.CallContext(ctx, &result, "eth_maxPriorityFeePerGas")
	if err != nil {
		return big.NewInt(1_000_000_000), nil
	}

	fee := new(big.Int)
	fee.SetString(result[2:], 16)
	return fee, nil
}

func (ca *ChainAdapter) GetBlockByNumber(ctx context.Context, chainID uint64, blockNumber uint64) (*BlockInfo, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	block, err := client.BlockByNumber(ctx, big.NewInt(int64(blockNumber)))
	if err != nil {
		return nil, fmt.Errorf("failed to get block: %w", err)
	}

	txHashes := make([]string, len(block.Transactions()))
	for i, tx := range block.Transactions() {
		txHashes[i] = tx.Hash().Hex()
	}

	return &BlockInfo{
		Number:       block.NumberU64(),
		Hash:         block.Hash().Hex(),
		ParentHash:   block.ParentHash().Hex(),
		Timestamp:    time.Unix(int64(block.Time()), 0),
		Transactions: txHashes,
		GasUsed:      block.GasUsed(),
		GasLimit:     block.GasLimit(),
		BaseFee:      block.BaseFee(),
		Size:         block.Size(),
	}, nil
}

func (ca *ChainAdapter) GetTransactionByHash(ctx context.Context, chainID uint64, txHash string) (*TransactionInfo, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	hash := common.HexToHash(txHash)
	tx, isPending, err := client.TransactionByHash(ctx, hash)
	if err != nil {
		return nil, fmt.Errorf("failed to get transaction: %w", err)
	}

	if isPending {
		return &TransactionInfo{
			Hash:  tx.Hash().Hex(),
			Value: tx.Value(),
			Gas:   tx.Gas(),
			Input: tx.Data(),
			Nonce: tx.Nonce(),
		}, nil
	}

	receipt, err := client.TransactionReceipt(ctx, hash)
	if err != nil {
		return nil, fmt.Errorf("failed to get receipt: %w", err)
	}

	from, err := types.Sender(types.NewEIP155Signer(tx.ChainId()), tx)
	if err != nil {
		from = common.Address{}
	}

	logs := make([]LogInfo, len(receipt.Logs))
	for i, log := range receipt.Logs {
		topics := make([]string, len(log.Topics))
		for j, topic := range log.Topics {
			topics[j] = topic.Hex()
		}
		logs[i] = LogInfo{
			Address:     log.Address.Hex(),
			Topics:      topics,
			Data:        log.Data,
			BlockNumber: log.BlockNumber,
			TxHash:      log.TxHash.Hex(),
			Index:       log.Index,
		}
	}

	var toAddr string
	if tx.To() != nil {
		toAddr = tx.To().Hex()
	}

	return &TransactionInfo{
		Hash:             tx.Hash().Hex(),
		BlockNumber:      receipt.BlockNumber.Uint64(),
		From:             from.Hex(),
		To:               toAddr,
		Value:            tx.Value(),
		Gas:              tx.Gas(),
		GasPrice:         tx.GasPrice(),
		MaxFeePerGas:     tx.GasFeeCap(),
		MaxPriorityFeePerGas: tx.GasTipCap(),
		Input:            tx.Data(),
		Nonce:            tx.Nonce(),
		Status:           receipt.Status,
		Logs:             logs,
	}, nil
}

func (ca *ChainAdapter) SendRawTransaction(ctx context.Context, chainID uint64, rawTx []byte) (string, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return "", errors.ErrChainNotSupported
	}

	tx := new(types.Transaction)
	if err := tx.UnmarshalBinary(rawTx); err != nil {
		return "", fmt.Errorf("invalid transaction: %w", err)
	}

	if err := client.SendTransaction(ctx, tx); err != nil {
		return "", fmt.Errorf("failed to send transaction: %w", err)
	}

	return tx.Hash().Hex(), nil
}

func (ca *ChainAdapter) GetBalance(ctx context.Context, chainID uint64, address string) (*big.Int, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	addr := common.HexToAddress(address)
	balance, err := client.BalanceAt(ctx, addr, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to get balance: %w", err)
	}

	return balance, nil
}

func (ca *ChainAdapter) GetNonce(ctx context.Context, chainID uint64, address string) (uint64, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return 0, errors.ErrChainNotSupported
	}

	addr := common.HexToAddress(address)
	nonce, err := client.PendingNonceAt(ctx, addr)
	if err != nil {
		return 0, fmt.Errorf("failed to get nonce: %w", err)
	}

	return nonce, nil
}

func (ca *ChainAdapter) FilterLogs(ctx context.Context, chainID uint64, query ethereum.FilterQuery) ([]types.Log, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	logs, err := client.FilterLogs(ctx, query)
	if err != nil {
		return nil, fmt.Errorf("failed to filter logs: %w", err)
	}

	return logs, nil
}

func (ca *ChainAdapter) SubscribeNewHead(ctx context.Context, chainID uint64, ch chan<- *types.Header) (ethereum.Subscription, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	sub, err := client.SubscribeNewHead(ctx, ch)
	if err != nil {
		return nil, fmt.Errorf("failed to subscribe: %w", err)
	}

	return sub, nil
}

func (ca *ChainAdapter) Close() {
	ca.mu.Lock()
	defer ca.mu.Unlock()

	for _, client := range ca.clients {
		client.Close()
	}
	for _, rpcClient := range ca.rpcClients {
		rpcClient.Close()
	}
}

func (ca *ChainAdapter) GetBlockByNumberForGasEstimator(ctx context.Context, chainID uint64, blockNumber uint64) (*gasestimator.BlockData, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	block, err := client.BlockByNumber(ctx, big.NewInt(int64(blockNumber)))
	if err != nil {
		return nil, fmt.Errorf("failed to get block: %w", err)
	}

	txs := make([]gasestimator.TransactionData, len(block.Transactions()))
	for i, tx := range block.Transactions() {
		receipt, err := client.TransactionReceipt(ctx, tx.Hash())
		gasUsed := uint64(0)
		if err == nil {
			gasUsed = receipt.GasUsed
		}

		txs[i] = gasestimator.TransactionData{
			GasPrice:             tx.GasPrice(),
			MaxFeePerGas:         tx.GasFeeCap(),
			MaxPriorityFeePerGas: tx.GasTipCap(),
			GasUsed:              gasUsed,
		}
	}

	var baseFee *big.Int
	if block.BaseFee() != nil {
		baseFee = block.BaseFee()
	}

	return &gasestimator.BlockData{
		Number:        block.NumberU64(),
		Timestamp:     time.Unix(int64(block.Time()), 0),
		BaseFeePerGas: baseFee,
		Transactions:  txs,
	}, nil
}

func (ca *ChainAdapter) InitializeFromConfig() error {
	for name, chain := range config.AppConfig.Chains {
		cfg := ChainConfig{
			ChainID: chain.ChainID,
			Name:    name,
			RPCURL:  chain.RPCURL,
		}
		if err := ca.AddChain(cfg); err != nil {
			logger.Log.Warn("Failed to add chain", zap.String("chain", name), zap.Error(err))
		}
	}
	return nil
}

func (ca *ChainAdapter) GetMetricsCollector() *MetricsCollector {
	return ca.metrics
}

func (ca *ChainAdapter) StartMetricsServer(config MetricsConfig) error {
	return ca.metrics.StartServer(config)
}

func (ca *ChainAdapter) StopMetricsServer(ctx context.Context) error {
	return ca.metrics.StopServer(ctx)
}

func (ca *ChainAdapter) GetChainHealthStatus(chainID uint64) (*HealthStatus, error) {
	ca.mu.RLock()
	monitor, exists := ca.monitors[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	status := monitor.GetHealthStatus()
	return &status, nil
}

func (ca *ChainAdapter) GetAllChainHealthStatus() map[uint64]HealthStatus {
	ca.mu.RLock()
	defer ca.mu.RUnlock()

	statuses := make(map[uint64]HealthStatus, len(ca.monitors))
	for chainID, monitor := range ca.monitors {
		statuses[chainID] = monitor.GetHealthStatus()
	}
	return statuses
}

func (ca *ChainAdapter) SendRawTransactionBatch(ctx context.Context, chainID uint64, rawTxs [][]byte) (*SendBatchResult, error) {
	ca.mu.RLock()
	client, exists := ca.clients[chainID]
	ca.mu.RUnlock()

	if !exists {
		return nil, errors.ErrChainNotSupported
	}

	result := &SendBatchResult{
		TxHashes: make([]string, 0, len(rawTxs)),
		Errors:   make([]string, 0),
	}

	opTimer := ca.metrics.StartTimer(chainID, "SendRawTransactionBatch")

	for i, rawTx := range rawTxs {
		rpcTimer := ca.metrics.StartRPCTimer(chainID, "SendRawTransaction")

		tx := new(types.Transaction)
		if err := tx.UnmarshalBinary(rawTx); err != nil {
			rpcTimer.ObserveError("invalid_tx")
			result.FailCount++
			result.Errors = append(result.Errors, fmt.Sprintf("tx %d: invalid raw transaction: %v", i, err))
			continue
		}

		if err := client.SendTransaction(ctx, tx); err != nil {
			rpcTimer.ObserveError("send_failed")
			result.FailCount++
			result.Errors = append(result.Errors, fmt.Sprintf("tx %d: %v", i, err))
			continue
		}

		rpcTimer.Observe("success")
		result.SuccessCount++
		result.TxHashes = append(result.TxHashes, tx.Hash().Hex())
	}

	opTimer.Observe("success")
	return result, nil
}
