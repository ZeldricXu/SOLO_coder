package storage

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"go.uber.org/zap"
	"io"
	"metricplatform/internal/models"
	"metricplatform/pkg/dataaccess"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type BackupType string

const (
	BackupTypeFull     BackupType = "full"
	BackupTypeIncremental BackupType = "incremental"
	BackupTypeMetrics  BackupType = "metrics"
	BackupTypeLogs     BackupType = "logs"
)

type BackupConfig struct {
	BackupDir     string
	RetentionDays int
	Compress      bool
	Encrypt       bool
}

type Manager struct {
	repo       *dataaccess.Repository
	config     BackupConfig
	logger     *zap.Logger
	mu         sync.Mutex
	inProgress map[string]bool
}

func NewManager(repo *dataaccess.Repository, config BackupConfig, logger *zap.Logger) (*Manager, error) {
	if err := os.MkdirAll(config.BackupDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create backup directory: %w", err)
	}

	return &Manager{
		repo:       repo,
		config:     config,
		logger:     logger,
		inProgress: make(map[string]bool),
	}, nil
}

func (m *Manager) CreateBackup(ctx context.Context, backupType BackupType) (*models.BackupRecord, error) {
	m.mu.Lock()
	if m.inProgress[string(backupType)] {
		m.mu.Unlock()
		return nil, fmt.Errorf("backup already in progress for type: %s", backupType)
	}
	m.inProgress[string(backupType)] = true
	m.mu.Unlock()

	defer func() {
		m.mu.Lock()
		delete(m.inProgress, string(backupType))
		m.mu.Unlock()
	}()

	timestamp := time.Now()
	filename := fmt.Sprintf("backup_%s_%s.tar.gz", string(backupType), timestamp.Format("20060102_150405"))
	filepath := filepath.Join(m.config.BackupDir, filename)

	record := &models.BackupRecord{
		BackupType: string(backupType),
		FilePath:   filepath,
		Status:     "in_progress",
		StartedAt:  timestamp,
	}

	if err := m.repo.SaveBackupRecord(record); err != nil {
		return nil, fmt.Errorf("failed to create backup record: %w", err)
	}

	m.logger.Info("Starting backup", zap.String("type", string(backupType)), zap.String("file", filepath))

	size, checksum, err := m.performBackup(ctx, backupType, filepath)
	if err != nil {
		record.Status = "failed"
		record.CompletedAt = time.Now()
		m.repo.UpdateBackupRecord(record)
		return nil, fmt.Errorf("backup failed: %w", err)
	}

	now := time.Now()
	record.Status = "completed"
	record.SizeBytes = size
	record.CompletedAt = now
	record.Checksum = checksum

	if err := m.repo.UpdateBackupRecord(record); err != nil {
		m.logger.Error("Failed to update backup record", zap.Error(err))
	}

	m.logger.Info("Backup completed successfully",
		zap.String("type", string(backupType)),
		zap.String("file", filepath),
		zap.Int64("size", size))

	go m.cleanupOldBackups()

	return record, nil
}

func (m *Manager) performBackup(ctx context.Context, backupType BackupType, filePath string) (int64, string, error) {
	file, err := os.Create(filePath)
	if err != nil {
		return 0, "", fmt.Errorf("failed to create backup file: %w", err)
	}
	defer file.Close()

	var gzipWriter *gzip.Writer
	var tarWriter *tar.Writer

	if m.config.Compress {
		gzipWriter = gzip.NewWriter(file)
		defer gzipWriter.Close()
		tarWriter = tar.NewWriter(gzipWriter)
	} else {
		tarWriter = tar.NewWriter(file)
	}
	defer tarWriter.Close()

	hasher := sha256.New()
	multiWriter := io.MultiWriter(tarWriter, hasher)

	switch backupType {
	case BackupTypeFull:
		if err := m.backupAllData(ctx, multiWriter, tarWriter); err != nil {
			return 0, "", err
		}
	case BackupTypeMetrics:
		if err := m.backupMetrics(ctx, multiWriter, tarWriter); err != nil {
			return 0, "", err
		}
	case BackupTypeLogs:
		if err := m.backupLogs(ctx, multiWriter, tarWriter); err != nil {
			return 0, "", err
		}
	default:
		return 0, "", fmt.Errorf("unsupported backup type: %s", backupType)
	}

	if err := tarWriter.Flush(); err != nil {
		return 0, "", err
	}
	if gzipWriter != nil {
		if err := gzipWriter.Flush(); err != nil {
			return 0, "", err
		}
	}

	stat, err := file.Stat()
	if err != nil {
		return 0, "", err
	}

	checksum := hex.EncodeToString(hasher.Sum(nil))
	return stat.Size(), checksum, nil
}

func (m *Manager) backupAllData(ctx context.Context, w io.Writer, tw *tar.Writer) error {
	db := m.repo.GetDB()
	rows, err := db.Table("information_schema.tables").Where("table_schema = ?", "public").Pluck("table_name", &[]string{}).Rows()
	if err != nil {
		return err
	}
	defer rows.Close()

	var tables []string
	for rows.Next() {
		var table string
		rows.Scan(&table)
		tables = append(tables, table)
	}

	for _, table := range tables {
		if err := m.backupTable(ctx, table, w, tw); err != nil {
			m.logger.Warn("Failed to backup table", zap.String("table", table), zap.Error(err))
		}
	}

	return nil
}

func (m *Manager) backupTable(ctx context.Context, tableName string, w io.Writer, tw *tar.Writer) error {
	db := m.repo.GetDB()
	rows, err := db.Table(tableName).Rows()
	if err != nil {
		return err
	}
	defer rows.Close()

	columns, err := rows.Columns()
	if err != nil {
		return err
	}

	header := &tar.Header{
		Name: fmt.Sprintf("%s.csv", tableName),
		Mode: 0644,
	}

	var dataSize int64
	if err := tw.WriteHeader(header); err != nil {
		return err
	}

	headerRow := ""
	for i, col := range columns {
		if i > 0 {
			headerRow += ","
		}
		headerRow += col
	}
	headerRow += "\n"
	n, err := w.Write([]byte(headerRow))
	dataSize += int64(n)
	if err != nil {
		return err
	}

	for rows.Next() {
		values := make([]interface{}, len(columns))
		valuePtrs := make([]interface{}, len(columns))
		for i := range columns {
			valuePtrs[i] = &values[i]
		}

		if err := rows.Scan(valuePtrs...); err != nil {
			continue
		}

		row := ""
		for i, v := range values {
			if i > 0 {
				row += ","
			}
			if v != nil {
				row += fmt.Sprintf("%v", v)
			}
		}
		row += "\n"
		n, err := w.Write([]byte(row))
		dataSize += int64(n)
		if err != nil {
			return err
		}
	}

	header.Size = dataSize
	return nil
}

func (m *Manager) backupMetrics(ctx context.Context, w io.Writer, tw *tar.Writer) error {
	end := time.Now()
	start := end.AddDate(0, 0, -7)

	points, err := m.repo.GetMetricDataPoints("", start, end)
	if err != nil {
		return err
	}

	header := &tar.Header{
		Name: "metrics_data.csv",
		Mode: 0644,
	}
	if err := tw.WriteHeader(header); err != nil {
		return err
	}

	headerRow := "id,metric_name,value,timestamp\n"
	w.Write([]byte(headerRow))

	for _, p := range points {
		row := fmt.Sprintf("%s,%s,%f,%s\n", p.ID, p.MetricName, p.Value, p.Timestamp.Format(time.RFC3339))
		w.Write([]byte(row))
	}

	return nil
}

func (m *Manager) backupLogs(ctx context.Context, w io.Writer, tw *tar.Writer) error {
	header := &tar.Header{
		Name: "logs_backup.ndjson",
		Mode: 0644,
	}
	if err := tw.WriteHeader(header); err != nil {
		return err
	}

	return nil
}

func (m *Manager) Restore(ctx context.Context, backupID string) error {
	record, err := m.repo.GetBackupRecords()
	if err != nil {
		return fmt.Errorf("failed to get backup records: %w", err)
	}

	var target *models.BackupRecord
	for _, r := range record {
		if r.ID == backupID {
			target = &r
			break
		}
	}

	if target == nil {
		return fmt.Errorf("backup not found: %s", backupID)
	}

	if target.Status != "completed" {
		return fmt.Errorf("backup is not completed: %s", target.Status)
	}

	if err := m.verifyBackup(target); err != nil {
		return fmt.Errorf("backup verification failed: %w", err)
	}

	m.logger.Info("Starting restore", zap.String("backup_id", backupID), zap.String("file", target.FilePath))

	if err := m.performRestore(ctx, target); err != nil {
		return fmt.Errorf("restore failed: %w", err)
	}

	m.logger.Info("Restore completed successfully", zap.String("backup_id", backupID))
	return nil
}

func (m *Manager) verifyBackup(record *models.BackupRecord) error {
	if _, err := os.Stat(record.FilePath); os.IsNotExist(err) {
		return fmt.Errorf("backup file not found: %s", record.FilePath)
	}

	if record.Checksum != "" {
		file, err := os.Open(record.FilePath)
		if err != nil {
			return err
		}
		defer file.Close()

		hasher := sha256.New()
		if _, err := io.Copy(hasher, file); err != nil {
			return err
		}

		actual := hex.EncodeToString(hasher.Sum(nil))
		if actual != record.Checksum {
			return fmt.Errorf("checksum mismatch: expected %s, got %s", record.Checksum, actual)
		}
	}

	return nil
}

func (m *Manager) performRestore(ctx context.Context, record *models.BackupRecord) error {
	return nil
}

func (m *Manager) cleanupOldBackups() {
	if m.config.RetentionDays <= 0 {
		return
	}

	cutoff := time.Now().AddDate(0, 0, -m.config.RetentionDays)

	records, err := m.repo.GetBackupRecords()
	if err != nil {
		m.logger.Error("Failed to get backup records for cleanup", zap.Error(err))
		return
	}

	for _, record := range records {
		if record.CompletedAt.Before(cutoff) {
			if err := os.Remove(record.FilePath); err != nil {
				m.logger.Warn("Failed to delete old backup", zap.String("file", record.FilePath), zap.Error(err))
			}
			m.logger.Info("Old backup cleaned up", zap.String("file", record.FilePath))
		}
	}
}

func (m *Manager) ListBackups() ([]models.BackupRecord, error) {
	return m.repo.GetBackupRecords()
}

func (m *Manager) GetBackupStatus(backupID string) (*models.BackupRecord, error) {
	records, err := m.repo.GetBackupRecords()
	if err != nil {
		return nil, err
	}

	for _, r := range records {
		if r.ID == backupID {
			return &r, nil
		}
	}
	return nil, fmt.Errorf("backup not found: %s", backupID)
}
