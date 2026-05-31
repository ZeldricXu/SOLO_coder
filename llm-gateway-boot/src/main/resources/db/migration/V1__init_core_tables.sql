CREATE TABLE IF NOT EXISTS core_entity (
    id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '实体ID',
    type VARCHAR(64) NOT NULL COMMENT '实体类型',
    status VARCHAR(64) NOT NULL COMMENT '状态',
    attributes JSON COMMENT '属性集合',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核心实体表';

CREATE TABLE IF NOT EXISTS config_definition (
    config_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '配置ID',
    namespace VARCHAR(128) NOT NULL COMMENT '命名空间',
    version INT NOT NULL DEFAULT 1 COMMENT '版本号',
    parameters JSON COMMENT '参数集合',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    applied_at DATETIME COMMENT '生效时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_namespace (namespace),
    INDEX idx_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置定义表';

CREATE TABLE IF NOT EXISTS run_instance (
    run_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '运行实例ID',
    entity_id VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    phase VARCHAR(64) NOT NULL COMMENT '执行阶段',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    error_detail TEXT COMMENT '错误详情',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase),
    INDEX idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS metric_snapshot (
    snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '快照ID',
    timestamp DATETIME NOT NULL COMMENT '时间戳',
    metrics JSON COMMENT '指标数据',
    dimensions JSON COMMENT '维度数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标快照表';
