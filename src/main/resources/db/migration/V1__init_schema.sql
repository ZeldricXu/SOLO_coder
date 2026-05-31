CREATE TABLE IF NOT EXISTS cdc_data_source (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    host VARCHAR(128) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR(64),
    username VARCHAR(64),
    password VARCHAR(256),
    config_json TEXT,
    status VARCHAR(32) DEFAULT 'ACTIVE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cdc_schema_info (
    id VARCHAR(64) PRIMARY KEY,
    data_source_id VARCHAR(64) NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    table_count INT DEFAULT 0,
    metadata_json TEXT,
    last_crawled_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_data_source (data_source_id)
);

CREATE TABLE IF NOT EXISTS cdc_table_info (
    id VARCHAR(64) PRIMARY KEY,
    data_source_id VARCHAR(64) NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    row_count BIGINT DEFAULT 0,
    size_bytes BIGINT DEFAULT 0,
    columns_json TEXT,
    indexes_json TEXT,
    statistics_json TEXT,
    sample_data_json TEXT,
    last_analyzed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_data_source (data_source_id),
    UNIQUE KEY uk_table (data_source_id, schema_name, table_name)
);

CREATE TABLE IF NOT EXISTS cdc_capture_task (
    id VARCHAR(64) PRIMARY KEY,
    data_source_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    table_list TEXT,
    start_position TEXT,
    current_position TEXT,
    status VARCHAR(32) DEFAULT 'STOPPED',
    config_json TEXT,
    last_capture_at DATETIME,
    capture_count BIGINT DEFAULT 0,
    error_count BIGINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_data_source (data_source_id),
    INDEX idx_status (status)
);

CREATE TABLE IF NOT EXISTS cdc_change_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    source_database VARCHAR(128),
    source_table VARCHAR(128),
    operation_type VARCHAR(16) NOT NULL,
    before_data JSON,
    after_data JSON,
    event_ts BIGINT NOT NULL,
    processed TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task (task_id),
    INDEX idx_event_ts (event_ts),
    INDEX idx_processed (processed)
);

CREATE TABLE IF NOT EXISTS cdc_stream_query (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    sql_text TEXT NOT NULL,
    parsed_plan_json TEXT,
    optimized_plan_json TEXT,
    physical_plan_json TEXT,
    status VARCHAR(32) DEFAULT 'DRAFT',
    execution_config JSON,
    last_executed_at DATETIME,
    execution_count INT DEFAULT 0,
    version INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_status (status)
);

CREATE TABLE IF NOT EXISTS cdc_vector_index (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    dimension INT NOT NULL,
    index_type VARCHAR(32) DEFAULT 'HNSW',
    metric_type VARCHAR(32) DEFAULT 'COSINE',
    vector_count BIGINT DEFAULT 0,
    index_path VARCHAR(512),
    config_json TEXT,
    status VARCHAR(32) DEFAULT 'CREATING',
    last_build_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cdc_lifecycle_policy (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    hot_storage_days INT DEFAULT 30,
    warm_storage_days INT DEFAULT 90,
    cold_storage_days INT DEFAULT 365,
    archive_after_days INT,
    delete_after_days INT,
    enabled TINYINT DEFAULT 1,
    config_json TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cdc_data_quality_rule (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    data_source_id VARCHAR(64),
    table_name VARCHAR(128),
    column_name VARCHAR(128),
    rule_expression TEXT NOT NULL,
    expected_value VARCHAR(256),
    severity VARCHAR(32) DEFAULT 'WARNING',
    enabled TINYINT DEFAULT 1,
    schedule_cron VARCHAR(64),
    last_check_at DATETIME,
    last_check_result VARCHAR(32),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0,
    INDEX idx_data_source (data_source_id)
);

CREATE TABLE IF NOT EXISTS cdc_quality_check_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    check_time DATETIME NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    actual_value VARCHAR(256),
    expected_value VARCHAR(256),
    error_message TEXT,
    sample_data JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rule (rule_id),
    INDEX idx_check_time (check_time)
);

CREATE TABLE IF NOT EXISTS cdc_lineage_graph (
    id VARCHAR(64) PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_identifier VARCHAR(256) NOT NULL,
    sql_text TEXT,
    lineage_json LONGTEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source (source_type, source_identifier)
);

CREATE TABLE IF NOT EXISTS cdc_lineage_edge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    graph_id VARCHAR(64) NOT NULL,
    source_table VARCHAR(128),
    source_column VARCHAR(128),
    target_table VARCHAR(128),
    target_column VARCHAR(128),
    transformation TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_graph (graph_id),
    INDEX idx_source (source_table, source_column),
    INDEX idx_target (target_table, target_column)
);

CREATE TABLE IF NOT EXISTS cdc_time_series_config (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    metric_name VARCHAR(128) NOT NULL,
    compression_algorithm VARCHAR(32) DEFAULT 'GORILLA',
    raw_retention_days INT DEFAULT 7,
    downsample_1h_retention_days INT DEFAULT 30,
    downsample_1d_retention_days INT DEFAULT 365,
    enabled TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS cdc_time_series_data (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id VARCHAR(64) NOT NULL,
    metric_ts BIGINT NOT NULL,
    value DOUBLE NOT NULL,
    tags_json JSON,
    resolution VARCHAR(16) DEFAULT 'RAW',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_config (config_id),
    INDEX idx_metric_ts (metric_ts),
    INDEX idx_resolution (resolution)
);
