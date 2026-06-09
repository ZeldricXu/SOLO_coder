package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"DF1-56/internal/models"
)

type RouteFilter struct {
	Path       string
	Method     string
	MatchType  string
	Enabled    *bool
	Page       int
	PageSize   int
}

func (p *PostgresClient) CreateRoute(ctx context.Context, route *models.Route) error {
	if route == nil {
		return fmt.Errorf("route cannot be nil")
	}
	if route.ID == "" {
		return fmt.Errorf("route id is required")
	}
	if route.Path == "" {
		return fmt.Errorf("path is required")
	}
	if route.Method == "" {
		return fmt.Errorf("method is required")
	}
	if route.UpstreamURL == "" {
		return fmt.Errorf("upstream url is required")
	}

	matchType := route.MatchType
	if matchType == "" {
		matchType = models.RouteMatchTypePrefix
	}

	protocol := route.Protocol
	if protocol == "" {
		protocol = models.ProtocolHTTP
	}

	middlewaresJSON, err := json.Marshal(route.Middlewares)
	if err != nil {
		return fmt.Errorf("failed to marshal middlewares: %w", err)
	}

	headersJSON, err := json.Marshal(route.Headers)
	if err != nil {
		return fmt.Errorf("failed to marshal headers: %w", err)
	}

	query := `
		INSERT INTO routes (
			id, path, method, match_type, regex_pattern, upstream_url,
			upstream_cluster, rewrite_path, protocol, timeout, retry_count,
			middlewares, rate_limit_policy, auth_policy, circuit_breaker,
			gray_policy, mirror_policy, headers, enabled, created_at, updated_at
		) VALUES (
			$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21
		)
	`

	now := time.Now()
	createdAt := route.CreatedAt
	if createdAt.IsZero() {
		createdAt = now
	}
	updatedAt := route.UpdatedAt
	if updatedAt.IsZero() {
		updatedAt = now
	}

	p.mu.RLock()
	defer p.mu.RUnlock()

	_, err = p.pool.Exec(ctx, query,
		route.ID,
		route.Path,
		route.Method,
		string(matchType),
		route.RegexPattern,
		route.UpstreamURL,
		route.UpstreamCluster,
		route.RewritePath,
		string(protocol),
		route.Timeout.Milliseconds(),
		route.RetryCount,
		middlewaresJSON,
		route.RateLimitPolicy,
		route.AuthPolicy,
		route.CircuitBreaker,
		route.GrayPolicy,
		route.MirrorPolicy,
		headersJSON,
		route.Enabled,
		createdAt,
		updatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to create route: %w", err)
	}
	return nil
}

func (p *PostgresClient) UpdateRoute(ctx context.Context, route *models.Route) error {
	if route == nil {
		return fmt.Errorf("route cannot be nil")
	}
	if route.ID == "" {
		return fmt.Errorf("route id is required")
	}

	middlewaresJSON, err := json.Marshal(route.Middlewares)
	if err != nil {
		return fmt.Errorf("failed to marshal middlewares: %w", err)
	}

	headersJSON, err := json.Marshal(route.Headers)
	if err != nil {
		return fmt.Errorf("failed to marshal headers: %w", err)
	}

	query := `
		UPDATE routes SET
			path = $1,
			method = $2,
			match_type = $3,
			regex_pattern = $4,
			upstream_url = $5,
			upstream_cluster = $6,
			rewrite_path = $7,
			protocol = $8,
			timeout = $9,
			retry_count = $10,
			middlewares = $11,
			rate_limit_policy = $12,
			auth_policy = $13,
			circuit_breaker = $14,
			gray_policy = $15,
			mirror_policy = $16,
			headers = $17,
			enabled = $18,
			updated_at = $19
		WHERE id = $20
	`

	p.mu.RLock()
	defer p.mu.RUnlock()

	_, err = p.pool.Exec(ctx, query,
		route.Path,
		route.Method,
		string(route.MatchType),
		route.RegexPattern,
		route.UpstreamURL,
		route.UpstreamCluster,
		route.RewritePath,
		string(route.Protocol),
		route.Timeout.Milliseconds(),
		route.RetryCount,
		middlewaresJSON,
		route.RateLimitPolicy,
		route.AuthPolicy,
		route.CircuitBreaker,
		route.GrayPolicy,
		route.MirrorPolicy,
		headersJSON,
		route.Enabled,
		time.Now(),
		route.ID,
	)
	if err != nil {
		return fmt.Errorf("failed to update route: %w", err)
	}
	return nil
}

func (p *PostgresClient) DeleteRoute(ctx context.Context, id string) error {
	if id == "" {
		return fmt.Errorf("route id is required")
	}

	query := "DELETE FROM routes WHERE id = $1"

	p.mu.RLock()
	defer p.mu.RUnlock()

	result, err := p.pool.Exec(ctx, query, id)
	if err != nil {
		return fmt.Errorf("failed to delete route: %w", err)
	}

	rowsAffected := result.RowsAffected()
	if rowsAffected == 0 {
		return fmt.Errorf("route not found")
	}
	return nil
}

func (p *PostgresClient) GetRoute(ctx context.Context, id string) (*models.Route, error) {
	if id == "" {
		return nil, fmt.Errorf("route id is required")
	}

	query := `
		SELECT id, path, method, match_type, regex_pattern, upstream_url,
		       upstream_cluster, rewrite_path, protocol, timeout, retry_count,
		       middlewares, rate_limit_policy, auth_policy, circuit_breaker,
		       gray_policy, mirror_policy, headers, enabled, created_at, updated_at
		FROM routes
		WHERE id = $1
	`

	p.mu.RLock()
	defer p.mu.RUnlock()

	route := &models.Route{}
	var matchType, protocol string
	var timeoutMs int64
	var middlewaresJSON, headersJSON []byte

	err := p.pool.QueryRow(ctx, query, id).Scan(
		&route.ID,
		&route.Path,
		&route.Method,
		&matchType,
		&route.RegexPattern,
		&route.UpstreamURL,
		&route.UpstreamCluster,
		&route.RewritePath,
		&protocol,
		&timeoutMs,
		&route.RetryCount,
		&middlewaresJSON,
		&route.RateLimitPolicy,
		&route.AuthPolicy,
		&route.CircuitBreaker,
		&route.GrayPolicy,
		&route.MirrorPolicy,
		&headersJSON,
		&route.Enabled,
		&route.CreatedAt,
		&route.UpdatedAt,
	)
	if err != nil {
		if err.Error() == "no rows in result set" {
			return nil, nil
		}
		return nil, fmt.Errorf("failed to get route: %w", err)
	}

	route.MatchType = models.RouteMatchType(matchType)
	route.Protocol = models.ProtocolType(protocol)
	route.Timeout = time.Duration(timeoutMs) * time.Millisecond

	if len(middlewaresJSON) > 0 {
		if err := json.Unmarshal(middlewaresJSON, &route.Middlewares); err != nil {
			return nil, fmt.Errorf("failed to unmarshal middlewares: %w", err)
		}
	}

	if len(headersJSON) > 0 {
		if err := json.Unmarshal(headersJSON, &route.Headers); err != nil {
			return nil, fmt.Errorf("failed to unmarshal headers: %w", err)
		}
	}

	return route, nil
}

func (p *PostgresClient) ListRoutes(ctx context.Context, filter RouteFilter) ([]*models.Route, int64, error) {
	var conditions []string
	var args []interface{}
	argIndex := 1

	if filter.Path != "" {
		conditions = append(conditions, fmt.Sprintf("path LIKE $%d", argIndex))
		args = append(args, "%"+filter.Path+"%")
		argIndex++
	}
	if filter.Method != "" {
		conditions = append(conditions, fmt.Sprintf("method = $%d", argIndex))
		args = append(args, filter.Method)
		argIndex++
	}
	if filter.MatchType != "" {
		conditions = append(conditions, fmt.Sprintf("match_type = $%d", argIndex))
		args = append(args, filter.MatchType)
		argIndex++
	}
	if filter.Enabled != nil {
		conditions = append(conditions, fmt.Sprintf("enabled = $%d", argIndex))
		args = append(args, *filter.Enabled)
		argIndex++
	}

	whereClause := ""
	if len(conditions) > 0 {
		whereClause = " WHERE " + strings.Join(conditions, " AND ")
	}

	countQuery := "SELECT COUNT(*) FROM routes" + whereClause
	query := `
		SELECT id, path, method, match_type, regex_pattern, upstream_url,
		       upstream_cluster, rewrite_path, protocol, timeout, retry_count,
		       middlewares, rate_limit_policy, auth_policy, circuit_breaker,
		       gray_policy, mirror_policy, headers, enabled, created_at, updated_at
		FROM routes
	` + whereClause + `
		ORDER BY created_at DESC
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
		return nil, 0, fmt.Errorf("failed to count routes: %w", err)
	}

	rows, err := p.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, 0, fmt.Errorf("failed to query routes: %w", err)
	}
	defer rows.Close()

	var routes []*models.Route
	for rows.Next() {
		route := &models.Route{}
		var matchType, protocol string
		var timeoutMs int64
		var middlewaresJSON, headersJSON []byte

		err := rows.Scan(
			&route.ID,
			&route.Path,
			&route.Method,
			&matchType,
			&route.RegexPattern,
			&route.UpstreamURL,
			&route.UpstreamCluster,
			&route.RewritePath,
			&protocol,
			&timeoutMs,
			&route.RetryCount,
			&middlewaresJSON,
			&route.RateLimitPolicy,
			&route.AuthPolicy,
			&route.CircuitBreaker,
			&route.GrayPolicy,
			&route.MirrorPolicy,
			&headersJSON,
			&route.Enabled,
			&route.CreatedAt,
			&route.UpdatedAt,
		)
		if err != nil {
			return nil, 0, fmt.Errorf("failed to scan route: %w", err)
		}

		route.MatchType = models.RouteMatchType(matchType)
		route.Protocol = models.ProtocolType(protocol)
		route.Timeout = time.Duration(timeoutMs) * time.Millisecond

		if len(middlewaresJSON) > 0 {
			if err := json.Unmarshal(middlewaresJSON, &route.Middlewares); err != nil {
				return nil, 0, fmt.Errorf("failed to unmarshal middlewares: %w", err)
			}
		}

		if len(headersJSON) > 0 {
			if err := json.Unmarshal(headersJSON, &route.Headers); err != nil {
				return nil, 0, fmt.Errorf("failed to unmarshal headers: %w", err)
			}
		}

		routes = append(routes, route)
	}

	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("rows iteration error: %w", err)
	}

	return routes, total, nil
}
