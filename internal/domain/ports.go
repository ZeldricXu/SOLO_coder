package domain

import (
	"context"
	"io"
	"time"
)

type Logger interface {
	Debug(msg string, fields ...interface{})
	Info(msg string, fields ...interface{})
	Warn(msg string, fields ...interface{})
	Error(msg string, fields ...interface{})
	Fatal(msg string, fields ...interface{})
	WithTraceID(traceID string) Logger
	Sync() error
}

type LogRotator interface {
	Rotate() error
	Archive(filename string) error
	Cleanup(retention time.Duration) error
}

type StorageManager interface {
	Backup(ctx context.Context, source string) (*BackupInfo, error)
	Restore(ctx context.Context, backupID string, dest string) error
	ListBackups(ctx context.Context) ([]BackupInfo, error)
	DeleteBackup(ctx context.Context, backupID string) error
}

type DataProcessor interface {
	Transform(ctx context.Context, data map[string]interface{}, rules map[string]interface{}) (map[string]interface{}, error)
	Normalize(ctx context.Context, data map[string]interface{}) (map[string]interface{}, error)
	Validate(ctx context.Context, data map[string]interface{}) error
}

type ConfigManager interface {
	Load(ctx context.Context, namespace string) (*ConfigDefinition, error)
	Save(ctx context.Context, config *ConfigDefinition) error
	Validate(config *ConfigDefinition) error
	GetDefault(namespace string) map[string]interface{}
}

type Monitor interface {
	RecordMetric(name string, value float64, dimensions map[string]string)
	EvaluateRules(ctx context.Context) ([]Alert, error)
	Notify(ctx context.Context, alert Alert) error
	GetSnapshot(ctx context.Context) (*MetricsSnapshot, error)
}

type AuditTrail interface {
	Record(ctx context.Context, record *AuditRecord) error
	VerifyIntegrity(ctx context.Context) (bool, []string, error)
	List(ctx context.Context, limit, offset int) ([]AuditRecord, error)
}

type DataMasker interface {
	Mask(ctx context.Context, data map[string]interface{}, user *User) (map[string]interface{}, error)
	RegisterSensitiveField(fieldPath string, roles []UserRole)
	IsSensitive(fieldPath string) bool
}

type DataAccessor interface {
	Migrate(ctx context.Context, targetVersion int) error
	GetSchemaVersion(ctx context.Context) (int, error)
	SaveRecord(ctx context.Context, record *DataRecord) error
	GetRecord(ctx context.Context, id string) (*DataRecord, error)
	QueryRecords(ctx context.Context, filter map[string]interface{}) ([]DataRecord, error)
}

type FLCoordinator interface {
	RegisterClient(clientID string) error
	DistributeTask(ctx context.Context, model *FLModel, clientID string) (*FLTask, error)
	AggregateGradients(ctx context.Context, taskID string, gradient []float64) error
	UpdateGlobalModel(ctx context.Context, modelID string) (*FLModel, error)
	GetGlobalModel(ctx context.Context, modelID string) (*FLModel, error)
}

type Hasher interface {
	Hash(data []byte) string
}

type Encryptor interface {
	Encrypt(plaintext []byte) ([]byte, error)
	Decrypt(ciphertext []byte) ([]byte, error)
}

type Serializer interface {
	Serialize(v interface{}) ([]byte, error)
	Deserialize(data []byte, v interface{}) error
}

type FileWriter interface {
	Open(name string) (io.WriteCloser, error)
	ReadDir(name string) ([]string, error)
	Remove(name string) error
	Rename(oldpath, newpath string) error
}

type Clock interface {
	Now() time.Time
	After(d time.Duration) <-chan time.Time
}
