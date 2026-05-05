package database

import (
	"database/sql"
	"fmt"
	"dbmigrator/pkg/models"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/lib/pq"
)

type DBConnection struct {
	db     *sql.DB
	config *models.DatabaseConfig
	driver string
}

func NewDBConnection(config *models.DatabaseConfig) (*DBConnection, error) {
	conn := &DBConnection{
		config: config,
		driver: config.Driver,
	}

	if err := conn.connect(); err != nil {
		return nil, err
	}

	return conn, nil
}

func (c *DBConnection) connect() error {
	var dsn string
	var driverName string

	switch c.driver {
	case "mysql":
		driverName = "mysql"
		dsn = fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?parseTime=true&multiStatements=true",
			c.config.User,
			c.config.Password,
			c.config.Host,
			c.config.Port,
			c.config.DBName,
		)
	case "postgres", "postgresql":
		driverName = "postgres"
		sslMode := c.config.SSLMode
		if sslMode == "" {
			sslMode = "disable"
		}
		dsn = fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
			c.config.Host,
			c.config.Port,
			c.config.User,
			c.config.Password,
			c.config.DBName,
			sslMode,
		)
	default:
		return fmt.Errorf("unsupported database driver: %s", c.driver)
	}

	db, err := sql.Open(driverName, dsn)
	if err != nil {
		return fmt.Errorf("failed to open database connection: %w", err)
	}

	if err := db.Ping(); err != nil {
		db.Close()
		return fmt.Errorf("failed to ping database: %w", err)
	}

	c.db = db
	return nil
}

func (c *DBConnection) Close() error {
	if c.db != nil {
		return c.db.Close()
	}
	return nil
}

func (c *DBConnection) DB() *sql.DB {
	return c.db
}

func (c *DBConnection) Driver() string {
	return c.driver
}

func (c *DBConnection) Begin() (*sql.Tx, error) {
	return c.db.Begin()
}

func (c *DBConnection) Exec(query string, args ...interface{}) (sql.Result, error) {
	return c.db.Exec(query, args...)
}

func (c *DBConnection) Query(query string, args ...interface{}) (*sql.Rows, error) {
	return c.db.Query(query, args...)
}

func (c *DBConnection) QueryRow(query string, args ...interface{}) *sql.Row {
	return c.db.QueryRow(query, args...)
}

func (c *DBConnection) EnsureMigrationTable(tableName string) error {
	var createTableSQL string

	switch c.driver {
	case "mysql":
		createTableSQL = fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				status_id VARCHAR(64) PRIMARY KEY,
				migration_id VARCHAR(255) NOT NULL UNIQUE,
				status ENUM('pending', 'executed', 'rolled_back', 'failed') NOT NULL DEFAULT 'pending',
				executed_at DATETIME,
				execution_time_ms BIGINT DEFAULT 0,
				affected_objects JSON,
				rollback_available BOOLEAN DEFAULT TRUE,
				created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
				INDEX idx_migration_id (migration_id),
				INDEX idx_status (status)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
		`, tableName)

	case "postgres":
		createTableSQL = fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				status_id VARCHAR(64) PRIMARY KEY,
				migration_id VARCHAR(255) NOT NULL UNIQUE,
				status VARCHAR(20) NOT NULL DEFAULT 'pending',
				executed_at TIMESTAMP,
				execution_time_ms BIGINT DEFAULT 0,
				affected_objects JSONB,
				rollback_available BOOLEAN DEFAULT TRUE,
				created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
			);
			CREATE INDEX IF NOT EXISTS idx_%s_migration_id ON %s(migration_id);
			CREATE INDEX IF NOT EXISTS idx_%s_status ON %s(status);
		`, tableName, tableName, tableName, tableName, tableName)
	}

	_, err := c.db.Exec(createTableSQL)
	if err != nil {
		return fmt.Errorf("failed to create migration table %s: %w", tableName, err)
	}

	return nil
}

func (c *DBConnection) EnsureLogTable(tableName string) error {
	var createTableSQL string

	switch c.driver {
	case "mysql":
		createTableSQL = fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				log_id VARCHAR(64) PRIMARY KEY,
				migration_id VARCHAR(255) NOT NULL,
				action VARCHAR(50) NOT NULL,
				sql_statements JSON,
				result VARCHAR(20) NOT NULL,
				error_message TEXT,
				logged_at DATETIME DEFAULT CURRENT_TIMESTAMP,
				INDEX idx_migration_id (migration_id),
				INDEX idx_logged_at (logged_at)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
		`, tableName)

	case "postgres":
		createTableSQL = fmt.Sprintf(`
			CREATE TABLE IF NOT EXISTS %s (
				log_id VARCHAR(64) PRIMARY KEY,
				migration_id VARCHAR(255) NOT NULL,
				action VARCHAR(50) NOT NULL,
				sql_statements JSONB,
				result VARCHAR(20) NOT NULL,
				error_message TEXT,
				logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
			);
			CREATE INDEX IF NOT EXISTS idx_%s_migration_id ON %s(migration_id);
			CREATE INDEX IF NOT EXISTS idx_%s_logged_at ON %s(logged_at);
		`, tableName, tableName, tableName, tableName, tableName)
	}

	_, err := c.db.Exec(createTableSQL)
	if err != nil {
		return fmt.Errorf("failed to create log table %s: %w", tableName, err)
	}

	return nil
}
