package database

import (
	"fmt"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	"session189/internal/domain"
	"session189/internal/infrastructure/logger"
)

var DB *gorm.DB

type Config struct {
	DSN          string
	MaxOpenConns int
	MaxIdleConns int
	MaxLifetime  time.Duration
}

func Init(cfg Config) error {
	newLogger := logger.New(
		&gormLogger{},
		logger.Config{
			SlowThreshold:             200 * time.Millisecond,
			LogLevel:                  logger.Info,
			IgnoreRecordNotFoundError: true,
			Colorful:                  true,
		},
	)

	db, err := gorm.Open(postgres.Open(cfg.DSN), &gorm.Config{
		Logger: newLogger,
	})
	if err != nil {
		return fmt.Errorf("connect database failed: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return fmt.Errorf("get sql db failed: %w", err)
	}

	maxOpen := cfg.MaxOpenConns
	if maxOpen <= 0 {
		maxOpen = 100
	}
	maxIdle := cfg.MaxIdleConns
	if maxIdle <= 0 {
		maxIdle = 10
	}
	maxLifetime := cfg.MaxLifetime
	if maxLifetime <= 0 {
		maxLifetime = time.Hour
	}

	sqlDB.SetMaxOpenConns(maxOpen)
	sqlDB.SetMaxIdleConns(maxIdle)
	sqlDB.SetConnMaxLifetime(maxLifetime)

	if err := sqlDB.Ping(); err != nil {
		return fmt.Errorf("ping database failed: %w", err)
	}

	DB = db
	logger.Info("Database connected successfully")

	if err := autoMigrate(db); err != nil {
		return fmt.Errorf("auto migrate failed: %w", err)
	}

	return nil
}

func autoMigrate(db *gorm.DB) error {
	models := []interface{}{
		&domain.AlertRule{},
		&domain.AlertEvent{},
		&domain.ProfileSample{},
		&domain.FlameGraph{},
		&domain.Entity{},
		&domain.AnomalyResult{},
		&domain.MetricBaseline{},
		&domain.Task{},
		&domain.TaskLog{},
		&domain.SLI{},
		&domain.SLO{},
		&domain.ErrorBudget{},
		&domain.SLIMeasurement{},
		&domain.TraceSpan{},
		&domain.SamplingPolicy{},
		&domain.TraceSummary{},
	}

	for _, model := range models {
		if err := db.AutoMigrate(model); err != nil {
			return fmt.Errorf("migrate %T failed: %w", model, err)
		}
	}

	logger.Info("Database migration completed")
	return nil
}

func GetDB() *gorm.DB {
	return DB
}

func Close() error {
	if DB != nil {
		sqlDB, err := DB.DB()
		if err != nil {
			return err
		}
		return sqlDB.Close()
	}
	return nil
}

type gormLogger struct{}

func (l *gormLogger) Printf(format string, args ...interface{}) {
	logger.Infof(format, args...)
}
