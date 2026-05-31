package domain

import (
	"time"
)

type BlockIndex struct {
	ID            string    `json:"id" gorm:"primaryKey"`
	ChainID       int64     `json:"chain_id" gorm:"index:idx_block_chain_number,unique"`
	BlockNumber   uint64    `json:"block_number" gorm:"index:idx_block_chain_number,unique"`
	BlockHash     string    `json:"block_hash" gorm:"uniqueIndex"`
	ParentHash    string    `json:"parent_hash"`
	Timestamp     time.Time `json:"timestamp" gorm:"index"`
	Difficulty    string    `json:"difficulty"`
	TotalDifficulty string `json:"total_difficulty"`
	GasLimit      uint64    `json:"gas_limit"`
	GasUsed       uint64    `json:"gas_used"`
	BaseFeePerGas string    `json:"base_fee_per_gas,omitempty"`
	Miner         string    `json:"miner"`
	ExtraData     string    `json:"extra_data,omitempty"`
	Size          uint64    `json:"size"`
	TxCount       int       `json:"tx_count"`
	LogCount      int       `json:"log_count"`
	Status        string    `json:"status" gorm:"index"`
	CreatedAt     time.Time `json:"created_at"`
	IndexedAt     time.Time `json:"indexed_at"`
}

type TransactionIndex struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	ChainID     int64                 `json:"chain_id" gorm:"index"`
	BlockNumber uint64                `json:"block_number" gorm:"index"`
	BlockHash   string                `json:"block_hash" gorm:"index"`
	TxHash      string                `json:"tx_hash" gorm:"uniqueIndex"`
	TxIndex     uint                  `json:"tx_index"`
	From        string                `json:"from" gorm:"index"`
	To          string                `json:"to" gorm:"index"`
	Value       string                `json:"value"`
	Gas         uint64                `json:"gas"`
	GasPrice    string                `json:"gas_price"`
	Input       string                `json:"input,omitempty"`
	Nonce       uint64                `json:"nonce"`
	Status      uint64                `json:"status"`
	GasUsed     uint64                `json:"gas_used"`
	LogCount    int                   `json:"log_count"`
	Timestamp   time.Time             `json:"timestamp" gorm:"index"`
	Metadata    map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type LogIndex struct {
	ID           string                 `json:"id" gorm:"primaryKey"`
	ChainID      int64                 `json:"chain_id" gorm:"index"`
	BlockNumber  uint64                `json:"block_number" gorm:"index"`
	BlockHash    string                `json:"block_hash" gorm:"index"`
	TxHash       string                `json:"tx_hash" gorm:"index"`
	TxIndex      uint                  `json:"tx_index"`
	LogIndex     uint                  `json:"log_index"`
	Address      string                `json:"address" gorm:"index"`
	Topics       []string              `json:"topics" gorm:"type:jsonb"`
	Data         string                `json:"data,omitempty"`
	EventName    string                `json:"event_name,omitempty" gorm:"index"`
	EventSig     string                `json:"event_sig,omitempty"`
	Decoded      map[string]interface{} `json:"decoded,omitempty" gorm:"type:jsonb"`
	Timestamp    time.Time             `json:"timestamp" gorm:"index"`
}

type IndexConfig struct {
	ChainID          int64    `json:"chain_id"`
	StartBlock       uint64   `json:"start_block"`
	EndBlock         *uint64  `json:"end_block,omitempty"`
	IndexBlocks      bool     `json:"index_blocks"`
	IndexTransactions bool   `json:"index_transactions"`
	IndexLogs        bool     `json:"index_logs"`
	ContractFilter   []string `json:"contract_filter,omitempty"`
	TopicFilter      []string `json:"topic_filter,omitempty"`
	Concurrency      int      `json:"concurrency"`
	BatchSize        int      `json:"batch_size"`
}

type IndexStatus struct {
	ChainID        int64     `json:"chain_id"`
	LatestBlock    uint64    `json:"latest_block"`
	IndexedBlock   uint64    `json:"indexed_block"`
	Status         string    `json:"status"`
	TotalBlocks    uint64    `json:"total_blocks"`
	TotalTxs       uint64    `json:"total_txs"`
	TotalLogs      uint64    `json:"total_logs"`
	LastIndexedAt  time.Time `json:"last_indexed_at"`
	Errors         int       `json:"errors"`
}

const (
	IndexStatusRunning = "running"
	IndexStatusPaused  = "paused"
	IndexStatusStopped = "stopped"
	IndexStatusError   = "error"
)
