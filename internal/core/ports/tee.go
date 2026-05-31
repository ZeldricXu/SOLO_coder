package ports

import (
	"context"
	"time"
)

type EnclaveType string

const (
	EnclaveTypeSGX      EnclaveType = "sgx"
	EnclaveTypeSEV      EnclaveType = "sev"
	EnclaveTypeTrustZone EnclaveType = "trustzone"
	EnclaveTypeHSM      EnclaveType = "hsm"
)

type EnclaveStatus string

const (
	EnclaveStatusCreated   EnclaveStatus = "created"
	EnclaveStatusRunning   EnclaveStatus = "running"
	EnclaveStatusPaused    EnclaveStatus = "paused"
	EnclaveStatusDestroyed EnclaveStatus = "destroyed"
	EnclaveStatusAttested  EnclaveStatus = "attested"
)

type EnclaveConfig struct {
	EnclaveID   string
	Type        EnclaveType
	MemorySize  int64
	CPUNum      int
	ImagePath   string
	Environment map[string]string
	Labels      map[string]string
}

type EnclaveInfo struct {
	EnclaveID   string
	Type        EnclaveType
	Status      EnclaveStatus
	MemorySize  int64
	CPUNum      int
	Endpoint    string
	PublicKey   []byte
	CreatedAt   time.Time
	UpdatedAt   time.Time
	Labels      map[string]string
}

type AttestationRequest struct {
	EnclaveID    string
	Nonce        string
	Challenge    []byte
	QuoteFormat  string
}

type AttestationReport struct {
	EnclaveID    string
	Quote        []byte
	Signature    []byte
	PublicKey    []byte
	Measurements map[string]string
	Timestamp    time.Time
	Valid        bool
}

type SecureExecutionRequest struct {
	EnclaveID   string
	Function    string
	Payload     []byte
	Encrypted   bool
	Timeout     time.Duration
}

type SecureExecutionResult struct {
	Result      []byte
	Signature   []byte
	ExecTime    time.Duration
	Success     bool
	ErrorMsg    string
}

type DomainEvent struct {
	EventID     string
	EventType   string
	AggregateID string
	Timestamp   time.Time
	Payload     map[string]interface{}
}

type EnclaveManager interface {
	CreateEnclave(ctx context.Context, config *EnclaveConfig) (*EnclaveInfo, error)
	DestroyEnclave(ctx context.Context, enclaveID string) error
	ListEnclaves(ctx context.Context) ([]*EnclaveInfo, error)
	GetEnclave(ctx context.Context, enclaveID string) (*EnclaveInfo, error)
}

type AttestationVerifier interface {
	RemoteAttestation(ctx context.Context, req *AttestationRequest) (*AttestationReport, error)
	VerifyAttestation(ctx context.Context, report *AttestationReport) (bool, error)
}

type TEEPort interface {
	EnclaveManager
	AttestationVerifier
	SecureExecute(ctx context.Context, req *SecureExecutionRequest) (*SecureExecutionResult, error)
}

type EventPublisher interface {
	Publish(ctx context.Context, event *DomainEvent) error
}
