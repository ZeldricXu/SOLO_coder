--==============================================================================
-- 企业级中间件数据库初始化脚本
--==============================================================================

--------------------------------------------------------------------------------
-- 扩展
--------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

--------------------------------------------------------------------------------
-- 核心实体表
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS core_entities (
    id VARCHAR(64) PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    attributes JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    labels JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_core_entities_type ON core_entities(type);
CREATE INDEX IF NOT EXISTS idx_core_entities_status ON core_entities(status);
CREATE INDEX IF NOT EXISTS idx_core_entities_created_at ON core_entities(created_at);
CREATE INDEX IF NOT EXISTS idx_core_entities_labels ON core_entities USING GIN (labels);

--------------------------------------------------------------------------------
-- 配置定义表
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS config_definitions (
    config_id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    parameters JSONB NOT NULL DEFAULT '{}',
    enabled BOOLEAN NOT NULL DEFAULT true,
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(namespace, version)
);

CREATE INDEX IF NOT EXISTS idx_config_definitions_namespace ON config_definitions(namespace);
CREATE INDEX IF NOT EXISTS idx_config_definitions_enabled ON config_definitions(enabled);

--------------------------------------------------------------------------------
-- 运行实例表
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS run_instances (
    run_id VARCHAR(64) PRIMARY KEY,
    entity_id VARCHAR(64) NOT NULL REFERENCES core_entities(id) ON DELETE CASCADE,
    phase VARCHAR(32) NOT NULL DEFAULT 'initializing',
    progress DOUBLE PRECISION NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    error_detail TEXT,
    metrics JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_run_instances_entity_id ON run_instances(entity_id);
CREATE INDEX IF NOT EXISTS idx_run_instances_phase ON run_instances(phase);
CREATE INDEX IF NOT EXISTS idx_run_instances_started_at ON run_instances(started_at);

--------------------------------------------------------------------------------
-- 统计快照表
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS metrics_snapshots (
    snapshot_id VARCHAR(64) PRIMARY KEY,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    metrics JSONB NOT NULL DEFAULT '{}',
    dimensions JSONB NOT NULL DEFAULT '{}',
    entity_id VARCHAR(64),
    run_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_metrics_snapshots_timestamp ON metrics_snapshots(timestamp);
CREATE INDEX IF NOT EXISTS idx_metrics_snapshots_entity_id ON metrics_snapshots(entity_id);
CREATE INDEX IF NOT EXISTS idx_metrics_snapshots_dimensions ON metrics_snapshots USING GIN (dimensions);

--------------------------------------------------------------------------------
-- 质量规则表
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_rules (
    rule_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    dataset VARCHAR(256) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'warning',
    enabled BOOLEAN NOT NULL DEFAULT true,
    schedule VARCHAR(64),
    parameters JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_quality_rules_type ON quality_rules(rule_type);
CREATE INDEX IF NOT EXISTS idx_quality_rules_dataset ON quality_rules(dataset);
CREATE INDEX IF NOT EXISTS idx_quality_rules_enabled ON quality_rules(enabled);

--------------------------------------------------------------------------------
-- 异常记录表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS quality_anomalies (
    anomaly_id VARCHAR(64) PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL REFERENCES quality_rules(rule_id) ON DELETE CASCADE,
    dataset VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'open',
    severity VARCHAR(16) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}',
    sample_data JSONB NOT NULL DEFAULT '{}',
    detected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_anomalies_rule_id ON quality_anomalies(rule_id);
CREATE INDEX IF NOT EXISTS idx_anomalies_status ON quality_anomalies(status);
CREATE INDEX IF NOT EXISTS idx_anomalies_dataset ON quality_anomalies(dataset);
CREATE INDEX IF NOT EXISTS idx_anomalies_detected_at ON quality_anomalies(detected_at);

--------------------------------------------------------------------------------
-- 数据源元数据表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_source_schemas (
    schema_id VARCHAR(64) PRIMARY KEY,
    source_name VARCHAR(128) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    connection_string TEXT NOT NULL,
    schema_data JSONB NOT NULL DEFAULT '{}',
    statistics JSONB NOT NULL DEFAULT '{}',
    sample_data JSONB NOT NULL DEFAULT '{}',
    last_crawled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(source_name)
);

CREATE INDEX IF NOT EXISTS idx_schemas_source_type ON data_source_schemas(source_type);
CREATE INDEX IF NOT EXISTS idx_schemas_last_crawled ON data_source_schemas(last_crawled_at);

--------------------------------------------------------------------------------
-- 数据血缘表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lineage_graphs (
    graph_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    source VARCHAR(256) NOT NULL,
    graph_data JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lineage_graphs_source ON lineage_graphs(source);
CREATE INDEX IF NOT EXISTS idx_lineage_graphs_created_at ON lineage_graphs(created_at);

--------------------------------------------------------------------------------
-- 通知历史表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_history (
    message_id VARCHAR(64) PRIMARY KEY,
    channel VARCHAR(32) NOT NULL,
    recipients JSONB NOT NULL DEFAULT '[]',
    subject VARCHAR(512),
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    sent_at TIMESTAMPTZ,
    error_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notification_history_channel ON notification_history(channel);
CREATE INDEX IF NOT EXISTS idx_notification_history_status ON notification_history(status);
CREATE INDEX IF NOT EXISTS idx_notification_history_created_at ON notification_history(created_at);

--------------------------------------------------------------------------------
-- CDC检查点表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cdc_checkpoints (
    source_id VARCHAR(64) PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    binlog_position VARCHAR(128),
    lsn VARCHAR(64),
    transaction_id VARCHAR(64),
    last_event_time TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

--------------------------------------------------------------------------------
-- 事件日志表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_log (
    event_id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64),
    entity_id VARCHAR(64),
    data JSONB NOT NULL DEFAULT '{}',
    metadata JSONB NOT NULL DEFAULT '{}',
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_event_log_event_type ON event_log(event_type);
CREATE INDEX IF NOT EXISTS idx_event_log_entity ON event_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_event_log_timestamp ON event_log(timestamp);

--------------------------------------------------------------------------------
-- 版本迁移记录表
#------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(64) PRIMARY KEY,
    description VARCHAR(256) NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    checksum VARCHAR(64),
    execution_time INTEGER
);

--==============================================================================
-- 初始化数据
--==============================================================================

INSERT INTO schema_migrations (version, description, execution_time)
VALUES ('000001', '初始Schema创建', 0)
ON CONFLICT (version) DO NOTHING;

INSERT INTO config_definitions (config_id, namespace, version, parameters, enabled)
VALUES
    ('cfg_default', 'default', 1, '{"timeout": 30, "retries": 3, "log_level": "info"}', true),
    ('cfg_development', 'development', 1, '{"timeout": 60, "retries": 5, "log_level": "debug"}', true),
    ('cfg_production', 'production', 1, '{"timeout": 30, "retries": 3, "log_level": "warn"}', true)
ON CONFLICT DO NOTHING;

--==============================================================================
-- 行级安全策略 (RLS)
--==============================================================================
ALTER TABLE core_entities ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_instances ENABLE ROW LEVEL SECURITY;

CREATE POLICY "core_entities_select" ON core_entities
    FOR SELECT USING (true);

CREATE POLICY "run_instances_select" ON run_instances
    FOR SELECT USING (true);

--==============================================================================
-- 自动更新时间戳触发器
--==============================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_core_entities_updated_at
    BEFORE UPDATE ON core_entities
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_config_definitions_updated_at
    BEFORE UPDATE ON config_definitions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_quality_rules_updated_at
    BEFORE UPDATE ON quality_rules
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_data_source_schemas_updated_at
    BEFORE UPDATE ON data_source_schemas
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_lineage_graphs_updated_at
    BEFORE UPDATE ON lineage_graphs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
