package domain

import (
	"math/big"
	"time"
)

type BridgeTransaction struct {
	ID              string                 `json:"id" gorm:"primaryKey"`
	BridgeTxID      string                 `json:"bridge_tx_id" gorm:"uniqueIndex"`
	SourceChainID   int64                  `json:"source_chain_id" gorm:"index"`
	DestChainID     int64                  `json:"dest_chain_id" gorm:"index"`
	SourceTxHash    string                 `json:"source_tx_hash" gorm:"index"`
	DestTxHash      string                 `json:"dest_tx_hash,omitempty" gorm:"index"`
	SourceAddress   string                 `json:"source_address"`
	DestAddress     string                 `json:"dest_address"`
	Amount          string                 `json:"amount"`
	TokenAddress    string                 `json:"token_address"`
	TokenSymbol     string                 `json:"token_symbol"`
	FeeAmount       string                 `json:"fee_amount"`
	Status          string                 `json:"status" gorm:"index"`
	SourceBlockNum  uint64                 `json:"source_block_num"`
	DestBlockNum    uint64                 `json:"dest_block_num,omitempty"`
	Confirmations   int                    `json:"confirmations"`
	RequiredConfs   int                    `json:"required_confirmations"`
	MessageHash     string                 `json:"message_hash"`
	Signatures      []string               `json:"signatures" gorm:"type:jsonb"`
	AtomicSwapID    string                 `json:"atomic_swap_id,omitempty"`
	LockTime        *time.Time             `json:"lock_time,omitempty"`
	SecretHash      string                 `json:"secret_hash,omitempty"`
	Secret          string                 `json:"secret,omitempty"`
	Error           string                 `json:"error,omitempty"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
	CompletedAt     *time.Time             `json:"completed_at,omitempty"`
	Metadata        map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type BridgeConfig struct {
	SourceChainID   int64                  `json:"source_chain_id"`
	DestChainID     int64                  `json:"dest_chain_id"`
	BridgeContract  string                 `json:"bridge_contract"`
	TokenPairs      []TokenPair            `json:"token_pairs"`
	MinAmount       string                 `json:"min_amount"`
	MaxAmount       string                 `json:"max_amount"`
	FeePercent      float64                `json:"fee_percent"`
	RequiredConfs   int                    `json:"required_confirmations"`
	Relayers        []string               `json:"relayers"`
}

type TokenPair struct {
	SourceToken string `json:"source_token"`
	DestToken   string `json:"dest_token"`
	SourceSymbol string `json:"source_symbol"`
	DestSymbol   string `json:"dest_symbol"`
	Ratio       string `json:"ratio"`
}

type BridgeRequest struct {
	SourceChainID  int64    `json:"source_chain_id" binding:"required"`
	DestChainID    int64    `json:"dest_chain_id" binding:"required"`
	SourceAddress  string   `json:"source_address" binding:"required"`
	DestAddress    string   `json:"dest_address" binding:"required"`
	Amount         *big.Int `json:"amount" binding:"required"`
	TokenAddress   string   `json:"token_address"`
	UseAtomicSwap  bool     `json:"use_atomic_swap"`
	TimeoutSeconds int64    `json:"timeout_seconds"`
}

type BridgeProof struct {
	BridgeTxID  string   `json:"bridge_tx_id"`
	MessageHash string   `json:"message_hash"`
	Signatures  []string `json:"signatures"`
	BlockNumber uint64   `json:"block_number"`
	TxHash      string   `json:"tx_hash"`
}

const (
	BridgeStatusPending     = "pending"
	BridgeStatusLocked      = "locked"
	BridgeStatusConfirmed   = "confirmed"
	BridgeStatusMinting     = "minting"
	BridgeStatusCompleted   = "completed"
	BridgeStatusFailed      = "failed"
	BridgeStatusRefunded    = "refunded"
	BridgeStatusRefunding   = "refunding"
)
