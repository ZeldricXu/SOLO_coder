-- 边缘节点表
CREATE TABLE IF NOT EXISTS edge_nodes (
    id UUID PRIMARY KEY,
    ip_address VARCHAR(45) NOT NULL,
    datacenter VARCHAR(100) NOT NULL,
    region VARCHAR(100) NOT NULL,
    bandwidth_capacity BIGINT NOT NULL,
    bandwidth_usage DOUBLE PRECISION DEFAULT 0.0,
    storage_capacity BIGINT NOT NULL,
    current_load DOUBLE PRECISION DEFAULT 0.0,
    status VARCHAR(20) NOT NULL DEFAULT 'online',
    weight INTEGER DEFAULT 100,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_heartbeat TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_edge_nodes_status ON edge_nodes(status);
CREATE INDEX idx_edge_nodes_region ON edge_nodes(region);
CREATE INDEX idx_edge_nodes_datacenter ON edge_nodes(datacenter);

-- 域名配置表
CREATE TABLE IF NOT EXISTS domain_configs (
    id UUID PRIMARY KEY,
    domain VARCHAR(255) UNIQUE NOT NULL,
    origin_server VARCHAR(255) NOT NULL,
    cache_ttl INTEGER DEFAULT 3600,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_domain_configs_domain ON domain_configs(domain);

-- 缓存规则表
CREATE TABLE IF NOT EXISTS cache_rules (
    id UUID PRIMARY KEY,
    domain_config_id UUID NOT NULL REFERENCES domain_configs(id) ON DELETE CASCADE,
    domain VARCHAR(255) NOT NULL,
    path_pattern VARCHAR(255) NOT NULL,
    eviction_policy VARCHAR(20) NOT NULL DEFAULT 'LRU',
    ttl_seconds INTEGER NOT NULL DEFAULT 3600,
    priority INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    ignore_query_params TEXT[] DEFAULT '{}',
    vary_by_ua BOOLEAN DEFAULT FALSE,
    vary_by_referer BOOLEAN DEFAULT FALSE,
    max_size_bytes BIGINT DEFAULT 1073741824,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_cache_rules_domain_config ON cache_rules(domain_config_id);
CREATE INDEX idx_cache_rules_path_pattern ON cache_rules(path_pattern);

-- TLS证书表
CREATE TABLE IF NOT EXISTS tls_certificates (
    id UUID PRIMARY KEY,
    domain VARCHAR(255) UNIQUE NOT NULL,
    certificate_pem TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL,
    issuer VARCHAR(255),
    not_before TIMESTAMP WITH TIME ZONE NOT NULL,
    not_after TIMESTAMP WITH TIME ZONE NOT NULL,
    auto_renew BOOLEAN DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tls_certificates_domain ON tls_certificates(domain);
CREATE INDEX idx_tls_certificates_status ON tls_certificates(status);
CREATE INDEX idx_tls_certificates_not_after ON tls_certificates(not_after);

-- 节点指标表
CREATE TABLE IF NOT EXISTS node_metrics (
    id UUID PRIMARY KEY,
    node_id UUID NOT NULL REFERENCES edge_nodes(id) ON DELETE CASCADE,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    qps DOUBLE PRECISION DEFAULT 0,
    bandwidth_usage DOUBLE PRECISION DEFAULT 0,
    cache_hit_rate DOUBLE PRECISION DEFAULT 0,
    origin_fetch_rate DOUBLE PRECISION DEFAULT 0,
    error_rate_4xx DOUBLE PRECISION DEFAULT 0,
    error_rate_5xx DOUBLE PRECISION DEFAULT 0,
    active_connections INTEGER DEFAULT 0,
    memory_usage DOUBLE PRECISION DEFAULT 0,
    cpu_usage DOUBLE PRECISION DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_node_metrics_node_id ON node_metrics(node_id);
CREATE INDEX idx_node_metrics_timestamp ON node_metrics(timestamp);

-- 告警表
CREATE TABLE IF NOT EXISTS alerts (
    id UUID PRIMARY KEY,
    alert_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    node_id UUID REFERENCES edge_nodes(id) ON DELETE SET NULL,
    message TEXT NOT NULL,
    acknowledged BOOLEAN DEFAULT FALSE,
    resolved BOOLEAN DEFAULT FALSE,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_alerts_node_id ON alerts(node_id);
CREATE INDEX idx_alerts_severity ON alerts(severity);
CREATE INDEX idx_alerts_resolved ON alerts(resolved);
CREATE INDEX idx_alerts_created_at ON alerts(created_at);

-- 配置版本表
CREATE TABLE IF NOT EXISTS config_versions (
    id UUID PRIMARY KEY,
    config_type VARCHAR(50) NOT NULL,
    version BIGINT NOT NULL,
    data JSONB NOT NULL,
    created_by VARCHAR(100),
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_config_versions_type ON config_versions(config_type);
CREATE INDEX idx_config_versions_version ON config_versions(version);

-- 配置部署记录表
CREATE TABLE IF NOT EXISTS config_deployments (
    id UUID PRIMARY KEY,
    config_version_id UUID NOT NULL REFERENCES config_versions(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    target_nodes UUID[] DEFAULT '{}',
    success_nodes UUID[] DEFAULT '{}',
    failed_nodes UUID[] DEFAULT '{}',
    canary_percent INTEGER DEFAULT 100,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_config_deployments_status ON config_deployments(status);
CREATE INDEX idx_config_deployments_config_version ON config_deployments(config_version_id);

-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_logs (
    id UUID PRIMARY KEY,
    operation_type VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID,
    operator VARCHAR(100),
    description TEXT,
    before_data JSONB,
    after_data JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_operation_logs_entity ON operation_logs(entity_type, entity_id);
CREATE INDEX idx_operation_logs_created_at ON operation_logs(created_at);
