package database

import (
	"database/sql"
	"fmt"
	"log"
	"time"

	_ "github.com/lib/pq"
	"pointcloud-platform/config"
)

var DB *sql.DB

func Init(cfg *config.DatabaseConfig) error {
	var err error
	DB, err = sql.Open("postgres", cfg.DSN())
	if err != nil {
		return fmt.Errorf("failed to connect to database: %w", err)
	}

	DB.SetMaxOpenConns(100)
	DB.SetMaxIdleConns(20)
	DB.SetConnMaxLifetime(time.Hour)

	if err := DB.Ping(); err != nil {
		return fmt.Errorf("failed to ping database: %w", err)
	}

	if err := createTables(); err != nil {
		return fmt.Errorf("failed to create tables: %w", err)
	}

	log.Println("Database initialized successfully")
	return nil
}

func createTables() error {
	schemas := []string{
		`CREATE TABLE IF NOT EXISTS projects (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			name VARCHAR(255) NOT NULL,
			description TEXT,
			owner_id UUID NOT NULL,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS datasets (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
			name VARCHAR(255) NOT NULL,
			description TEXT,
			point_count BIGINT DEFAULT 0,
			bounds_min_x DOUBLE PRECISION,
			bounds_min_y DOUBLE PRECISION,
			bounds_min_z DOUBLE PRECISION,
			bounds_max_x DOUBLE PRECISION,
			bounds_max_y DOUBLE PRECISION,
			bounds_max_z DOUBLE PRECISION,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS dataset_versions (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			dataset_id UUID NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
			version INTEGER NOT NULL,
			file_path VARCHAR(512) NOT NULL,
			file_format VARCHAR(10) NOT NULL,
			file_size BIGINT,
			point_count BIGINT,
			scale_factor_x DOUBLE PRECISION DEFAULT 1,
			scale_factor_y DOUBLE PRECISION DEFAULT 1,
			scale_factor_z DOUBLE PRECISION DEFAULT 1,
			offset_x DOUBLE PRECISION DEFAULT 0,
			offset_y DOUBLE PRECISION DEFAULT 0,
			offset_z DOUBLE PRECISION DEFAULT 0,
			coord_system VARCHAR(50),
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			UNIQUE(dataset_id, version)
		)`,
		`CREATE TABLE IF NOT EXISTS users (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			username VARCHAR(50) UNIQUE NOT NULL,
			email VARCHAR(255) UNIQUE NOT NULL,
			password_hash VARCHAR(255) NOT NULL,
			role VARCHAR(20) DEFAULT 'user',
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS project_permissions (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
			user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			permission VARCHAR(20) NOT NULL,
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			UNIQUE(project_id, user_id, permission)
		)`,
		`CREATE TABLE IF NOT EXISTS annotations (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			dataset_id UUID NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
			version_id UUID REFERENCES dataset_versions(id) ON DELETE CASCADE,
			type VARCHAR(30) NOT NULL,
			label VARCHAR(255),
			geometry JSONB NOT NULL,
			properties JSONB,
			creator_id UUID NOT NULL REFERENCES users(id),
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
			updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE TABLE IF NOT EXISTS measurements (
			id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
			dataset_id UUID NOT NULL REFERENCES datasets(id) ON DELETE CASCADE,
			type VARCHAR(20) NOT NULL,
			points JSONB NOT NULL,
			value DOUBLE PRECISION,
			unit VARCHAR(20),
			label VARCHAR(255),
			creator_id UUID NOT NULL REFERENCES users(id),
			created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
		)`,
		`CREATE INDEX IF NOT EXISTS idx_annotations_dataset ON annotations(dataset_id)`,
		`CREATE INDEX IF NOT EXISTS idx_annotations_type ON annotations(type)`,
		`CREATE INDEX IF NOT EXISTS idx_measurements_dataset ON measurements(dataset_id)`,
		`CREATE INDEX IF NOT EXISTS idx_datasets_project ON datasets(project_id)`,
	}

	for _, schema := range schemas {
		if _, err := DB.Exec(schema); err != nil {
			return fmt.Errorf("failed to execute schema: %w, sql: %s", err, schema)
		}
	}

	return nil
}

func Close() {
	if DB != nil {
		DB.Close()
	}
}
