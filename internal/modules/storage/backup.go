package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type BackupType string

const (
	BackupTypeFull     BackupType = "full"
	BackupTypeIncremental BackupType = "incremental"
	BackupTypeTable    BackupType = "table"
)

type BackupStatus string

const (
	BackupStatusPending   BackupStatus = "pending"
	BackupStatusRunning   BackupStatus = "running"
	BackupStatusCompleted BackupStatus = "completed"
	BackupStatusFailed    BackupStatus = "failed"
)

type BackupRecord struct {
	BackupID    string                 `json:"backup_id" gorm:"primaryKey;type:varchar(64)"`
	Name        string                 `json:"name"`
	Type        BackupType             `json:"type" gorm:"type:varchar(32);index"`
	Status      BackupStatus           `json:"status" gorm:"type:varchar(32);index"`
	Tables      []string               `json:"tables" gorm:"type:text[]"`
	FilePath    string                 `json:"file_path"`
	FileSize    int64                  `json:"file_size"`
	Checksum    string                 `json:"checksum"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	Error       string                 `json:"error,omitempty"`
	CreatedBy   string                 `json:"created_by"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	CreatedAt   time.Time              `json:"created_at" gorm:"index"`
}

func (BackupRecord) TableName() string {
	return "backup_records"
}

type BackupManager struct {
	backupDir     string
	pgDumpPath    string
	maxConcurrent int
	activeBackups int
}

func NewBackupManager(backupDir string) *BackupManager {
	_ = os.MkdirAll(backupDir, 0755)
	return &BackupManager{
		backupDir:     backupDir,
		pgDumpPath:    "pg_dump",
		maxConcurrent: 2,
	}
}

func (m *BackupManager) CreateBackup(ctx context.Context, backupType BackupType, tables []string, name, createdBy string) (*BackupRecord, error) {
	record := &BackupRecord{
		BackupID:  uuid.New().String(),
		Name:      name,
		Type:      backupType,
		Status:    BackupStatusPending,
		Tables:    tables,
		CreatedBy: createdBy,
		CreatedAt: time.Now(),
	}

	if name == "" {
		record.Name = fmt.Sprintf("backup-%s-%s", backupType, time.Now().Format("20060102150405"))
	}

	if err := database.DB.WithContext(ctx).Create(record).Error; err != nil {
		return nil, fmt.Errorf("create backup record failed: %w", err)
	}

	go m.executeBackup(record)

	return record, nil
}

func (m *BackupManager) executeBackup(record *BackupRecord) {
	ctx := context.Background()
	now := time.Now()
	record.StartedAt = &now
	record.Status = BackupStatusRunning
	_ = database.DB.Model(record).Updates(map[string]interface{}{
		"status":     BackupStatusRunning,
		"started_at": now,
	})

	fileName := fmt.Sprintf("%s.sql", record.BackupID)
	filePath := filepath.Join(m.backupDir, fileName)
	record.FilePath = filePath

	logger.Info("Starting backup",
		zap.String("backup_id", record.BackupID),
		zap.String("type", string(record.Type)))

	var err error
	switch record.Type {
	case BackupTypeFull:
		err = m.backupFull(ctx, filePath)
	case BackupTypeTable:
		err = m.backupTables(ctx, filePath, record.Tables)
	default:
		err = m.backupFull(ctx, filePath)
	}

	now = time.Now()
	record.CompletedAt = &now

	if err != nil {
		record.Status = BackupStatusFailed
		record.Error = err.Error()
		logger.Error("Backup failed",
			zap.String("backup_id", record.BackupID),
			zap.Error(err))
	} else {
		record.Status = BackupStatusCompleted

		fileInfo, statErr := os.Stat(filePath)
		if statErr == nil {
			record.FileSize = fileInfo.Size()
		}

		record.Checksum = m.calculateChecksum(filePath)
		record.Metadata = map[string]interface{}{
			"backup_tool": "pg_dump",
			"format":      "sql",
		}

		logger.Info("Backup completed",
			zap.String("backup_id", record.BackupID),
			zap.Int64("size", record.FileSize))
	}

	_ = database.DB.Model(record).Updates(map[string]interface{}{
		"status":       record.Status,
		"completed_at": record.CompletedAt,
		"error":        record.Error,
		"file_size":    record.FileSize,
		"checksum":     record.Checksum,
		"metadata":     record.Metadata,
	})
}

func (m *BackupManager) backupFull(ctx context.Context, filePath string) error {
	dsn := database.DB.Config.DSN
	dsnMap := parseDSN(dsn)

	args := []string{
		"--host", dsnMap["host"],
		"--port", dsnMap["port"],
		"--username", dsnMap["user"],
		"--dbname", dsnMap["dbname"],
		"--file", filePath,
		"--no-owner",
		"--no-privileges",
	}

	cmd := exec.CommandContext(ctx, m.pgDumpPath, args...)
	cmd.Env = append(os.Environ(), fmt.Sprintf("PGPASSWORD=%s", dsnMap["password"]))

	var stderr io.Writer = os.Stderr
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("pg_dump failed: %w, output: %s", err, string(output))
	}
	_ = stderr

	return nil
}

func (m *BackupManager) backupTables(ctx context.Context, filePath string, tables []string) error {
	if len(tables) == 0 {
		return fmt.Errorf("no tables specified for table backup")
	}

	dsn := database.DB.Config.DSN
	dsnMap := parseDSN(dsn)

	args := []string{
		"--host", dsnMap["host"],
		"--port", dsnMap["port"],
		"--username", dsnMap["user"],
		"--dbname", dsnMap["dbname"],
		"--file", filePath,
		"--no-owner",
		"--no-privileges",
	}

	for _, table := range tables {
		args = append(args, "--table", table)
	}

	cmd := exec.CommandContext(ctx, m.pgDumpPath, args...)
	cmd.Env = append(os.Environ(), fmt.Sprintf("PGPASSWORD=%s", dsnMap["password"]))

	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("pg_dump failed: %w, output: %s", err, string(output))
	}

	return nil
}

func (m *BackupManager) calculateChecksum(filePath string) string {
	data, err := os.ReadFile(filePath)
	if err != nil {
		return ""
	}
	return fmt.Sprintf("%x", len(data))
}

func (m *BackupManager) GetBackup(ctx context.Context, backupID string) (*BackupRecord, error) {
	var record BackupRecord
	if err := database.DB.WithContext(ctx).Where("backup_id = ?", backupID).First(&record).Error; err != nil {
		return nil, fmt.Errorf("get backup failed: %w", err)
	}
	return &record, nil
}

func (m *BackupManager) ListBackups(ctx context.Context, status BackupStatus, offset, limit int) ([]BackupRecord, int64, error) {
	var records []BackupRecord
	var total int64

	query := database.DB.WithContext(ctx).Model(&BackupRecord{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count backups failed: %w", err)
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&records).Error; err != nil {
		return nil, 0, fmt.Errorf("list backups failed: %w", err)
	}

	return records, total, nil
}

func (m *BackupManager) DeleteBackup(ctx context.Context, backupID string) error {
	var record BackupRecord
	if err := database.DB.WithContext(ctx).Where("backup_id = ?", backupID).First(&record).Error; err != nil {
		return fmt.Errorf("backup not found: %w", err)
	}

	if record.FilePath != "" {
		_ = os.Remove(record.FilePath)
	}

	if err := database.DB.WithContext(ctx).Delete(&record).Error; err != nil {
		return fmt.Errorf("delete backup failed: %w", err)
	}

	logger.Info("Backup deleted", zap.String("backup_id", backupID))
	return nil
}

func (m *BackupManager) DownloadBackup(ctx context.Context, backupID string) (string, io.ReadCloser, error) {
	record, err := m.GetBackup(ctx, backupID)
	if err != nil {
		return "", nil, err
	}

	if record.Status != BackupStatusCompleted {
		return "", nil, fmt.Errorf("backup not completed")
	}

	file, err := os.Open(record.FilePath)
	if err != nil {
		return "", nil, fmt.Errorf("open backup file failed: %w", err)
	}

	return filepath.Base(record.FilePath), file, nil
}

func parseDSN(dsn string) map[string]string {
	result := make(map[string]string)
	parts := splitDSN(dsn)
	for _, part := range parts {
		kv := splitFirst(part, "=")
		if len(kv) == 2 {
			result[kv[0]] = kv[1]
		}
	}
	return result
}

func splitDSN(dsn string) []string {
	var parts []string
	var current string
	inQuote := false
	for _, c := range dsn {
		if c == '\'' {
			inQuote = !inQuote
		} else if c == ' ' && !inQuote {
			if current != "" {
				parts = append(parts, current)
				current = ""
			}
			continue
		}
		current += string(c)
	}
	if current != "" {
		parts = append(parts, current)
	}
	return parts
}

func splitFirst(s string, sep string) []string {
	for i := 0; i < len(s); i++ {
		if s[i] == sep[0] {
			return []string{s[:i], s[i+1:]}
		}
	}
	return []string{s}
}

import (
	"strings"
	"gorm.io/gorm"
)
