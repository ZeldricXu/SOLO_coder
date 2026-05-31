package txbuilder

import (
	"context"
	"crypto/ecdsa"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/ethereum/go-ethereum/crypto"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
)

type TransactionRequest struct {
	ChainID           uint64                 `json:"chain_id"`
	From              string                 `json:"from"`
	To                string                 `json:"to"`
	Value             *big.Int               `json:"value"`
	Data              []byte                 `json:"data,omitempty"`
	GasLimit          uint64                 `json:"gas_limit,omitempty"`
	GasPrice          *big.Int               `json:"gas_price,omitempty"`
	MaxFeePerGas      *big.Int               `json:"max_fee_per_gas,omitempty"`
	MaxPriorityFeePerGas *big.Int            `json:"max_priority_fee_per_gas,omitempty"`
	Nonce             uint64                 `json:"nonce,omitempty"`
	AccessList        types.AccessList       `json:"access_list,omitempty"`
	GasOptimization   bool                   `json:"gas_optimization"`
	MultiSigConfig    *MultiSigConfig        `json:"multi_sig_config,omitempty"`
}

type MultiSigConfig struct {
	Threshold  int      `json:"threshold"`
	Signers    []string `json:"signers"`
	SafeAddress string  `json:"safe_address"`
}

type SignedTransaction struct {
	ChainID    uint64           `json:"chain_id"`
	TxHash     string           `json:"tx_hash"`
	RawTx      []byte           `json:"raw_tx"`
	From       string           `json:"from"`
	To         string           `json:"to"`
	Value      *big.Int         `json:"value"`
	GasUsed    uint64           `json:"gas_used"`
	GasPrice   *big.Int         `json:"gas_price"`
	Signatures [][]byte         `json:"signatures,omitempty"`
}

type Signer interface {
	Sign(ctx context.Context, hash common.Hash) ([]byte, error)
	Address() common.Address
}

type LocalSigner struct {
	privateKey *ecdsa.PrivateKey
	address    common.Address
}

func NewLocalSigner(privateKeyHex string) (*LocalSigner, error) {
	privateKey, err := crypto.HexToECDSA(privateKeyHex)
	if err != nil {
		return nil, fmt.Errorf("invalid private key: %w", err)
	}

	publicKey := privateKey.Public()
	publicKeyECDSA, ok := publicKey.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("invalid public key")
	}

	return &LocalSigner{
		privateKey: privateKey,
		address:    crypto.PubkeyToAddress(*publicKeyECDSA),
	}, nil
}

func (s *LocalSigner) Sign(ctx context.Context, hash common.Hash) ([]byte, error) {
	signature, err := crypto.Sign(hash.Bytes(), s.privateKey)
	if err != nil {
		return nil, fmt.Errorf("signing failed: %w", err)
	}
	return signature, nil
}

func (s *LocalSigner) Address() common.Address {
	return s.address
}

type TransactionBuilder struct {
	signers      map[string]Signer
	signersMutex sync.RWMutex
	batchBuilder *BatchBuilder
	requestBatcher *RequestBatcher
}

func NewTransactionBuilder() *TransactionBuilder {
	tb := &TransactionBuilder{
		signers: make(map[string]Signer),
	}
	tb.batchBuilder = NewBatchBuilder(tb)
	return tb
}

func (tb *TransactionBuilder) RegisterSigner(address string, signer Signer) {
	tb.signersMutex.Lock()
	defer tb.signersMutex.Unlock()
	tb.signers[address] = signer
}

func (tb *TransactionBuilder) BuildTransaction(ctx context.Context, req TransactionRequest) (*types.Transaction, error) {
	if req.ChainID == 0 {
		return nil, errors.New(400, "chain_id is required")
	}

	if req.GasOptimization {
		req = tb.optimizeGas(req)
	}

	var tx *types.Transaction

	if req.MaxFeePerGas != nil && req.MaxPriorityFeePerGas != nil {
		tx = types.NewTx(&types.DynamicFeeTx{
			ChainID:   big.NewInt(int64(req.ChainID)),
			Nonce:     req.Nonce,
			GasTipCap: req.MaxPriorityFeePerGas,
			GasFeeCap: req.MaxFeePerGas,
			Gas:       req.GasLimit,
			To:        tb.toAddressPtr(req.To),
			Value:     req.Value,
			Data:      req.Data,
			AccessList: req.AccessList,
		})
	} else {
		tx = types.NewTx(&types.LegacyTx{
			Nonce:    req.Nonce,
			GasPrice: req.GasPrice,
			Gas:      req.GasLimit,
			To:       tb.toAddressPtr(req.To),
			Value:    req.Value,
			Data:     req.Data,
		})
	}

	return tx, nil
}

func (tb *TransactionBuilder) SignTransaction(ctx context.Context, tx *types.Transaction, signerAddress string) (*SignedTransaction, error) {
	tb.signersMutex.RLock()
	signer, exists := tb.signers[signerAddress]
	tb.signersMutex.RUnlock()

	if !exists {
		return nil, errors.New(404, "signer not found", signerAddress)
	}

	chainID := tx.ChainId()
	signer2 := types.NewEIP155Signer(chainID)

	signature, err := signer.Sign(ctx, signer2.Hash(tx))
	if err != nil {
		return nil, errors.Wrap(err, "signing failed")
	}

	signedTx, err := tx.WithSignature(signer2, signature)
	if err != nil {
		return nil, errors.Wrap(err, "failed to apply signature")
	}

	rawTx, err := signedTx.MarshalBinary()
	if err != nil {
		return nil, errors.Wrap(err, "failed to marshal transaction")
	}

	return &SignedTransaction{
		ChainID:  chainID.Uint64(),
		TxHash:   signedTx.Hash().Hex(),
		RawTx:    rawTx,
		From:     signerAddress,
		To:       signedTx.To().Hex(),
		Value:    signedTx.Value(),
		GasUsed:  signedTx.Gas(),
		GasPrice: signedTx.GasPrice(),
		Signatures: [][]byte{signature},
	}, nil
}

func (tb *TransactionBuilder) SignMultiSig(ctx context.Context, tx *types.Transaction, req TransactionRequest) (*SignedTransaction, error) {
	if req.MultiSigConfig == nil {
		return nil, errors.New(400, "multi-sig config is required")
	}

	config := req.MultiSigConfig
	if len(config.Signers) < config.Threshold {
		return nil, errors.New(400, "not enough signers for threshold")
	}

	signatures := make([][]byte, 0, config.Threshold)
	chainID := tx.ChainId()
	signer2 := types.NewEIP155Signer(chainID)
	txHash := signer2.Hash(tx)

	for _, signerAddr := range config.Signers {
		tb.signersMutex.RLock()
		signer, exists := tb.signers[signerAddr]
		tb.signersMutex.RUnlock()

		if !exists {
			logger.Log.Warn("signer not registered, skipping", zap.String("signer", signerAddr))
			continue
		}

		signature, err := signer.Sign(ctx, txHash)
		if err != nil {
			logger.Log.Warn("signing failed, skipping", zap.String("signer", signerAddr), zap.Error(err))
			continue
		}

		signatures = append(signatures, signature)

		if len(signatures) >= config.Threshold {
			break
		}
	}

	if len(signatures) < config.Threshold {
		return nil, errors.New(400, "not enough valid signatures collected")
	}

	signedTx, err := tx.WithSignature(signer2, signatures[0])
	if err != nil {
		return nil, errors.Wrap(err, "failed to apply signature")
	}

	rawTx, err := signedTx.MarshalBinary()
	if err != nil {
		return nil, errors.Wrap(err, "failed to marshal transaction")
	}

	return &SignedTransaction{
		ChainID:    chainID.Uint64(),
		TxHash:     signedTx.Hash().Hex(),
		RawTx:      rawTx,
		From:       config.SafeAddress,
		To:         signedTx.To().Hex(),
		Value:      signedTx.Value(),
		GasUsed:    signedTx.Gas(),
		GasPrice:   signedTx.GasPrice(),
		Signatures: signatures,
	}, nil
}

func (tb *TransactionBuilder) EstimateGasLimit(ctx context.Context, req TransactionRequest, estimator GasEstimatorInterface) (uint64, error) {
	if req.GasLimit > 0 {
		return req.GasLimit, nil
	}

	baseGas := uint64(21000)
	if len(req.Data) > 0 {
		for _, b := range req.Data {
			if b == 0 {
				baseGas += 4
			} else {
				baseGas += 16
			}
		}
	}

	gasEstimate, err := estimator.EstimateGas(ctx, req.ChainID)
	if err != nil {
		return baseGas + 10000, nil
	}

	return baseGas + (gasEstimate.Standard / 1000000000), nil
}

type GasEstimatorInterface interface {
	EstimateGas(ctx context.Context, chainID uint64) (interface{}, error)
}

func (tb *TransactionBuilder) optimizeGas(req TransactionRequest) TransactionRequest {
	if req.GasPrice != nil {
		buffered := new(big.Int).Mul(req.GasPrice, big.NewInt(110))
		req.GasPrice = new(big.Int).Div(buffered, big.NewInt(100))
	}
	if req.MaxFeePerGas != nil {
		buffered := new(big.Int).Mul(req.MaxFeePerGas, big.NewInt(110))
		req.MaxFeePerGas = new(big.Int).Div(buffered, big.NewInt(100))
	}
	if req.MaxPriorityFeePerGas != nil {
		buffered := new(big.Int).Mul(req.MaxPriorityFeePerGas, big.NewInt(110))
		req.MaxPriorityFeePerGas = new(big.Int).Div(buffered, big.NewInt(100))
	}
	return req
}

func (tb *TransactionBuilder) toAddressPtr(to string) *common.Address {
	if to == "" {
		return nil
	}
	addr := common.HexToAddress(to)
	return &addr
}

func (tb *TransactionBuilder) BuildBatch(ctx context.Context, batchReq BatchRequest) (*BatchResult, error) {
	return tb.batchBuilder.BuildBatch(ctx, batchReq)
}

func (tb *TransactionBuilder) SignBatch(ctx context.Context, txs []*types.Transaction, signerAddresses []string) (*BatchResult, error) {
	return tb.batchBuilder.SignBatch(ctx, txs, signerAddresses)
}

func (tb *TransactionBuilder) StartRequestBatcher(batchSize int, batchTimeout time.Duration) {
	if tb.requestBatcher != nil {
		tb.requestBatcher.Stop()
	}

	processor := func(ctx context.Context, requests []*QueuedRequest) ([]BatchResultItem, error) {
		txReqs := make([]TransactionRequest, len(requests))
		for i, r := range requests {
			txReqs[i] = r.Request
		}

		result, err := tb.batchBuilder.BuildBatch(ctx, BatchRequest{
			Requests: txReqs,
			Options: BatchOptions{
				MaxBatchSize: batchSize,
				Timeout:      batchTimeout,
				FailOnError:  false,
			},
		})

		if err != nil {
			return nil, err
		}

		return result.Results, nil
	}

	tb.requestBatcher = NewRequestBatcher(batchSize, batchTimeout, processor)
	tb.requestBatcher.Start()
}

func (tb *TransactionBuilder) StopRequestBatcher() {
	if tb.requestBatcher != nil {
		tb.requestBatcher.Stop()
		tb.requestBatcher = nil
	}
}

func (tb *TransactionBuilder) SubmitBatchedRequest(ctx context.Context, req TransactionRequest) (BatchResultItem, error) {
	if tb.requestBatcher == nil {
		tx, err := tb.BuildTransaction(ctx, req)
		if err != nil {
			return BatchResultItem{}, err
		}
		return BatchResultItem{
			Success: true,
			Data:    tx,
			TxHash:  tx.Hash().Hex(),
		}, nil
	}
	return tb.requestBatcher.Submit(ctx, req)
}

func (tb *TransactionBuilder) GetBatcherStats() *BatcherStats {
	if tb.requestBatcher == nil {
		return nil
	}
	stats := tb.requestBatcher.GetStats()
	return &stats
}
