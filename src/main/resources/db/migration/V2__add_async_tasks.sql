-- V2: 添加异步任务表
CREATE TABLE IF NOT EXISTS async_tasks (
    task_id VARCHAR(64) PRIMARY KEY COMMENT '任务ID',
    trace_id VARCHAR(64) COMMENT '追踪ID',
    namespace VARCHAR(64) COMMENT '命名空间',
    status VARCHAR(32) NOT NULL COMMENT '状态',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步任务表';
