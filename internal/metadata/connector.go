package metadata

import (
	"context"
	"database/sql"
	"fmt"
	"session154/internal/logger"

	"go.uber.org/zap"
)

type DBConnector interface {
	Connect(ctx context.Context) error
	Disconnect() error
	DB() *sql.DB
	Config() DataSourceConfig
}

type SQLConnector struct {
	config DataSourceConfig
	db     *sql.DB
}

func NewSQLConnector(config DataSourceConfig) DBConnector {
	return &SQLConnector{config: config}
}

func (c *SQLConnector) Connect(ctx context.Context) error {
	var dsn string
	var driver string

	switch c.config.Type {
	case SourcePostgreSQL:
		driver = "postgres"
		dsn = buildPostgresDSN(c.config)
	case SourceMySQL:
		driver = "mysql"
		dsn = buildMysqlDSN(c.config)
	default:
		return fmt.Errorf("unsupported data source type: %s", c.config.Type)
	}

	db, err := sql.Open(driver, dsn)
	if err != nil {
		return fmt.Errorf("failed to open connection: %w", err)
	}

	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return fmt.Errorf("failed to ping database: %w", err)
	}

	c.db = db
	logger.Info("connector connected to data source",
		zap.String("type", string(c.config.Type)),
		zap.String("database", c.config.Database))
	return nil
}

func (c *SQLConnector) Disconnect() error {
	if c.db != nil {
		return c.db.Close()
	}
	return nil
}

func (c *SQLConnector) DB() *sql.DB                 { return c.db }
func (c *SQLConnector) Config() DataSourceConfig    { return c.config }

func buildPostgresDSN(cfg DataSourceConfig) string {
	if cfg.DSN != "" {
		return cfg.DSN
	}
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=disable",
		cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.Database)
}

func buildMysqlDSN(cfg DataSourceConfig) string {
	if cfg.DSN != "" {
		return cfg.DSN
	}
	return fmt.Sprintf("%s:%s@tcp(%s:%d)/%s",
		cfg.User, cfg.Password, cfg.Host, cfg.Port, cfg.Database)
}
