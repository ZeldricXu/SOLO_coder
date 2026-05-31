package ports

import (
	"context"

	"github.com/apishield/apishield/internal/core/models"
	"github.com/google/uuid"
)

type SecurityModule interface {
	Initialize(ctx context.Context, config *models.Config) error
	Start(ctx context.Context) (*models.RunInstance, error)
	Stop(ctx context.Context, instanceID uuid.UUID) error
	Validate(ctx context.Context, data map[string]any) error
	Process(ctx context.Context, instanceID uuid.UUID, input map[string]any) (map[string]any, error)
	GetStatus(ctx context.Context, instanceID uuid.UUID) (models.EntityStatus, error)
	Shutdown(ctx context.Context) error
}

type ResourceManager interface {
	CreateEntity(ctx context.Context, entity *models.Entity) (*models.Entity, error)
	GetEntity(ctx context.Context, id uuid.UUID) (*models.Entity, error)
	UpdateEntity(ctx context.Context, entity *models.Entity) (*models.Entity, error)
	DeleteEntity(ctx context.Context, id uuid.UUID) error
	ListEntities(ctx context.Context, filters map[string]string) ([]*models.Entity, error)

	CreateConfig(ctx context.Context, config *models.Config) (*models.Config, error)
	GetConfig(ctx context.Context, id uuid.UUID) (*models.Config, error)
	UpdateConfig(ctx context.Context, config *models.Config) (*models.Config, error)
	DeleteConfig(ctx context.Context, id uuid.UUID) error
	ListConfigs(ctx context.Context, entityID uuid.UUID) ([]*models.Config, error)

	CreateSnapshot(ctx context.Context, snapshot *models.Snapshot) (*models.Snapshot, error)
	GetSnapshot(ctx context.Context, id uuid.UUID) (*models.Snapshot, error)
	ListSnapshots(ctx context.Context, entityID uuid.UUID) ([]*models.Snapshot, error)
	RestoreSnapshot(ctx context.Context, snapshotID uuid.UUID) error
}

type Event struct {
	ID        uuid.UUID
	Type      string
	Source    string
	Payload   map[string]any
	Timestamp int64
}

type EventHandler func(ctx context.Context, event Event) error

type EventPublisher interface {
	Publish(ctx context.Context, event Event) error
	Subscribe(ctx context.Context, eventType string, handler EventHandler) error
	Unsubscribe(ctx context.Context, eventType string, handlerID string) error
	Close(ctx context.Context) error
}

type CryptoProvider interface {
	Encrypt(ctx context.Context, plaintext []byte, keyID string) ([]byte, error)
	Decrypt(ctx context.Context, ciphertext []byte, keyID string) ([]byte, error)
	Sign(ctx context.Context, data []byte, keyID string) ([]byte, error)
	Verify(ctx context.Context, data []byte, signature []byte, keyID string) (bool, error)
	Hash(ctx context.Context, data []byte) (string, error)
	GenerateKey(ctx context.Context) (string, error)
	RotateKey(ctx context.Context, keyID string) (string, error)
}

type Logger interface {
	Debug(ctx context.Context, msg string, fields map[string]any)
	Info(ctx context.Context, msg string, fields map[string]any)
	Warn(ctx context.Context, msg string, fields map[string]any)
	Error(ctx context.Context, msg string, err error, fields map[string]any)
	Fatal(ctx context.Context, msg string, err error, fields map[string]any)
	WithFields(fields map[string]any) Logger
	Close() error
}

type MPCProtocol string

const (
	ProtocolGarbledCircuit       MPCProtocol = "garbled_circuit"
	ProtocolSecretSharing        MPCProtocol = "secret_sharing"
	ProtocolHomomorphicEncryption MPCProtocol = "homomorphic_encryption"
)

type MPCService interface {
	StartProtocol(ctx context.Context, protocol MPCProtocol, participants []string) (string, error)
	SubmitInput(ctx context.Context, sessionID string, participant string, input []byte) error
	GetResult(ctx context.Context, sessionID string) ([]byte, error)
	CancelProtocol(ctx context.Context, sessionID string) error
}

type DataLevel string

const (
	LevelPublic       DataLevel = "public"
	LevelInternal     DataLevel = "internal"
	LevelConfidential DataLevel = "confidential"
	LevelTopSecret    DataLevel = "top_secret"
)

type SensitiveDataType string

const (
	SensitivePhone     SensitiveDataType = "phone"
	SensitiveEmail     SensitiveDataType = "email"
	SensitiveIDCard    SensitiveDataType = "id_card"
	SensitiveBankCard  SensitiveDataType = "bank_card"
)

type SensitiveDataMatch struct {
	Type     SensitiveDataType
	Value    string
	Position int
	Length   int
}

type DataClassification struct {
	DataID       string
	Level        DataLevel
	Matches      []*SensitiveDataMatch
	ScanTime     int64
	Confidence   float64
	PolicyApplied bool
	PolicyAction  string
}

type ClassificationPolicy struct {
	ID          string
	Name        string
	Description string
	Level       DataLevel
	Action      string
	Rules       map[string]any
	IsActive    bool
}

type DataScanRequest struct {
	DataID      string
	Content     string
	DeepScan    bool
	Fields      []string
}

type DataClassifierPort interface {
	ScanData(ctx context.Context, req *DataScanRequest) (*DataClassification, error)
	Classify(ctx context.Context, dataID string, content string) (*DataClassification, error)
	ApplyPolicy(ctx context.Context, classification *DataClassification, policy *ClassificationPolicy) error
	GetClassification(ctx context.Context, dataID string) (*DataClassification, error)
}
