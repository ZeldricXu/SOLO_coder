package domain

import (
	"math/big"
	"time"
)

type Transaction struct {
	ID              string                 `json:"id" gorm:"primaryKey"`
	ChainID         int64                  `json:"chain_id"`
	Hash            string                 `json:"hash,omitempty" gorm:"index"`
	From            string                 `json:"from"`
	To              string                 `json:"to"`
	Value           string                 `json:"value"`
	Data            string                 `json:"data"`
	Nonce           uint64                 `json:"nonce"`
	GasLimit        uint64                 `json:"gas_limit"`
	GasPrice        string                 `json:"gas_price,omitempty"`
	MaxFeePerGas    string                 `json:"max_fee_per_gas,omitempty"`
	PriorityFee     string                 `json:"priority_fee,omitempty"`
	Type            int                    `json:"type"`
	Status          string                 `json:"status" gorm:"index"`
	BlockNumber     uint64                 `json:"block_number,omitempty"`
	BlockHash       string                 `json:"block_hash,omitempty"`
	Signature       string                 `json:"signature,omitempty"`
	RawTx           string                 `json:"raw_tx,omitempty"`
	MultisigWallet  string                 `json:"multisig_wallet,omitempty"`
	Signatures      []string               `json:"signatures,omitempty" gorm:"type:jsonb"`
	GasUsed         uint64                 `json:"gas_used,omitempty"`
	EffectiveGasPrice string                `json:"effective_gas_price,omitempty"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
	ExecutedAt      *time.Time             `json:"executed_at,omitempty"`
	Error           string                 `json:"error,omitempty"`
	Metadata        map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type BuildRequest struct {
	ChainID        int64                  `json:"chain_id" binding:"required"`
	From           string                 `json:"from" binding:"required"`
	To             string                 `json:"to"`
	Value          string                 `json:"value"`
	Data           string                 `json:"data"`
	GasLimit       uint64                 `json:"gas_limit"`
	GasPrice       string                 `json:"gas_price"`
	MaxFeePerGas   string                 `json:"max_fee_per_gas"`
	PriorityFee    string                 `json:"priority_fee"`
	Nonce          *uint64                `json:"nonce"`
	Optimization   GasOptimization        `json:"optimization"`
	Metadata       map[string]interface{} `json:"metadata"`
}

type GasOptimization struct {
	Enabled       bool   `json:"enabled"`
	Strategy      string `json:"strategy"`
	TargetConfirm int    `json:"target_confirm"`
	MaxWaitTime   int    `json:"max_wait_time"`
}

type SignRequest struct {
	TxID      string `json:"tx_id" binding:"required"`
	Signer    string `json:"signer" binding:"required"`
	Signature string `json:"signature" binding:"required"`
}

type BroadcastResponse struct {
	TxID   string `json:"tx_id"`
	Hash   string `json:"hash"`
	Status string `json:"status"`
}

const (
	TxStatusPending   = "pending"
	TxStatusSigned    = "signed"
	TxStatusBroadcast = "broadcast"
	TxStatusConfirmed = "confirmed"
	TxStatusFailed    = "failed"
	TxStatusRejected  = "rejected"

	TxTypeLegacy     = 0
	TxTypeAccessList = 1
	TxTypeDynamic    = 2
)

type MultisigPolicy struct {
	Threshold int      `json:"threshold"`
	Signers   []string `json:"signers"`
}
