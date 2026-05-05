package rollback

import (
	"database/sql"
	"fmt"
	"strings"
	"time"

	"dbmigrator/internal/database"
	"dbmigrator/pkg/models"
)

type Rollbacker struct {
	db database.IDBConnection
}

func NewRollbacker(db database.IDBConnection) *Rollbacker {
	return &Rollbacker{
		db: db,
	}
}

func (r *Rollbacker) RollbackMigration(migration *models.Migration) (*models.RollbackResult, error) {
	result := &models.RollbackResult{
		MigrationID: migration.MigrationID,
		Version:     migration.Version,
		Success:     false,
	}

	startTime := time.Now()

	if migration.DownScript == "" {
		result.Error = fmt.Errorf("no down script found for migration: %s", migration.MigrationID)
		result.ExecutionTime = time.Since(startTime)
		return result, result.Error
	}

	statements := r.parseSQLStatements(migration.DownScript)

	if len(statements) == 0 {
		result.Success = true
		result.ExecutionTime = time.Since(startTime)
		return result, nil
	}

	tx, err := r.db.Begin()
	if err != nil {
		result.Error = fmt.Errorf("failed to begin transaction: %w", err)
		result.ExecutionTime = time.Since(startTime)
		return result, result.Error
	}

	for _, stmt := range statements {
		stmt = strings.TrimSpace(stmt)
		if stmt == "" {
			continue
		}

		if _, err := tx.Exec(stmt); err != nil {
			tx.Rollback()
			result.Error = fmt.Errorf("failed to execute rollback statement: %s\nError: %w", stmt, err)
			result.ExecutionTime = time.Since(startTime)
			return result, result.Error
		}
	}

	if err := tx.Commit(); err != nil {
		result.Error = fmt.Errorf("failed to commit rollback transaction: %w", err)
		result.ExecutionTime = time.Since(startTime)
		return result, result.Error
	}

	result.Success = true
	result.ExecutionTime = time.Since(startTime)
	return result, nil
}

func (r *Rollbacker) RollbackMigrationTx(tx *sql.Tx, migration *models.Migration) (*models.RollbackResult, error) {
	result := &models.RollbackResult{
		MigrationID: migration.MigrationID,
		Version:     migration.Version,
		Success:     false,
	}

	startTime := time.Now()

	if migration.DownScript == "" {
		result.Error = fmt.Errorf("no down script found for migration: %s", migration.MigrationID)
		result.ExecutionTime = time.Since(startTime)
		return result, result.Error
	}

	statements := r.parseSQLStatements(migration.DownScript)

	if len(statements) == 0 {
		result.Success = true
		result.ExecutionTime = time.Since(startTime)
		return result, nil
	}

	for _, stmt := range statements {
		stmt = strings.TrimSpace(stmt)
		if stmt == "" {
			continue
		}

		if _, err := tx.Exec(stmt); err != nil {
			result.Error = fmt.Errorf("failed to execute rollback statement: %s\nError: %w", stmt, err)
			result.ExecutionTime = time.Since(startTime)
			return result, result.Error
		}
	}

	result.Success = true
	result.ExecutionTime = time.Since(startTime)
	return result, nil
}

func (r *Rollbacker) parseSQLStatements(script string) []string {
	script = strings.ReplaceAll(script, "\r\n", "\n")
	script = strings.ReplaceAll(script, "\r", "\n")

	var statements []string
	var current strings.Builder
	inString := false
	stringChar := rune(0)
	inLineComment := false
	inBlockComment := false

	runes := []rune(script)

	for i := 0; i < len(runes); i++ {
		r := runes[i]

		if inLineComment {
			if r == '\n' {
				inLineComment = false
			}
			continue
		}

		if inBlockComment {
			if r == '*' && i+1 < len(runes) && runes[i+1] == '/' {
				inBlockComment = false
				i++
			}
			continue
		}

		if inString {
			current.WriteRune(r)
			if r == stringChar {
				if i+1 < len(runes) && runes[i+1] == stringChar {
					current.WriteRune(runes[i+1])
					i++
				} else {
					inString = false
				}
			}
			continue
		}

		if r == '-' && i+1 < len(runes) && runes[i+1] == '-' {
			inLineComment = true
			i++
			continue
		}

		if r == '/' && i+1 < len(runes) && runes[i+1] == '*' {
			inBlockComment = true
			i++
			continue
		}

		if r == '\'' || r == '"' || r == '`' {
			inString = true
			stringChar = r
			current.WriteRune(r)
			continue
		}

		if r == ';' {
			stmt := strings.TrimSpace(current.String())
			if stmt != "" {
				statements = append(statements, stmt)
			}
			current.Reset()
			continue
		}

		current.WriteRune(r)
	}

	remaining := strings.TrimSpace(current.String())
	if remaining != "" {
		statements = append(statements, remaining)
	}

	return statements
}

func (r *Rollbacker) PreviewRollback(migration *models.Migration) ([]string, error) {
	if migration.DownScript == "" {
		return nil, fmt.Errorf("no down script found for migration: %s", migration.MigrationID)
	}
	statements := r.parseSQLStatements(migration.DownScript)
	return statements, nil
}

func (r *Rollbacker) Begin() (*sql.Tx, error) {
	return r.db.Begin()
}
