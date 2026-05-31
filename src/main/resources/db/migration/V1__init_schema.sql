CREATE TABLE IF NOT EXISTS core_entities (
    id VARCHAR(64) PRIMARY KEY COMMENT '实体ID',
    type VARCHAR(64) NOT NULL COMMENT '实体类型',
    status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending/processing/completed/failed',
    attributes JSON COMMENT '属性集合',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_type_status (type, status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核心实体表';

CREATE TABLE IF NOT EXISTS config_definitions (
    config_id VARCHAR(64) PRIMARY KEY COMMENT '配置ID',
    namespace VARCHAR(64) NOT NULL COMMENT '命名空间',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    parameters JSON COMMENT '参数集合',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    source VARCHAR(32) DEFAULT 'database' COMMENT '配置来源',
    applied_at DATETIME COMMENT '生效时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_namespace_version (namespace, version, deleted),
    INDEX idx_namespace_enabled (namespace, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置定义表';

CREATE TABLE IF NOT EXISTS run_instances (
    run_id VARCHAR(64) PRIMARY KEY COMMENT '运行实例ID',
    entity_id VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    phase VARCHAR(32) NOT NULL DEFAULT 'initializing' COMMENT '阶段: initializing/processing/finalizing/completed/failed',
    progress DECIMAL(5,4) DEFAULT 0.0 COMMENT '进度 0.0-1.0',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    error_detail TEXT COMMENT '错误详情',
    trace_id VARCHAR(64) COMMENT '链路追踪ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase),
    INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS metric_snapshots (
    snapshot_id VARCHAR(64) PRIMARY KEY COMMENT '快照ID',
    timestamp DATETIME NOT NULL COMMENT '快照时间',
    metrics JSON COMMENT '指标数据',
    dimensions JSON COMMENT '维度数据',
    aggregate_level VARCHAR(16) DEFAULT 'raw' COMMENT '聚合级别: raw/minute/hour/day',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_timestamp (timestamp),
    INDEX idx_aggregate_level (aggregate_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标快照表';

CREATE TABLE IF NOT EXISTS slo_definitions (
    slo_id VARCHAR(64) PRIMARY KEY COMMENT 'SLO ID',
    name VARCHAR(128) NOT NULL COMMENT 'SLO名称',
    description TEXT COMMENT '描述',
    sli_type VARCHAR(64) NOT NULL COMMENT 'SLI类型: availability/latency/throughput/error_rate',
    target DECIMAL(10,6) NOT NULL COMMENT '目标值',
    window_days INT NOT NULL DEFAULT 28 COMMENT '时间窗口(天)',
    threshold DECIMAL(10,6) DEFAULT 0.9 COMMENT '告警阈值比例',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO定义表';

CREATE TABLE IF NOT EXISTS error_budget_records (
    record_id VARCHAR(64) PRIMARY KEY,
    slo_id VARCHAR(64) NOT NULL,
    window_start DATETIME NOT NULL,
    window_end DATETIME NOT NULL,
    total_budget DECIMAL(20,6) NOT NULL,
    consumed_budget DECIMAL(20,6) NOT NULL DEFAULT 0,
    remaining_budget DECIMAL(20,6) NOT NULL,
    burn_rate DECIMAL(10,4) DEFAULT 0 COMMENT '消耗速率',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_slo_id (slo_id),
    INDEX idx_window_start (window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='错误预算记录表';

CREATE TABLE IF NOT EXISTS sli_metrics (
    metric_id VARCHAR(64) PRIMARY KEY,
    slo_id VARCHAR(64) NOT NULL,
    timestamp DATETIME NOT NULL,
    sli_value DECIMAL(20,6) NOT NULL,
    good_events BIGINT DEFAULT 0,
    total_events BIGINT DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_slo_timestamp (slo_id, timestamp),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLI指标表';

CREATE TABLE IF NOT EXISTS anomaly_detection_results (
    result_id VARCHAR(64) PRIMARY KEY,
    metric_name VARCHAR(128) NOT NULL,
    algorithm VARCHAR(64) NOT NULL COMMENT '算法: zscore/ewma/isolation_forest',
    timestamp DATETIME NOT NULL,
    is_anomaly TINYINT DEFAULT 0,
    anomaly_score DECIMAL(20,6) DEFAULT 0,
    baseline_value DECIMAL(20,6),
    current_value DECIMAL(20,6),
    threshold DECIMAL(20,6),
    severity VARCHAR(16) DEFAULT 'info' COMMENT '严重级别: info/warning/critical',
    dimensions JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_metric_time (metric_name, timestamp),
    INDEX idx_severity (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异常检测结果表';

CREATE TABLE IF NOT EXISTS notification_records (
    notification_id VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(32) NOT NULL COMMENT '渠道: email/sms/webhook/dingtalk/feishu',
    template_code VARCHAR(64) NOT NULL,
    recipient VARCHAR(512) NOT NULL,
    title VARCHAR(256),
    content TEXT,
    status VARCHAR(16) DEFAULT 'pending' COMMENT '状态: pending/sent/failed',
    retry_count INT DEFAULT 0,
    error_message TEXT,
    sent_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_channel_status (channel, status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知记录表';

CREATE TABLE IF NOT EXISTS profiling_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    session_type VARCHAR(32) NOT NULL COMMENT '类型: cpu/memory/lock/io',
    target_pid INT,
    duration_seconds INT DEFAULT 60,
    sampling_rate INT DEFAULT 100,
    status VARCHAR(16) DEFAULT 'running' COMMENT '状态: running/completed/failed',
    output_path VARCHAR(512),
    started_at DATETIME NOT NULL,
    ended_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type_status (session_type, status),
    INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='性能剖析会话表';

CREATE TABLE IF NOT EXISTS gateway_access_logs (
    log_id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64),
    request_path VARCHAR(512) NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    request_headers JSON,
    request_body TEXT,
    response_status INT,
    response_headers JSON,
    response_body TEXT,
    client_ip VARCHAR(64),
    service_name VARCHAR(128),
    latency_ms BIGINT,
    error_message VARCHAR(1024),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id (trace_id),
    INDEX idx_path (request_path),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网关访问日志表';
