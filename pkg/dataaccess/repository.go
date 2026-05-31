package dataaccess

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"go.uber.org/zap"
	"metricplatform/internal/models"
	"sort"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

type Migration struct {
	Version int
	Name    string
	Up      func(*gorm.DB) error
	Down    func(*gorm.DB) error
}

type Repository struct {
	db         *gorm.DB
	migrations []Migration
	logger     *zap.Logger
}

func NewRepository(dsn string, logger *zap.Logger) (*Repository, error) {
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	return &Repository{
		db:     db,
		logger: logger,
	}, nil
}

func (r *Repository) Init() error {
	err := r.db.AutoMigrate(
		&models.Entity{},
		&models.Config{},
		&models.RunInstance{},
		&models.MetricsSnapshot{},
		&models.AlertRule{},
		&models.Alert{},
		&models.SLO{},
		&models.SLOStatus{},
		&models.MetricDataPoint{},
		&models.LogEntry{},
		&models.SchemaMigration{},
		&models.BackupRecord{},
		&models.Span{},
		&models.SamplingConfig{},
		&models.Task{},
		&models.TaskRun{},
	)
	if err != nil {
		return fmt.Errorf("failed to auto-migrate: %w", err)
	}

	if err := r.db.AutoMigrate(&models.SchemaMigration{}); err != nil {
		return fmt.Errorf("failed to migrate schema_migrations: %w", err)
	}

	r.logger.Info("Database initialized successfully")
	return nil
}

func (r *Repository) RegisterMigrations(migrations []Migration) {
	sort.Slice(migrations, func(i, j int) bool {
		return migrations[i].Version < migrations[j].Version
	})
	r.migrations = migrations
}

func (r *Repository) Migrate() error {
	var currentVersion int
	result := r.db.Model(&models.SchemaMigration{}).Order("version DESC").Limit(1).Pluck("version", &currentVersion)
	if result.Error != nil && result.Error != gorm.ErrRecordNotFound {
		return fmt.Errorf("failed to get current migration version: %w", result.Error)
	}

	r.logger.Info("Current schema version", zap.Int("version", currentVersion))

	for _, migration := range r.migrations {
		if migration.Version > currentVersion {
			r.logger.Info("Applying migration", zap.Int("version", migration.Version), zap.String("name", migration.Name))

			if err := migration.Up(r.db); err != nil {
				return fmt.Errorf("migration %d failed: %w", migration.Version, err)
			}

			checksum := calculateChecksum(migration.Name)
			record := &models.SchemaMigration{
				Version:   migration.Version,
				Name:      migration.Name,
				AppliedAt: time.Now(),
				Checksum:  checksum,
			}

			if err := r.db.Create(record).Error; err != nil {
				return fmt.Errorf("failed to record migration: %w", err)
			}

			r.logger.Info("Migration applied successfully", zap.Int("version", migration.Version))
		}
	}

	return nil
}

func (r *Repository) Rollback(version int) error {
	sort.Slice(r.migrations, func(i, j int) bool {
		return r.migrations[i].Version > r.migrations[j].Version
	})

	for _, migration := range r.migrations {
		if migration.Version > version {
			r.logger.Info("Rolling back migration", zap.Int("version", migration.Version), zap.String("name", migration.Name))

			if migration.Down != nil {
				if err := migration.Down(r.db); err != nil {
					return fmt.Errorf("rollback %d failed: %w", migration.Version, err)
				}
			}

			if err := r.db.Where("version = ?", migration.Version).Delete(&models.SchemaMigration{}).Error; err != nil {
				return fmt.Errorf("failed to delete migration record: %w", err)
			}

			r.logger.Info("Migration rolled back successfully", zap.Int("version", migration.Version))
		}
	}

	return nil
}

func (r *Repository) GetMigrationHistory() ([]models.SchemaMigration, error) {
	var history []models.SchemaMigration
	result := r.db.Order("version ASC").Find(&history)
	return history, result.Error
}

func (r *Repository) GetDB() *gorm.DB {
	return r.db
}

func (r *Repository) SaveEntity(entity *models.Entity) error {
	if entity.ID == "" {
		entity.ID = generateID("ent")
	}
	now := time.Now()
	if entity.CreatedAt.IsZero() {
		entity.CreatedAt = now
	}
	entity.UpdatedAt = now
	return r.db.Create(entity).Error
}

func (r *Repository) GetEntity(id string) (*models.Entity, error) {
	var entity models.Entity
	result := r.db.First(&entity, "id = ?", id)
	if result.Error != nil {
		return nil, result.Error
	}
	return &entity, nil
}

func (r *Repository) UpdateEntity(entity *models.Entity) error {
	entity.UpdatedAt = time.Now()
	return r.db.Save(entity).Error
}

func (r *Repository) SaveConfig(config *models.Config) error {
	if config.ConfigID == "" {
		config.ConfigID = generateID("cfg")
	}
	config.AppliedAt = time.Now()
	return r.db.Create(config).Error
}

func (r *Repository) GetConfig(configID string) (*models.Config, error) {
	var config models.Config
	result := r.db.First(&config, "config_id = ?", configID)
	if result.Error != nil {
		return nil, result.Error
	}
	return &config, nil
}

func (r *Repository) SaveRunInstance(instance *models.RunInstance) error {
	if instance.RunID == "" {
		instance.RunID = generateID("run")
	}
	if instance.StartedAt.IsZero() {
		instance.StartedAt = time.Now()
	}
	return r.db.Create(instance).Error
}

func (r *Repository) UpdateRunInstance(instance *models.RunInstance) error {
	return r.db.Save(instance).Error
}

func (r *Repository) GetRunInstance(runID string) (*models.RunInstance, error) {
	var instance models.RunInstance
	result := r.db.First(&instance, "run_id = ?", runID)
	if result.Error != nil {
		return nil, result.Error
	}
	return &instance, nil
}

func (r *Repository) SaveMetricsSnapshot(snapshot *models.MetricsSnapshot) error {
	if snapshot.SnapshotID == "" {
		snapshot.SnapshotID = generateID("snap")
	}
	if snapshot.Timestamp.IsZero() {
		snapshot.Timestamp = time.Now()
	}
	return r.db.Create(snapshot).Error
}

func (r *Repository) GetMetricsSnapshots(start, end time.Time) ([]models.MetricsSnapshot, error) {
	var snapshots []models.MetricsSnapshot
	result := r.db.Where("timestamp BETWEEN ? AND ?", start, end).Order("timestamp DESC").Find(&snapshots)
	return snapshots, result.Error
}

func (r *Repository) SaveAlertRule(rule *models.AlertRule) error {
	if rule.ID == "" {
		rule.ID = generateID("rule")
	}
	now := time.Now()
	if rule.CreatedAt.IsZero() {
		rule.CreatedAt = now
	}
	rule.UpdatedAt = now
	return r.db.Create(rule).Error
}

func (r *Repository) GetAlertRules() ([]models.AlertRule, error) {
	var rules []models.AlertRule
	result := r.db.Find(&rules)
	return rules, result.Error
}

func (r *Repository) SaveAlert(alert *models.Alert) error {
	if alert.ID == "" {
		alert.ID = generateID("alert")
	}
	if alert.ActiveAt.IsZero() {
		alert.ActiveAt = time.Now()
	}
	return r.db.Create(alert).Error
}

func (r *Repository) UpdateAlert(alert *models.Alert) error {
	return r.db.Save(alert).Error
}

func (r *Repository) GetActiveAlerts() ([]models.Alert, error) {
	var alerts []models.Alert
	result := r.db.Where("state = ?", "firing").Find(&alerts)
	return alerts, result.Error
}

func (r *Repository) SaveMetricDataPoint(point *models.MetricDataPoint) error {
	if point.ID == "" {
		point.ID = generateID("dp")
	}
	if point.Timestamp.IsZero() {
		point.Timestamp = time.Now()
	}
	return r.db.Create(point).Error
}

func (r *Repository) GetMetricDataPoints(metricName string, start, end time.Time) ([]models.MetricDataPoint, error) {
	var points []models.MetricDataPoint
	result := r.db.Where("metric_name = ? AND timestamp BETWEEN ? AND ?", metricName, start, end).Order("timestamp ASC").Find(&points)
	return points, result.Error
}

func (r *Repository) SaveLogEntry(entry *models.LogEntry) error {
	if entry.ID == "" {
		entry.ID = generateID("log")
	}
	if entry.Timestamp.IsZero() {
		entry.Timestamp = time.Now()
	}
	return r.db.Create(entry).Error
}

func (r *Repository) SaveSpan(span *models.Span) error {
	return r.db.Create(span).Error
}

func (r *Repository) GetSpansByTrace(traceID string) ([]models.Span, error) {
	var spans []models.Span
	result := r.db.Where("trace_id = ?", traceID).Order("start_time ASC").Find(&spans)
	return spans, result.Error
}

func (r *Repository) SaveTask(task *models.Task) error {
	if task.ID == "" {
		task.ID = generateID("task")
	}
	now := time.Now()
	if task.CreatedAt.IsZero() {
		task.CreatedAt = now
	}
	task.UpdatedAt = now
	return r.db.Create(task).Error
}

func (r *Repository) UpdateTask(task *models.Task) error {
	task.UpdatedAt = time.Now()
	return r.db.Save(task).Error
}

func (r *Repository) GetTasks() ([]models.Task, error) {
	var tasks []models.Task
	result := r.db.Find(&tasks)
	return tasks, result.Error
}

func (r *Repository) SaveTaskRun(run *models.TaskRun) error {
	if run.ID == "" {
		run.ID = generateID("trun")
	}
	if run.StartedAt.IsZero() {
		run.StartedAt = time.Now()
	}
	return r.db.Create(run).Error
}

func (r *Repository) UpdateTaskRun(run *models.TaskRun) error {
	return r.db.Save(run).Error
}

func (r *Repository) SaveBackupRecord(record *models.BackupRecord) error {
	if record.ID == "" {
		record.ID = generateID("backup")
	}
	return r.db.Create(record).Error
}

func (r *Repository) UpdateBackupRecord(record *models.BackupRecord) error {
	return r.db.Save(record).Error
}

func (r *Repository) GetBackupRecords() ([]models.BackupRecord, error) {
	var records []models.BackupRecord
	result := r.db.Order("started_at DESC").Find(&records)
	return records, result.Error
}

func calculateChecksum(name string) string {
	h := sha256.New()
	h.Write([]byte(name))
	return hex.EncodeToString(h.Sum(nil))
}

func generateID(prefix string) string {
	return fmt.Sprintf("%s_%d", prefix, time.Now().UnixNano())
}

func (r *Repository) Close() error {
	sqlDB, err := r.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}
