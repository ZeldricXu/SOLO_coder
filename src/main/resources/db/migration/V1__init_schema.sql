CREATE TABLE IF NOT EXISTS `core_entity` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `type` VARCHAR(50) NOT NULL COMMENT '实体类型',
    `status` VARCHAR(30) NOT NULL DEFAULT 'active' COMMENT '状态',
    `attributes` JSON COMMENT '属性集合',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_type` (`type`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核心实体表';

CREATE TABLE IF NOT EXISTS `config_snapshot` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
    `namespace` VARCHAR(50) NOT NULL COMMENT '命名空间',
    `version` INT NOT NULL COMMENT '版本号',
    `parameters` JSON NOT NULL COMMENT '配置参数',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `applied_at` DATETIME COMMENT '应用时间',
    `created_by` VARCHAR(64) COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_version` (`config_id`, `version`),
    KEY `idx_namespace` (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置快照表';

CREATE TABLE IF NOT EXISTS `run_instance` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行实例ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    `phase` VARCHAR(30) NOT NULL COMMENT '执行阶段',
    `progress` DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '进度百分比',
    `started_at` DATETIME NOT NULL COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `error_detail` TEXT COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_id` (`run_id`),
    KEY `idx_entity_id` (`entity_id`),
    KEY `idx_phase` (`phase`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS `metrics_snapshot` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
    `timestamp` DATETIME NOT NULL COMMENT '快照时间',
    `metrics` JSON NOT NULL COMMENT '指标数据',
    `dimensions` JSON COMMENT '维度数据',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
    KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标快照表';

CREATE TABLE IF NOT EXISTS `sla_definition` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `sla_id` VARCHAR(64) NOT NULL COMMENT 'SLA定义ID',
    `name` VARCHAR(100) NOT NULL COMMENT 'SLA名称',
    `description` VARCHAR(500) COMMENT '描述',
    `entity_type` VARCHAR(50) NOT NULL COMMENT '适用实体类型',
    `duration_seconds` BIGINT NOT NULL COMMENT 'SLA时长(秒)',
    `warning_threshold` DECIMAL(5,2) NOT NULL DEFAULT 0.75 COMMENT '警告阈值比例',
    `escalation_levels` JSON COMMENT '升级级别配置',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sla_id` (`sla_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA定义表';

CREATE TABLE IF NOT EXISTS `sla_tracker` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `tracker_id` VARCHAR(64) NOT NULL COMMENT '跟踪ID',
    `sla_id` VARCHAR(64) NOT NULL COMMENT '关联SLA定义ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    `status` VARCHAR(30) NOT NULL DEFAULT 'running' COMMENT '状态: running,warning,escalated,completed,breached',
    `start_time` DATETIME NOT NULL COMMENT '开始时间',
    `deadline_time` DATETIME NOT NULL COMMENT '截止时间',
    `current_level` INT NOT NULL DEFAULT 0 COMMENT '当前升级级别',
    `last_notified_at` DATETIME COMMENT '上次通知时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tracker_id` (`tracker_id`),
    KEY `idx_entity_id` (`entity_id`),
    KEY `idx_status` (`status`),
    KEY `idx_deadline` (`deadline_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SLA跟踪表';

CREATE TABLE IF NOT EXISTS `work_order` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `order_id` VARCHAR(64) NOT NULL COMMENT '工单ID',
    `title` VARCHAR(200) NOT NULL COMMENT '工单标题',
    `description` TEXT COMMENT '工单描述',
    `type` VARCHAR(50) NOT NULL COMMENT '工单类型',
    `priority` VARCHAR(20) NOT NULL DEFAULT 'medium' COMMENT '优先级: low, medium, high, urgent',
    `status` VARCHAR(30) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, assigned, processing, completed, closed',
    `required_skills` JSON COMMENT '所需技能列表',
    `assignee_id` VARCHAR(64) COMMENT '处理人ID',
    `creator_id` VARCHAR(64) NOT NULL COMMENT '创建人ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `sla_tracker_id` VARCHAR(64) COMMENT 'SLA跟踪ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_id` (`order_id`),
    KEY `idx_status` (`status`),
    KEY `idx_assignee` (`assignee_id`),
    KEY `idx_tenant` (`tenant_id`),
    KEY `idx_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单表';

CREATE TABLE IF NOT EXISTS `employee` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `employee_id` VARCHAR(64) NOT NULL COMMENT '员工ID',
    `name` VARCHAR(100) NOT NULL COMMENT '员工姓名',
    `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
    `department` VARCHAR(100) COMMENT '部门',
    `position` VARCHAR(100) COMMENT '职位',
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态: active, inactive, on_leave',
    `current_load` INT NOT NULL DEFAULT 0 COMMENT '当前负载(工单数量)',
    `max_load` INT NOT NULL DEFAULT 10 COMMENT '最大负载',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_employee_id` (`employee_id`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

CREATE TABLE IF NOT EXISTS `skill_tree` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `skill_id` VARCHAR(64) NOT NULL COMMENT '技能ID',
    `name` VARCHAR(100) NOT NULL COMMENT '技能名称',
    `description` VARCHAR(500) COMMENT '技能描述',
    `category` VARCHAR(50) NOT NULL COMMENT '技能分类',
    `parent_id` VARCHAR(64) COMMENT '父技能ID',
    `level` INT NOT NULL DEFAULT 1 COMMENT '层级',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_id` (`skill_id`),
    KEY `idx_parent` (`parent_id`),
    KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能树表';

CREATE TABLE IF NOT EXISTS `employee_skill` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `employee_id` VARCHAR(64) NOT NULL COMMENT '员工ID',
    `skill_id` VARCHAR(64) NOT NULL COMMENT '技能ID',
    `proficiency_level` INT NOT NULL DEFAULT 1 COMMENT '熟练程度: 1-5',
    `certified` TINYINT NOT NULL DEFAULT 0 COMMENT '是否认证',
    `last_assessed_at` DATETIME COMMENT '上次评估时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_employee_skill` (`employee_id`, `skill_id`),
    KEY `idx_skill_id` (`skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工技能表';

CREATE TABLE IF NOT EXISTS `learning_path` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `path_id` VARCHAR(64) NOT NULL COMMENT '学习路径ID',
    `employee_id` VARCHAR(64) NOT NULL COMMENT '员工ID',
    `target_skill_id` VARCHAR(64) NOT NULL COMMENT '目标技能ID',
    `current_stage` INT NOT NULL DEFAULT 1 COMMENT '当前阶段',
    `total_stages` INT NOT NULL COMMENT '总阶段数',
    `status` VARCHAR(20) NOT NULL DEFAULT 'in_progress' COMMENT '状态: in_progress, completed, abandoned',
    `recommended_courses` JSON COMMENT '推荐课程',
    `started_at` DATETIME NOT NULL COMMENT '开始时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_path_id` (`path_id`),
    KEY `idx_employee` (`employee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习路径表';

CREATE TABLE IF NOT EXISTS `tenant_usage` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `usage_id` VARCHAR(64) NOT NULL COMMENT '用量ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `resource_type` VARCHAR(50) NOT NULL COMMENT '资源类型',
    `usage_amount` DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '用量',
    `unit` VARCHAR(30) NOT NULL COMMENT '计量单位',
    `period_start` DATETIME NOT NULL COMMENT '计费周期开始',
    `period_end` DATETIME NOT NULL COMMENT '计费周期结束',
    `collected_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_usage_id` (`usage_id`),
    KEY `idx_tenant_period` (`tenant_id`, `period_start`, `period_end`),
    KEY `idx_resource_type` (`resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户用量表';

CREATE TABLE IF NOT EXISTS `billing_plan` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `plan_id` VARCHAR(64) NOT NULL COMMENT '计费方案ID',
    `name` VARCHAR(100) NOT NULL COMMENT '方案名称',
    `description` VARCHAR(500) COMMENT '描述',
    `resource_type` VARCHAR(50) NOT NULL COMMENT '资源类型',
    `unit_price` DECIMAL(18,6) NOT NULL COMMENT '单价',
    `currency` VARCHAR(10) NOT NULL DEFAULT 'CNY' COMMENT '货币',
    `billing_cycle` VARCHAR(20) NOT NULL DEFAULT 'monthly' COMMENT '计费周期: hourly, daily, monthly',
    `tiers` JSON COMMENT '阶梯定价配置',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plan_id` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计费方案表';

CREATE TABLE IF NOT EXISTS `billing_invoice` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `invoice_id` VARCHAR(64) NOT NULL COMMENT '账单ID',
    `tenant_id` VARCHAR(64) NOT NULL COMMENT '租户ID',
    `billing_period_start` DATETIME NOT NULL COMMENT '计费周期开始',
    `billing_period_end` DATETIME NOT NULL COMMENT '计费周期结束',
    `total_amount` DECIMAL(18,6) NOT NULL COMMENT '总金额',
    `currency` VARCHAR(10) NOT NULL DEFAULT 'CNY' COMMENT '货币',
    `status` VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT '状态: draft, issued, paid, overdue, cancelled',
    `line_items` JSON COMMENT '账单明细',
    `issued_at` DATETIME COMMENT '发行时间',
    `paid_at` DATETIME COMMENT '支付时间',
    `due_date` DATETIME COMMENT '到期日',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_invoice_id` (`invoice_id`),
    KEY `idx_tenant_period` (`tenant_id`, `billing_period_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单表';

CREATE TABLE IF NOT EXISTS `scheduled_task` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `type` VARCHAR(50) NOT NULL COMMENT '任务类型',
    `cron_expression` VARCHAR(100) COMMENT 'Cron表达式',
    `parameters` JSON COMMENT '任务参数',
    `dependencies` JSON COMMENT '依赖任务ID列表',
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态: active, paused, disabled',
    `last_run_id` VARCHAR(64) COMMENT '上次运行ID',
    `last_run_at` DATETIME COMMENT '上次运行时间',
    `next_run_at` DATETIME COMMENT '下次运行时间',
    `created_by` VARCHAR(64) COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调度任务表';

CREATE TABLE IF NOT EXISTS `task_execution` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `execution_id` VARCHAR(64) NOT NULL COMMENT '执行ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `trigger_type` VARCHAR(30) NOT NULL COMMENT '触发类型: manual, scheduled, dependency',
    `status` VARCHAR(30) NOT NULL COMMENT '状态: pending, running, completed, failed, cancelled',
    `start_time` DATETIME COMMENT '开始时间',
    `end_time` DATETIME COMMENT '结束时间',
    `result` TEXT COMMENT '执行结果',
    `error_message` TEXT COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_execution_id` (`execution_id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行记录表';

CREATE TABLE IF NOT EXISTS `task_dependency` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `dependent_task_id` VARCHAR(64) NOT NULL COMMENT '依赖任务ID',
    `dependency_type` VARCHAR(20) NOT NULL DEFAULT 'finish_to_start' COMMENT '依赖类型',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_dependency` (`task_id`, `dependent_task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖关系表';

CREATE TABLE IF NOT EXISTS `approval_rule` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `rule_id` VARCHAR(64) NOT NULL COMMENT '规则ID',
    `name` VARCHAR(100) NOT NULL COMMENT '规则名称',
    `process_type` VARCHAR(50) NOT NULL COMMENT '流程类型',
    `conditions` JSON NOT NULL COMMENT '条件表达式',
    `approval_type` VARCHAR(20) NOT NULL DEFAULT 'sequential' COMMENT '审批类型: sequential, parallel, any',
    `approvers` JSON COMMENT '审批人配置',
    `dynamic_approver_script` TEXT COMMENT '动态审批人脚本',
    `sign_type` VARCHAR(20) NOT NULL DEFAULT 'all' COMMENT '会签类型: all, any, majority',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_id` (`rule_id`),
    KEY `idx_process_type` (`process_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批规则表';

CREATE TABLE IF NOT EXISTS `approval_instance` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `instance_id` VARCHAR(64) NOT NULL COMMENT '审批实例ID',
    `rule_id` VARCHAR(64) NOT NULL COMMENT '规则ID',
    `business_key` VARCHAR(64) NOT NULL COMMENT '业务主键',
    `business_data` JSON COMMENT '业务数据',
    `status` VARCHAR(30) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, approved, rejected, cancelled',
    `current_node` VARCHAR(64) COMMENT '当前节点',
    `submitter_id` VARCHAR(64) NOT NULL COMMENT '提交人ID',
    `submitted_at` DATETIME NOT NULL COMMENT '提交时间',
    `completed_at` DATETIME COMMENT '完成时间',
    `final_decision` VARCHAR(20) COMMENT '最终决定',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_instance_id` (`instance_id`),
    KEY `idx_business_key` (`business_key`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批实例表';

CREATE TABLE IF NOT EXISTS `approval_record` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `record_id` VARCHAR(64) NOT NULL COMMENT '记录ID',
    `instance_id` VARCHAR(64) NOT NULL COMMENT '审批实例ID',
    `node_id` VARCHAR(64) NOT NULL COMMENT '节点ID',
    `approver_id` VARCHAR(64) NOT NULL COMMENT '审批人ID',
    `action` VARCHAR(20) NOT NULL COMMENT '操作: approve, reject, delegate',
    `comment` VARCHAR(1000) COMMENT '审批意见',
    `approved_at` DATETIME COMMENT '审批时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_record_id` (`record_id`),
    KEY `idx_instance_id` (`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批记录表';

CREATE TABLE IF NOT EXISTS `log_level_config` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
    `logger_name` VARCHAR(200) NOT NULL COMMENT 'Logger名称',
    `level` VARCHAR(10) NOT NULL COMMENT '日志级别: TRACE, DEBUG, INFO, WARN, ERROR',
    `scope` VARCHAR(20) NOT NULL DEFAULT 'global' COMMENT '作用域: global, tenant, user',
    `scope_id` VARCHAR(64) COMMENT '作用域ID',
    `expire_at` DATETIME COMMENT '过期时间',
    `created_by` VARCHAR(64) COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_logger_scope` (`logger_name`, `scope`, `scope_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志级别配置表';
