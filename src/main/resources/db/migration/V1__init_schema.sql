CREATE TABLE IF NOT EXISTS `resources` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `resource_id` VARCHAR(64) NOT NULL COMMENT '资源唯一标识',
  `type` VARCHAR(32) NOT NULL COMMENT '资源类型',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '资源状态',
  `attributes` JSON COMMENT '资源属性',
  `config` JSON COMMENT '资源配置',
  `labels` JSON COMMENT '标签',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_id` (`resource_id`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源表';

CREATE TABLE IF NOT EXISTS `configs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `config_id` VARCHAR(64) NOT NULL COMMENT '配置唯一标识',
  `namespace` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '命名空间',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `parameters` JSON NOT NULL COMMENT '配置参数',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `source_type` VARCHAR(32) NOT NULL COMMENT '配置来源类型',
  `applied_at` DATETIME COMMENT '生效时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_id_version` (`config_id`, `version`),
  KEY `idx_namespace` (`namespace`),
  KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置表';

CREATE TABLE IF NOT EXISTS `run_instances` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` VARCHAR(64) NOT NULL COMMENT '运行实例ID',
  `entity_id` VARCHAR(64) NOT NULL COMMENT '关联实体ID',
  `phase` VARCHAR(32) NOT NULL COMMENT '运行阶段',
  `progress` DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '进度0-1',
  `started_at` DATETIME NOT NULL COMMENT '开始时间',
  `completed_at` DATETIME COMMENT '完成时间',
  `error_detail` TEXT COMMENT '错误详情',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_id` (`run_id`),
  KEY `idx_entity_id` (`entity_id`),
  KEY `idx_phase` (`phase`),
  KEY `idx_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS `metric_snapshots` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
  `timestamp` DATETIME NOT NULL COMMENT '快照时间',
  `metrics` JSON NOT NULL COMMENT '指标数据',
  `dimensions` JSON COMMENT '维度数据',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
  KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标快照表';

CREATE TABLE IF NOT EXISTS `sidecar_instances` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `instance_id` VARCHAR(64) NOT NULL COMMENT 'Sidecar实例ID',
  `pod_name` VARCHAR(128) NOT NULL COMMENT 'Pod名称',
  `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
  `status` VARCHAR(32) NOT NULL COMMENT '状态',
  `cpu_limit` VARCHAR(32) COMMENT 'CPU限制',
  `memory_limit` VARCHAR(32) COMMENT '内存限制',
  `config_version` INT NOT NULL DEFAULT 1 COMMENT '配置版本',
  `last_heartbeat` DATETIME COMMENT '最后心跳时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_instance_id` (`instance_id`),
  KEY `idx_namespace_pod` (`namespace`, `pod_name`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sidecar实例表';

CREATE TABLE IF NOT EXISTS `dns_records` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `domain` VARCHAR(255) NOT NULL COMMENT '域名',
  `record_type` VARCHAR(16) NOT NULL DEFAULT 'A' COMMENT '记录类型',
  `value` VARCHAR(512) NOT NULL COMMENT '记录值',
  `ttl` INT NOT NULL DEFAULT 300 COMMENT 'TTL秒',
  `upstream` VARCHAR(128) COMMENT '上游DNS',
  `cached_at` DATETIME NOT NULL COMMENT '缓存时间',
  `expires_at` DATETIME NOT NULL COMMENT '过期时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_domain_type` (`domain`, `record_type`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DNS记录表';

CREATE TABLE IF NOT EXISTS `notifications` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `notification_id` VARCHAR(64) NOT NULL COMMENT '通知ID',
  `type` VARCHAR(32) NOT NULL COMMENT '通知类型',
  `priority` INT NOT NULL DEFAULT 1 COMMENT '优先级 1-5',
  `title` VARCHAR(255) NOT NULL COMMENT '标题',
  `content` TEXT NOT NULL COMMENT '内容',
  `recipient` VARCHAR(255) NOT NULL COMMENT '接收人',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
  `sent_at` DATETIME COMMENT '发送时间',
  `error_message` TEXT COMMENT '错误信息',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_id` (`notification_id`),
  KEY `idx_priority_status` (`priority`, `status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

CREATE TABLE IF NOT EXISTS `notification_suppressions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `rule_name` VARCHAR(128) NOT NULL COMMENT '抑制规则名称',
  `alert_key` VARCHAR(255) NOT NULL COMMENT '告警键',
  `window_start` DATETIME NOT NULL COMMENT '窗口开始',
  `window_end` DATETIME NOT NULL COMMENT '窗口结束',
  `count` INT NOT NULL DEFAULT 0 COMMENT '计数',
  `suppressed` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否抑制',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_alert_key_window` (`alert_key`, `window_start`, `window_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知抑制表';

CREATE TABLE IF NOT EXISTS `flow_policies` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `policy_id` VARCHAR(64) NOT NULL COMMENT '策略ID',
  `type` VARCHAR(32) NOT NULL COMMENT '策略类型: canary/blue-green/mirroring/circuit-breaker',
  `name` VARCHAR(128) NOT NULL COMMENT '策略名称',
  `config` JSON NOT NULL COMMENT '策略配置',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_id` (`policy_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流量策略表';

CREATE TABLE IF NOT EXISTS `events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `event_id` VARCHAR(64) NOT NULL COMMENT '事件ID',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合根ID',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型',
  `version` INT NOT NULL COMMENT '版本号',
  `payload` JSON NOT NULL COMMENT '事件载荷',
  `metadata` JSON COMMENT '元数据',
  `timestamp` DATETIME NOT NULL COMMENT '事件时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_aggregate` (`aggregate_type`, `aggregate_id`, `version`),
  KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件表';

CREATE TABLE IF NOT EXISTS `snapshots` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合根ID',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
  `version` INT NOT NULL COMMENT '版本号',
  `state` JSON NOT NULL COMMENT '状态快照',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
  KEY `idx_aggregate` (`aggregate_type`, `aggregate_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='快照表';

CREATE TABLE IF NOT EXISTS `commands` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `command_id` VARCHAR(64) NOT NULL COMMENT '命令ID',
  `command_type` VARCHAR(64) NOT NULL COMMENT '命令类型',
  `aggregate_id` VARCHAR(64) COMMENT '聚合根ID',
  `payload` JSON NOT NULL COMMENT '命令载荷',
  `metadata` JSON COMMENT '元数据',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
  `result` JSON COMMENT '执行结果',
  `timestamp` DATETIME NOT NULL COMMENT '命令时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_command_id` (`command_id`),
  KEY `idx_command_type` (`command_type`),
  KEY `idx_status` (`status`),
  KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='命令表';

CREATE TABLE IF NOT EXISTS `audit_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `audit_id` VARCHAR(64) NOT NULL COMMENT '审计ID',
  `user_id` VARCHAR(64) COMMENT '用户ID',
  `operation` VARCHAR(128) NOT NULL COMMENT '操作',
  `resource_type` VARCHAR(64) COMMENT '资源类型',
  `resource_id` VARCHAR(64) COMMENT '资源ID',
  `old_value` JSON COMMENT '旧值',
  `new_value` JSON COMMENT '新值',
  `ip_address` VARCHAR(64) COMMENT 'IP地址',
  `user_agent` VARCHAR(512) COMMENT 'User Agent',
  `timestamp` DATETIME NOT NULL COMMENT '时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_id` (`audit_id`),
  KEY `idx_user_operation` (`user_id`, `operation`),
  KEY `idx_resource` (`resource_type`, `resource_id`),
  KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';
