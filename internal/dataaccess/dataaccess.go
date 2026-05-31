package dataaccess

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"

	"session316/internal/config"
	applogger "session316/internal/logger"
	"session316/internal/models"
	apperrors "session316/pkg/errors"
	"session316/pkg/utils"
)

type SchemaMigration struct {
	ID          string    `gorm:"primaryKey;type:varchar(64)" json:"id"`
	Version     int       `gorm:"index;unique" json:"version"`
	Name        string    `gorm:"type:varchar(256)" json:"name"`
	Description string    `gorm:"type:text" json:"description,omitempty"`
	UpSQL       string    `gorm:"type:text" json:"-"`
	DownSQL     string    `gorm:"type:text" json:"-"`
	Checksum    string    `gorm:"type:varchar(64)" json:"checksum"`
	Status      string    `gorm:"type:varchar(32);index" json:"status"`
	AppliedBy   string    `gorm:"type:varchar(64)" json:"applied_by,omitempty"`
	AppliedAt   time.Time `json:"applied_at,omitempty"`
	RollbackAt  time.Time `json:"rollback_at,omitempty"`
	ExecutionMs int64     `json:"execution_ms,omitempty"`
	Error       string    `gorm:"type:text" json:"error,omitempty"`
}

type MigrationScript struct {
	Version     int
	Name        string
	Description string
	Up          string
	Down        string
}

type ConnectionPoolConfig struct {
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
	ConnMaxIdleTime time.Duration
}

type DataAccessManager struct {
	db          *gorm.DB
	sqlDB       *sql.DB
	mu          sync.RWMutex
	migrations  []*MigrationScript
	poolConfig  ConnectionPoolConfig
	isConnected bool
}

var (
	managerInstance *DataAccessManager
	managerOnce     sync.Once
)

func DefaultConnectionPoolConfig() ConnectionPoolConfig {
	return ConnectionPoolConfig{
		MaxOpenConns:    100,
		MaxIdleConns:    20,
		ConnMaxLifetime: time.Hour,
		ConnMaxIdleTime: 30 * time.Minute,
	}
}

func NewDataAccessManager() *DataAccessManager {
	return &DataAccessManager{
		migrations: make([]*MigrationScript, 0),
		poolConfig: DefaultConnectionPoolConfig(),
	}
}

func GetManager() *DataAccessManager {
	managerOnce.Do(func() {
		managerInstance = NewDataAccessManager()
	})
	return managerInstance
}

func (dam *DataAccessManager) Connect(cfg *config.DatabaseConfig) error {
	dam.mu.Lock()
	defer dam.mu.Unlock()

	if dam.isConnected {
		return nil
	}

	db, err := gorm.Open(postgres.Open(cfg.DSN), &gorm.Config{
		PrepareStmt: true,
		Logger:      newGormLogger(),
	})
	if err != nil {
		applogger.Error("failed to connect database", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "数据库连接失败")
	}

	sqlDB, err := db.DB()
	if err != nil {
		applogger.Error("failed to get sql DB instance", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "获取数据库实例失败")
	}

	sqlDB.SetMaxOpenConns(dam.poolConfig.MaxOpenConns)
	sqlDB.SetMaxIdleConns(dam.poolConfig.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(dam.poolConfig.ConnMaxLifetime)
	sqlDB.SetConnMaxIdleTime(dam.poolConfig.ConnMaxIdleTime)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := sqlDB.PingContext(ctx); err != nil {
		applogger.Error("failed to ping database", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "数据库连接健康检查失败")
	}

	dam.db = db
	dam.sqlDB = sqlDB
	dam.isConnected = true

	applogger.Info("database connected successfully",
		zap.Int("max_open_conns", dam.poolConfig.MaxOpenConns),
		zap.Int("max_idle_conns", dam.poolConfig.MaxIdleConns),
	)

	return nil
}

func (dam *DataAccessManager) Disconnect() error {
	dam.mu.Lock()
	defer dam.mu.Unlock()

	if !dam.isConnected {
		return nil
	}

	if dam.sqlDB != nil {
		if err := dam.sqlDB.Close(); err != nil {
			applogger.Error("failed to close database connection", zap.Error(err))
			return apperrors.Wrap(err, apperrors.ErrCodeInternal, "关闭数据库连接失败")
		}
	}

	dam.isConnected = false
	applogger.Info("database disconnected successfully")
	return nil
}

func (dam *DataAccessManager) SetConnectionPoolConfig(cfg ConnectionPoolConfig) {
	dam.mu.Lock()
	defer dam.mu.Unlock()
	dam.poolConfig = cfg

	if dam.isConnected && dam.sqlDB != nil {
		dam.sqlDB.SetMaxOpenConns(cfg.MaxOpenConns)
		dam.sqlDB.SetMaxIdleConns(cfg.MaxIdleConns)
		dam.sqlDB.SetConnMaxLifetime(cfg.ConnMaxLifetime)
		dam.sqlDB.SetConnMaxIdleTime(cfg.ConnMaxIdleTime)
	}
}

func (dam *DataAccessManager) GetPoolStats() sql.DBStats {
	dam.mu.RLock()
	defer dam.mu.RUnlock()
	if dam.sqlDB == nil {
		return sql.DBStats{}
	}
	return dam.sqlDB.Stats()
}

func (dam *DataAccessManager) DB() *gorm.DB {
	dam.mu.RLock()
	defer dam.mu.RUnlock()
	return dam.db
}

func (dam *DataAccessManager) RegisterMigration(script *MigrationScript) error {
	dam.mu.Lock()
	defer dam.mu.Unlock()

	if script == nil {
		return apperrors.ValidationError("script", "迁移脚本不能为空")
	}
	if script.Version <= 0 {
		return apperrors.ValidationError("version", "版本号必须大于0")
	}
	if script.Up == "" {
		return apperrors.ValidationError("up", "Up SQL不能为空")
	}

	for _, m := range dam.migrations {
		if m.Version == script.Version {
			return apperrors.NewWithDetails(apperrors.ErrCodeConflict,
				"迁移版本已存在",
				fmt.Sprintf("版本 %d 已注册", script.Version),
			)
		}
	}

	dam.migrations = append(dam.migrations, script)
	applogger.Info("migration registered",
		zap.Int("version", script.Version),
		zap.String("name", script.Name),
	)
	return nil
}

func (dam *DataAccessManager) RegisterMigrations(scripts []*MigrationScript) error {
	for _, script := range scripts {
		if err := dam.RegisterMigration(script); err != nil {
			return err
		}
	}
	return nil
}

func (dam *DataAccessManager) InitSchema(ctx context.Context) error {
	if err := dam.ensureConnected(); err != nil {
		return err
	}

	err := dam.db.WithContext(ctx).AutoMigrate(
		&SchemaMigration{},
		&models.Entity{},
		&models.Config{},
		&models.RunInstance{},
		&models.Snapshot{},
	)
	if err != nil {
		applogger.Error("failed to auto migrate schema", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "初始化Schema失败")
	}

	applogger.Info("schema initialized successfully")
	return nil
}

func (dam *DataAccessManager) Migrate(ctx context.Context, targetVersion int) error {
	if err := dam.ensureConnected(); err != nil {
		return err
	}

	if err := dam.InitSchema(ctx); err != nil {
		return err
	}

	currentVersion, err := dam.getCurrentVersion(ctx)
	if err != nil {
		return err
	}

	if targetVersion > 0 && currentVersion >= targetVersion {
		applogger.Info("schema is already at or above target version",
			zap.Int("current_version", currentVersion),
			zap.Int("target_version", targetVersion),
		)
		return nil
	}

	scripts := dam.getPendingMigrations(currentVersion, targetVersion)
	if len(scripts) == 0 {
		applogger.Info("no pending migrations")
		return nil
	}

	applogger.Info("starting migration",
		zap.Int("current_version", currentVersion),
		zap.Int("target_version", targetVersion),
		zap.Int("migration_count", len(scripts)),
	)

	for _, script := range scripts {
		if err := dam.applyMigration(ctx, script); err != nil {
			applogger.Error("migration failed",
				zap.Int("version", script.Version),
				zap.String("name", script.Name),
				zap.Error(err),
			)
			return err
		}
	}

	applogger.Info("migration completed successfully")
	return nil
}

func (dam *DataAccessManager) MigrateAll(ctx context.Context) error {
	return dam.Migrate(ctx, 0)
}

func (dam *DataAccessManager) Rollback(ctx context.Context, targetVersion int) error {
	if err := dam.ensureConnected(); err != nil {
		return err
	}

	currentVersion, err := dam.getCurrentVersion(ctx)
	if err != nil {
		return err
	}

	if targetVersion >= currentVersion {
		return apperrors.ValidationError("target_version",
			"回滚目标版本必须小于当前版本",
		)
	}

	scripts := dam.getRollbackMigrations(currentVersion, targetVersion)
	if len(scripts) == 0 {
		applogger.Info("no migrations to rollback")
		return nil
	}

	applogger.Info("starting rollback",
		zap.Int("current_version", currentVersion),
		zap.Int("target_version", targetVersion),
		zap.Int("rollback_count", len(scripts)),
	)

	for i := len(scripts) - 1; i >= 0; i-- {
		if err := dam.revertMigration(ctx, scripts[i]); err != nil {
			applogger.Error("rollback failed",
				zap.Int("version", scripts[i].Version),
				zap.String("name", scripts[i].Name),
				zap.Error(err),
			)
			return err
		}
	}

	applogger.Info("rollback completed successfully")
	return nil
}

func (dam *DataAccessManager) RollbackLast(ctx context.Context) error {
	currentVersion, err := dam.getCurrentVersion(ctx)
	if err != nil {
		return err
	}
	return dam.Rollback(ctx, currentVersion-1)
}

func (dam *DataAccessManager) GetCurrentVersion(ctx context.Context) (int, error) {
	return dam.getCurrentVersion(ctx)
}

func (dam *DataAccessManager) GetMigrations(ctx context.Context) ([]SchemaMigration, error) {
	if err := dam.ensureConnected(); err != nil {
		return nil, err
	}

	var migrations []SchemaMigration
	result := dam.db.WithContext(ctx).Order("version DESC").Find(&migrations)
	if result.Error != nil {
		applogger.Error("failed to get migrations", zap.Error(result.Error))
		return nil, apperrors.Wrap(result.Error, apperrors.ErrCodeInternal, "获取迁移记录失败")
	}
	return migrations, nil
}

func (dam *DataAccessManager) GetPendingMigrations(ctx context.Context) ([]*MigrationScript, error) {
	currentVersion, err := dam.getCurrentVersion(ctx)
	if err != nil {
		return nil, err
	}
	return dam.getPendingMigrations(currentVersion, 0), nil
}

func (dam *DataAccessManager) ExportData(ctx context.Context, tables []string, writer io.Writer) error {
	if err := dam.ensureConnected(); err != nil {
		return err
	}

	if len(tables) == 0 {
		tables = []string{"entities", "configs", "run_instances", "snapshots", "schema_migrations"}
	}

	exportData := make(map[string]interface{})

	for _, table := range tables {
		rows, err := dam.db.WithContext(ctx).Table(table).Rows()
		if err != nil {
			applogger.Error("failed to export table", zap.String("table", table), zap.Error(err))
			continue
		}

		var records []map[string]interface{}
		columns, _ := rows.Columns()

		for rows.Next() {
			values := make([]interface{}, len(columns))
			valuePtrs := make([]interface{}, len(columns))
			for i := range columns {
				valuePtrs[i] = &values[i]
			}

			if err := rows.Scan(valuePtrs...); err != nil {
				rows.Close()
				return apperrors.Wrap(err, apperrors.ErrCodeInternal, "扫描数据失败")
			}

			record := make(map[string]interface{})
			for i, col := range columns {
				val := values[i]
				if b, ok := val.([]byte); ok {
					record[col] = string(b)
				} else {
					record[col] = val
				}
			}
			records = append(records, record)
		}
		rows.Close()

		exportData[table] = records
		applogger.Info("exported table data",
			zap.String("table", table),
			zap.Int("record_count", len(records)),
		)
	}

	encoder := json.NewEncoder(writer)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(exportData); err != nil {
		applogger.Error("failed to encode export data", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "编码导出数据失败")
	}

	applogger.Info("data export completed", zap.Int("table_count", len(tables)))
	return nil
}

func (dam *DataAccessManager) ExportToFile(ctx context.Context, tables []string, filePath string) error {
	file, err := os.Create(filePath)
	if err != nil {
		applogger.Error("failed to create export file", zap.String("path", filePath), zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "创建导出文件失败")
	}
	defer file.Close()

	return dam.ExportData(ctx, tables, file)
}

func (dam *DataAccessManager) ImportData(ctx context.Context, reader io.Reader, truncate bool) error {
	if err := dam.ensureConnected(); err != nil {
		return err
	}

	var importData map[string][]map[string]interface{}
	decoder := json.NewDecoder(reader)
	if err := decoder.Decode(&importData); err != nil {
		applogger.Error("failed to decode import data", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "解码导入数据失败")
	}

	tx := dam.db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return apperrors.Wrap(tx.Error, apperrors.ErrCodeInternal, "开启事务失败")
	}
	defer func() {
		if r := recover(); r != nil {
			tx.Rollback()
		}
	}()

	for table, records := range importData {
		if truncate {
			if err := tx.Exec(fmt.Sprintf("TRUNCATE TABLE %s CASCADE", table)).Error; err != nil {
				tx.Rollback()
				applogger.Error("failed to truncate table", zap.String("table", table), zap.Error(err))
				return apperrors.Wrap(err, apperrors.ErrCodeInternal, "清空表数据失败")
			}
		}

		for _, record := range records {
			if err := tx.Table(table).Create(record).Error; err != nil {
				tx.Rollback()
				applogger.Error("failed to insert record",
					zap.String("table", table),
					zap.Error(err),
				)
				return apperrors.Wrap(err, apperrors.ErrCodeInternal, "插入数据失败")
			}
		}

		applogger.Info("imported table data",
			zap.String("table", table),
			zap.Int("record_count", len(records)),
		)
	}

	if err := tx.Commit().Error; err != nil {
		applogger.Error("failed to commit import transaction", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "提交导入事务失败")
	}

	applogger.Info("data import completed", zap.Int("table_count", len(importData)))
	return nil
}

func (dam *DataAccessManager) ImportFromFile(ctx context.Context, filePath string, truncate bool) error {
	file, err := os.Open(filePath)
	if err != nil {
		applogger.Error("failed to open import file", zap.String("path", filePath), zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "打开导入文件失败")
	}
	defer file.Close()

	return dam.ImportData(ctx, file, truncate)
}

func (dam *DataAccessManager) ensureConnected() error {
	dam.mu.RLock()
	connected := dam.isConnected
	dam.mu.RUnlock()

	if !connected {
		return apperrors.NewWithDetails(apperrors.ErrCodeInternal,
			"数据库未连接",
			"请先调用Connect方法建立数据库连接",
		)
	}
	return nil
}

func (dam *DataAccessManager) getCurrentVersion(ctx context.Context) (int, error) {
	var migration SchemaMigration
	result := dam.db.WithContext(ctx).
		Where("status = ?", models.StatusCompleted).
		Order("version DESC").
		First(&migration)

	if result.Error != nil {
		if result.Error == gorm.ErrRecordNotFound {
			return 0, nil
		}
		applogger.Error("failed to get current version", zap.Error(result.Error))
		return 0, apperrors.Wrap(result.Error, apperrors.ErrCodeInternal, "获取当前版本失败")
	}
	return migration.Version, nil
}

func (dam *DataAccessManager) getPendingMigrations(currentVersion, targetVersion int) []*MigrationScript {
	dam.mu.RLock()
	defer dam.mu.RUnlock()

	var pending []*MigrationScript
	for _, script := range dam.migrations {
		if script.Version > currentVersion {
			if targetVersion == 0 || script.Version <= targetVersion {
				pending = append(pending, script)
			}
		}
	}

	for i := range pending {
		for j := i + 1; j < len(pending); j++ {
			if pending[i].Version > pending[j].Version {
				pending[i], pending[j] = pending[j], pending[i]
			}
		}
	}
	return pending
}

func (dam *DataAccessManager) getRollbackMigrations(currentVersion, targetVersion int) []*MigrationScript {
	dam.mu.RLock()
	defer dam.mu.RUnlock()

	var rollback []*MigrationScript
	for _, script := range dam.migrations {
		if script.Version > targetVersion && script.Version <= currentVersion {
			rollback = append(rollback, script)
		}
	}

	for i := range rollback {
		for j := i + 1; j < len(rollback); j++ {
			if rollback[i].Version < rollback[j].Version {
				rollback[i], rollback[j] = rollback[j], rollback[i]
			}
		}
	}
	return rollback
}

func (dam *DataAccessManager) applyMigration(ctx context.Context, script *MigrationScript) error {
	startTime := time.Now()
	migrationID := utils.GenerateID("mg")

	migration := &SchemaMigration{
		ID:          migrationID,
		Version:     script.Version,
		Name:        script.Name,
		Description: script.Description,
		Checksum:    utils.HashSHA256([]byte(script.Up + script.Down)),
		Status:      models.StatusPending,
		AppliedAt:   startTime,
	}

	if err := dam.db.WithContext(ctx).Create(migration).Error; err != nil {
		applogger.Error("failed to create migration record", zap.Error(err))
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "创建迁移记录失败")
	}

	tx := dam.db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return dam.updateMigrationError(ctx, migrationID, tx.Error)
	}

	if err := tx.Exec(script.Up).Error; err != nil {
		tx.Rollback()
		return dam.updateMigrationError(ctx, migrationID, err)
	}

	if err := tx.Commit().Error; err != nil {
		return dam.updateMigrationError(ctx, migrationID, err)
	}

	executionMs := time.Since(startTime).Milliseconds()
	result := dam.db.WithContext(ctx).Model(&SchemaMigration{}).
		Where("id = ?", migrationID).
		Updates(map[string]interface{}{
			"status":       models.StatusCompleted,
			"execution_ms": executionMs,
			"applied_at":   time.Now(),
		})

	if result.Error != nil {
		return apperrors.Wrap(result.Error, apperrors.ErrCodeInternal, "更新迁移状态失败")
	}

	applogger.Info("migration applied successfully",
		zap.Int("version", script.Version),
		zap.String("name", script.Name),
		zap.Int64("execution_ms", executionMs),
	)
	return nil
}

func (dam *DataAccessManager) revertMigration(ctx context.Context, script *MigrationScript) error {
	if script.Down == "" {
		return apperrors.NewWithDetails(apperrors.ErrCodeValidation,
			"回滚脚本不存在",
			fmt.Sprintf("版本 %d 没有定义Down SQL", script.Version),
		)
	}

	startTime := time.Now()

	var existingMigration SchemaMigration
	result := dam.db.WithContext(ctx).
		Where("version = ? AND status = ?", script.Version, models.StatusCompleted).
		First(&existingMigration)

	if result.Error != nil {
		applogger.Error("migration record not found for rollback",
			zap.Int("version", script.Version),
			zap.Error(result.Error),
		)
		return apperrors.Wrap(result.Error, apperrors.ErrCodeNotFound, "迁移记录不存在")
	}

	tx := dam.db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return apperrors.Wrap(tx.Error, apperrors.ErrCodeInternal, "开启回滚事务失败")
	}

	if err := tx.Exec(script.Down).Error; err != nil {
		tx.Rollback()
		applogger.Error("rollback sql execution failed",
			zap.Int("version", script.Version),
			zap.Error(err),
		)
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "执行回滚SQL失败")
	}

	if err := tx.Commit().Error; err != nil {
		return apperrors.Wrap(err, apperrors.ErrCodeInternal, "提交回滚事务失败")
	}

	executionMs := time.Since(startTime).Milliseconds()
	result = dam.db.WithContext(ctx).Model(&SchemaMigration{}).
		Where("id = ?", existingMigration.ID).
		Updates(map[string]interface{}{
			"status":       models.StatusRollback,
			"rollback_at":  time.Now(),
			"execution_ms": executionMs,
		})

	if result.Error != nil {
		return apperrors.Wrap(result.Error, apperrors.ErrCodeInternal, "更新回滚状态失败")
	}

	applogger.Info("migration reverted successfully",
		zap.Int("version", script.Version),
		zap.String("name", script.Name),
		zap.Int64("execution_ms", executionMs),
	)
	return nil
}

func (dam *DataAccessManager) updateMigrationError(ctx context.Context, migrationID string, err error) error {
	updateResult := dam.db.WithContext(ctx).Model(&SchemaMigration{}).
		Where("id = ?", migrationID).
		Updates(map[string]interface{}{
			"status": models.StatusFailed,
			"error":  err.Error(),
		})

	if updateResult.Error != nil {
		return apperrors.Wrap(updateResult.Error, apperrors.ErrCodeInternal, "更新迁移错误状态失败")
	}
	return apperrors.Wrap(err, apperrors.ErrCodeInternal, "执行迁移失败")
}

type gormLogger struct {
	logLevel gormlogger.LogLevel
}

func newGormLogger() *gormLogger {
	return &gormLogger{
		logLevel: gormlogger.Warn,
	}
}

func (l *gormLogger) LogMode(level gormlogger.LogLevel) gormlogger.Interface {
	newLogger := *l
	newLogger.logLevel = level
	return &newLogger
}

func (l *gormLogger) Info(ctx context.Context, msg string, data ...interface{}) {
	if l.logLevel >= gormlogger.Info {
		applogger.Info(fmt.Sprintf(msg, data...))
	}
}

func (l *gormLogger) Warn(ctx context.Context, msg string, data ...interface{}) {
	if l.logLevel >= gormlogger.Warn {
		applogger.Warn(fmt.Sprintf(msg, data...))
	}
}

func (l *gormLogger) Error(ctx context.Context, msg string, data ...interface{}) {
	if l.logLevel >= gormlogger.Error {
		applogger.Error(fmt.Sprintf(msg, data...))
	}
}

func (l *gormLogger) Trace(ctx context.Context, begin time.Time, fc func() (string, int64), err error) {
	if l.logLevel <= gormlogger.Silent {
		return
	}

	elapsed := time.Since(begin)
	sql, rows := fc()

	if err != nil && l.logLevel >= gormlogger.Error {
		applogger.Error("gorm trace",
			zap.Error(err),
			zap.Duration("elapsed", elapsed),
			zap.Int64("rows", rows),
			zap.String("sql", sql),
		)
		return
	}

	if l.logLevel >= gormlogger.Info {
		applogger.Debug("gorm trace",
			zap.Duration("elapsed", elapsed),
			zap.Int64("rows", rows),
			zap.String("sql", sql),
		)
	}
}
