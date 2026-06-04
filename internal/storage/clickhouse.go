package storage

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	_ "github.com/ClickHouse/clickhouse-go"
	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
)

type ClickHouseStore struct {
	config *config.ClickHouseConfig
	db     *sql.DB
}

func NewClickHouseStore(cfg *config.ClickHouseConfig) (*ClickHouseStore, error) {
	store := &ClickHouseStore{config: cfg}
	if err := store.connect(); err != nil {
		return nil, err
	}
	if err := store.initSchema(); err != nil {
		return nil, err
	}
	return store, nil
}

func (ch *ClickHouseStore) connect() error {
	dsn := fmt.Sprintf("tcp://%s?database=%s&username=%s&password=%s",
		ch.config.Address,
		ch.config.Database,
		ch.config.Username,
		ch.config.Password,
	)

	db, err := sql.Open("clickhouse", dsn)
	if err != nil {
		return err
	}

	if err := db.Ping(); err != nil {
		return err
	}

	ch.db = db
	return nil
}

func (ch *ClickHouseStore) initSchema() error {
	schemas := []string{
		`CREATE DATABASE IF NOT EXISTS ` + ch.config.Database,
		`CREATE TABLE IF NOT EXISTS logs (
			id String,
			timestamp DateTime64(9),
			source String,
			host String,
			level String,
			message String,
			fields Map(String, String),
			raw String
		) ENGINE = MergeTree()
		PARTITION BY toDate(timestamp)
		ORDER BY (timestamp, host, level)
		TTL timestamp + INTERVAL 30 DAY`,
		`CREATE TABLE IF NOT EXISTS aggregates (
			window_id String,
			window_start DateTime64(9),
			window_end DateTime64(9),
			window_type String,
			key String,
			count Int64,
			level_counts Map(String, Int64),
			fields Map(String, String)
		) ENGINE = MergeTree()
		PARTITION BY toDate(window_start)
		ORDER BY (window_start, key)`,
		`CREATE TABLE IF NOT EXISTS anomalies (
			id String,
			timestamp DateTime64(9),
			metric_name String,
			anomaly_score Float64,
			is_anomaly Bool,
			method String,
			threshold Float64,
			value Float64,
			features Map(String, Float64)
		) ENGINE = MergeTree()
		PARTITION BY toDate(timestamp)
		ORDER BY (timestamp, metric_name)`,
		`CREATE TABLE IF NOT EXISTS alerts (
			id String,
			timestamp DateTime64(9),
			alert_type String,
			severity String,
			title String,
			description String,
			source_ip String,
			count Int64,
			details Map(String, String)
		) ENGINE = MergeTree()
		PARTITION BY toDate(timestamp)
		ORDER BY (timestamp, severity)`,
	}

	for _, schema := range schemas {
		if _, err := ch.db.Exec(schema); err != nil {
			return err
		}
	}

	return nil
}

func (ch *ClickHouseStore) InsertLog(ctx context.Context, log *models.LogEntry) error {
	query := `INSERT INTO logs (id, timestamp, source, host, level, message, fields, raw) 
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := ch.db.ExecContext(ctx, query,
		log.ID,
		log.Timestamp,
		log.Source,
		log.Host,
		log.Level,
		log.Message,
		log.Fields,
		log.Raw,
	)
	return err
}

func (ch *ClickHouseStore) InsertLogs(ctx context.Context, logs []*models.LogEntry) error {
	tx, err := ch.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	stmt, err := tx.Prepare(`INSERT INTO logs (id, timestamp, source, host, level, message, fields, raw) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, log := range logs {
		if _, err := stmt.Exec(
			log.ID,
			log.Timestamp,
			log.Source,
			log.Host,
			log.Level,
			log.Message,
			log.Fields,
			log.Raw,
		); err != nil {
			return err
		}
	}

	return tx.Commit()
}

func (ch *ClickHouseStore) InsertAggregate(ctx context.Context, agg *models.WindowAggregate) error {
	fieldsStr := make(map[string]string)
	for k, v := range agg.Fields {
		fieldsStr[k] = fmt.Sprintf("%v", v)
	}

	query := `INSERT INTO aggregates (window_id, window_start, window_end, window_type, key, count, level_counts, fields)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := ch.db.ExecContext(ctx, query,
		agg.WindowID,
		agg.WindowStart,
		agg.WindowEnd,
		agg.WindowType,
		agg.Key,
		agg.Count,
		agg.LevelCounts,
		fieldsStr,
	)
	return err
}

func (ch *ClickHouseStore) InsertAnomaly(ctx context.Context, anomaly *models.AnomalyResult) error {
	query := `INSERT INTO anomalies (id, timestamp, metric_name, anomaly_score, is_anomaly, method, threshold, value, features)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := ch.db.ExecContext(ctx, query,
		anomaly.ID,
		anomaly.Timestamp,
		anomaly.MetricName,
		anomaly.AnomalyScore,
		anomaly.IsAnomaly,
		anomaly.Method,
		anomaly.Threshold,
		anomaly.Value,
		anomaly.Features,
	)
	return err
}

func (ch *ClickHouseStore) InsertAlert(ctx context.Context, alert *models.AlertEvent) error {
	detailsStr := make(map[string]string)
	for k, v := range alert.Details {
		detailsStr[k] = fmt.Sprintf("%v", v)
	}

	query := `INSERT INTO alerts (id, timestamp, alert_type, severity, title, description, source_ip, count, details)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := ch.db.ExecContext(ctx, query,
		alert.ID,
		alert.Timestamp,
		alert.AlertType,
		alert.Severity,
		alert.Title,
		alert.Description,
		alert.SourceIP,
		alert.Count,
		detailsStr,
	)
	return err
}

func (ch *ClickHouseStore) QueryLogs(ctx context.Context, startTime, endTime time.Time, query string, limit int) ([]*models.LogEntry, error) {
	var whereClauses []string
	var args []interface{}

	whereClauses = append(whereClauses, "timestamp >= ? AND timestamp <= ?")
	args = append(args, startTime, endTime)

	if query != "" {
		whereClauses = append(whereClauses, "(message ILIKE ? OR raw ILIKE ?)")
		args = append(args, "%"+query+"%", "%"+query+"%")
	}

	whereSQL := strings.Join(whereClauses, " AND ")
	sqlQuery := fmt.Sprintf(`SELECT id, timestamp, source, host, level, message, fields, raw 
		FROM logs WHERE %s ORDER BY timestamp DESC LIMIT ?`, whereSQL)
	args = append(args, limit)

	rows, err := ch.db.QueryContext(ctx, sqlQuery, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var logs []*models.LogEntry
	for rows.Next() {
		log := &models.LogEntry{}
		err := rows.Scan(
			&log.ID,
			&log.Timestamp,
			&log.Source,
			&log.Host,
			&log.Level,
			&log.Message,
			&log.Fields,
			&log.Raw,
		)
		if err != nil {
			return nil, err
		}
		logs = append(logs, log)
	}

	return logs, nil
}

func (ch *ClickHouseStore) QueryAggregate(ctx context.Context, sql string, args ...interface{}) (*sql.Rows, error) {
	return ch.db.QueryContext(ctx, sql, args...)
}

func (ch *ClickHouseStore) Close() error {
	return ch.db.Close()
}
