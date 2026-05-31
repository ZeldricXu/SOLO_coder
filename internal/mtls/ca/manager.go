package ca

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type Manager struct {
	caCert *domain.CACert
	logger *zap.Logger
}

func NewManager(commonName string, orgName string, logger *zap.Logger) (ports.CAStore, error) {
	if logger == nil {
		logger = zap.NewNop()
	}
	caCert, err := generateCA(commonName, orgName, logger)
	if err != nil {
		return nil, err
	}
	return &Manager{
		caCert: caCert,
		logger: logger,
	}, nil
}

func generateCA(commonName string, orgName string, logger *zap.Logger) (*domain.CACert, error) {
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, fmt.Errorf("generate CA private key: %w", err)
	}

	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("generate serial number: %w", err)
	}

	template := &x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{orgName},
			CommonName:   commonName,
		},
		NotBefore:             time.Now(),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            2,
		SignatureAlgorithm:    x509.SHA256WithRSA,
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, template, template, &priv.PublicKey, priv)
	if err != nil {
		return nil, fmt.Errorf("create CA certificate: %w", err)
	}

	certPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "CERTIFICATE",
		Bytes: derBytes,
	})

	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(priv),
	})

	caCert, err := x509.ParseCertificate(derBytes)
	if err != nil {
		return nil, fmt.Errorf("parse CA certificate: %w", err)
	}

	logger.Info("CA certificate generated",
		zap.String("common_name", commonName),
		zap.String("organization", orgName),
		zap.Time("expires_at", template.NotAfter),
	)

	return &domain.CACert{
		Cert:       caCert,
		PrivateKey: priv,
		CertPEM:    string(certPEM),
		KeyPEM:     string(keyPEM),
	}, nil
}

func (m *Manager) IssueCertificate(req *domain.CertificateRequest) (*domain.Certificate, error) {
	if req.CommonName == "" {
		return nil, fmt.Errorf("common name is required")
	}
	if req.Validity <= 0 {
		req.Validity = 90 * 24 * time.Hour
	}
	if req.KeySize <= 0 {
		req.KeySize = 2048
	}

	priv, err := rsa.GenerateKey(rand.Reader, req.KeySize)
	if err != nil {
		return nil, fmt.Errorf("generate private key failed: %w", err)
	}

	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, fmt.Errorf("generate serial number failed: %w", err)
	}

	template := &x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			Organization: []string{"ChaosLab"},
			CommonName:   req.CommonName,
		},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(req.Validity),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth, x509.ExtKeyUsageServerAuth},
		DNSNames:     req.DNSNames,
		SubjectKeyId: []byte{1, 2, 3, 4, 6},
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, template, m.caCert.Cert, &priv.PublicKey, m.caCert.PrivateKey)
	if err != nil {
		return nil, fmt.Errorf("sign certificate failed: %w", err)
	}

	certPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "CERTIFICATE",
		Bytes: derBytes,
	})

	keyPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "RSA PRIVATE KEY",
		Bytes: x509.MarshalPKCS1PrivateKey(priv),
	})

	parsedCert, err := x509.ParseCertificate(derBytes)
	if err != nil {
		return nil, fmt.Errorf("parse certificate failed: %w", err)
	}

	cert := &domain.Certificate{
		CertID:     fmt.Sprintf("cert_%x", serialNumber.Bytes()[:8]),
		Namespace:  req.Namespace,
		CommonName: req.CommonName,
		DNSNames:   req.DNSNames,
		IssuedAt:   parsedCert.NotBefore,
		ExpiresAt:  parsedCert.NotAfter,
		Serial:     serialNumber.String(),
		CertPEM:    string(certPEM),
		KeyPEM:     string(keyPEM),
	}

	m.logger.Info("certificate issued",
		zap.String("cert_id", cert.CertID),
		zap.String("common_name", cert.CommonName),
		zap.String("namespace", req.Namespace),
		zap.Time("expires_at", cert.ExpiresAt),
	)

	return cert, nil
}

func (m *Manager) GetCACert() *domain.CACert {
	return m.caCert
}

func ParseCertificatePEM(pemData string) (*x509.Certificate, error) {
	block, _ := pem.Decode([]byte(pemData))
	if block == nil {
		return nil, fmt.Errorf("invalid PEM data")
	}
	return x509.ParseCertificate(block.Bytes)
}
