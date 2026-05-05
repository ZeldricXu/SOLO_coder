package executor

import (
	"database/sql"
	"fmt"
	"strings"
	"time"

	"dbmigrator/internal/database"
	"dbmigrator/pkg/models"
)

type TransactionManager struct {
	db *database.DBConnection
}

type TransactionResult struct {
	Success       bool
	Error         error
	ExecutionTime time.Duration
	SQLCount      int
}

func NewTransactionManager(db *database.DBConnection) *TransactionManager {
	return &TransactionManager{
		db: db,
	}
}

func (tm *TransactionManager) ExecuteInTransaction(exec func(*sql.Tx) error) (*TransactionResult, error) {
	result := &TransactionResult{
		Success: false,
	}

	startTime := time.Now()

	tx, err := tm.db.Begin()
	if err != nil {
		result.Error = err
		return result, fmt.Errorf("failed to begin transaction: %w", err)
	}

	defer func() {
		if r := recover(); r != nil {
			tx.Rollback()
			result.Error = fmt.Errorf("panic during transaction: %v", r)
		}
	}()

	if err := exec(tx); err != nil {
		if rollbackErr := tx.Rollback(); rollbackErr != nil {
			result.Error = fmt.Errorf("execution failed: %v; rollback also failed: %v", err, rollbackErr)
		} else {
			result.Error = err
		}
		result.ExecutionTime = time.Since(startTime)
		return result, result.Error
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

func (tm *TransactionManager) ExecuteMigration(migration *models.Migration) (*models.ExecutionResult, error) {
	result := &models.ExecutionResult{
		MigrationID: migration.MigrationID,
		Version:     migration.Version,
		Success:     false,
	}

	statements := tm.parseSQLStatements(migration.UpScript)
	result.SQLCount = len(statements)

	if len(statements) == 0 {
		result.Success = true
		return result, nil
	}

	txResult, err := tm.ExecuteInTransaction(func(tx *sql.Tx) error {
		for i, stmt := range statements {
			stmt = trimSQLStatement(stmt)
			if stmt == "" {
				continue
			}

			if _, execErr := tx.Exec(stmt); execErr != nil {
				return fmt.Errorf("statement %d failed: %s\nError: %w", i+1, truncateStatement(stmt, 100), execErr)
			}
		}
		return nil
	})

	if err != nil {
		result.Error = err
		result.ExecutionTime = txResult.ExecutionTime
		return result, err
	}

	result.Success = txResult.Success
	result.ExecutionTime = txResult.ExecutionTime
	return result, nil
}

func (tm *TransactionManager) ExecuteRollback(migration *models.Migration) (*models.RollbackResult, error) {
	result := &models.RollbackResult{
		MigrationID: migration.MigrationID,
		Version:     migration.Version,
		Success:     false,
	}

	if migration.DownScript == "" {
		result.Error = fmt.Errorf("no down script found for migration: %s", migration.MigrationID)
		return result, result.Error
	}

	statements := tm.parseSQLStatements(migration.DownScript)

	if len(statements) == 0 {
		result.Success = true
		return result, nil
	}

	txResult, err := tm.ExecuteInTransaction(func(tx *sql.Tx) error {
		for i, stmt := range statements {
			stmt = trimSQLStatement(stmt)
			if stmt == "" {
				continue
			}

			if _, execErr := tx.Exec(stmt); execErr != nil {
				return fmt.Errorf("rollback statement %d failed: %s\nError: %w", i+1, truncateStatement(stmt, 100), execErr)
			}
		}
		return nil
	})

	if err != nil {
		result.Error = err
		result.ExecutionTime = txResult.ExecutionTime
		return result, err
	}

	result.Success = txResult.Success
	result.ExecutionTime = txResult.ExecutionTime
	return result, nil
}

func (tm *TransactionManager) parseSQLStatements(script string) []string {
	script = normalizeLineEndings(script)

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
			stmt := trimSQLStatement(current.String())
			if stmt != "" {
				statements = append(statements, stmt)
			}
			current.Reset()
			continue
		}

		current.WriteRune(r)
	}

	remaining := trimSQLStatement(current.String())
	if remaining != "" {
		statements = append(statements, remaining)
	}

	return statements
}

func (tm *TransactionManager) PreviewMigration(migration *models.Migration) ([]string, error) {
	statements := tm.parseSQLStatements(migration.UpScript)
	return statements, nil
}

func (tm *TransactionManager) PreviewRollback(migration *models.Migration) ([]string, error) {
	if migration.DownScript == "" {
		return nil, fmt.Errorf("no down script found for migration: %s", migration.MigrationID)
	}
	statements := tm.parseSQLStatements(migration.DownScript)
	return statements, nil
}

func normalizeLineEndings(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	s = strings.ReplaceAll(s, "\r", "\n")
	return s
}

func trimSQLStatement(s string) string {
	s = strings.TrimSpace(s)
	s = strings.Trim(s, "\n\r\t")
	return s
}

func truncateStatement(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen-3] + "..."
}
