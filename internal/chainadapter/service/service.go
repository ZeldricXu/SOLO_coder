package service

import (
	"context"
	"math/big"
	"sync"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/chainadapter/domain"
	"github.com/solocoder/session147/internal/chainadapter/ports"
)

type chainAdapterService struct {
	chains map[int64]ports.ChainClient
	configs map[int64]*domain.ChainConfig
	mu     sync.RWMutex
}

func NewChainAdapterService() ports.ChainAdapterService {
	return &chainAdapterService{
		chains:  make(map[int64]ports.ChainClient),
		configs: make(map[int64]*domain.ChainConfig),
	}
}

func (s *chainAdapterService) RegisterChain(config *domain.ChainConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	client, err := newEthClient(config)
	if err != nil {
		return err
	}

	s.chains[config.ChainID] = client
	s.configs[config.ChainID] = config
	return nil
}

func (s *chainAdapterService) GetChain(chainID int64) (ports.ChainClient, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	client, exists := s.chains[chainID]
	if !exists {
		return nil, errors.NotFound("chain not found", nil)
	}
	return client, nil
}

func (s *chainAdapterService) ListChains() []*domain.ChainConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()

	configs := make([]*domain.ChainConfig, 0, len(s.configs))
	for _, cfg := range s.configs {
		configs = append(configs, cfg)
	}
	return configs
}

func (s *chainAdapterService) GetBlockNumber(ctx context.Context, chainID int64) (uint64, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return 0, err
	}
	return client.GetBlockNumber(ctx)
}

func (s *chainAdapterService) GetBlock(ctx context.Context, chainID int64, blockNumber uint64) (*domain.BlockData, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.GetBlockByNumber(ctx, blockNumber)
}

func (s *chainAdapterService) GetBlockByHash(ctx context.Context, chainID int64, hash string) (*domain.BlockData, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.GetBlockByHash(ctx, hash)
}

func (s *chainAdapterService) GetBalance(ctx context.Context, chainID int64, address string) (*domain.BalanceResponse, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}

	balance, err := client.GetBalance(ctx, address)
	if err != nil {
		return nil, err
	}

	nonce, err := client.GetNonce(ctx, address)
	if err != nil {
		nonce = 0
	}

	return &domain.BalanceResponse{
		Address: address,
		Balance: balance,
		Nonce:   nonce,
	}, nil
}

func (s *chainAdapterService) GetTransaction(ctx context.Context, chainID int64, hash string) (*domain.TransactionData, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.GetTransactionByHash(ctx, hash)
}

func (s *chainAdapterService) GetTransactionReceipt(ctx context.Context, chainID int64, hash string) (*domain.TransactionReceipt, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.GetTransactionReceipt(ctx, hash)
}

func (s *chainAdapterService) EstimateGas(ctx context.Context, chainID int64, to, data string, value *big.Int) (uint64, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return 0, err
	}
	return client.EstimateGas(ctx, to, data, value)
}

func (s *chainAdapterService) GetGasPrice(ctx context.Context, chainID int64) (*big.Int, *big.Int, *big.Int, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, nil, nil, err
	}

	baseFee, err := client.GetBaseFeePerGas(ctx)
	if err != nil {
		baseFee = big.NewInt(1000000000)
	}

	priorityFee, err := client.GetMaxPriorityFeePerGas(ctx)
	if err != nil {
		priorityFee = big.NewInt(1000000000)
	}

	gasPrice := new(big.Int).Add(baseFee, priorityFee)
	return baseFee, priorityFee, gasPrice, nil
}

func (s *chainAdapterService) SendTransaction(ctx context.Context, chainID int64, rawTx string) (string, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return "", err
	}
	return client.SendRawTransaction(ctx, rawTx)
}

func (s *chainAdapterService) CallContract(ctx context.Context, chainID int64, to, data string) ([]byte, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.CallContract(ctx, to, data)
}

func (s *chainAdapterService) GetLogs(ctx context.Context, chainID int64, fromBlock, toBlock uint64, addresses []string, topics []string) ([]domain.LogData, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.GetLogs(ctx, fromBlock, toBlock, addresses, topics)
}

func (s *chainAdapterService) SubscribeNewHeads(ctx context.Context, chainID int64) (<-chan *domain.BlockData, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.SubscribeNewHeads(ctx)
}

func (s *chainAdapterService) SubscribeLogs(ctx context.Context, chainID int64, addresses []string, topics []string) (<-chan *domain.LogData, error) {
	client, err := s.GetChain(chainID)
	if err != nil {
		return nil, err
	}
	return client.SubscribeLogs(ctx, addresses, topics)
}

func newEthClient(config *domain.ChainConfig) (ports.ChainClient, error) {
	return &mockEthClient{config: config}, nil
}

type mockEthClient struct {
	config *domain.ChainConfig
}

func (m *mockEthClient) GetChainID(ctx context.Context) (int64, error) {
	return m.config.ChainID, nil
}

func (m *mockEthClient) GetBlockNumber(ctx context.Context) (uint64, error) {
	return 1000000, nil
}

func (m *mockEthClient) GetBlockByNumber(ctx context.Context, blockNumber uint64) (*domain.BlockData, error) {
	return &domain.BlockData{
		Number:       blockNumber,
		Hash:         "0xmock",
		GasLimit:     30000000,
		GasUsed:      15000000,
		BaseFee:      "1000000000",
	}, nil
}

func (m *mockEthClient) GetBlockByHash(ctx context.Context, hash string) (*domain.BlockData, error) {
	return &domain.BlockData{
		Hash:     hash,
		GasLimit: 30000000,
	}, nil
}

func (m *mockEthClient) GetBalance(ctx context.Context, address string) (*big.Int, error) {
	return big.NewInt(1000000000000000000), nil
}

func (m *mockEthClient) GetNonce(ctx context.Context, address string) (uint64, error) {
	return 0, nil
}

func (m *mockEthClient) GetTransactionByHash(ctx context.Context, hash string) (*domain.TransactionData, error) {
	return &domain.TransactionData{
		Hash: hash,
	}, nil
}

func (m *mockEthClient) GetTransactionReceipt(ctx context.Context, hash string) (*domain.TransactionReceipt, error) {
	return &domain.TransactionReceipt{
		TransactionHash: hash,
		Status:          1,
	}, nil
}

func (m *mockEthClient) GetBaseFeePerGas(ctx context.Context) (*big.Int, error) {
	return big.NewInt(1000000000), nil
}

func (m *mockEthClient) GetMaxPriorityFeePerGas(ctx context.Context) (*big.Int, error) {
	return big.NewInt(1000000000), nil
}

func (m *mockEthClient) EstimateGas(ctx context.Context, to, data string, value *big.Int) (uint64, error) {
	return 21000, nil
}

func (m *mockEthClient) SendRawTransaction(ctx context.Context, rawTx string) (string, error) {
	return "0xtxhashmock", nil
}

func (m *mockEthClient) CallContract(ctx context.Context, to, data string) ([]byte, error) {
	return []byte{}, nil
}

func (m *mockEthClient) GetLogs(ctx context.Context, fromBlock, toBlock uint64, addresses []string, topics []string) ([]domain.LogData, error) {
	return []domain.LogData{}, nil
}

func (m *mockEthClient) SubscribeNewHeads(ctx context.Context) (<-chan *domain.BlockData, error) {
	ch := make(chan *domain.BlockData, 100)
	return ch, nil
}

func (m *mockEthClient) SubscribeLogs(ctx context.Context, addresses []string, topics []string) (<-chan *domain.LogData, error) {
	ch := make(chan *domain.LogData, 100)
	return ch, nil
}
