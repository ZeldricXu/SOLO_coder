package mtls

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"sync"
	"time"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type CertificateManager struct {
	mu          sync.RWMutex
	certs       map[string]*models.Certificate
	policies    map[string]*models.RotationPolicy
	crls        map[string]*models.CRL
	caCert      *x509.Certificate
	caKey       *rsa.PrivateKey
	persistence PersistenceStore
}

func NewCertificateManager() *CertificateManager {
	caCert, caKey, _ := generateCA()
	return &CertificateManager{
		certs:    make(map[string]*models.Certificate),
		policies: make(map[string]*models.RotationPolicy),
		crls:     make(map[string]*models.CRL),
		caCert:   caCert,
		caKey:    caKey,
	}
}

func generateCA() (*x509.Certificate, *rsa.PrivateKey, error) {
	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	template := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject: pkix.Name{
			Organization: []string{"Platform CA"},
			CommonName:   "platform-ca",
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().Add(365 * 24 * time.Hour),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
	}
	derBytes, _ := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	cert, _ := x509.ParseCertificate(derBytes)
	return cert, key, nil
}

func (m *CertificateManager) IssueCertificate(cn string, sans []string, validity time.Duration) (*models.Certificate, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	key, _ := rsa.GenerateKey(rand.Reader, 2048)
	serial, _ := rand.Int(rand.Reader, big.NewInt(1000000))
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			CommonName: cn,
		},
		DNSNames:    sans,
		NotBefore:   time.Now(),
		NotAfter:    time.Now().Add(validity),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
	}
	derBytes, _ := x509.CreateCertificate(rand.Reader, template, m.caCert, &key.PublicKey, m.caKey)
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})

	cert := &models.Certificate{
		ID:         utils.GenerateID("cert"),
		CN:         cn,
		SANs:       sans,
		Serial:     serial.String(),
		NotBefore:  template.NotBefore,
		NotAfter:   template.NotAfter,
		Status:     "active",
		PEM:        string(certPEM),
		PrivateKey: string(keyPEM),
	}
	m.certs[cert.ID] = cert
	return cert, nil
}

func (m *CertificateManager) ListCertificates() []*models.Certificate {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]*models.Certificate, 0, len(m.certs))
	for _, c := range m.certs {
		result = append(result, c)
	}
	return result
}

func (m *CertificateManager) GetCertificate(id string) (*models.Certificate, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	c, ok := m.certs[id]
	return c, ok
}

func (m *CertificateManager) RevokeCertificate(id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	cert, ok := m.certs[id]
	if !ok {
		return ErrCertificateNotFound
	}
	cert.Status = "revoked"
	if crl, exists := m.crls[m.caCert.Subject.CommonName]; exists {
		crl.Revoked = append(crl.Revoked, cert.Serial)
		crl.UpdatedAt = utils.Now()
	} else {
		m.crls[m.caCert.Subject.CommonName] = &models.CRL{
			ID:       utils.GenerateID("crl"),
			IssuerCN: m.caCert.Subject.CommonName,
			Revoked:  []string{cert.Serial},
			UpdatedAt: utils.Now(),
		}
	}
	return nil
}

func (m *CertificateManager) CreateRotationPolicy(name string, autoRotate bool, daysBefore int) *models.RotationPolicy {
	m.mu.Lock()
	defer m.mu.Unlock()
	policy := &models.RotationPolicy{
		ID:         utils.GenerateID("policy"),
		Name:       name,
		AutoRotate: autoRotate,
		DaysBefore: daysBefore,
	}
	m.policies[policy.ID] = policy
	return policy
}

func (m *CertificateManager) ListRotationPolicies() []*models.RotationPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]*models.RotationPolicy, 0, len(m.policies))
	for _, p := range m.policies {
		result = append(result, p)
	}
	return result
}

func (m *CertificateManager) GetCRL(issuerCN string) (*models.CRL, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	crl, ok := m.crls[issuerCN]
	return crl, ok
}

func (m *CertificateManager) RotateIfNeeded(id string) (*models.Certificate, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	cert, ok := m.certs[id]
	if !ok {
		return nil, ErrCertificateNotFound
	}
	for _, policy := range m.policies {
		if policy.AutoRotate {
			threshold := time.Now().Add(time.Duration(policy.DaysBefore*24) * time.Hour)
			if cert.NotAfter.Before(threshold) {
				newCert, _ := m.IssueCertificate(cert.CN, cert.SANs, cert.NotAfter.Sub(cert.NotBefore))
				cert.Status = "rotated"
				return newCert, nil
			}
		}
	}
	return cert, nil
}

var ErrCertificateNotFound = &certError{"certificate not found"}

type certError struct {
	msg string
}

func (e *certError) Error() string { return e.msg }

func pemEncode(blockType string, bytes []byte) []byte {
	block := &pem.Block{Type: blockType, Bytes: bytes}
	return pem.EncodeToMemory(block)
}

func pemDecode(data []byte) ([]byte, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("failed to decode PEM block")
	}
	return block.Bytes, nil
}
