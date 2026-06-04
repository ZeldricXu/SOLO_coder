CREATE TABLE IF NOT EXISTS namespaces (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL REFERENCES namespaces(name),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL,
    description TEXT,
    cron_expression VARCHAR(128),
    delay_seconds INTEGER DEFAULT 0,
    interval_seconds INTEGER DEFAULT 0,
    payload JSONB,
    callback_url VARCHAR(1024),
    timeout_seconds INTEGER DEFAULT 300,
    max_retries INTEGER DEFAULT 3,
    retry_backoff VARCHAR(32) DEFAULT 'exponential',
    dependencies TEXT[] DEFAULT '{}',
    dag_id VARCHAR(64),
    priority INTEGER DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    tags TEXT[] DEFAULT '{}'
);

CREATE INDEX IF NOT EXISTS idx_tasks_namespace ON tasks(namespace);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_next_run_at ON tasks(next_run_at);
CREATE INDEX IF NOT EXISTS idx_tasks_dag_id ON tasks(dag_id);

CREATE TABLE IF NOT EXISTS dags (
    id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL REFERENCES namespaces(name),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    nodes JSONB NOT NULL DEFAULT '{}'::JSONB,
    edges JSONB NOT NULL DEFAULT '[]'::JSONB,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_dags_namespace ON dags(namespace);

CREATE TABLE IF NOT EXISTS executions (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES tasks(id),
    namespace VARCHAR(64) NOT NULL REFERENCES namespaces(name),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    worker_id VARCHAR(64),
    node_id VARCHAR(64),
    input_payload JSONB,
    output_payload JSONB,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    duration_ms BIGINT,
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    trace_id VARCHAR(128),
    span_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    parent_execution_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_executions_task_id ON executions(task_id);
CREATE INDEX IF NOT EXISTS idx_executions_namespace ON executions(namespace);
CREATE INDEX IF NOT EXISTS idx_executions_status ON executions(status);
CREATE INDEX IF NOT EXISTS idx_executions_created_at ON executions(created_at DESC);

CREATE TABLE IF NOT EXISTS task_logs (
    id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL REFERENCES executions(id),
    task_id VARCHAR(64) NOT NULL REFERENCES tasks(id),
    namespace VARCHAR(64) NOT NULL REFERENCES namespaces(name),
    log_level VARCHAR(32) NOT NULL,
    message TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sequence BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_logs_execution_id ON task_logs(execution_id);
CREATE INDEX IF NOT EXISTS idx_task_logs_timestamp ON task_logs(timestamp DESC);

CREATE TABLE IF NOT EXISTS workers (
    id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL REFERENCES namespaces(name),
    hostname VARCHAR(255),
    grpc_addr VARCHAR(255),
    http_addr VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'healthy',
    last_heartbeat TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    unhealthy_count INTEGER DEFAULT 0,
    capabilities TEXT[] DEFAULT '{}',
    current_load INTEGER DEFAULT 0,
    max_load INTEGER DEFAULT 100
);

CREATE INDEX IF NOT EXISTS idx_workers_status ON workers(status);
CREATE INDEX IF NOT EXISTS idx_workers_last_heartbeat ON workers(last_heartbeat);

CREATE TABLE IF NOT EXISTS audit_logs (
    id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) REFERENCES namespaces(name),
    actor VARCHAR(255) NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64),
    old_value JSONB,
    new_value JSONB,
    ip_address VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_namespace ON audit_logs(namespace);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at DESC);

CREATE TABLE IF NOT EXISTS dead_letters (
    id VARCHAR(64) PRIMARY KEY,
    execution_id VARCHAR(64) NOT NULL REFERENCES executions(id),
    task_id VARCHAR(64) NOT NULL REFERENCES tasks(id),
    namespace VARCHAR(64) NOT NULL REFERENCES namespaces(name),
    error_message TEXT,
    original_status VARCHAR(32),
    payload JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replayed BOOLEAN NOT NULL DEFAULT FALSE,
    replayed_at TIMESTAMP,
    replayed_by VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_dead_letters_namespace ON dead_letters(namespace);
CREATE INDEX IF NOT EXISTS idx_dead_letters_replayed ON dead_letters(replayed);
