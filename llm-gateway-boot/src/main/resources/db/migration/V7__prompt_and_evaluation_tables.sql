CREATE TABLE IF NOT EXISTS prompt_template (
    prompt_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'Prompt ID',
    prompt_name VARCHAR(128) NOT NULL COMMENT 'Prompt名称',
    description TEXT COMMENT '描述',
    template TEXT NOT NULL COMMENT '模板内容',
    variables JSON COMMENT '变量定义',
    model_config JSON COMMENT '模型配置',
    version INT DEFAULT 1 COMMENT '版本号',
    status VARCHAR(32) DEFAULT 'draft' COMMENT '状态',
    tags JSON COMMENT '标签',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_status (status),
    INDEX idx_created_by (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Prompt模板表';

CREATE TABLE IF NOT EXISTS prompt_version (
    version_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '版本ID',
    prompt_id VARCHAR(64) NOT NULL COMMENT 'Prompt ID',
    version INT NOT NULL COMMENT '版本号',
    template TEXT NOT NULL COMMENT '模板内容',
    variables JSON COMMENT '变量定义',
    model_config JSON COMMENT '模型配置',
    change_log TEXT COMMENT '变更记录',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_prompt_id (prompt_id),
    INDEX idx_version (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Prompt版本表';

CREATE TABLE IF NOT EXISTS ab_experiment (
    experiment_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '实验ID',
    experiment_name VARCHAR(128) NOT NULL COMMENT '实验名称',
    description TEXT COMMENT '描述',
    experiment_type VARCHAR(64) DEFAULT 'ab' COMMENT '实验类型',
    status VARCHAR(32) DEFAULT 'draft' COMMENT '状态',
    traffic_percentage INT DEFAULT 10 COMMENT '流量百分比',
    variants JSON NOT NULL COMMENT '变体配置',
    metrics JSON COMMENT '评估指标',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AB实验表';

CREATE TABLE IF NOT EXISTS ab_experiment_result (
    result_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '结果ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    variant_id VARCHAR(64) NOT NULL COMMENT '变体ID',
    metrics JSON COMMENT '指标结果',
    sample_size INT DEFAULT 0 COMMENT '样本量',
    statistical_significance DECIMAL(5,4) COMMENT '统计显著性',
    is_winner TINYINT DEFAULT 0 COMMENT '是否获胜',
    calculated_at DATETIME COMMENT '计算时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_experiment_id (experiment_id),
    INDEX idx_variant_id (variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AB实验结果表';

CREATE TABLE IF NOT EXISTS evaluation_run (
    run_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '运行ID',
    run_name VARCHAR(128) NOT NULL COMMENT '运行名称',
    model_id VARCHAR(64) NOT NULL COMMENT '模型ID',
    model_version VARCHAR(32) COMMENT '模型版本',
    dataset VARCHAR(128) COMMENT '数据集',
    evaluation_type VARCHAR(64) NOT NULL COMMENT '评估类型',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态',
    metrics JSON COMMENT '评估指标',
    results JSON COMMENT '详细结果',
    error_detail TEXT COMMENT '错误详情',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_model_id (model_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评估运行表';

CREATE TABLE IF NOT EXISTS model_drift (
    drift_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '漂移ID',
    model_id VARCHAR(64) NOT NULL COMMENT '模型ID',
    feature_name VARCHAR(128) NOT NULL COMMENT '特征名称',
    drift_type VARCHAR(32) NOT NULL COMMENT '漂移类型',
    drift_score DECIMAL(10,6) NOT NULL COMMENT '漂移分数',
    threshold DECIMAL(10,6) COMMENT '阈值',
    is_alert TINYINT DEFAULT 0 COMMENT '是否告警',
    window_start DATETIME NOT NULL COMMENT '窗口开始',
    window_end DATETIME NOT NULL COMMENT '窗口结束',
    details JSON COMMENT '详情',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_model_id (model_id),
    INDEX idx_feature_name (feature_name),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型漂移表';
