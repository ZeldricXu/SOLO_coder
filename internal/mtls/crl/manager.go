package crl

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type Manager struct {
	mu         sync.RWMutex
	revoked    map[string]*domain.RevokedCert
	caStore    ports.CAStore
	lastUpdate time.Time
	nextUpdate time.Time
	crlPEM     string
	logger     *zap.Logger
}

func NewCRLManager(caStore ports.CAStore, logger *zap.Logger) ports.CRLManager {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Manager{
		revoked:    make(map[string]*domain.RevokedCert),
		caStore:    caStore,
		lastUpdate: time.Now(),
		nextUpdate: time.Now().Add(24 * time.Hour),
		logger:     logger,
	}
}

func (m *Manager) Revoke(cert *domain.Certificate, reason string) error {
	if cert == nil {
		return fmt.Errorf("certificate cannot be nil")
	}
	if cert.Revoked {
		return fmt.Errorf("certificate already revoked")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	revoked := &domain.RevokedCert{
		Serial:     cert.Serial,
		CommonName: cert.CommonName,
		RevokedAt:  time.Now(),
		Reason:     reason,
	}

	m.revoked[cert.Serial] = revoked
	cert.Revoked = true
	cert.RevokedAt = &revoked.RevokedAt

	if err := m.regenerateCRL(); err != nil {
		return fmt.Errorf("failed to regenerate CRL: %w", err)
	}

	m.logger.Info("certificate revoked",
		zap.String("cert_id", cert.CertID),
		zap.String("serial", cert.Serial),
		zap.String("common_name", cert.CommonName),
		zap.String("reason", reason),
	)

	return nil
}

func (m *Manager) IsRevoked(serial string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, revoked := m.revoked[serial]
	return revoked
}

func (m *Manager) GetCRL() *domain.CRL {
	m.mu.RLock()
	defer m.mu.RUnlock()

	revokedList := make([]*domain.RevokedCert, 0, len(m.revoked))
	for _, r := range m.revoked {
		revokedList = append(revokedList, r)
	}

	return &domain.CRL{
		IssuerCN:     m.caStore.GetCACert().Cert.Subject.CommonName,
		RevokedCerts: revokedList,
		LastUpdate:   m.lastUpdate,
		NextUpdate:   m.nextUpdate,
		PEM:          m.crlPEM,
	}
}

func (m *Manager) regenerateCRL() error {
	caCert := m.caStore.GetCACert()

	revokedCerts := make([]pkix.RevokedCertificate, 0, len(m.revoked))
	for _, r := range m.revoked {
		serialInt, success := new(big.Int).SetString(r.Serial, 10)
		if !success {
			continue
		}
		revokedCerts = append(revokedCerts, pkix.RevokedCertificate{
			SerialNumber:   serialInt,
			RevocationTime: r.RevokedAt,
		})
	}

	crlTemplate := &x509.RevocationList{
		RevokedCertificates: revokedCerts,
		ThisUpdate:          time.Now(),
		NextUpdate:          time.Now().Add(24 * time.Hour),
		Number:              big.NewInt(1),
	}

	signer, ok := caCert.PrivateKey.(*rsa.PrivateKey)
	if !ok {
		return fmt.Errorf("invalid CA private key type")
	}

	derBytes, err := x509.CreateRevocationList(rand.Reader, crlTemplate, caCert.Cert, signer)
	if err != nil {
		return err
	}

	m.crlPEM = string(pem.EncodeToMemory(&pem.Block{
		Type:  "X509 CRL",
		Bytes: derBytes,
	}))

	m.lastUpdate = time.Now()
	m.nextUpdate = time.Now().Add(24 * time.Hour)

	m.logger.Debug("CRL regenerated",
		zap.Int("revoked_count", len(m.revoked)),
	)

	return nil
}

func (m *Manager) Count() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.revoked)
}

func (m *Manager) ListRevoked() []*domain.RevokedCert {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*domain.RevokedCert, 0, len(m.revoked))
	for _, r := range m.revoked {
		list = append(list, r)
	}
	return list
}

func (m *Manager) RevokeBatch(ctx context.Context, certIDs []string, reason string) []*ports.BatchOperationResult {
	results := make([]*ports.BatchOperationResult, 0, len(certIDs))

	if reason == "" {
		reason = "batch_revoke"
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	successCount := 0

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

		serial := certID
		if _, exists := m.revoked[serial]; exists {
			result.Success = false
			result.Error = "certificate already revoked"
			results = append(results, result)
			continue
		}

		revoked := &domain.RevokedCert{
			Serial:     serial,
			CommonName: certID,
			RevokedAt:  time.Now(),
			Reason:     reason,
		}

		m.revoked[serial] = revoked
		result.Success = true
		successCount++
		results = append(results, result)

		m.logger.Debug("batch certificate revoked",
			zap.String("cert_id", certID),
			zap.String("serial", serial),
		)
	}

	if successCount > 0 {
		if err := m.regenerateCRL(); err != nil {
			m.logger.Warn("failed to regenerate CRL after batch revoke",
				zap.Error(err),
			)
		}
	}

	m.logger.Info("batch revoke completed",
		zap.Int("total", len(certIDs)),
		zap.Int("success", successCount),
	)

	return results
}
