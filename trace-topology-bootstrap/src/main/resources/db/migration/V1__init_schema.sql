CREATE TABLE IF NOT EXISTS t_entity (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attributes TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_type (type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_config (
    config_id VARCHAR(64) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    parameters TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    applied_at DATETIME NOT NULL,
    PRIMARY KEY (config_id, version),
    INDEX idx_namespace (namespace),
    INDEX idx_config_id (config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_run_instance (
    run_id VARCHAR(64) NOT NULL PRIMARY KEY,
    entity_id VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    progress DOUBLE NOT NULL DEFAULT 0,
    started_at DATETIME NOT NULL,
    completed_at DATETIME,
    error_detail TEXT,
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_service_node (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    service_name VARCHAR(128) NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    version VARCHAR(32),
    metadata TEXT,
    registered_at DATETIME NOT NULL,
    last_heartbeat_at DATETIME NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_namespace (namespace),
    INDEX idx_service_name (service_name),
    INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_snapshot (
    snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
    timestamp DATETIME NOT NULL,
    metrics TEXT,
    dimensions TEXT,
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64),
    user_id VARCHAR(64),
    operation VARCHAR(128) NOT NULL,
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    request_body TEXT,
    response_body TEXT,
    status_code INT,
    duration_ms BIGINT,
    created_at DATETIME NOT NULL,
    INDEX idx_trace_id (trace_id),
    INDEX idx_operation (operation),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
