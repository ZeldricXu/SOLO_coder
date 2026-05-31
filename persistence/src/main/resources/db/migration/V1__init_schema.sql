CREATE TABLE IF NOT EXISTS sys_tenant (
    id BIGINT PRIMARY KEY COMMENT '租户ID',
    tenant_code VARCHAR(64) NOT NULL UNIQUE COMMENT '租户编码',
    tenant_name VARCHAR(128) NOT NULL COMMENT '租户名称',
    status TINYINT DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    expired_at DATETIME COMMENT '过期时间',
    config_json TEXT COMMENT '租户配置JSON',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    created_by VARCHAR(64) COMMENT '创建人',
    updated_by VARCHAR(64) COMMENT '更新人',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    version INT DEFAULT 0 COMMENT '乐观锁版本'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户表';

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY COMMENT '用户ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(256) NOT NULL COMMENT '密码',
    email VARCHAR(128) COMMENT '邮箱',
    phone VARCHAR(32) COMMENT '手机号',
    real_name VARCHAR(64) COMMENT '真实姓名',
    status TINYINT DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    created_by VARCHAR(64) COMMENT '创建人',
    updated_by VARCHAR(64) COMMENT '更新人',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    version INT DEFAULT 0 COMMENT '乐观锁版本',
    INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS task_definition (
    id BIGINT PRIMARY KEY COMMENT '任务定义ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_code VARCHAR(64) NOT NULL COMMENT '任务编码',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    task_type VARCHAR(32) NOT NULL COMMENT '任务类型',
    description VARCHAR(512) COMMENT '任务描述',
    config_json TEXT COMMENT '任务配置JSON',
    cron_expression VARCHAR(64) COMMENT 'Cron表达式',
    priority INT DEFAULT 5 COMMENT '优先级',
    timeout_seconds INT DEFAULT 3600 COMMENT '超时时间(秒)',
    retry_count INT DEFAULT 3 COMMENT '重试次数',
    status TINYINT DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    version INT DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    created_by VARCHAR(64) COMMENT '创建人',
    updated_by VARCHAR(64) COMMENT '更新人',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_task_code (tenant_id, task_code),
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务定义表';

CREATE TABLE IF NOT EXISTS task_dependency (
    id BIGINT PRIMARY KEY COMMENT '依赖ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '当前任务ID',
    dependent_task_id BIGINT NOT NULL COMMENT '依赖任务ID',
    dependency_type VARCHAR(32) DEFAULT 'finish_to_start' COMMENT '依赖类型',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_task_id (task_id),
    INDEX idx_dependent_task_id (dependent_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务依赖关系表';

CREATE TABLE IF NOT EXISTS task_instance (
    id BIGINT PRIMARY KEY COMMENT '任务实例ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '任务定义ID',
    instance_no VARCHAR(64) NOT NULL UNIQUE COMMENT '实例编号',
    parent_instance_id BIGINT COMMENT '父实例ID',
    status VARCHAR(32) NOT NULL DEFAULT 'pending' COMMENT '状态: pending, running, success, failed, cancelled, timeout',
    phase VARCHAR(64) COMMENT '当前阶段',
    progress DECIMAL(5,4) DEFAULT 0 COMMENT '进度 0-1',
    input_data TEXT COMMENT '输入数据JSON',
    output_data TEXT COMMENT '输出数据JSON',
    error_detail TEXT COMMENT '错误详情',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    scheduled_at DATETIME COMMENT '计划执行时间',
    retry_count INT DEFAULT 0 COMMENT '当前重试次数',
    worker_node VARCHAR(128) COMMENT '执行节点',
    version INT DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_scheduled_at (scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务实例表';

CREATE TABLE IF NOT EXISTS sla_policy (
    id BIGINT PRIMARY KEY COMMENT 'SLA策略ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    policy_name VARCHAR(128) NOT NULL COMMENT '策略名称',
    policy_code VARCHAR(64) NOT NULL COMMENT '策略编码',
    task_type VARCHAR(32) COMMENT '适用任务类型',
    sla_duration BIGINT NOT NULL COMMENT 'SLA时长(毫秒)',
    warning_threshold DECIMAL(5,4) DEFAULT 0.8 COMMENT '告警阈值',
    escalation_levels TEXT COMMENT '升级层级配置JSON',
    notification_channels TEXT COMMENT '通知渠道配置JSON',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    version INT DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    created_by VARCHAR(64) COMMENT '创建人',
    updated_by VARCHAR(64) COMMENT '更新人',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_policy_code (policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLA策略表';

CREATE TABLE IF NOT EXISTS sla_record (
    id BIGINT PRIMARY KEY COMMENT 'SLA记录ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    policy_id BIGINT NOT NULL COMMENT 'SLA策略ID',
    task_instance_id BIGINT NOT NULL COMMENT '任务实例ID',
    sla_start_time DATETIME NOT NULL COMMENT 'SLA开始时间',
    sla_end_time DATETIME NOT NULL COMMENT 'SLA结束时间',
    actual_end_time DATETIME COMMENT '实际完成时间',
    sla_status VARCHAR(32) DEFAULT 'normal' COMMENT 'SLA状态: normal, warning, overtime, completed',
    current_level INT DEFAULT 0 COMMENT '当前升级层级',
    warning_time DATETIME COMMENT '告警时间',
    escalation_history TEXT COMMENT '升级历史JSON',
    version INT DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_task_instance_id (task_instance_id),
    INDEX idx_sla_status (sla_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SLA记录表';

CREATE TABLE IF NOT EXISTS skill_category (
    id BIGINT PRIMARY KEY COMMENT '技能分类ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    category_name VARCHAR(128) NOT NULL COMMENT '分类名称',
    category_code VARCHAR(64) NOT NULL COMMENT '分类编码',
    parent_id BIGINT COMMENT '父分类ID',
    sort_order INT DEFAULT 0 COMMENT '排序',
    description VARCHAR(512) COMMENT '描述',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能分类表';

CREATE TABLE IF NOT EXISTS skill_definition (
    id BIGINT PRIMARY KEY COMMENT '技能定义ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    skill_name VARCHAR(128) NOT NULL COMMENT '技能名称',
    skill_code VARCHAR(64) NOT NULL COMMENT '技能编码',
    category_id BIGINT NOT NULL COMMENT '分类ID',
    skill_level INT DEFAULT 1 COMMENT '技能等级 1-5',
    description VARCHAR(512) COMMENT '技能描述',
    knowledge_points TEXT COMMENT '知识点JSON',
    evaluation_criteria TEXT COMMENT '评估标准JSON',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_category_id (category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能定义表';

CREATE TABLE IF NOT EXISTS skill_relation (
    id BIGINT PRIMARY KEY COMMENT '技能关系ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    skill_id BIGINT NOT NULL COMMENT '技能ID',
    prerequisite_skill_id BIGINT NOT NULL COMMENT '前置技能ID',
    relation_type VARCHAR(32) DEFAULT 'prerequisite' COMMENT '关系类型',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_skill_id (skill_id),
    INDEX idx_prerequisite_skill_id (prerequisite_skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能依赖关系表';

CREATE TABLE IF NOT EXISTS employee_skill (
    id BIGINT PRIMARY KEY COMMENT '员工技能ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    employee_id BIGINT NOT NULL COMMENT '员工ID',
    skill_id BIGINT NOT NULL COMMENT '技能ID',
    proficiency_level INT DEFAULT 0 COMMENT '熟练度 0-5',
    last_evaluated_at DATETIME COMMENT '最后评估时间',
    evaluator_id BIGINT COMMENT '评估人ID',
    evaluation_note VARCHAR(1024) COMMENT '评估备注',
    learning_progress DECIMAL(5,4) DEFAULT 0 COMMENT '学习进度',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_employee_skill (tenant_id, employee_id, skill_id),
    INDEX idx_employee_id (employee_id),
    INDEX idx_skill_id (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工技能表';

CREATE TABLE IF NOT EXISTS learning_path (
    id BIGINT PRIMARY KEY COMMENT '学习路径ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    path_name VARCHAR(128) NOT NULL COMMENT '路径名称',
    target_role VARCHAR(64) COMMENT '目标角色',
    description VARCHAR(512) COMMENT '描述',
    skill_sequence TEXT COMMENT '技能序列JSON',
    estimated_duration BIGINT COMMENT '预计时长(小时)',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学习路径表';

CREATE TABLE IF NOT EXISTS metric_definition (
    id BIGINT PRIMARY KEY COMMENT '指标定义ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    metric_code VARCHAR(64) NOT NULL COMMENT '指标编码',
    metric_name VARCHAR(128) NOT NULL COMMENT '指标名称',
    metric_type VARCHAR(32) NOT NULL COMMENT '指标类型: counter, gauge, histogram',
    unit VARCHAR(32) COMMENT '单位',
    description VARCHAR(512) COMMENT '描述',
    labels TEXT COMMENT '标签定义JSON',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_metric_code (tenant_id, metric_code),
    INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标定义表';

CREATE TABLE IF NOT EXISTS metric_data (
    id BIGINT PRIMARY KEY COMMENT '指标数据ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    metric_id BIGINT NOT NULL COMMENT '指标ID',
    metric_value DECIMAL(20,6) NOT NULL COMMENT '指标值',
    labels_json TEXT COMMENT '标签值JSON',
    timestamp_ms BIGINT NOT NULL COMMENT '时间戳(毫秒)',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_metric_id (metric_id),
    INDEX idx_timestamp (timestamp_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标数据表';

CREATE TABLE IF NOT EXISTS metric_aggregate (
    id BIGINT PRIMARY KEY COMMENT '聚合结果ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    metric_id BIGINT NOT NULL COMMENT '指标ID',
    aggregate_type VARCHAR(32) NOT NULL COMMENT '聚合类型: sum, avg, max, min, count',
    aggregate_period VARCHAR(32) NOT NULL COMMENT '聚合周期: minute, hour, day, week, month',
    period_start BIGINT NOT NULL COMMENT '周期开始时间',
    period_end BIGINT NOT NULL COMMENT '周期结束时间',
    aggregate_value DECIMAL(20,6) NOT NULL COMMENT '聚合值',
    sample_count BIGINT DEFAULT 0 COMMENT '样本数量',
    labels_json TEXT COMMENT '标签值JSON',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_metric_period (metric_id, aggregate_period, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标聚合结果表';

CREATE TABLE IF NOT EXISTS approval_flow (
    id BIGINT PRIMARY KEY COMMENT '审批流程ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    flow_name VARCHAR(128) NOT NULL COMMENT '流程名称',
    flow_code VARCHAR(64) NOT NULL COMMENT '流程编码',
    flow_type VARCHAR(32) NOT NULL COMMENT '流程类型',
    description VARCHAR(512) COMMENT '描述',
    flow_definition TEXT NOT NULL COMMENT '流程定义JSON',
    version INT DEFAULT 1 COMMENT '版本',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_flow_code (tenant_id, flow_code),
    INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流程表';

CREATE TABLE IF NOT EXISTS approval_instance (
    id BIGINT PRIMARY KEY COMMENT '审批实例ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    flow_id BIGINT NOT NULL COMMENT '流程ID',
    business_key VARCHAR(128) NOT NULL COMMENT '业务Key',
    business_data TEXT COMMENT '业务数据JSON',
    initiator_id BIGINT NOT NULL COMMENT '发起人ID',
    current_node_id VARCHAR(64) COMMENT '当前节点ID',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态: pending, processing, approved, rejected, cancelled',
    started_at DATETIME NOT NULL COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    result VARCHAR(1024) COMMENT '审批结果备注',
    version INT DEFAULT 0 COMMENT '乐观锁版本',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_business_key (business_key),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批实例表';

CREATE TABLE IF NOT EXISTS approval_task (
    id BIGINT PRIMARY KEY COMMENT '审批任务ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    instance_id BIGINT NOT NULL COMMENT '审批实例ID',
    node_id VARCHAR(64) NOT NULL COMMENT '节点ID',
    node_name VARCHAR(128) NOT NULL COMMENT '节点名称',
    approval_type VARCHAR(32) NOT NULL COMMENT '审批类型: any, all, or_sign',
    assignee_id BIGINT COMMENT '审批人ID',
    candidate_users TEXT COMMENT '候选人列表JSON',
    candidate_groups TEXT COMMENT '候选组列表JSON',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态: pending, approved, rejected, delegated, transferred',
    comment VARCHAR(1024) COMMENT '审批意见',
    approved_at DATETIME COMMENT '审批时间',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_instance_id (instance_id),
    INDEX idx_assignee_id (assignee_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批任务表';

CREATE TABLE IF NOT EXISTS flow_design (
    id BIGINT PRIMARY KEY COMMENT '流程设计ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    design_name VARCHAR(128) NOT NULL COMMENT '设计名称',
    design_code VARCHAR(64) NOT NULL COMMENT '设计编码',
    flow_type VARCHAR(32) NOT NULL COMMENT '流程类型',
    description VARCHAR(512) COMMENT '描述',
    node_definitions TEXT NOT NULL COMMENT '节点定义JSON',
    edge_definitions TEXT NOT NULL COMMENT '连线定义JSON',
    design_data TEXT COMMENT '完整设计数据JSON',
    status VARCHAR(32) DEFAULT 'draft' COMMENT '状态: draft, published, archived',
    version INT DEFAULT 1 COMMENT '版本',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    created_by VARCHAR(64) COMMENT '创建人',
    updated_by VARCHAR(64) COMMENT '更新人',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_design_code (tenant_id, design_code),
    INDEX idx_tenant_id (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流程设计表';

CREATE TABLE IF NOT EXISTS backup_record (
    id BIGINT PRIMARY KEY COMMENT '备份记录ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    backup_type VARCHAR(32) NOT NULL COMMENT '备份类型: full, incremental, manual',
    backup_name VARCHAR(128) NOT NULL COMMENT '备份名称',
    source_path VARCHAR(512) COMMENT '源路径',
    target_path VARCHAR(512) NOT NULL COMMENT '目标路径',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    checksum VARCHAR(128) COMMENT '校验和',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态: pending, running, success, failed',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    retention_days INT DEFAULT 30 COMMENT '保留天数',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='备份记录表';

CREATE TABLE IF NOT EXISTS restore_record (
    id BIGINT PRIMARY KEY COMMENT '恢复记录ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    backup_id BIGINT NOT NULL COMMENT '备份ID',
    restore_name VARCHAR(128) NOT NULL COMMENT '恢复名称',
    source_path VARCHAR(512) NOT NULL COMMENT '源路径',
    target_path VARCHAR(512) NOT NULL COMMENT '目标路径',
    status VARCHAR(32) DEFAULT 'pending' COMMENT '状态: pending, running, success, failed',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_backup_id (backup_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='恢复记录表';

CREATE TABLE IF NOT EXISTS tenant_resource_quota (
    id BIGINT PRIMARY KEY COMMENT '配额ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    resource_type VARCHAR(32) NOT NULL COMMENT '资源类型',
    quota_limit BIGINT NOT NULL COMMENT '配额限制',
    quota_used BIGINT DEFAULT 0 COMMENT '已使用量',
    unit VARCHAR(32) COMMENT '单位',
    warning_threshold DECIMAL(5,4) DEFAULT 0.8 COMMENT '告警阈值',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_resource (tenant_id, resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户资源配额表';

CREATE TABLE IF NOT EXISTS tenant_config (
    id BIGINT PRIMARY KEY COMMENT '配置ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    config_key VARCHAR(128) NOT NULL COMMENT '配置键',
    config_value TEXT COMMENT '配置值',
    config_type VARCHAR(32) DEFAULT 'string' COMMENT '配置类型',
    description VARCHAR(512) COMMENT '描述',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_tenant_config_key (tenant_id, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户配置表';

CREATE TABLE IF NOT EXISTS usage_record (
    id BIGINT PRIMARY KEY COMMENT '用量记录ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    resource_type VARCHAR(32) NOT NULL COMMENT '资源类型',
    usage_amount BIGINT NOT NULL COMMENT '使用量',
    unit VARCHAR(32) COMMENT '单位',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    tags_json TEXT COMMENT '标签JSON',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_resource_type (resource_type),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用量记录表';

CREATE TABLE IF NOT EXISTS billing_cycle (
    id BIGINT PRIMARY KEY COMMENT '账单周期ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    cycle_type VARCHAR(32) NOT NULL COMMENT '周期类型: monthly, weekly, daily',
    cycle_code VARCHAR(32) NOT NULL COMMENT '周期编码如202605',
    cycle_start DATE NOT NULL COMMENT '周期开始日期',
    cycle_end DATE NOT NULL COMMENT '周期结束日期',
    total_amount DECIMAL(15,2) DEFAULT 0 COMMENT '总金额',
    status VARCHAR(32) DEFAULT 'unpaid' COMMENT '状态: unpaid, paid, overdue, void',
    paid_at DATETIME COMMENT '支付时间',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_cycle_code (cycle_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单周期表';

CREATE TABLE IF NOT EXISTS billing_item (
    id BIGINT PRIMARY KEY COMMENT '账单明细ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    cycle_id BIGINT NOT NULL COMMENT '账单周期ID',
    resource_type VARCHAR(32) NOT NULL COMMENT '资源类型',
    usage_amount BIGINT NOT NULL COMMENT '使用量',
    unit_price DECIMAL(15,6) NOT NULL COMMENT '单价',
    total_price DECIMAL(15,2) NOT NULL COMMENT '总价',
    unit VARCHAR(32) COMMENT '单位',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    INDEX idx_cycle_id (cycle_id),
    INDEX idx_resource_type (resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账单明细表';

CREATE TABLE IF NOT EXISTS pricing_rule (
    id BIGINT PRIMARY KEY COMMENT '定价规则ID',
    resource_type VARCHAR(32) NOT NULL COMMENT '资源类型',
    billing_mode VARCHAR(32) NOT NULL COMMENT '计费模式: pay_as_you_go, prepaid',
    unit_price DECIMAL(15,6) NOT NULL COMMENT '单价',
    unit VARCHAR(32) COMMENT '单位',
    currency VARCHAR(16) DEFAULT 'CNY' COMMENT '货币',
    tier_config TEXT COMMENT '阶梯配置JSON',
    effective_date DATE NOT NULL COMMENT '生效日期',
    expiry_date DATE COMMENT '失效日期',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定价规则表';

INSERT INTO sys_tenant (id, tenant_code, tenant_name, status, created_at, updated_at) VALUES
(1, 'default', '默认租户', 1, NOW(), NOW());

INSERT INTO sys_user (id, tenant_id, username, password, real_name, status, created_at, updated_at) VALUES
(1, 1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '系统管理员', 1, NOW(), NOW());
