CREATE TABLE IF NOT EXISTS `sys_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
  `namespace` VARCHAR(128) NOT NULL DEFAULT 'default' COMMENT '命名空间',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `config_key` VARCHAR(255) NOT NULL COMMENT '配置键',
  `config_value` TEXT COMMENT '配置值',
  `description` VARCHAR(500) COMMENT '描述',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `applied_at` DATETIME COMMENT '生效时间',
  `created_by` VARCHAR(64) COMMENT '创建人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_namespace_version` (`config_id`, `namespace`, `version`),
  KEY `idx_namespace_key` (`namespace`, `config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置表';

CREATE TABLE IF NOT EXISTS `sys_config_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `config_id` VARCHAR(64) NOT NULL,
  `namespace` VARCHAR(128) NOT NULL,
  `version` INT NOT NULL,
  `config_key` VARCHAR(255) NOT NULL,
  `config_value` TEXT,
  `description` VARCHAR(500),
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `rollback_from_version` INT COMMENT '回滚来源版本',
  `rolled_back_at` DATETIME COMMENT '回滚时间',
  `rolled_back_by` VARCHAR(64) COMMENT '回滚人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_config_id` (`config_id`),
  KEY `idx_namespace_version` (`namespace`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置历史表';

CREATE TABLE IF NOT EXISTS `sys_device` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `device_id` VARCHAR(64) NOT NULL COMMENT '设备ID',
  `device_name` VARCHAR(128) COMMENT '设备名称',
  `device_type` VARCHAR(64) COMMENT '设备类型',
  `protocol_type` VARCHAR(32) COMMENT '协议类型',
  `status` VARCHAR(32) NOT NULL DEFAULT 'inactive' COMMENT '状态:inactive,active,offline,fault',
  `auth_token` VARCHAR(255) COMMENT '认证令牌',
  `auth_secret` VARCHAR(255) COMMENT '认证密钥',
  `metadata` JSON COMMENT '元数据',
  `last_heartbeat_at` DATETIME COMMENT '最后心跳时间',
  `activated_at` DATETIME COMMENT '激活时间',
  `created_by` VARCHAR(64),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_id` (`device_id`),
  KEY `idx_status` (`status`),
  KEY `idx_device_type` (`device_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备表';

CREATE TABLE IF NOT EXISTS `edge_inference_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` VARCHAR(64) NOT NULL,
  `device_id` VARCHAR(64) NOT NULL,
  `model_id` VARCHAR(64) NOT NULL,
  `model_version` VARCHAR(32),
  `input_data` TEXT COMMENT '输入数据',
  `input_path` VARCHAR(500) COMMENT '输入文件路径',
  `output_data` TEXT COMMENT '输出结果',
  `output_path` VARCHAR(500) COMMENT '输出文件路径',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态:pending,processing,completed,failed',
  `progress` DECIMAL(5,2) DEFAULT 0 COMMENT '进度0-100',
  `priority` INT DEFAULT 5 COMMENT '优先级1-10',
  `started_at` DATETIME,
  `completed_at` DATETIME,
  `error_detail` TEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_device_status` (`device_id`, `status`),
  KEY `idx_status_priority` (`status`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='边缘推理任务表';

CREATE TABLE IF NOT EXISTS `data_aggregation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `aggregation_id` VARCHAR(64) NOT NULL,
  `device_id` VARCHAR(64) NOT NULL,
  `stream_id` VARCHAR(64) NOT NULL,
  `window_start` DATETIME NOT NULL,
  `window_end` DATETIME NOT NULL,
  `aggregation_type` VARCHAR(32) NOT NULL COMMENT '聚合类型:sum,avg,count,min,max',
  `metric_name` VARCHAR(128) NOT NULL,
  `metric_value` DECIMAL(20,6) NOT NULL,
  `record_count` INT DEFAULT 1,
  `metadata` JSON,
  `uploaded` TINYINT DEFAULT 0 COMMENT '是否已上传',
  `uploaded_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aggregation_window` (`aggregation_id`, `window_start`, `window_end`),
  KEY `idx_device_stream` (`device_id`, `stream_id`),
  KEY `idx_uploaded` (`uploaded`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据聚合表';

CREATE TABLE IF NOT EXISTS `storage_object` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `object_id` VARCHAR(64) NOT NULL,
  `bucket_name` VARCHAR(128) NOT NULL,
  `object_key` VARCHAR(500) NOT NULL,
  `object_name` VARCHAR(255),
  `content_type` VARCHAR(128),
  `content_length` BIGINT DEFAULT 0,
  `etag` VARCHAR(128),
  `provider` VARCHAR(32) NOT NULL COMMENT '存储提供者:s3,minio,local',
  `metadata` JSON,
  `tags` VARCHAR(500),
  `created_by` VARCHAR(64),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_id` (`object_id`),
  KEY `idx_bucket_key` (`bucket_name`, `object_key`),
  KEY `idx_provider` (`provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='存储对象表';

CREATE TABLE IF NOT EXISTS `notification` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `notification_id` VARCHAR(64) NOT NULL,
  `template_code` VARCHAR(64),
  `channel_type` VARCHAR(32) NOT NULL COMMENT '渠道:sms,email,webhook,app',
  `recipient` VARCHAR(500) NOT NULL COMMENT '接收人',
  `subject` VARCHAR(255),
  `content` TEXT NOT NULL,
  `variables` JSON COMMENT '模板变量',
  `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态:pending,sent,failed',
  `retry_count` INT DEFAULT 0,
  `max_retries` INT DEFAULT 3,
  `sent_at` DATETIME,
  `error_detail` TEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_id` (`notification_id`),
  KEY `idx_channel_status` (`channel_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知表';

CREATE TABLE IF NOT EXISTS `notification_template` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `template_code` VARCHAR(64) NOT NULL,
  `template_name` VARCHAR(128),
  `channel_type` VARCHAR(32) NOT NULL,
  `subject_template` VARCHAR(500),
  `content_template` TEXT NOT NULL,
  `variables_schema` JSON COMMENT '变量定义schema',
  `enabled` TINYINT DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_code_channel` (`template_code`, `channel_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知模板表';

CREATE TABLE IF NOT EXISTS `offline_cache` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `cache_key` VARCHAR(255) NOT NULL,
  `cache_value` MEDIUMTEXT COMMENT '缓存数据(JSON)',
  `data_type` VARCHAR(64) COMMENT '数据类型',
  `expire_at` DATETIME,
  `synced` TINYINT DEFAULT 0 COMMENT '是否已同步',
  `sync_attempts` INT DEFAULT 0,
  `last_sync_attempt_at` DATETIME,
  `sync_error` TEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cache_key` (`cache_key`),
  KEY `idx_synced` (`synced`),
  KEY `idx_expire_at` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线缓存表';

CREATE TABLE IF NOT EXISTS `protocol_adapter` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `adapter_id` VARCHAR(64) NOT NULL,
  `adapter_name` VARCHAR(128),
  `protocol_type` VARCHAR(32) NOT NULL COMMENT '协议类型:modbus,mqtt,opcua,http',
  `driver_class` VARCHAR(500) NOT NULL COMMENT '驱动类',
  `config_schema` JSON COMMENT '配置schema',
  `enabled` TINYINT DEFAULT 1,
  `version` VARCHAR(32),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adapter_id` (`adapter_id`),
  KEY `idx_protocol_type` (`protocol_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协议适配器表';

CREATE TABLE IF NOT EXISTS `device_protocol_binding` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `device_id` VARCHAR(64) NOT NULL,
  `adapter_id` VARCHAR(64) NOT NULL,
  `connection_config` JSON COMMENT '连接配置',
  `status` VARCHAR(32) DEFAULT 'disconnected' COMMENT '连接状态',
  `last_connected_at` DATETIME,
  `last_disconnected_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_adapter` (`device_id`, `adapter_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备协议绑定表';

CREATE TABLE IF NOT EXISTS `sys_resource` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `resource_id` VARCHAR(64) NOT NULL,
  `type` VARCHAR(64) NOT NULL,
  `status` VARCHAR(32) NOT NULL DEFAULT 'active',
  `attributes` JSON,
  `labels` JSON,
  `created_by` VARCHAR(64),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_id` (`resource_id`),
  KEY `idx_type_status` (`type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源表';
