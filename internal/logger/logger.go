package logger

import (
	"encoding/json"
	"fmt"
	"time"

	"dbmigrator/internal/database"
	"dbmigrator/pkg/models"
)

type Logger struct {
	db        *database.DBConnection
	tableName string
}

func NewLogger(db *database.DBConnection, tableName string) (*Logger, error) {
	l := &Logger{
		db:        db,
		tableName: tableName,
	}

	if err := l.ensureTable(); err != nil {
		return nil, err
	}

	return l, nil
}

func (l *Logger) ensureTable() error {
	return l.db.EnsureLogTable(l.tableName)
}

func (l *Logger) LogExecution(migration *models.Migration, action string, sqlStatements []string, result *models.ExecutionResult) error {
	logID := fmt.Sprintf("log_%s_%d", migration.MigrationID, time.Now().UnixNano())

	resultStr := "success"
	errorMsg := ""
	if !result.Success && result.Error != nil {
		resultStr = "failed"
		errorMsg = result.Error.Error()
	}

	sqlJSON, err := json.Marshal(sqlStatements)
	if err != nil {
		sqlJSON = []byte("[]")
	}

	var query string
	var args []interface{}

	switch l.db.Driver() {
	case "mysql":
		query = fmt.Sprintf(`
			INSERT INTO %s (log_id, migration_id, action, sql_statements, result, error_message, logged_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
		`, l.tableName)
		args = []interface{}{
			logID,
			migration.MigrationID,
			action,
			string(sqlJSON),
			resultStr,
			errorMsg,
			time.Now(),
		}

	case "postgres":
		query = fmt.Sprintf(`
			INSERT INTO %s (log_id, migration_id, action, sql_statements, result, error_message, logged_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7)
		`, l.tableName)
		args = []interface{}{
			logID,
			migration.MigrationID,
			action,
			string(sqlJSON),
			resultStr,
			errorMsg,
			time.Now(),
		}
	}

	_, err = l.db.Exec(query, args...)
	if err != nil {
		return fmt.Errorf("failed to log execution: %w", err)
	}

	return nil
}

func (l *Logger) LogRollback(migration *models.Migration, sqlStatements []string, result *models.RollbackResult) error {
	logID := fmt.Sprintf("log_%s_%d", migration.MigrationID, time.Now().UnixNano())

	resultStr := "success"
	errorMsg := ""
	if !result.Success && result.Error != nil {
		resultStr = "failed"
		errorMsg = result.Error.Error()
	}

	sqlJSON, err := json.Marshal(sqlStatements)
	if err != nil {
		sqlJSON = []byte("[]")
	}

	var query string
	var args []interface{}

	switch l.db.Driver() {
	case "mysql":
		query = fmt.Sprintf(`
			INSERT INTO %s (log_id, migration_id, action, sql_statements, result, error_message, logged_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
		`, l.tableName)
		args = []interface{}{
			logID,
			migration.MigrationID,
			"rollback",
			string(sqlJSON),
			resultStr,
			errorMsg,
			time.Now(),
		}

	case "postgres":
		query = fmt.Sprintf(`
			INSERT INTO %s (log_id, migration_id, action, sql_statements, result, error_message, logged_at)
			VALUES ($1, $2, $3, $4, $5, $6, $7)
		`, l.tableName)
		args = []interface{}{
			logID,
			migration.MigrationID,
			"rollback",
			string(sqlJSON),
			resultStr,
			errorMsg,
			time.Now(),
		}
	}

	_, err = l.db.Exec(query, args...)
	if err != nil {
		return fmt.Errorf("failed to log rollback: %w", err)
	}

	return nil
}

func (l *Logger) GetLogs(migrationID string, limit int) ([]*models.MigrationLog, error) {
	var query string
	var args []interface{}

	if migrationID != "" {
		switch l.db.Driver() {
		case "mysql":
			query = fmt.Sprintf(`
				SELECT log_id, migration_id, action, sql_statements, result, error_message, logged_at
				FROM %s
				WHERE migration_id = ?
				ORDER BY logged_at DESC
				LIMIT ?
			`, l.tableName)
		case "postgres":
			query = fmt.Sprintf(`
				SELECT log_id, migration_id, action, sql_statements, result, error_message, logged_at
				FROM %s
				WHERE migration_id = $1
				ORDER BY logged_at DESC
				LIMIT $2
			`, l.tableName)
		}
		args = []interface{}{migrationID, limit}
	} else {
		switch l.db.Driver() {
		case "mysql":
			query = fmt.Sprintf(`
				SELECT log_id, migration_id, action, sql_statements, result, error_message, logged_at
				FROM %s
				ORDER BY logged_at DESC
				LIMIT ?
			`, l.tableName)
		case "postgres":
			query = fmt.Sprintf(`
				SELECT log_id, migration_id, action, sql_statements, result, error_message, logged_at
				FROM %s
				ORDER BY logged_at DESC
				LIMIT $1
			`, l.tableName)
		}
		args = []interface{}{limit}
	}

	rows, err := l.db.Query(query, args...)
	if err != nil {
		return nil, fmt.Errorf("failed to query logs: %w", err)
	}
	defer rows.Close()

	logs := make([]*models.MigrationLog, 0)

	for rows.Next() {
		log := &models.MigrationLog{}
		var sqlStatementsStr string
		var loggedAt time.Time

		err := rows.Scan(
			&log.LogID,
			&log.MigrationID,
			&log.Action,
			&sqlStatementsStr,
			&log.Result,
			&log.ErrorMessage,
			&loggedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan log: %w", err)
		}

		log.LoggedAt = loggedAt

		var statements []string
		if err := json.Unmarshal([]byte(sqlStatementsStr), &statements); err == nil {
			log.SQLStatements = statements
		} else {
			log.SQLStatements = []string{}
		}

		logs = append(logs, log)
	}

	return logs, nil
}
