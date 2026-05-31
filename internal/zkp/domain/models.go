package domain

import (
	"time"
)

type ZKPProof struct {
	ID          string                 `json:"id" gorm:"primaryKey"`
	ProofType   string                 `json:"proof_type" gorm:"index"`
	CircuitID   string                 `json:"circuit_id" gorm:"index"`
	ProofData   string                 `json:"proof_data"`
	PublicInput string                 `json:"public_input"`
	VerifyingKey string                `json:"verifying_key,omitempty"`
	Status      string                 `json:"status" gorm:"index"`
	Result      bool                   `json:"result"`
	VerifyTime  int64                  `json:"verify_time_ms"`
	Error       string                 `json:"error,omitempty"`
	CreatedAt   time.Time              `json:"created_at"`
	VerifiedAt  *time.Time             `json:"verified_at,omitempty"`
	Metadata    map[string]interface{} `json:"metadata,omitempty" gorm:"type:jsonb"`
}

type VerifyRequest struct {
	ProofType    string                 `json:"proof_type" binding:"required"`
	CircuitID    string                 `json:"circuit_id" binding:"required"`
	ProofData    string                 `json:"proof_data" binding:"required"`
	PublicInput  string                 `json:"public_input"`
	VerifyingKey string                 `json:"verifying_key"`
	Metadata     map[string]interface{} `json:"metadata"`
}

type VerifyResponse struct {
	ProofID  string `json:"proof_id"`
	Valid    bool   `json:"valid"`
	Verified bool   `json:"verified"`
	Error    string `json:"error,omitempty"`
}

type Circuit struct {
	ID            string                 `json:"id" gorm:"primaryKey"`
	Name          string                 `json:"name"`
	Version       string                 `json:"version"`
	CircuitType   string                 `json:"circuit_type"`
	VerifyingKey  string                 `json:"verifying_key"`
	ProvingKey    string                 `json:"proving_key,omitempty"`
	R1CS          string                 `json:"r1cs,omitempty"`
	Wasmm         string                 `json:"wasm,omitempty"`
	InputSchema   map[string]interface{} `json:"input_schema,omitempty" gorm:"type:jsonb"`
	Description   string                 `json:"description"`
	Status        string                 `json:"status" gorm:"index"`
	CreatedAt     time.Time              `json:"created_at"`
	UpdatedAt     time.Time              `json:"updated_at"`
}

const (
	ProofStatusPending  = "pending"
	ProofStatusVerified = "verified"
	ProofStatusFailed   = "failed"
	ProofStatusInvalid  = "invalid"

	ProofTypeGroth16 = "groth16"
	ProofTypePlonk   = "plonk"
	ProofTypeMarlin  = "marlin"
)
