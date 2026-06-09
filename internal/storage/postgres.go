package storage

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"DF1-56/internal/models"
	pg "DF1-56/internal/storage/postgres"
)

type PostgresConfig struct {
	Host            string
	Port            int
	User            string
	Password        string
	Database        string
	DBName          string
	SSLMode         string
	ConnString      string
	MaxConns        int32
	MinConns        int32
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
	ConnectTimeout  time.Duration
	Timeout         time.Duration
}

type PostgresClient struct {
	pool    *pgxpool.Pool
	config  PostgresConfig
	client  *pg.PostgresClient
}

func NewPostgresClient(cfg PostgresConfig) (*PostgresClient, error) {
	if cfg.ConnString == "" {
		return nil, fmt.Errorf("postgres connection string cannot be empty")
	}

	maxConns := cfg.MaxConns
	if maxConns <= 0 {
		maxConns = 50
	}

	minConns := cfg.MinConns
	if minConns <= 0 {
		minConns = 10
	}

	connectTimeout := cfg.ConnectTimeout
	if connectTimeout <= 0 {
		connectTimeout = 5 * time.Second
	}

	client, err := pg.NewPostgresClient(cfg.ConnString)
	if err != nil {
		return nil, fmt.Errorf("failed to create postgres client: %w", err)
	}

	return &PostgresClient{
		pool:   client.GetConn(),
		config: cfg,
		client: client,
	}, nil
}

func (p *PostgresClient) Pool() *pgxpool.Pool {
	return p.pool
}

func (p *PostgresClient) Ping(ctx context.Context) error {
	return p.client.Ping()
}

func (p *PostgresClient) Close() error {
	return p.client.Close()
}

func (p *PostgresClient) CreateAuditLog(ctx context.Context, log *models.AuditLog) error {
	return p.client.CreateAuditLog(ctx, log)
}

func (p *PostgresClient) GetAuditLogs(ctx context.Context, filter pg.AuditLogFilter) ([]*models.AuditLog, int64, error) {
	return p.client.GetAuditLogs(ctx, filter)
}

func (p *PostgresClient) CreateRoute(ctx context.Context, route *models.Route) error {
	return p.client.CreateRoute(ctx, route)
}

func (p *PostgresClient) UpdateRoute(ctx context.Context, route *models.Route) error {
	return p.client.UpdateRoute(ctx, route)
}

func (p *PostgresClient) DeleteRoute(ctx context.Context, id string) error {
	return p.client.DeleteRoute(ctx, id)
}

func (p *PostgresClient) GetRoute(ctx context.Context, id string) (*models.Route, error) {
	return p.client.GetRoute(ctx, id)
}

func (p *PostgresClient) ListRoutes(ctx context.Context, filter pg.RouteFilter) ([]*models.Route, int64, error) {
	return p.client.ListRoutes(ctx, filter)
}

func (p *PostgresClient) InitSchema(ctx context.Context) error {
	schemaSQL := `
	CREATE TABLE IF NOT EXISTS routes (
		id VARCHAR(64) PRIMARY KEY,
		path VARCHAR(512) NOT NULL,
		method VARCHAR(16) NOT NULL,
		match_type VARCHAR(16) NOT NULL DEFAULT 'prefix',
		regex_pattern VARCHAR(512),
		upstream_url VARCHAR(512) NOT NULL,
		upstream_cluster VARCHAR(64),
		rewrite_path VARCHAR(512),
		protocol VARCHAR(16) NOT NULL DEFAULT 'http',
		timeout BIGINT NOT NULL DEFAULT 30000,
		retry_count INT NOT NULL DEFAULT 0,
		middlewares JSONB,
		rate_limit_policy VARCHAR(64),
		auth_policy VARCHAR(64),
		circuit_breaker VARCHAR(64),
		gray_policy VARCHAR(64),
		mirror_policy VARCHAR(64),
		headers JSONB,
		enabled BOOLEAN NOT NULL DEFAULT true,
		created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
		updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS audit_logs (
		id VARCHAR(64) PRIMARY KEY,
		timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
		request_id VARCHAR(64) NOT NULL,
		trace_id VARCHAR(64),
		user_id VARCHAR(64),
		api_key VARCHAR(64),
		client_ip VARCHAR(45) NOT NULL,
		method VARCHAR(16) NOT NULL,
		path VARCHAR(512) NOT NULL,
		route_id VARCHAR(64),
		upstream VARCHAR(512),
		status_code INT NOT NULL,
		duration_ms BIGINT NOT NULL,
		error TEXT,
		rate_limited BOOLEAN NOT NULL DEFAULT false,
		circuit_broken BOOLEAN NOT NULL DEFAULT false,
		gray_version VARCHAR(64)
	);

	CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs(timestamp);
	CREATE INDEX IF NOT EXISTS idx_audit_logs_request_id ON audit_logs(request_id);
	CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON audit_logs(user_id);
	CREATE INDEX IF NOT EXISTS idx_audit_logs_route_id ON audit_logs(route_id);
	`

	_, err := p.pool.Exec(ctx, schemaSQL)
	return err
}
