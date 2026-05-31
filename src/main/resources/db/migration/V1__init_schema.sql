-- =============================================
-- ContractAI Platform Database Schema
-- Version: 1.0
-- =============================================

-- =============================================
-- 1. 多租户隔离策略模块表
-- =============================================

CREATE TABLE `tenant` (
    `id` BIGINT NOT NULL COMMENT '租户ID',
    `tenant_code` VARCHAR(64) NOT NULL COMMENT '租户编码',
    `tenant_name` VARCHAR(128) NOT NULL COMMENT '租户名称',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    `type` VARCHAR(32) NOT NULL DEFAULT 'enterprise' COMMENT '租户类型: enterprise, individual',
    `industry` VARCHAR(64) COMMENT '所属行业',
    `contact_name` VARCHAR(64) COMMENT '联系人',
    `contact_phone` VARCHAR(32) COMMENT '联系电话',
    `contact_email` VARCHAR(128) COMMENT '联系邮箱',
    `expire_at` DATETIME COMMENT '过期时间',
    `attributes` JSON COMMENT '扩展属性',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_code`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户表';

CREATE TABLE `tenant_config` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
    `namespace` VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '命名空间',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `parameters` JSON NOT NULL COMMENT '配置参数',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `applied_at` DATETIME COMMENT '生效时间',
    `description` VARCHAR(256) COMMENT '配置描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_config` (`tenant_id`, `config_id`, `namespace`, `version`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户配置表';

CREATE TABLE `tenant_quota` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `resource_type` VARCHAR(64) NOT NULL COMMENT '资源类型: storage, api_call, user_count, workflow_count',
    `quota_limit` BIGINT NOT NULL COMMENT '配额上限',
    `quota_used` BIGINT NOT NULL DEFAULT 0 COMMENT '已使用量',
    `unit` VARCHAR(32) NOT NULL COMMENT '单位: GB, times, count',
    `reset_period` VARCHAR(32) COMMENT '重置周期: daily, monthly, yearly, none',
    `last_reset_at` DATETIME COMMENT '上次重置时间',
    `warning_threshold` DECIMAL(5,2) DEFAULT 80.00 COMMENT '告警阈值(%)',
    `attributes` JSON COMMENT '扩展属性',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_quota` (`tenant_id`, `resource_type`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户配额表';

-- =============================================
-- 2. 技能图谱建模模块表
-- =============================================

CREATE TABLE `skill_category` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `category_code` VARCHAR(64) NOT NULL COMMENT '分类编码',
    `category_name` VARCHAR(128) NOT NULL COMMENT '分类名称',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父分类ID',
    `level` INT NOT NULL DEFAULT 1 COMMENT '层级',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `description` VARCHAR(256) COMMENT '分类描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能分类表';

CREATE TABLE `skill` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `skill_code` VARCHAR(64) NOT NULL COMMENT '技能编码',
    `skill_name` VARCHAR(128) NOT NULL COMMENT '技能名称',
    `category_id` BIGINT NOT NULL COMMENT '分类ID',
    `level` INT NOT NULL DEFAULT 1 COMMENT '技能等级: 1-初级, 2-中级, 3-高级, 4-专家, 5-资深专家',
    `description` TEXT COMMENT '技能描述',
    `prerequisite_skills` JSON COMMENT '前置技能ID列表',
    `learning_path` JSON COMMENT '学习路径配置',
    `certification_required` TINYINT DEFAULT 0 COMMENT '是否需要认证',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_category_id` (`category_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能表';

CREATE TABLE `employee` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `employee_no` VARCHAR(64) NOT NULL COMMENT '员工编号',
    `name` VARCHAR(64) NOT NULL COMMENT '姓名',
    `department` VARCHAR(128) COMMENT '部门',
    `position` VARCHAR(128) COMMENT '职位',
    `email` VARCHAR(128) COMMENT '邮箱',
    `phone` VARCHAR(32) COMMENT '电话',
    `attributes` JSON COMMENT '扩展属性',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_employee_no` (`tenant_id`, `employee_no`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

CREATE TABLE `employee_skill` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `employee_id` BIGINT NOT NULL COMMENT '员工ID',
    `skill_id` BIGINT NOT NULL COMMENT '技能ID',
    `proficiency_level` INT NOT NULL DEFAULT 1 COMMENT '熟练度: 1-了解, 2-熟悉, 3-掌握, 4-精通, 5-专家',
    `certification_status` TINYINT DEFAULT 0 COMMENT '认证状态: 0-未认证, 1-已认证',
    `certification_date` DATE COMMENT '认证日期',
    `expire_date` DATE COMMENT '过期日期',
    `last_assessed_at` DATETIME COMMENT '上次评估时间',
    `assessment_score` DECIMAL(5,2) COMMENT '评估分数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_employee_skill` (`tenant_id`, `employee_id`, `skill_id`),
    KEY `idx_employee_id` (`employee_id`),
    KEY `idx_skill_id` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工技能关联表';

CREATE TABLE `learning_path` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `path_code` VARCHAR(64) NOT NULL COMMENT '路径编码',
    `path_name` VARCHAR(128) NOT NULL COMMENT '路径名称',
    `description` TEXT COMMENT '路径描述',
    `target_skill_id` BIGINT NOT NULL COMMENT '目标技能ID',
    `estimated_hours` INT COMMENT '预计学习时长(小时)',
    `course_steps` JSON COMMENT '课程步骤',
    `prerequisite_paths` JSON COMMENT '前置路径ID列表',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_target_skill_id` (`target_skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习路径表';

-- =============================================
-- 3. 用量计量与计费模块表
-- =============================================

CREATE TABLE `usage_record` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `resource_type` VARCHAR(64) NOT NULL COMMENT '资源类型',
    `usage_amount` BIGINT NOT NULL COMMENT '使用量',
    `unit` VARCHAR(32) NOT NULL COMMENT '单位',
    `usage_time` DATETIME NOT NULL COMMENT '使用时间',
    `source` VARCHAR(64) COMMENT '来源: api, workflow, document',
    `source_id` VARCHAR(128) COMMENT '来源ID',
    `attributes` JSON COMMENT '扩展属性',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_resource_time` (`tenant_id`, `resource_type`, `usage_time`),
    KEY `idx_usage_time` (`usage_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用量记录表';

CREATE TABLE `billing_plan` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `plan_code` VARCHAR(64) NOT NULL COMMENT '套餐编码',
    `plan_name` VARCHAR(128) NOT NULL COMMENT '套餐名称',
    `plan_type` VARCHAR(32) NOT NULL COMMENT '套餐类型: free, standard, enterprise, custom',
    `price` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '价格',
    `billing_cycle` VARCHAR(32) NOT NULL COMMENT '计费周期: monthly, yearly, on_demand',
    `start_date` DATE NOT NULL COMMENT '生效日期',
    `end_date` DATE COMMENT '失效日期',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 0-无效, 1-有效',
    `included_resources` JSON COMMENT '包含的资源配额',
    `overage_rates` JSON COMMENT '超量费率',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费套餐表';

CREATE TABLE `price_rule` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `resource_type` VARCHAR(64) NOT NULL COMMENT '资源类型',
    `billing_mode` VARCHAR(32) NOT NULL COMMENT '计费模式: fixed, tiered, volume',
    `price_per_unit` DECIMAL(12,4) NOT NULL COMMENT '单价',
    `tier_config` JSON COMMENT '阶梯配置',
    `currency` VARCHAR(16) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `effective_from` DATETIME NOT NULL COMMENT '生效时间',
    `effective_to` DATETIME COMMENT '失效时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_resource` (`tenant_id`, `resource_type`),
    KEY `idx_effective` (`effective_from`, `effective_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格规则表';

CREATE TABLE `bill` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `bill_no` VARCHAR(64) NOT NULL COMMENT '账单编号',
    `billing_period` VARCHAR(32) NOT NULL COMMENT '账期: YYYY-MM',
    `total_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '总金额',
    `paid_amount` DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '已支付金额',
    `status` VARCHAR(32) NOT NULL DEFAULT 'unpaid' COMMENT '状态: unpaid, paid, overdue, void',
    `issue_date` DATE NOT NULL COMMENT '出账日期',
    `due_date` DATE NOT NULL COMMENT '到期日期',
    `paid_date` DATE COMMENT '支付日期',
    `currency` VARCHAR(16) NOT NULL DEFAULT 'CNY' COMMENT '币种',
    `bill_items` JSON COMMENT '账单明细',
    `summary` JSON COMMENT '账单汇总',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bill_no` (`bill_no`),
    KEY `idx_tenant_period` (`tenant_id`, `billing_period`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单表';

CREATE TABLE `bill_item` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `bill_id` BIGINT NOT NULL COMMENT '账单ID',
    `resource_type` VARCHAR(64) NOT NULL COMMENT '资源类型',
    `usage_amount` BIGINT NOT NULL COMMENT '使用量',
    `unit` VARCHAR(32) NOT NULL COMMENT '单位',
    `unit_price` DECIMAL(12,4) NOT NULL COMMENT '单价',
    `amount` DECIMAL(12,2) NOT NULL COMMENT '金额',
    `description` VARCHAR(256) COMMENT '描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_bill_id` (`bill_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单明细表';

-- =============================================
-- 4. 可视化流程设计模块表
-- =============================================

CREATE TABLE `workflow_definition` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `flow_code` VARCHAR(64) NOT NULL COMMENT '流程编码',
    `flow_name` VARCHAR(128) NOT NULL COMMENT '流程名称',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本',
    `category` VARCHAR(64) COMMENT '分类',
    `description` TEXT COMMENT '流程描述',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '状态: draft, published, deprecated',
    `nodes` JSON NOT NULL COMMENT '节点配置',
    `edges` JSON NOT NULL COMMENT '连线配置',
    `variables` JSON COMMENT '流程变量',
    `form_schema` JSON COMMENT '表单定义',
    `published_at` DATETIME COMMENT '发布时间',
    `published_by` BIGINT COMMENT '发布人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_flow_version` (`tenant_id`, `flow_code`, `version`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程定义表';

CREATE TABLE `workflow_node` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `flow_id` BIGINT NOT NULL COMMENT '流程ID',
    `node_id` VARCHAR(64) NOT NULL COMMENT '节点ID',
    `node_name` VARCHAR(128) NOT NULL COMMENT '节点名称',
    `node_type` VARCHAR(64) NOT NULL COMMENT '节点类型: start, end, approval, condition, parallel, subflow, service',
    `position_x` INT COMMENT 'X坐标',
    `position_y` INT COMMENT 'Y坐标',
    `config` JSON COMMENT '节点配置',
    `form_schema` JSON COMMENT '节点表单',
    `listener_config` JSON COMMENT '监听器配置',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_flow_node` (`flow_id`, `node_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_flow_id` (`flow_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程节点表';

CREATE TABLE `workflow_edge` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `flow_id` BIGINT NOT NULL COMMENT '流程ID',
    `edge_id` VARCHAR(64) NOT NULL COMMENT '连线ID',
    `source_node_id` VARCHAR(64) NOT NULL COMMENT '源节点ID',
    `target_node_id` VARCHAR(64) NOT NULL COMMENT '目标节点ID',
    `edge_name` VARCHAR(128) COMMENT '连线名称',
    `condition_expression` TEXT COMMENT '条件表达式',
    `priority` INT DEFAULT 0 COMMENT '优先级',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_flow_edge` (`flow_id`, `edge_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_flow_id` (`flow_id`),
    KEY `idx_source_node` (`flow_id`, `source_node_id`),
    KEY `idx_target_node` (`flow_id`, `target_node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程连线表';

CREATE TABLE `workflow_instance` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `instance_no` VARCHAR(64) NOT NULL COMMENT '实例编号',
    `flow_id` BIGINT NOT NULL COMMENT '流程定义ID',
    `business_key` VARCHAR(128) COMMENT '业务主键',
    `status` VARCHAR(32) NOT NULL DEFAULT 'running' COMMENT '状态: running, completed, suspended, terminated, cancelled',
    `current_node_id` VARCHAR(64) COMMENT '当前节点ID',
    `variables` JSON COMMENT '流程变量',
    `form_data` JSON COMMENT '表单数据',
    `started_at` DATETIME NOT NULL COMMENT '开始时间',
    `ended_at` DATETIME COMMENT '结束时间',
    `started_by` BIGINT COMMENT '发起人',
    `error_detail` VARCHAR(1024) COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_no` (`instance_no`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_flow_id` (`flow_id`),
    KEY `idx_status` (`status`),
    KEY `idx_business_key` (`business_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程实例表';

-- =============================================
-- 5. SLA时效监控模块表
-- =============================================

CREATE TABLE `sla_policy` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `policy_code` VARCHAR(64) NOT NULL COMMENT '策略编码',
    `policy_name` VARCHAR(128) NOT NULL COMMENT '策略名称',
    `sla_type` VARCHAR(32) NOT NULL COMMENT 'SLA类型: ticket, task, approval',
    `priority` INT NOT NULL DEFAULT 1 COMMENT '优先级: 1-低, 2-中, 3-高, 4-紧急, 5-非常紧急',
    `response_time` INT NOT NULL COMMENT '响应时限(分钟)',
    `resolution_time` INT NOT NULL COMMENT '解决时限(分钟)',
    `warning_threshold` DECIMAL(5,2) DEFAULT 80.00 COMMENT '告警阈值(%)',
    `escalation_rules` JSON COMMENT '升级规则',
    `notification_channels` JSON COMMENT '通知渠道',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `description` VARCHAR(256) COMMENT '描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_sla_type` (`sla_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA策略表';

CREATE TABLE `sla_record` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `policy_id` BIGINT NOT NULL COMMENT '策略ID',
    `business_type` VARCHAR(32) NOT NULL COMMENT '业务类型',
    `business_id` VARCHAR(128) NOT NULL COMMENT '业务ID',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, in_progress, completed, breached',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `response_deadline` DATETIME NOT NULL COMMENT '响应截止时间',
    `resolution_deadline` DATETIME NOT NULL COMMENT '解决截止时间',
    `response_time` DATETIME COMMENT '实际响应时间',
    `resolution_time` DATETIME COMMENT '实际解决时间',
    `current_stage` VARCHAR(32) COMMENT '当前阶段',
    `escalation_level` INT DEFAULT 0 COMMENT '升级级别',
    `last_escalation_at` DATETIME COMMENT '上次升级时间',
    `notifications_sent` JSON COMMENT '已发送通知',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_business` (`tenant_id`, `business_type`, `business_id`),
    KEY `idx_status` (`status`),
    KEY `idx_deadlines` (`response_deadline`, `resolution_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA记录表';

CREATE TABLE `sla_escalation` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `sla_record_id` BIGINT NOT NULL COMMENT 'SLA记录ID',
    `escalation_level` INT NOT NULL COMMENT '升级级别',
    `escalation_type` VARCHAR(32) NOT NULL COMMENT '升级类型: warning, response_breach, resolution_breach',
    `escalation_time` DATETIME NOT NULL COMMENT '升级时间',
    `notified_users` JSON COMMENT '通知用户列表',
    `notification_channels` JSON COMMENT '通知渠道',
    `acknowledged` TINYINT DEFAULT 0 COMMENT '是否已确认',
    `acknowledged_by` BIGINT COMMENT '确认人',
    `acknowledged_at` DATETIME COMMENT '确认时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_sla_record_id` (`sla_record_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA升级记录表';

-- =============================================
-- 6. 文档智能比对模块表
-- =============================================

CREATE TABLE `document` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `doc_code` VARCHAR(64) NOT NULL COMMENT '文档编码',
    `doc_title` VARCHAR(256) NOT NULL COMMENT '文档标题',
    `doc_type` VARCHAR(32) NOT NULL COMMENT '文档类型: contract, agreement, memo, other',
    `file_type` VARCHAR(16) NOT NULL COMMENT '文件类型: pdf, docx, txt',
    `file_size` BIGINT COMMENT '文件大小(字节)',
    `file_path` VARCHAR(512) COMMENT '文件路径',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本',
    `status` VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT '状态: draft, reviewing, approved, archived',
    `content_hash` VARCHAR(128) COMMENT '内容哈希',
    `metadata` JSON COMMENT '元数据',
    `tags` JSON COMMENT '标签',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父文档ID',
    `created_by` BIGINT COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_doc_type` (`doc_type`),
    KEY `idx_content_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

CREATE TABLE `document_content` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `document_id` BIGINT NOT NULL COMMENT '文档ID',
    `content_text` MEDIUMTEXT COMMENT '文本内容',
    `structured_content` JSON COMMENT '结构化内容',
    `key_clauses` JSON COMMENT '关键条款',
    `entities` JSON COMMENT '识别出的实体',
    `content_hash` VARCHAR(128) COMMENT '内容哈希',
    `parsed_at` DATETIME COMMENT '解析时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_id` (`document_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档内容表';

CREATE TABLE `document_comparison` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `comparison_code` VARCHAR(64) NOT NULL COMMENT '比对编码',
    `comparison_name` VARCHAR(256) COMMENT '比对名称',
    `source_doc_id` BIGINT NOT NULL COMMENT '源文档ID',
    `target_doc_id` BIGINT NOT NULL COMMENT '目标文档ID',
    `comparison_type` VARCHAR(32) NOT NULL DEFAULT 'full' COMMENT '比对类型: full, partial, clause',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, processing, completed, failed',
    `algorithm` VARCHAR(64) NOT NULL DEFAULT 'diff-match-patch' COMMENT '比对算法',
    `similarity_score` DECIMAL(5,2) COMMENT '相似度(%)',
    `diff_stats` JSON COMMENT '差异统计',
    `highlights` JSON COMMENT '高亮信息',
    `change_summary` TEXT COMMENT '变更摘要',
    `detailed_diffs` MEDIUMTEXT COMMENT '详细差异(JSON)',
    `started_at` DATETIME COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `error_detail` VARCHAR(1024) COMMENT '错误详情',
    `created_by` BIGINT COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_comparison_code` (`comparison_code`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_docs` (`source_doc_id`, `target_doc_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档比对表';

CREATE TABLE `document_clause` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `document_id` BIGINT NOT NULL COMMENT '文档ID',
    `clause_code` VARCHAR(64) COMMENT '条款编码',
    `clause_title` VARCHAR(256) NOT NULL COMMENT '条款标题',
    `clause_type` VARCHAR(64) COMMENT '条款类型: confidentiality, liability, warranty, termination, etc.',
    `clause_content` TEXT NOT NULL COMMENT '条款内容',
    `start_position` INT COMMENT '起始位置',
    `end_position` INT COMMENT '结束位置',
    `importance` INT DEFAULT 1 COMMENT '重要程度: 1-普通, 2-重要, 3-关键',
    `risk_level` VARCHAR(32) DEFAULT 'low' COMMENT '风险等级: low, medium, high',
    `metadata` JSON COMMENT '元数据',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_document_id` (`document_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_clause_type` (`clause_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档条款表';

-- =============================================
-- 7. 工单智能分配模块表
-- =============================================

CREATE TABLE `ticket` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `ticket_no` VARCHAR(64) NOT NULL COMMENT '工单号',
    `title` VARCHAR(256) NOT NULL COMMENT '标题',
    `description` TEXT COMMENT '描述',
    `ticket_type` VARCHAR(64) NOT NULL COMMENT '工单类型',
    `priority` INT NOT NULL DEFAULT 2 COMMENT '优先级: 1-低, 2-中, 3-高, 4-紧急',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, assigned, in_progress, resolved, closed, cancelled',
    `source` VARCHAR(64) COMMENT '来源',
    `category` VARCHAR(128) COMMENT '分类',
    `tags` JSON COMMENT '标签',
    `assignee_id` BIGINT COMMENT '处理人ID',
    `assignee_group` VARCHAR(128) COMMENT '处理组',
    `required_skills` JSON COMMENT '所需技能',
    `sla_policy_id` BIGINT COMMENT 'SLA策略ID',
    `parent_id` BIGINT DEFAULT 0 COMMENT '父工单ID',
    `form_data` JSON COMMENT '表单数据',
    `created_by` BIGINT COMMENT '创建人',
    `resolved_at` DATETIME COMMENT '解决时间',
    `closed_at` DATETIME COMMENT '关闭时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_status` (`status`),
    KEY `idx_assignee` (`assignee_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单表';

CREATE TABLE `ticket_assignment_log` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `ticket_id` BIGINT NOT NULL COMMENT '工单ID',
    `assignment_type` VARCHAR(32) NOT NULL COMMENT '分配类型: auto, manual, reassign, escalation',
    `from_assignee_id` BIGINT COMMENT '原处理人',
    `to_assignee_id` BIGINT COMMENT '新处理人',
    `assignment_reason` VARCHAR(256) COMMENT '分配原因',
    `assignment_strategy` VARCHAR(64) COMMENT '分配策略',
    `match_score` DECIMAL(5,2) COMMENT '匹配分数',
    `load_balance_factor` DECIMAL(5,2) COMMENT '负载均衡因子',
    `assigned_by` BIGINT COMMENT '分配人',
    `assigned_at` DATETIME NOT NULL COMMENT '分配时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_ticket_id` (`ticket_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_assignee` (`to_assignee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单分配日志表';

CREATE TABLE `employee_workload` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `employee_id` BIGINT NOT NULL COMMENT '员工ID',
    `open_tickets_count` INT NOT NULL DEFAULT 0 COMMENT '待处理工单数',
    `total_tickets_count` INT NOT NULL DEFAULT 0 COMMENT '总工单数',
    `avg_resolution_time` INT COMMENT '平均解决时间(分钟)',
    `workload_score` DECIMAL(5,2) DEFAULT 0 COMMENT '负载评分',
    `capacity` INT DEFAULT 10 COMMENT '处理容量',
    `efficiency_factor` DECIMAL(5,2) DEFAULT 1.00 COMMENT '效率因子',
    `last_calculated_at` DATETIME COMMENT '上次计算时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_employee_id` (`tenant_id`, `employee_id`),
    KEY `idx_workload_score` (`workload_score`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工负载表';

CREATE TABLE `assignment_strategy` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `strategy_code` VARCHAR(64) NOT NULL COMMENT '策略编码',
    `strategy_name` VARCHAR(128) NOT NULL COMMENT '策略名称',
    `strategy_type` VARCHAR(32) NOT NULL COMMENT '策略类型: skill_match, load_balance, round_robin, least_loaded, hybrid',
    `ticket_types` JSON COMMENT '适用工单类型',
    `skill_match_weight` DECIMAL(5,2) DEFAULT 50.00 COMMENT '技能匹配权重',
    `load_balance_weight` DECIMAL(5,2) DEFAULT 30.00 COMMENT '负载均衡权重',
    `efficiency_weight` DECIMAL(5,2) DEFAULT 20.00 COMMENT '效率权重',
    `config` JSON COMMENT '策略配置',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `description` VARCHAR(256) COMMENT '描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_strategy_type` (`strategy_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分配策略表';

-- =============================================
-- 8. 审批规则引擎模块表
-- =============================================

CREATE TABLE `approval_rule` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `rule_code` VARCHAR(64) NOT NULL COMMENT '规则编码',
    `rule_name` VARCHAR(128) NOT NULL COMMENT '规则名称',
    `rule_type` VARCHAR(32) NOT NULL COMMENT '规则类型: condition, approver, cc, timeout',
    `business_type` VARCHAR(64) NOT NULL COMMENT '业务类型',
    `priority` INT NOT NULL DEFAULT 0 COMMENT '优先级',
    `condition_expression` TEXT COMMENT '条件表达式',
    `approval_strategy` VARCHAR(32) COMMENT '审批策略: any, all, percentage, sequential',
    `approver_count` INT COMMENT '审批人数要求',
    `approval_percentage` DECIMAL(5,2) COMMENT '审批通过比例',
    `approver_config` JSON COMMENT '审批人配置',
    `cc_config` JSON COMMENT '抄送人配置',
    `timeout_config` JSON COMMENT '超时配置',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `description` VARCHAR(256) COMMENT '描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_business` (`tenant_id`, `business_type`),
    KEY `idx_rule_type` (`rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批规则表';

CREATE TABLE `approval_process` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `process_no` VARCHAR(64) NOT NULL COMMENT '审批编号',
    `business_type` VARCHAR(64) NOT NULL COMMENT '业务类型',
    `business_id` VARCHAR(128) NOT NULL COMMENT '业务ID',
    `title` VARCHAR(256) NOT NULL COMMENT '审批标题',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, approving, approved, rejected, cancelled, timeout',
    `approval_strategy` VARCHAR(32) NOT NULL COMMENT '审批策略',
    `current_stage` INT DEFAULT 0 COMMENT '当前阶段',
    `total_stages` INT NOT NULL DEFAULT 1 COMMENT '总阶段数',
    `form_data` JSON COMMENT '表单数据',
    `variables` JSON COMMENT '变量',
    `approver_list` JSON COMMENT '审批人列表',
    `cc_list` JSON COMMENT '抄送人列表',
    `started_by` BIGINT NOT NULL COMMENT '发起人',
    `started_at` DATETIME NOT NULL COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `timeout_at` DATETIME COMMENT '超时时间',
    `final_decision` VARCHAR(32) COMMENT '最终决定',
    `final_comment` TEXT COMMENT '最终意见',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_process_no` (`process_no`),
    KEY `idx_tenant_business` (`tenant_id`, `business_type`, `business_id`),
    KEY `idx_status` (`status`),
    KEY `idx_started_by` (`started_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批流程表';

CREATE TABLE `approval_stage` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `process_id` BIGINT NOT NULL COMMENT '审批流程ID',
    `stage_index` INT NOT NULL COMMENT '阶段序号',
    `stage_name` VARCHAR(128) COMMENT '阶段名称',
    `approval_strategy` VARCHAR(32) NOT NULL COMMENT '审批策略',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, approving, approved, rejected, skipped',
    `approver_count` INT NOT NULL DEFAULT 0 COMMENT '审批人数',
    `approved_count` INT NOT NULL DEFAULT 0 COMMENT '已通过人数',
    `rejected_count` INT NOT NULL DEFAULT 0 COMMENT '已拒绝人数',
    `sign_type` VARCHAR(32) COMMENT '会签类型: countersign(会签), or_sign(或签)',
    `started_at` DATETIME COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_process_id` (`process_id`),
    KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批阶段表';

CREATE TABLE `approval_task` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `process_id` BIGINT NOT NULL COMMENT '审批流程ID',
    `stage_id` BIGINT NOT NULL COMMENT '审批阶段ID',
    `approver_id` BIGINT NOT NULL COMMENT '审批人ID',
    `status` VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, approved, rejected, transferred, delegated, timeout',
    `action` VARCHAR(32) COMMENT '操作: approve, reject, transfer, delegate',
    `comment` TEXT COMMENT '审批意见',
    `signatures` JSON COMMENT '签名信息',
    `assigned_at` DATETIME NOT NULL COMMENT '分配时间',
    `acted_at` DATETIME COMMENT '处理时间',
    `transferred_to` BIGINT COMMENT '转办给',
    `delegated_to` BIGINT COMMENT '委托给',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_process_id` (`process_id`),
    KEY `idx_stage_id` (`stage_id`),
    KEY `idx_approver_id` (`approver_id`),
    KEY `idx_tenant_id` (`tenant_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批任务表';

-- =============================================
-- 9. 运行实例和统计快照表
-- =============================================

CREATE TABLE `run_instance` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '实体ID',
    `entity_type` VARCHAR(64) NOT NULL COMMENT '实体类型',
    `phase` VARCHAR(32) NOT NULL COMMENT '阶段',
    `progress` DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '进度',
    `started_at` DATETIME NOT NULL COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `status` VARCHAR(32) NOT NULL DEFAULT 'running' COMMENT '状态',
    `error_detail` VARCHAR(1024) COMMENT '错误详情',
    `attributes` JSON COMMENT '扩展属性',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_id` (`run_id`),
    KEY `idx_tenant_entity` (`tenant_id`, `entity_type`, `entity_id`),
    KEY `idx_status` (`status`),
    KEY `idx_phase` (`phase`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行实例表';

CREATE TABLE `stat_snapshot` (
    `id` BIGINT NOT NULL,
    `tenant_id` BIGINT NOT NULL,
    `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
    `snapshot_time` DATETIME NOT NULL COMMENT '快照时间',
    `metrics` JSON NOT NULL COMMENT '指标数据',
    `dimensions` JSON COMMENT '维度',
    `metrics_type` VARCHAR(64) NOT NULL COMMENT '指标类型',
    `aggregation_level` VARCHAR(32) NOT NULL DEFAULT 'minute' COMMENT '聚合级别: minute, hour, day, month',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
    KEY `idx_tenant_time` (`tenant_id`, `snapshot_time`),
    KEY `idx_metrics_type` (`metrics_type`),
    KEY `idx_aggregation` (`aggregation_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计快照表';
