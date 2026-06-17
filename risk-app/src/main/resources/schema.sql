-- 风控规则引擎与告警系统数据库初始化脚本
-- PostgreSQL 14+

-- 规则配置表
CREATE TABLE IF NOT EXISTS risk_rules (
    id BIGSERIAL PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL UNIQUE,
    rule_name VARCHAR(256) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    business_line VARCHAR(64) NOT NULL,
    event_types TEXT,
    priority INTEGER DEFAULT 100,
    short_circuit BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    severity VARCHAR(32) DEFAULT 'MEDIUM',
    dsl_expression TEXT,
    window_config JSONB,
    sequence_config JSONB,
    model_weight DOUBLE PRECISION DEFAULT 0.5,
    threshold DOUBLE PRECISION DEFAULT 0.7,
    escalation_threshold INTEGER,
    suppression_rules TEXT,
    actions TEXT,
    description TEXT,
    version INTEGER DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    created_by VARCHAR(64),
    updated_by VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_rules_business_line ON risk_rules(business_line);
CREATE INDEX IF NOT EXISTS idx_rules_enabled ON risk_rules(enabled);
CREATE INDEX IF NOT EXISTS idx_rules_priority ON risk_rules(priority);
CREATE INDEX IF NOT EXISTS idx_rules_type ON risk_rules(rule_type);
CREATE INDEX IF NOT EXISTS idx_rules_updated_at ON risk_rules(updated_at);

COMMENT ON TABLE risk_rules IS '风控规则定义表';
COMMENT ON COLUMN risk_rules.event_types IS '逗号分隔的事件类型列表';
COMMENT ON COLUMN risk_rules.suppression_rules IS '逗号分隔的需抑制的规则ID';
COMMENT ON COLUMN risk_rules.actions IS '逗号分隔的动作ID列表';

-- 告警记录表
CREATE TABLE IF NOT EXISTS alert_records (
    id BIGSERIAL PRIMARY KEY,
    alert_id VARCHAR(64) NOT NULL UNIQUE,
    fingerprint VARCHAR(128) NOT NULL,
    rule_id VARCHAR(64),
    rule_name VARCHAR(256),
    severity VARCHAR(32) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    business_line VARCHAR(64) NOT NULL,
    description TEXT,
    risk_score DOUBLE PRECISION DEFAULT 0.0,
    rule_hit_count INTEGER DEFAULT 1,
    event_count INTEGER DEFAULT 1,
    first_event_time BIGINT,
    last_event_time BIGINT,
    status VARCHAR(32) DEFAULT 'OPEN',
    triggered_event_ids TEXT,
    suppressed_by VARCHAR(64),
    metadata JSONB,
    actions TEXT,
    acknowledged_by VARCHAR(64),
    acknowledged_at BIGINT,
    resolved_by VARCHAR(64),
    resolved_at BIGINT,
    resolution_notes TEXT,
    false_positive BOOLEAN DEFAULT FALSE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_fingerprint ON alert_records(fingerprint);
CREATE INDEX IF NOT EXISTS idx_alert_rule_id ON alert_records(rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_entity ON alert_records(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_alert_business_line ON alert_records(business_line);
CREATE INDEX IF NOT EXISTS idx_alert_severity ON alert_records(severity);
CREATE INDEX IF NOT EXISTS idx_alert_status ON alert_records(status);
CREATE INDEX IF NOT EXISTS idx_alert_created_at ON alert_records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_last_event ON alert_records(last_event_time DESC);

COMMENT ON TABLE alert_records IS '告警记录表';
COMMENT ON COLUMN alert_records.triggered_event_ids IS '逗号分隔的触发事件ID列表';

-- 模型配置表
CREATE TABLE IF NOT EXISTS model_configs (
    id BIGSERIAL PRIMARY KEY,
    model_id VARCHAR(64) NOT NULL UNIQUE,
    model_name VARCHAR(256) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    model_path VARCHAR(512) NOT NULL,
    feature_names TEXT,
    feature_extractors JSONB,
    default_values JSONB,
    input_name VARCHAR(128),
    output_name VARCHAR(128),
    output_shape TEXT,
    threshold DOUBLE PRECISION DEFAULT 0.5,
    enabled BOOLEAN DEFAULT TRUE,
    weight DOUBLE PRECISION DEFAULT 0.5,
    baseline_metrics JSONB,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(model_name, model_version)
);

CREATE INDEX IF NOT EXISTS idx_model_enabled ON model_configs(enabled);
CREATE INDEX IF NOT EXISTS idx_model_updated_at ON model_configs(updated_at);

COMMENT ON TABLE model_configs IS 'ML模型配置表';
COMMENT ON COLUMN model_configs.feature_names IS '逗号分隔的特征名列表';

-- 动作定义表
CREATE TABLE IF NOT EXISTS action_definitions (
    id BIGSERIAL PRIMARY KEY,
    action_id VARCHAR(64) NOT NULL UNIQUE,
    action_name VARCHAR(256) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    business_line VARCHAR(64),
    enabled BOOLEAN DEFAULT TRUE,
    parameters JSONB,
    webhook_config JSONB,
    retry_config JSONB,
    rate_limit_config JSONB,
    description TEXT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_action_type ON action_definitions(action_type);
CREATE INDEX IF NOT EXISTS idx_action_enabled ON action_definitions(enabled);
CREATE INDEX IF NOT EXISTS idx_action_business_line ON action_definitions(business_line);

COMMENT ON TABLE action_definitions IS '响应动作定义表';

-- 规则命中日志表
CREATE TABLE IF NOT EXISTS rule_hit_logs (
    id BIGSERIAL PRIMARY KEY,
    log_id VARCHAR(64) NOT NULL UNIQUE,
    rule_id VARCHAR(64) NOT NULL,
    rule_name VARCHAR(256),
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(128),
    business_line VARCHAR(64),
    entity_id VARCHAR(128),
    entity_type VARCHAR(64),
    matched BOOLEAN DEFAULT TRUE,
    short_circuited BOOLEAN DEFAULT FALSE,
    rule_score DOUBLE PRECISION,
    model_score DOUBLE PRECISION,
    final_score DOUBLE PRECISION,
    matched_value DOUBLE PRECISION,
    threshold_value DOUBLE PRECISION,
    matched_reasons TEXT,
    matched_events TEXT,
    context JSONB,
    alert_id VARCHAR(64),
    evaluation_latency_ms INTEGER,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hit_rule_id ON rule_hit_logs(rule_id);
CREATE INDEX IF NOT EXISTS idx_hit_event_id ON rule_hit_logs(event_id);
CREATE INDEX IF NOT EXISTS idx_hit_entity ON rule_hit_logs(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_hit_created_at ON rule_hit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_hit_business_line ON rule_hit_logs(business_line);
CREATE INDEX IF NOT EXISTS idx_hit_alert_id ON rule_hit_logs(alert_id);

COMMENT ON TABLE rule_hit_logs IS '规则命中日志表（异步批量写入）';

-- 模型推理日志表
CREATE TABLE IF NOT EXISTS model_inference_logs (
    id BIGSERIAL PRIMARY KEY,
    log_id VARCHAR(64) NOT NULL UNIQUE,
    model_id VARCHAR(64) NOT NULL,
    model_name VARCHAR(256),
    model_version VARCHAR(64),
    event_id VARCHAR(64) NOT NULL,
    input_features JSONB,
    output_score DOUBLE PRECISION,
    raw_output JSONB,
    inference_latency_ms INTEGER,
    is_exception BOOLEAN DEFAULT FALSE,
    error_message TEXT,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_infer_model_id ON model_inference_logs(model_id);
CREATE INDEX IF NOT EXISTS idx_infer_event_id ON model_inference_logs(event_id);
CREATE INDEX IF NOT EXISTS idx_infer_created_at ON model_inference_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_infer_exception ON model_inference_logs(is_exception);

COMMENT ON TABLE model_inference_logs IS '模型推理日志表';

-- 告警处理审计表
CREATE TABLE IF NOT EXISTS alert_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    audit_id VARCHAR(64) NOT NULL UNIQUE,
    alert_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    operator VARCHAR(64),
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    from_severity VARCHAR(32),
    to_severity VARCHAR(32),
    comment TEXT,
    metadata JSONB,
    created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_alert_id ON alert_audit_logs(alert_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON alert_audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action ON alert_audit_logs(action);

COMMENT ON TABLE alert_audit_logs IS '告警处理审计日志表';

-- 事件Schema注册表
CREATE TABLE IF NOT EXISTS event_schemas (
    id BIGSERIAL PRIMARY KEY,
    schema_id VARCHAR(64) NOT NULL UNIQUE,
    business_line VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    required_fields TEXT,
    schema_definition JSONB,
    enabled BOOLEAN DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(business_line, event_type)
);

COMMENT ON TABLE event_schemas IS '事件Schema注册表，用于事件字段校验';
COMMENT ON COLUMN event_schemas.required_fields IS '逗号分隔的必填字段名';
