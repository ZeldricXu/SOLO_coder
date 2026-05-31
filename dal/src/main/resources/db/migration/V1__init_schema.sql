CREATE TABLE IF NOT EXISTS t_resource (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(64) NOT NULL COMMENT '资源ID',
    type VARCHAR(64) NOT NULL COMMENT '资源类型',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    attributes JSON COMMENT '属性',
    namespace VARCHAR(64) COMMENT '命名空间',
    config TEXT COMMENT '配置',
    labels TEXT COMMENT '标签',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_resource_id (resource_id),
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_namespace (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源表';

CREATE TABLE IF NOT EXISTS t_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_id VARCHAR(64) NOT NULL COMMENT '配置ID',
    namespace VARCHAR(64) NOT NULL COMMENT '命名空间',
    version INT NOT NULL DEFAULT 1 COMMENT '版本',
    parameters JSON COMMENT '参数',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    applied_at DATETIME COMMENT '生效时间',
    source VARCHAR(32) COMMENT '配置来源',
    content TEXT COMMENT '配置内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_config_id (config_id),
    INDEX idx_namespace (namespace),
    INDEX idx_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置表';

CREATE TABLE IF NOT EXISTS t_run_instance (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL COMMENT '运行实例ID',
    entity_id VARCHAR(64) NOT NULL COMMENT '实体ID',
    phase VARCHAR(32) NOT NULL COMMENT '阶段',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    error_detail TEXT COMMENT '错误详情',
    trace_id VARCHAR(64) COMMENT '链路ID',
    metadata TEXT COMMENT '元数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_run_id (run_id),
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase),
    INDEX idx_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS t_metric_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_id VARCHAR(64) NOT NULL COMMENT '快照ID',
    timestamp DATETIME NOT NULL COMMENT '时间戳',
    metrics JSON COMMENT '指标集合',
    dimensions JSON COMMENT '维度',
    metric_name VARCHAR(128) COMMENT '指标名称',
    value DOUBLE COMMENT '指标值',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_snapshot_id (snapshot_id),
    INDEX idx_timestamp (timestamp),
    INDEX idx_metric_name (metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标快照表';

CREATE TABLE IF NOT EXISTS t_alert_rule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id VARCHAR(64) NOT NULL COMMENT '告警规则ID',
    name VARCHAR(128) NOT NULL COMMENT '规则名称',
    metric_name VARCHAR(128) NOT NULL COMMENT '指标名称',
    expression TEXT NOT NULL COMMENT '告警表达式',
    level VARCHAR(32) NOT NULL DEFAULT 'warning' COMMENT '告警级别',
    threshold DOUBLE NOT NULL COMMENT '阈值',
    duration INT NOT NULL DEFAULT 60 COMMENT '持续时间(秒)',
    notification_config JSON COMMENT '通知配置',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_alert_id (alert_id),
    INDEX idx_metric_name (metric_name),
    INDEX idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则表';

CREATE TABLE IF NOT EXISTS t_scheduled_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL COMMENT '任务ID',
    name VARCHAR(128) NOT NULL COMMENT '任务名称',
    cron_expression VARCHAR(64) NOT NULL COMMENT 'Cron表达式',
    job_type VARCHAR(32) NOT NULL COMMENT '任务类型',
    job_params JSON COMMENT '任务参数',
    status VARCHAR(32) NOT NULL DEFAULT 'stopped' COMMENT '状态',
    last_run_at DATETIME COMMENT '上次运行时间',
    next_run_at DATETIME COMMENT '下次运行时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_job_id (job_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务表';

CREATE TABLE IF NOT EXISTS t_slo_config (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    slo_id VARCHAR(64) NOT NULL COMMENT 'SLO ID',
    name VARCHAR(128) NOT NULL COMMENT 'SLO名称',
    sli_metric VARCHAR(128) NOT NULL COMMENT 'SLI指标',
    target DOUBLE NOT NULL COMMENT '目标值',
    time_window INT NOT NULL DEFAULT 2592000 COMMENT '时间窗口(秒)',
    error_budget DOUBLE NOT NULL COMMENT '错误预算',
    burn_rate_threshold DOUBLE NOT NULL DEFAULT 2 COMMENT '燃尽率阈值',
    notification_config JSON COMMENT '通知配置',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_slo_id (slo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLO配置表';

CREATE TABLE IF NOT EXISTS t_log_pipeline (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    pipeline_id VARCHAR(64) NOT NULL COMMENT '管道ID',
    name VARCHAR(128) NOT NULL COMMENT '管道名称',
    source_config JSON COMMENT '源配置',
    parser_config JSON COMMENT '解析配置',
    filter_config JSON COMMENT '过滤配置',
    route_config JSON COMMENT '路由配置',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_pipeline_id (pipeline_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日志管道表';
