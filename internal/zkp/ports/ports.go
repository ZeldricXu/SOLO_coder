package ports

import (
	"context"
	"github.com/solocoder/session147/internal/zkp/domain"
)

type ZKPRepository interface {
	StoreProof(ctx context.Context, proof *domain.ZKPProof) error
	GetProof(ctx context.Context, id string) (*domain.ZKPProof, error)
	ListProofs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.ZKPProof, int64, error)
	UpdateProof(ctx context.Context, proof *domain.ZKPProof) error

	StoreCircuit(ctx context.Context, circuit *domain.Circuit) error
	GetCircuit(ctx context.Context, id string) (*domain.Circuit, error)
	ListCircuits(ctx context.Context, page, pageSize int) ([]domain.Circuit, int64, error)
	UpdateCircuit(ctx context.Context, circuit *domain.Circuit) error
}

type ZKPService interface {
	VerifyProof(ctx context.Context, req *domain.VerifyRequest) (*domain.VerifyResponse, error)
	GetProof(ctx context.Context, id string) (*domain.ZKPProof, error)
	ListProofs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.ZKPProof, int64, error)

	RegisterCircuit(ctx context.Context, circuit *domain.Circuit) (*domain.Circuit, error)
	GetCircuit(ctx context.Context, id string) (*domain.Circuit, error)
	ListCircuits(ctx context.Context, page, pageSize int) ([]domain.Circuit, int64, error)
}

type ProofVerifier interface {
	VerifyGroth16(ctx context.Context, proofData, publicInput, verifyingKey string) (bool, error)
	VerifyPlonk(ctx context.Context, proofData, publicInput, verifyingKey string) (bool, error)
}
