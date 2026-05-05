-- TaskFlow 数据库迁移脚本 - 添加任务依赖关系表

USE taskflow;

-- 任务依赖关系表
CREATE TABLE IF NOT EXISTS task_dependencies (
    dependency_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    prerequisite_task_id VARCHAR(36) NOT NULL,
    dependency_type ENUM('finish_to_start', 'start_to_start', 'finish_to_finish', 'start_to_finish') NOT NULL DEFAULT 'finish_to_start',
    lag_days INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_prerequisite (task_id, prerequisite_task_id),
    INDEX idx_dependencies_task (task_id),
    INDEX idx_dependencies_prerequisite (prerequisite_task_id),
    FOREIGN KEY (task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
    FOREIGN KEY (prerequisite_task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 通知队列表（用于异步通知处理）
CREATE TABLE IF NOT EXISTS notification_queue (
    queue_id VARCHAR(36) PRIMARY KEY,
    type ENUM('task_created', 'task_assigned', 'task_status_changed', 'task_deadline_approaching', 'event_created', 'system') NOT NULL,
    payload JSON NOT NULL,
    status ENUM('pending', 'processing', 'completed', 'failed') NOT NULL DEFAULT 'pending',
    priority INT DEFAULT 0,
    retry_count INT DEFAULT 0,
    error_message TEXT NULL,
    scheduled_at TIMESTAMP NULL,
    processed_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_queue_status (status),
    INDEX idx_queue_priority (priority DESC),
    INDEX idx_queue_created (created_at),
    INDEX idx_queue_scheduled (scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 通知队列日志表
CREATE TABLE IF NOT EXISTS notification_queue_log (
    log_id VARCHAR(36) PRIMARY KEY,
    queue_id VARCHAR(36) NOT NULL,
    status ENUM('pending', 'processing', 'completed', 'failed') NOT NULL,
    message TEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_log_queue (queue_id),
    INDEX idx_log_created (created_at),
    FOREIGN KEY (queue_id) REFERENCES notification_queue(queue_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
