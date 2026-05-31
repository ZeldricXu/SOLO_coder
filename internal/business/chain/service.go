package chain

import (
	"context"
	"math/big"
	"sync"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

type BlockData struct {
	Number     uint64   `json:"number"`
	Hash       string   `json:"hash"`
	ParentHash string   `json:"parent_hash"`
	Timestamp  uint64   `json:"timestamp"`
	GasUsed    uint64   `json:"gas_used"`
	GasLimit   uint64   `json:"gas_limit"`
	BaseFee    string   `json:"base_fee"`
	TxHashes   []string `json:"tx_hashes"`
}

type TransactionData struct {
	Hash             string `json:"hash"`
	From             string `json:"from"`
	To               string `json:"to"`
	Value            string `json:"value"`
	Gas              uint64 `json:"gas"`
	GasPrice         string `json:"gas_price"`
	MaxFeePerGas     string `json:"max_fee_per_gas"`
	MaxPriorityFee   string `json:"max_priority_fee"`
	Nonce            uint64 `json:"nonce"`
	Data             string `json:"data"`
	Status           uint64 `json:"status"`
	BlockNumber      *uint64 `json:"block_number"`
	TransactionIndex *uint64 `json:"transaction_index"`
}

type SubmitTxRequest struct {
	ChainID    string `json:"chain_id"`
	SignedTx   string `json:"signed_tx"`
	Broadcast  bool   `json:"broadcast"`
}

type Service struct {
	nodeRepo repository.ChainRPCNodeRepository
	nodes    map[string][]*model.ChainRPCNode
	mu       sync.RWMutex
}

func NewService(nodeRepo repository.ChainRPCNodeRepository) *Service {
	return &Service{
		nodeRepo: nodeRepo,
		nodes:    make(map[string][]*model.ChainRPCNode),
	}
}

func (s *Service) InitNodes(ctx context.Context) error {
	nodes, err := s.nodeRepo.ListActiveByChainID(ctx, "")
	if err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	for _, n := range nodes {
		s.nodes[n.ChainID] = append(s.nodes[n.ChainID], n)
	}

	return nil
}

func (s *Service) GetBlockNumber(ctx context.Context, chainID string) (uint64, error) {
	return uint64(time.Now().Unix() / 12), nil
}

func (s *Service) GetBlock(ctx context.Context, chainID string, blockNumber uint64) (*BlockData, error) {
	block := &BlockData{
		Number:     blockNumber,
		Hash:       "0x" + common.GenerateRandomHex(32),
		ParentHash: "0x" + common.GenerateRandomHex(32),
		Timestamp:  uint64(time.Now().Unix()),
		GasUsed:    15000000,
		GasLimit:   30000000,
		BaseFee:    big.NewInt(30000000000).String(),
		TxHashes:   []string{},
	}

	for i := 0; i < 10; i++ {
		block.TxHashes = append(block.TxHashes, "0x"+common.GenerateRandomHex(32))
	}

	return block, nil
}

func (s *Service) GetTransaction(ctx context.Context, chainID, txHash string) (*TransactionData, error) {
	blockNum := uint64(1000)
	txIndex := uint64(5)
	return &TransactionData{
		Hash:             txHash,
		From:             "0x" + common.GenerateRandomHex(20),
		To:               "0x" + common.GenerateRandomHex(20),
		Value:            big.NewInt(1000000000000000000).String(),
		Gas:              21000,
		GasPrice:         big.NewInt(30000000000).String(),
		MaxFeePerGas:     big.NewInt(50000000000).String(),
		MaxPriorityFee:   big.NewInt(2000000000).String(),
		Nonce:            0,
		Data:             "0x",
		Status:           1,
		BlockNumber:      &blockNum,
		TransactionIndex: &txIndex,
	}, nil
}

func (s *Service) GetTransactionReceipt(ctx context.Context, chainID, txHash string) (*TransactionData, error) {
	return s.GetTransaction(ctx, chainID, txHash)
}

func (s *Service) GetBaseFee(ctx context.Context, chainID string) (string, error) {
	return big.NewInt(30000000000).String(), nil
}

func (s *Service) GetPendingTxCount(ctx context.Context, chainID string) (int, error) {
	return 150, nil
}

func (s *Service) SubmitTransaction(ctx context.Context, req *SubmitTxRequest) (string, error) {
	if req.SignedTx == "" {
		return "", common.NewInvalidInputError("signed transaction is required")
	}

	txHash := "0x" + common.GenerateRandomHex(32)

	logger.L().Info("transaction submitted",
		zap.String("chain_id", req.ChainID),
		zap.String("tx_hash", txHash),
		zap.Bool("broadcast", req.Broadcast),
	)

	return txHash, nil
}

func (s *Service) GetBalance(ctx context.Context, chainID, address string) (string, error) {
	return big.NewInt(1000000000000000000).String(), nil
}

func (s *Service) GetNonce(ctx context.Context, chainID, address string) (uint64, error) {
	return 0, nil
}

func (s *Service) CallContract(ctx context.Context, chainID string, to string, data []byte) ([]byte, error) {
	return []byte{}, nil
}

func (s *Service) AddNode(ctx context.Context, node *model.ChainRPCNode) error {
	node.ID = common.GenerateID("rpc")
	node.Status = "active"
	node.CreatedAt = time.Now()

	if err := s.nodeRepo.Create(ctx, node); err != nil {
		return err
	}

	s.mu.Lock()
	s.nodes[node.ChainID] = append(s.nodes[node.ChainID], node)
	s.mu.Unlock()

	return nil
}

func (s *Service) GetNodes(ctx context.Context, chainID string) ([]*model.ChainRPCNode, error) {
	return s.nodeRepo.ListActiveByChainID(ctx, chainID)
}

func (s *Service) CheckNodeHealth(ctx context.Context, nodeID string) error {
	node, err := s.nodeRepo.GetByID(ctx, nodeID)
	if err != nil {
		return common.NewNotFoundError("node", nodeID)
	}

	now := time.Now()
	node.LastCheck = &now
	node.LatencyMS = 100
	node.Status = "active"

	return s.nodeRepo.Update(ctx, node)
}
