package storage

import (
	"context"
	"fmt"
	"time"

	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type DB struct {
	*gorm.DB
	config *config.DatabaseConfig
}

type DBConfig struct {
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
	ConnMaxIdleTime time.Duration
}

func DefaultDBConfig() DBConfig {
	return DBConfig{
		MaxOpenConns:    100,
		MaxIdleConns:    10,
		ConnMaxLifetime: time.Hour,
		ConnMaxIdleTime: 10 * time.Minute,
	}
}

func NewDB(cfg *config.DatabaseConfig, opts ...func(*DBConfig)) (*DB, error) {
	dbConfig := DefaultDBConfig()
	for _, opt := range opts {
		opt(&dbConfig)
	}

	var gormDB *gorm.DB
	err := util.Do(context.Background(), func() error {
		var err error
		gormDB, err = gorm.Open(postgres.Open(cfg.DSN()), &gorm.Config{
			Logger: logger.Default.LogMode(logger.Warn),
		})
		if err != nil {
			return fmt.Errorf("failed to connect to database: %w", err)
		}

		sqlDB, err := gormDB.DB()
		if err != nil {
			return fmt.Errorf("failed to get database instance: %w", err)
		}

		sqlDB.SetMaxOpenConns(dbConfig.MaxOpenConns)
		sqlDB.SetMaxIdleConns(dbConfig.MaxIdleConns)
		sqlDB.SetConnMaxLifetime(dbConfig.ConnMaxLifetime)
		sqlDB.SetConnMaxIdleTime(dbConfig.ConnMaxIdleTime)

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := sqlDB.PingContext(ctx); err != nil {
			return fmt.Errorf("failed to ping database: %w", err)
		}

		return nil
	}, util.WithMaxRetries(5), util.WithBaseDelay(time.Second), util.WithMaxDelay(30*time.Second))

	if err != nil {
		return nil, err
	}

	return &DB{
		DB:     gormDB,
		config: cfg,
	}, nil
}

func (db *DB) AutoMigrate() error {
	return models.AutoMigrate(db.DB)
}

func (db *DB) Transaction(fn func(tx *gorm.DB) error) error {
	return db.DB.Transaction(fn)
}

func (db *DB) WithContext(ctx context.Context) *gorm.DB {
	return db.DB.WithContext(ctx)
}

func (db *DB) Close() error {
	sqlDB, err := db.DB.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

func (db *DB) HealthCheck(ctx context.Context) error {
	sqlDB, err := db.DB.DB()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	return sqlDB.PingContext(ctx)
}
