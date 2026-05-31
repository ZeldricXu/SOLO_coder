package database

import (
	"fmt"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"

	"llmgateway/internal/domain/entity"
	applogger "llmgateway/internal/infrastructure/logger"
)

type Config struct {
	DSN          string
	MaxOpenConns int
	MaxIdleConns int
}

var db *gorm.DB

func Init(cfg Config) error {
	gormDB, err := gorm.Open(postgres.Open(cfg.DSN), &gorm.Config{
		Logger: gormlogger.Default.LogMode(gormlogger.Warn),
	})
	if err != nil {
		return fmt.Errorf("failed to connect database: %w", err)
	}

	sqlDB, err := gormDB.DB()
	if err != nil {
		return fmt.Errorf("failed to get sql db: %w", err)
	}

	sqlDB.SetMaxOpenConns(cfg.MaxOpenConns)
	sqlDB.SetMaxIdleConns(cfg.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(time.Hour)

	if err := sqlDB.Ping(); err != nil {
		return fmt.Errorf("failed to ping database: %w", err)
	}

	db = gormDB
	applogger.Info("database connected successfully")
	return nil
}

func AutoMigrate() error {
	err := db.AutoMigrate(
		&entity.Config{},
		&entity.RunInstance{},
		&entity.Snapshot{},
		&entity.Model{},
		&entity.ModelVersion{},
		&entity.Provider{},
		&entity.ModelEndpoint{},
		&entity.GPUResource{},
		&entity.Task{},
		&entity.Prompt{},
		&entity.PromptVersion{},
		&entity.ABExperiment{},
		&entity.ExperimentResult{},
		&entity.EvaluationMetric{},
		&entity.DriftDetection{},
		&entity.EvaluationDataset{},
		&entity.EvaluationRun{},
		&entity.MonitorConfig{},
		&entity.AdversarialPrompt{},
		&entity.AttackStrategy{},
		&entity.SecurityAssessment{},
		&entity.Vulnerability{},
		&entity.Document{},
		&entity.DocumentChunk{},
		&entity.ChunkVector{},
		&entity.ParsePipeline{},
		&entity.PipelineExecution{},
		&entity.Feature{},
		&entity.FeatureValue{},
		&entity.FeatureSet{},
		&entity.FeatureView{},
		&entity.OnlineStore{},
		&entity.OfflineStore{},
	)
	if err != nil {
		return fmt.Errorf("failed to migrate database: %w", err)
	}
	applogger.Info("database migration completed")
	return nil
}

func DB() *gorm.DB {
	return db
}

func Begin() *gorm.DB {
	return db.Begin()
}

func WithTransaction(fn func(tx *gorm.DB) error) error {
	tx := db.Begin()
	if tx.Error != nil {
		return tx.Error
	}

	defer func() {
		if r := recover(); r != nil {
			tx.Rollback()
			panic(r)
		}
	}()

	if err := fn(tx); err != nil {
		tx.Rollback()
		return err
	}

	return tx.Commit().Error
}

func Close() error {
	if db != nil {
		sqlDB, err := db.DB()
		if err != nil {
			return err
		}
		return sqlDB.Close()
	}
	return nil
}
