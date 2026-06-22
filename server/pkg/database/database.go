package database

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	_ "github.com/lib/pq"

	"onboarding-server/internal/config"
)

var DB *sql.DB

func Connect(cfg *config.Config) (*sql.DB, error) {
	db, err := sql.Open("postgres", cfg.DBConnectionString())
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}

	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("ping database: %w", err)
	}

	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)

	DB = db
	return db, nil
}

func RunMigrations(db *sql.DB, migrationsDir string) error {
	if _, err := os.Stat(migrationsDir); os.IsNotExist(err) {
		return fmt.Errorf("migrations directory not found: %s", migrationsDir)
	}

	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version VARCHAR(255) PRIMARY KEY,
			applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		)
	`)
	if err != nil {
		return fmt.Errorf("create schema_migrations table: %w", err)
	}

	files, err := os.ReadDir(migrationsDir)
	if err != nil {
		return fmt.Errorf("read migrations directory: %w", err)
	}

	var sqlFiles []string
	for _, f := range files {
		if !f.IsDir() && strings.HasSuffix(f.Name(), ".sql") {
			sqlFiles = append(sqlFiles, f.Name())
		}
	}
	sort.Strings(sqlFiles)

	for _, filename := range sqlFiles {
		var exists bool
		err := db.QueryRow("SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = $1)", filename).Scan(&exists)
		if err != nil {
			return fmt.Errorf("check migration %s: %w", filename, err)
		}
		if exists {
			continue
		}

		content, err := os.ReadFile(filepath.Join(migrationsDir, filename))
		if err != nil {
			return fmt.Errorf("read migration %s: %w", filename, err)
		}

		tx, err := db.Begin()
		if err != nil {
			return fmt.Errorf("begin transaction for %s: %w", filename, err)
		}

		if _, err := tx.Exec(string(content)); err != nil {
			tx.Rollback()
			return fmt.Errorf("execute migration %s: %w", filename, err)
		}

		if _, err := tx.Exec("INSERT INTO schema_migrations (version) VALUES ($1)", filename); err != nil {
			tx.Rollback()
			return fmt.Errorf("record migration %s: %w", filename, err)
		}

		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %s: %w", filename, err)
		}
	}

	return nil
}

func Close() {
	if DB != nil {
		DB.Close()
	}
}
