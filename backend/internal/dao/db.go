package dao

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/featureflag/platform/internal/config"
	"github.com/featureflag/platform/pkg/logger"
)

var DB *pgxpool.Pool

func InitDB() error {
	cfg := config.AppConfig.Database

	poolConfig, err := pgxpool.ParseConfig(cfg.DSN())
	if err != nil {
		return fmt.Errorf("parse db config error: %w", err)
	}

	poolConfig.MaxConns = int32(cfg.MaxOpenConns)
	poolConfig.MinConns = int32(cfg.MaxIdleConns)
	poolConfig.MaxConnLifetime = time.Hour
	poolConfig.MaxConnIdleTime = 30 * time.Minute

	pool, err := pgxpool.NewWithConfig(context.Background(), poolConfig)
	if err != nil {
		return fmt.Errorf("create db pool error: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := pool.Ping(ctx); err != nil {
		return fmt.Errorf("ping db error: %w", err)
	}

	DB = pool
	logger.Info("database connected successfully")
	return nil
}

func CloseDB() {
	if DB != nil {
		DB.Close()
		logger.Info("database connection closed")
	}
}

func Exec(ctx context.Context, sql string, args ...interface{}) (int64, error) {
	result, err := DB.Exec(ctx, sql, args...)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}

func QueryRow(ctx context.Context, sql string, args ...interface{}) *pgxpool.Row {
	return DB.QueryRow(ctx, sql, args...)
}

func Query(ctx context.Context, sql string, args ...interface{}) (pgxpool.Rows, error) {
	return DB.Query(ctx, sql, args...)
}
