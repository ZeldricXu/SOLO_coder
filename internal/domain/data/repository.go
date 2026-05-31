package data

import (
	"context"
	"database/sql"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
)

type DataRepositoryImpl struct {
	db         *sql.DB
	migrations []*Migration
	applied    map[string]bool
	mu         sync.RWMutex
	logger     domain.Logger
}

func NewDataRepositoryImpl(db *sql.DB, logger domain.Logger) *DataRepositoryImpl {
	return &DataRepositoryImpl{
		db:         db,
		migrations: make([]*Migration, 0),
		applied:    make(map[string]bool),
		logger:     logger,
	}
}

func (r *DataRepositoryImpl) RegisterMigration(migration *Migration) error {
	if migration == nil {
		return errors.New(errors.ErrCodeValidation, "migration cannot be nil")
	}
	if migration.Version == "" {
		return errors.New(errors.ErrCodeValidation, "migration version required")
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	for _, m := range r.migrations {
		if m.Version == migration.Version {
			return errors.New(errors.ErrCodeConflict,
				fmt.Sprintf("migration version %s already exists", migration.Version))
		}
	}

	migration.ID = uuid.New().String()
	r.migrations = append(r.migrations, migration)

	sort.Slice(r.migrations, func(i, j int) bool {
		return r.migrations[i].Version < r.migrations[j].Version
	})

	r.logger.Info("Migration registered",
		domain.String("version", migration.Version),
		domain.String("description", migration.Description),
	)

	return nil
}

func (r *DataRepositoryImpl) Migrate(ctx context.Context, targetVersion string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if err := r.ensureMigrationsTable(); err != nil {
		return err
	}

	appliedVersions, err := r.getAppliedVersions()
	if err != nil {
		return err
	}

	for _, migration := range r.migrations {
		if targetVersion != "" && migration.Version > targetVersion {
			break
		}

		if appliedVersions[migration.Version] {
			continue
		}

		if _, err := r.db.ExecContext(ctx, migration.UpSQL); err != nil {
			return errors.Wrap(err, errors.ErrCodeInternal,
				fmt.Sprintf("migration %s failed", migration.Version))
		}

		now := time.Now()
		if _, err := r.db.ExecContext(ctx,
			`INSERT INTO schema_migrations (version, description, applied_at) VALUES ($1, $2, $3)`,
			migration.Version, migration.Description, now); err != nil {
			return errors.Wrap(err, errors.ErrCodeInternal,
				"failed to record migration")
		}

		migration.AppliedAt = now
		r.applied[migration.Version] = true

		r.logger.Info("Migration applied",
			domain.String("version", migration.Version),
		)
	}

	return nil
}

func (r *DataRepositoryImpl) Rollback(ctx context.Context, targetVersion string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if err := r.ensureMigrationsTable(); err != nil {
		return err
	}

	appliedVersions, err := r.getAppliedVersions()
	if err != nil {
		return err
	}

	for i := len(r.migrations) - 1; i >= 0; i-- {
		migration := r.migrations[i]
		if migration.Version <= targetVersion {
			break
		}

		if !appliedVersions[migration.Version] {
			continue
		}

		if _, err := r.db.ExecContext(ctx, migration.DownSQL); err != nil {
			return errors.Wrap(err, errors.ErrCodeInternal,
				fmt.Sprintf("rollback %s failed", migration.Version))
		}

		if _, err := r.db.ExecContext(ctx,
			`DELETE FROM schema_migrations WHERE version = $1`, migration.Version); err != nil {
			return errors.Wrap(err, errors.ErrCodeInternal,
				"failed to remove migration record")
		}

		delete(r.applied, migration.Version)

		r.logger.Info("Migration rolled back",
			domain.String("version", migration.Version),
		)
	}

	return nil
}

func (r *DataRepositoryImpl) GetSchemaVersion(ctx context.Context) (string, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if err := r.ensureMigrationsTable(); err != nil {
		return "", err
	}

	var version string
	err := r.db.QueryRowContext(ctx,
		`SELECT version FROM schema_migrations ORDER BY applied_at DESC LIMIT 1`).Scan(&version)
	if err == sql.ErrNoRows {
		return "0", nil
	}
	if err != nil {
		return "", errors.Wrap(err, errors.ErrCodeInternal, "failed to get schema version")
	}

	return version, nil
}

func (r *DataRepositoryImpl) ListMigrations(ctx context.Context) ([]*Migration, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	result := make([]*Migration, len(r.migrations))
	copy(result, r.migrations)
	return result, nil
}

func (r *DataRepositoryImpl) ensureMigrationsTable() error {
	_, err := r.db.Exec(`
		CREATE TABLE IF NOT EXISTS schema_migrations (
			id UUID PRIMARY KEY,
			version VARCHAR(255) UNIQUE NOT NULL,
			description TEXT,
			applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
		)
	`)
	return err
}

func (r *DataRepositoryImpl) getAppliedVersions() (map[string]bool, error) {
	rows, err := r.db.Query(`SELECT version FROM schema_migrations`)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to query applied migrations")
	}
	defer rows.Close()

	result := make(map[string]bool)
	for rows.Next() {
		var version string
		if err := rows.Scan(&version); err != nil {
			return nil, err
		}
		result[version] = true
	}

	return result, nil
}
