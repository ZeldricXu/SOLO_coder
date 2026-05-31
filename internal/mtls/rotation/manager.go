package rotation

import (
	"context"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type Manager struct {
	mu       sync.RWMutex
	policies map[string]*domain.RotationPolicy
	caStore  ports.CAStore
	logger   *zap.Logger
}

func NewRotationManager(caStore ports.CAStore, logger *zap.Logger) (ports.RotationPolicyStore, ports.CertificateRotator) {
	if logger == nil {
		logger = zap.NewNop()
	}
	rm := &Manager{
		policies: make(map[string]*domain.RotationPolicy),
		caStore:  caStore,
		logger:   logger,
	}
	go rm.startAutoRotation()
	return rm, rm
}

func (m *Manager) SetPolicy(ctx context.Context, policy *domain.RotationPolicy) error {
	if policy == nil {
		return &domain.AppError{Message: "policy cannot be nil"}
	}
	if policy.Namespace == "" {
		return &domain.AppError{Message: "namespace is required"}
	}
	if policy.ValidityPeriod <= 0 {
		policy.ValidityPeriod = 90 * 24 * time.Hour
	}
	if policy.RotationWindow <= 0 {
		policy.RotationWindow = 7 * 24 * time.Hour
	}
	if policy.AlertBeforeDays <= 0 {
		policy.AlertBeforeDays = 7
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	m.policies[policy.Namespace] = policy

	m.logger.Info("rotation policy set",
		zap.String("namespace", policy.Namespace),
		zap.Bool("auto_rotate", policy.AutoRotate),
		zap.Duration("validity_period", policy.ValidityPeriod),
	)

	return nil
}

func (m *Manager) GetPolicy(namespace string) *domain.RotationPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.policies[namespace]
}

func (m *Manager) ShouldRotate(cert *domain.Certificate) bool {
	if cert.Revoked {
		return false
	}

	policy := m.GetPolicy(cert.Namespace)
	if policy == nil || !policy.AutoRotate {
		return false
	}

	alertThreshold := time.Duration(policy.AlertBeforeDays) * 24 * time.Hour
	rotationTime := cert.ExpiresAt.Add(-alertThreshold)

	return time.Now().After(rotationTime)
}

func (m *Manager) Rotate(ctx context.Context, cert *domain.Certificate) (*domain.Certificate, error) {
	if cert == nil {
		return nil, &domain.AppError{Message: "certificate cannot be nil"}
	}

	policy := m.GetPolicy(cert.Namespace)
	validity := 90 * 24 * time.Hour
	if policy != nil {
		validity = policy.ValidityPeriod
	}

	req := &domain.CertificateRequest{
		CommonName: cert.CommonName,
		DNSNames:   cert.DNSNames,
		Namespace:  cert.Namespace,
		Validity:   validity,
		KeySize:    2048,
	}

	newCert, err := m.caStore.IssueCertificate(req)
	if err != nil {
		return nil, err
	}

	m.logger.Info("certificate rotated",
		zap.String("old_cert_id", cert.CertID),
		zap.String("new_cert_id", newCert.CertID),
		zap.String("common_name", cert.CommonName),
	)

	return newCert, nil
}

func (m *Manager) startAutoRotation() {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for range ticker.C {
		m.checkAndRotate()
	}
}

func (m *Manager) checkAndRotate() {
	m.mu.RLock()
	policies := make([]*domain.RotationPolicy, 0, len(m.policies))
	for _, p := range m.policies {
		policies = append(policies, p)
	}
	m.mu.RUnlock()

	m.logger.Debug("auto rotation check started",
		zap.Int("policy_count", len(policies)),
	)
}

func (m *Manager) ListPolicies(ctx context.Context) []*domain.RotationPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*domain.RotationPolicy, 0, len(m.policies))
	for _, p := range m.policies {
		list = append(list, p)
	}
	return list
}

func (m *Manager) RotateBatch(ctx context.Context, certIDs []string) []*ports.BatchOperationResult {
	results := make([]*ports.BatchOperationResult, 0, len(certIDs))

	for _, certID := range certIDs {
		result := &ports.BatchOperationResult{
			ID: certID,
		}

		select {
		case <-ctx.Done():
			result.Success = false
			result.Error = "context cancelled"
			results = append(results, result)
			continue
		default:
		}

		req := &domain.CertificateRequest{
			CommonName: certID,
			Namespace:  "batch",
			Validity:   90 * 24 * time.Hour,
			KeySize:    2048,
		}

		newCert, err := m.caStore.IssueCertificate(req)
		if err != nil {
			result.Success = false
			result.Error = err.Error()
			results = append(results, result)
			continue
		}

		result.Success = true
		result.Cert = newCert
		results = append(results, result)

		m.logger.Info("batch certificate rotated",
			zap.String("old_cert_id", certID),
			zap.String("new_cert_id", newCert.CertID),
		)
	}

	return results
}
