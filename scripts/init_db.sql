CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE namespaces (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) UNIQUE NOT NULL,
    display_name VARCHAR(255),
    description TEXT,
    gpu_quota_min FLOAT DEFAULT 0.1,
    gpu_quota_max FLOAT DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE TABLE models (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    namespace_id UUID NOT NULL REFERENCES namespaces(id),
    namespace VARCHAR(255) NOT NULL,
    description TEXT,
    task_type VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    labels JSONB DEFAULT '{}'::jsonb,
    UNIQUE(namespace, name)
);

CREATE TABLE model_versions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    model_id UUID NOT NULL REFERENCES models(id),
    version VARCHAR(50) NOT NULL,
    format VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'pending',
    signature JSONB DEFAULT '[]'::jsonb,
    file_path VARCHAR(1024),
    gpu_memory_mb BIGINT DEFAULT 0,
    created_by UUID,
    checksum VARCHAR(255),
    training_dataset_ref VARCHAR(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB DEFAULT '{}'::jsonb,
    UNIQUE(model_id, version)
);

CREATE TABLE inference_instances (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    model_version_id UUID NOT NULL REFERENCES model_versions(id),
    model_name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    instance_address VARCHAR(255),
    gpu_device_id INT,
    status VARCHAR(50) NOT NULL,
    current_load INT DEFAULT 0,
    gpu_memory_used_mb BIGINT DEFAULT 0,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inference_logs (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(255) NOT NULL,
    trace_id VARCHAR(255),
    model_name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    instance_id UUID,
    input_shapes JSONB,
    batch_size INT,
    latency_ms BIGINT,
    gpu_memory_used_mb BIGINT,
    status_code INT,
    error_message TEXT,
    outputs JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inference_logs_model_version ON inference_logs(model_name, version, created_at);
CREATE INDEX idx_inference_logs_trace_id ON inference_logs(trace_id);
CREATE INDEX idx_inference_logs_created_at ON inference_logs(created_at DESC);

CREATE TABLE ab_test_configs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    model_id UUID NOT NULL REFERENCES models(id),
    model_name VARCHAR(255) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    version_a VARCHAR(50) NOT NULL,
    version_b VARCHAR(50) NOT NULL,
    traffic_split_a INT NOT NULL DEFAULT 50,
    traffic_split_b INT NOT NULL DEFAULT 50,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    primary_metric VARCHAR(100),
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP,
    created_by UUID
);

CREATE TABLE ab_test_metrics (
    id BIGSERIAL PRIMARY KEY,
    ab_test_id UUID NOT NULL REFERENCES ab_test_configs(id),
    version VARCHAR(50) NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    request_count BIGINT DEFAULT 0,
    p50_latency_ms FLOAT,
    p95_latency_ms FLOAT,
    p99_latency_ms FLOAT,
    error_rate FLOAT,
    business_metrics JSONB
);

CREATE TABLE prediction_distributions (
    id BIGSERIAL PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    label_distribution JSONB NOT NULL,
    total_samples BIGINT NOT NULL,
    drift_score FLOAT,
    is_alert BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_prediction_distributions_window ON prediction_distributions(model_name, version, window_start);

CREATE TABLE tenant_usage (
    id BIGSERIAL PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    date DATE NOT NULL,
    gpu_usage_seconds BIGINT DEFAULT 0,
    inference_count BIGINT DEFAULT 0,
    cost_amount DECIMAL(10, 2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(namespace, date)
);

CREATE TABLE prediction_ma_history (
    id BIGSERIAL PRIMARY KEY,
    model_name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    moving_avg FLOAT NOT NULL,
    std_dev FLOAT,
    trend_direction VARCHAR(20),
    is_alert BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_prediction_ma_window ON prediction_ma_history(model_name, version, window_start);
