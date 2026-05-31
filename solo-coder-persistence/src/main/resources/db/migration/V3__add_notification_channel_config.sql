-- V3__add_notification_channel_config.sql
-- Add notification channel dynamic configuration table

CREATE TABLE IF NOT EXISTS t_notification_channel_config (
    id BIGINT PRIMARY KEY,
    channel_type VARCHAR(64) NOT NULL,
    enabled TINYINT DEFAULT 1,
    rate_limit_per_second INT DEFAULT 100,
    timeout_ms INT DEFAULT 5000,
    max_retries INT DEFAULT 3,
    retry_interval_ms BIGINT DEFAULT 1000,
    extra_json TEXT,
    config_version BIGINT DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0,
    UNIQUE INDEX idx_channel_type (channel_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Add expires_at column to log_level_config for persistence support
ALTER TABLE t_log_level_config ADD COLUMN expires_at DATETIME;
ALTER TABLE t_log_level_config ADD COLUMN logger_version BIGINT DEFAULT 1;
