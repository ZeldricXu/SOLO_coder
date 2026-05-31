package database

import (
	"context"
	"fmt"
	"time"

	"github.com/go-redis/redis/v8"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	"github.com/solocoder/task-scheduler/internal/config"
	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/logging"
	"github.com/solocoder/task-scheduler/internal/models"
)

type Database struct {
	DB    *gorm.DB
	Redis *redis.Client
}

func New(cfg *config.AppConfig) (*Database, error) {
	db := &Database{}

	if err := db.initPostgres(cfg); err != nil {
		return nil, fmt.Errorf("postgres init failed: %w", err)
	}

	if err := db.initRedis(cfg); err != nil {
		return nil, fmt.Errorf("redis init failed: %w", err)
	}

	if err := db.migrate(); err != nil {
		return nil, fmt.Errorf("migration failed: %w", err)
	}

	return db, nil
}

func (d *Database) initPostgres(cfg *config.AppConfig) error {
	logging.Info(context.Background(), "Initializing PostgreSQL connection", nil)

	gormLogger := logger.Default.LogMode(logger.Info)

	var err error
	d.DB, err = gorm.Open(postgres.Open(cfg.Database.DSN), &gorm.Config{
		Logger:               gormLogger,
		PrepareStmt:          true,
		SkipDefaultTransaction: false,
	})

	if err != nil {
		return err
	}

	sqlDB, err := d.DB.DB()
	if err != nil {
		return err
	}

	sqlDB.SetMaxOpenConns(cfg.Database.MaxOpenConns)
	sqlDB.SetMaxIdleConns(cfg.Database.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(cfg.Database.MaxLifetime)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := sqlDB.PingContext(ctx); err != nil {
		return err
	}

	logging.Info(context.Background(), "PostgreSQL connection established", nil)
	return nil
}

func (d *Database) initRedis(cfg *config.AppConfig) error {
	logging.Info(context.Background(), "Initializing Redis connection", nil)

	d.Redis = redis.NewClient(&redis.Options{
		Addr:     cfg.Redis.Address,
		Password: cfg.Redis.Password,
		DB:       cfg.Redis.DB,
		PoolSize: cfg.Redis.PoolSize,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := d.Redis.Ping(ctx).Err(); err != nil {
		return err
	}

	logging.Info(context.Background(), "Redis connection established", nil)
	return nil
}

func (d *Database) migrate() error {
	logging.Info(context.Background(), "Running database migrations", nil)

	err := d.DB.AutoMigrate(
		&models.CoreEntity{},
		&models.ConfigDefinition{},
		&models.RunInstance{},
		&models.MetricsSnapshot{},
		&models.Resource{},
		&models.BatchOperation{},
		&contracts.BackupRecord{},
		&contracts.RestoreRecord{},
	)

	if err != nil {
		return err
	}

	logging.Info(context.Background(), "Database migrations completed", nil)
	return nil
}

func (d *Database) Close() error {
	var errs []error

	if d.DB != nil {
		sqlDB, err := d.DB.DB()
		if err != nil {
			errs = append(errs, err)
		} else if err := sqlDB.Close(); err != nil {
			errs = append(errs, err)
		}
	}

	if d.Redis != nil {
		if err := d.Redis.Close(); err != nil {
			errs = append(errs, err)
		}
	}

	if len(errs) > 0 {
		return fmt.Errorf("errors closing database: %v", errs)
	}

	return nil
}

func (d *Database) Transaction(ctx context.Context, fn func(tx *gorm.DB) error) error {
	return d.DB.WithContext(ctx).Transaction(fn)
}

func (d *Database) HealthCheck(ctx context.Context) error {
	if d.DB != nil {
		sqlDB, err := d.DB.DB()
		if err != nil {
			return fmt.Errorf("postgres health check failed: %w", err)
		}
		if err := sqlDB.PingContext(ctx); err != nil {
			return fmt.Errorf("postgres ping failed: %w", err)
		}
	}

	if d.Redis != nil {
		if err := d.Redis.Ping(ctx).Err(); err != nil {
			return fmt.Errorf("redis ping failed: %w", err)
		}
	}

	return nil
}
