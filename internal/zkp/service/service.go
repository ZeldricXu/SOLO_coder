package service

import (
	"context"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/zkp/domain"
	"github.com/solocoder/session147/internal/zkp/ports"
	"go.uber.org/zap"
)

type zkpService struct {
	repo     ports.ZKPRepository
	verifier ports.ProofVerifier
}

func NewZKPService(repo ports.ZKPRepository, verifier ports.ProofVerifier) ports.ZKPService {
	return &zkpService{
		repo:     repo,
		verifier: verifier,
	}
}

func (s *zkpService) VerifyProof(ctx context.Context, req *domain.VerifyRequest) (*domain.VerifyResponse, error) {
	logger.Info("verifying ZKP proof", zap.String("circuit_id", req.CircuitID), zap.String("proof_type", req.ProofType))

	proof := &domain.ZKPProof{
		ID:          utils.GenerateID("zkp"),
		ProofType:   req.ProofType,
		CircuitID:   req.CircuitID,
		ProofData:   req.ProofData,
		PublicInput: req.PublicInput,
		VerifyingKey: req.VerifyingKey,
		Status:      domain.ProofStatusPending,
		CreatedAt:   time.Now(),
		Metadata:    req.Metadata,
	}

	if req.VerifyingKey == "" {
		circuit, err := s.repo.GetCircuit(ctx, req.CircuitID)
		if err != nil {
			return nil, errors.NotFound("circuit not found", err)
		}
		proof.VerifyingKey = circuit.VerifyingKey
	}

	start := time.Now()
	var valid bool
	var err error

	switch req.ProofType {
	case domain.ProofTypeGroth16:
		valid, err = s.verifier.VerifyGroth16(ctx, req.ProofData, req.PublicInput, proof.VerifyingKey)
	case domain.ProofTypePlonk:
		valid, err = s.verifier.VerifyPlonk(ctx, req.ProofData, req.PublicInput, proof.VerifyingKey)
	default:
		return nil, errors.BadRequest("unsupported proof type", nil)
	}

	verifyTime := time.Since(start).Milliseconds()
	now := time.Now()
	proof.VerifiedAt = &now
	proof.VerifyTime = verifyTime

	if err != nil {
		proof.Status = domain.ProofStatusFailed
		proof.Error = err.Error()
		proof.Result = false
		_ = s.repo.StoreProof(ctx, proof)
		return nil, errors.Internal("proof verification failed", err)
	}

	proof.Result = valid
	if valid {
		proof.Status = domain.ProofStatusVerified
	} else {
		proof.Status = domain.ProofStatusInvalid
	}

	if err := s.repo.StoreProof(ctx, proof); err != nil {
		logger.Error("failed to store proof", zap.Error(err))
	}

	return &domain.VerifyResponse{
		ProofID:  proof.ID,
		Valid:    valid,
		Verified: true,
	}, nil
}

func (s *zkpService) GetProof(ctx context.Context, id string) (*domain.ZKPProof, error) {
	return s.repo.GetProof(ctx, id)
}

func (s *zkpService) ListProofs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.ZKPProof, int64, error) {
	return s.repo.ListProofs(ctx, filter, page, pageSize)
}

func (s *zkpService) RegisterCircuit(ctx context.Context, circuit *domain.Circuit) (*domain.Circuit, error) {
	if circuit.ID == "" {
		circuit.ID = utils.GenerateID("circuit")
	}
	circuit.CreatedAt = time.Now()
	circuit.UpdatedAt = time.Now()
	circuit.Status = "active"

	if err := s.repo.StoreCircuit(ctx, circuit); err != nil {
		return nil, errors.Internal("failed to register circuit", err)
	}
	return circuit, nil
}

func (s *zkpService) GetCircuit(ctx context.Context, id string) (*domain.Circuit, error) {
	return s.repo.GetCircuit(ctx, id)
}

func (s *zkpService) ListCircuits(ctx context.Context, page, pageSize int) ([]domain.Circuit, int64, error) {
	return s.repo.ListCircuits(ctx, page, pageSize)
}
