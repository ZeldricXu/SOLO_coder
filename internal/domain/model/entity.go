package model

import (
	"encoding/json"
	"time"
)

type Entity struct {
	ID         string                 `json:"id" gorm:"primaryKey;type:varchar(64)"`
	Type       string                 `json:"type" gorm:"type:varchar(32);index"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	Attributes map[string]interface{} `json:"attributes" gorm:"type:jsonb"`
	CreatedAt  time.Time              `json:"created_at"`
	UpdatedAt  time.Time              `json:"updated_at"`
}

func (e Entity) TableName() string { return "entities" }

type Config struct {
	ConfigID   string                 `json:"config_id" gorm:"primaryKey;type:varchar(64)"`
	Namespace  string                 `json:"namespace" gorm:"type:varchar(64);index"`
	Version    int64                  `json:"version" gorm:"index"`
	Parameters map[string]interface{} `json:"parameters" gorm:"type:jsonb"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at"`
}

func (c Config) TableName() string { return "configs" }

type RunInstance struct {
	RunID        string                 `json:"run_id" gorm:"primaryKey;type:varchar(64)"`
	EntityID     string                 `json:"entity_id" gorm:"type:varchar(64);index"`
	Phase        string                 `json:"phase" gorm:"type:varchar(32);index"`
	Progress     float64                `json:"progress"`
	StartedAt    time.Time              `json:"started_at"`
	CompletedAt  *time.Time             `json:"completed_at"`
	ErrorDetail  map[string]interface{} `json:"error_detail" gorm:"type:jsonb"`
}

func (r RunInstance) TableName() string { return "run_instances" }

type Snapshot struct {
	SnapshotID string                 `json:"snapshot_id" gorm:"primaryKey;type:varchar(64)"`
	Timestamp  time.Time              `json:"timestamp" gorm:"index"`
	Metrics    map[string]float64     `json:"metrics" gorm:"type:jsonb"`
	Dimensions map[string]string      `json:"dimensions" gorm:"type:jsonb"`
}

func (s Snapshot) TableName() string { return "snapshots" }

type ZKPProof struct {
	ID            string          `json:"id" gorm:"primaryKey;type:varchar(64)"`
	CircuitID     string          `json:"circuit_id" gorm:"type:varchar(64);index"`
	ProofData     json.RawMessage `json:"proof_data" gorm:"type:jsonb"`
	PublicInputs  json.RawMessage `json:"public_inputs" gorm:"type:jsonb"`
	VerificationKey string        `json:"verification_key" gorm:"type:text"`
	Verified      bool            `json:"verified"`
	VerifyResult  string          `json:"verify_result" gorm:"type:varchar(32)"`
	VerifiedAt    *time.Time      `json:"verified_at"`
	CreatedAt     time.Time       `json:"created_at"`
}

func (z ZKPProof) TableName() string { return "zkp_proofs" }

type Transaction struct {
	ID              string          `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ChainID         string          `json:"chain_id" gorm:"type:varchar(32);index"`
	FromAddress     string          `json:"from_address" gorm:"type:varchar(64);index"`
	ToAddress       string          `json:"to_address" gorm:"type:varchar(64);index"`
	Value           string          `json:"value"`
	Data            []byte          `json:"data"`
	Nonce           uint64          `json:"nonce"`
	GasLimit        uint64          `json:"gas_limit"`
	GasPrice        string          `json:"gas_price"`
	MaxFeePerGas    string          `json:"max_fee_per_gas"`
	MaxPriorityFee  string          `json:"max_priority_fee"`
	Status          string          `json:"status" gorm:"type:varchar(32);index"`
	TxHash          string          `json:"tx_hash" gorm:"type:varchar(128);index"`
	Signatures      json.RawMessage `json:"signatures" gorm:"type:jsonb"`
	MultisigID      string          `json:"multisig_id" gorm:"type:varchar(64);index"`
	BlockNumber     *uint64         `json:"block_number"`
	CreatedAt       time.Time       `json:"created_at"`
}

func (t Transaction) TableName() string { return "transactions" }

type MultisigProposal struct {
	ID              string          `json:"id" gorm:"primaryKey;type:varchar(64)"`
	WalletID        string          `json:"wallet_id" gorm:"type:varchar(64);index"`
	TransactionID   string          `json:"transaction_id" gorm:"type:varchar(64);index"`
	Status          string          `json:"status" gorm:"type:varchar(32);index"`
	Threshold       uint32          `json:"threshold"`
	Signatures      json.RawMessage `json:"signatures" gorm:"type:jsonb"`
	Signers         []string        `json:"signers" gorm:"type:text[]"`
	ApprovedCount   uint32          `json:"approved_count"`
	ExecutedAt      *time.Time      `json:"executed_at"`
	ExpiresAt       *time.Time      `json:"expires_at"`
	CreatedAt       time.Time       `json:"created_at"`
}

func (m MultisigProposal) TableName() string { return "multisig_proposals" }

type ContractEvent struct {
	ID              string          `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ChainID         string          `json:"chain_id" gorm:"type:varchar(32);index"`
	ContractAddress string          `json:"contract_address" gorm:"type:varchar(64);index"`
	EventName       string          `json:"event_name" gorm:"type:varchar(64);index"`
	BlockNumber     uint64          `json:"block_number"`
	TxHash          string          `json:"tx_hash" gorm:"type:varchar(128);index"`
	LogIndex        uint64          `json:"log_index"`
	Topics          []string        `json:"topics" gorm:"type:text[]"`
	Data            json.RawMessage `json:"data" gorm:"type:jsonb"`
	Processed       bool            `json:"processed"`
	ProcessedAt     *time.Time      `json:"processed_at"`
	CreatedAt       time.Time       `json:"created_at"`
}

func (c ContractEvent) TableName() string { return "contract_events" }

type CrossChainTransfer struct {
	ID               string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	SourceChainID    string    `json:"source_chain_id" gorm:"type:varchar(32);index"`
	DestChainID      string    `json:"dest_chain_id" gorm:"type:varchar(32);index"`
	Asset            string    `json:"asset" gorm:"type:varchar(64);index"`
	Amount           string    `json:"amount"`
	Sender           string    `json:"sender" gorm:"type:varchar(64);index"`
	Recipient        string    `json:"recipient" gorm:"type:varchar(64);index"`
	LockTxHash       string    `json:"lock_tx_hash" gorm:"type:varchar(128);index"`
	MintTxHash       string    `json:"mint_tx_hash" gorm:"type:varchar(128);index"`
	Status           string    `json:"status" gorm:"type:varchar(32);index"`
	LockedAt         *time.Time `json:"locked_at"`
	MintedAt         *time.Time `json:"minted_at"`
	AtomicProof      json.RawMessage `json:"atomic_proof" gorm:"type:jsonb"`
	CreatedAt        time.Time `json:"created_at"`
}

func (c CrossChainTransfer) TableName() string { return "cross_chain_transfers" }

type GasEstimate struct {
	ID              string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ChainID         string    `json:"chain_id" gorm:"type:varchar(32);index"`
	ContractAddress string    `json:"contract_address" gorm:"type:varchar(64);index"`
	MethodSig       string    `json:"method_sig" gorm:"type:varchar(32);index"`
	EstimatedGas    uint64    `json:"estimated_gas"`
	GasPriceLow     string    `json:"gas_price_low"`
	GasPriceAvg     string    `json:"gas_price_avg"`
	GasPriceHigh    string    `json:"gas_price_high"`
	PriorityFeeLow  string    `json:"priority_fee_low"`
	PriorityFeeAvg  string    `json:"priority_fee_avg"`
	PriorityFeeHigh string    `json:"priority_fee_high"`
	Confidence      float64   `json:"confidence"`
	HistoricalData  json.RawMessage `json:"historical_data" gorm:"type:jsonb"`
	NetworkStatus   json.RawMessage `json:"network_status" gorm:"type:jsonb"`
	CreatedAt       time.Time `json:"created_at"`
}

func (g GasEstimate) TableName() string { return "gas_estimates" }

type ChainRPCNode struct {
	ID         string   `json:"id" gorm:"primaryKey;type:varchar(64)"`
	ChainID    string   `json:"chain_id" gorm:"type:varchar(32);index"`
	RPCURL     string   `json:"rpc_url"`
	WSURL      string   `json:"ws_url"`
	NetworkID  uint64   `json:"network_id"`
	Status     string   `json:"status" gorm:"type:varchar(32);index"`
	Priority   int32    `json:"priority"`
	LatencyMS  int64    `json:"latency_ms"`
	LastCheck  *time.Time `json:"last_check"`
	RetryCount int32    `json:"retry_count"`
	CreatedAt  time.Time `json:"created_at"`
}

func (c ChainRPCNode) TableName() string { return "chain_rpc_nodes" }

type HDWallet struct {
	ID            string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	UserID        string    `json:"user_id" gorm:"type:varchar(64);index"`
	Name          string    `json:"name" gorm:"type:varchar(128)"`
	ChainCode     []byte    `json:"chain_code"`
	MasterPubKey  []byte    `json:"master_pub_key"`
	DerivationPath string   `json:"derivation_path" gorm:"type:varchar(64)"`
	EncryptedSeed []byte    `json:"encrypted_seed"`
	CreatedAt     time.Time `json:"created_at"`
}

func (h HDWallet) TableName() string { return "hd_wallets" }

type DerivedAddress struct {
	ID            string    `json:"id" gorm:"primaryKey;type:varchar(64)"`
	WalletID      string    `json:"wallet_id" gorm:"type:varchar(64);index"`
	Address       string    `json:"address" gorm:"type:varchar(128);index"`
	AddressIndex  uint32    `json:"address_index"`
	DerivationPath string   `json:"derivation_path" gorm:"type:varchar(128)"`
	ChainID       string    `json:"chain_id" gorm:"type:varchar(32);index"`
	Labels        []string  `json:"labels" gorm:"type:text[]"`
	IsChange      bool      `json:"is_change"`
	Balance       string    `json:"balance"`
	LastSync      *time.Time `json:"last_sync"`
	CreatedAt     time.Time `json:"created_at"`
}

func (d DerivedAddress) TableName() string { return "derived_addresses" }
