package models

import (
	"time"

	"gorm.io/gorm"
	"github.com/google/uuid"
)

type BaseModel struct {
	ID        string         `gorm:"primaryKey;type:varchar(36)" json:"id"`
	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
	DeletedAt gorm.DeletedAt `gorm:"index" json:"deleted_at,omitempty"`
}

func (b *BaseModel) BeforeCreate(tx *gorm.DB) error {
	if b.ID == "" {
		b.ID = uuid.New().String()
	}
	return nil
}

type Entity struct {
	BaseModel
	Type       string                 `gorm:"type:varchar(50);index" json:"type"`
	Status     string                 `gorm:"type:varchar(50);index" json:"status"`
	Attributes map[string]interface{} `gorm:"type:jsonb" json:"attributes"`
}

type Config struct {
	BaseModel
	ConfigID   string                 `gorm:"type:varchar(100);uniqueIndex" json:"config_id"`
	Namespace  string                 `gorm:"type:varchar(100);index" json:"namespace"`
	Version    int                    `json:"version"`
	Parameters map[string]interface{} `gorm:"type:jsonb" json:"parameters"`
	Enabled    bool                   `json:"enabled"`
	AppliedAt  *time.Time             `json:"applied_at,omitempty"`
}

type RunInstance struct {
	BaseModel
	RunID      string     `gorm:"type:varchar(100);uniqueIndex" json:"run_id"`
	EntityID   string     `gorm:"type:varchar(36);index" json:"entity_id"`
	Phase      string     `gorm:"type:varchar(50);index" json:"phase"`
	Progress   float64    `json:"progress"`
	StartedAt  time.Time  `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	ErrorDetail *string    `gorm:"type:text" json:"error_detail,omitempty"`
}

type StatsSnapshot struct {
	BaseModel
	SnapshotID string                 `gorm:"type:varchar(100);uniqueIndex" json:"snapshot_id"`
	Timestamp  time.Time              `gorm:"index" json:"timestamp"`
	Metrics    map[string]interface{} `gorm:"type:jsonb" json:"metrics"`
	Dimensions map[string]string      `gorm:"type:jsonb" json:"dimensions"`
}

type GasPriceRecord struct {
	BaseModel
	ChainID    uint64    `gorm:"index" json:"chain_id"`
	BlockNumber uint64   `gorm:"index" json:"block_number"`
	BlockTime  time.Time `gorm:"index" json:"block_time"`
	Low        uint64    `json:"low"`
	Average    uint64    `json:"average"`
	High       uint64    `json:"high"`
	BaseFee    uint64    `json:"base_fee,omitempty"`
}

type AddressBook struct {
	BaseModel
	Address    string            `gorm:"type:varchar(100);uniqueIndex" json:"address"`
	Label      string            `gorm:"type:varchar(100);index" json:"label"`
	Tags       map[string]string `gorm:"type:jsonb" json:"tags"`
	ChainID    uint64            `gorm:"index" json:"chain_id"`
	DerivationPath string        `gorm:"type:varchar(100)" json:"derivation_path,omitempty"`
	Note       string            `gorm:"type:text" json:"note,omitempty"`
}

type EventSubscription struct {
	BaseModel
	ChainID        uint64                 `gorm:"index" json:"chain_id"`
	ContractAddress string                `gorm:"type:varchar(100);index" json:"contract_address"`
	EventSignature string                 `gorm:"type:varchar(256);index" json:"event_signature"`
	CallbackURL    string                 `gorm:"type:varchar(500)" json:"callback_url"`
	FromBlock      uint64                 `json:"from_block"`
	LastProcessedBlock uint64             `json:"last_processed_block"`
	Filters        map[string]interface{} `gorm:"type:jsonb" json:"filters"`
	Active         bool                   `json:"active"`
}

type IndexedBlock struct {
	BaseModel
	ChainID     uint64    `gorm:"index:idx_chain_block,unique" json:"chain_id"`
	BlockNumber uint64    `gorm:"index:idx_chain_block,unique;index" json:"block_number"`
	BlockHash   string    `gorm:"type:varchar(100);index" json:"block_hash"`
	ParentHash  string    `gorm:"type:varchar(100)" json:"parent_hash"`
	BlockTime   time.Time `gorm:"index" json:"block_time"`
	TxCount     int       `json:"tx_count"`
	GasUsed     uint64    `json:"gas_used"`
	GasLimit    uint64    `json:"gas_limit"`
	Size        int       `json:"size"`
	Indexed     bool      `gorm:"default:false;index" json:"indexed"`
}

type IndexedTransaction struct {
	BaseModel
	ChainID       uint64 `gorm:"index" json:"chain_id"`
	BlockNumber   uint64 `gorm:"index" json:"block_number"`
	TxHash        string `gorm:"type:varchar(100);uniqueIndex" json:"tx_hash"`
	FromAddress   string `gorm:"type:varchar(100);index" json:"from_address"`
	ToAddress     string `gorm:"type:varchar(100);index" json:"to_address"`
	Value         string `gorm:"type:varchar(100)" json:"value"`
	Gas           uint64 `json:"gas"`
	GasPrice      uint64 `json:"gas_price"`
	GasUsed       uint64 `json:"gas_used"`
	Nonce         uint64 `json:"nonce"`
	Data          []byte `json:"data,omitempty"`
	Status        int    `json:"status"`
}

type ZKPProof struct {
	BaseModel
	ProofID     string                 `gorm:"type:varchar(100);uniqueIndex" json:"proof_id"`
	CircuitID   string                 `gorm:"type:varchar(100);index" json:"circuit_id"`
	ProofData   []byte                 `json:"proof_data"`
	PublicInputs map[string]interface{} `gorm:"type:jsonb" json:"public_inputs"`
	Verified    bool                   `gorm:"index" json:"verified"`
	VerifiedAt  *time.Time             `json:"verified_at,omitempty"`
	Verifier    string                 `gorm:"type:varchar(100)" json:"verifier,omitempty"`
}

type StoredContent struct {
	BaseModel
	ContentID   string            `gorm:"type:varchar(100);uniqueIndex" json:"content_id"`
	StorageType string            `gorm:"type:varchar(50);index" json:"storage_type"`
	CID         string            `gorm:"type:varchar(256);index" json:"cid"`
	Size        int64             `json:"size"`
	Pinned      bool              `gorm:"default:true;index" json:"pinned"`
	PinDuration int64             `json:"pin_duration,omitempty"`
	Metadata    map[string]string `gorm:"type:jsonb" json:"metadata"`
}
