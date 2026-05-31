CREATE TABLE IF NOT EXISTS core_entity (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attributes TEXT,
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_type (type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS config_definition (
    config_id VARCHAR(64) NOT NULL PRIMARY KEY,
    namespace VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    parameters TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    applied_at DATETIME,
    INDEX idx_namespace (namespace),
    INDEX idx_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS run_instance (
    run_id VARCHAR(64) NOT NULL PRIMARY KEY,
    entity_id VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    progress DOUBLE,
    started_at DATETIME,
    completed_at DATETIME,
    error_detail TEXT,
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stats_snapshot (
    snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
    timestamp DATETIME NOT NULL,
    metrics TEXT,
    dimensions TEXT,
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS domain_event (
    event_id VARCHAR(64) NOT NULL PRIMARY KEY,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT,
    sequence BIGINT,
    occurred_at DATETIME,
    metadata TEXT,
    INDEX idx_aggregate_id (aggregate_id),
    INDEX idx_event_type (event_type),
    INDEX idx_sequence (sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS command_log (
    command_id VARCHAR(64) NOT NULL PRIMARY KEY,
    command_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64),
    payload TEXT,
    user_id VARCHAR(64),
    issued_at DATETIME,
    status VARCHAR(32),
    result TEXT,
    INDEX idx_command_type (command_type),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dns_upstream (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    host VARCHAR(256) NOT NULL,
    port INT DEFAULT 53,
    priority INT DEFAULT 100,
    weight INT DEFAULT 1,
    protocol VARCHAR(16) DEFAULT 'udp',
    enabled BOOLEAN DEFAULT TRUE,
    timeout_ms INT DEFAULT 5000,
    max_retries INT DEFAULT 3,
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_priority (priority),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dns_cache (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    domain VARCHAR(512) NOT NULL,
    record_type INT NOT NULL,
    record_data TEXT,
    ttl BIGINT,
    expires_at DATETIME,
    created_at DATETIME,
    hit_count INT DEFAULT 0,
    INDEX idx_domain (domain),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sidecar_instance (
    instance_id VARCHAR(64) NOT NULL PRIMARY KEY,
    service_name VARCHAR(128) NOT NULL,
    version VARCHAR(64),
    host VARCHAR(256),
    port INT,
    status VARCHAR(32),
    config_hash VARCHAR(64),
    cpu_limit DOUBLE,
    memory_limit DOUBLE,
    created_at DATETIME,
    heartbeat_at DATETIME,
    INDEX idx_service_name (service_name),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS traffic_strategy (
    strategy_id VARCHAR(64) NOT NULL PRIMARY KEY,
    strategy_type VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    description TEXT,
    rules TEXT,
    target_service VARCHAR(128),
    traffic_percent INT DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_strategy_type (strategy_type),
    INDEX idx_target_service (target_service)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mtls_certificate (
    cert_id VARCHAR(64) NOT NULL PRIMARY KEY,
    common_name VARCHAR(256) NOT NULL,
    serial_number VARCHAR(128),
    certificate TEXT,
    private_key TEXT,
    issuer VARCHAR(256),
    not_before DATETIME,
    not_after DATETIME,
    status VARCHAR(32),
    created_at DATETIME,
    rotated_at DATETIME,
    INDEX idx_common_name (common_name),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mtls_crl (
    crl_id VARCHAR(64) NOT NULL PRIMARY KEY,
    serial_number VARCHAR(128) NOT NULL,
    reason VARCHAR(128),
    revoked_at DATETIME,
    expires_at DATETIME,
    INDEX idx_serial_number (serial_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    log_id VARCHAR(64) NOT NULL PRIMARY KEY,
    command_id VARCHAR(64),
    user_id VARCHAR(64),
    action VARCHAR(64),
    resource_type VARCHAR(64),
    resource_id VARCHAR(64),
    before_state TEXT,
    after_state TEXT,
    created_at DATETIME,
    client_ip VARCHAR(64),
    user_agent TEXT,
    INDEX idx_command_id (command_id),
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS request_trace (
    trace_id VARCHAR(64) NOT NULL PRIMARY KEY,
    parent_span_id VARCHAR(64),
    span_id VARCHAR(64),
    service_name VARCHAR(128),
    operation VARCHAR(256),
    start_time DATETIME,
    duration_ms BIGINT,
    status_code INT,
    error_message TEXT,
    tags TEXT,
    INDEX idx_trace_id (trace_id),
    INDEX idx_service_name (service_name),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
