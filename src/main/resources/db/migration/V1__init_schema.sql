-- =====================================================================
-- Metric Platform Database Schema
-- =====================================================================

CREATE TABLE IF NOT EXISTS `sys_entity` (
  `id` VARCHAR(64) NOT NULL COMMENT '实体ID',
  `type` VARCHAR(64) NOT NULL COMMENT '实体类型',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
  `attributes` JSON NULL COMMENT '属性',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实体表';

CREATE TABLE IF NOT EXISTS `sys_config` (
  `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
  `namespace` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '命名空间',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `parameters` JSON NULL COMMENT '参数',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `applied_at` DATETIME NULL COMMENT '应用时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`config_id`),
  UNIQUE KEY `uk_namespace_version` (`namespace`, `version`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置表';

CREATE TABLE IF NOT EXISTS `sys_run_instance` (
  `run_id` VARCHAR(64) NOT NULL COMMENT '运行实例ID',
  `entity_id` VARCHAR(64) NOT NULL COMMENT '实体ID',
  `phase` VARCHAR(32) NOT NULL DEFAULT 'initializing' COMMENT '阶段',
  `progress` DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '进度',
  `started_at` DATETIME NULL COMMENT '开始时间',
  `completed_at` DATETIME NULL COMMENT '完成时间',
  `error_detail` TEXT NULL COMMENT '错误详情',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`run_id`),
  KEY `idx_entity_id` (`entity_id`),
  KEY `idx_phase` (`phase`),
  KEY `idx_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS `sys_metrics_snapshot` (
  `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
  `timestamp` DATETIME NOT NULL COMMENT '时间戳',
  `metrics` JSON NULL COMMENT '指标',
  `dimensions` JSON NULL COMMENT '维度',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`snapshot_id`),
  KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标快照表';

CREATE TABLE IF NOT EXISTS `sys_log_level` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  `logger_name` VARCHAR(255) NOT NULL COMMENT 'Logger名称',
  `level` VARCHAR(16) NOT NULL COMMENT '日志级别',
  `effective` TINYINT NOT NULL DEFAULT 1 COMMENT '是否生效',
  `expire_at` DATETIME NULL COMMENT '过期时间',
  `created_by` VARCHAR(64) NULL COMMENT '创建人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY `uk_logger_name` (`logger_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日志级别配置表';

CREATE TABLE IF NOT EXISTS `sys_scheduled_task` (
  `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
  `task_name` VARCHAR(128) NOT NULL COMMENT '任务名称',
  `task_type` VARCHAR(32) NOT NULL COMMENT '任务类型',
  `cron_expression` VARCHAR(64) NULL COMMENT 'Cron表达式',
  `dependencies` JSON NULL COMMENT '依赖任务ID列表',
  `parameters` JSON NULL COMMENT '任务参数',
  `status` VARCHAR(32) NOT NULL DEFAULT 'stopped' COMMENT '状态',
  `last_run_at` DATETIME NULL COMMENT '上次运行时间',
  `next_run_at` DATETIME NULL COMMENT '下次运行时间',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `timeout` BIGINT NOT NULL DEFAULT 3600000 COMMENT '超时时间(ms)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`task_id`),
  KEY `idx_status` (`status`),
  KEY `idx_next_run_at` (`next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度任务表';

CREATE TABLE IF NOT EXISTS `sys_data_lifecycle` (
  `lifecycle_id` VARCHAR(64) NOT NULL COMMENT '生命周期ID',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `hot_days` INT NOT NULL DEFAULT 7 COMMENT '热数据天数',
  `warm_days` INT NOT NULL DEFAULT 30 COMMENT '温数据天数',
  `cold_days` INT NOT NULL DEFAULT 90 COMMENT '冷数据天数',
  `archive_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否归档',
  `cleanup_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否清理',
  `archive_table_suffix` VARCHAR(32) NOT NULL DEFAULT '_archive' COMMENT '归档表后缀',
  `last_migrate_at` DATETIME NULL COMMENT '上次迁移时间',
  `last_archive_at` DATETIME NULL COMMENT '上次归档时间',
  `last_cleanup_at` DATETIME NULL COMMENT '上次清理时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`lifecycle_id`),
  UNIQUE KEY `uk_table_name` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据生命周期配置表';

CREATE TABLE IF NOT EXISTS `sys_notification_template` (
  `template_id` VARCHAR(64) NOT NULL COMMENT '模板ID',
  `template_name` VARCHAR(128) NOT NULL COMMENT '模板名称',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道: email/sms/webhook/dingtalk/wechat',
  `subject_template` VARCHAR(255) NULL COMMENT '主题模板',
  `content_template` TEXT NOT NULL COMMENT '内容模板',
  `variables` JSON NULL COMMENT '变量定义',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`template_id`),
  KEY `idx_channel` (`channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知模板表';

CREATE TABLE IF NOT EXISTS `sys_notification_record` (
  `record_id` VARCHAR(64) NOT NULL COMMENT '记录ID',
  `template_id` VARCHAR(64) NULL COMMENT '模板ID',
  `channel` VARCHAR(32) NOT NULL COMMENT '渠道',
  `receiver` VARCHAR(512) NOT NULL COMMENT '接收人',
  `subject` VARCHAR(255) NULL COMMENT '主题',
  `content` TEXT NOT NULL COMMENT '内容',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
  `error_message` TEXT NULL COMMENT '错误信息',
  `sent_at` DATETIME NULL COMMENT '发送时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`record_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知记录表';

CREATE TABLE IF NOT EXISTS `sys_metadata_source` (
  `source_id` VARCHAR(64) NOT NULL COMMENT '数据源ID',
  `source_name` VARCHAR(128) NOT NULL COMMENT '数据源名称',
  `source_type` VARCHAR(32) NOT NULL COMMENT '数据源类型: mysql/postgresql/oracle/kafka',
  `connection_config` JSON NOT NULL COMMENT '连接配置',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
  `last_scan_at` DATETIME NULL COMMENT '上次扫描时间',
  `scan_interval` BIGINT NOT NULL DEFAULT 86400000 COMMENT '扫描间隔(ms)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`source_id`),
  KEY `idx_source_type` (`source_type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='元数据数据源表';

CREATE TABLE IF NOT EXISTS `sys_metadata_schema` (
  `schema_id` VARCHAR(64) NOT NULL COMMENT 'Schema ID',
  `source_id` VARCHAR(64) NOT NULL COMMENT '数据源ID',
  `database_name` VARCHAR(128) NOT NULL COMMENT '数据库名',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `description` VARCHAR(512) NULL COMMENT '描述',
  `columns` JSON NOT NULL COMMENT '列定义',
  `statistics` JSON NULL COMMENT '统计信息',
  `sample_data` JSON NULL COMMENT '样例数据',
  `row_count` BIGINT NOT NULL DEFAULT 0 COMMENT '行数',
  `data_size` BIGINT NOT NULL DEFAULT 0 COMMENT '数据大小(字节)',
  `collected_at` DATETIME NOT NULL COMMENT '采集时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`schema_id`),
  UNIQUE KEY `uk_source_db_table` (`source_id`, `database_name`, `table_name`),
  KEY `idx_collected_at` (`collected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='元数据Schema表';

CREATE TABLE IF NOT EXISTS `sys_vector_index` (
  `index_id` VARCHAR(64) NOT NULL COMMENT '索引ID',
  `index_name` VARCHAR(128) NOT NULL COMMENT '索引名称',
  `vector_dimension` INT NOT NULL COMMENT '向量维度',
  `index_type` VARCHAR(32) NOT NULL DEFAULT 'hnsw' COMMENT '索引类型: hnsw/ivfflat',
  `metric_type` VARCHAR(32) NOT NULL DEFAULT 'cosine' COMMENT '度量类型: cosine/euclidean/dot_product',
  `index_params` JSON NULL COMMENT '索引参数',
  `status` VARCHAR(32) NOT NULL DEFAULT 'creating' COMMENT '状态',
  `vector_count` BIGINT NOT NULL DEFAULT 0 COMMENT '向量数量',
  `index_path` VARCHAR(512) NULL COMMENT '索引路径',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`index_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量索引表';

CREATE TABLE IF NOT EXISTS `sys_cdc_connector` (
  `connector_id` VARCHAR(64) NOT NULL COMMENT '连接器ID',
  `connector_name` VARCHAR(128) NOT NULL COMMENT '连接器名称',
  `source_type` VARCHAR(32) NOT NULL COMMENT '数据源类型: mysql/postgresql',
  `source_config` JSON NOT NULL COMMENT '数据源配置',
  `output_adapter` VARCHAR(32) NOT NULL DEFAULT 'kafka' COMMENT '输出适配器: kafka/redis/file',
  `output_config` JSON NULL COMMENT '输出配置',
  `server_id` VARCHAR(64) NOT NULL COMMENT 'Debezium Server ID',
  `status` VARCHAR(32) NOT NULL DEFAULT 'stopped' COMMENT '状态',
  `last_event_at` DATETIME NULL COMMENT '最后事件时间',
  `event_count` BIGINT NOT NULL DEFAULT 0 COMMENT '事件数量',
  `error_message` TEXT NULL COMMENT '错误信息',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`connector_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CDC连接器表';

CREATE TABLE IF NOT EXISTS `sys_gateway_route` (
  `route_id` VARCHAR(64) NOT NULL COMMENT '路由ID',
  `path` VARCHAR(255) NOT NULL COMMENT '路径',
  `target_url` VARCHAR(512) NOT NULL COMMENT '目标URL',
  `auth_required` TINYINT NOT NULL DEFAULT 1 COMMENT '是否需要认证',
  `rate_limit_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否限流',
  `rate_limit_capacity` INT NULL COMMENT '限流容量',
  `rate_limit_refill` INT NULL COMMENT '限流补充量',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`route_id`),
  KEY `idx_path` (`path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网关路由表';

CREATE TABLE IF NOT EXISTS `sys_api_key` (
  `key_id` VARCHAR(64) NOT NULL COMMENT 'Key ID',
  `api_key` VARCHAR(128) NOT NULL COMMENT 'API Key',
  `secret_key` VARCHAR(255) NOT NULL COMMENT 'Secret Key',
  `name` VARCHAR(128) NOT NULL COMMENT '名称',
  `permissions` JSON NULL COMMENT '权限列表',
  `rate_limit_capacity` INT NOT NULL DEFAULT 1000 COMMENT '限流容量',
  `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
  `expire_at` DATETIME NULL COMMENT '过期时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`key_id`),
  UNIQUE KEY `uk_api_key` (`api_key`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API密钥表';

CREATE TABLE IF NOT EXISTS `sys_resource` (
  `id` VARCHAR(64) NOT NULL COMMENT '资源ID',
  `type` VARCHAR(64) NOT NULL COMMENT '资源类型',
  `config` JSON NULL COMMENT '配置',
  `labels` JSON NULL COMMENT '标签',
  `status` VARCHAR(32) NOT NULL DEFAULT 'provisioning' COMMENT '状态',
  `progress` DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '进度',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源表';
