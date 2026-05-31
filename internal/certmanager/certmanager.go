package certmanager

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/utils"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

type Certificate struct {
	ID             string    `json:"id"`
	CommonName     string    `json:"common_name"`
	SerialNumber   string    `json:"serial_number"`
	CertPEM        string    `json:"cert_pem"`
	KeyPEM         string    `json:"key_pem,omitempty"`
	Issuer         string    `json:"issuer"`
	NotBefore      time.Time `json:"not_before"`
	NotAfter       time.Time `json:"not_after"`
	Status         string    `json:"status"`
	AutoRotate     bool      `json:"auto_rotate"`
	RotateDays     int       `json:"rotate_days"`
	CreatedAt      time.Time `json:"created_at"`
}

type RevocationEntry struct {
	SerialNumber string    `json:"serial_number"`
	Reason       string    `json:"reason"`
	RevokedAt    time.Time `json:"revoked_at"`
}

type RotationPolicy struct {
	Enabled       bool `json:"enabled"`
	RotateBefore  int  `json:"rotate_before_days"`
	MaxRetries    int  `json:"max_retries"`
}

type OperationLatency struct {
	IssueCertificate   time.Duration
	RevokeCertificate  time.Duration
	RotateCertificate  time.Duration
	VerifyCertificate  time.Duration
	GenerateCRL        time.Duration
}

type ManagerMetrics struct {
	CertificatesIssued    int64
	CertificatesRevoked   int64
	CertificatesRotated   int64
	VerificationAttempts  int64
	VerificationSuccesses int64
	VerificationFailures  int64
	CRLGenerations        int64
	ActiveCerts           int64
	RevokedCerts          int64
	ExpiringSoon          int64
}

type Manager struct {
	certs        map[string]*Certificate
	revocations  map[string]*RevocationEntry
	rootCA       *x509.Certificate
	rootCAKey    *rsa.PrivateKey
	rootCertPEM  string
	rotationPol  RotationPolicy
	metrics      ManagerMetrics
	latency      OperationLatency
	mu           sync.RWMutex
	metricsMu    sync.RWMutex
	promMetrics  *prometheusMetrics
}

type prometheusMetrics struct {
	certificatesIssued    prometheus.Counter
	certificatesRevoked   prometheus.Counter
	certificatesRotated   prometheus.Counter
	verificationAttempts  prometheus.Counter
	verificationSuccesses prometheus.Counter
	verificationFailures  prometheus.Counter
	crlGenerations        prometheus.Counter
	activeCerts           prometheus.Gauge
	revokedCerts          prometheus.Gauge
	expiringSoon          prometheus.Gauge
	operationLatency      *prometheus.HistogramVec
}

func newPrometheusMetrics() *prometheusMetrics {
	return &prometheusMetrics{
		certificatesIssued: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_certificates_issued_total",
			Help: "Total number of certificates issued",
		}),
		certificatesRevoked: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_certificates_revoked_total",
			Help: "Total number of certificates revoked",
		}),
		certificatesRotated: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_certificates_rotated_total",
			Help: "Total number of certificates rotated",
		}),
		verificationAttempts: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_verification_attempts_total",
			Help: "Total number of certificate verification attempts",
		}),
		verificationSuccesses: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_verification_successes_total",
			Help: "Total number of successful certificate verifications",
		}),
		verificationFailures: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_verification_failures_total",
			Help: "Total number of failed certificate verifications",
		}),
		crlGenerations: promauto.NewCounter(prometheus.CounterOpts{
			Name: "certmanager_crl_generations_total",
			Help: "Total number of CRL generations",
		}),
		activeCerts: promauto.NewGauge(prometheus.GaugeOpts{
			Name: "certmanager_active_certificates",
			Help: "Number of currently active certificates",
		}),
		revokedCerts: promauto.NewGauge(prometheus.GaugeOpts{
			Name: "certmanager_revoked_certificates",
			Help: "Number of revoked certificates",
		}),
		expiringSoon: promauto.NewGauge(prometheus.GaugeOpts{
			Name: "certmanager_expiring_soon_certificates",
			Help: "Number of certificates expiring in next 30 days",
		}),
		operationLatency: promauto.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "certmanager_operation_duration_seconds",
			Help:    "Duration of certificate manager operations",
			Buckets: []float64{0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10},
		}, []string{"operation"}),
	}
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			certs:       make(map[string]*Certificate),
			revocations: make(map[string]*RevocationEntry),
			rotationPol: RotationPolicy{
				Enabled:      true,
				RotateBefore: 30,
				MaxRetries:   3,
			},
			promMetrics: newPrometheusMetrics(),
		}
		instance.generateRootCA()
		go instance.startMetricsUpdater()
	})
	return instance
}

func (m *Manager) startMetricsUpdater() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m.updateGauges()
	}
}

func (m *Manager) updateGauges() {
	m.mu.RLock()
	defer m.mu.RUnlock()

	activeCount := 0
	expiringCount := 0
	threshold := time.Now().AddDate(0, 0, 30)

	for _, cert := range m.certs {
		if cert.Status == "active" {
			activeCount++
			if cert.NotAfter.Before(threshold) {
				expiringCount++
			}
		}
	}

	m.promMetrics.activeCerts.Set(float64(activeCount))
	m.promMetrics.revokedCerts.Set(float64(len(m.revocations)))
	m.promMetrics.expiringSoon.Set(float64(expiringCount))

	m.metricsMu.Lock()
	m.metrics.ActiveCerts = int64(activeCount)
	m.metrics.RevokedCerts = int64(len(m.revocations))
	m.metrics.ExpiringSoon = int64(expiringCount)
	m.metricsMu.Unlock()
}

func (m *Manager) recordLatency(operation string, duration time.Duration) {
	m.promMetrics.operationLatency.WithLabelValues(operation).Observe(duration.Seconds())

	m.metricsMu.Lock()
	defer m.metricsMu.Unlock()

	switch operation {
	case "issue":
		m.latency.IssueCertificate = duration
	case "revoke":
		m.latency.RevokeCertificate = duration
	case "rotate":
		m.latency.RotateCertificate = duration
	case "verify":
		m.latency.VerifyCertificate = duration
	case "crl":
		m.latency.GenerateCRL = duration
	}
}

func (m *Manager) generateRootCA() error {
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return err
	}

	template := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject: pkix.Name{
			Organization: []string{"Enterprise Config Platform"},
			CommonName:   "Root CA",
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, &template, &template, &priv.PublicKey, priv)
	if err != nil {
		return err
	}

	cert, _ := x509.ParseCertificate(derBytes)
	m.rootCA = cert
	m.rootCAKey = priv
	m.rootCertPEM = string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes}))

	return nil
}

func (m *Manager) IssueCertificate(commonName string, dnsNames []string, autoRotate bool, rotateDays int) (*Certificate, error) {
	startTime := time.Now()
	defer func() {
		m.recordLatency("issue", time.Since(startTime))
	}()

	m.mu.Lock()
	defer m.mu.Unlock()

	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}

	serialNumber := big.NewInt(time.Now().UnixNano())
	template := x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"Enterprise Config Platform"},
			CommonName:   commonName,
		},
		DNSNames:     dnsNames,
		NotBefore:    time.Now(),
		NotAfter:     time.Now().AddDate(1, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth, x509.ExtKeyUsageServerAuth},
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, &template, m.rootCA, &priv.PublicKey, m.rootCAKey)
	if err != nil {
		return nil, err
	}

	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: derBytes})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(priv)})

	cert := &Certificate{
		ID:           utils.GenerateID("cert"),
		CommonName:   commonName,
		SerialNumber: serialNumber.String(),
		CertPEM:      string(certPEM),
		KeyPEM:       string(keyPEM),
		Issuer:       m.rootCA.Subject.CommonName,
		NotBefore:    template.NotBefore,
		NotAfter:     template.NotAfter,
		Status:       "active",
		AutoRotate:   autoRotate,
		RotateDays:   rotateDays,
		CreatedAt:    time.Now().UTC(),
	}

	m.certs[cert.ID] = cert

	m.promMetrics.certificatesIssued.Inc()
	m.metricsMu.Lock()
	m.metrics.CertificatesIssued++
	m.metricsMu.Unlock()

	return cert, nil
}

func (m *Manager) RevokeCertificate(certID, reason string) error {
	startTime := time.Now()
	defer func() {
		m.recordLatency("revoke", time.Since(startTime))
	}()

	m.mu.Lock()
	defer m.mu.Unlock()

	cert, exists := m.certs[certID]
	if !exists {
		return errors.New("certificate not found")
	}

	cert.Status = "revoked"
	m.revocations[cert.SerialNumber] = &RevocationEntry{
		SerialNumber: cert.SerialNumber,
		Reason:       reason,
		RevokedAt:    time.Now().UTC(),
	}

	m.promMetrics.certificatesRevoked.Inc()
	m.metricsMu.Lock()
	m.metrics.CertificatesRevoked++
	m.metricsMu.Unlock()

	return nil
}

func (m *Manager) IsRevoked(serialNumber string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, revoked := m.revocations[serialNumber]
	return revoked
}

func (m *Manager) GetCertificate(certID string) (*Certificate, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	cert, exists := m.certs[certID]
	if !exists {
		return nil, errors.New("certificate not found")
	}
	return cert, nil
}

func (m *Manager) ListCertificates() []*Certificate {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*Certificate, 0, len(m.certs))
	for _, cert := range m.certs {
		result = append(result, cert)
	}
	return result
}

func (m *Manager) GetRootCAPEM() string {
	return m.rootCertPEM
}

func (m *Manager) SetRotationPolicy(policy RotationPolicy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rotationPol = policy
}

func (m *Manager) GetRotationPolicy() RotationPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.rotationPol
}

func (m *Manager) RotateCertificate(certID string) (*Certificate, error) {
	startTime := time.Now()
	defer func() {
		m.recordLatency("rotate", time.Since(startTime))
	}()

	cert, err := m.GetCertificate(certID)
	if err != nil {
		return nil, err
	}

	dnsNames := []string{cert.CommonName}
	newCert, err := m.IssueCertificate(cert.CommonName, dnsNames, cert.AutoRotate, cert.RotateDays)
	if err != nil {
		return nil, err
	}

	m.RevokeCertificate(certID, "rotation")

	m.promMetrics.certificatesRotated.Inc()
	m.metricsMu.Lock()
	m.metrics.CertificatesRotated++
	m.metricsMu.Unlock()

	return newCert, nil
}

func (m *Manager) CheckAndRotate() []*Certificate {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var rotated []*Certificate
	threshold := time.Now().AddDate(0, 0, m.rotationPol.RotateBefore)

	for _, cert := range m.certs {
		if cert.Status == "active" && cert.AutoRotate && cert.NotAfter.Before(threshold) {
			if newCert, err := m.RotateCertificate(cert.ID); err == nil {
				rotated = append(rotated, newCert)
			}
		}
	}
	return rotated
}

func (m *Manager) GenerateCRL() (string, error) {
	startTime := time.Now()
	defer func() {
		m.recordLatency("crl", time.Since(startTime))
	}()

	m.mu.RLock()
	defer m.mu.RUnlock()

	var revokedCerts []pkix.RevokedCertificate
	for _, entry := range m.revocations {
		serial := new(big.Int)
		serial.SetString(entry.SerialNumber, 10)
		revokedCerts = append(revokedCerts, pkix.RevokedCertificate{
			SerialNumber:   serial,
			RevocationTime: entry.RevokedAt,
		})
	}

	crlBytes, err := m.rootCA.CreateCRL(rand.Reader, m.rootCAKey, revokedCerts, time.Now(), time.Now().AddDate(0, 0, 7))
	if err != nil {
		return "", err
	}

	m.promMetrics.crlGenerations.Inc()
	m.metricsMu.Lock()
	m.metrics.CRLGenerations++
	m.metricsMu.Unlock()

	return string(pem.EncodeToMemory(&pem.Block{Type: "X509 CRL", Bytes: crlBytes})), nil
}

func (m *Manager) VerifyCertificate(certPEM string) (bool, error) {
	startTime := time.Now()
	defer func() {
		m.recordLatency("verify", time.Since(startTime))
	}()

	m.promMetrics.verificationAttempts.Inc()
	m.metricsMu.Lock()
	m.metrics.VerificationAttempts++
	m.metricsMu.Unlock()

	block, _ := pem.Decode([]byte(certPEM))
	if block == nil {
		m.promMetrics.verificationFailures.Inc()
		m.metricsMu.Lock()
		m.metrics.VerificationFailures++
		m.metricsMu.Unlock()
		return false, errors.New("failed to parse certificate PEM")
	}

	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		m.promMetrics.verificationFailures.Inc()
		m.metricsMu.Lock()
		m.metrics.VerificationFailures++
		m.metricsMu.Unlock()
		return false, err
	}

	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM([]byte(m.rootCertPEM))

	opts := x509.VerifyOptions{
		Roots: roots,
	}

	if _, err := cert.Verify(opts); err != nil {
		m.promMetrics.verificationFailures.Inc()
		m.metricsMu.Lock()
		m.metrics.VerificationFailures++
		m.metricsMu.Unlock()
		return false, fmt.Errorf("verification failed: %w", err)
	}

	if m.IsRevoked(cert.SerialNumber.String()) {
		m.promMetrics.verificationFailures.Inc()
		m.metricsMu.Lock()
		m.metrics.VerificationFailures++
		m.metricsMu.Unlock()
		return false, errors.New("certificate revoked")
	}

	m.promMetrics.verificationSuccesses.Inc()
	m.metricsMu.Lock()
	m.metrics.VerificationSuccesses++
	m.metricsMu.Unlock()

	return true, nil
}

func (m *Manager) GetMetrics() ManagerMetrics {
	m.metricsMu.RLock()
	defer m.metricsMu.RUnlock()
	return m.metrics
}

func (m *Manager) GetLatency() OperationLatency {
	m.metricsMu.RLock()
	defer m.metricsMu.RUnlock()
	return m.latency
}

func (m *Manager) GetStats() map[string]interface{} {
	m.updateGauges()
	return map[string]interface{}{
		"metrics": m.GetMetrics(),
		"latency": m.GetLatency(),
	}
}
