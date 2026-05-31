package service

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"math/big"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/bridge/domain"
	"github.com/solocoder/session147/internal/bridge/ports"
	"go.uber.org/zap"
)

type bridgeService struct {
	repo        ports.BridgeRepository
	verifier    ports.MessageVerifier
	atomicSwap  ports.AtomicSwapHandler
	configs     map[string]*domain.BridgeConfig
}

func NewBridgeService(repo ports.BridgeRepository, verifier ports.MessageVerifier, atomicSwap ports.AtomicSwapHandler) ports.BridgeService {
	return &bridgeService{
		repo:       repo,
		verifier:   verifier,
		atomicSwap: atomicSwap,
		configs:    make(map[string]*domain.BridgeConfig),
	}
}

func (s *bridgeService) InitiateBridge(ctx context.Context, req *domain.BridgeRequest) (*domain.BridgeTransaction, error) {
	logger.Info("initiating bridge transaction",
		zap.Int64("source_chain", req.SourceChainID),
		zap.Int64("dest_chain", req.DestChainID))

	if req.Amount.Sign() <= 0 {
		return nil, errors.BadRequest("invalid amount", nil)
	}

	bridgeTxID := utils.GenerateID("bridge")

	var secretHash string
	var lockTime *time.Time
	var atomicSwapID string

	if req.UseAtomicSwap {
		secret := make([]byte, 32)
		_, _ = rand.Read(secret)
		secretHashBytes := sha256Hash(secret)
		secretHash = hex.EncodeToString(secretHashBytes)

		lt := time.Now().Add(time.Duration(req.TimeoutSeconds) * time.Second)
		lockTime = &lt

		swapID, err := s.atomicSwap.InitiateSwap(ctx, req.SourceChainID, req.DestChainID,
			req.Amount.String(), secretHash, lt)
		if err != nil {
			return nil, errors.Internal("failed to initiate atomic swap", err)
		}
		atomicSwapID = swapID
	}

	tx := &domain.BridgeTransaction{
		ID:             utils.GenerateID("btx"),
		BridgeTxID:     bridgeTxID,
		SourceChainID:  req.SourceChainID,
		DestChainID:    req.DestChainID,
		SourceAddress:  req.SourceAddress,
		DestAddress:    req.DestAddress,
		Amount:         req.Amount.String(),
		TokenAddress:   req.TokenAddress,
		Status:         domain.BridgeStatusPending,
		RequiredConfs:  15,
		AtomicSwapID:   atomicSwapID,
		LockTime:       lockTime,
		SecretHash:     secretHash,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
	}

	if err := s.repo.CreateTransaction(ctx, tx); err != nil {
		return nil, errors.Internal("failed to create bridge transaction", err)
	}

	return tx, nil
}

func (s *bridgeService) ConfirmLock(ctx context.Context, bridgeTxID string, sourceTxHash string, blockNumber uint64) error {
	tx, err := s.repo.GetTransactionByBridgeTxID(ctx, bridgeTxID)
	if err != nil {
		return errors.NotFound("bridge transaction not found", err)
	}

	if tx.Status != domain.BridgeStatusPending {
		return errors.BadRequest("transaction is not in pending state", nil)
	}

	tx.SourceTxHash = sourceTxHash
	tx.SourceBlockNum = blockNumber
	tx.Status = domain.BridgeStatusLocked
	tx.UpdatedAt = time.Now()

	return s.repo.UpdateTransaction(ctx, tx)
}

func (s *bridgeService) VerifyProof(ctx context.Context, proof *domain.BridgeProof) (bool, error) {
	tx, err := s.repo.GetTransactionByBridgeTxID(ctx, proof.BridgeTxID)
	if err != nil {
		return false, errors.NotFound("bridge transaction not found", err)
	}

	if tx.Status != domain.BridgeStatusLocked {
		return false, errors.BadRequest("transaction is not locked", nil)
	}

	if tx.Confirmations < tx.RequiredConfs {
		return false, errors.BadRequest("not enough confirmations", nil)
	}

	message := []byte(proof.MessageHash)
	valid, err := s.verifier.VerifyMessage(ctx, message, proof.Signatures)
	if err != nil {
		return false, errors.Internal("proof verification failed", err)
	}

	if valid {
		tx.Status = domain.BridgeStatusConfirmed
		tx.UpdatedAt = time.Now()
		_ = s.repo.UpdateTransaction(ctx, tx)
	}

	return valid, nil
}

func (s *bridgeService) MintTokens(ctx context.Context, bridgeTxID string) (string, error) {
	tx, err := s.repo.GetTransactionByBridgeTxID(ctx, bridgeTxID)
	if err != nil {
		return "", errors.NotFound("bridge transaction not found", err)
	}

	if tx.Status != domain.BridgeStatusConfirmed {
		return "", errors.BadRequest("transaction is not confirmed", nil)
	}

	tx.Status = domain.BridgeStatusMinting
	tx.UpdatedAt = time.Now()
	_ = s.repo.UpdateTransaction(ctx, tx)

	destTxHash := utils.GenerateID("dtx")
	return destTxHash, nil
}

func (s *bridgeService) CompleteBridge(ctx context.Context, bridgeTxID string, destTxHash string) error {
	tx, err := s.repo.GetTransactionByBridgeTxID(ctx, bridgeTxID)
	if err != nil {
		return errors.NotFound("bridge transaction not found", err)
	}

	if tx.Status != domain.BridgeStatusMinting {
		return errors.BadRequest("transaction is not in minting state", nil)
	}

	now := time.Now()
	tx.DestTxHash = destTxHash
	tx.Status = domain.BridgeStatusCompleted
	tx.CompletedAt = &now
	tx.UpdatedAt = now

	return s.repo.UpdateTransaction(ctx, tx)
}

func (s *bridgeService) RefundTransaction(ctx context.Context, bridgeTxID string) error {
	tx, err := s.repo.GetTransactionByBridgeTxID(ctx, bridgeTxID)
	if err != nil {
		return errors.NotFound("bridge transaction not found", err)
	}

	if tx.LockTime != nil && time.Now().Before(*tx.LockTime) {
		return errors.BadRequest("lock time has not expired", nil)
	}

	tx.Status = domain.BridgeStatusRefunding
	tx.UpdatedAt = time.Now()
	_ = s.repo.UpdateTransaction(ctx, tx)

	if tx.AtomicSwapID != "" {
		_, err := s.atomicSwap.RefundSwap(ctx, tx.SourceChainID, tx.AtomicSwapID)
		if err != nil {
			logger.Warn("atomic swap refund failed", zap.Error(err))
		}
	}

	now := time.Now()
	tx.Status = domain.BridgeStatusRefunded
	tx.CompletedAt = &now
	tx.UpdatedAt = now

	return s.repo.UpdateTransaction(ctx, tx)
}

func (s *bridgeService) RetryTransaction(ctx context.Context, bridgeTxID string) error {
	tx, err := s.repo.GetTransactionByBridgeTxID(ctx, bridgeTxID)
	if err != nil {
		return errors.NotFound("bridge transaction not found", err)
	}

	if tx.Status != domain.BridgeStatusFailed {
		return errors.BadRequest("only failed transactions can be retried", nil)
	}

	tx.Status = domain.BridgeStatusPending
	tx.Error = ""
	tx.UpdatedAt = time.Now()

	return s.repo.UpdateTransaction(ctx, tx)
}

func (s *bridgeService) GetTransaction(ctx context.Context, id string) (*domain.BridgeTransaction, error) {
	return s.repo.GetTransaction(ctx, id)
}

func (s *bridgeService) ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.BridgeTransaction, int64, error) {
	return s.repo.ListTransactions(ctx, filter, page, pageSize)
}

func (s *bridgeService) ProcessPendingTransactions(ctx context.Context) error {
	configs := make([]struct {
		SourceChainID int64
		MinConfs      int
	}, 0)
	for _, cfg := range s.configs {
		configs = append(configs, struct {
			SourceChainID int64
			MinConfs      int
		}{cfg.SourceChainID, cfg.RequiredConfs})
	}

	for _, cfg := range configs {
		pending, err := s.repo.GetPendingTransactions(ctx, cfg.SourceChainID, cfg.MinConfs)
		if err != nil {
			logger.Error("fetch pending transactions failed", zap.Error(err))
			continue
		}

		for _, tx := range pending {
			if tx.Confirmations >= tx.RequiredConfs && tx.Status == domain.BridgeStatusLocked {
				tx.Status = domain.BridgeStatusConfirmed
				tx.UpdatedAt = time.Now()
				_ = s.repo.UpdateTransaction(ctx, &tx)
			}
		}
	}

	return nil
}

func sha256Hash(data []byte) []byte {
	h := sha256.Sum256(data)
	return h[:]
}
