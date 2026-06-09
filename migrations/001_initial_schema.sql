-- CloudCI Initial Database Schema

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Pipelines table
CREATE TABLE pipelines (
    id VARCHAR(26) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    project_id VARCHAR(100) NOT NULL,
    description TEXT,
    definition JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    version INTEGER NOT NULL DEFAULT 1,
    labels JSONB,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_pipelines_name ON pipelines(name);
CREATE INDEX idx_pipelines_project_id ON pipelines(project_id);
CREATE INDEX idx_pipelines_status ON pipelines(status);
CREATE INDEX idx_pipelines_deleted_at ON pipelines(deleted_at);

-- Pipeline executions table
CREATE TABLE pipeline_executions (
    id VARCHAR(26) PRIMARY KEY,
    pipeline_id VARCHAR(26) NOT NULL,
    pipeline_name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    project_id VARCHAR(100) NOT NULL,
    trigger_source VARCHAR(20) NOT NULL,
    trigger_type VARCHAR(30) NOT NULL,
    commit VARCHAR(64),
    branch VARCHAR(255),
    tag VARCHAR(100),
    ref VARCHAR(255),
    message TEXT,
    author VARCHAR(255),
    author_email VARCHAR(255),
    event_id VARCHAR(26),
    variables JSONB,
    parameters JSONB,
    queued_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_sec BIGINT,
    timeout_at TIMESTAMP WITH TIME ZONE,
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    canceled_at TIMESTAMP WITH TIME ZONE,
    canceled_by VARCHAR(100),
    error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pipeline_executions_pipeline_id ON pipeline_executions(pipeline_id);
CREATE INDEX idx_pipeline_executions_status ON pipeline_executions(status);
CREATE INDEX idx_pipeline_executions_project_id ON pipeline_executions(project_id);
CREATE INDEX idx_pipeline_executions_branch ON pipeline_executions(branch);
CREATE INDEX idx_pipeline_executions_tag ON pipeline_executions(tag);
CREATE INDEX idx_pipeline_executions_event_id ON pipeline_executions(event_id);
CREATE INDEX idx_pipeline_executions_created_at ON pipeline_executions(created_at);

-- Stage executions table
CREATE TABLE stage_executions (
    id VARCHAR(26) PRIMARY KEY,
    execution_id VARCHAR(26) NOT NULL,
    stage_name VARCHAR(255) NOT NULL,
    stage_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    plugin_name VARCHAR(100),
    plugin_version VARCHAR(50),
    image VARCHAR(500),
    depends_on JSONB,
    env JSONB,
    commands JSONB,
    attempt INTEGER NOT NULL DEFAULT 1,
    max_attempts INTEGER NOT NULL DEFAULT 1,
    allow_failure BOOLEAN NOT NULL DEFAULT false,
    worker_id VARCHAR(100),
    node_id VARCHAR(100),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_sec BIGINT,
    timeout_at TIMESTAMP WITH TIME ZONE,
    exit_code INTEGER,
    error TEXT,
    output JSONB,
    resources JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_stage_executions_execution_id ON stage_executions(execution_id);
CREATE INDEX idx_stage_executions_status ON stage_executions(status);
CREATE INDEX idx_stage_executions_stage_type ON stage_executions(stage_type);

-- Webhook events table
CREATE TABLE webhook_events (
    id VARCHAR(26) PRIMARY KEY,
    source VARCHAR(20) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    deduplication_key VARCHAR(255),
    project_id VARCHAR(100),
    repo_name VARCHAR(255),
    repo_url VARCHAR(500),
    commit VARCHAR(64),
    branch VARCHAR(255),
    tag VARCHAR(100),
    ref VARCHAR(255),
    message TEXT,
    author VARCHAR(255),
    author_email VARCHAR(255),
    pull_request_id VARCHAR(100),
    pull_request_title VARCHAR(500),
    payload JSONB,
    headers JSONB,
    signature VARCHAR(255),
    signature_valid BOOLEAN,
    processed BOOLEAN NOT NULL DEFAULT false,
    processing_error TEXT,
    processed_at TIMESTAMP WITH TIME ZONE,
    matched_pipelines JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_webhook_events_source ON webhook_events(source);
CREATE INDEX idx_webhook_events_event_type ON webhook_events(event_type);
CREATE INDEX idx_webhook_events_deduplication_key ON webhook_events(deduplication_key);
CREATE INDEX idx_webhook_events_project_id ON webhook_events(project_id);
CREATE INDEX idx_webhook_events_processed ON webhook_events(processed);
CREATE INDEX idx_webhook_events_created_at ON webhook_events(created_at);

-- Plugins table
CREATE TABLE plugins (
    id VARCHAR(26) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(50) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description TEXT,
    author VARCHAR(255),
    icon VARCHAR(500),
    binary_path VARCHAR(500) NOT NULL,
    command VARCHAR(255),
    args JSONB,
    env JSONB,
    config_schema JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    health_check_endpoint VARCHAR(255),
    last_health_check TIMESTAMP WITH TIME ZONE,
    health_status VARCHAR(20),
    tags JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_plugin_name_version ON plugins(name, version);
CREATE INDEX idx_plugins_type ON plugins(type);
CREATE INDEX idx_plugins_status ON plugins(status);

-- Secrets table
CREATE TABLE secrets (
    id VARCHAR(26) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    source VARCHAR(20) NOT NULL,
    vault_path VARCHAR(500),
    vault_key VARCHAR(100),
    env_var_name VARCHAR(255),
    project_id VARCHAR(100),
    allowed_pipelines JSONB,
    allowed_stages JSONB,
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 1,
    created_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_secrets_name ON secrets(name);
CREATE INDEX idx_secrets_project_id ON secrets(project_id);

-- Secret usage logs table
CREATE TABLE secret_usage_logs (
    id VARCHAR(26) PRIMARY KEY,
    secret_id VARCHAR(26) NOT NULL,
    secret_name VARCHAR(255) NOT NULL,
    execution_id VARCHAR(26) NOT NULL,
    pipeline_id VARCHAR(26) NOT NULL,
    stage_name VARCHAR(255),
    requested_by VARCHAR(100),
    ip_address VARCHAR(45),
    success BOOLEAN NOT NULL,
    reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_secret_usage_logs_secret_id ON secret_usage_logs(secret_id);
CREATE INDEX idx_secret_usage_logs_execution_id ON secret_usage_logs(execution_id);
CREATE INDEX idx_secret_usage_logs_pipeline_id ON secret_usage_logs(pipeline_id);
CREATE INDEX idx_secret_usage_logs_created_at ON secret_usage_logs(created_at);

-- Artifact records table
CREATE TABLE artifact_records (
    id VARCHAR(26) PRIMARY KEY,
    execution_id VARCHAR(26) NOT NULL,
    stage_id VARCHAR(26),
    project_id VARCHAR(100),
    name VARCHAR(255) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    size BIGINT NOT NULL,
    content_type VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'uploading',
    storage_bucket VARCHAR(100) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    digest VARCHAR(64),
    labels JSONB,
    uploaded_by VARCHAR(100),
    uploaded_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    download_count INTEGER NOT NULL DEFAULT 0,
    last_download TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_artifact_records_execution_id ON artifact_records(execution_id);
CREATE INDEX idx_artifact_records_stage_id ON artifact_records(stage_id);
CREATE INDEX idx_artifact_records_project_id ON artifact_records(project_id);
CREATE INDEX idx_artifact_records_storage_key ON artifact_records(storage_key);
CREATE INDEX idx_artifacts_expires_at ON artifact_records(expires_at);

-- Log records table
CREATE TABLE log_records (
    id VARCHAR(26) PRIMARY KEY,
    execution_id VARCHAR(26) NOT NULL,
    stage_id VARCHAR(26),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    stream VARCHAR(20),
    line_number BIGINT,
    plugin_name VARCHAR(100),
    data JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_log_records_execution_id ON log_records(execution_id);
CREATE INDEX idx_log_records_stage_id ON log_records(stage_id);
CREATE INDEX idx_log_records_timestamp ON log_records(timestamp);

-- Trigger scheduled jobs table
CREATE TABLE scheduled_triggers (
    id VARCHAR(26) PRIMARY KEY,
    pipeline_id VARCHAR(26) NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    timezone VARCHAR(50) NOT NULL DEFAULT 'UTC',
    variables JSONB,
    next_run TIMESTAMP WITH TIME ZONE NOT NULL,
    last_run TIMESTAMP WITH TIME ZONE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_scheduled_triggers_pipeline_id ON scheduled_triggers(pipeline_id);
CREATE INDEX idx_scheduled_triggers_next_run ON scheduled_triggers(next_run);
CREATE INDEX idx_scheduled_triggers_enabled ON scheduled_triggers(enabled);
