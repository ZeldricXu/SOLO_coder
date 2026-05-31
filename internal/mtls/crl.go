package mtls

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type CRLManager struct {
	mu          sync.RWMutex
	revoked     map[string]*common.RevokedCert
	caMgr       *CAManager
	lastUpdate  time.Time
	nextUpdate  time.Time
	crlPEM      string
}

func NewCRLManager(caMgr *CAManager) *CRLManager {
	m := &CRLManager{
		revoked:    make(map[string]*common.RevokedCert),
		caMgr:      caMgr,
		lastUpdate: time.Now(),
		nextUpdate: time.Now().Add(24 * time.Hour),
	}
	return m
}

func (m *CRLManager) Revoke(cert *common.Certificate, reason string) error {
	if cert == nil {
		return common.NewBadRequestError("certificate cannot be nil")
	}
	if cert.Revoked {
		return common.NewConflictError("certificate already revoked")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	revoked := &common.RevokedCert{
		Serial:     cert.Serial,
		CommonName: cert.CommonName,
		RevokedAt:  time.Now(),
		Reason:     reason,
	}

	m.revoked[cert.Serial] = revoked
	cert.Revoked = true
	cert.RevokedAt = &revoked.RevokedAt

	if err := m.regenerateCRL(); err != nil {
		return common.NewInternalError("failed to regenerate CRL", err)
	}

	common.Info("certificate revoked",
		zap.String("cert_id", cert.CertID),
		zap.String("serial", cert.Serial),
		zap.String("common_name", cert.CommonName),
		zap.String("reason", reason),
	)

	return nil
}

func (m *CRLManager) IsRevoked(serial string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, revoked := m.revoked[serial]
	return revoked
}

func (m *CRLManager) GetCRL() *common.CRL {
	m.mu.RLock()
	defer m.mu.RUnlock()

	revokedList := make([]*common.RevokedCert, 0, len(m.revoked))
	for _, r := range m.revoked {
		revokedList = append(revokedList, r)
	}

	return &common.CRL{
		IssuerCN:     m.caMgr.GetCACert().Cert.Subject.CommonName,
		RevokedCerts: revokedList,
		LastUpdate:   m.lastUpdate,
		NextUpdate:   m.nextUpdate,
		PEM:          m.crlPEM,
	}
}

func (m *CRLManager) regenerateCRL() error {
	caCert := m.caMgr.GetCACert()

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
		return common.NewInternalError("invalid CA private key type", nil)
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

	common.Debug("CRL regenerated",
		zap.Int("revoked_count", len(m.revoked)),
	)

	return nil
}

func (m *CRLManager) Count() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.revoked)
}

func (m *CRLManager) ListRevoked() []*common.RevokedCert {
	m.mu.RLock()
	defer m.mu.RUnlock()

	list := make([]*common.RevokedCert, 0, len(m.revoked))
	for _, r := range m.revoked {
		list = append(list, r)
	}
	return list
}
