package postgres

import (
	"context"
	"fmt"
	"strings"
	"time"

	"DF1-56/internal/models"
)

type AuditLogFilter struct {
	RequestID  string
	TraceID    string
	UserID     string
	RouteID    string
	ClientIP   string
	StatusCode *int
	StartTime  *time.Time
	EndTime    *time.Time
	Page       int
	PageSize   int
}

func (p *PostgresClient) CreateAuditLog(ctx context.Context, log *models.AuditLog) error {
	if log == nil {
		return fmt.Errorf("audit log cannot be nil")
	}
	if log.ID == "" {
		return fmt.Errorf("audit log id is required")
	}
	if log.RequestID == "" {
		return fmt.Errorf("request id is required")
	}
	if log.ClientIP == "" {
		return fmt.Errorf("client ip is required")
	}
	if log.Method == "" {
		return fmt.Errorf("method is required")
	}
	if log.Path == "" {
		return fmt.Errorf("path is required")
	}

	query := `
		INSERT INTO audit_logs (
			id, timestamp, request_id, trace_id, user_id, api_key,
			client_ip, method, path, route_id, upstream, status_code,
			duration_ms, error, rate_limited, circuit_broken, gray_version
		) VALUES (
			$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17
		)
	`

	timestamp := log.Timestamp
	if timestamp.IsZero() {
		timestamp = time.Now()
	}

	p.mu.RLock()
	defer p.mu.RUnlock()

	_, err := p.pool.Exec(ctx, query,
		log.ID,
		timestamp,
		log.RequestID,
		log.TraceID,
		log.UserID,
		log.APIKey,
		log.ClientIP,
		log.Method,
		log.Path,
		log.RouteID,
		log.Upstream,
		log.StatusCode,
		log.Duration,
		log.Error,
		log.RateLimited,
		log.CircuitBroken,
		log.GrayVersion,
	)
	if err != nil {
		return fmt.Errorf("failed to create audit log: %w", err)
	}
	return nil
}

func (p *PostgresClient) GetAuditLogs(ctx context.Context, filter AuditLogFilter) ([]*models.AuditLog, int64, error) {
	var conditions []string
	var args []interface{}
	argIndex := 1

	if filter.RequestID != "" {
		conditions = append(conditions, fmt.Sprintf("request_id = $%d", argIndex))
		args = append(args, filter.RequestID)
		argIndex++
	}
	if filter.TraceID != "" {
		conditions = append(conditions, fmt.Sprintf("trace_id = $%d", argIndex))
		args = append(args, filter.TraceID)
		argIndex++
	}
	if filter.UserID != "" {
		conditions = append(conditions, fmt.Sprintf("user_id = $%d", argIndex))
		args = append(args, filter.UserID)
		argIndex++
	}
	if filter.RouteID != "" {
		conditions = append(conditions, fmt.Sprintf("route_id = $%d", argIndex))
		args = append(args, filter.RouteID)
		argIndex++
	}
	if filter.ClientIP != "" {
		conditions = append(conditions, fmt.Sprintf("client_ip = $%d", argIndex))
		args = append(args, filter.ClientIP)
		argIndex++
	}
	if filter.StatusCode != nil {
		conditions = append(conditions, fmt.Sprintf("status_code = $%d", argIndex))
		args = append(args, *filter.StatusCode)
		argIndex++
	}
	if filter.StartTime != nil {
		conditions = append(conditions, fmt.Sprintf("timestamp >= $%d", argIndex))
		args = append(args, *filter.StartTime)
		argIndex++
	}
	if filter.EndTime != nil {
		conditions = append(conditions, fmt.Sprintf("timestamp <= $%d", argIndex))
		args = append(args, *filter.EndTime)
		argIndex++
	}

	whereClause := ""
	if len(conditions) > 0 {
		whereClause = " WHERE " + strings.Join(conditions, " AND ")
	}

	countQuery := "SELECT COUNT(*) FROM audit_logs" + whereClause
	query := `
		SELECT id, timestamp, request_id, trace_id, user_id, api_key,
		       client_ip, method, path, route_id, upstream, status_code,
		       duration_ms, error, rate_limited, circuit_broken, gray_version
		FROM audit_logs
	` + whereClause + `
		ORDER BY timestamp DESC
	`

	page := filter.Page
	if page <= 0 {
		page = 1
	}
	pageSize := filter.PageSize
	if pageSize <= 0 {
		pageSize = 20
	}
	query += fmt.Sprintf(" LIMIT %d OFFSET %d", pageSize, (page-1)*pageSize)

	p.mu.RLock()
	defer p.mu.RUnlock()

	var total int64
	if err := p.pool.QueryRow(ctx, countQuery, args...).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("failed to count audit logs: %w", err)
	}

	rows, err := p.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to query audit logs: %w", err)
	}
	defer rows.Close()

	var logs []*models.AuditLog
	for rows.Next() {
		log := &models.AuditLog{}
		err := rows.Scan(
			&log.ID,
			&log.Timestamp,
			&log.RequestID,
			&log.TraceID,
			&log.UserID,
			&log.APIKey,
			&log.ClientIP,
			&log.Method,
			&log.Path,
			&log.RouteID,
			&log.Upstream,
			&log.StatusCode,
			&log.Duration,
			&log.Error,
			&log.RateLimited,
			&log.CircuitBroken,
			&log.GrayVersion,
		)
		if err != nil {
			return nil, 0, fmt.Errorf("failed to scan audit log: %w", err)
		}
		logs = append(logs, log)
	}

	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("rows iteration error: %w", err)
	}

	return logs, total, nil
}

func (p *PostgresClient) GetAuditLogByID(ctx context.Context, id string) (*models.AuditLog, error) {
	if id == "" {
		return nil, fmt.Errorf("audit log id is required")
	}

	query := `
		SELECT id, timestamp, request_id, trace_id, user_id, api_key,
		       client_ip, method, path, route_id, upstream, status_code,
		       duration_ms, error, rate_limited, circuit_broken, gray_version
		FROM audit_logs
		WHERE id = $1
	`

	p.mu.RLock()
	defer p.mu.RUnlock()

	log := &models.AuditLog{}
	err := p.pool.QueryRow(ctx, query, id).Scan(
		&log.ID,
		&log.Timestamp,
		&log.RequestID,
		&log.TraceID,
		&log.UserID,
		&log.APIKey,
		&log.ClientIP,
		&log.Method,
		&log.Path,
		&log.RouteID,
		&log.Upstream,
		&log.StatusCode,
		&log.Duration,
		&log.Error,
		&log.RateLimited,
		&log.CircuitBroken,
		&log.GrayVersion,
	)
	if err != nil {
		if err.Error() == "no rows in result set" {
			return nil, nil
		}
		return nil, fmt.Errorf("failed to get audit log: %w", err)
	}

	return log, nil
}
