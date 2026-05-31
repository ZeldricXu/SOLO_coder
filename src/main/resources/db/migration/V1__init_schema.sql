CREATE TABLE IF NOT EXISTS resource (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    type VARCHAR(64) NOT NULL COMMENT '资源类型',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    attributes JSON COMMENT '属性',
    config JSON COMMENT '配置',
    labels JSON COMMENT '标签',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源表';

CREATE TABLE IF NOT EXISTS config (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    config_id VARCHAR(64) NOT NULL COMMENT '配置ID',
    namespace VARCHAR(64) NOT NULL COMMENT '命名空间',
    version INT NOT NULL COMMENT '版本号',
    parameters JSON COMMENT '参数',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    applied_at DATETIME COMMENT '应用时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_config_namespace (config_id, namespace, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置表';

CREATE TABLE IF NOT EXISTS run_instance (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    run_id VARCHAR(64) NOT NULL COMMENT '运行ID',
    entity_id VARCHAR(64) NOT NULL COMMENT '实体ID',
    phase VARCHAR(32) NOT NULL COMMENT '执行阶段',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    error_detail TEXT COMMENT '错误详情',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    KEY idx_run_id (run_id),
    KEY idx_entity_id (entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS metric_snapshot (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    snapshot_id VARCHAR(64) NOT NULL COMMENT '快照ID',
    timestamp DATETIME NOT NULL COMMENT '时间戳',
    metrics JSON COMMENT '指标数据',
    dimensions JSON COMMENT '维度',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    KEY idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标快照表';

CREATE TABLE IF NOT EXISTS notification (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    notification_id VARCHAR(64) NOT NULL COMMENT '通知ID',
    type VARCHAR(32) NOT NULL COMMENT '通知类型',
    recipient VARCHAR(256) NOT NULL COMMENT '接收者',
    content TEXT COMMENT '内容',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    max_retries INT DEFAULT 3 COMMENT '最大重试次数',
    next_retry_at DATETIME COMMENT '下次重试时间',
    last_error TEXT COMMENT '上次错误',
    delivered_at DATETIME COMMENT '送达时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    KEY idx_status (status),
    KEY idx_next_retry (next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    log_id VARCHAR(64) NOT NULL COMMENT '日志ID',
    user_id VARCHAR(64) COMMENT '用户ID',
    operation VARCHAR(128) NOT NULL COMMENT '操作类型',
    resource_type VARCHAR(64) COMMENT '资源类型',
    resource_id VARCHAR(64) COMMENT '资源ID',
    detail JSON COMMENT '详情',
    previous_hash VARCHAR(128) COMMENT '前一个哈希',
    current_hash VARCHAR(128) NOT NULL COMMENT '当前哈希',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    KEY idx_log_id (log_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

CREATE TABLE IF NOT EXISTS privacy_budget (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    epsilon_remaining DECIMAL(10,6) NOT NULL COMMENT '剩余epsilon',
    delta_remaining DECIMAL(10,6) NOT NULL COMMENT '剩余delta',
    total_queries INT DEFAULT 0 COMMENT '总查询次数',
    last_reset_at DATETIME COMMENT '上次重置时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='隐私预算表';

CREATE TABLE IF NOT EXISTS stored_file (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    file_id VARCHAR(64) NOT NULL COMMENT '文件ID',
    original_name VARCHAR(256) NOT NULL COMMENT '原始文件名',
    stored_path VARCHAR(512) NOT NULL COMMENT '存储路径',
    file_size BIGINT NOT NULL COMMENT '文件大小',
    content_type VARCHAR(128) COMMENT '内容类型',
    lifecycle_policy VARCHAR(32) COMMENT '生命周期策略',
    expire_at DATETIME COMMENT '过期时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    KEY idx_file_id (file_id),
    KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='存储文件表';

CREATE TABLE IF NOT EXISTS alert_rule (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    rule_id VARCHAR(64) NOT NULL COMMENT '规则ID',
    name VARCHAR(128) NOT NULL COMMENT '规则名称',
    metric_name VARCHAR(128) NOT NULL COMMENT '指标名称',
    operator VARCHAR(16) NOT NULL COMMENT '操作符',
    threshold DECIMAL(20,6) NOT NULL COMMENT '阈值',
    severity VARCHAR(16) NOT NULL COMMENT '严重程度',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    notification_channels JSON COMMENT '通知渠道',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则表';

CREATE TABLE IF NOT EXISTS schema_migration (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    version VARCHAR(32) NOT NULL COMMENT '版本号',
    script_name VARCHAR(256) NOT NULL COMMENT '脚本名称',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    checksum VARCHAR(128) COMMENT '校验和',
    executed_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Schema迁移表';

CREATE TABLE IF NOT EXISTS tee_enclave (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    enclave_id VARCHAR(64) NOT NULL COMMENT 'Enclave ID',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    attestation_report TEXT COMMENT '远程证明报告',
    public_key TEXT COMMENT '公钥',
    last_health_check DATETIME COMMENT '上次健康检查',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TEE Enclave表';

CREATE TABLE IF NOT EXISTS fl_training_task (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID',
    model_name VARCHAR(128) NOT NULL COMMENT '模型名称',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    current_round INT DEFAULT 0 COMMENT '当前轮次',
    total_rounds INT NOT NULL COMMENT '总轮次',
    participants JSON COMMENT '参与方',
    global_model_path VARCHAR(512) COMMENT '全局模型路径',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='联邦学习任务表';

CREATE TABLE IF NOT EXISTS data_masking_rule (
    id VARCHAR(64) PRIMARY KEY COMMENT '主键ID',
    rule_id VARCHAR(64) NOT NULL COMMENT '规则ID',
    field_name VARCHAR(128) NOT NULL COMMENT '字段名',
    mask_type VARCHAR(32) NOT NULL COMMENT '脱敏类型',
    required_role VARCHAR(64) COMMENT '所需角色',
    pattern VARCHAR(256) COMMENT '匹配模式',
    replacement VARCHAR(256) COMMENT '替换模板',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据脱敏规则表';
