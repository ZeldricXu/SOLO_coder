package domain

import (
	"crypto/ecdsa"
	"math/big"
)

type Transaction struct {
	ChainID    *big.Int
	Nonce      uint64
	GasPrice   *big.Int
	GasLimit   uint64
	To         []byte
	Value      *big.Int
	Data       []byte
	Signatures []Signature
}

type Signature struct {
	Signer string
	V, R, S *big.Int
	Weight uint32
}

type TransactionParams struct {
	To       []byte
	Value    *big.Int
	Data     []byte
	Nonce    uint64
	GasLimit uint64
	GasPrice *big.Int
}

type TransactionConstructor interface {
	Build(params TransactionParams) (*Transaction, error)
	Validate(tx *Transaction) error
}

type TransactionSigner interface {
	Sign(tx *Transaction, privateKey *ecdsa.PrivateKey, weight uint32) error
	VerifySignature(tx *Transaction, sig Signature) bool
}

type TransactionSerializer interface {
	Serialize(tx *Transaction) ([]byte, error)
	AddTimestamp(tx *Transaction) error
}

type GasOptimizer interface {
	OptimizeGas(tx *Transaction, baseFee *big.Int, priorityFee *big.Int) error
}

type TransactionService interface {
	TransactionConstructor
	TransactionSigner
	TransactionSerializer
	GasOptimizer
}
