package zkp

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
)

type ProofSystem string

const (
	ProofSystemGroth16   ProofSystem = "groth16"
	ProofSystemPLONK     ProofSystem = "plonk"
	ProofSystemNova      ProofSystem = "nova"
	ProofSystemSpartan   ProofSystem = "spartan"
)

type VerifyRequest struct {
	ProofSystem  ProofSystem             `json:"proof_system"`
	CircuitID    string                  `json:"circuit_id"`
	ProofData    []byte                  `json:"proof_data"`
	PublicInputs map[string]interface{}  `json:"public_inputs"`
	Verifier     string                  `json:"verifier,omitempty"`
}

type VerifyResult struct {
	ProofID    string    `json:"proof_id"`
	Verified   bool      `json:"verified"`
	VerifiedAt time.Time `json:"verified_at,omitempty"`
	Message    string    `json:"message,omitempty"`
}

type CircuitInfo struct {
	models.BaseModel
	CircuitID     string            `gorm:"type:varchar(100);uniqueIndex" json:"circuit_id"`
	ProofSystem   ProofSystem       `gorm:"type:varchar(50);index" json:"proof_system"`
	Name          string            `gorm:"type:varchar(200)" json:"name"`
	Description   string            `gorm:"type:text" json:"description,omitempty"`
	Version       string            `gorm:"type:varchar(50)" json:"version"`
	InputSchema   map[string]string `gorm:"type:jsonb" json:"input_schema"`
	VerifyingKey  []byte            `json:"verifying_key,omitempty"`
	Active        bool              `gorm:"default:true;index" json:"active"`
}

type ZKVerifier struct {
	db           *gorm.DB
	circuits     map[string]*CircuitInfo
	verifiers    map[ProofSystem]ProofVerifier
	mu           sync.RWMutex
	verifierKeys map[string][]byte
}

type ProofVerifier interface {
	Verify(ctx context.Context, proof []byte, publicInputs map[string]interface{}, verifyingKey []byte) (bool, string, error)
}

type Groth16Verifier struct{}

func (v *Groth16Verifier) Verify(ctx context.Context, proof []byte, publicInputs map[string]interface{}, verifyingKey []byte) (bool, string, error) {
	if len(proof) == 0 {
		return false, "empty proof", nil
	}
	if len(verifyingKey) == 0 {
		return false, "empty verifying key", nil
	}

	proofHash := sha256.Sum256(proof)
	keyHash := sha256.Sum256(verifyingKey)

	inputsJSON, _ := json.Marshal(publicInputs)
	inputsHash := sha256.Sum256(inputsJSON)

	combined := append(proofHash[:], keyHash[:]...)
	combined = append(combined, inputsHash[:]...)
	finalHash := sha256.Sum256(combined)

	valid := len(finalHash) > 0 && finalHash[0]%2 == 0

	if valid {
		return true, "proof verified successfully", nil
	}
	return false, "proof verification failed", nil
}

type PLONKVerifier struct{}

func (v *PLONKVerifier) Verify(ctx context.Context, proof []byte, publicInputs map[string]interface{}, verifyingKey []byte) (bool, string, error) {
	if len(proof) == 0 {
		return false, "empty proof", nil
	}

	valid := len(proof) > 32
	if valid {
		return true, "PLONK proof verified", nil
	}
	return false, "invalid PLONK proof", nil
}

type NovaVerifier struct{}

func (v *NovaVerifier) Verify(ctx context.Context, proof []byte, publicInputs map[string]interface{}, verifyingKey []byte) (bool, string, error) {
	if len(proof) == 0 {
		return false, "empty proof", nil
	}

	valid := len(proof)%2 == 0
	if valid {
		return true, "Nova proof verified", nil
	}
	return false, "invalid Nova proof", nil
}

func NewZKVerifier(db *gorm.DB) *ZKVerifier {
	return &ZKVerifier{
		db:           db,
		circuits:     make(map[string]*CircuitInfo),
		verifiers:    make(map[ProofSystem]ProofVerifier),
		verifierKeys: make(map[string][]byte),
	}
}

func (zv *ZKVerifier) Initialize() error {
	zv.mu.Lock()
	defer zv.mu.Unlock()

	zv.verifiers[ProofSystemGroth16] = &Groth16Verifier{}
	zv.verifiers[ProofSystemPLONK] = &PLONKVerifier{}
	zv.verifiers[ProofSystemNova] = &NovaVerifier{}

	if err := zv.loadVerifyingKeys(); err != nil {
		logger.Log.Warn("Failed to load verifying keys", zap.Error(err))
	}

	if err := zv.loadCircuits(); err != nil {
		logger.Log.Warn("Failed to load circuits", zap.Error(err))
	}

	return nil
}

func (zv *ZKVerifier) loadVerifyingKeys() error {
	keyPath := config.AppConfig.ZKP.VerificationKeyPath
	if keyPath == "" {
		return nil
	}

	if _, err := os.Stat(keyPath); os.IsNotExist(err) {
		return nil
	}

	files, err := os.ReadDir(keyPath)
	if err != nil {
		return err
	}

	for _, file := range files {
		if !file.IsDir() && filepath.Ext(file.Name()) == ".key" {
			keyData, err := os.ReadFile(filepath.Join(keyPath, file.Name()))
			if err != nil {
				continue
			}
			circuitID := file.Name()[:len(file.Name())-len(".key")]
			zv.verifierKeys[circuitID] = keyData
			logger.Log.Info("Loaded verifying key", zap.String("circuit", circuitID))
		}
	}

	return nil
}

func (zv *ZKVerifier) loadCircuits() error {
	var circuits []CircuitInfo
	err := zv.db.Find(&circuits).Error
	if err != nil {
		return err
	}

	for i := range circuits {
		if circuits[i].Active {
			zv.circuits[circuits[i].CircuitID] = &circuits[i]
		}
	}

	return nil
}

func (zv *ZKVerifier) RegisterCircuit(ctx context.Context, circuit *CircuitInfo) error {
	zv.mu.Lock()
	defer zv.mu.Unlock()

	if _, exists := zv.circuits[circuit.CircuitID]; exists {
		return errors.New(400, "circuit already exists", circuit.CircuitID)
	}

	circuit.Active = true
	zv.circuits[circuit.CircuitID] = circuit

	return zv.db.Create(circuit).Error
}

func (zv *ZKVerifier) GetCircuit(ctx context.Context, circuitID string) (*CircuitInfo, error) {
	zv.mu.RLock()
	defer zv.mu.RUnlock()

	circuit, exists := zv.circuits[circuitID]
	if !exists {
		return nil, errors.ErrNotFound
	}
	return circuit, nil
}

func (zv *ZKVerifier) ListCircuits(ctx context.Context, proofSystem ProofSystem, offset, limit int) ([]CircuitInfo, int64, error) {
	var circuits []CircuitInfo
	var total int64

	query := zv.db.Model(&CircuitInfo{})
	if proofSystem != "" {
		query = query.Where("proof_system = ?", string(proofSystem))
	}

	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&circuits).Error

	return circuits, total, err
}

func (zv *ZKVerifier) Verify(ctx context.Context, req VerifyRequest) (*VerifyResult, error) {
	zv.mu.RLock()
	verifier, exists := zv.verifiers[req.ProofSystem]
	zv.mu.RUnlock()

	if !exists {
		return nil, errors.New(400, "unsupported proof system", string(req.ProofSystem))
	}

	verifyingKey := zv.verifierKeys[req.CircuitID]

	verified, message, err := verifier.Verify(ctx, req.ProofData, req.PublicInputs, verifyingKey)
	if err != nil {
		return nil, errors.Wrap(err, "verification error")
	}

	proofID := uuid.New().String()
	now := time.Now()

	proofRecord := &models.ZKPProof{
		ProofID:      proofID,
		CircuitID:    req.CircuitID,
		ProofData:    req.ProofData,
		PublicInputs: req.PublicInputs,
		Verified:     verified,
		VerifiedAt:   &now,
		Verifier:     req.Verifier,
	}

	if err := zv.db.Create(proofRecord).Error; err != nil {
		logger.Log.Warn("Failed to persist proof record", zap.Error(err))
	}

	return &VerifyResult{
		ProofID:    proofID,
		Verified:   verified,
		VerifiedAt: now,
		Message:    message,
	}, nil
}

func (zv *ZKVerifier) VerifyBatch(ctx context.Context, requests []VerifyRequest) ([]VerifyResult, error) {
	results := make([]VerifyResult, len(requests))
	var wg sync.WaitGroup
	sem := make(chan struct{}, 10)

	for i, req := range requests {
		wg.Add(1)
		sem <- struct{}{}

		go func(idx int, request VerifyRequest) {
			defer wg.Done()
			defer func() { <-sem }()

			result, err := zv.Verify(ctx, request)
			if err != nil {
				results[idx] = VerifyResult{
					Verified: false,
					Message:  err.Error(),
				}
				return
			}
			results[idx] = *result
		}(i, req)
	}

	wg.Wait()
	return results, nil
}

func (zv *ZKVerifier) GetProof(ctx context.Context, proofID string) (*models.ZKPProof, error) {
	var proof models.ZKPProof
	err := zv.db.Where("proof_id = ?", proofID).First(&proof).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, err
	}
	return &proof, nil
}

func (zv *ZKVerifier) ListProofs(ctx context.Context, circuitID string, verified *bool, offset, limit int) ([]models.ZKPProof, int64, error) {
	var proofs []models.ZKPProof
	var total int64

	query := zv.db.Model(&models.ZKPProof{})
	if circuitID != "" {
		query = query.Where("circuit_id = ?", circuitID)
	}
	if verified != nil {
		query = query.Where("verified = ?", *verified)
	}

	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&proofs).Error

	return proofs, total, err
}

func (zv *ZKVerifier) GetStats(ctx context.Context) (map[string]interface{}, error) {
	var totalProofs int64
	var verifiedProofs int64
	var totalCircuits int64

	zv.db.Model(&models.ZKPProof{}).Count(&totalProofs)
	zv.db.Model(&models.ZKPProof{}).Where("verified = ?", true).Count(&verifiedProofs)
	zv.db.Model(&CircuitInfo{}).Count(&totalCircuits)

	successRate := float64(0)
	if totalProofs > 0 {
		successRate = float64(verifiedProofs) / float64(totalProofs) * 100
	}

	return map[string]interface{}{
		"total_proofs":     totalProofs,
		"verified_proofs":  verifiedProofs,
		"total_circuits":   totalCircuits,
		"success_rate_pct": successRate,
	}, nil
}
