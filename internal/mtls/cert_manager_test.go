package mtls

import (
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestNewCertificateManager(t *testing.T) {
	manager := NewCertificateManager()
	if manager == nil {
		t.Fatal("Expected non-nil CertificateManager")
	}
	if manager.caCert == nil {
		t.Fatal("Expected non-nil CA certificate")
	}
	if manager.caKey == nil {
		t.Fatal("Expected non-nil CA private key")
	}
}

func TestIssueCertificate(t *testing.T) {
	manager := NewCertificateManager()
	cn := "test-service.example.com"
	sans := []string{"test-service", "127.0.0.1"}
	validity := 24 * time.Hour

	cert, err := manager.IssueCertificate(cn, sans, validity)
	if err != nil {
		t.Fatalf("Failed to issue certificate: %v", err)
	}

	if cert == nil {
		t.Fatal("Expected non-nil certificate")
	}
	if cert.CN != cn {
		t.Errorf("Expected CN %q, got %q", cn, cert.CN)
	}
	if len(cert.SANs) != len(sans) {
		t.Errorf("Expected %d SANs, got %d", len(sans), len(cert.SANs))
	}
	if cert.Status != "active" {
		t.Errorf("Expected status 'active', got %q", cert.Status)
	}
	if cert.PEM == "" {
		t.Error("Expected non-empty PEM certificate")
	}
	if cert.PrivateKey == "" {
		t.Error("Expected non-empty private key")
	}

	block, _ := pem.Decode([]byte(cert.PEM))
	if block == nil {
		t.Fatal("Failed to decode certificate PEM")
	}
	parsedCert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("Failed to parse certificate: %v", err)
	}
	if parsedCert.Subject.CommonName != cn {
		t.Errorf("Parsed cert CN mismatch: expected %q, got %q", cn, parsedCert.Subject.CommonName)
	}
	if len(parsedCert.DNSNames) != 1 || parsedCert.DNSNames[0] != sans[0] {
		t.Errorf("Parsed cert DNSNames mismatch: expected %v, got %v", sans[:1], parsedCert.DNSNames)
	}
}

func TestListAndGetCertificates(t *testing.T) {
	manager := NewCertificateManager()

	_, _ = manager.IssueCertificate("svc1.example.com", nil, 24*time.Hour)
	_, _ = manager.IssueCertificate("svc2.example.com", nil, 48*time.Hour)

	certs := manager.ListCertificates()
	if len(certs) != 2 {
		t.Errorf("Expected 2 certificates, got %d", len(certs))
	}

	cert := certs[0]
	retrieved, ok := manager.GetCertificate(cert.ID)
	if !ok {
		t.Errorf("Expected to find certificate %q", cert.ID)
	}
	if retrieved.ID != cert.ID {
		t.Errorf("Expected ID %q, got %q", cert.ID, retrieved.ID)
	}

	_, ok = manager.GetCertificate("non-existent")
	if ok {
		t.Error("Expected 'ok' to be false for non-existent certificate")
	}
}

func TestRevokeCertificate(t *testing.T) {
	manager := NewCertificateManager()
	cert, _ := manager.IssueCertificate("to-revoke.example.com", nil, 24*time.Hour)

	err := manager.RevokeCertificate(cert.ID)
	if err != nil {
		t.Fatalf("Failed to revoke certificate: %v", err)
	}

	retrieved, _ := manager.GetCertificate(cert.ID)
	if retrieved.Status != "revoked" {
		t.Errorf("Expected status 'revoked', got %q", retrieved.Status)
	}

	crl, ok := manager.GetCRL("platform-ca")
	if !ok {
		t.Fatal("Expected CRL to exist")
	}
	if len(crl.Revoked) != 1 {
		t.Errorf("Expected 1 revoked serial, got %d", len(crl.Revoked))
	}
	if crl.Revoked[0] != cert.Serial {
		t.Errorf("Expected revoked serial %q, got %q", cert.Serial, crl.Revoked[0])
	}

	err = manager.RevokeCertificate("non-existent")
	if err == nil {
		t.Error("Expected error when revoking non-existent certificate")
	}
}

func TestRotationPolicy(t *testing.T) {
	manager := NewCertificateManager()

	policy := manager.CreateRotationPolicy("default-policy", true, 30)
	if policy == nil {
		t.Fatal("Expected non-nil rotation policy")
	}
	if !policy.AutoRotate {
		t.Error("Expected AutoRotate to be true")
	}
	if policy.DaysBefore != 30 {
		t.Errorf("Expected DaysBefore 30, got %d", policy.DaysBefore)
	}

	policies := manager.ListRotationPolicies()
	if len(policies) != 1 {
		t.Errorf("Expected 1 policy, got %d", len(policies))
	}
}

func TestRotateIfNeeded(t *testing.T) {
	manager := NewCertificateManager()
	manager.CreateRotationPolicy("auto", true, 365)

	validity := 100 * 24 * time.Hour
	cert, _ := manager.IssueCertificate("rotate-test.example.com", nil, validity)

	result, err := manager.RotateIfNeeded(cert.ID)
	if err != nil {
		t.Fatalf("RotateIfNeeded failed: %v", err)
	}
	if result.ID != cert.ID {
		t.Error("Expected no rotation when not within threshold")
	}
	if result.Status != "active" {
		t.Error("Expected status to remain 'active'")
	}

	_, err = manager.RotateIfNeeded("non-existent")
	if err == nil {
		t.Error("Expected error for non-existent certificate")
	}
}

func TestGetCRL(t *testing.T) {
	manager := NewCertificateManager()

	_, ok := manager.GetCRL("platform-ca")
	if ok {
		t.Error("Expected no CRL initially")
	}

	cert, _ := manager.IssueCertificate("crl-test.example.com", nil, 24*time.Hour)
	_ = manager.RevokeCertificate(cert.ID)

	crl, ok := manager.GetCRL("platform-ca")
	if !ok {
		t.Fatal("Expected CRL after revocation")
	}
	if crl.IssuerCN != "platform-ca" {
		t.Errorf("Expected issuer 'platform-ca', got %q", crl.IssuerCN)
	}
}

func TestEmptySANs(t *testing.T) {
	manager := NewCertificateManager()
	cert, err := manager.IssueCertificate("empty-sans.example.com", nil, 24*time.Hour)
	if err != nil {
		t.Fatalf("Failed to issue certificate with empty SANs: %v", err)
	}
	if len(cert.SANs) != 0 {
		t.Errorf("Expected 0 SANs, got %d", len(cert.SANs))
	}
}

func TestZeroValidity(t *testing.T) {
	manager := NewCertificateManager()
	cert, err := manager.IssueCertificate("zero-validity.example.com", nil, 0)
	if err != nil {
		t.Fatalf("Failed to issue certificate with zero validity: %v", err)
	}
	if cert.NotBefore.After(cert.NotAfter) {
		t.Error("Expected NotBefore <= NotAfter even with zero validity")
	}
}

func TestEmptyCN(t *testing.T) {
	manager := NewCertificateManager()
	cert, err := manager.IssueCertificate("", nil, 24*time.Hour)
	if err != nil {
		t.Fatalf("Failed to issue certificate with empty CN: %v", err)
	}
	if cert.CN != "" {
		t.Errorf("Expected empty CN, got %q", cert.CN)
	}
}

func TestConcurrentIssueCertificates(t *testing.T) {
	manager := NewCertificateManager()
	const goroutines = 50
	var wg sync.WaitGroup
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		go func(index int) {
			defer wg.Done()
			cn := fmt.Sprintf("concurrent-%d.example.com", index)
			_, err := manager.IssueCertificate(cn, nil, 24*time.Hour)
			if err != nil {
				t.Errorf("Failed to issue certificate in goroutine %d: %v", index, err)
			}
		}(i)
	}
	wg.Wait()

	certs := manager.ListCertificates()
	if len(certs) != goroutines {
		t.Errorf("Expected %d certificates, got %d", goroutines, len(certs))
	}
}

func TestConcurrentRevoke(t *testing.T) {
	manager := NewCertificateManager()
	const count = 20
	certIDs := make([]string, count)

	for i := 0; i < count; i++ {
		cert, _ := manager.IssueCertificate(fmt.Sprintf("revoke-%d.example.com", i), nil, 24*time.Hour)
		certIDs[i] = cert.ID
	}

	var wg sync.WaitGroup
	wg.Add(count)
	for i := 0; i < count; i++ {
		go func(id string) {
			defer wg.Done()
			_ = manager.RevokeCertificate(id)
		}(certIDs[i])
	}
	wg.Wait()

	for _, id := range certIDs {
		cert, _ := manager.GetCertificate(id)
		if cert.Status != "revoked" {
			t.Errorf("Expected certificate %s to be revoked", id)
		}
	}

	crl, _ := manager.GetCRL("platform-ca")
	if len(crl.Revoked) != count {
		t.Errorf("Expected %d revoked serials in CRL, got %d", count, len(crl.Revoked))
	}
}

func TestConcurrentReadWrite(t *testing.T) {
	manager := NewCertificateManager()
	cert, _ := manager.IssueCertificate("read-write.example.com", nil, 24*time.Hour)

	const goroutines = 30
	var wg sync.WaitGroup
	wg.Add(goroutines * 2)

	for i := 0; i < goroutines; i++ {
		go func() {
			defer wg.Done()
			_, _ = manager.GetCertificate(cert.ID)
			_ = manager.ListCertificates()
			_, _ = manager.GetCRL("platform-ca")
		}()

		go func() {
			defer wg.Done()
			_, _ = manager.IssueCertificate("extra.example.com", nil, 1*time.Hour)
		}()
	}
	wg.Wait()
}

func TestFilePersistenceSaveLoad(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "mtls-test")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	path := filepath.Join(tempDir, "certs.gob")
	store := NewFilePersistence(path)

	if store.Exists() {
		t.Error("Expected store to not exist initially")
	}

	manager := NewCertificateManager()
	manager.EnablePersistence(store)

	cert1, _ := manager.IssueCertificate("persist1.example.com", []string{"svc1"}, 24*time.Hour)
	cert2, _ := manager.IssueCertificate("persist2.example.com", []string{"svc2"}, 48*time.Hour)
	policy := manager.CreateRotationPolicy("test-policy", true, 15)
	_ = manager.RevokeCertificate(cert2.ID)

	err = manager.Persist()
	if err != nil {
		t.Fatalf("Failed to persist: %v", err)
	}

	if !store.Exists() {
		t.Error("Expected store to exist after save")
	}

	newManager := NewCertificateManager()
	newStore := NewFilePersistence(path)
	newManager.EnablePersistence(newStore)

	err = newManager.LoadFromPersistence()
	if err != nil {
		t.Fatalf("Failed to load from persistence: %v", err)
	}

	certs := newManager.ListCertificates()
	if len(certs) != 2 {
		t.Errorf("Expected 2 certificates after load, got %d", len(certs))
	}

	retrieved1, ok := newManager.GetCertificate(cert1.ID)
	if !ok {
		t.Errorf("Expected to find certificate %s", cert1.ID)
	}
	if retrieved1.Status != "active" {
		t.Errorf("Expected cert1 status 'active', got %q", retrieved1.Status)
	}

	retrieved2, _ := newManager.GetCertificate(cert2.ID)
	if retrieved2.Status != "revoked" {
		t.Errorf("Expected cert2 status 'revoked', got %q", retrieved2.Status)
	}

	policies := newManager.ListRotationPolicies()
	if len(policies) != 1 {
		t.Errorf("Expected 1 policy after load, got %d", len(policies))
	}
	if policies[0].ID != policy.ID {
		t.Errorf("Expected policy ID %s, got %s", policy.ID, policies[0].ID)
	}

	crl, ok := newManager.GetCRL("platform-ca")
	if !ok {
		t.Fatal("Expected CRL to exist after load")
	}
	if len(crl.Revoked) != 1 {
		t.Errorf("Expected 1 revoked serial, got %d", len(crl.Revoked))
	}
}

func TestSnapshotRestore(t *testing.T) {
	manager := NewCertificateManager()
	cert, _ := manager.IssueCertificate("snapshot.example.com", nil, 24*time.Hour)

	snapshot := manager.Snapshot()
	if snapshot == nil {
		t.Fatal("Expected non-nil snapshot")
	}
	if len(snapshot.Certs) != 1 {
		t.Errorf("Expected 1 cert in snapshot, got %d", len(snapshot.Certs))
	}

	newManager := NewCertificateManager()
	err := newManager.Restore(snapshot)
	if err != nil {
		t.Fatalf("Failed to restore snapshot: %v", err)
	}

	certs := newManager.ListCertificates()
	if len(certs) != 1 {
		t.Errorf("Expected 1 certificate after restore, got %d", len(certs))
	}
	if certs[0].ID != cert.ID {
		t.Errorf("Expected restored cert ID %s, got %s", cert.ID, certs[0].ID)
	}
}

func TestRestoreNilSnapshot(t *testing.T) {
	manager := NewCertificateManager()
	err := manager.Restore(nil)
	if err == nil {
		t.Error("Expected error when restoring nil snapshot")
	}
}

func TestLoadFromNonExistentFile(t *testing.T) {
	tempDir, _ := os.MkdirTemp("", "mtls-test")
	defer os.RemoveAll(tempDir)

	path := filepath.Join(tempDir, "nonexistent.gob")
	store := NewFilePersistence(path)

	manager := NewCertificateManager()
	manager.EnablePersistence(store)

	err := manager.LoadFromPersistence()
	if err != nil {
		t.Errorf("Expected no error for non-existent file, got %v", err)
	}
}

