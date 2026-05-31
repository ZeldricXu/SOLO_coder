-- 创建文件表
CREATE TABLE IF NOT EXISTS files (
    id VARCHAR(64) PRIMARY KEY COMMENT '文件ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    file_path VARCHAR(512) COMMENT '文件存储路径',
    storage_class VARCHAR(32) DEFAULT 'standard' COMMENT '存储类型',
    lifecycle_policy VARCHAR(64) COMMENT '生命周期策略',
    status VARCHAR(32) DEFAULT 'active' COMMENT '状态',
    metadata TEXT COMMENT '元数据(JSON)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    archived_at DATETIME COMMENT '归档时间',
    expires_at DATETIME COMMENT '过期时间',
    INDEX idx_status (status),
    INDEX idx_expires_at (expires_at),
    INDEX idx_file_name (file_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件存储表';

-- 创建特征表
CREATE TABLE IF NOT EXISTS features (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    feature_name VARCHAR(128) NOT NULL COMMENT '特征名称',
    entity_id VARCHAR(64) NOT NULL COMMENT '实体ID',
    feature_value TEXT COMMENT '特征值(JSON)',
    event_time DATETIME NOT NULL COMMENT '事件时间',
    source VARCHAR(64) DEFAULT 'online' COMMENT '来源(online/offline)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_feature_entity (feature_name, entity_id),
    INDEX idx_event_time (event_time),
    INDEX idx_source (source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='特征存储表';

-- 创建特征注册表
CREATE TABLE IF NOT EXISTS feature_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    feature_name VARCHAR(128) UNIQUE NOT NULL COMMENT '特征名称',
    description TEXT COMMENT '特征描述',
    schema_definition TEXT COMMENT 'Schema定义(JSON)',
    value_type VARCHAR(32) DEFAULT 'double' COMMENT '值类型',
    is_active TINYINT(1) DEFAULT 1 COMMENT '是否激活',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='特征注册表';

-- 创建GPU任务表
CREATE TABLE IF NOT EXISTS gpu_tasks (
    id VARCHAR(64) PRIMARY KEY COMMENT '任务ID',
    task_name VARCHAR(255) NOT NULL COMMENT '任务名称',
    priority INT DEFAULT 5 COMMENT '优先级(1-10)',
    gpu_requirement INT DEFAULT 1 COMMENT '需要的GPU数量',
    parameters TEXT COMMENT '任务参数(JSON)',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态',
    progress DOUBLE DEFAULT 0 COMMENT '进度(0-1)',
    allocated_gpus VARCHAR(255) COMMENT '分配的GPU列表',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    error_detail TEXT COMMENT '错误详情',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_status (status),
    INDEX idx_priority (priority),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='GPU任务表';

-- 创建文档表
CREATE TABLE IF NOT EXISTS documents (
    id VARCHAR(64) PRIMARY KEY COMMENT '文档ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    content_type VARCHAR(128) COMMENT '内容类型',
    content LONGTEXT COMMENT '文档内容',
    chunk_count INT DEFAULT 0 COMMENT '分块数量',
    vector_count INT DEFAULT 0 COMMENT '向量数量',
    status VARCHAR(32) DEFAULT 'processing' COMMENT '状态',
    error_detail TEXT COMMENT '错误详情',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status),
    INDEX idx_content_type (content_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

-- 创建Prompt版本表
CREATE TABLE IF NOT EXISTS prompt_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    prompt_name VARCHAR(128) NOT NULL COMMENT 'Prompt名称',
    version VARCHAR(32) NOT NULL COMMENT '版本号',
    content TEXT NOT NULL COMMENT 'Prompt内容',
    variables TEXT COMMENT '变量定义(JSON)',
    description TEXT COMMENT '描述',
    created_by VARCHAR(64) COMMENT '创建人',
    is_default TINYINT(1) DEFAULT 0 COMMENT '是否默认版本',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_prompt_version (prompt_name, version),
    INDEX idx_prompt_name (prompt_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Prompt版本表';

-- 创建AB实验表
CREATE TABLE IF NOT EXISTS ab_experiments (
    id VARCHAR(64) PRIMARY KEY COMMENT '实验ID',
    experiment_name VARCHAR(255) NOT NULL COMMENT '实验名称',
    prompt_name VARCHAR(128) NOT NULL COMMENT '关联的Prompt名称',
    versions TEXT NOT NULL COMMENT '参与实验的版本列表(JSON)',
    traffic_split TEXT NOT NULL COMMENT '流量分配(JSON)',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    status VARCHAR(32) DEFAULT 'running' COMMENT '状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_status (status),
    INDEX idx_prompt_name (prompt_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AB实验表';

-- 创建实验结果表
CREATE TABLE IF NOT EXISTS experiment_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    experiment_id VARCHAR(64) NOT NULL COMMENT '实验ID',
    version VARCHAR(32) NOT NULL COMMENT '版本号',
    request_id VARCHAR(64) COMMENT '请求ID',
    metrics TEXT COMMENT '指标数据(JSON)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_experiment_version (experiment_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实验结果表';

-- 创建模型评估表
CREATE TABLE IF NOT EXISTS model_evaluations (
    id VARCHAR(64) PRIMARY KEY COMMENT '评估ID',
    model_id VARCHAR(128) NOT NULL COMMENT '模型ID',
    dataset_id VARCHAR(128) COMMENT '数据集ID',
    metrics TEXT COMMENT '评估指标(JSON)',
    config TEXT COMMENT '评估配置(JSON)',
    status VARCHAR(32) DEFAULT 'running' COMMENT '状态',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_model_id (model_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型评估表';

-- 创建预测记录表
CREATE TABLE IF NOT EXISTS prediction_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    model_id VARCHAR(128) NOT NULL COMMENT '模型ID',
    prediction_id VARCHAR(64) NOT NULL COMMENT '预测ID',
    features TEXT COMMENT '特征数据(JSON)',
    prediction TEXT COMMENT '预测结果(JSON)',
    actual_value TEXT COMMENT '实际值',
    accuracy DOUBLE COMMENT '本次预测准确度',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_model_id (model_id),
    INDEX idx_prediction_id (prediction_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预测记录表';

-- 创建路由配置表
CREATE TABLE IF NOT EXISTS gateway_routes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    path VARCHAR(255) UNIQUE NOT NULL COMMENT '路径模式',
    target_service VARCHAR(128) NOT NULL COMMENT '目标服务',
    target_url VARCHAR(512) NOT NULL COMMENT '目标URL',
    protocol VARCHAR(32) DEFAULT 'HTTP' COMMENT '协议',
    is_enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网关路由表';
