-- ChaosLab Core Schema Migration V1
-- Core Entity Tables

CREATE TABLE `core_entity` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `ent_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '实体业务ID',
    `type` VARCHAR(64) NOT NULL COMMENT '实体类型',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `attributes` JSON COMMENT '属性JSON',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_type_status` (`type`, `status`),
    INDEX `idx_ent_id` (`ent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核心实体表';

CREATE TABLE `config_definition` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `config_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '配置ID',
    `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `parameters` JSON COMMENT '参数JSON',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `applied_at` DATETIME COMMENT '应用时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version_lock` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_namespace` (`namespace`),
    INDEX `idx_config_id` (`config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置定义表';

CREATE TABLE `run_instance` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '运行实例ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    `phase` VARCHAR(32) NOT NULL DEFAULT 'initializing' COMMENT '执行阶段',
    `progress` DECIMAL(5,4) NOT NULL DEFAULT 0.0 COMMENT '进度0-1',
    `started_at` DATETIME COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `error_detail` TEXT COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_entity_id` (`entity_id`),
    INDEX `idx_phase` (`phase`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE `stats_snapshot` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `snapshot_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '快照ID',
    `timestamp` DATETIME NOT NULL COMMENT '快照时间',
    `metrics` JSON COMMENT '指标JSON',
    `dimensions` JSON COMMENT '维度JSON',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统计快照表';

-- Sidecar Module Tables
CREATE TABLE `sidecar_injection_policy` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `policy_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '策略ID',
    `name` VARCHAR(128) NOT NULL COMMENT '策略名称',
    `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
    `selector` JSON COMMENT '选择器JSON',
    `sidecar_image` VARCHAR(256) NOT NULL COMMENT 'Sidecar镜像',
    `resources` JSON COMMENT '资源限制JSON',
    `injection_mode` VARCHAR(32) NOT NULL DEFAULT 'automatic' COMMENT '注入模式',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_namespace` (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sidecar注入策略表';

CREATE TABLE `sidecar_instance` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `instance_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '实例ID',
    `policy_id` VARCHAR(64) NOT NULL COMMENT '关联策略ID',
    `target_pod` VARCHAR(128) NOT NULL COMMENT '目标Pod',
    `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
    `status` VARCHAR(32) NOT NULL DEFAULT 'injecting' COMMENT '状态',
    `config_hash` VARCHAR(64) COMMENT '配置哈希',
    `last_heartbeat` DATETIME COMMENT '最后心跳时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_policy_id` (`policy_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sidecar实例表';

CREATE TABLE `sidecar_config` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `config_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '配置ID',
    `instance_id` VARCHAR(64) NOT NULL COMMENT '关联实例ID',
    `config_data` JSON COMMENT '配置数据JSON',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `applied` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已应用',
    `applied_at` DATETIME COMMENT '应用时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version_lock` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_instance_id` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Sidecar配置表';

-- mTLS Module Tables
CREATE TABLE `mtls_certificate` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `cert_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '证书ID',
    `common_name` VARCHAR(256) NOT NULL COMMENT '通用名称',
    `serial_number` VARCHAR(128) UNIQUE COMMENT '序列号',
    `certificate_pem` TEXT NOT NULL COMMENT '证书PEM',
    `private_key_pem` TEXT COMMENT '私钥PEM',
    `issuer` VARCHAR(256) COMMENT '颁发者',
    `not_before` DATETIME NOT NULL COMMENT '生效时间',
    `not_after` DATETIME NOT NULL COMMENT '过期时间',
    `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    `rotation_policy_id` VARCHAR(64) COMMENT '轮转策略ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_status` (`status`),
    INDEX `idx_not_after` (`not_after`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='mTLS证书表';

CREATE TABLE `mtls_rotation_policy` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `policy_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '策略ID',
    `name` VARCHAR(128) NOT NULL COMMENT '策略名称',
    `validity_days` INT NOT NULL DEFAULT 365 COMMENT '有效期天数',
    `rotation_days` INT NOT NULL DEFAULT 30 COMMENT '轮转提前天数',
    `auto_rotate` TINYINT NOT NULL DEFAULT 1 COMMENT '自动轮转',
    `key_algorithm` VARCHAR(32) NOT NULL DEFAULT 'RSA' COMMENT '密钥算法',
    `key_size` INT NOT NULL DEFAULT 2048 COMMENT '密钥长度',
    `signature_algorithm` VARCHAR(64) NOT NULL DEFAULT 'SHA256withRSA' COMMENT '签名算法',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='mTLS轮转策略表';

CREATE TABLE `mtls_revocation_list` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `revocation_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '吊销ID',
    `cert_id` VARCHAR(64) NOT NULL COMMENT '证书ID',
    `serial_number` VARCHAR(128) NOT NULL COMMENT '证书序列号',
    `reason` VARCHAR(256) COMMENT '吊销原因',
    `revoked_at` DATETIME NOT NULL COMMENT '吊销时间',
    `revoked_by` VARCHAR(128) COMMENT '吊销人',
    `crl_number` INT COMMENT 'CRL编号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_cert_id` (`cert_id`),
    INDEX `idx_serial_number` (`serial_number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='mTLS吊销列表';

-- DNS Module Tables
CREATE TABLE `dns_upstream` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `upstream_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '上游ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `address` VARCHAR(128) NOT NULL COMMENT '地址',
    `protocol` VARCHAR(32) NOT NULL DEFAULT 'udp' COMMENT '协议',
    `timeout_ms` INT NOT NULL DEFAULT 5000 COMMENT '超时毫秒',
    `priority` INT NOT NULL DEFAULT 100 COMMENT '优先级',
    `health_check_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '健康检查启用',
    `status` VARCHAR(32) NOT NULL DEFAULT 'healthy' COMMENT '状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DNS上游服务器表';

CREATE TABLE `dns_resolution_policy` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `policy_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '策略ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `domain_pattern` VARCHAR(256) NOT NULL COMMENT '域名模式',
    `strategy` VARCHAR(32) NOT NULL DEFAULT 'round_robin' COMMENT '解析策略',
    `upstream_ids` JSON COMMENT '上游ID列表JSON',
    `cache_ttl` INT COMMENT '缓存TTL秒',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_domain_pattern` (`domain_pattern`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DNS解析策略表';

CREATE TABLE `dns_cache` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `cache_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '缓存ID',
    `query_key` VARCHAR(256) NOT NULL UNIQUE COMMENT '查询键',
    `query_type` VARCHAR(16) NOT NULL COMMENT '查询类型',
    `response_data` JSON COMMENT '响应数据JSON',
    `ttl` INT NOT NULL COMMENT 'TTL秒',
    `expires_at` DATETIME NOT NULL COMMENT '过期时间',
    `hit_count` INT NOT NULL DEFAULT 0 COMMENT '命中次数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_query_key` (`query_key`),
    INDEX `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='DNS缓存表';

-- Traffic Control Module Tables
CREATE TABLE `traffic_strategy` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `strategy_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '策略ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `type` VARCHAR(32) NOT NULL COMMENT '策略类型canary/bluegreen/mirror/circuit',
    `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
    `selector` JSON COMMENT '选择器JSON',
    `config` JSON NOT NULL COMMENT '配置JSON',
    `enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_type` (`type`),
    INDEX `idx_namespace` (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流量策略表';

CREATE TABLE `traffic_strategy_run` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '运行ID',
    `strategy_id` VARCHAR(64) NOT NULL COMMENT '策略ID',
    `phase` VARCHAR(32) NOT NULL DEFAULT 'initializing' COMMENT '阶段',
    `progress` DECIMAL(5,4) NOT NULL DEFAULT 0.0 COMMENT '进度',
    `traffic_percentage` INT COMMENT '流量百分比',
    `started_at` DATETIME COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `metrics` JSON COMMENT '指标JSON',
    `error_detail` TEXT COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_strategy_id` (`strategy_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流量策略运行表';

-- Image Distribution Module Tables
CREATE TABLE `image_repository` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `repo_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '仓库ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `registry_url` VARCHAR(256) NOT NULL COMMENT 'Registry地址',
    `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
    `auth_type` VARCHAR(32) NOT NULL DEFAULT 'none' COMMENT '认证类型',
    `username` VARCHAR(128) COMMENT '用户名',
    `password_encrypted` VARCHAR(256) COMMENT '加密密码',
    `tls_verify` TINYINT NOT NULL DEFAULT 1 COMMENT 'TLS验证',
    `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='镜像仓库表';

CREATE TABLE `image_layer` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `layer_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '层ID',
    `digest` VARCHAR(128) NOT NULL UNIQUE COMMENT '摘要',
    `size_bytes` BIGINT NOT NULL COMMENT '大小字节',
    `media_type` VARCHAR(128) COMMENT '媒体类型',
    `blob_path` VARCHAR(512) COMMENT 'Blob路径',
    `downloaded` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已下载',
    `downloaded_at` DATETIME COMMENT '下载时间',
    `p2p_seeders` INT NOT NULL DEFAULT 0 COMMENT 'P2P种子数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_digest` (`digest`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='镜像分层表';

CREATE TABLE `image_sync_task` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '任务ID',
    `source_repo_id` VARCHAR(64) NOT NULL COMMENT '源仓库ID',
    `target_repo_id` VARCHAR(64) NOT NULL COMMENT '目标仓库ID',
    `image_reference` VARCHAR(512) NOT NULL COMMENT '镜像引用',
    `strategy` VARCHAR(32) NOT NULL DEFAULT 'layered' COMMENT '同步策略',
    `p2p_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT 'P2P加速启用',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `progress` DECIMAL(5,4) NOT NULL DEFAULT 0.0 COMMENT '进度',
    `total_layers` INT COMMENT '总层数',
    `completed_layers` INT COMMENT '已完成层数',
    `started_at` DATETIME COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `error_detail` TEXT COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='镜像同步任务表';

-- Event Store Module Tables
CREATE TABLE `event_log` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `event_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '事件ID',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合根ID',
    `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
    `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型',
    `event_version` INT NOT NULL DEFAULT 1 COMMENT '事件版本',
    `payload` JSON NOT NULL COMMENT '事件负载',
    `metadata` JSON COMMENT '元数据',
    `sequence_number` BIGINT NOT NULL COMMENT '序列号',
    `timestamp` DATETIME NOT NULL COMMENT '事件时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_aggregate` (`aggregate_id`, `aggregate_type`),
    INDEX `idx_sequence` (`sequence_number`),
    INDEX `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件日志表';

CREATE TABLE `event_snapshot` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `snapshot_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '快照ID',
    `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合根ID',
    `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
    `state` JSON NOT NULL COMMENT '状态JSON',
    `sequence_number` BIGINT NOT NULL COMMENT '截止序列号',
    `version` INT NOT NULL DEFAULT 1 COMMENT '快照版本',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_aggregate_seq` (`aggregate_id`, `sequence_number`),
    INDEX `idx_aggregate` (`aggregate_id`, `aggregate_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件快照表';

CREATE TABLE `event_projection` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `projection_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '投影ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
    `handler_config` JSON NOT NULL COMMENT '处理器配置',
    `last_sequence` BIGINT NOT NULL DEFAULT 0 COMMENT '最后处理序列号',
    `status` VARCHAR(32) NOT NULL DEFAULT 'running' COMMENT '状态',
    `rebuild_in_progress` TINYINT NOT NULL DEFAULT 0 COMMENT '重建中',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事件投影表';

-- Fault Injection Module Tables
CREATE TABLE `fault_scenario` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `scenario_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '场景ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `description` VARCHAR(512) COMMENT '描述',
    `fault_type` VARCHAR(64) NOT NULL COMMENT '故障类型',
    `scope` JSON NOT NULL COMMENT '注入范围JSON',
    `config` JSON NOT NULL COMMENT '故障配置JSON',
    `duration_ms` BIGINT COMMENT '持续时间毫秒',
    `auto_rollback` TINYINT NOT NULL DEFAULT 1 COMMENT '自动回滚',
    `rollback_timeout_ms` BIGINT NOT NULL DEFAULT 300000 COMMENT '回滚超时',
    `tags` JSON COMMENT '标签JSON',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_fault_type` (`fault_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='故障场景表';

CREATE TABLE `fault_injection_run` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '运行ID',
    `scenario_id` VARCHAR(64) NOT NULL COMMENT '场景ID',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `phase` VARCHAR(32) NOT NULL DEFAULT 'preparing' COMMENT '阶段',
    `targets` JSON COMMENT '目标列表JSON',
    `started_at` DATETIME COMMENT '开始时间',
    `ended_at` DATETIME COMMENT '结束时间',
    `rollback_triggered` TINYINT NOT NULL DEFAULT 0 COMMENT '是否触发回滚',
    `rollback_reason` VARCHAR(512) COMMENT '回滚原因',
    `rollback_completed_at` DATETIME COMMENT '回滚完成时间',
    `metrics` JSON COMMENT '指标JSON',
    `error_detail` TEXT COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_scenario_id` (`scenario_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='故障注入运行表';

-- Command & Audit Module Tables
CREATE TABLE `command_log` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `command_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '命令ID',
    `command_type` VARCHAR(64) NOT NULL COMMENT '命令类型',
    `aggregate_id` VARCHAR(64) COMMENT '聚合根ID',
    `payload` JSON NOT NULL COMMENT '命令负载',
    `metadata` JSON COMMENT '元数据',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `result` JSON COMMENT '结果JSON',
    `error_detail` TEXT COMMENT '错误详情',
    `executed_at` DATETIME COMMENT '执行时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `created_by` VARCHAR(128) COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_command_type` (`command_type`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='命令日志表';

CREATE TABLE `audit_log` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `audit_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '审计ID',
    `command_id` VARCHAR(64) COMMENT '关联命令ID',
    `event_id` VARCHAR(64) COMMENT '关联事件ID',
    `action` VARCHAR(128) NOT NULL COMMENT '操作',
    `actor` VARCHAR(128) NOT NULL COMMENT '操作者',
    `resource_type` VARCHAR(64) COMMENT '资源类型',
    `resource_id` VARCHAR(64) COMMENT '资源ID',
    `old_value` JSON COMMENT '旧值',
    `new_value` JSON COMMENT '新值',
    `ip_address` VARCHAR(64) COMMENT 'IP地址',
    `user_agent` VARCHAR(512) COMMENT '用户代理',
    `compliance_tags` JSON COMMENT '合规标签',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_command_id` (`command_id`),
    INDEX `idx_event_id` (`event_id`),
    INDEX `idx_actor` (`actor`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';

CREATE TABLE `compliance_report` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '主键ID',
    `report_id` VARCHAR(64) NOT NULL UNIQUE COMMENT '报告ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `type` VARCHAR(64) NOT NULL COMMENT '报告类型',
    `period_start` DATETIME NOT NULL COMMENT '开始时间',
    `period_end` DATETIME NOT NULL COMMENT '结束时间',
    `filters` JSON COMMENT '过滤条件',
    `summary` JSON COMMENT '摘要JSON',
    `details` JSON COMMENT '详情JSON',
    `generated_by` VARCHAR(128) COMMENT '生成人',
    `generated_at` DATETIME COMMENT '生成时间',
    `status` VARCHAR(32) NOT NULL DEFAULT 'generating' COMMENT '状态',
    `file_path` VARCHAR(512) COMMENT '文件路径',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    INDEX `idx_type` (`type`),
    INDEX `idx_period` (`period_start`, `period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='合规报告表';
