-- CI/CD Platform Database Initialization Script

-- Create database
-- CREATE DATABASE cicd;

-- Connect to cicd database
\c cicd;

-- Create extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Create enum types
CREATE TYPE pipeline_status AS ENUM ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED');
CREATE TYPE step_type AS ENUM ('SCRIPT', 'DOCKER', 'PUSH', 'DEPLOY', 'KUBECTL', 'CALL_WEBHOOK', 'APPROVAL');
CREATE TYPE deployment_strategy AS ENUM ('ROLLING', 'BLUE_GREEN', 'CANARY');
CREATE TYPE trigger_type AS ENUM ('MANUAL', 'WEBHOOK', 'SCHEDULED', 'APPROVAL');
CREATE TYPE role_type AS ENUM ('PLATFORM_ADMIN', 'PROJECT_OWNER', 'DEVELOPER', 'VIEWER');
CREATE TYPE notification_channel AS ENUM ('DINGTALK', 'FEISHU', 'WECOM', 'EMAIL', 'SLACK');
CREATE TYPE artifact_type AS ENUM ('JAR', 'WAR', 'DOCKER', 'NPM', 'ZIP', 'OTHER');
CREATE TYPE approval_mode AS ENUM ('ALL', 'ANY');
CREATE TYPE approval_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED');

-- Create tables
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    real_name VARCHAR(50),
    avatar_url VARCHAR(255),
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    role role_type NOT NULL,
    project_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, role, project_id)
);

CREATE TABLE projects (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    git_url VARCHAR(255),
    git_auth_type VARCHAR(20) DEFAULT 'NONE',
    git_username VARCHAR(50),
    git_token VARCHAR(255),
    git_ssh_key TEXT,
    webhook_secret VARCHAR(255),
    owner VARCHAR(50),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pipelines (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    yaml_definition TEXT NOT NULL,
    parameters JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pipeline_executions (
    id BIGSERIAL PRIMARY KEY,
    pipeline_id BIGINT NOT NULL REFERENCES pipelines(id),
    execution_number INT NOT NULL,
    status pipeline_status NOT NULL DEFAULT 'PENDING',
    branch_name VARCHAR(100),
    git_commit_sha VARCHAR(40),
    git_commit_message TEXT,
    trigger_type trigger_type NOT NULL,
    triggered_by VARCHAR(50),
    params JSONB,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_seconds INT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(pipeline_id, execution_number)
);

CREATE INDEX idx_pipeline_executions_status ON pipeline_executions(status);
CREATE INDEX idx_pipeline_executions_pipeline ON pipeline_executions(pipeline_id, created_at DESC);

CREATE TABLE stage_executions (
    id BIGSERIAL PRIMARY KEY,
    pipeline_execution_id BIGINT NOT NULL REFERENCES pipeline_executions(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    stage_order INT NOT NULL,
    status pipeline_status NOT NULL DEFAULT 'PENDING',
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_seconds INT
);

CREATE TABLE job_executions (
    id BIGSERIAL PRIMARY KEY,
    stage_execution_id BIGINT NOT NULL REFERENCES stage_executions(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    job_order INT NOT NULL,
    status pipeline_status NOT NULL DEFAULT 'PENDING',
    runner_id BIGINT,
    runner_name VARCHAR(100),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_seconds INT,
    workspace_path VARCHAR(500)
);

CREATE TABLE step_executions (
    id BIGSERIAL PRIMARY KEY,
    job_execution_id BIGINT NOT NULL REFERENCES job_executions(id) ON DELETE CASCADE,
    name VARCHAR(100),
    type step_type NOT NULL,
    step_order INT NOT NULL,
    status pipeline_status NOT NULL DEFAULT 'PENDING',
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_seconds INT,
    exit_code INT,
    output TEXT,
    error_message TEXT,
    config JSONB
);

CREATE TABLE environments (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    name VARCHAR(50) NOT NULL,
    description TEXT,
    k8s_cluster VARCHAR(100),
    k8s_namespace VARCHAR(50),
    api_url VARCHAR(255),
    deployment_strategy deployment_strategy DEFAULT 'ROLLING',
    require_approval BOOLEAN DEFAULT false,
    approvers TEXT[],
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, name)
);

CREATE TABLE environment_variables (
    id BIGSERIAL PRIMARY KEY,
    environment_id BIGINT NOT NULL REFERENCES environments(id) ON DELETE CASCADE,
    key VARCHAR(100) NOT NULL,
    value TEXT NOT NULL,
    sensitive BOOLEAN DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(environment_id, key)
);

CREATE TABLE deployments (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    service_name VARCHAR(100) NOT NULL,
    version VARCHAR(100) NOT NULL,
    environment_name VARCHAR(50) NOT NULL,
    deployment_strategy deployment_strategy NOT NULL,
    status pipeline_status NOT NULL DEFAULT 'PENDING',
    pipeline_execution_id BIGINT REFERENCES pipeline_executions(id),
    deployed_by VARCHAR(50),
    deployed_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_seconds INT,
    output TEXT,
    error_message TEXT,
    description TEXT,
    config JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_deployments_project ON deployments(project_id, created_at DESC);
CREATE INDEX idx_deployments_env ON deployments(environment_name, created_at DESC);

CREATE TABLE artifacts (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    name VARCHAR(200) NOT NULL,
    version VARCHAR(100) NOT NULL,
    type artifact_type NOT NULL,
    repository VARCHAR(200) NOT NULL,
    size_bytes BIGINT,
    git_commit_sha VARCHAR(40),
    build_time TIMESTAMP NOT NULL,
    pipeline_execution_id BIGINT REFERENCES pipeline_executions(id),
    metadata JSONB,
    pinned BOOLEAN DEFAULT false,
    cleanup_status VARCHAR(20) DEFAULT 'NONE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, name, version)
);

CREATE INDEX idx_artifacts_project ON artifacts(project_id, created_at DESC);
CREATE INDEX idx_artifacts_cleanup_status ON artifacts(cleanup_status);

CREATE TABLE job_events (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    job_token VARCHAR(64) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    step_index INT,
    step_name VARCHAR(200),
    step_status VARCHAR(20),
    runner_id BIGINT,
    log_increment TEXT,
    log_offset INT,
    exit_code INT,
    error_message TEXT,
    event_timestamp TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_job_events_job_id ON job_events(job_id, event_timestamp ASC);
CREATE INDEX idx_job_events_token ON job_events(job_token);

CREATE TABLE runners (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    token VARCHAR(255) NOT NULL,
    host_name VARCHAR(100),
    ip_address VARCHAR(50),
    os VARCHAR(50),
    architecture VARCHAR(50),
    cpu_cores INT,
    total_memory BIGINT,
    version VARCHAR(50),
    tags TEXT[],
    max_concurrent_jobs INT DEFAULT 1,
    current_job_id BIGINT,
    last_heartbeat TIMESTAMP,
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    executed_jobs INT DEFAULT 0,
    cpu_usage INT,
    memory_usage INT,
    disk_usage INT,
    active BOOLEAN DEFAULT true
);

CREATE INDEX idx_runners_status ON runners(last_heartbeat DESC);
CREATE INDEX idx_runners_tags ON runners USING GIN(tags);

CREATE TABLE approvals (
    id BIGSERIAL PRIMARY KEY,
    pipeline_execution_id BIGINT NOT NULL REFERENCES pipeline_executions(id) ON DELETE CASCADE,
    environment_name VARCHAR(50) NOT NULL,
    approvers TEXT[] NOT NULL,
    approval_mode approval_mode NOT NULL DEFAULT 'ANY',
    status approval_status NOT NULL DEFAULT 'PENDING',
    approved_count INT DEFAULT 0,
    expires_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE approval_decisions (
    id BIGSERIAL PRIMARY KEY,
    approval_id BIGINT NOT NULL REFERENCES approvals(id) ON DELETE CASCADE,
    approver VARCHAR(50) NOT NULL,
    decision approval_status NOT NULL,
    comment TEXT,
    decided_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id),
    event_type VARCHAR(50) NOT NULL,
    source VARCHAR(20) NOT NULL,
    branch_name VARCHAR(100),
    commit_sha VARCHAR(40),
    commit_message TEXT,
    author VARCHAR(100),
    changes JSONB,
    raw_payload TEXT,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notification_templates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    channels notification_channel[] NOT NULL,
    recipient_type VARCHAR(20) NOT NULL,
    specify_users TEXT[],
    template_subject VARCHAR(255),
    template_content TEXT,
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notification_history (
    id BIGSERIAL PRIMARY KEY,
    channel notification_channel NOT NULL,
    event_type VARCHAR(50),
    recipient VARCHAR(255) NOT NULL,
    title VARCHAR(255),
    content TEXT,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_notification_history ON notification_history(sent_at DESC);

-- Insert default admin user (password: admin123, BCrypt encrypted)
INSERT INTO users (username, password, email, real_name, enabled)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTV.7/i', 'admin@example.com', '系统管理员', true)
ON CONFLICT (username) DO NOTHING;

-- Assign platform admin role to default admin
INSERT INTO user_roles (user_id, role, project_id)
SELECT id, 'PLATFORM_ADMIN', NULL FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;

-- Insert default project
INSERT INTO projects (name, code, description, owner, active)
VALUES ('示例项目', 'demo-project', 'CI/CD平台示例项目', 'admin', true)
ON CONFLICT (code) DO NOTHING;

-- Insert default environments
INSERT INTO environments (project_id, name, description, k8s_namespace, deployment_strategy, require_approval)
SELECT p.id, 'dev', '开发环境', 'development', 'ROLLING', false
FROM projects p WHERE p.code = 'demo-project'
ON CONFLICT DO NOTHING;

INSERT INTO environments (project_id, name, description, k8s_namespace, deployment_strategy, require_approval)
SELECT p.id, 'staging', '预发布环境', 'staging', 'ROLLING', false
FROM projects p WHERE p.code = 'demo-project'
ON CONFLICT DO NOTHING;

INSERT INTO environments (project_id, name, description, k8s_namespace, deployment_strategy, require_approval, approvers)
SELECT p.id, 'prod', '生产环境', 'production', 'CANARY', true, ARRAY['admin']
FROM projects p WHERE p.code = 'demo-project'
ON CONFLICT DO NOTHING;

-- Insert default runner
INSERT INTO runners (name, description, token, tags, max_concurrent_jobs)
VALUES ('default-runner', '默认Runner', 'cicd-runner-default-token-123456', ARRAY['docker', 'java', 'node', 'python', 'go'], 2)
ON CONFLICT (name) DO NOTHING;

-- Create function for auto-updating updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create triggers for updated_at
CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_projects_updated_at BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_pipelines_updated_at BEFORE UPDATE ON pipelines
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_environments_updated_at BEFORE UPDATE ON environments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
