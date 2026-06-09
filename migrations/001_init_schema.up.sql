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
SETTINGS index_granularity = 8192;

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
GROUP BY timestamp, service_name, level;

CREATE TABLE IF NOT EXISTS alerts (
    id String,
    alert_type String,
    service_name String,
    severity String,
    title String,
    description String,
    metric_value Float64,
    threshold Float64,
    algorithm String,
    score Float64,
    created_at DateTime64(9, 'UTC'),
    deduplication_key String,
    labels Map(String, String),
    raw_data String
) ENGINE = MergeTree()
PARTITION BY toDate(created_at)
ORDER BY (created_at, service_name, severity)
SETTINGS index_granularity = 8192;

CREATE TABLE IF NOT EXISTS incidents (
    id String,
    title String,
    severity String,
    status String,
    service_names Array(String),
    error_codes Array(String),
    trace_ids Array(String),
    alert_count Int32,
    start_time DateTime64(9, 'UTC'),
    end_time DateTime64(9, 'UTC'),
    updated_at DateTime64(9, 'UTC'),
    labels Map(String, String)
) ENGINE = MergeTree()
PARTITION BY toDate(start_time)
ORDER BY (start_time, severity)
SETTINGS index_granularity = 8192;
