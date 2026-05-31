-- ============================================
-- TaskFlow Platform - Initial Schema
-- Version: 1.0.0
-- Description: 核心表结构初始化
-- ============================================

-- 租户表
CREATE TABLE IF NOT EXISTS `tenant` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `name` VARCHAR(128) NOT NULL COMMENT '租户名称',
    `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    `plan_type` VARCHAR(32) DEFAULT 'standard' COMMENT '套餐类型',
    `expire_at` DATETIME DEFAULT NULL COMMENT '过期时间',
    `config` JSON DEFAULT NULL COMMENT '个性化配置',
    `quota` JSON DEFAULT NULL COMMENT '资源配额',
    `contact_email` VARCHAR(128) DEFAULT NULL COMMENT '联系邮箱',
    `contact_phone` VARCHAR(32) DEFAULT NULL COMMENT '联系电话',
    `address` VARCHAR(256) DEFAULT NULL COMMENT '地址',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户表';

-- 资源表
CREATE TABLE IF NOT EXISTS `resource` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `resource_id` VARCHAR(64) NOT NULL COMMENT '资源ID',
    `type` VARCHAR(64) NOT NULL COMMENT '资源类型',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `name` VARCHAR(128) DEFAULT NULL COMMENT '名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `attributes` JSON DEFAULT NULL COMMENT '属性',
    `labels` JSON DEFAULT NULL COMMENT '标签',
    `config_id` VARCHAR(64) DEFAULT NULL COMMENT '配置ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_resource` (`tenant_id`, `resource_id`),
    KEY `idx_tenant_type` (`tenant_id`, `type`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源表';

-- 配置表
CREATE TABLE IF NOT EXISTS `config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
    `namespace` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '命名空间',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `parameters` JSON DEFAULT NULL COMMENT '参数配置',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `applied_at` DATETIME DEFAULT NULL COMMENT '应用时间',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_config_version` (`tenant_id`, `config_id`, `version`),
    KEY `idx_tenant_namespace` (`tenant_id`, `namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置表';

-- 运行实例表
CREATE TABLE IF NOT EXISTS `run_instance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '实体ID',
    `phase` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '执行阶段',
    `progress` DOUBLE NOT NULL DEFAULT 0.0 COMMENT '进度 0.0-1.0',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    `error_detail` TEXT DEFAULT NULL COMMENT '错误详情',
    `config_id` VARCHAR(64) DEFAULT NULL COMMENT '配置ID',
    `trigger_type` VARCHAR(32) DEFAULT 'manual' COMMENT '触发类型',
    `executor` VARCHAR(64) DEFAULT NULL COMMENT '执行者',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    `parent_run_id` VARCHAR(64) DEFAULT NULL COMMENT '父运行ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_id` (`run_id`),
    KEY `idx_tenant_entity` (`tenant_id`, `entity_id`),
    KEY `idx_tenant_phase` (`tenant_id`, `phase`),
    KEY `idx_started_at` (`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

-- 统计表
CREATE TABLE IF NOT EXISTS `snapshot` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
    `timestamp` DATETIME NOT NULL COMMENT '时间戳',
    `metrics` JSON DEFAULT NULL COMMENT '指标数据',
    `dimensions` JSON DEFAULT NULL COMMENT '维度数据',
    `period` VARCHAR(32) DEFAULT 'hour' COMMENT '统计周期',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
    KEY `idx_tenant_timestamp` (`tenant_id`, `timestamp`),
    KEY `idx_period` (`period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统计快照表';

-- 任务表
CREATE TABLE IF NOT EXISTS `task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `name` VARCHAR(128) NOT NULL COMMENT '任务名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `type` VARCHAR(64) NOT NULL DEFAULT 'custom' COMMENT '任务类型',
    `status` VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    `cron_expression` VARCHAR(128) DEFAULT NULL COMMENT 'Cron表达式',
    `next_run_time` DATETIME DEFAULT NULL COMMENT '下次运行时间',
    `last_run_time` DATETIME DEFAULT NULL COMMENT '上次运行时间',
    `parameters` JSON DEFAULT NULL COMMENT '任务参数',
    `handler_class` VARCHAR(256) DEFAULT NULL COMMENT '处理器类',
    `timeout_seconds` INT NOT NULL DEFAULT 300 COMMENT '超时时间(秒)',
    `max_retry` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `flow_id` VARCHAR(64) DEFAULT NULL COMMENT '流程ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_task` (`tenant_id`, `task_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_next_run_time` (`next_run_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

-- 技能表
CREATE TABLE IF NOT EXISTS `skill` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `skill_id` VARCHAR(64) NOT NULL COMMENT '技能ID',
    `name` VARCHAR(128) NOT NULL COMMENT '技能名称',
    `code` VARCHAR(64) NOT NULL COMMENT '技能编码',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `category` VARCHAR(64) DEFAULT NULL COMMENT '分类',
    `level` INT NOT NULL DEFAULT 1 COMMENT '技能层级',
    `parent_id` VARCHAR(64) DEFAULT NULL COMMENT '父技能ID',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `prerequisites` JSON DEFAULT NULL COMMENT '前置技能',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_skill` (`tenant_id`, `skill_id`),
    KEY `idx_tenant_parent` (`tenant_id`, `parent_id`),
    KEY `idx_tenant_category` (`tenant_id`, `category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能表';

-- 员工技能表
CREATE TABLE IF NOT EXISTS `employee_skill` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `employee_id` VARCHAR(64) NOT NULL COMMENT '员工ID',
    `skill_id` VARCHAR(64) NOT NULL COMMENT '技能ID',
    `proficiency_level` INT NOT NULL DEFAULT 0 COMMENT '熟练度 0-5',
    `assessment_date` DATETIME DEFAULT NULL COMMENT '评估日期',
    `assessor` VARCHAR(64) DEFAULT NULL COMMENT '评估人',
    `evidence` VARCHAR(512) DEFAULT NULL COMMENT '证明材料',
    `notes` VARCHAR(1024) DEFAULT NULL COMMENT '备注',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_employee_skill` (`tenant_id`, `employee_id`, `skill_id`),
    KEY `idx_tenant_employee` (`tenant_id`, `employee_id`),
    KEY `idx_tenant_skill` (`tenant_id`, `skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工技能表';

-- 通知模板表
CREATE TABLE IF NOT EXISTS `notification_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `template_id` VARCHAR(64) NOT NULL COMMENT '模板ID',
    `name` VARCHAR(128) NOT NULL COMMENT '模板名称',
    `type` VARCHAR(32) NOT NULL COMMENT '通知类型: email,sms,dingtalk,wechat',
    `channel` VARCHAR(32) NOT NULL COMMENT '通知渠道',
    `subject` VARCHAR(256) DEFAULT NULL COMMENT '主题',
    `content` TEXT NOT NULL COMMENT '模板内容',
    `variables` JSON DEFAULT NULL COMMENT '模板变量',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_template` (`tenant_id`, `template_id`),
    KEY `idx_tenant_type` (`tenant_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知模板表';

-- 通知记录表
CREATE TABLE IF NOT EXISTS `notification_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `record_id` VARCHAR(64) NOT NULL COMMENT '记录ID',
    `template_id` VARCHAR(64) DEFAULT NULL COMMENT '模板ID',
    `type` VARCHAR(32) NOT NULL COMMENT '通知类型',
    `channel` VARCHAR(32) NOT NULL COMMENT '通知渠道',
    `receiver` VARCHAR(256) NOT NULL COMMENT '接收人',
    `subject` VARCHAR(256) DEFAULT NULL COMMENT '主题',
    `content` TEXT DEFAULT NULL COMMENT '内容',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `error_message` VARCHAR(512) DEFAULT NULL COMMENT '错误信息',
    `sent_at` DATETIME DEFAULT NULL COMMENT '发送时间',
    `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '追踪ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_record_id` (`record_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知记录表';

-- 资源用量表
CREATE TABLE IF NOT EXISTS `resource_usage` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `usage_id` VARCHAR(64) NOT NULL COMMENT '用量ID',
    `resource_type` VARCHAR(64) NOT NULL COMMENT '资源类型',
    `usage_amount` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '用量',
    `unit` VARCHAR(32) NOT NULL COMMENT '单位',
    `period_start` DATETIME NOT NULL COMMENT '周期开始',
    `period_end` DATETIME NOT NULL COMMENT '周期结束',
    `dimensions` JSON DEFAULT NULL COMMENT '维度',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_id` (`usage_id`),
    KEY `idx_tenant_resource_period` (`tenant_id`, `resource_type`, `period_start`, `period_end`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源用量表';

-- 账单表
CREATE TABLE IF NOT EXISTS `bill` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `bill_id` VARCHAR(64) NOT NULL COMMENT '账单ID',
    `billing_period` VARCHAR(32) NOT NULL COMMENT '账期: YYYY-MM',
    `total_amount` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '总金额',
    `discount_amount` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '优惠金额',
    `payable_amount` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '应付金额',
    `paid_amount` DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '实付金额',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '状态',
    `issued_at` DATETIME DEFAULT NULL COMMENT '出账时间',
    `due_date` DATETIME DEFAULT NULL COMMENT '到期时间',
    `paid_at` DATETIME DEFAULT NULL COMMENT '支付时间',
    `details` JSON DEFAULT NULL COMMENT '账单明细',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bill_id` (`bill_id`),
    UNIQUE KEY `uk_tenant_period` (`tenant_id`, `billing_period`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单表';

-- 流程定义表
CREATE TABLE IF NOT EXISTS `flow_definition` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `flow_id` VARCHAR(64) NOT NULL COMMENT '流程ID',
    `name` VARCHAR(128) NOT NULL COMMENT '流程名称',
    `description` VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本',
    `nodes` JSON DEFAULT NULL COMMENT '节点定义',
    `edges` JSON DEFAULT NULL COMMENT '连线定义',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_flow_version` (`tenant_id`, `flow_id`, `version`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程定义表';

-- 流程实例表
CREATE TABLE IF NOT EXISTS `flow_instance` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `instance_id` VARCHAR(64) NOT NULL COMMENT '实例ID',
    `flow_id` VARCHAR(64) NOT NULL COMMENT '流程ID',
    `flow_version` INT NOT NULL COMMENT '流程版本',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态',
    `current_node` VARCHAR(64) DEFAULT NULL COMMENT '当前节点',
    `variables` JSON DEFAULT NULL COMMENT '流程变量',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始时间',
    `ended_at` DATETIME DEFAULT NULL COMMENT '结束时间',
    `error_detail` TEXT DEFAULT NULL COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(64) DEFAULT NULL COMMENT '更新人',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_id` (`instance_id`),
    KEY `idx_tenant_flow` (`tenant_id`, `flow_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程实例表';
