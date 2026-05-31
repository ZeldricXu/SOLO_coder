package models

import (
	"math/big"
	"time"
)

// 核心实体模型
type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey"`
	Type       string                 `json:"type"`
	Status     string                 `json:"status"`
	Attributes map[string]interface{} `json:"attributes" gorm:"serializer:json"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

// 配置定义模型
type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey"`
	Namespace  string                 `json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `json:"parameters" gorm:"serializer:json"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  time.Time              `json:"applied_at"`
}

// 运行实例模型
type RunInstance struct {
	RunID       string    `json:"run_id" gorm:"primaryKey"`
	EntityID    string    `json:"entity_id"`
	Phase       string    `json:"phase"`
	Progress    float64   `json:"progress"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string    `json:"error_detail,omitempty"`
}

// 统计快照模型
type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey"`
	Timestamp  time.Time              `json:"timestamp"`
	Metrics    map[string]float64     `json:"metrics" gorm:"serializer:json"`
	Dimensions map[string]string      `json:"dimensions" gorm:"serializer:json"`
}

// 交易数据结构
type Transaction struct {
	ChainID   *big.Int
	Nonce     uint64
	GasPrice  *big.Int
	GasLimit  uint64
	To        []byte
	Value     *big.Int
	Data      []byte
	Signatures []Signature
}

// 签名数据
type Signature struct {
	Signer   string
	V, R, S  *big.Int
	Weight   uint32
}

// HD钱包地址
type WalletAddress struct {
	Address    string
	DerivationPath string
	PublicKey  []byte
	Index      uint32
	Label      string
	Tags       []string
}

// 链上区块数据
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

// 多签提案
type MultisigProposal struct {
	ID              string
	Transaction     *Transaction
	RequiredSigners int
	Status          string
	Signatures      []Signature
	CreatedAt       time.Time
	ExecutedAt      *time.Time
}

// Gas费用预估结果
type GasEstimate struct {
	ChainID        string
	GasLimit       uint64
	BaseFee        *big.Int
	PriorityFee    *big.Int
	MaxFee         *big.Int
	EstimatedTotal *big.Int
	Confidence     float64
	Timestamp      time.Time
}

// 跨链桥接消息
type BridgeMessage struct {
	ID              string
	SourceChain     string
	DestinationChain string
	SourceAddress   string
	DestAddress     string
	Amount          *big.Int
	TokenAddress    string
	Nonce           uint64
	Status          string
	Proof           []byte
	CreatedAt       time.Time
}

// 去中心化存储内容
type StorageContent struct {
	CID        string
	Data       []byte
	Size       uint64
	PinStatus  string
	Networks   []string
	Timestamp  time.Time
}
