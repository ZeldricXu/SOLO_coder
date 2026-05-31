package domain

import (
	"math/big"
	"time"
)

type ChainConfig struct {
	ChainID   int64  `json:"chain_id"`
	Name      string `json:"name"`
	Symbol    string `json:"symbol"`
	Decimals  int    `json:"decimals"`
	RPCURL    string `json:"rpc_url"`
	WSURL     string `json:"ws_url"`
	Explorer  string `json:"explorer"`
	IsTestnet bool   `json:"is_testnet"`
}

type BlockData struct {
	Number       uint64    `json:"number"`
	Hash         string    `json:"hash"`
	ParentHash   string    `json:"parent_hash"`
	Timestamp    time.Time `json:"timestamp"`
	Difficulty   string    `json:"difficulty"`
	TotalDifficulty string `json:"total_difficulty"`
	GasLimit     uint64    `json:"gas_limit"`
	GasUsed      uint64    `json:"gas_used"`
	BaseFee      string    `json:"base_fee,omitempty"`
	Miner        string    `json:"miner"`
	ExtraData    string    `json:"extra_data"`
	Size         uint64    `json:"size"`
	TransactionCount int   `json:"transaction_count"`
}

type TransactionData struct {
	Hash              string `json:"hash"`
	BlockHash         string `json:"block_hash"`
	BlockNumber       uint64 `json:"block_number"`
	From              string `json:"from"`
	To                string `json:"to"`
	Value             string `json:"value"`
	Gas               uint64 `json:"gas"`
	GasPrice          string `json:"gas_price"`
	MaxFeePerGas      string `json:"max_fee_per_gas,omitempty"`
	MaxPriorityFeePerGas string `json:"max_priority_fee_per_gas,omitempty"`
	Input             string `json:"input"`
	Nonce             uint64 `json:"nonce"`
	TransactionIndex  uint   `json:"transaction_index"`
	Type              uint8  `json:"type"`
	Status            uint64 `json:"status,omitempty"`
	GasUsed           uint64 `json:"gas_used,omitempty"`
	CumulativeGasUsed uint64 `json:"cumulative_gas_used,omitempty"`
}

type LogData struct {
	Address          string   `json:"address"`
	Topics           []string `json:"topics"`
	Data             string   `json:"data"`
	BlockNumber      uint64   `json:"block_number"`
	TransactionHash  string   `json:"transaction_hash"`
	TransactionIndex uint     `json:"transaction_index"`
	BlockHash        string   `json:"block_hash"`
	LogIndex         uint     `json:"log_index"`
	Removed          bool     `json:"removed"`
}

type TransactionReceipt struct {
	TransactionHash   string   `json:"transaction_hash"`
	BlockHash         string   `json:"block_hash"`
	BlockNumber       uint64   `json:"block_number"`
	Status            uint64   `json:"status"`
	CumulativeGasUsed uint64   `json:"cumulative_gas_used"`
	GasUsed           uint64   `json:"gas_used"`
	ContractAddress   string   `json:"contract_address,omitempty"`
	Logs              []LogData `json:"logs"`
	LogsBloom         string   `json:"logs_bloom"`
}

type BalanceResponse struct {
	Address string   `json:"address"`
	Balance *big.Int `json:"balance"`
	Nonce   uint64   `json:"nonce"`
}
