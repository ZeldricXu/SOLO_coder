package domain

import (
	"time"
)

type HDWallet struct {
	ID             string                 `json:"id" gorm:"primaryKey"`
	Name           string                 `json:"name"`
	Mnemonic       string                 `json:"mnemonic,omitempty"`
	Seed           string                 `json:"seed,omitempty"`
	MasterKey      string                 `json:"master_key,omitempty"`
	DerivationPath string                 `json:"derivation_path"`
	CoinType       int                    `json:"coin_type"`
	Network        string                 `json:"network"`
	Status         string                 `json:"status" gorm:"index"`
	CreatedBy      string                 `json:"created_by"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
	Metadata       map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type DerivedAddress struct {
	ID              string                 `json:"id" gorm:"primaryKey"`
	WalletID        string                 `json:"wallet_id" gorm:"index"`
	Address         string                 `json:"address" gorm:"uniqueIndex"`
	PublicKey       string                 `json:"public_key,omitempty"`
	DerivationPath  string                 `json:"derivation_path"`
	Index           uint32                 `json:"index"`
	ChainCode       string                 `json:"chain_code,omitempty"`
	IsChange        bool                   `json:"is_change"`
	Status          string                 `json:"status" gorm:"index"`
	Balance         string                 `json:"balance,omitempty"`
	TransactionCount uint64                `json:"transaction_count"`
	FirstUsedAt     *time.Time             `json:"first_used_at,omitempty"`
	LastUsedAt      *time.Time             `json:"last_used_at,omitempty"`
	CreatedAt       time.Time              `json:"created_at"`
	UpdatedAt       time.Time              `json:"updated_at"`
	Metadata        map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type AddressBookEntry struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	Address     string                 `json:"address" gorm:"index"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	ChainID     int64                  `json:"chain_id" gorm:"index"`
	Tags        []string               `json:"tags" gorm:"type:jsonb"`
	Category    string                 `json:"category" gorm:"index"`
	CreatedBy   string                 `json:"created_by"`
	IsFavorite  bool                   `json:"is_favorite"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	Metadata    map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type DeriveAddressRequest struct {
	WalletID       string `json:"wallet_id" binding:"required"`
	DerivationPath string `json:"derivation_path"`
	Index          uint32 `json:"index"`
	Count          uint32 `json:"count"`
	IsChange       bool   `json:"is_change"`
}

type ImportAddressRequest struct {
	Address   string                 `json:"address" binding:"required"`
	PublicKey string                 `json:"public_key"`
	WalletID  string                 `json:"wallet_id"`
	Metadata  map[string]interface{} `json:"metadata"`
}

type AddAddressBookRequest struct {
	Address     string                 `json:"address" binding:"required"`
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	ChainID     int64                  `json:"chain_id" binding:"required"`
	Tags        []string               `json:"tags"`
	Category    string                 `json:"category"`
	IsFavorite  bool                   `json:"is_favorite"`
	Metadata    map[string]interface{} `json:"metadata"`
}

const (
	CoinTypeETH   = 60
	CoinTypeBTC   = 0
	CoinTypeSOL   = 501

	AddressStatusActive = "active"
	AddressStatusUnused = "unused"
	AddressStatusArchived = "archived"
)
