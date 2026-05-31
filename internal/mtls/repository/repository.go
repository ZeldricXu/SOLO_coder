package repository

import (
	"context"
	"fmt"
	"sync"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
)

type InMemoryRepository struct {
	mu    sync.RWMutex
	certs map[string]*domain.Certificate
}

func NewCertificateRepository() ports.CertificateRepository {
	return &InMemoryRepository{
		certs: make(map[string]*domain.Certificate),
	}
}

func NewBatchCertificateRepository() ports.BatchCertificateRepository {
	return &InMemoryRepository{
		certs: make(map[string]*domain.Certificate),
	}
}

func (r *InMemoryRepository) SaveBatch(ctx context.Context, certs []*domain.Certificate) []*ports.BatchOperationResult {
	results := make([]*ports.BatchOperationResult, 0, len(certs))

	r.mu.Lock()
	defer r.mu.Unlock()

	for _, cert := range certs {
		result := &ports.BatchOperationResult{
			ID: cert.CertID,
		}
		if cert == nil {
			result.Success = false
			result.Error = "certificate is nil"
			results = append(results, result)
			continue
		}
		r.certs[cert.CertID] = cert
		result.Success = true
		result.Cert = cert
		results = append(results, result)
	}

	return results
}

func (r *InMemoryRepository) FindByIDs(ctx context.Context, certIDs []string) ([]*domain.Certificate, []*ports.BatchOperationResult) {
	certs := make([]*domain.Certificate, 0, len(certIDs))
	results := make([]*ports.BatchOperationResult, 0, len(certIDs))

	r.mu.RLock()
	defer r.mu.RUnlock()

	for _, certID := range certIDs {
		result := &ports.BatchOperationResult{
			ID: certID,
		}
		cert, exists := r.certs[certID]
		if !exists {
			result.Success = false
			result.Error = fmt.Sprintf("certificate %s not found", certID)
			results = append(results, result)
			continue
		}
		result.Success = true
		result.Cert = cert
		certs = append(certs, cert)
		results = append(results, result)
	}

	return certs, results
}

func (r *InMemoryRepository) Save(ctx context.Context, cert *domain.Certificate) error {
	if cert == nil {
		return fmt.Errorf("certificate cannot be nil")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.certs[cert.CertID] = cert
	return nil
}

func (r *InMemoryRepository) FindByID(ctx context.Context, certID string) (*domain.Certificate, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	cert, exists := r.certs[certID]
	if !exists {
		return nil, fmt.Errorf("certificate %s not found", certID)
	}
	return cert, nil
}

func (r *InMemoryRepository) ListByNamespace(ctx context.Context, namespace string) ([]*domain.Certificate, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	list := make([]*domain.Certificate, 0)
	for _, cert := range r.certs {
		if namespace == "" || cert.Namespace == namespace {
			list = append(list, cert)
		}
	}
	return list, nil
}

func (r *InMemoryRepository) Update(ctx context.Context, cert *domain.Certificate) error {
	if cert == nil {
		return fmt.Errorf("certificate cannot be nil")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.certs[cert.CertID]; !exists {
		return fmt.Errorf("certificate %s not found", cert.CertID)
	}
	r.certs[cert.CertID] = cert
	return nil
}
