package domain

import (
	"time"
)

type Signer struct {
	Address   string    `json:"address"`
	PublicKey string    `json:"public_key,omitempty"`
	Weight    int       `json:"weight"`
	AddedAt   time.Time `json:"added_at"`
}

type Signature struct {
	Signer    string    `json:"signer"`
	Signature string    `json:"signature"`
	SignedAt  time.Time `json:"signed_at"`
}

type Proposal struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	WalletID    string                 `json:"wallet_id" gorm:"index"`
	Title       string                 `json:"title"`
	Description string                 `json:"description"`
	Type        string                 `json:"type" gorm:"index"`
	Status      string                 `json:"status" gorm:"index"`
	ChainID     int64                  `json:"chain_id"`
	ToAddress   string                 `json:"to_address"`
	Value       string                 `json:"value"`
	Data        string                 `json:"data"`
	Nonce       uint64                 `json:"nonce"`
	GasLimit    uint64                 `json:"gas_limit"`
	Signatures  []Signature            `json:"signatures" gorm:"type:jsonb"`
	Threshold   int                    `json:"threshold"`
	TotalWeight int                    `json:"total_weight"`
	ExecutedAt  *time.Time             `json:"executed_at"`
	ExpiresAt   *time.Time             `json:"expires_at"`
	TxHash      string                 `json:"tx_hash,omitempty"`
	CreatedBy   string                 `json:"created_by"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
}

type Wallet struct {
	ID           string                 `json:"id" gorm:"primaryKey"`
	Name         string                 `json:"name"`
	ChainID      int64                  `json:"chain_id"`
	Address      string                 `json:"address" gorm:"uniqueIndex"`
	Signers      []Signer               `json:"signers" gorm:"type:jsonb"`
	Threshold    int                    `json:"threshold"`
	TotalWeight  int                    `json:"total_weight"`
	Nonce        uint64                 `json:"nonce"`
	Status       string                 `json:"status" gorm:"index"`
	Creator      string                 `json:"creator"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
	Metadata     map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
}

type CreateProposalRequest struct {
	WalletID    string                 `json:"wallet_id" binding:"required"`
	Title       string                 `json:"title" binding:"required"`
	Description string                 `json:"description"`
	Type        string                 `json:"type" binding:"required"`
	ToAddress   string                 `json:"to_address" binding:"required"`
	Value       string                 `json:"value" binding:"required"`
	Data        string                 `json:"data"`
	GasLimit    uint64                 `json:"gas_limit"`
	ExpiresAt   *time.Time             `json:"expires_at"`
	Metadata    map[string]interface{} `json:"metadata"`
}

type SignProposalRequest struct {
	ProposalID string `json:"proposal_id" binding:"required"`
	Signer     string `json:"signer" binding:"required"`
	Signature  string `json:"signature" binding:"required"`
}

type ExecuteProposalRequest struct {
	ProposalID string `json:"proposal_id" binding:"required"`
	Executor   string `json:"executor"`
}

const (
	ProposalStatusPending   = "pending"
	ProposalStatusApproved  = "approved"
	ProposalStatusRejected  = "rejected"
	ProposalStatusExecuted  = "executed"
	ProposalStatusFailed    = "failed"
	ProposalStatusCancelled = "cancelled"
	ProposalStatusExpired   = "expired"

	WalletStatusActive  = "active"
	WalletStatusPaused  = "paused"
	WalletStatusClosed  = "closed"
)
