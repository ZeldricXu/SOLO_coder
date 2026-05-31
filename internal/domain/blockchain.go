package domain

import (
	"math/big"
	"time"
)

type Block struct {
	Number       uint64
	Hash         []byte
	ParentHash   []byte
	Timestamp    time.Time
	Transactions []Transaction
	GasUsed      uint64
	GasLimit     uint64
	BaseFee      *big.Int
}

type ChainConfig struct {
	Name         string
	ChainID      int64
	RPCURLs      []string
	WSURL        string
	ExplorerURL  string
	NativeToken  string
	Confirmations int
}

type ChainSwitcher interface {
	SwitchChain(chainName string) error
	GetCurrentChain() string
}

type BlockReader interface {
	GetBlockByNumber(blockNumber uint64) (*Block, error)
	GetLatestBlock() (*Block, error)
	GetBlockByHash(blockHash []byte) (*Block, error)
}

type TransactionReader interface {
	GetTransactionByHash(txHash []byte) (*Transaction, error)
	GetTransactionReceipt(txHash []byte) (map[string]interface{}, error)
}

type TransactionWriter interface {
	SendTransaction(tx *Transaction) ([]byte, error)
}

type GasProvider interface {
	EstimateGas(tx *Transaction) (uint64, error)
	GetGasPrice() (*big.Int, error)
}

type AccountProvider interface {
	GetBalance(address string, blockNumber string) (*big.Int, error)
	GetNonce(address string, blockNumber string) (uint64, error)
}

type ContractCaller interface {
	CallContract(to string, data []byte, blockNumber string) ([]byte, error)
}

type BlockchainService interface {
	ChainSwitcher
	BlockReader
	TransactionReader
	TransactionWriter
	GasProvider
	AccountProvider
	ContractCaller
}

type BlockQuerier interface {
	BlockReader
	ChainSwitcher
}

type GasEstimationProvider interface {
	GasProvider
	ChainSwitcher
}

type TransactionBroadcaster interface {
	TransactionWriter
	ChainSwitcher
}
