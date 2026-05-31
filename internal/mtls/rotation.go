package mtls

import (
	"context"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type RotationManager struct {
	mu       sync.RWMutex
	policies map[string]*common.RotationPolicy
	caMgr    *CAManager
}

func NewRotationManager(caMgr *CAManager) *RotationManager {
	rm := &RotationManager{
		policies: make(map[string]*common.RotationPolicy),
		caMgr:    caMgr,
	}
	go rm.startAutoRotation()
	return rm
}

func (m *RotationManager) SetPolicy(ctx context.Context, policy *common.RotationPolicy) error {
	if policy == nil {
		return common.NewBadRequestError("policy cannot be nil")
	}
	if policy.Namespace == "" {
		return common.NewValidationError("namespace is required", "namespace")
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

	common.Info("rotation policy set",
		zap.String("namespace", policy.Namespace),
		zap.Bool("auto_rotate", policy.AutoRotate),
		zap.Duration("validity_period", policy.ValidityPeriod),
	)

	return nil
}

func (m *RotationManager) GetPolicy(namespace string) *common.RotationPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.policies[namespace]
}

func (m *RotationManager) ShouldRotate(cert *common.Certificate) bool {
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

func (m *RotationManager) Rotate(ctx context.Context, cert *common.Certificate) (*common.Certificate, error) {
	if cert == nil {
		return nil, common.NewBadRequestError("certificate cannot be nil")
	}

	policy := m.GetPolicy(cert.Namespace)
	validity := 90 * 24 * time.Hour
	if policy != nil {
		validity = policy.ValidityPeriod
	}

	req := &common.CertificateRequest{
		CommonName: cert.CommonName,
		DNSNames:   cert.DNSNames,
		Namespace:  cert.Namespace,
		Validity:   validity,
		KeySize:    2048,
	}

	newCert, err := m.caMgr.IssueCertificate(req)
	if err != nil {
		return nil, err
	}

	common.Info("certificate rotated",
		zap.String("old_cert_id", cert.CertID),
		zap.String("new_cert_id", newCert.CertID),
		zap.String("common_name", cert.CommonName),
	)

	return newCert, nil
}

func (m *RotationManager) startAutoRotation() {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for range ticker.C {
		m.checkAndRotate()
	}
}

func (m *RotationManager) checkAndRotate() {
	m.mu.RLock()
	policies := make([]*common.RotationPolicy, 0, len(m.policies))
	for _, p := range m.policies {
		policies = append(policies, p)
	}
	m.mu.RUnlock()

	common.Debug("auto rotation check started",
		zap.Int("policy_count", len(policies)),
	)
}

func (m *RotationManager) ListPolicies(ctx context.Context) []*common.RotationPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*common.RotationPolicy, 0, len(m.policies))
	for _, p := range m.policies {
		list = append(list, p)
	}
	return list
}
