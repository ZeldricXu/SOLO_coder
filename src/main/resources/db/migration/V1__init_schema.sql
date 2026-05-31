CREATE TABLE IF NOT EXISTS config_definition (
    id VARCHAR(64) PRIMARY KEY,
    config_id VARCHAR(64) NOT NULL,
    namespace VARCHAR(128) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    parameters JSON,
    enabled BOOLEAN DEFAULT TRUE,
    applied_at TIMESTAMP,
    source VARCHAR(32),
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_namespace (namespace),
    INDEX idx_config_id (config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS log_level_config (
    id VARCHAR(64) PRIMARY KEY,
    service_name VARCHAR(128) NOT NULL,
    logger_name VARCHAR(256) NOT NULL,
    current_level VARCHAR(16) NOT NULL,
    target_level VARCHAR(16) NOT NULL,
    effective_at TIMESTAMP,
    expires_at TIMESTAMP,
    reason VARCHAR(512),
    operator VARCHAR(64),
    active BOOLEAN DEFAULT TRUE,
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_service (service_name),
    INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS task (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    parameters JSON,
    scheduled_by VARCHAR(64),
    scheduled_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_ms BIGINT,
    result TEXT,
    error_message TEXT,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_type (type),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS log_entry (
    id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64),
    service_name VARCHAR(128) NOT NULL,
    level VARCHAR(16) NOT NULL,
    message TEXT,
    logger_name VARCHAR(256),
    thread_name VARCHAR(128),
    timestamp TIMESTAMP NOT NULL,
    tags JSON,
    metadata JSON,
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_service (service_name),
    INDEX idx_trace_id (trace_id),
    INDEX idx_timestamp (timestamp),
    INDEX idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS slo_config (
    id VARCHAR(64) PRIMARY KEY,
    slo_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    service_name VARCHAR(128) NOT NULL,
    target_percentage DOUBLE NOT NULL,
    window_seconds BIGINT NOT NULL,
    sli_config JSON,
    alerting_rules JSON,
    enabled BOOLEAN DEFAULT TRUE,
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_service (service_name),
    INDEX idx_slo_id (slo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS error_budget (
    id VARCHAR(64) PRIMARY KEY,
    budget_id VARCHAR(64) NOT NULL,
    slo_id VARCHAR(64) NOT NULL,
    total_budget DOUBLE NOT NULL,
    remaining_budget DOUBLE NOT NULL,
    consumed_budget DOUBLE NOT NULL DEFAULT 0,
    burn_rate DOUBLE DEFAULT 0,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    status VARCHAR(32),
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_slo_id (slo_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS notification (
    id VARCHAR(64) PRIMARY KEY,
    notification_id VARCHAR(64) NOT NULL,
    title VARCHAR(256) NOT NULL,
    content TEXT,
    priority VARCHAR(16) NOT NULL,
    recipient VARCHAR(128),
    channel VARCHAR(32),
    payload JSON,
    sent_at TIMESTAMP,
    status VARCHAR(32),
    suppression_key VARCHAR(256),
    suppressed_until TIMESTAMP,
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_priority (priority),
    INDEX idx_suppression_key (suppression_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS metrics_snapshot (
    id VARCHAR(64) PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    metrics JSON,
    dimensions JSON,
    attributes JSON,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_timestamp (timestamp),
    INDEX idx_snapshot_id (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
