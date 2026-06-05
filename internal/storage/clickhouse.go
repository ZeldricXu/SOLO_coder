package storage

import (
	"context"
	"fmt"
	"time"

	"github.com/ClickHouse/clickhouse-go/v2"
	"github.com/ClickHouse/clickhouse-go/v2/lib/driver"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type ClickHouseClient struct {
	conn   driver.Conn
	cfg    config.ClickHouseConfig
}

func NewClickHouseClient(cfg config.ClickHouseConfig) (*ClickHouseClient, error) {
	addr := make([]string, len(cfg.Addresses))
	for i, a := range cfg.Addresses {
		addr[i] = a
	}

	conn, err := clickhouse.Open(&clickhouse.Options{
		Addr: addr,
		Auth: clickhouse.Auth{
			Database: cfg.Database,
			Username: cfg.Username,
			Password: cfg.Password,
		},
		DialTimeout:  time.Duration(cfg.DialTimeout) * time.Second,
		ReadTimeout:  time.Duration(cfg.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(cfg.WriteTimeout) * time.Second,
		MaxOpenConns: cfg.MaxOpenConns,
		MaxIdleConns: cfg.MaxIdleConns,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to clickhouse: %w", err)
	}

	if err := conn.Ping(context.Background()); err != nil {
		return nil, fmt.Errorf("failed to ping clickhouse: %w", err)
	}

	client := &ClickHouseClient{
		conn: conn,
		cfg:  cfg,
	}

	if err := client.initSchema(); err != nil {
		return nil, fmt.Errorf("failed to init schema: %w", err)
	}

	return client, nil
}

func (c *ClickHouseClient) initSchema() error {
	ctx := context.Background()

	schemaSQL := `
	CREATE TABLE IF NOT EXISTS logs (
		id String,
		timestamp DateTime64(9, 'UTC'),
		received_at DateTime64(9, 'UTC'),
		source String,
		source_id String,
		service_name String,
		host String,
		level String,
		message String,
		raw_message String,
		trace_id String,
		span_id String,
		user_id String,
		client_ip String,
		geo_country String,
		geo_city String,
		geo_lat Float64,
		geo_lon Float64,
		status_code Int32,
		response_time_ms Int64,
		error_code String,
		error_description String,
		tags Array(String),
		parsed_fields Map(String, String),
		labels Map(String, String),
		original_index String
	) ENGINE = MergeTree()
	PARTITION BY toDate(timestamp)
	ORDER BY (timestamp, service_name, level)
	SETTINGS index_granularity = 8192
	`

	if err := c.conn.Exec(ctx, schemaSQL); err != nil {
		return fmt.Errorf("failed to create logs table: %w", err)
	}

	mvSQL := `
	CREATE MATERIALIZED VIEW IF NOT EXISTS logs_mv
	ENGINE = SummingMergeTree()
	PARTITION BY toDate(timestamp)
	ORDER BY (timestamp, service_name, level)
	AS SELECT
		toStartOfMinute(timestamp) as timestamp,
		service_name,
		level,
		count() as count
	FROM logs
	GROUP BY timestamp, service_name, level
	`

	if err := c.conn.Exec(ctx, mvSQL); err != nil {
		return fmt.Errorf("failed to create materialized view: %w", err)
	}

	return nil
}

func (c *ClickHouseClient) Insert(ctx context.Context, events []*models.LogEvent) error {
	if len(events) == 0 {
		return nil
	}

	batch, err := c.conn.PrepareBatch(ctx, "INSERT INTO logs")
	if err != nil {
		return fmt.Errorf("failed to prepare batch: %w", err)
	}

	for _, event := range events {
		parsedFields := make(map[string]string)
		for k, v := range event.ParsedFields {
			parsedFields[k] = fmt.Sprintf("%v", v)
		}

		var geoCountry, geoCity string
		var geoLat, geoLon float64
		if event.GeoLocation != nil {
			geoCountry = event.GeoLocation.Country
			geoCity = event.GeoLocation.City
			geoLat = event.GeoLocation.Latitude
			geoLon = event.GeoLocation.Longitude
		}

		err := batch.Append(
			event.ID,
			event.Timestamp,
			event.ReceivedAt,
			string(event.Source),
			event.SourceID,
			event.ServiceName,
			event.Host,
			string(event.Level),
			event.Message,
			event.RawMessage,
			event.TraceID,
			event.SpanID,
			event.UserID,
			event.ClientIP,
			geoCountry,
			geoCity,
			geoLat,
			geoLon,
			int32(event.StatusCode),
			event.ResponseTime,
			event.ErrorCode,
			event.ErrorDesc,
			event.Tags,
			parsedFields,
			event.Labels,
			event.OriginalIndex,
		)
		if err != nil {
			return fmt.Errorf("failed to append to batch: %w", err)
		}
	}

	return batch.Send()
}

func (c *ClickHouseClient) Query(ctx context.Context, req *models.LogQueryRequest) (*models.LogQueryResponse, error) {
	resp := &models.LogQueryResponse{
		Logs:         make([]*models.LogEvent, 0),
		TimeSeries:   make([]*models.TimeSeriesPoint, 0),
		Distribution: make([]*models.DistributionBucket, 0),
	}

	totalSQL := `SELECT count() FROM logs WHERE timestamp BETWEEN ? AND ?`
	args := []interface{}{req.StartTime, req.EndTime}
	conditions := ""

	if req.ServiceName != "" {
		conditions += " AND service_name = ?"
		args = append(args, req.ServiceName)
	}
	if req.Level != "" && req.Level != models.LevelUnknown {
		conditions += " AND level = ?"
		args = append(args, string(req.Level))
	}
	if req.TraceID != "" {
		conditions += " AND trace_id = ?"
		args = append(args, req.TraceID)
	}
	if req.ErrorCode != "" {
		conditions += " AND error_code = ?"
		args = append(args, req.ErrorCode)
	}
	if req.Keywords != "" {
		conditions += " AND (message LIKE ? OR raw_message LIKE ?)"
		kw := "%" + req.Keywords + "%"
		args = append(args, kw, kw)
	}

	totalSQL += conditions
	if err := c.conn.QueryRow(ctx, totalSQL, args...).Scan(&resp.Total); err != nil {
		return nil, fmt.Errorf("failed to get total count: %w", err)
	}

	offset := (req.Page - 1) * req.PageSize
	logsSQL := `
	SELECT 
		id, timestamp, received_at, source, source_id, service_name, host, level,
		message, raw_message, trace_id, span_id, user_id, client_ip,
		geo_country, geo_city, geo_lat, geo_lon, status_code, response_time_ms,
		error_code, error_description, tags, parsed_fields, labels, original_index
	FROM logs 
	WHERE timestamp BETWEEN ? AND ?
	` + conditions + `
	ORDER BY timestamp DESC
	LIMIT ? OFFSET ?
	`

	logArgs := append([]interface{}{req.StartTime, req.EndTime}, args[2:]...)
	logArgs = append(logArgs, req.PageSize, offset)

	rows, err := c.conn.Query(ctx, logsSQL, logArgs...)
	if err != nil {
		return nil, fmt.Errorf("failed to query logs: %w", err)
	}
	defer rows.Close()

	for rows.Next() {
		event := &models.LogEvent{
			ParsedFields: make(map[string]interface{}),
			Labels:       make(map[string]string),
			Tags:         make([]string, 0),
		}

		var geoCountry, geoCity string
		var geoLat, geoLon float64
		var parsedFields map[string]string

		err := rows.Scan(
			&event.ID, &event.Timestamp, &event.ReceivedAt,
			(*string)(&event.Source), &event.SourceID, &event.ServiceName,
			&event.Host, (*string)(&event.Level), &event.Message,
			&event.RawMessage, &event.TraceID, &event.SpanID,
			&event.UserID, &event.ClientIP, &geoCountry, &geoCity,
			&geoLat, &geoLon, &event.StatusCode, &event.ResponseTime,
			&event.ErrorCode, &event.ErrorDesc, &event.Tags,
			&parsedFields, &event.Labels, &event.OriginalIndex,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan row: %w", err)
		}

		if geoCountry != "" || geoCity != "" {
			event.GeoLocation = &models.GeoLocation{
				Country:   geoCountry,
				City:      geoCity,
				Latitude:  geoLat,
				Longitude: geoLon,
			}
		}

		for k, v := range parsedFields {
			event.ParsedFields[k] = v
		}

		resp.Logs = append(resp.Logs, event)
	}

	tsSQL := `
	SELECT 
		toStartOfMinute(timestamp) as ts,
		count() as cnt,
		sumIf(1, level IN ('ERROR', 'FATAL')) as err_cnt,
		median(response_time_ms) as p50,
		quantile(0.99)(response_time_ms) as p99
	FROM logs 
	WHERE timestamp BETWEEN ? AND ?
	` + conditions + `
	GROUP BY ts ORDER BY ts
	`

	tsArgs := append([]interface{}{req.StartTime, req.EndTime}, args[2:]...)
	tsRows, err := c.conn.Query(ctx, tsSQL, tsArgs...)
	if err != nil {
		return nil, fmt.Errorf("failed to query time series: %w", err)
	}
	defer tsRows.Close()

	for tsRows.Next() {
		point := &models.TimeSeriesPoint{}
		err := tsRows.Scan(&point.Timestamp, &point.Count, &point.ErrorCount, &point.P50Latency, &point.P99Latency)
		if err != nil {
			return nil, fmt.Errorf("failed to scan time series: %w", err)
		}
		resp.TimeSeries = append(resp.TimeSeries, point)
	}

	distSQL := `
	SELECT service_name, count() as cnt
	FROM logs 
	WHERE timestamp BETWEEN ? AND ?
	` + conditions + `
	GROUP BY service_name ORDER BY cnt DESC
	`

	distArgs := append([]interface{}{req.StartTime, req.EndTime}, args[2:]...)
	distRows, err := c.conn.Query(ctx, distSQL, distArgs...)
	if err != nil {
		return nil, fmt.Errorf("failed to query distribution: %w", err)
	}
	defer distRows.Close()

	total := float64(resp.Total)
	for distRows.Next() {
		bucket := &models.DistributionBucket{}
		err := distRows.Scan(&bucket.Key, &bucket.Count)
		if err != nil {
			return nil, fmt.Errorf("failed to scan distribution: %w", err)
		}
		if total > 0 {
			bucket.Percentage = float64(bucket.Count) / total * 100
		}
		resp.Distribution = append(resp.Distribution, bucket)
	}

	return resp, nil
}

func (c *ClickHouseClient) GetEventChain(ctx context.Context, traceID string) (*models.EventChain, error) {
	sql := `
	SELECT 
		id, timestamp, received_at, source, source_id, service_name, host, level,
		message, raw_message, trace_id, span_id, user_id, client_ip,
		geo_country, geo_city, geo_lat, geo_lon, status_code, response_time_ms,
		error_code, error_description, tags, parsed_fields, labels, original_index
	FROM logs 
	WHERE trace_id = ?
	ORDER BY timestamp ASC
	`

	rows, err := c.conn.Query(ctx, sql, traceID)
	if err != nil {
		return nil, fmt.Errorf("failed to query event chain: %w", err)
	}
	defer rows.Close()

	chain := &models.EventChain{
		TraceID:  traceID,
		Services: make([]string, 0),
		Events:   make([]*models.LogEvent, 0),
	}

	serviceSet := make(map[string]bool)

	for rows.Next() {
		event := &models.LogEvent{
			ParsedFields: make(map[string]interface{}),
			Labels:       make(map[string]string),
			Tags:         make([]string, 0),
		}

		var geoCountry, geoCity string
		var geoLat, geoLon float64
		var parsedFields map[string]string

		err := rows.Scan(
			&event.ID, &event.Timestamp, &event.ReceivedAt,
			(*string)(&event.Source), &event.SourceID, &event.ServiceName,
			&event.Host, (*string)(&event.Level), &event.Message,
			&event.RawMessage, &event.TraceID, &event.SpanID,
			&event.UserID, &event.ClientIP, &geoCountry, &geoCity,
			&geoLat, &geoLon, &event.StatusCode, &event.ResponseTime,
			&event.ErrorCode, &event.ErrorDesc, &event.Tags,
			&parsedFields, &event.Labels, &event.OriginalIndex,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan event chain: %w", err)
		}

		if geoCountry != "" || geoCity != "" {
			event.GeoLocation = &models.GeoLocation{
				Country:   geoCountry,
				City:      geoCity,
				Latitude:  geoLat,
				Longitude: geoLon,
			}
		}

		for k, v := range parsedFields {
			event.ParsedFields[k] = v
		}

		if event.Level == models.LevelError || event.Level == models.LevelFatal {
			chain.HasError = true
			if event.ErrorCode != "" {
				chain.ErrorCode = event.ErrorCode
			}
		}

		if !serviceSet[event.ServiceName] {
			serviceSet[event.ServiceName] = true
			chain.Services = append(chain.Services, event.ServiceName)
		}

		if chain.StartTime.IsZero() || event.Timestamp.Before(chain.StartTime) {
			chain.StartTime = event.Timestamp
			chain.RootService = event.ServiceName
		}
		if event.Timestamp.After(chain.EndTime) {
			chain.EndTime = event.Timestamp
		}

		chain.Events = append(chain.Events, event)
	}

	chain.Duration = chain.EndTime.Sub(chain.StartTime).Milliseconds()

	return chain, nil
}

func (c *ClickHouseClient) Close() error {
	return c.conn.Close()
}
