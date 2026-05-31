CREATE TABLE IF NOT EXISTS adversarial_attack (
    attack_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '攻击ID',
    attack_name VARCHAR(128) NOT NULL COMMENT '攻击名称',
    attack_type VARCHAR(64) NOT NULL COMMENT '攻击类型',
    description TEXT COMMENT '描述',
    strategy VARCHAR(64) NOT NULL COMMENT '攻击策略',
    parameters JSON COMMENT '参数配置',
    severity VARCHAR(32) DEFAULT 'medium' COMMENT '严重程度',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_attack_type (attack_type),
    INDEX idx_severity (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对抗攻击表';

CREATE TABLE IF NOT EXISTS adversarial_prompt (
    prompt_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '对抗Prompt ID',
    attack_id VARCHAR(64) NOT NULL COMMENT '攻击ID',
    original_prompt TEXT COMMENT '原始Prompt',
    adversarial_prompt TEXT NOT NULL COMMENT '对抗Prompt',
    target_model VARCHAR(128) COMMENT '目标模型',
    expected_behavior TEXT COMMENT '预期行为',
    success_criteria VARCHAR(255) COMMENT '成功条件',
    generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    INDEX idx_attack_id (attack_id),
    INDEX idx_target_model (target_model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对抗Prompt表';

CREATE TABLE IF NOT EXISTS adversarial_evaluation (
    eval_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '评估ID',
    model_id VARCHAR(64) NOT NULL COMMENT '模型ID',
    model_version VARCHAR(32) COMMENT '模型版本',
    attack_count INT DEFAULT 0 COMMENT '攻击次数',
    success_count INT DEFAULT 0 COMMENT '成功次数',
    failure_count INT DEFAULT 0 COMMENT '失败次数',
    success_rate DECIMAL(5,2) DEFAULT 0 COMMENT '成功率',
    avg_response_time_ms BIGINT DEFAULT 0 COMMENT '平均响应时间',
    details JSON COMMENT '详细结果',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_by VARCHAR(64) COMMENT '创建人',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_model_id (model_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对抗评估表';

CREATE TABLE IF NOT EXISTS gpu_node (
    node_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '节点ID',
    node_name VARCHAR(128) NOT NULL COMMENT '节点名称',
    host VARCHAR(255) NOT NULL COMMENT '主机地址',
    port INT DEFAULT 22 COMMENT 'SSH端口',
    gpu_type VARCHAR(64) NOT NULL COMMENT 'GPU类型',
    gpu_count INT NOT NULL COMMENT 'GPU数量',
    total_memory_gb INT NOT NULL COMMENT '总显存(GB)',
    available_memory_gb INT NOT NULL COMMENT '可用显存(GB)',
    status VARCHAR(32) DEFAULT 'online' COMMENT '状态',
    labels JSON COMMENT '标签',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_gpu_type (gpu_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='GPU节点表';

CREATE TABLE IF NOT EXISTS gpu_task (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '任务ID',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    task_type VARCHAR(64) NOT NULL COMMENT '任务类型',
    priority INT DEFAULT 0 COMMENT '优先级',
    required_gpu_count INT DEFAULT 1 COMMENT '需要GPU数量',
    required_memory_gb INT DEFAULT 0 COMMENT '需要显存(GB)',
    node_id VARCHAR(64) COMMENT '分配节点ID',
    gpu_indices JSON COMMENT '分配GPU索引',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态',
    command TEXT COMMENT '执行命令',
    output_path VARCHAR(512) COMMENT '输出路径',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度',
    pid INT DEFAULT 0 COMMENT '进程ID',
    error_detail TEXT COMMENT '错误详情',
    submitter VARCHAR(64) COMMENT '提交人',
    queued_at DATETIME COMMENT '排队时间',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX idx_status (status),
    INDEX idx_priority (priority),
    INDEX idx_node_id (node_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='GPU任务表';
