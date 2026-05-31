package blockchain

import (
	"context"
	"fmt"
	"gas-estimator/internal/domain"
	"gas-estimator/internal/infra/metrics"
	"math/big"
	"time"
)

type MonitoredBlockchainAdapter struct {
	baseAdapter domain.BlockchainService
	metrics     metrics.MetricsService
	currentChain *big.Int
}

func NewMonitoredBlockchainAdapter(baseAdapter domain.BlockchainService, metricsSvc metrics.MetricsService) domain.BlockchainService {
	return &MonitoredBlockchainAdapter{
		baseAdapter: baseAdapter,
		metrics:     metricsSvc,
		currentChain: big.NewInt(1),
	}
}

func (m *MonitoredBlockchainAdapter) SwitchChain(chainID *big.Int) error {
	start := time.Now()
	
	err := m.baseAdapter.SwitchChain(chainID)
	duration := time.Since(start)
	
	chainIDStr := "unknown"
	if chainID != nil {
		chainIDStr = chainID.String()
	}
	
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("switch_chain", chainIDStr, duration, err == nil)
		if err == nil {
			m.currentChain = chainID
			m.metrics.UpdateBlockchainHealth(chainIDStr, true)
		} else {
			m.metrics.UpdateBlockchainHealth(chainIDStr, false)
		}
	}
	
	return err
}

func (m *MonitoredBlockchainAdapter) GetCurrentChain() *big.Int {
	if m.currentChain != nil {
		return new(big.Int).Set(m.currentChain)
	}
	return m.baseAdapter.GetCurrentChain()
}

func (m *MonitoredBlockchainAdapter) GetBlockByNumber(blockNumber *big.Int) (*domain.Block, error) {
	start := time.Now()
	
	block, err := m.baseAdapter.GetBlockByNumber(blockNumber)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_block_by_number", chainIDStr, duration, err == nil)
	}
	
	return block, err
}

func (m *MonitoredBlockchainAdapter) GetLatestBlock() (*domain.Block, error) {
	start := time.Now()
	
	block, err := m.baseAdapter.GetLatestBlock()
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_latest_block", chainIDStr, duration, err == nil)
	}
	
	return block, err
}

func (m *MonitoredBlockchainAdapter) GetBalance(address string) (*big.Int, error) {
	start := time.Now()
	
	balance, err := m.baseAdapter.GetBalance(address)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_balance", chainIDStr, duration, err == nil)
	}
	
	return balance, err
}

func (m *MonitoredBlockchainAdapter) SendTransaction(tx *domain.Transaction) (string, error) {
	start := time.Now()
	
	hash, err := m.baseAdapter.SendTransaction(tx)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordTransaction(err == nil, duration)
		m.metrics.RecordBlockchainCall("send_transaction", chainIDStr, duration, err == nil)
	}
	
	return hash, err
}

func (m *MonitoredBlockchainAdapter) GetTransactionReceipt(txHash string) (*domain.TransactionReceipt, error) {
	start := time.Now()
	
	receipt, err := m.baseAdapter.GetTransactionReceipt(txHash)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_transaction_receipt", chainIDStr, duration, err == nil)
	}
	
	return receipt, err
}

func (m *MonitoredBlockchainAdapter) EstimateGas(from string, to string, value *big.Int, data []byte) (uint64, error) {
	start := time.Now()
	
	gas, err := m.baseAdapter.EstimateGas(from, to, value, data)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.MeasureGasEstimate(func() (bool, error) {
			return false, err
		})
		m.metrics.RecordBlockchainCall("estimate_gas", chainIDStr, duration, err == nil)
	}
	
	return gas, err
}

func (m *MonitoredBlockchainAdapter) GetGasPrice() (*big.Int, error) {
	start := time.Now()
	
	gasPrice, err := m.baseAdapter.GetGasPrice()
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_gas_price", chainIDStr, duration, err == nil)
	}
	
	return gasPrice, err
}

func (m *MonitoredBlockchainAdapter) GetNonce(address string) (uint64, error) {
	start := time.Now()
	
	nonce, err := m.baseAdapter.GetNonce(address)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_nonce", chainIDStr, duration, err == nil)
	}
	
	return nonce, err
}

func (m *MonitoredBlockchainAdapter) CallContract(from string, to string, data []byte, blockNumber *big.Int) ([]byte, error) {
	start := time.Now()
	
	result, err := m.baseAdapter.CallContract(from, to, data, blockNumber)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("call_contract", chainIDStr, duration, err == nil)
	}
	
	return result, err
}

func (m *MonitoredBlockchainAdapter) GetChainID() (*big.Int, error) {
	start := time.Now()
	
	chainID, err := m.baseAdapter.GetChainID()
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("get_chain_id", chainIDStr, duration, err == nil)
	}
	
	return chainID, err
}

func (m *MonitoredBlockchainAdapter) HealthCheck(ctx context.Context) error {
	start := time.Now()
	
	err := m.baseAdapter.HealthCheck(ctx)
	duration := time.Since(start)
	
	chainIDStr := m.getCurrentChainID()
	if m.metrics != nil {
		m.metrics.RecordBlockchainCall("health_check", chainIDStr, duration, err == nil)
		if err == nil {
			m.metrics.UpdateBlockchainHealth(chainIDStr, true)
		} else {
			m.metrics.UpdateBlockchainHealth(chainIDStr, false)
		}
	}
	
	return err
}

func (m *MonitoredBlockchainAdapter) getCurrentChainID() string {
	if m.currentChain == nil {
		return "unknown"
	}
	return m.currentChain.String()
}

func (m *MonitoredBlockchainAdapter) HealthCheckWithTimeout(timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	
	return m.HealthCheck(ctx)
}

func (m *MonitoredBlockchainAdapter) GetMetrics() metrics.MetricsService {
	return m.metrics
}

type MonitoredAdapterConfig struct {
	MetricsConfig *metrics.MetricsConfig
}

func NewMonitoredAdapterWithConfig(baseAdapter domain.BlockchainService, config *MonitoredAdapterConfig) (domain.BlockchainService, metrics.MetricsService, error) {
	if config == nil {
		config = &MonitoredAdapterConfig{}
	}
	
	metricsSvc := metrics.NewMetrics(config.MetricsConfig)
	
	adapter := NewMonitoredBlockchainAdapter(baseAdapter, metricsSvc)
	
	return adapter, metricsSvc, nil
}

var (
	_ domain.BlockchainService = (*MonitoredBlockchainAdapter)(nil)
)
