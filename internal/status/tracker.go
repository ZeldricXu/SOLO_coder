package status

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"dbmigrator/internal/database"
	"dbmigrator/pkg/models"
)

type Tracker struct {
	db        database.IDBConnection
	tableName string
}

func NewTracker(db database.IDBConnection, tableName string) (*Tracker, error) {
	t := &Tracker{
		db:        db,
		tableName: tableName,
	}

	if err := t.ensureTable(); err != nil {
		return nil, err
	}

	return t, nil
}

func (t *Tracker) ensureTable() error {
	return t.db.EnsureMigrationTable(t.tableName)
}

func (t *Tracker) RecordMigration(migration *models.Migration, result *models.ExecutionResult) error {
	statusID := fmt.Sprintf("status_%s_%d", migration.MigrationID, time.Now().UnixNano())

	var affectedObjectsJSON []byte
	if len(result.MigrationID) > 0 {
		affectedObjectsJSON, _ = json.Marshal([]string{})
	}

	status := models.MigrationStatusExecuted
	if !result.Success {
		status = models.MigrationStatusFailed
	}

	var query string
	var args []interface{}

	switch t.db.Driver() {
	case "mysql":
		query = fmt.Sprintf(`
			INSERT INTO %s (status_id, migration_id, status, executed_at, execution_time_ms, affected_objects, rollback_available)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE
				status = VALUES(status),
				executed_at = VALUES(executed_at),
				execution_time_ms = VALUES(execution_time_ms),
				affected_objects = VALUES(affected_objects)
		`, t.tableName)
		args = []interface{}{
			statusID,
			migration.MigrationID,
			status,
			time.Now(),
			result.ExecutionTime.Milliseconds(),
			string(affectedObjectsJSON),
			len(migration.DownScript) > 0,
		}

	case "postgres":
		query = fmt.Sprintf(`
			INSERT INTO %s (status_id, migration_id, status, executed_at, execution_time_ms, affected_objects, rollback_available)
			VALUES ($1, $2, $3, $4, $5, $6, $7)
			ON CONFLICT (migration_id) DO UPDATE SET
				status = EXCLUDED.status,
				executed_at = EXCLUDED.executed_at,
				execution_time_ms = EXCLUDED.execution_time_ms,
				affected_objects = EXCLUDED.affected_objects
		`, t.tableName)
		args = []interface{}{
			statusID,
			migration.MigrationID,
			string(status),
			time.Now(),
			result.ExecutionTime.Milliseconds(),
			string(affectedObjectsJSON),
			len(migration.DownScript) > 0,
		}
	}

	_, err := t.db.Exec(query, args...)
	if err != nil {
		return fmt.Errorf("failed to record migration status: %w", err)
	}

	return nil
}

func (t *Tracker) UpdateRollbackStatus(migrationID string) error {
	var query string
	var args []interface{}

	switch t.db.Driver() {
	case "mysql":
		query = fmt.Sprintf(`
			UPDATE %s 
			SET status = ?, executed_at = ?
			WHERE migration_id = ?
		`, t.tableName)
		args = []interface{}{
			string(models.MigrationStatusRolledBack),
			time.Now(),
			migrationID,
		}

	case "postgres":
		query = fmt.Sprintf(`
			UPDATE %s 
			SET status = $1, executed_at = $2
			WHERE migration_id = $3
		`, t.tableName)
		args = []interface{}{
			string(models.MigrationStatusRolledBack),
			time.Now(),
			migrationID,
		}
	}

	_, err := t.db.Exec(query, args...)
	if err != nil {
		return fmt.Errorf("failed to update rollback status: %w", err)
	}

	return nil
}

func (t *Tracker) GetExecutedMigrations() (map[string]bool, []*models.MigrationState, error) {
	query := fmt.Sprintf(`
		SELECT status_id, migration_id, status, executed_at, execution_time_ms, rollback_available
		FROM %s
		WHERE status = ?
		ORDER BY executed_at ASC
	`, t.tableName)

	var statusFilter string
	if t.db.Driver() == "postgres" {
		query = fmt.Sprintf(`
			SELECT status_id, migration_id, status, executed_at, execution_time_ms, rollback_available
			FROM %s
			WHERE status = $1
			ORDER BY executed_at ASC
		`, t.tableName)
		statusFilter = string(models.MigrationStatusExecuted)
	} else {
		statusFilter = string(models.MigrationStatusExecuted)
	}

	rows, err := t.db.Query(query, statusFilter)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to query executed migrations: %w", err)
	}
	defer rows.Close()

	executedIDs := make(map[string]bool)
	states := make([]*models.MigrationState, 0)

	for rows.Next() {
		state := &models.MigrationState{}
		var executedAt sql.NullTime
		var statusStr string

		err := rows.Scan(
			&state.StatusID,
			&state.MigrationID,
			&statusStr,
			&executedAt,
			&state.ExecutionTimeMS,
			&state.RollbackAvailable,
		)
		if err != nil {
			return nil, nil, fmt.Errorf("failed to scan migration state: %w", err)
		}

		state.Status = models.MigrationStatus(statusStr)
		if executedAt.Valid {
			state.ExecutedAt = executedAt.Time
		}

		executedIDs[state.MigrationID] = true
		states = append(states, state)
	}

	return executedIDs, states, nil
}

func (t *Tracker) GetLatestExecutedMigrations(limit int) ([]*models.MigrationState, error) {
	query := fmt.Sprintf(`
		SELECT status_id, migration_id, status, executed_at, execution_time_ms, rollback_available
		FROM %s
		WHERE status = ?
		ORDER BY executed_at DESC
	`, t.tableName)

	var statusFilter string
	if t.db.Driver() == "postgres" {
		query = fmt.Sprintf(`
			SELECT status_id, migration_id, status, executed_at, execution_time_ms, rollback_available
			FROM %s
			WHERE status = $1
			ORDER BY executed_at DESC
			LIMIT %d
		`, t.tableName, limit)
		statusFilter = string(models.MigrationStatusExecuted)
	} else {
		query = fmt.Sprintf(`
			SELECT status_id, migration_id, status, executed_at, execution_time_ms, rollback_available
			FROM %s
			WHERE status = ?
			ORDER BY executed_at DESC
			LIMIT %d
		`, t.tableName, limit)
		statusFilter = string(models.MigrationStatusExecuted)
	}

	rows, err := t.db.Query(query, statusFilter)
	if err != nil {
		return nil, fmt.Errorf("failed to query latest migrations: %w", err)
	}
	defer rows.Close()

	states := make([]*models.MigrationState, 0)

	for rows.Next() {
		state := &models.MigrationState{}
		var executedAt sql.NullTime
		var statusStr string

		err := rows.Scan(
			&state.StatusID,
			&state.MigrationID,
			&statusStr,
			&executedAt,
			&state.ExecutionTimeMS,
			&state.RollbackAvailable,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan migration state: %w", err)
		}

		state.Status = models.MigrationStatus(statusStr)
		if executedAt.Valid {
			state.ExecutedAt = executedAt.Time
		}

		states = append(states, state)
	}

	return states, nil
}

func (t *Tracker) GetAllStatuses() ([]*models.MigrationState, error) {
	query := fmt.Sprintf(`
		SELECT status_id, migration_id, status, executed_at, execution_time_ms, rollback_available
		FROM %s
		ORDER BY executed_at ASC
	`, t.tableName)

	rows, err := t.db.Query(query)
	if err != nil {
		return nil, fmt.Errorf("failed to query all statuses: %w", err)
	}
	defer rows.Close()

	states := make([]*models.MigrationState, 0)

	for rows.Next() {
		state := &models.MigrationState{}
		var executedAt sql.NullTime
		var statusStr string

		err := rows.Scan(
			&state.StatusID,
			&state.MigrationID,
			&statusStr,
			&executedAt,
			&state.ExecutionTimeMS,
			&state.RollbackAvailable,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan migration state: %w", err)
		}

		state.Status = models.MigrationStatus(statusStr)
		if executedAt.Valid {
			state.ExecutedAt = executedAt.Time
		}

		states = append(states, state)
	}

	return states, nil
}

func (t *Tracker) IsMigrationExecuted(migrationID string) (bool, error) {
	query := fmt.Sprintf(`
		SELECT COUNT(*) FROM %s 
		WHERE migration_id = ? AND status = ?
	`, t.tableName)

	if t.db.Driver() == "postgres" {
		query = fmt.Sprintf(`
			SELECT COUNT(*) FROM %s 
			WHERE migration_id = $1 AND status = $2
		`, t.tableName)
	}

	var count int
	err := t.db.QueryRow(query, migrationID, string(models.MigrationStatusExecuted)).Scan(&count)
	if err != nil {
		return false, fmt.Errorf("failed to check migration status: %w", err)
	}

	return count > 0, nil
}
