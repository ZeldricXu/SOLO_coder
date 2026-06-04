-- 边缘节点表扩展：添加层级角色和父节点
ALTER TABLE edge_nodes ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'edge';
ALTER TABLE edge_nodes ADD COLUMN IF NOT EXISTS parent_node_id UUID REFERENCES edge_nodes(id) ON DELETE SET NULL;
CREATE INDEX idx_edge_nodes_role ON edge_nodes(role);
CREATE INDEX idx_edge_nodes_parent ON edge_nodes(parent_node_id);

-- 域名配置表扩展：添加内容类型
ALTER TABLE domain_configs ADD COLUMN IF NOT EXISTS content_type VARCHAR(20);

-- 内容热度记录表
CREATE TABLE IF NOT EXISTS content_heat_records (
    id UUID PRIMARY KEY,
    url VARCHAR(1024) NOT NULL,
    region VARCHAR(100) NOT NULL,
    access_count BIGINT DEFAULT 0,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_content_heat_url ON content_heat_records(url);
CREATE INDEX idx_content_heat_region ON content_heat_records(region);
CREATE INDEX idx_content_heat_recorded ON content_heat_records(recorded_at);

-- 预热计划表
CREATE TABLE IF NOT EXISTS preheat_plans (
    id UUID PRIMARY KEY,
    content_urls JSONB NOT NULL DEFAULT '[]',
    target_regions JSONB NOT NULL DEFAULT '[]',
    scheduled_at TIMESTAMP WITH TIME ZONE,
    bandwidth_limit_bps BIGINT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_preheat_plans_status ON preheat_plans(status);
CREATE INDEX idx_preheat_plans_scheduled ON preheat_plans(scheduled_at);

-- A/B测试实验表
CREATE TABLE IF NOT EXISTS ab_experiments (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    control_strategy VARCHAR(50) NOT NULL,
    treatment_strategy VARCHAR(50) NOT NULL,
    traffic_percentage INTEGER NOT NULL DEFAULT 50,
    target_nodes JSONB NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    metrics JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_ab_experiments_status ON ab_experiments(status);
CREATE INDEX idx_ab_experiments_name ON ab_experiments(name);

-- A/B测试指标表
CREATE TABLE IF NOT EXISTS ab_experiment_metrics (
    id UUID PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES ab_experiments(id) ON DELETE CASCADE,
    group_type VARCHAR(20) NOT NULL,
    sample_size INTEGER DEFAULT 0,
    cache_hit_rate DOUBLE PRECISION DEFAULT 0,
    avg_latency_ms DOUBLE PRECISION DEFAULT 0,
    origin_fetch_rate DOUBLE PRECISION DEFAULT 0,
    user_qoe_score DOUBLE PRECISION DEFAULT 0,
    collected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ab_metrics_experiment ON ab_experiment_metrics(experiment_id);
CREATE INDEX idx_ab_metrics_group ON ab_experiment_metrics(group_type);
CREATE INDEX idx_ab_metrics_collected ON ab_experiment_metrics(collected_at);
