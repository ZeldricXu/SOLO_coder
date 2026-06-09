package postgres

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type PostgresClient struct {
	pool *pgxpool.Pool
	mu   sync.RWMutex
}

func NewPostgresClient(connString string) (*PostgresClient, error) {
	if connString == "" {
		return nil, fmt.Errorf("connection string is required")
	}

	config, err := pgxpool.ParseConfig(connString)
	if err != nil {
		return nil, fmt.Errorf("failed to parse config: %w", err)
	}

	config.MaxConns = 50
	config.MinConns = 10
	config.MaxConnLifetime = 1 * time.Hour
	config.MaxConnIdleTime = 30 * time.Minute
	config.HealthCheckPeriod = 5 * time.Minute
	config.ConnConfig.ConnectTimeout = 5 * time.Second

	pool, err := pgxpool.NewWithConfig(context.Background(), config)
	if err != nil {
		return nil, fmt.Errorf("failed to create pool: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("failed to ping postgres: %w", err)
	}

	return &PostgresClient{
		pool: pool,
	}, nil
}

func (p *PostgresClient) Ping() error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	p.mu.RLock()
	defer p.mu.RUnlock()

	return p.pool.Ping(ctx)
}

func (p *PostgresClient) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.pool.Close()
	return nil
}

func (p *PostgresClient) GetConn() *pgxpool.Pool {
	p.mu.RLock()
	defer p.mu.RUnlock()

	return p.pool
}

func (p *PostgresClient) BeginTx(ctx context.Context) (pgx.Tx, error) {
	p.mu.RLock()
	defer p.mu.RUnlock()

	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to begin transaction: %w", err)
	}
	return tx, nil
}

func (p *PostgresClient) ExecTx(ctx context.Context, fn func(tx pgx.Tx) error) error {
	tx, err := p.BeginTx(ctx)
	if err != nil {
		return err
	}
	defer func() {
		if r := recover(); r != nil {
			_ = tx.Rollback(ctx)
			panic(r)
		}
	}()

	if err := fn(tx); err != nil {
		if rbErr := tx.Rollback(ctx); rbErr != nil {
			return fmt.Errorf("fn failed: %w, rollback failed: %w", err, rbErr)
		}
		return fmt.Errorf("fn failed, rolled back: %w", err)
	}

	if err := tx.Commit(ctx); err != nil {
		if rbErr := tx.Rollback(ctx); rbErr != nil {
			return fmt.Errorf("commit failed: %w, rollback failed: %w", err, rbErr)
		}
		return fmt.Errorf("commit failed: %w", err)
	}
	return nil
}
