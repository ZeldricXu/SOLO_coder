package db

import (
	"database/sql"
	"fmt"
	"time"
)

const CurrentSchemaVersion = 2

type SchemaVersion struct {
	Version   int
	AppliedAt time.Time
}

func (db *Database) GetSchemaVersion() (int, error) {
	var version int
	err := db.QueryRow("SELECT version FROM schema_version ORDER BY version DESC LIMIT 1").Scan(&version)
	if err != nil {
		if err == sql.ErrNoRows {
			return 0, nil
		}
		_, checkErr := db.Exec("SELECT 1 FROM schema_version LIMIT 1")
		if checkErr != nil {
			return 0, nil
		}
		return 0, fmt.Errorf("get schema version: %w", err)
	}
	return version, nil
}

func (db *Database) SetSchemaVersion(version int) error {
	_, err := db.Exec(`
		INSERT OR REPLACE INTO schema_version (version, applied_at) VALUES (?, ?)
	`, version, time.Now())
	return err
}

func (db *Database) ensureSchemaVersionTable() {
	db.Exec(`CREATE TABLE IF NOT EXISTS schema_version (
		version INTEGER PRIMARY KEY,
		applied_at DATETIME DEFAULT CURRENT_TIMESTAMP
	)`)

	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM schema_version").Scan(&count)
	if err == nil && count == 0 {
		db.Exec("INSERT INTO schema_version (version, applied_at) VALUES (1, ?)", time.Now())
	}
}

func (db *Database) RunMigrations() error {
	db.ensureSchemaVersionTable()

	currentVersion, err := db.GetSchemaVersion()
	if err != nil {
		return fmt.Errorf("get schema version: %w", err)
	}

	if currentVersion >= CurrentSchemaVersion {
		return nil
	}

	migrations := map[int][]string{
		2: {
			`ALTER TABLE notes ADD COLUMN content_preview TEXT DEFAULT ''`,
			`CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated_at)`,
			`CREATE TABLE IF NOT EXISTS note_vectors_status (
				note_id INTEGER PRIMARY KEY,
				vector_status TEXT DEFAULT 'pending',
				updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
			)`,
			`CREATE INDEX IF NOT EXISTS idx_vectors_status ON note_vectors_status(vector_status)`,
		},
	}

	for v := currentVersion + 1; v <= CurrentSchemaVersion; v++ {
		steps, ok := migrations[v]
		if !ok {
			continue
		}

		tx, err := db.Begin()
		if err != nil {
			return fmt.Errorf("begin migration transaction for version %d: %w", v, err)
		}

		for _, step := range steps {
			if _, err := tx.Exec(step); err != nil {
				tx.Rollback()
				return fmt.Errorf("migration v%d failed: %w, sql: %s", v, err, step)
			}
		}

		if _, err := tx.Exec(`
			INSERT OR REPLACE INTO schema_version (version, applied_at) VALUES (?, ?)
		`, v, time.Now()); err != nil {
			tx.Rollback()
			return fmt.Errorf("update schema version to %d: %w", v, err)
		}

		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration v%d: %w", v, err)
		}
	}

	return nil
}
