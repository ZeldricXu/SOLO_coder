package executor

import (
	"database/sql"
	"fmt"
	"strings"
	"time"

	"dbmigrator/internal/database"
	"dbmigrator/pkg/models"
)

type Executor struct {
	db database.IDBConnection
}

func NewExecutor(db database.IDBConnection) *Executor {
	return &Executor{
		db: db,
	}
}

func (e *Executor) ExecuteMigration(migration *models.Migration) (*models.ExecutionResult, error) {
	result := &models.ExecutionResult{
		MigrationID: migration.MigrationID,
		Version:     migration.Version,
		Success:     false,
	}

	startTime := time.Now()

	statements := e.parseSQLStatements(migration.UpScript)
	result.SQLCount = len(statements)

	if len(statements) == 0 {
		result.Success = true
		result.ExecutionTime = time.Since(startTime)
		return result, nil
	}

	tx, err := e.db.Begin()
	if err != nil {
		result.Error = err
		return result, fmt.Errorf("failed to begin transaction: %w", err)
	}

	for _, stmt := range statements {
		stmt = strings.TrimSpace(stmt)
		if stmt == "" {
			continue
		}

		if _, err := tx.Exec(stmt); err != nil {
			tx.Rollback()
			result.Error = fmt.Errorf("failed to execute statement: %s\nError: %w", stmt, err)
			result.ExecutionTime = time.Since(startTime)
			return result, result.Error
		}
	}

	if err := tx.Commit(); err != nil {
		result.Error = fmt.Errorf("failed to commit transaction: %w", err)
		result.ExecutionTime = time.Since(startTime)
		return result, result.Error
	}

	result.Success = true
	result.ExecutionTime = time.Since(startTime)
	return result, nil
}

func (e *Executor) ExecuteMigrationTx(tx *sql.Tx, migration *models.Migration) (*models.ExecutionResult, error) {
	result := &models.ExecutionResult{
		MigrationID: migration.MigrationID,
		Version:     migration.Version,
		Success:     false,
	}

	startTime := time.Now()

	statements := e.parseSQLStatements(migration.UpScript)
	result.SQLCount = len(statements)

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
			result.Error = fmt.Errorf("failed to execute statement: %s\nError: %w", stmt, err)
			result.ExecutionTime = time.Since(startTime)
			return result, result.Error
		}
	}

	result.Success = true
	result.ExecutionTime = time.Since(startTime)
	return result, nil
}

func (e *Executor) parseSQLStatements(script string) []string {
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

func (e *Executor) PreviewMigration(migration *models.Migration) ([]string, error) {
	statements := e.parseSQLStatements(migration.UpScript)
	return statements, nil
}

func (e *Executor) Begin() (*sql.Tx, error) {
	return e.db.Begin()
}
