package mtls

import (
	"crypto/x509"
	"encoding/gob"
	"encoding/pem"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"github.com/parking-platform/platform/pkg/models"
)

type PersistenceStore interface {
	Save(snapshot *CertificateSnapshot) error
	Load() (*CertificateSnapshot, error)
	Exists() bool
}

type CertificateSnapshot struct {
	CAKeyPEM  string
	CACertPEM string
	Certs     map[string]*models.Certificate
	Policies  map[string]*models.RotationPolicy
	CRLs      map[string]*models.CRL
}

type FilePersistence struct {
	path string
	mu   sync.RWMutex
}

func NewFilePersistence(path string) *FilePersistence {
	return &FilePersistence{
		path: path,
	}
}

func (f *FilePersistence) Save(snapshot *CertificateSnapshot) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	dir := filepath.Dir(f.path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}

	file, err := os.Create(f.path)
	if err != nil {
		return err
	}
	defer file.Close()

	encoder := gob.NewEncoder(file)
	return encoder.Encode(snapshot)
}

func (f *FilePersistence) Load() (*CertificateSnapshot, error) {
	f.mu.RLock()
	defer f.mu.RUnlock()

	file, err := os.Open(f.path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var snapshot CertificateSnapshot
	decoder := gob.NewDecoder(file)
	if err := decoder.Decode(&snapshot); err != nil {
		return nil, err
	}
	return &snapshot, nil
}

func (f *FilePersistence) Exists() bool {
	f.mu.RLock()
	defer f.mu.RUnlock()
	_, err := os.Stat(f.path)
	return err == nil
}

func init() {
	gob.Register(&models.Certificate{})
	gob.Register(&models.RotationPolicy{})
	gob.Register(&models.CRL{})
}

func (m *CertificateManager) EnablePersistence(store PersistenceStore) {
	m.persistence = store
}

func (m *CertificateManager) Snapshot() *CertificateSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var caKeyPEM, caCertPEM string
	if m.caKey != nil {
		caKeyBytes := x509.MarshalPKCS1PrivateKey(m.caKey)
		caKeyPEM = string(pemEncode("RSA PRIVATE KEY", caKeyBytes))
	}
	if m.caCert != nil {
		caCertPEM = string(pemEncode("CERTIFICATE", m.caCert.Raw))
	}

	certs := make(map[string]*models.Certificate)
	for k, v := range m.certs {
		certs[k] = v
	}

	policies := make(map[string]*models.RotationPolicy)
	for k, v := range m.policies {
		policies[k] = v
	}

	crls := make(map[string]*models.CRL)
	for k, v := range m.crls {
		crls[k] = v
	}

	return &CertificateSnapshot{
		CAKeyPEM:  caKeyPEM,
		CACertPEM: caCertPEM,
		Certs:     certs,
		Policies:  policies,
		CRLs:      crls,
	}
}

func (m *CertificateManager) Restore(snapshot *CertificateSnapshot) error {
	if snapshot == nil {
		return errors.New("snapshot is nil")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if snapshot.CACertPEM != "" {
		block, _ := pemDecode([]byte(snapshot.CACertPEM))
		if block != nil {
			caCert, err := x509.ParseCertificate(block)
			if err != nil {
				return err
			}
			m.caCert = caCert
		}
	}

	if snapshot.CAKeyPEM != "" {
		block, _ := pemDecode([]byte(snapshot.CAKeyPEM))
		if block != nil {
			caKey, err := x509.ParsePKCS1PrivateKey(block)
			if err != nil {
				return err
			}
			m.caKey = caKey
		}
	}

	m.certs = make(map[string]*models.Certificate)
	for k, v := range snapshot.Certs {
		m.certs[k] = v
	}

	m.policies = make(map[string]*models.RotationPolicy)
	for k, v := range snapshot.Policies {
		m.policies[k] = v
	}

	m.crls = make(map[string]*models.CRL)
	for k, v := range snapshot.CRLs {
		m.crls[k] = v
	}

	return nil
}

func (m *CertificateManager) Persist() error {
	if m.persistence == nil {
		return errors.New("persistence not enabled")
	}
	return m.persistence.Save(m.Snapshot())
}

func (m *CertificateManager) LoadFromPersistence() error {
	if m.persistence == nil {
		return errors.New("persistence not enabled")
	}
	if !m.persistence.Exists() {
		return nil
	}
	snapshot, err := m.persistence.Load()
	if err != nil {
		return err
	}
	return m.Restore(snapshot)
}

func (m *CertificateManager) AutoPersist() {
	if m.persistence == nil {
		return
	}
	_ = m.Persist()
}

func (m *CertificateManager) IssueCertificateWithPersist(cn string, sans []string, validity time.Duration) (*models.Certificate, error) {
	cert, err := m.IssueCertificate(cn, sans, validity)
	if err != nil {
		return nil, err
	}
	m.AutoPersist()
	return cert, nil
}

func (m *CertificateManager) RevokeCertificateWithPersist(id string) error {
	err := m.RevokeCertificate(id)
	if err != nil {
		return err
	}
	m.AutoPersist()
	return nil
}

func (m *CertificateManager) CreateRotationPolicyWithPersist(name string, autoRotate bool, daysBefore int) *models.RotationPolicy {
	policy := m.CreateRotationPolicy(name, autoRotate, daysBefore)
	m.AutoPersist()
	return policy
}
