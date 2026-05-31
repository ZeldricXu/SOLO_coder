package mtls

import (
	"context"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/chaoslab/platform/internal/mtls/batch"
	"go.uber.org/zap"
)

type CertificateService struct {
	caStore         ports.CAStore
	certRepo        ports.CertificateRepository
	batchCertRepo   ports.BatchCertificateRepository
	rotationPolicy  ports.RotationPolicyStore
	certRotator     ports.CertificateRotator
	crlManager      ports.CRLManager
	batchProcessor  ports.BatchProcessor
	logger          *zap.Logger
}

func NewCertificateService(
	caStore ports.CAStore,
	certRepo ports.CertificateRepository,
	rotationPolicy ports.RotationPolicyStore,
	certRotator ports.CertificateRotator,
	crlManager ports.CRLManager,
	logger *zap.Logger,
) ports.MTLSCertificateService {
	if logger == nil {
		logger = zap.NewNop()
	}

	svc := &CertificateService{
		caStore:        caStore,
		certRepo:       certRepo,
		rotationPolicy: rotationPolicy,
		certRotator:    certRotator,
		crlManager:     crlManager,
		logger:         logger,
	}

	if bcr, ok := certRepo.(ports.BatchCertificateRepository); ok {
		svc.batchCertRepo = bcr
	}

	svc.initBatchProcessor()

	return svc
}

func NewCertificateServiceWithBatch(
	caStore ports.CAStore,
	certRepo ports.BatchCertificateRepository,
	rotationPolicy ports.RotationPolicyStore,
	certRotator ports.CertificateRotator,
	crlManager ports.CRLManager,
	logger *zap.Logger,
) ports.MTLSCertificateService {
	if logger == nil {
		logger = zap.NewNop()
	}

	svc := &CertificateService{
		caStore:        caStore,
		certRepo:       certRepo,
		batchCertRepo:  certRepo,
		rotationPolicy: rotationPolicy,
		certRotator:    certRotator,
		crlManager:     crlManager,
		logger:         logger,
	}

	svc.initBatchProcessor()

	return svc
}

func (s *CertificateService) initBatchProcessor() {
	processFn := func(operation string, items []*ports.BatchRequest) []*ports.BatchOperationResult {
		results := make([]*ports.BatchOperationResult, 0, len(items))

		switch operation {
		case "issue":
			reqs := make([]*domain.CertificateRequest, 0, len(items))
			for _, item := range items {
				if req, ok := item.Payload.(*domain.CertificateRequest); ok {
					reqs = append(reqs, req)
				}
			}
			batchResults := s.IssueCertificates(context.Background(), reqs)
			results = append(results, batchResults...)

		case "get":
			certIDs := make([]string, 0, len(items))
			for _, item := range items {
				if id, ok := item.Payload.(string); ok {
					certIDs = append(certIDs, id)
				}
			}
			_, batchResults := s.GetCertificates(context.Background(), certIDs)
			results = append(results, batchResults...)

		case "revoke":
			certIDs := make([]string, 0, len(items))
			reason := "batch_revoke"
			for _, item := range items {
				if payload, ok := item.Payload.(map[string]interface{}); ok {
					if id, ok := payload["cert_id"].(string); ok {
						certIDs = append(certIDs, id)
					}
					if r, ok := payload["reason"].(string); ok && r != "" {
						reason = r
					}
				}
			}
			batchResults := s.RevokeCertificates(context.Background(), certIDs, reason)
			results = append(results, batchResults...)

		default:
			for _, item := range items {
				results = append(results, &ports.BatchOperationResult{
					Success: false,
					Error:   "unknown operation: " + operation,
				})
			}
		}

		return results
	}

	s.batchProcessor = batch.NewProcessor(50, 50*time.Millisecond, processFn, s.logger)
	s.batchProcessor.Start()
}

func (s *CertificateService) IssueCertificate(ctx context.Context, req *domain.CertificateRequest) (*domain.Certificate, error) {
	cert, err := s.caStore.IssueCertificate(req)
	if err != nil {
		return nil, err
	}

	if err := s.certRepo.Save(ctx, cert); err != nil {
		return nil, err
	}

	return cert, nil
}

func (s *CertificateService) RotateCertificate(ctx context.Context, certID string) (*domain.Certificate, error) {
	cert, err := s.certRepo.FindByID(ctx, certID)
	if err != nil {
		return nil, err
	}

	newCert, err := s.certRotator.Rotate(ctx, cert)
	if err != nil {
		return nil, err
	}

	if err := s.crlManager.Revoke(cert, "rotation"); err != nil {
		s.logger.Warn("failed to revoke old certificate during rotation",
			zap.String("cert_id", certID),
			zap.Error(err),
		)
	}

	if err := s.certRepo.Save(ctx, newCert); err != nil {
		return nil, err
	}

	return newCert, nil
}

func (s *CertificateService) RevokeCertificate(ctx context.Context, certID string, reason string) error {
	cert, err := s.certRepo.FindByID(ctx, certID)
	if err != nil {
		return err
	}

	if reason == "" {
		reason = "unspecified"
	}

	return s.crlManager.Revoke(cert, reason)
}

func (s *CertificateService) GetCRL(ctx context.Context) (*domain.CRL, error) {
	return s.crlManager.GetCRL(), nil
}

func (s *CertificateService) SetRotationPolicy(ctx context.Context, policy *domain.RotationPolicy) error {
	return s.rotationPolicy.SetPolicy(ctx, policy)
}

func (s *CertificateService) GetCertificate(ctx context.Context, certID string) (*domain.Certificate, error) {
	return s.certRepo.FindByID(ctx, certID)
}

func (s *CertificateService) ListCertificates(ctx context.Context, namespace string) ([]*domain.Certificate, error) {
	return s.certRepo.ListByNamespace(ctx, namespace)
}

func (s *CertificateService) GetCACertificatePEM() string {
	return s.caStore.GetCACert().CertPEM
}

func (s *CertificateService) IssueCertificates(ctx context.Context, reqs []*domain.CertificateRequest) []*ports.BatchOperationResult {
	results := make([]*ports.BatchOperationResult, 0, len(reqs))
	certs := make([]*domain.Certificate, 0, len(reqs))

	for _, req := range reqs {
		result := &ports.BatchOperationResult{}

		select {
		case <-ctx.Done():
			result.Success = false
			result.Error = "context cancelled"
			results = append(results, result)
			continue
		default:
		}

		cert, err := s.caStore.IssueCertificate(req)
		if err != nil {
			result.Success = false
			result.Error = err.Error()
			results = append(results, result)
			continue
		}

		result.ID = cert.CertID
		result.Success = true
		result.Cert = cert
		certs = append(certs, cert)
		results = append(results, result)
	}

	if s.batchCertRepo != nil && len(certs) > 0 {
		s.batchCertRepo.SaveBatch(ctx, certs)
	} else if len(certs) > 0 {
		for _, cert := range certs {
			_ = s.certRepo.Save(ctx, cert)
		}
	}

	s.logger.Info("batch issue certificates completed",
		zap.Int("total", len(reqs)),
		zap.Int("success", len(certs)),
	)

	return results
}

func (s *CertificateService) RotateCertificates(ctx context.Context, certIDs []string) []*ports.BatchOperationResult {
	return s.certRotator.RotateBatch(ctx, certIDs)
}

func (s *CertificateService) RevokeCertificates(ctx context.Context, certIDs []string, reason string) []*ports.BatchOperationResult {
	return s.crlManager.RevokeBatch(ctx, certIDs, reason)
}

func (s *CertificateService) GetCertificates(ctx context.Context, certIDs []string) ([]*domain.Certificate, []*ports.BatchOperationResult) {
	if s.batchCertRepo != nil {
		return s.batchCertRepo.FindByIDs(ctx, certIDs)
	}

	certs := make([]*domain.Certificate, 0, len(certIDs))
	results := make([]*ports.BatchOperationResult, 0, len(certIDs))

	for _, certID := range certIDs {
		result := &ports.BatchOperationResult{
			ID: certID,
		}
		cert, err := s.certRepo.FindByID(ctx, certID)
		if err != nil {
			result.Success = false
			result.Error = err.Error()
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

func (s *CertificateService) QueueBatchRequest(req *ports.BatchRequest) *ports.BatchRequestFuture {
	if s.batchProcessor == nil {
		future := &ports.BatchRequestFuture{
			ResultChan: make(chan *ports.BatchOperationResult, 1),
		}
		future.ResultChan <- &ports.BatchOperationResult{
			Success: false,
			Error:   "batch processor not initialized",
		}
		close(future.ResultChan)
		return future
	}
	return s.batchProcessor.QueueRequest(req)
}

func (s *CertificateService) GetBatchProcessor() ports.BatchProcessor {
	return s.batchProcessor
}
