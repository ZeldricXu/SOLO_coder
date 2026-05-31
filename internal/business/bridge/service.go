package bridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sync"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

type LockAssetRequest struct {
	SourceChainID string   `json:"source_chain_id"`
	DestChainID   string   `json:"dest_chain_id"`
	Asset         string   `json:"asset"`
	Amount        string   `json:"amount"`
	Sender        string   `json:"sender"`
	Recipient     string   `json:"recipient"`
	LockTxHash    string   `json:"lock_tx_hash"`
}

type MintAssetRequest struct {
	TransferID string `json:"transfer_id"`
	MintTxHash string `json:"mint_tx_hash"`
	Proof      string `json:"proof"`
}

type AtomicProof struct {
	TransferID  string            `json:"transfer_id"`
	LockTxHash  string            `json:"lock_tx_hash"`
	SourceChain string            `json:"source_chain"`
	DestChain   string            `json:"dest_chain"`
	Signatures  []string          `json:"signatures"`
	Hash        string            `json:"hash"`
}

type Service struct {
	transferRepo repository.CrossChainTransferRepository
	verifier     interface {
		Verify(ctx context.Context, proof string) (bool, error)
	}
	pendingTransfers map[string]*model.CrossChainTransfer
	mu               sync.RWMutex
}

func NewService(transferRepo repository.CrossChainTransferRepository) *Service {
	return &Service{
		transferRepo:     transferRepo,
		pendingTransfers: make(map[string]*model.CrossChainTransfer),
	}
}

func (s *Service) SetVerifier(v interface{}) { s.verifier = v }

func (s *Service) LockAsset(ctx context.Context, req *LockAssetRequest) (*model.CrossChainTransfer, error) {
	transfer := &model.CrossChainTransfer{
		ID:            common.GenerateID("cct"),
		SourceChainID: req.SourceChainID,
		DestChainID:   req.DestChainID,
		Asset:         req.Asset,
		Amount:        req.Amount,
		Sender:        req.Sender,
		Recipient:     req.Recipient,
		LockTxHash:    req.LockTxHash,
		Status:        "locked",
		CreatedAt:     time.Now(),
	}

	now := time.Now()
	transfer.LockedAt = &now

	proof := s.generateAtomicProof(transfer)
	proofBytes, _ := json.Marshal(proof)
	transfer.AtomicProof = proofBytes

	if err := s.transferRepo.Create(ctx, transfer); err != nil {
		logger.L().Error("failed to create cross-chain transfer", zap.Error(err))
		return nil, common.NewInternalError("failed to lock asset")
	}

	s.mu.Lock()
	s.pendingTransfers[transfer.ID] = transfer
	s.mu.Unlock()

	logger.L().Info("asset locked for cross-chain transfer",
		zap.String("transfer_id", transfer.ID),
		zap.String("source", req.SourceChainID),
		zap.String("dest", req.DestChainID),
		zap.String("asset", req.Asset),
	)

	return transfer, nil
}

func (s *Service) MintAsset(ctx context.Context, req *MintAssetRequest) (*model.CrossChainTransfer, error) {
	transfer, err := s.transferRepo.GetByID(ctx, req.TransferID)
	if err != nil {
		return nil, common.NewNotFoundError("transfer", req.TransferID)
	}

	if transfer.Status != "locked" && transfer.Status != "pending_mint" {
		return nil, common.NewConflictError(req.TransferID, "transfer not in valid state for minting")
	}

	if s.verifier != nil {
		verified, err := s.verifier.Verify(ctx, req.Proof)
		if err != nil || !verified {
			return nil, common.NewInvalidInputError("invalid cross-chain proof")
		}
	}

	transfer.MintTxHash = req.MintTxHash
	transfer.Status = "completed"
	now := time.Now()
	transfer.MintedAt = &now

	if err := s.transferRepo.Update(ctx, transfer); err != nil {
		logger.L().Error("failed to update transfer", zap.Error(err))
		return nil, common.NewInternalError("failed to mint asset")
	}

	s.mu.Lock()
	delete(s.pendingTransfers, transfer.ID)
	s.mu.Unlock()

	logger.L().Info("cross-chain transfer completed",
		zap.String("transfer_id", transfer.ID),
	)

	return transfer, nil
}

func (s *Service) VerifyAtomicity(ctx context.Context, transferID string) (bool, error) {
	transfer, err := s.transferRepo.GetByID(ctx, transferID)
	if err != nil {
		return false, common.NewNotFoundError("transfer", transferID)
	}

	var proof AtomicProof
	if err := json.Unmarshal(transfer.AtomicProof, &proof); err != nil {
		return false, common.NewInternalError("invalid atomic proof format")
	}

	computedHash := s.computeProofHash(transfer)
	if computedHash != proof.Hash {
		return false, nil
	}

	return transfer.LockTxHash == proof.LockTxHash &&
		transfer.SourceChainID == proof.SourceChain &&
		transfer.DestChainID == proof.DestChain, nil
}

func (s *Service) generateAtomicProof(transfer *model.CrossChainTransfer) *AtomicProof {
	proof := &AtomicProof{
		TransferID:  transfer.ID,
		LockTxHash:  transfer.LockTxHash,
		SourceChain: transfer.SourceChainID,
		DestChain:   transfer.DestChainID,
		Signatures:  []string{},
	}
	proof.Hash = s.computeProofHash(transfer)
	return proof
}

func (s *Service) computeProofHash(transfer *model.CrossChainTransfer) string {
	data := transfer.ID + transfer.LockTxHash + transfer.SourceChainID +
		transfer.DestChainID + transfer.Asset + transfer.Amount
	h := sha256.Sum256([]byte(data))
	return hex.EncodeToString(h[:])
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.CrossChainTransfer, error) {
	transfer, err := s.transferRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("transfer", id)
	}
	return transfer, nil
}

func (s *Service) List(ctx context.Context, status string, limit, offset int) ([]*model.CrossChainTransfer, int64, error) {
	return s.transferRepo.List(ctx, status, limit, offset)
}
