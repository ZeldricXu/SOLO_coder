-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================
-- Tenants Table
-- ============================================
CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) UNIQUE NOT NULL,
    api_key_hash VARCHAR(255) NOT NULL,
    qps_limit INT NOT NULL DEFAULT 100,
    rate_limit_per_minute INT NOT NULL DEFAULT 1000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenants_api_key ON tenants(api_key);
CREATE INDEX IF NOT EXISTS idx_tenants_api_key_hash ON tenants(api_key_hash);
CREATE INDEX IF NOT EXISTS idx_tenants_created_at ON tenants(created_at);

-- ============================================
-- Models Table
-- ============================================
CREATE TABLE IF NOT EXISTS models (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) UNIQUE NOT NULL,
    category VARCHAR(100) NOT NULL,
    description TEXT,
    latest_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_models_name ON models(name);
CREATE INDEX IF NOT EXISTS idx_models_category ON models(category);
CREATE INDEX IF NOT EXISTS idx_models_created_at ON models(created_at);

-- ============================================
-- Model Versions Table
-- ============================================
CREATE TABLE IF NOT EXISTS model_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    model_id UUID NOT NULL REFERENCES models(id) ON DELETE CASCADE,
    version INT NOT NULL,
    framework VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    minio_bucket VARCHAR(255),
    minio_object_path VARCHAR(500),
    gpu_memory_required_mb INT,
    input_schema JSONB,
    output_schema JSONB,
    preprocess_pipeline JSONB,
    postprocess_pipeline JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_model_versions_model_id_version ON model_versions(model_id, version);
CREATE INDEX IF NOT EXISTS idx_model_versions_model_id ON model_versions(model_id);
CREATE INDEX IF NOT EXISTS idx_model_versions_status ON model_versions(status);
CREATE INDEX IF NOT EXISTS idx_model_versions_framework ON model_versions(framework);
CREATE INDEX IF NOT EXISTS idx_model_versions_created_at ON model_versions(created_at);

-- ============================================
-- GPU Devices Table
-- ============================================
CREATE TABLE IF NOT EXISTS gpu_devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    node_id VARCHAR(255) NOT NULL,
    gpu_uuid VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    total_memory_mb INT NOT NULL,
    driver_version VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_gpu_devices_node_id ON gpu_devices(node_id);
CREATE INDEX IF NOT EXISTS idx_gpu_devices_name ON gpu_devices(name);
CREATE INDEX IF NOT EXISTS idx_gpu_devices_total_memory_mb ON gpu_devices(total_memory_mb);
CREATE INDEX IF NOT EXISTS idx_gpu_devices_created_at ON gpu_devices(created_at);

-- ============================================
-- Model Deployments Table
-- ============================================
CREATE TABLE IF NOT EXISTS model_deployments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    model_version_id UUID NOT NULL REFERENCES model_versions(id) ON DELETE CASCADE,
    gpu_device_id UUID NOT NULL REFERENCES gpu_devices(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    loaded_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    request_count BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_model_deployments_model_version_id ON model_deployments(model_version_id);
CREATE INDEX IF NOT EXISTS idx_model_deployments_gpu_device_id ON model_deployments(gpu_device_id);
CREATE INDEX IF NOT EXISTS idx_model_deployments_status ON model_deployments(status);
CREATE INDEX IF NOT EXISTS idx_model_deployments_last_used_at ON model_deployments(last_used_at);
CREATE INDEX IF NOT EXISTS idx_model_deployments_loaded_at ON model_deployments(loaded_at);

-- ============================================
-- Experiments Table
-- ============================================
CREATE TABLE IF NOT EXISTS experiments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) UNIQUE NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'draft',
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_experiments_name ON experiments(name);
CREATE INDEX IF NOT EXISTS idx_experiments_model_name ON experiments(model_name);
CREATE INDEX IF NOT EXISTS idx_experiments_status ON experiments(status);
CREATE INDEX IF NOT EXISTS idx_experiments_start_time ON experiments(start_time);
CREATE INDEX IF NOT EXISTS idx_experiments_end_time ON experiments(end_time);
CREATE INDEX IF NOT EXISTS idx_experiments_created_at ON experiments(created_at);

-- ============================================
-- Experiment Groups Table
-- ============================================
CREATE TABLE IF NOT EXISTS experiment_groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    experiment_id UUID NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    group_name VARCHAR(100) NOT NULL,
    model_version_id UUID NOT NULL REFERENCES model_versions(id) ON DELETE CASCADE,
    traffic_percent INT NOT NULL,
    is_control BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_experiment_groups_experiment_id_group_name ON experiment_groups(experiment_id, group_name);
CREATE INDEX IF NOT EXISTS idx_experiment_groups_experiment_id ON experiment_groups(experiment_id);
CREATE INDEX IF NOT EXISTS idx_experiment_groups_model_version_id ON experiment_groups(model_version_id);
CREATE INDEX IF NOT EXISTS idx_experiment_groups_is_control ON experiment_groups(is_control);

-- ============================================
-- Experiment Metrics Table
-- ============================================
CREATE TABLE IF NOT EXISTS experiment_metrics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    experiment_id UUID NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    metric_name VARCHAR(255) NOT NULL,
    metric_type VARCHAR(50) NOT NULL,
    description TEXT,
    unit VARCHAR(50)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_experiment_metrics_experiment_id_metric_name ON experiment_metrics(experiment_id, metric_name);
CREATE INDEX IF NOT EXISTS idx_experiment_metrics_experiment_id ON experiment_metrics(experiment_id);
CREATE INDEX IF NOT EXISTS idx_experiment_metrics_metric_type ON experiment_metrics(metric_type);

-- ============================================
-- Experiment Results Table
-- ============================================
CREATE TABLE IF NOT EXISTS experiment_results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    experiment_id UUID NOT NULL REFERENCES experiments(id) ON DELETE CASCADE,
    group_name VARCHAR(100) NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    sample_count BIGINT NOT NULL DEFAULT 0,
    mean_value DOUBLE PRECISION,
    std_value DOUBLE PRECISION,
    p95_value DOUBLE PRECISION,
    p99_value DOUBLE PRECISION,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_experiment_results_experiment_group_metric ON experiment_results(experiment_id, group_name, metric_name);
CREATE INDEX IF NOT EXISTS idx_experiment_results_experiment_id ON experiment_results(experiment_id);
CREATE INDEX IF NOT EXISTS idx_experiment_results_group_name ON experiment_results(group_name);
CREATE INDEX IF NOT EXISTS idx_experiment_results_metric_name ON experiment_results(metric_name);
CREATE INDEX IF NOT EXISTS idx_experiment_results_computed_at ON experiment_results(computed_at);

-- ============================================
-- Routing Rules Table
-- ============================================
CREATE TABLE IF NOT EXISTS routing_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    model_name VARCHAR(255) UNIQUE NOT NULL,
    strategy VARCHAR(100) NOT NULL,
    config JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_routing_rules_model_name ON routing_rules(model_name);
CREATE INDEX IF NOT EXISTS idx_routing_rules_strategy ON routing_rules(strategy);
CREATE INDEX IF NOT EXISTS idx_routing_rules_created_at ON routing_rules(created_at);
CREATE INDEX IF NOT EXISTS idx_routing_rules_updated_at ON routing_rules(updated_at);
