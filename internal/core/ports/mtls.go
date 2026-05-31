package ports

import (
	"context"

	"github.com/chaoslab/platform/internal/core/domain"
)

type CAStore interface {
	IssueCertificate(req *domain.CertificateRequest) (*domain.Certificate, error)
	GetCACert() *domain.CACert
}

type BatchOperationResult struct {
	ID      string `json:"id"`
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
	Cert    *domain.Certificate `json:"cert,omitempty"`
}

type BatchCertificateRepository interface {
	CertificateRepository
	SaveBatch(ctx context.Context, certs []*domain.Certificate) []*BatchOperationResult
	FindByIDs(ctx context.Context, certIDs []string) ([]*domain.Certificate, []*BatchOperationResult)
}

type CertificateRepository interface {
	Save(ctx context.Context, cert *domain.Certificate) error
	FindByID(ctx context.Context, certID string) (*domain.Certificate, error)
	ListByNamespace(ctx context.Context, namespace string) ([]*domain.Certificate, error)
	Update(ctx context.Context, cert *domain.Certificate) error
}

type RotationPolicyStore interface {
	SetPolicy(ctx context.Context, policy *domain.RotationPolicy) error
	GetPolicy(namespace string) *domain.RotationPolicy
	ShouldRotate(cert *domain.Certificate) bool
	ListPolicies(ctx context.Context) []*domain.RotationPolicy
}

type CertificateRotator interface {
	Rotate(ctx context.Context, cert *domain.Certificate) (*domain.Certificate, error)
	RotateBatch(ctx context.Context, certIDs []string) []*BatchOperationResult
}

type CRLManager interface {
	Revoke(cert *domain.Certificate, reason string) error
	IsRevoked(serial string) bool
	GetCRL() *domain.CRL
	Count() int
	ListRevoked() []*domain.RevokedCert
	RevokeBatch(ctx context.Context, certIDs []string, reason string) []*BatchOperationResult
}

type BatchProcessor interface {
	QueueRequest(req *BatchRequest) *BatchRequestFuture
	Start()
	Stop()
}

type BatchRequest struct {
	Operation string
	Payload   interface{}
}

type BatchRequestFuture struct {
	ResultChan chan *BatchOperationResult
}

func (f *BatchRequestFuture) Wait() *BatchOperationResult {
	return <-f.ResultChan
}
