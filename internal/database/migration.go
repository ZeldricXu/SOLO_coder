package database

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Migration struct {
	Version int64
	Name    string
	Up      func(*gorm.DB) error
	Down    func(*gorm.DB) error
}

type Migrator struct {
	db      *gorm.DB
	sqlDB   *sql.DB
	history map[int64]*Migration
	ordered []int64
}

func NewMigrator(db *gorm.DB) (*Migrator, error) {
	sqlDB, err := db.DB()
	if err != nil {
		return nil, err
	}

	m := &Migrator{
		db:      db,
		sqlDB:   sqlDB,
		history: make(map[int64]*Migration),
	}

	if err := m.ensureMigrationTable(); err != nil {
		return nil, err
	}

	m.registerMigrations()
	return m, nil
}

func (m *Migrator) ensureMigrationTable() error {
	return m.db.AutoMigrate(&models.SchemaMigration{})
}

func (m *Migrator) registerMigrations() {
	migrations := []*Migration{
		{
			Version: 1,
			Name:    "init_core_tables",
			Up:      m.migrateUp1,
			Down:    m.migrateDown1,
		},
		{
			Version: 2,
			Name:    "add_tenant_config_columns",
			Up:      m.migrateUp2,
			Down:    m.migrateDown2,
		},
		{
			Version: 3,
			Name:    "add_sla_tracking_index",
			Up:      m.migrateUp3,
			Down:    m.migrateDown3,
		},
	}

	for _, mig := range migrations {
		m.history[mig.Version] = mig
		m.ordered = append(m.ordered, mig.Version)
	}
	sort.Slice(m.ordered, func(i, j int) bool {
		return m.ordered[i] < m.ordered[j]
	})
}

func (m *Migrator) migrateUp1(db *gorm.DB) error {
	return db.AutoMigrate(
		&models.Tenant{},
		&models.Entity{},
		&models.ConfigDefinition{},
		&models.RunInstance{},
		&models.StatsSnapshot{},
		&models.ApprovalRule{},
		&models.ApprovalTask{},
		&models.Skill{},
		&models.EmployeeSkill{},
		&models.LearningPath{},
		&models.SLAConfiguration{},
		&models.SLATracking{},
		&models.WorkflowDefinition{},
		&models.WorkflowInstance{},
		&models.ScheduledTask{},
	)
}

func (m *Migrator) migrateDown1(db *gorm.DB) error {
	return db.Migrator().DropTable(
		&models.Tenant{},
		&models.Entity{},
		&models.ConfigDefinition{},
		&models.RunInstance{},
		&models.StatsSnapshot{},
		&models.ApprovalRule{},
		&models.ApprovalTask{},
		&models.Skill{},
		&models.EmployeeSkill{},
		&models.LearningPath{},
		&models.SLAConfiguration{},
		&models.SLATracking{},
		&models.WorkflowDefinition{},
		&models.WorkflowInstance{},
		&models.ScheduledTask{},
	)
}

func (m *Migrator) migrateUp2(db *gorm.DB) error {
	return nil
}

func (m *Migrator) migrateDown2(db *gorm.DB) error {
	return nil
}

func (m *Migrator) migrateUp3(db *gorm.DB) error {
	return nil
}

func (m *Migrator) migrateDown3(db *gorm.DB) error {
	return nil
}

func (m *Migrator) GetAppliedVersions(ctx context.Context) (map[int64]bool, error) {
	var migrations []models.SchemaMigration
	if err := m.db.WithContext(ctx).Find(&migrations).Error; err != nil {
		return nil, err
	}

	applied := make(map[int64]bool)
	for _, mig := range migrations {
		applied[mig.Version] = true
	}
	return applied, nil
}

func (m *Migrator) Up(ctx context.Context) error {
	applied, err := m.GetAppliedVersions(ctx)
	if err != nil {
		return err
	}

	for _, version := range m.ordered {
		if applied[version] {
			continue
		}

		mig := m.history[version]
		logger.Info("applying migration", zap.Int64("version", version), zap.String("name", mig.Name))

		tx := m.db.WithContext(ctx).Begin()
		if tx.Error != nil {
			return tx.Error
		}

		if err := mig.Up(tx); err != nil {
			tx.Rollback()
			logger.Error("migration failed", zap.Int64("version", version), zap.Error(err))
			return err
		}

		record := &models.SchemaMigration{
			ID:        fmt.Sprintf("mig_%s", uuid.New().String()[:8]),
			Version:   version,
			Name:      mig.Name,
			AppliedAt: time.Now(),
		}

		if err := tx.Create(record).Error; err != nil {
			tx.Rollback()
			return err
		}

		if err := tx.Commit().Error; err != nil {
			return err
		}

		logger.Info("migration applied", zap.Int64("version", version), zap.String("name", mig.Name))
	}

	return nil
}

func (m *Migrator) Down(ctx context.Context, steps int) error {
	if steps <= 0 {
		return errors.New("steps must be positive")
	}

	applied, err := m.GetAppliedVersions(ctx)
	if err != nil {
		return err
	}

	var toRollback []int64
	for i := len(m.ordered) - 1; i >= 0; i-- {
		version := m.ordered[i]
		if applied[version] {
			toRollback = append(toRollback, version)
			if len(toRollback) >= steps {
				break
			}
		}
	}

	for _, version := range toRollback {
		mig := m.history[version]
		logger.Info("rolling back migration", zap.Int64("version", version), zap.String("name", mig.Name))

		tx := m.db.WithContext(ctx).Begin()
		if tx.Error != nil {
			return tx.Error
		}

		if err := mig.Down(tx); err != nil {
			tx.Rollback()
			logger.Error("rollback failed", zap.Int64("version", version), zap.Error(err))
			return err
		}

		if err := tx.Where("version = ?", version).Delete(&models.SchemaMigration{}).Error; err != nil {
			tx.Rollback()
			return err
		}

		if err := tx.Commit().Error; err != nil {
			return err
		}

		logger.Info("migration rolled back", zap.Int64("version", version), zap.String("name", mig.Name))
	}

	return nil
}

func (m *Migrator) Status(ctx context.Context) (map[string]interface{}, error) {
	applied, err := m.GetAppliedVersions(ctx)
	if err != nil {
		return nil, err
	}

	status := make(map[string]interface{})
	var pending []map[string]interface{}
	var appliedList []map[string]interface{}

	for _, version := range m.ordered {
		mig := m.history[version]
		item := map[string]interface{}{
			"version": version,
			"name":    mig.Name,
		}
		if applied[version] {
			appliedList = append(appliedList, item)
		} else {
			pending = append(pending, item)
		}
	}

	status["current_version"] = m.currentVersion(applied)
	status["pending_migrations"] = pending
	status["applied_migrations"] = appliedList
	status["total_applied"] = len(appliedList)
	status["total_pending"] = len(pending)

	return status, nil
}

func (m *Migrator) currentVersion(applied map[int64]bool) int64 {
	var max int64 = 0
	for v := range applied {
		if v > max {
			max = v
		}
	}
	return max
}

type Repository struct {
	db *gorm.DB
}

func NewRepository(db *gorm.DB) *Repository {
	return &Repository{db: db}
}

func (r *Repository) CreateEntity(ctx context.Context, entity *models.Entity) error {
	return r.db.WithContext(ctx).Create(entity).Error
}

func (r *Repository) GetEntity(ctx context.Context, id string, tenantID string) (*models.Entity, error) {
	var entity models.Entity
	if err := r.db.WithContext(ctx).Where("id = ? AND tenant_id = ?", id, tenantID).First(&entity).Error; err != nil {
		return nil, err
	}
	return &entity, nil
}

func (r *Repository) UpdateEntityStatus(ctx context.Context, id, tenantID, status string) error {
	return r.db.WithContext(ctx).Model(&models.Entity{}).
		Where("id = ? AND tenant_id = ?", id, tenantID).
		Updates(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now(),
		}).Error
}

func (r *Repository) CreateConfigDefinition(ctx context.Context, cfg *models.ConfigDefinition) error {
	return r.db.WithContext(ctx).Create(cfg).Error
}

func (r *Repository) GetConfigDefinition(ctx context.Context, namespace string, tenantID string) (*models.ConfigDefinition, error) {
	var cfg models.ConfigDefinition
	if err := r.db.WithContext(ctx).
		Where("namespace = ? AND tenant_id = ? AND enabled = ?", namespace, tenantID, true).
		Order("version DESC").
		First(&cfg).Error; err != nil {
		return nil, err
	}
	return &cfg, nil
}

func (r *Repository) CreateRunInstance(ctx context.Context, run *models.RunInstance) error {
	return r.db.WithContext(ctx).Create(run).Error
}

func (r *Repository) UpdateRunProgress(ctx context.Context, runID, tenantID string, progress float64, phase string) error {
	return r.db.WithContext(ctx).Model(&models.RunInstance{}).
		Where("run_id = ? AND tenant_id = ?", runID, tenantID).
		Updates(map[string]interface{}{
			"progress":   progress,
			"phase":      phase,
			"updated_at": time.Now(),
		}).Error
}

func (r *Repository) CreateStatsSnapshot(ctx context.Context, snapshot *models.StatsSnapshot) error {
	return r.db.WithContext(ctx).Create(snapshot).Error
}

func (r *Repository) GetStatsSnapshots(ctx context.Context, tenantID string, start, end time.Time, limit int) ([]*models.StatsSnapshot, error) {
	var snapshots []*models.StatsSnapshot
	query := r.db.WithContext(ctx).
		Where("tenant_id = ?", tenantID).
		Where("timestamp BETWEEN ? AND ?", start, end).
		Order("timestamp DESC")

	if limit > 0 {
		query = query.Limit(limit)
	}

	if err := query.Find(&snapshots).Error; err != nil {
		return nil, err
	}
	return snapshots, nil
}

type TableComparison struct {
	SourceTable string
	TargetTable string
	RowsMatched int64
	RowsMissing int64
	RowsExtra   int64
}

func (r *Repository) CompareTables(ctx context.Context, source, target string, keys []string) (*TableComparison, error) {
	keyCols := strings.Join(keys, ", ")

	var matched, missing, extra int64

	matchedQuery := fmt.Sprintf(`
		SELECT COUNT(*) FROM %s s 
		INNER JOIN %s t ON %s
	`, source, target, buildJoinCondition("s", "t", keys))

	missingQuery := fmt.Sprintf(`
		SELECT COUNT(*) FROM %s s 
		LEFT JOIN %s t ON %s 
		WHERE t.%s IS NULL
	`, source, target, buildJoinCondition("s", "t", keys), keys[0])

	extraQuery := fmt.Sprintf(`
		SELECT COUNT(*) FROM %s t 
		LEFT JOIN %s s ON %s 
		WHERE s.%s IS NULL
	`, target, source, buildJoinCondition("s", "t", keys), keys[0])

	if err := r.db.WithContext(ctx).Raw(matchedQuery).Scan(&matched).Error; err != nil {
		return nil, err
	}
	if err := r.db.WithContext(ctx).Raw(missingQuery).Scan(&missing).Error; err != nil {
		return nil, err
	}
	if err := r.db.WithContext(ctx).Raw(extraQuery).Scan(&extra).Error; err != nil {
		return nil, err
	}

	return &TableComparison{
		SourceTable: source,
		TargetTable: target,
		RowsMatched: matched,
		RowsMissing: missing,
		RowsExtra:   extra,
	}, nil
}

func buildJoinCondition(a, b string, keys []string) string {
	conditions := make([]string, len(keys))
	for i, k := range keys {
		conditions[i] = fmt.Sprintf("%s.%s = %s.%s", a, k, b, k)
	}
	return strings.Join(conditions, " AND ")
}
