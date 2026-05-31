-- 设备影子表增加监控相关字段
ALTER TABLE device_shadow
ADD COLUMN sync_latency_ms BIGINT DEFAULT 0 COMMENT '同步延迟(ms)',
ADD COLUMN conflict_count INT DEFAULT 0 COMMENT '状态冲突次数',
ADD COLUMN last_conflict_time DATETIME COMMENT '最后冲突时间',
ADD COLUMN monitor_status VARCHAR(32) DEFAULT 'NORMAL' COMMENT '监控状态',
ADD COLUMN last_metric_update DATETIME COMMENT '最后指标更新时间',
ADD INDEX idx_monitor_status (monitor_status);

-- 数据聚合表增加故障恢复相关字段
ALTER TABLE data_aggregation
ADD COLUMN checkpoint_id VARCHAR(64) COMMENT '检查点ID',
ADD COLUMN recovery_status VARCHAR(32) DEFAULT 'NONE' COMMENT '恢复状态',
ADD COLUMN failure_count INT DEFAULT 0 COMMENT '失败次数',
ADD COLUMN last_failure_time DATETIME COMMENT '最后失败时间',
ADD COLUMN last_failure_reason TEXT COMMENT '最后失败原因',
ADD COLUMN data_checksum VARCHAR(64) COMMENT '数据校验和',
ADD COLUMN recovered_from VARCHAR(64) COMMENT '恢复来源聚合ID',
ADD INDEX idx_recovery_status (recovery_status),
ADD INDEX idx_checkpoint_id (checkpoint_id);

-- 聚合检查点表（用于故障恢复）
CREATE TABLE IF NOT EXISTS aggregation_checkpoint (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    checkpoint_id VARCHAR(64) NOT NULL COMMENT '检查点ID',
    device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
    aggregation_type VARCHAR(32) NOT NULL COMMENT '聚合类型',
    time_window VARCHAR(32) NOT NULL COMMENT '时间窗口',
    buffer_snapshot JSON COMMENT '缓冲区快照',
    window_start DATETIME COMMENT '窗口开始时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    version INT DEFAULT 1 COMMENT '乐观锁版本',
    UNIQUE KEY uk_checkpoint_device (checkpoint_id, device_id),
    INDEX idx_device_id (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合检查点表';

-- AI模型表增加版本化管理字段
ALTER TABLE ai_model
ADD COLUMN parent_model_id VARCHAR(64) COMMENT '父模型ID',
ADD COLUMN version_status VARCHAR(32) DEFAULT 'DRAFT' COMMENT '版本状态',
ADD COLUMN version_description TEXT COMMENT '版本描述',
ADD COLUMN change_log TEXT COMMENT '变更日志',
ADD COLUMN compatibility_check JSON COMMENT '兼容性配置',
ADD COLUMN trained_at DATETIME COMMENT '训练时间',
ADD COLUMN training_dataset VARCHAR(256) COMMENT '训练数据集',
ADD COLUMN accuracy_metrics JSON COMMENT '精度指标',
ADD COLUMN is_default_version TINYINT DEFAULT 0 COMMENT '是否默认版本',
ADD COLUMN deprecated TINYINT DEFAULT 0 COMMENT '是否已废弃',
ADD COLUMN deprecated_at DATETIME COMMENT '废弃时间',
ADD COLUMN deprecated_reason TEXT COMMENT '废弃原因',
ADD INDEX idx_parent_model_id (parent_model_id),
ADD INDEX idx_version_status (version_status),
ADD INDEX idx_is_default_version (is_default_version);

-- 推理任务表增加版本化字段
ALTER TABLE inference_task
ADD COLUMN model_version_snapshot JSON COMMENT '模型版本快照',
ADD COLUMN rollback_available TINYINT DEFAULT 0 COMMENT '是否可回滚',
ADD COLUMN rollback_task_id VARCHAR(64) COMMENT '回滚任务ID',
ADD COLUMN input_data_checksum VARCHAR(64) COMMENT '输入数据校验和',
ADD INDEX idx_rollback_available (rollback_available);

-- 模型版本发布记录表
CREATE TABLE IF NOT EXISTS model_version_release (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    release_id VARCHAR(64) NOT NULL COMMENT '发布ID',
    model_id VARCHAR(64) NOT NULL COMMENT '模型ID',
    model_version VARCHAR(32) NOT NULL COMMENT '模型版本',
    release_type VARCHAR(32) DEFAULT 'MINOR' COMMENT '发布类型',
    release_status VARCHAR(32) DEFAULT 'PENDING' COMMENT '发布状态',
    release_notes TEXT COMMENT '发布说明',
    grayscale_percentage INT DEFAULT 0 COMMENT '灰度百分比',
    grayscale_devices JSON COMMENT '灰度设备列表',
    success_count INT DEFAULT 0 COMMENT '成功升级数',
    failure_count INT DEFAULT 0 COMMENT '失败升级数',
    rollback_count INT DEFAULT 0 COMMENT '回滚数',
    scheduled_at DATETIME COMMENT '计划发布时间',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    version INT DEFAULT 1 COMMENT '乐观锁版本',
    INDEX idx_release_id (release_id),
    INDEX idx_model_id (model_id),
    INDEX idx_release_status (release_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型版本发布记录表';

-- 设备影子监控指标表
CREATE TABLE IF NOT EXISTS shadow_monitor_metric (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    metric_id VARCHAR(64) NOT NULL COMMENT '指标ID',
    device_id VARCHAR(64) NOT NULL COMMENT '设备ID',
    metric_type VARCHAR(32) NOT NULL COMMENT '指标类型',
    metric_value DOUBLE NOT NULL COMMENT '指标值',
    metric_unit VARCHAR(16) COMMENT '指标单位',
    timestamp DATETIME NOT NULL COMMENT '时间戳',
    tags JSON COMMENT '标签信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    version INT DEFAULT 1 COMMENT '乐观锁版本',
    INDEX idx_metric_id (metric_id),
    INDEX idx_device_id (device_id),
    INDEX idx_metric_type (metric_type),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备影子监控指标表';
