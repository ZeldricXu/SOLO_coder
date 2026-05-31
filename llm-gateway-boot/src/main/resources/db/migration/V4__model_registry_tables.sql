CREATE TABLE IF NOT EXISTS model (
    model_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '模型ID',
    model_name VARCHAR(128) NOT NULL COMMENT '模型名称',
    model_type VARCHAR(64) NOT NULL COMMENT '模型类型',
    provider VARCHAR(64) NOT NULL COMMENT '提供商',
    description TEXT COMMENT '描述',
    task_type VARCHAR(64) COMMENT '任务类型',
    base_model VARCHAR(128) COMMENT '基础模型',
    license VARCHAR(64) COMMENT '许可证',
    tags JSON COMMENT '标签',
    owner VARCHAR(64) COMMENT '负责人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_model_name (model_name, deleted),
    INDEX idx_provider (provider),
    INDEX idx_model_type (model_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型注册表';

CREATE TABLE IF NOT EXISTS model_version (
    version_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '版本ID',
    model_id VARCHAR(64) NOT NULL COMMENT '模型ID',
    version VARCHAR(32) NOT NULL COMMENT '版本号',
    stage VARCHAR(32) DEFAULT 'development' COMMENT '阶段',
    description TEXT COMMENT '描述',
    artifact_path VARCHAR(512) COMMENT '制品路径',
    metrics JSON COMMENT '指标',
    parameters JSON COMMENT '参数',
    dataset VARCHAR(128) COMMENT '数据集',
    commit_hash VARCHAR(64) COMMENT '提交哈希',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    UNIQUE KEY uk_model_version (model_id, version, deleted),
    INDEX idx_stage (stage),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型版本表';

CREATE TABLE IF NOT EXISTS model_endpoint (
    endpoint_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '端点ID',
    model_id VARCHAR(64) NOT NULL COMMENT '模型ID',
    version_id VARCHAR(64) NOT NULL COMMENT '版本ID',
    endpoint_name VARCHAR(128) NOT NULL COMMENT '端点名称',
    provider VARCHAR(64) NOT NULL COMMENT '提供商',
    base_url VARCHAR(255) NOT NULL COMMENT '基础URL',
    api_key VARCHAR(255) COMMENT 'API密钥',
    max_tokens INT DEFAULT 4096 COMMENT '最大Token数',
    temperature DECIMAL(3,2) DEFAULT 0.7 COMMENT '温度',
    timeout INT DEFAULT 30 COMMENT '超时时间(秒)',
    rate_limit INT DEFAULT 60 COMMENT '限流QPS',
    status VARCHAR(32) DEFAULT 'active' COMMENT '状态',
    priority INT DEFAULT 0 COMMENT '优先级',
    weight INT DEFAULT 100 COMMENT '权重',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_model_id (model_id),
    INDEX idx_provider (provider),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型端点表';

CREATE TABLE IF NOT EXISTS stage_transition_log (
    log_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '日志ID',
    version_id VARCHAR(64) NOT NULL COMMENT '版本ID',
    from_stage VARCHAR(32) COMMENT '源阶段',
    to_stage VARCHAR(32) NOT NULL COMMENT '目标阶段',
    reason TEXT COMMENT '原因',
    created_by VARCHAR(64) COMMENT '操作人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_version_id (version_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='阶段流转日志表';
