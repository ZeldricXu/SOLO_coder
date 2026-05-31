package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"session316/internal/logger"
	"session316/internal/models"
	apperrors "session316/pkg/errors"
	"session316/pkg/utils"
)

const (
	BackupTypeFull        = "full"
	BackupTypeIncremental = "incremental"
	backupFilePrefix      = "backup_"
	backupFileExt         = ".json"
	manifestFileName      = "manifest.json"
	defaultRetentionDays  = 30
	defaultMaxBackups     = 10
	defaultBackupTimeout  = 30 * time.Minute
	defaultRestoreTimeout = 60 * time.Minute
)

type BackupType string

type BackupConfig struct {
	BackupDir       string
	RetentionDays   int
	MaxBackups      int
	EncryptionKey   []byte
	Compress        bool
}

type BackupProgress struct {
	BackupID    string    `json:"backup_id"`
	Type        BackupType `json:"type"`
	Status      string    `json:"status"`
	Progress    float64   `json:"progress"`
	TotalItems  int       `json:"total_items"`
	Processed   int       `json:"processed"`
	StartedAt   time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at,omitempty"`
	Error       string    `json:"error,omitempty"`
}

type BackupManifest struct {
	BackupID    string          `json:"backup_id"`
	Type        BackupType      `json:"type"`
	CreatedAt   time.Time       `json:"created_at"`
	Size        int64           `json:"size"`
	Checksum    string          `json:"checksum"`
	TableCounts map[string]int  `json:"table_counts"`
	BaseBackupID string         `json:"base_backup_id,omitempty"`
	Encrypted   bool            `json:"encrypted"`
}

type BackupTask struct {
	ID       string
	Type     BackupType
	Progress *BackupProgress
	Cancel   context.CancelFunc
}

type StorageManager struct {
	db            *gorm.DB
	config        BackupConfig
	mu            sync.RWMutex
	activeTasks   map[string]*BackupTask
	progressCache map[string]*BackupProgress
}

func NewStorageManager(db *gorm.DB, config BackupConfig) (*StorageManager, error) {
	if db == nil {
		return nil, apperrors.ValidationError("db", "数据库连接不能为空")
	}
	if config.BackupDir == "" {
		config.BackupDir = "./backups"
	}
	if config.RetentionDays <= 0 {
		config.RetentionDays = defaultRetentionDays
	}
	if config.MaxBackups <= 0 {
		config.MaxBackups = defaultMaxBackups
	}

	if err := os.MkdirAll(config.BackupDir, 0755); err != nil {
		return nil, apperrors.InternalError(err, "创建备份目录")
	}

	sm := &StorageManager{
		db:            db,
		config:        config,
		activeTasks:   make(map[string]*BackupTask),
		progressCache: make(map[string]*BackupProgress),
	}

	logger.Info("StorageManager初始化成功",
		zap.String("backup_dir", config.BackupDir),
		zap.Int("retention_days", config.RetentionDays),
		zap.Int("max_backups", config.MaxBackups))

	return sm, nil
}

func (sm *StorageManager) Backup(ctx context.Context, backupType BackupType) (string, error) {
	sm.mu.Lock()
	for _, task := range sm.activeTasks {
		if task.Type == backupType || task.Progress.Status == models.StatusRunning {
			sm.mu.Unlock()
			return "", apperrors.New(apperrors.ErrCodeConflict, "已有备份任务正在执行")
		}
	}
	sm.mu.Unlock()

	backupID := utils.GenerateID("bak")
	ctx, cancel := context.WithTimeout(ctx, defaultBackupTimeout)

	progress := &BackupProgress{
		BackupID:  backupID,
		Type:      backupType,
		Status:    models.StatusRunning,
		Progress:  0,
		StartedAt: time.Now(),
	}

	task := &BackupTask{
		ID:       backupID,
		Type:     backupType,
		Progress: progress,
		Cancel:   cancel,
	}

	sm.mu.Lock()
	sm.activeTasks[backupID] = task
	sm.progressCache[backupID] = progress
	sm.mu.Unlock()

	logger.Info("开始备份任务",
		zap.String("backup_id", backupID),
		zap.String("type", string(backupType)))

	go func() {
		defer func() {
			sm.mu.Lock()
			delete(sm.activeTasks, backupID)
			sm.mu.Unlock()
			cancel()
		}()

		if err := sm.performBackup(ctx, backupID, backupType, progress); err != nil {
			sm.mu.Lock()
			progress.Status = models.StatusFailed
			progress.Error = err.Error()
			sm.mu.Unlock()
			logger.Error("备份任务失败",
				zap.String("backup_id", backupID),
				zap.Error(err))
			return
		}

		now := time.Now()
		sm.mu.Lock()
		progress.Status = models.StatusCompleted
		progress.CompletedAt = &now
		progress.Progress = 100
		sm.mu.Unlock()

		logger.Info("备份任务完成",
			zap.String("backup_id", backupID),
			zap.Duration("duration", time.Since(progress.StartedAt)))

		if err := sm.cleanupOldBackups(); err != nil {
			logger.Warn("清理旧备份失败", zap.Error(err))
		}
	}()

	return backupID, nil
}

func (sm *StorageManager) performBackup(ctx context.Context, backupID string, backupType BackupType, progress *BackupProgress) error {
	backupDir := filepath.Join(sm.config.BackupDir, backupID)
	if err := os.MkdirAll(backupDir, 0755); err != nil {
		return apperrors.InternalError(err, "创建备份子目录")
	}

	tables := []interface{}{
		&models.Entity{},
		&models.Config{},
		&models.RunInstance{},
		&models.Snapshot{},
		&models.Resource{},
	}

	tableCounts := make(map[string]int)
	totalItems := 0

	for _, table := range tables {
		tableName := sm.db.Model(table).Statement.Table
		var count int64
		if err := sm.db.Model(table).Count(&count).Error; err != nil {
			return apperrors.InternalError(err, fmt.Sprintf("统计表%s记录数", tableName))
		}
		tableCounts[tableName] = int(count)
		totalItems += int(count)
	}

	sm.mu.Lock()
	progress.TotalItems = totalItems
	sm.mu.Unlock()

	var baseBackupID string
	if backupType == BackupTypeIncremental {
		latestFull, err := sm.getLatestFullBackup()
		if err != nil {
			return apperrors.InternalError(err, "获取基准全量备份")
		}
		baseBackupID = latestFull
	}

	processed := 0
	for _, table := range tables {
		tableName := sm.db.Model(table).Statement.Table

		rows, err := sm.db.Model(table).Rows()
		if err != nil {
			return apperrors.InternalError(err, fmt.Sprintf("查询表%s数据", tableName))
		}

		var records []interface{}
		for rows.Next() {
			select {
			case <-ctx.Done():
				rows.Close()
				return apperrors.New(apperrors.ErrCodeInternal, "备份任务被取消")
			default:
			}

			record := table
			if err := sm.db.ScanRows(rows, record); err != nil {
				rows.Close()
				return apperrors.InternalError(err, fmt.Sprintf("扫描表%s数据", tableName))
			}
			records = append(records, record)

			processed++
			sm.mu.Lock()
			progress.Processed = processed
			if totalItems > 0 {
				progress.Progress = float64(processed) / float64(totalItems) * 100
			}
			sm.mu.Unlock()
		}
		rows.Close()

		data, err := json.MarshalIndent(records, "", "  ")
		if err != nil {
			return apperrors.InternalError(err, fmt.Sprintf("序列化表%s数据", tableName))
		}

		if sm.config.EncryptionKey != nil {
			encrypted, err := utils.AESEncrypt(data, sm.config.EncryptionKey)
			if err != nil {
				return apperrors.InternalError(err, fmt.Sprintf("加密表%s数据", tableName))
			}
			data = []byte(encrypted)
		}

		fileName := fmt.Sprintf("%s%s", tableName, backupFileExt)
		filePath := filepath.Join(backupDir, fileName)
		if err := os.WriteFile(filePath, data, 0644); err != nil {
			return apperrors.InternalError(err, fmt.Sprintf("写入表%s备份文件", tableName))
		}
	}

	manifest := BackupManifest{
		BackupID:    backupID,
		Type:        backupType,
		CreatedAt:   time.Now(),
		TableCounts: tableCounts,
		BaseBackupID: baseBackupID,
		Encrypted:   sm.config.EncryptionKey != nil,
	}

	var totalSize int64
	err := filepath.Walk(backupDir, func(_ string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() {
			totalSize += info.Size()
		}
		return nil
	})
	if err != nil {
		return apperrors.InternalError(err, "计算备份大小")
	}
	manifest.Size = totalSize

	checksumData, _ := json.Marshal(manifest)
	manifest.Checksum = utils.HashSHA256(checksumData)

	manifestPath := filepath.Join(backupDir, manifestFileName)
	manifestData, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return apperrors.InternalError(err, "序列化备份清单")
	}
	if err := os.WriteFile(manifestPath, manifestData, 0644); err != nil {
		return apperrors.InternalError(err, "写入备份清单")
	}

	logger.Debug("备份清单已写入",
		zap.String("backup_id", backupID),
		zap.Int64("size", totalSize),
		zap.Int("tables", len(tableCounts)))

	return nil
}

func (sm *StorageManager) Restore(ctx context.Context, backupID string) error {
	if backupID == "" {
		return apperrors.ValidationError("backup_id", "备份ID不能为空")
	}

	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, defaultRestoreTimeout)
	defer cancel()

	backupDir := filepath.Join(sm.config.BackupDir, backupID)
	if _, err := os.Stat(backupDir); os.IsNotExist(err) {
		return apperrors.NotFoundError("backup", backupID)
	}

	manifestPath := filepath.Join(backupDir, manifestFileName)
	manifestData, err := os.ReadFile(manifestPath)
	if err != nil {
		return apperrors.InternalError(err, "读取备份清单")
	}

	var manifest BackupManifest
	if err := json.Unmarshal(manifestData, &manifest); err != nil {
		return apperrors.InternalError(err, "解析备份清单")
	}

	logger.Info("开始恢复数据",
		zap.String("backup_id", backupID),
		zap.String("type", string(manifest.Type)))

	tables := []struct {
		model interface{}
		name  string
	}{
		{&models.Entity{}, "entities"},
		{&models.Config{}, "configs"},
		{&models.RunInstance{}, "run_instances"},
		{&models.Snapshot{}, "snapshots"},
		{&models.Resource{}, "resources"},
	}

	tx := sm.db.Begin()
	if tx.Error != nil {
		return apperrors.InternalError(tx.Error, "开始事务")
	}
	defer func() {
		if r := recover(); r != nil {
			tx.Rollback()
		}
	}()

	for _, t := range tables {
		select {
		case <-ctx.Done():
			tx.Rollback()
			return apperrors.New(apperrors.ErrCodeInternal, "恢复任务被取消")
		default:
		}

		filePath := filepath.Join(backupDir, fmt.Sprintf("%s%s", t.name, backupFileExt))
		if _, err := os.Stat(filePath); os.IsNotExist(err) {
			logger.Warn("表备份文件不存在，跳过", zap.String("table", t.name))
			continue
		}

		data, err := os.ReadFile(filePath)
		if err != nil {
			tx.Rollback()
			return apperrors.InternalError(err, fmt.Sprintf("读取表%s备份文件", t.name))
		}

		if manifest.Encrypted {
			if sm.config.EncryptionKey == nil {
				tx.Rollback()
				return apperrors.New(apperrors.ErrCodeDecryption, "备份已加密但未提供解密密钥")
			}
			decrypted, err := utils.AESDecrypt(string(data), sm.config.EncryptionKey)
			if err != nil {
				tx.Rollback()
				return apperrors.Wrap(err, apperrors.ErrCodeDecryption, "解密备份数据失败")
			}
			data = decrypted
		}

		var records []json.RawMessage
		if err := json.Unmarshal(data, &records); err != nil {
			tx.Rollback()
			return apperrors.InternalError(err, fmt.Sprintf("解析表%s备份数据", t.name))
		}

		if len(records) == 0 {
			continue
		}

		if err := tx.Exec(fmt.Sprintf("TRUNCATE TABLE %s CASCADE", t.name)).Error; err != nil {
			tx.Rollback()
			return apperrors.InternalError(err, fmt.Sprintf("清空表%s", t.name))
		}

		for _, recordData := range records {
			record := t.model
			if err := json.Unmarshal(recordData, record); err != nil {
				tx.Rollback()
				return apperrors.InternalError(err, fmt.Sprintf("反序列化表%s记录", t.name))
			}
			if err := tx.Create(record).Error; err != nil {
				tx.Rollback()
				return apperrors.InternalError(err, fmt.Sprintf("插入表%s记录", t.name))
			}
		}

		logger.Debug("表恢复完成",
			zap.String("table", t.name),
			zap.Int("records", len(records)))
	}

	if err := tx.Commit().Error; err != nil {
		return apperrors.InternalError(err, "提交事务")
	}

	logger.Info("数据恢复完成", zap.String("backup_id", backupID))
	return nil
}

func (sm *StorageManager) GetProgress(backupID string) (*BackupProgress, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()

	progress, exists := sm.progressCache[backupID]
	if !exists {
		backupDir := filepath.Join(sm.config.BackupDir, backupID)
		if _, err := os.Stat(backupDir); os.IsNotExist(err) {
			return nil, apperrors.NotFoundError("backup", backupID)
		}

		manifestPath := filepath.Join(backupDir, manifestFileName)
		manifestData, err := os.ReadFile(manifestPath)
		if err != nil {
			return nil, apperrors.InternalError(err, "读取备份清单")
		}

		var manifest BackupManifest
		if err := json.Unmarshal(manifestData, &manifest); err != nil {
			return nil, apperrors.InternalError(err, "解析备份清单")
		}

		progress = &BackupProgress{
			BackupID:    backupID,
			Type:        manifest.Type,
			Status:      models.StatusCompleted,
			Progress:    100,
			TotalItems:  0,
			Processed:   0,
			StartedAt:   manifest.CreatedAt,
			CompletedAt: &manifest.CreatedAt,
		}
		sm.progressCache[backupID] = progress
	}

	return progress, nil
}

func (sm *StorageManager) ListBackups() ([]BackupManifest, error) {
	entries, err := os.ReadDir(sm.config.BackupDir)
	if err != nil {
		return nil, apperrors.InternalError(err, "列出备份目录")
	}

	var manifests []BackupManifest
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		backupID := entry.Name()
		manifestPath := filepath.Join(sm.config.BackupDir, backupID, manifestFileName)
		if _, err := os.Stat(manifestPath); os.IsNotExist(err) {
			continue
		}

		data, err := os.ReadFile(manifestPath)
		if err != nil {
			logger.Warn("读取备份清单失败",
				zap.String("backup_id", backupID),
				zap.Error(err))
			continue
		}

		var manifest BackupManifest
		if err := json.Unmarshal(data, &manifest); err != nil {
			logger.Warn("解析备份清单失败",
				zap.String("backup_id", backupID),
				zap.Error(err))
			continue
		}

		manifests = append(manifests, manifest)
	}

	sort.Slice(manifests, func(i, j int) bool {
		return manifests[i].CreatedAt.After(manifests[j].CreatedAt)
	})

	return manifests, nil
}

func (sm *StorageManager) CancelBackup(backupID string) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	task, exists := sm.activeTasks[backupID]
	if !exists {
		return apperrors.NotFoundError("backup_task", backupID)
	}

	task.Cancel()
	task.Progress.Status = models.StatusRollback
	task.Progress.Error = "用户取消"

	logger.Info("备份任务已取消", zap.String("backup_id", backupID))
	return nil
}

func (sm *StorageManager) DeleteBackup(backupID string) error {
	sm.mu.Lock()
	if _, active := sm.activeTasks[backupID]; active {
		sm.mu.Unlock()
		return apperrors.New(apperrors.ErrCodeConflict, "备份任务正在执行，无法删除")
	}
	sm.mu.Unlock()

	backupDir := filepath.Join(sm.config.BackupDir, backupID)
	if _, err := os.Stat(backupDir); os.IsNotExist(err) {
		return apperrors.NotFoundError("backup", backupID)
	}

	if err := os.RemoveAll(backupDir); err != nil {
		return apperrors.InternalError(err, "删除备份目录")
	}

	sm.mu.Lock()
	delete(sm.progressCache, backupID)
	sm.mu.Unlock()

	logger.Info("备份已删除", zap.String("backup_id", backupID))
	return nil
}

func (sm *StorageManager) getLatestFullBackup() (string, error) {
	manifests, err := sm.ListBackups()
	if err != nil {
		return "", err
	}

	for _, m := range manifests {
		if m.Type == BackupTypeFull {
			return m.BackupID, nil
		}
	}

	return "", apperrors.NotFoundError("full_backup", "未找到全量备份")
}

func (sm *StorageManager) cleanupOldBackups() error {
	manifests, err := sm.ListBackups()
	if err != nil {
		return err
	}

	cutoffTime := time.Now().AddDate(0, 0, -sm.config.RetentionDays)
	var toDelete []string

	for i, m := range manifests {
		if i >= sm.config.MaxBackups || m.CreatedAt.Before(cutoffTime) {
			toDelete = append(toDelete, m.BackupID)
		}
	}

	for _, backupID := range toDelete {
		sm.mu.RLock()
		_, active := sm.activeTasks[backupID]
		sm.mu.RUnlock()

		if active {
			continue
		}

		backupDir := filepath.Join(sm.config.BackupDir, backupID)
		if err := os.RemoveAll(backupDir); err != nil {
			logger.Error("删除旧备份失败",
				zap.String("backup_id", backupID),
				zap.Error(err))
			continue
		}

		sm.mu.Lock()
		delete(sm.progressCache, backupID)
		sm.mu.Unlock()

		logger.Info("旧备份已清理", zap.String("backup_id", backupID))
	}

	return nil
}

func (sm *StorageManager) ExportBackup(backupID string, writer io.Writer) error {
	backupDir := filepath.Join(sm.config.BackupDir, backupID)
	if _, err := os.Stat(backupDir); os.IsNotExist(err) {
		return apperrors.NotFoundError("backup", backupID)
	}

	entries, err := os.ReadDir(backupDir)
	if err != nil {
		return apperrors.InternalError(err, "读取备份目录")
	}

	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		filePath := filepath.Join(backupDir, entry.Name())
		file, err := os.Open(filePath)
		if err != nil {
			return apperrors.InternalError(err, fmt.Sprintf("打开文件%s", entry.Name()))
		}
		if _, err := io.Copy(writer, file); err != nil {
			file.Close()
			return apperrors.InternalError(err, fmt.Sprintf("写入文件%s", entry.Name()))
		}
		file.Close()
	}

	return nil
}

func (sm *StorageManager) StartAutoCleanup(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		interval = 24 * time.Hour
	}

	ticker := time.NewTicker(interval)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				logger.Info("自动清理任务已停止")
				return
			case <-ticker.C:
				if err := sm.cleanupOldBackups(); err != nil {
					logger.Error("自动清理备份失败", zap.Error(err))
				}
			}
		}
	}()

	logger.Info("自动清理任务已启动", zap.Duration("interval", interval))
}
