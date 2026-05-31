package domain

import (
	"crypto/x509"
	"time"
)

type CACert struct {
	Cert       *x509.Certificate
	PrivateKey interface{}
	CertPEM    string
	KeyPEM     string
}

type Certificate struct {
	CertID     string     `json:"cert_id" gorm:"primaryKey"`
	Namespace  string     `json:"namespace"`
	CommonName string     `json:"common_name"`
	DNSNames   []string   `json:"dns_names" gorm:"serializer:json"`
	IssuedAt   time.Time  `json:"issued_at"`
	ExpiresAt  time.Time  `json:"expires_at"`
	Revoked    bool       `json:"revoked"`
	RevokedAt  *time.Time `json:"revoked_at,omitempty"`
	Serial     string     `json:"serial"`
	CertPEM    string     `json:"cert_pem"`
	KeyPEM     string     `json:"key_pem"`
}

type CertificateRequest struct {
	CommonName string        `json:"common_name"`
	DNSNames   []string      `json:"dns_names"`
	Namespace  string        `json:"namespace"`
	Validity   time.Duration `json:"validity"`
	KeySize    int           `json:"key_size"`
}

type RotationPolicy struct {
	Namespace       string        `json:"namespace"`
	AutoRotate      bool          `json:"auto_rotate"`
	RotationWindow  time.Duration `json:"rotation_window"`
	ValidityPeriod  time.Duration `json:"validity_period"`
	AlertBeforeDays int           `json:"alert_before_days"`
}

type CRL struct {
	IssuerCN     string         `json:"issuer_cn"`
	RevokedCerts []*RevokedCert `json:"revoked_certs"`
	LastUpdate   time.Time      `json:"last_update"`
	NextUpdate   time.Time      `json:"next_update"`
	PEM          string         `json:"pem"`
}

type RevokedCert struct {
	Serial     string    `json:"serial"`
	CommonName string    `json:"common_name"`
	RevokedAt  time.Time `json:"revoked_at"`
	Reason     string    `json:"reason"`
}
