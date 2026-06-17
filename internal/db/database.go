package db

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"

	"github.com/multicloud/cli/internal/common"
)

type Database struct {
	db   *sql.DB
	path string
}

func NewDatabase(path string) (*Database, error) {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to resolve database path", err)
	}

	if err := common.EnsureDir(absPath); err != nil {
		return nil, err
	}

	db, err := sql.Open("sqlite", absPath)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to open database", err)
	}

	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	database := &Database{
		db:   db,
		path: absPath,
	}

	if err := database.initSchema(); err != nil {
		return nil, err
	}

	return database, nil
}

func (d *Database) initSchema() error {
	schema := `
	CREATE TABLE IF NOT EXISTS resources (
		id TEXT PRIMARY KEY,
		name TEXT NOT NULL,
		type TEXT NOT NULL,
		provider TEXT NOT NULL,
		region TEXT NOT NULL,
		properties BLOB,
		tags BLOB,
		status TEXT NOT NULL,
		created_at TIMESTAMP NOT NULL,
		updated_at TIMESTAMP NOT NULL,
		metadata BLOB
	);

	CREATE INDEX IF NOT EXISTS idx_resources_name ON resources(name);
	CREATE INDEX IF NOT EXISTS idx_resources_provider ON resources(provider);
	CREATE INDEX IF NOT EXISTS idx_resources_type ON resources(type);
	CREATE INDEX IF NOT EXISTS idx_resources_status ON resources(status);

	CREATE TABLE IF NOT EXISTS audit_logs (
		id TEXT PRIMARY KEY,
		user TEXT NOT NULL,
		action TEXT NOT NULL,
		resource TEXT NOT NULL,
		provider TEXT NOT NULL,
		status TEXT NOT NULL,
		message TEXT,
		metadata BLOB,
		timestamp TIMESTAMP NOT NULL
	);

	CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
	CREATE INDEX IF NOT EXISTS idx_audit_logs_provider ON audit_logs(provider);
	CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs(timestamp);

	CREATE TABLE IF NOT EXISTS state_cache (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		serial INTEGER NOT NULL,
		lineage TEXT NOT NULL,
		state_data BLOB NOT NULL,
		created_at TIMESTAMP NOT NULL
	);

	CREATE INDEX IF NOT EXISTS idx_state_cache_serial ON state_cache(serial);
	CREATE INDEX IF NOT EXISTS idx_state_cache_lineage ON state_cache(lineage);
	`

	_, err := d.db.Exec(schema)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to initialize database schema", err)
	}

	return nil
}

func (d *Database) Close() error {
	return d.db.Close()
}

func (d *Database) Path() string {
	return d.path
}

func (d *Database) SaveResource(r *common.Resource) error {
	propertiesJSON, _ := json.Marshal(r.Properties)
	tagsJSON, _ := json.Marshal(r.Tags)
	metadataJSON, _ := json.Marshal(r.Metadata)

	query := `
	INSERT OR REPLACE INTO resources (
		id, name, type, provider, region, properties, tags, status, created_at, updated_at, metadata
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := d.db.Exec(query,
		r.ID, r.Name, r.Type, r.Provider, r.Region,
		propertiesJSON, tagsJSON, r.Status,
		r.CreatedAt, r.UpdatedAt, metadataJSON,
	)

	if err != nil {
		return common.NewError(common.ErrOperationFailed, fmt.Sprintf("failed to save resource %s", r.ID), err)
	}

	return nil
}

func (d *Database) GetResource(id string) (*common.Resource, error) {
	query := `
	SELECT id, name, type, provider, region, properties, tags, status, created_at, updated_at, metadata
	FROM resources WHERE id = ?`

	var r common.Resource
	var propertiesJSON, tagsJSON, metadataJSON []byte

	err := d.db.QueryRow(query, id).Scan(
		&r.ID, &r.Name, &r.Type, &r.Provider, &r.Region,
		&propertiesJSON, &tagsJSON, &r.Status,
		&r.CreatedAt, &r.UpdatedAt, &metadataJSON,
	)

	if err == sql.ErrNoRows {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("resource %s not found", id))
	}
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, fmt.Sprintf("failed to get resource %s", id), err)
	}

	if len(propertiesJSON) > 0 {
		json.Unmarshal(propertiesJSON, &r.Properties)
	}
	if len(tagsJSON) > 0 {
		json.Unmarshal(tagsJSON, &r.Tags)
	}
	if len(metadataJSON) > 0 {
		json.Unmarshal(metadataJSON, &r.Metadata)
	}

	if r.Properties == nil {
		r.Properties = make(map[string]interface{})
	}
	if r.Tags == nil {
		r.Tags = make(map[string]string)
	}

	return &r, nil
}

func (d *Database) DeleteResource(id string) error {
	query := `DELETE FROM resources WHERE id = ?`
	_, err := d.db.Exec(query, id)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, fmt.Sprintf("failed to delete resource %s", id), err)
	}
	return nil
}

func (d *Database) ListResources(provider common.CloudProvider, resourceType common.ResourceType) ([]*common.Resource, error) {
	query := `
	SELECT id, name, type, provider, region, properties, tags, status, created_at, updated_at, metadata
	FROM resources WHERE 1=1`

	var args []interface{}

	if provider != "" {
		query += " AND provider = ?"
		args = append(args, provider)
	}

	if resourceType != "" {
		query += " AND type = ?"
		args = append(args, resourceType)
	}

	query += " ORDER BY created_at DESC"

	rows, err := d.db.Query(query, args...)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to list resources", err)
	}
	defer rows.Close()

	var resources []*common.Resource

	for rows.Next() {
		var r common.Resource
		var propertiesJSON, tagsJSON, metadataJSON []byte

		err := rows.Scan(
			&r.ID, &r.Name, &r.Type, &r.Provider, &r.Region,
			&propertiesJSON, &tagsJSON, &r.Status,
			&r.CreatedAt, &r.UpdatedAt, &metadataJSON,
		)
		if err != nil {
			return nil, common.NewError(common.ErrOperationFailed, "failed to scan resource", err)
		}

		if len(propertiesJSON) > 0 {
			json.Unmarshal(propertiesJSON, &r.Properties)
		}
		if len(tagsJSON) > 0 {
			json.Unmarshal(tagsJSON, &r.Tags)
		}
		if len(metadataJSON) > 0 {
			json.Unmarshal(metadataJSON, &r.Metadata)
		}

		if r.Properties == nil {
			r.Properties = make(map[string]interface{})
		}
		if r.Tags == nil {
			r.Tags = make(map[string]string)
		}

		resources = append(resources, &r)
	}

	return resources, nil
}

func (d *Database) AddAuditLog(log *common.AuditLog) error {
	metadataJSON, _ := json.Marshal(log.Metadata)

	if log.ID == "" {
		log.ID = common.GenerateID("audit")
	}
	if log.Timestamp.IsZero() {
		log.Timestamp = time.Now()
	}

	query := `
	INSERT INTO audit_logs (
		id, user, action, resource, provider, status, message, metadata, timestamp
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := d.db.Exec(query,
		log.ID, log.User, log.Action, log.Resource, log.Provider,
		log.Status, log.Message, metadataJSON, log.Timestamp,
	)

	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to add audit log", err)
	}

	return nil
}

func (d *Database) ListAuditLogs(limit int, action string, provider common.CloudProvider) ([]*common.AuditLog, error) {
	query := `
	SELECT id, user, action, resource, provider, status, message, metadata, timestamp
	FROM audit_logs WHERE 1=1`

	var args []interface{}

	if action != "" {
		query += " AND action = ?"
		args = append(args, action)
	}

	if provider != "" {
		query += " AND provider = ?"
		args = append(args, provider)
	}

	query += " ORDER BY timestamp DESC"

	if limit > 0 {
		query += " LIMIT ?"
		args = append(args, limit)
	}

	rows, err := d.db.Query(query, args...)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to list audit logs", err)
	}
	defer rows.Close()

	var logs []*common.AuditLog

	for rows.Next() {
		var log common.AuditLog
		var metadataJSON []byte

		err := rows.Scan(
			&log.ID, &log.User, &log.Action, &log.Resource, &log.Provider,
			&log.Status, &log.Message, &metadataJSON, &log.Timestamp,
		)
		if err != nil {
			return nil, common.NewError(common.ErrOperationFailed, "failed to scan audit log", err)
		}

		if len(metadataJSON) > 0 {
			json.Unmarshal(metadataJSON, &log.Metadata)
		}

		logs = append(logs, &log)
	}

	return logs, nil
}

func (d *Database) SaveStateCache(serial int, lineage string, stateData []byte) error {
	query := `
	INSERT INTO state_cache (serial, lineage, state_data, created_at)
	VALUES (?, ?, ?, ?)`

	_, err := d.db.Exec(query, serial, lineage, stateData, time.Now())
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to save state cache", err)
	}

	return nil
}

func (d *Database) GetLatestStateCache(lineage string) ([]byte, error) {
	query := `
	SELECT state_data FROM state_cache
	WHERE lineage = ?
	ORDER BY serial DESC LIMIT 1`

	var stateData []byte
	err := d.db.QueryRow(query, lineage).Scan(&stateData)
	if err == sql.ErrNoRows {
		return nil, common.NewError(common.ErrNotFound, "no state cache found")
	}
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to get state cache", err)
	}

	return stateData, nil
}

func (d *Database) GetStateCacheBySerial(serial int, lineage string) ([]byte, error) {
	query := `
	SELECT state_data FROM state_cache
	WHERE serial = ? AND lineage = ?`

	var stateData []byte
	err := d.db.QueryRow(query, serial, lineage).Scan(&stateData)
	if err == sql.ErrNoRows {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("state cache serial %d not found", serial))
	}
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to get state cache", err)
	}

	return stateData, nil
}

func (d *Database) CleanupOldStateCache(keepVersions int) error {
	query := `
	DELETE FROM state_cache
	WHERE id NOT IN (
		SELECT id FROM state_cache
		ORDER BY serial DESC
		LIMIT ?
	)`

	_, err := d.db.Exec(query, keepVersions)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to cleanup old state cache", err)
	}

	return nil
}

func (d *Database) GetStats() (map[string]interface{}, error) {
	stats := make(map[string]interface{})

	query := `SELECT COUNT(*) FROM resources`
	var count int
	d.db.QueryRow(query).Scan(&count)
	stats["resources"] = count

	query = `SELECT COUNT(*) FROM audit_logs`
	d.db.QueryRow(query).Scan(&count)
	stats["audit_logs"] = count

	query = `SELECT COUNT(*) FROM state_cache`
	d.db.QueryRow(query).Scan(&count)
	stats["state_versions"] = count

	query = `SELECT provider, COUNT(*) FROM resources GROUP BY provider`
	rows, err := d.db.Query(query)
	if err == nil {
		defer rows.Close()
		providerCounts := make(map[string]int)
		for rows.Next() {
			var provider string
			var count int
			rows.Scan(&provider, &count)
			providerCounts[provider] = count
		}
		stats["resources_by_provider"] = providerCounts
	}

	return stats, nil
}

func (d *Database) Vacuum() error {
	_, err := d.db.Exec("VACUUM")
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to vacuum database", err)
	}
	return nil
}

func (d *Database) Backup(backupPath string) error {
	if backupPath == "" {
		backupPath = d.path + ".backup-" + time.Now().Format("20060102-150405")
	}

	sourceData, err := os.ReadFile(d.path)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to read source database", err)
	}

	if err := os.WriteFile(backupPath, sourceData, 0644); err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to write backup", err)
	}

	return nil
}

func (d *Database) Begin() (*sql.Tx, error) {
	return d.db.Begin()
}

func (d *Database) DB() *sql.DB {
	return d.db
}
