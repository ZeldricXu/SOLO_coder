CREATE TABLE IF NOT EXISTS document (
    document_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '文档ID',
    title VARCHAR(255) COMMENT '标题',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    content_hash VARCHAR(64) COMMENT '内容哈希',
    storage_path VARCHAR(512) COMMENT '存储路径',
    charset VARCHAR(32) DEFAULT 'UTF-8' COMMENT '编码',
    language VARCHAR(32) DEFAULT 'zh-CN' COMMENT '语言',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态',
    metadata JSON COMMENT '元数据',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_file_type (file_type),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

CREATE TABLE IF NOT EXISTS document_chunk (
    chunk_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '切片ID',
    document_id VARCHAR(64) NOT NULL COMMENT '文档ID',
    chunk_index INT NOT NULL COMMENT '切片索引',
    content TEXT NOT NULL COMMENT '切片内容',
    content_length INT DEFAULT 0 COMMENT '内容长度',
    token_count INT DEFAULT 0 COMMENT 'Token数量',
    start_offset INT DEFAULT 0 COMMENT '起始偏移',
    end_offset INT DEFAULT 0 COMMENT '结束偏移',
    metadata JSON COMMENT '元数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_document_id (document_id),
    INDEX idx_chunk_index (chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档切片表';

CREATE TABLE IF NOT EXISTS document_embedding (
    embedding_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '向量ID',
    chunk_id VARCHAR(64) NOT NULL COMMENT '切片ID',
    document_id VARCHAR(64) NOT NULL COMMENT '文档ID',
    model_name VARCHAR(128) NOT NULL COMMENT '模型名称',
    vector BLOB NOT NULL COMMENT '向量数据',
    dimension INT NOT NULL COMMENT '维度',
    metadata JSON COMMENT '元数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_chunk_id (chunk_id),
    INDEX idx_document_id (document_id),
    INDEX idx_model_name (model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档向量表';

CREATE TABLE IF NOT EXISTS parse_pipeline (
    pipeline_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '管道ID',
    pipeline_name VARCHAR(128) NOT NULL COMMENT '管道名称',
    description TEXT COMMENT '描述',
    config JSON COMMENT '配置',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='解析管道表';

CREATE TABLE IF NOT EXISTS parse_task (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '任务ID',
    document_id VARCHAR(64) NOT NULL COMMENT '文档ID',
    pipeline_id VARCHAR(64) COMMENT '管道ID',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态',
    phase VARCHAR(64) COMMENT '当前阶段',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度',
    chunk_count INT DEFAULT 0 COMMENT '切片数量',
    error_detail TEXT COMMENT '错误详情',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_document_id (document_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='解析任务表';
