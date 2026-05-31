CREATE TABLE IF NOT EXISTS feature (
    feature_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '特征ID',
    feature_name VARCHAR(128) NOT NULL COMMENT '特征名称',
    feature_type VARCHAR(64) NOT NULL COMMENT '特征类型',
    description TEXT COMMENT '描述',
    entity VARCHAR(64) NOT NULL COMMENT '所属实体',
    value_type VARCHAR(32) NOT NULL COMMENT '值类型',
    ttl INT DEFAULT 86400 COMMENT '存活时间(秒)',
    version INT DEFAULT 1 COMMENT '版本号',
    status VARCHAR(32) DEFAULT 'active' COMMENT '状态',
    tags JSON COMMENT '标签',
    owner VARCHAR(64) COMMENT '负责人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_feature_name (feature_name, entity, deleted),
    INDEX idx_entity (entity),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='特征注册表';

CREATE TABLE IF NOT EXISTS feature_value (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    feature_id VARCHAR(64) NOT NULL COMMENT '特征ID',
    entity_key VARCHAR(128) NOT NULL COMMENT '实体键',
    value JSON NOT NULL COMMENT '特征值',
    timestamp_ms BIGINT NOT NULL COMMENT '时间戳(毫秒)',
    event_time DATETIME NOT NULL COMMENT '事件时间',
    source VARCHAR(64) COMMENT '数据源',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_feature_entity (feature_id, entity_key),
    INDEX idx_event_time (event_time),
    INDEX idx_timestamp (timestamp_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='特征值表';

CREATE TABLE IF NOT EXISTS feature_backfill_job (
    job_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '回填任务ID',
    feature_id VARCHAR(64) NOT NULL COMMENT '特征ID',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度',
    total_count BIGINT DEFAULT 0 COMMENT '总条数',
    success_count BIGINT DEFAULT 0 COMMENT '成功条数',
    failed_count BIGINT DEFAULT 0 COMMENT '失败条数',
    error_detail TEXT COMMENT '错误详情',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_feature_id (feature_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='特征回填任务表';
