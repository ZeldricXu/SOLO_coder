CREATE TABLE IF NOT EXISTS `alert_rules` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `rule_id` VARCHAR(64) NOT NULL COMMENT '规则ID',
    `name` VARCHAR(255) NOT NULL COMMENT '规则名称',
    `description` TEXT COMMENT '规则描述',
    `namespace` VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '命名空间',
    `metric_name` VARCHAR(255) NOT NULL COMMENT '指标名称',
    `operator` VARCHAR(32) NOT NULL COMMENT '比较运算符',
    `threshold` DOUBLE NOT NULL COMMENT '阈值',
    `duration_seconds` INT NOT NULL DEFAULT 60 COMMENT '持续时间(秒)',
    `severity` VARCHAR(32) NOT NULL DEFAULT 'warning' COMMENT '严重程度',
    `notification_channels` VARCHAR(512) COMMENT '通知渠道',
    `labels` TEXT COMMENT '标签(JSON)',
    `annotations` TEXT COMMENT '注解(JSON)',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_by` VARCHAR(128) COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_id` (`rule_id`),
    KEY `idx_namespace` (`namespace`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则表';

CREATE TABLE IF NOT EXISTS `alert_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `alert_id` VARCHAR(64) NOT NULL COMMENT '告警ID',
    `rule_id` VARCHAR(64) NOT NULL COMMENT '规则ID',
    `severity` VARCHAR(32) NOT NULL COMMENT '严重程度',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `current_value` DOUBLE COMMENT '当前值',
    `message` TEXT COMMENT '告警消息',
    `labels` TEXT COMMENT '标签(JSON)',
    `annotations` TEXT COMMENT '注解(JSON)',
    `started_at` DATETIME NOT NULL COMMENT '开始时间',
    `resolved_at` DATETIME COMMENT '解决时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_alert_id` (`alert_id`),
    KEY `idx_rule_id` (`rule_id`),
    KEY `idx_status` (`status`),
    KEY `idx_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警历史表';

CREATE TABLE IF NOT EXISTS `metric_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `metric_name` VARCHAR(255) NOT NULL COMMENT '指标名称',
    `value` DOUBLE NOT NULL COMMENT '指标值',
    `dimensions` TEXT COMMENT '维度(JSON)',
    `timestamp` DATETIME NOT NULL COMMENT '时间戳',
    `timestamp_hour` BIGINT NOT NULL COMMENT '小时时间戳',
    `timestamp_day` BIGINT NOT NULL COMMENT '天时间戳',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_metric_name` (`metric_name`),
    KEY `idx_timestamp` (`timestamp`),
    KEY `idx_timestamp_hour` (`timestamp_hour`),
    KEY `idx_timestamp_day` (`timestamp_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标数据表';

CREATE TABLE IF NOT EXISTS `trace_spans` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `trace_id` VARCHAR(128) NOT NULL COMMENT '追踪ID',
    `span_id` VARCHAR(64) NOT NULL COMMENT 'Span ID',
    `parent_span_id` VARCHAR(64) COMMENT '父Span ID',
    `service_name` VARCHAR(255) NOT NULL COMMENT '服务名称',
    `operation_name` VARCHAR(255) NOT NULL COMMENT '操作名称',
    `duration_nanos` BIGINT NOT NULL COMMENT '持续时间(纳秒)',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `tags` TEXT COMMENT '标签(JSON)',
    `logs` TEXT COMMENT '日志(JSON)',
    `sampled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否采样',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_span_id` (`span_id`),
    KEY `idx_service_name` (`service_name`),
    KEY `idx_start_time` (`start_time`),
    KEY `idx_sampled` (`sampled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='追踪Span表';

CREATE TABLE IF NOT EXISTS `schema_version` (
    `installed_rank` INT NOT NULL,
    `version` VARCHAR(50),
    `description` VARCHAR(200) NOT NULL,
    `type` VARCHAR(20) NOT NULL,
    `script` VARCHAR(1000) NOT NULL,
    `checksum` INT,
    `installed_by` VARCHAR(100) NOT NULL,
    `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `execution_time` INT NOT NULL,
    `success` TINYINT(1) NOT NULL,
    PRIMARY KEY (`installed_rank`),
    KEY `idx_schema_version_succ` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Flyway Schema Version Table';
