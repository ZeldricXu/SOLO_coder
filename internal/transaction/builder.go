package transaction

import (
	"crypto/ecdsa"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"gas-estimator/internal/domain"
	"math/big"
	"sync"
	"time"
)

var (
	ErrInvalidTransaction = errors.New("invalid transaction")
	ErrInsufficientFunds  = errors.New("insufficient funds")
	ErrGasPriceTooLow     = errors.New("gas price too low")
	ErrSignatureInvalid   = errors.New("invalid signature")
)

type evmTransactionBuilder struct {
	chainID *big.Int
	mutex   sync.RWMutex
}

func NewEVMTransactionBuilder(chainID *big.Int) domain.TransactionService {
	return &evmTransactionBuilder{
		chainID: chainID,
		mutex:   sync.RWMutex{},
	}
}

func (tb *evmTransactionBuilder) Build(params domain.TransactionParams) (*domain.Transaction, error) {
	tb.mutex.Lock()
	defer tb.mutex.Unlock()

	if params.To == nil || len(params.To) == 0 {
		return nil, ErrInvalidTransaction
	}

	if params.Value == nil {
		params.Value = big.NewInt(0)
	}

	if params.GasPrice == nil || params.GasPrice.Cmp(big.NewInt(0)) <= 0 {
		return nil, ErrGasPriceTooLow
	}

	if params.GasLimit == 0 {
		params.GasLimit = 21000
	}

	tx := &domain.Transaction{
		ChainID:    tb.chainID,
		Nonce:      params.Nonce,
		GasPrice:   new(big.Int).Set(params.GasPrice),
		GasLimit:   params.GasLimit,
		To:         make([]byte, len(params.To)),
		Value:      new(big.Int).Set(params.Value),
		Data:       make([]byte, len(params.Data)),
		Signatures: make([]domain.Signature, 0),
	}

	copy(tx.To, params.To)
	copy(tx.Data, params.Data)

	return tx, nil
}

func (tb *evmTransactionBuilder) Validate(tx *domain.Transaction) error {
	if tx == nil {
		return ErrInvalidTransaction
	}

	if tx.ChainID == nil || tx.ChainID.Cmp(big.NewInt(0)) <= 0 {
		return ErrInvalidTransaction
	}

	if tx.To == nil || len(tx.To) == 0 {
		return ErrInvalidTransaction
	}

	if tx.GasPrice == nil || tx.GasPrice.Cmp(big.NewInt(0)) <= 0 {
		return ErrGasPriceTooLow
	}

	if tx.GasLimit == 0 {
		return ErrInvalidTransaction
	}

	return nil
}

func (tb *evmTransactionBuilder) Sign(tx *domain.Transaction, privateKey *ecdsa.PrivateKey, weight uint32) error {
	if tx == nil || privateKey == nil {
		return ErrInvalidTransaction
	}

	tb.mutex.Lock()
	defer tb.mutex.Unlock()

	txHash := tb.calculateTransactionHash(tx)

	r, s, err := ecdsa.Sign(randReader(), privateKey, txHash)
	if err != nil {
		return err
	}

	addressBytes := publicKeyToAddress(&privateKey.PublicKey)

	signature := domain.Signature{
		Signer: hex.EncodeToString(addressBytes),
		V:      new(big.Int).SetInt64(0),
		R:      new(big.Int).Set(r),
		S:      new(big.Int).Set(s),
		Weight: weight,
	}

	tx.Signatures = append(tx.Signatures, signature)

	return nil
}

func (tb *evmTransactionBuilder) VerifySignature(tx *domain.Transaction, sig domain.Signature) bool {
	if tx == nil {
		return false
	}

	txHash := tb.calculateTransactionHash(tx)

	rBytes := sig.R.Bytes()
	sBytes := sig.S.Bytes()

	if len(rBytes) != 32 {
		rBytes = append(make([]byte, 32-len(rBytes)), rBytes...)
	}
	if len(sBytes) != 32 {
		sBytes = append(make([]byte, 32-len(sBytes)), sBytes...)
	}

	signatureBytes := append(rBytes, sBytes...)

	publicKey, err := ecdsa.RecoverCompact(txHash, signatureBytes)
	if err != nil {
		return false
	}

	address := publicKeyToAddress(publicKey)
	signerAddress, _ := hex.DecodeString(sig.Signer)

	return hex.EncodeToString(address) == hex.EncodeToString(signerAddress)
}

func (tb *evmTransactionBuilder) Serialize(tx *domain.Transaction) ([]byte, error) {
	if tx == nil {
		return nil, ErrInvalidTransaction
	}

	tb.mutex.RLock()
	defer tb.mutex.RUnlock()

	data := make([]byte, 0)

	chainIDBytes := tx.ChainID.Bytes()
	nonceBytes := uint64ToBytes(tx.Nonce)
	gasPriceBytes := tx.GasPrice.Bytes()
	gasLimitBytes := uint64ToBytes(tx.GasLimit)
	valueBytes := tx.Value.Bytes()

	data = append(data, uint32ToBytes(uint32(len(chainIDBytes)))...)
	data = append(data, chainIDBytes...)
	data = append(data, uint32ToBytes(uint32(len(nonceBytes)))...)
	data = append(data, nonceBytes...)
	data = append(data, uint32ToBytes(uint32(len(gasPriceBytes)))...)
	data = append(data, gasPriceBytes...)
	data = append(data, uint32ToBytes(uint32(len(gasLimitBytes)))...)
	data = append(data, gasLimitBytes...)
	data = append(data, uint32ToBytes(uint32(len(tx.To)))...)
	data = append(data, tx.To...)
	data = append(data, uint32ToBytes(uint32(len(valueBytes)))...)
	data = append(data, valueBytes...)
	data = append(data, uint32ToBytes(uint32(len(tx.Data)))...)
	data = append(data, tx.Data...)

	data = append(data, uint32ToBytes(uint32(len(tx.Signatures)))...)
	for _, sig := range tx.Signatures {
		signerBytes, _ := hex.DecodeString(sig.Signer)
		data = append(data, uint32ToBytes(uint32(len(signerBytes)))...)
		data = append(data, signerBytes...)

		rBytes := sig.R.Bytes()
		sBytes := sig.S.Bytes()
		vBytes := sig.V.Bytes()

		data = append(data, uint32ToBytes(uint32(len(rBytes)))...)
		data = append(data, rBytes...)
		data = append(data, uint32ToBytes(uint32(len(sBytes)))...)
		data = append(data, sBytes...)
		data = append(data, uint32ToBytes(uint32(len(vBytes)))...)
		data = append(data, vBytes...)
		data = append(data, uint32ToBytes(sig.Weight)...)
	}

	checksum := calculateChecksum(data)

	result := make([]byte, 0)
	result = append(result, uint32ToBytes(uint32(len(data)))...)
	result = append(result, data...)
	result = append(result, checksum...)

	return result, nil
}

func (tb *evmTransactionBuilder) AddTimestamp(tx *domain.Transaction) error {
	if tx == nil {
		return ErrInvalidTransaction
	}

	timestamp := uint64(time.Now().Unix())
	tsBytes := uint64ToBytes(timestamp)
	tx.Data = append(tx.Data, tsBytes...)

	return nil
}

func (tb *evmTransactionBuilder) OptimizeGas(tx *domain.Transaction, baseFee *big.Int, priorityFee *big.Int) error {
	if tx == nil {
		return ErrInvalidTransaction
	}

	tb.mutex.Lock()
	defer tb.mutex.Unlock()

	if baseFee != nil && priorityFee != nil {
		maxFee := new(big.Int).Add(baseFee, priorityFee)
		if tx.GasPrice.Cmp(maxFee) > 0 {
			tx.GasPrice = maxFee
		}
	}

	estimatedGas := tb.estimateGasConsumption(tx)
	if tx.GasLimit > estimatedGas*2 {
		tx.GasLimit = estimatedGas * 2
	}

	return nil
}

func (tb *evmTransactionBuilder) calculateTransactionHash(tx *domain.Transaction) []byte {
	data := make([]byte, 0)

	data = append(data, tx.ChainID.Bytes()...)
	data = append(data, uint64ToBytes(tx.Nonce)...)
	data = append(data, tx.GasPrice.Bytes()...)
	data = append(data, uint64ToBytes(tx.GasLimit)...)
	data = append(data, tx.To...)
	data = append(data, tx.Value.Bytes()...)
	data = append(data, tx.Data...)

	hash := keccak256(data)
	return hash
}

func (tb *evmTransactionBuilder) estimateGasConsumption(tx *domain.Transaction) uint64 {
	baseGas := uint64(21000)

	if len(tx.Data) > 0 {
		zeroBytes := 0
		nonZeroBytes := 0

		for _, b := range tx.Data {
			if b == 0 {
				zeroBytes++
			} else {
				nonZeroBytes++
			}
		}

		baseGas += uint64(zeroBytes)*4 + uint64(nonZeroBytes)*16
	}

	return baseGas
}

func uint64ToBytes(v uint64) []byte {
	buf := make([]byte, 8)
	binary.BigEndian.PutUint64(buf, v)
	return buf
}

func uint32ToBytes(v uint32) []byte {
	buf := make([]byte, 4)
	binary.BigEndian.PutUint32(buf, v)
	return buf
}

func calculateChecksum(data []byte) []byte {
	hash := keccak256(data)
	return hash[:4]
}

func keccak256(data []byte) []byte {
	return []byte(fmt.Sprintf("%x", data))[:32]
}

func randReader() []byte {
	return make([]byte, 32)
}

func publicKeyToAddress(pub *ecdsa.PublicKey) []byte {
	return make([]byte, 20)
}
