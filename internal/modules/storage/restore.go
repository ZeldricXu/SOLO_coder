package storage

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type RestoreStatus string

const (
	RestoreStatusPending   RestoreStatus = "pending"
	RestoreStatusRunning   RestoreStatus = "running"
	RestoreStatusCompleted RestoreStatus = "completed"
	RestoreStatusFailed    RestoreStatus = "failed"
)

type RestoreRecord struct {
	RestoreID   string                 `json:"restore_id" gorm:"primaryKey;type:varchar(64)"`
	BackupID    string                 `json:"backup_id" gorm:"type:varchar(64);index"`
	Name        string                 `json:"name"`
	Status      RestoreStatus          `json:"status" gorm:"type:varchar(32);index"`
	BackupPath  string                 `json:"backup_path"`
	TargetDB    string                 `json:"target_db"`
	DropTables  bool                   `json:"drop_tables"`
	Metadata    map[string]interface{} `json:"metadata" gorm:"type:jsonb"`
	Error       string                 `json:"error,omitempty"`
	CreatedBy   string                 `json:"created_by"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	CreatedAt   time.Time              `json:"created_at" gorm:"index"`
}

func (RestoreRecord) TableName() string {
	return "restore_records"
}

type RestoreManager struct {
	pgRestorePath string
}

func NewRestoreManager() *RestoreManager {
	return &RestoreManager{
		pgRestorePath: "pg_restore",
	}
}

func (m *RestoreManager) CreateRestore(ctx context.Context, backupID, targetDB, name, createdBy string, dropTables bool) (*RestoreRecord, error) {
	backupMgr := NewBackupManager("")
	backup, err := backupMgr.GetBackup(ctx, backupID)
	if err != nil {
		return nil, fmt.Errorf("get backup failed: %w", err)
	}

	if backup.Status != BackupStatusCompleted {
		return nil, fmt.Errorf("backup not completed")
	}

	record := &RestoreRecord{
		RestoreID:  uuid.New().String(),
		BackupID:   backupID,
		Name:       name,
		Status:     RestoreStatusPending,
		BackupPath: backup.FilePath,
		TargetDB:   targetDB,
		DropTables: dropTables,
		CreatedBy:  createdBy,
		CreatedAt:  time.Now(),
	}

	if name == "" {
		record.Name = fmt.Sprintf("restore-%s-%s", backupID, time.Now().Format("20060102150405"))
	}

	if err := database.DB.WithContext(ctx).Create(record).Error; err != nil {
		return nil, fmt.Errorf("create restore record failed: %w", err)
	}

	go m.executeRestore(record)

	return record, nil
}

func (m *RestoreManager) executeRestore(record *RestoreRecord) {
	ctx := context.Background()
	now := time.Now()
	record.StartedAt = &now
	record.Status = RestoreStatusRunning
	_ = database.DB.Model(record).Updates(map[string]interface{}{
		"status":     RestoreStatusRunning,
		"started_at": now,
	})

	logger.Info("Starting restore",
		zap.String("restore_id", record.RestoreID),
		zap.String("backup_id", record.BackupID))

	err := m.restoreDatabase(ctx, record)

	now = time.Now()
	record.CompletedAt = &now

	if err != nil {
		record.Status = RestoreStatusFailed
		record.Error = err.Error()
		logger.Error("Restore failed",
			zap.String("restore_id", record.RestoreID),
			zap.Error(err))
	} else {
		record.Status = RestoreStatusCompleted
		record.Metadata = map[string]interface{}{
			"restore_tool": "psql",
			"target_db":    record.TargetDB,
		}
		logger.Info("Restore completed",
			zap.String("restore_id", record.RestoreID),
			zap.String("backup_id", record.BackupID))
	}

	_ = database.DB.Model(record).Updates(map[string]interface{}{
		"status":       record.Status,
		"completed_at": record.CompletedAt,
		"error":        record.Error,
		"metadata":     record.Metadata,
	})
}

func (m *RestoreManager) restoreDatabase(ctx context.Context, record *RestoreRecord) error {
	if _, err := os.Stat(record.BackupPath); os.IsNotExist(err) {
		return fmt.Errorf("backup file not found: %s", record.BackupPath)
	}

	dsn := database.DB.Config.DSN
	dsnMap := parseDSN(dsn)

	targetDB := record.TargetDB
	if targetDB == "" {
		targetDB = dsnMap["dbname"]
	}

	isSQLFile := strings.HasSuffix(strings.ToLower(record.BackupPath), ".sql")

	if isSQLFile {
		return m.restoreSQL(ctx, record.BackupPath, dsnMap, targetDB, record.DropTables)
	}

	return m.restoreDump(ctx, record.BackupPath, dsnMap, targetDB, record.DropTables)
}

func (m *RestoreManager) restoreSQL(ctx context.Context, filePath string, dsnMap map[string]string, targetDB string, dropTables bool) error {
	file, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("open sql file failed: %w", err)
	}
	defer file.Close()

	if dropTables {
		if err := m.dropExistingTables(ctx, dsnMap, targetDB); err != nil {
			logger.Warn("Failed to drop existing tables", zap.Error(err))
		}
	}

	dsn := fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=%s",
		dsnMap["user"], dsnMap["password"], dsnMap["host"], dsnMap["port"], targetDB, "disable")

	cmd := exec.CommandContext(ctx, "psql", dsn)
	cmd.Stdin = file

	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("psql failed: %w, output: %s", err, string(output))
	}

	return nil
}

func (m *RestoreManager) restoreDump(ctx context.Context, filePath string, dsnMap map[string]string, targetDB string, dropTables bool) error {
	args := []string{
		"--host", dsnMap["host"],
		"--port", dsnMap["port"],
		"--username", dsnMap["user"],
		"--dbname", targetDB,
		"--no-owner",
		"--no-privileges",
	}

	if dropTables {
		args = append(args, "--clean")
	}

	args = append(args, filePath)

	cmd := exec.CommandContext(ctx, m.pgRestorePath, args...)
	cmd.Env = append(os.Environ(), fmt.Sprintf("PGPASSWORD=%s", dsnMap["password"]))

	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("pg_restore failed: %w, output: %s", err, string(output))
	}

	return nil
}

func (m *RestoreManager) dropExistingTables(ctx context.Context, dsnMap map[string]string, targetDB string) error {
	tables, err := m.listTables(ctx, dsnMap, targetDB)
	if err != nil {
		return err
	}

	for _, table := range tables {
		dropSQL := fmt.Sprintf("DROP TABLE IF EXISTS %s CASCADE;", table)
		if err := database.DB.Exec(dropSQL).Error; err != nil {
			logger.Warn("Failed to drop table", zap.String("table", table), zap.Error(err))
		}
	}

	return nil
}

func (m *RestoreManager) listTables(ctx context.Context, dsnMap map[string]string, targetDB string) ([]string, error) {
	var tables []string
	query := `
		SELECT table_name 
		FROM information_schema.tables 
		WHERE table_schema = 'public' 
		AND table_type = 'BASE TABLE'
	`

	rows, err := database.DB.WithContext(ctx).Raw(query).Rows()
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	for rows.Next() {
		var tableName string
		if err := rows.Scan(&tableName); err != nil {
			continue
		}
		tables = append(tables, tableName)
	}

	return tables, nil
}

func (m *RestoreManager) GetRestore(ctx context.Context, restoreID string) (*RestoreRecord, error) {
	var record RestoreRecord
	if err := database.DB.WithContext(ctx).Where("restore_id = ?", restoreID).First(&record).Error; err != nil {
		return nil, fmt.Errorf("get restore failed: %w", err)
	}
	return &record, nil
}

func (m *RestoreManager) ListRestores(ctx context.Context, status RestoreStatus, offset, limit int) ([]RestoreRecord, int64, error) {
	var records []RestoreRecord
	var total int64

	query := database.DB.WithContext(ctx).Model(&RestoreRecord{})
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count restores failed: %w", err)
	}

	if err := query.Order("created_at DESC").Offset(offset).Limit(limit).Find(&records).Error; err != nil {
		return nil, 0, fmt.Errorf("list restores failed: %w", err)
	}

	return records, total, nil
}

func (m *RestoreManager) CancelRestore(ctx context.Context, restoreID string) error {
	var record RestoreRecord
	if err := database.DB.WithContext(ctx).Where("restore_id = ?", restoreID).First(&record).Error; err != nil {
		return fmt.Errorf("restore not found: %w", err)
	}

	if record.Status != RestoreStatusRunning {
		return fmt.Errorf("restore not running")
	}

	record.Status = RestoreStatusFailed
	record.Error = "cancelled by user"
	now := time.Now()
	record.CompletedAt = &now

	if err := database.DB.WithContext(ctx).Model(&record).Updates(map[string]interface{}{
		"status":       record.Status,
		"error":        record.Error,
		"completed_at": record.CompletedAt,
	}).Error; err != nil {
		return fmt.Errorf("cancel restore failed: %w", err)
	}

	logger.Info("Restore cancelled", zap.String("restore_id", restoreID))
	return nil
}

func (m *RestoreManager) UploadBackup(ctx context.Context, file io.Reader, filename string) (*BackupRecord, error) {
	backupDir := "./backups"
	_ = os.MkdirAll(backupDir, 0755)

	backupID := uuid.New().String()
	filePath := fmt.Sprintf("%s/%s-%s", backupDir, backupID, filename)

	out, err := os.Create(filePath)
	if err != nil {
		return nil, fmt.Errorf("create backup file failed: %w", err)
	}
	defer out.Close()

	written, err := io.Copy(out, bufio.NewReader(file))
	if err != nil {
		return nil, fmt.Errorf("save backup file failed: %w", err)
	}

	record := &BackupRecord{
		BackupID:  backupID,
		Name:      filename,
		Type:      BackupTypeFull,
		Status:    BackupStatusCompleted,
		FilePath:  filePath,
		FileSize:  written,
		CreatedBy: "upload",
		CreatedAt: time.Now(),
	}
	now := time.Now()
	record.StartedAt = &now
	record.CompletedAt = &now

	if err := database.DB.WithContext(ctx).Create(record).Error; err != nil {
		_ = os.Remove(filePath)
		return nil, fmt.Errorf("create backup record failed: %w", err)
	}

	logger.Info("Backup uploaded",
		zap.String("backup_id", backupID),
		zap.String("filename", filename),
		zap.Int64("size", written))

	return record, nil
}
