-- ChaosLab Feature Enhancement V2
-- Sidecar Dynamic Config, mTLS Pluggable Strategy, DNS Async

-- ============================================================
-- Sidecar Dynamic Config Tables
-- ============================================================

CREATE TABLE `dynamic_config` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `config_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '配置ID',
    `config_key` VARCHAR(128) NOT NULL UNIQUE COMMENT '配置键',
    `config_name` VARCHAR(128) NOT NULL COMMENT '配置名称',
    `config_type` VARCHAR(32) NOT NULL DEFAULT 'string' COMMENT '配置类型',
    `description` VARCHAR(512) COMMENT '配置描述',
    `config_value` JSON COMMENT '配置值JSON',
    `default_value` VARCHAR(256) COMMENT '默认值',
    `validation_rule` TEXT COMMENT '验证规则JSON',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `hot_reloadable` TINYINT NOT NULL DEFAULT 1 COMMENT '是否支持热重载',
    `scope` VARCHAR(64) NOT NULL DEFAULT 'global' COMMENT '作用域',
    `last_modified_by` VARCHAR(64) COMMENT '最后修改人',
    `last_modified_at` DATETIME COMMENT '最后修改时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_config_key` (`config_key`),
    INDEX `idx_scope` (`scope`),
    INDEX `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态配置表';

CREATE TABLE `config_template` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `template_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '模板ID',
    `template_name` VARCHAR(128) NOT NULL COMMENT '模板名称',
    `template_type` VARCHAR(32) NOT NULL COMMENT '模板类型',
    `scenario` VARCHAR(64) NOT NULL COMMENT '适用场景',
    `description` VARCHAR(512) COMMENT '模板描述',
    `config_data` JSON COMMENT '配置数据JSON',
    `resource_limits` JSON COMMENT '资源限制JSON',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `priority` INT NOT NULL DEFAULT 0 COMMENT '优先级',
    `created_by` VARCHAR(64) COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_scenario` (`scenario`),
    INDEX `idx_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置模板表';

CREATE TABLE `config_change_log` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `log_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '日志ID',
    `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
    `config_key` VARCHAR(128) NOT NULL COMMENT '配置键',
    `old_value` JSON COMMENT '旧值',
    `new_value` JSON COMMENT '新值',
    `change_type` VARCHAR(32) NOT NULL COMMENT '变更类型',
    `changed_by` VARCHAR(64) NOT NULL COMMENT '变更人',
    `changed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    `change_reason` VARCHAR(512) COMMENT '变更原因',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    `rollback_status` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '回滚状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_config_id` (`config_id`),
    INDEX `idx_config_key` (`config_key`),
    INDEX `idx_changed_at` (`changed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置变更日志表';

-- ============================================================
-- Sidecar Instance Enhancement
-- ============================================================

ALTER TABLE `sidecar_instance`
ADD COLUMN `config_update_pending` TINYINT DEFAULT 0 COMMENT '配置更新待处理' AFTER `last_heartbeat`;

-- ============================================================
-- DNS Async Task Table
-- ============================================================

CREATE TABLE `dns_async_task` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '任务ID',
    `domain` VARCHAR(256) NOT NULL COMMENT '域名',
    `query_type` VARCHAR(16) NOT NULL DEFAULT 'A' COMMENT '查询类型',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    `priority` VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '优先级',
    `callback_type` VARCHAR(32) COMMENT '回调类型',
    `callback_url` VARCHAR(512) COMMENT '回调URL',
    `callback_headers` JSON COMMENT '回调头',
    `event_name` VARCHAR(64) COMMENT '事件名称',
    `event_payload` JSON COMMENT '事件载荷',
    `request_id` VARCHAR(64) COMMENT '请求ID',
    `upstream_id` VARCHAR(64) COMMENT '上游DNS ID',
    `result` JSON COMMENT '结果JSON',
    `error_message` TEXT COMMENT '错误信息',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `submitted_at` DATETIME COMMENT '提交时间',
    `started_at` DATETIME COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `duration_ms` BIGINT COMMENT '耗时(ms)',
    `requested_by` VARCHAR(64) COMMENT '请求人',
    `context` JSON COMMENT '上下文数据',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '版本号',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_status` (`status`),
    INDEX `idx_domain` (`domain`),
    INDEX `idx_priority` (`priority`),
    INDEX `idx_submitted_at` (`submitted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DNS异步任务表';

-- ============================================================
-- mTLS Strategy Tables (No new tables needed, using existing)
-- ============================================================

-- Insert default dynamic configs
INSERT INTO `dynamic_config` (`config_id`, `config_key`, `config_name`, `config_type`, `description`, `config_value`, `hot_reloadable`, `scope`, `last_modified_by`, `last_modified_at`, `version`) VALUES
('dc-default-cpu', 'sidecar.resource.cpu.limit', 'Sidecar CPU限制', 'resource', 'Sidecar容器默认CPU限制', '{"value": "500m"}', 1, 'global', 'system', NOW(), 1),
('dc-default-mem', 'sidecar.resource.memory.limit', 'Sidecar内存限制', 'resource', 'Sidecar容器默认内存限制', '{"value": "256Mi"}', 1, 'global', 'system', NOW(), 1),
('dc-default-image', 'sidecar.image.default', '默认Sidecar镜像', 'image', '默认Sidecar代理镜像', '{"value": "chaoslab/sidecar-proxy:v1.0.0"}', 1, 'global', 'system', NOW(), 1),
('dc-dns-timeout', 'dns.query.timeout', 'DNS查询超时', 'timeout', 'DNS查询默认超时时间(ms)', '{"value": 5000}', 1, 'global', 'system', NOW(), 1),
('dc-dns-cache-ttl', 'dns.cache.ttl', 'DNS缓存TTL', 'cache', 'DNS缓存默认TTL(秒)', '{"value": 300}', 1, 'global', 'system', NOW(), 1);

-- Insert default config templates
INSERT INTO `config_template` (`template_id`, `template_name`, `template_type`, `scenario`, `description`, `config_data`, `resource_limits`, `priority`, `created_by`) VALUES
('ct-prod-high', '生产环境高性能模板', 'sidecar', 'production', '生产环境高性能Sidecar配置', '{"logLevel": "WARN", "timeout": 30, "retryCount": 5, "connectionPool": 100}', '{"cpuLimit": "1000m", "memoryLimit": "512Mi", "cpuRequest": "200m", "memoryRequest": "256Mi"}', 1, 'system'),
('ct-staging-standard', '预发环境标准模板', 'sidecar', 'staging', '预发环境标准Sidecar配置', '{"logLevel": "INFO", "timeout": 30, "retryCount": 3, "connectionPool": 50}', '{"cpuLimit": "500m", "memoryLimit": "256Mi", "cpuRequest": "100m", "memoryRequest": "128Mi"}', 5, 'system'),
('ct-dev-light', '开发环境轻量模板', 'sidecar', 'development', '开发环境轻量Sidecar配置', '{"logLevel": "DEBUG", "timeout": 60, "retryCount": 1, "connectionPool": 10}', '{"cpuLimit": "250m", "memoryLimit": "128Mi", "cpuRequest": "50m", "memoryRequest": "64Mi"}', 10, 'system'),
('ct-test-strict', '测试环境严格模板', 'sidecar', 'testing', '测试环境严格Sidecar配置', '{"logLevel": "TRACE", "timeout": 120, "retryCount": 0, "connectionPool": 5, "debugMode": true}', '{"cpuLimit": "200m", "memoryLimit": "128Mi", "cpuRequest": "50m", "memoryRequest": "64Mi"}', 15, 'system');
