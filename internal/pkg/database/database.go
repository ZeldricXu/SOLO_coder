package database

import (
	"context"
	"fmt"
	"model-inference-platform/internal/pkg/config"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type DB interface {
	Exec(ctx context.Context, query string, args ...interface{}) (int64, error)
	QueryRow(ctx context.Context, query string, args ...interface{}) pgx.Row
	Query(ctx context.Context, query string, args ...interface{}) (pgx.Rows, error)
	Close()
}

type Database struct {
	pool *pgxpool.Pool
}

func New(cfg config.DatabaseConfig) (*Database, error) {
	connStr := fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.DBName, cfg.SSLMode,
	)

	poolConfig, err := pgxpool.ParseConfig(connStr)
	if err != nil {
		return nil, fmt.Errorf("failed to parse config: %w", err)
	}

	pool, err := pgxpool.NewWithConfig(context.Background(), poolConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create pool: %w", err)
	}

	if err := pool.Ping(context.Background()); err != nil {
		return nil, fmt.Errorf("failed to ping: %w", err)
	}

	return &Database{pool: pool}, nil
}

func (d *Database) Pool() *pgxpool.Pool {
	return d.pool
}

func (d *Database) Close() {
	d.pool.Close()
}

func (d *Database) Exec(ctx context.Context, query string, args ...interface{}) (int64, error) {
	result, err := d.pool.Exec(ctx, query, args...)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected(), nil
}

func (d *Database) QueryRow(ctx context.Context, query string, args ...interface{}) pgx.Row {
	return d.pool.QueryRow(ctx, query, args...)
}

func (d *Database) Query(ctx context.Context, query string, args ...interface{}) (pgx.Rows, error) {
	return d.pool.Query(ctx, query, args...)
}
