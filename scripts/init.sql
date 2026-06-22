-- ============================================
-- Feature Flag Platform Database Schema
-- PostgreSQL 14+
-- ============================================

-- ============================================
-- Extensions
-- ============================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- ============================================
-- Enum Types
-- ============================================
CREATE TYPE switch_type AS ENUM (
    'BOOLEAN',
    'PERCENTAGE',
    'WHITELIST'
);

CREATE TYPE switch_scope AS ENUM (
    'GLOBAL',
    'ENVIRONMENT',
    'TENANT'
);

CREATE TYPE switch_status AS ENUM (
    'DRAFT',
    'PENDING_APPROVAL',
    'ACTIVE',
    'INACTIVE',
    'SCHEDULED'
);

CREATE TYPE strategy_operator AS ENUM (
    'AND',
    'OR'
);

CREATE TYPE whitelist_field AS ENUM (
    'USER_ID',
    'DEPARTMENT',
    'TAG'
);

CREATE TYPE whitelist_operator AS ENUM (
    'IN',
    'NOT_IN',
    'CONTAINS',
    'NOT_CONTAINS'
);

CREATE TYPE approval_status AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'CANCELLED'
);

CREATE TYPE event_type AS ENUM (
    'SWITCH_CREATED',
    'SWITCH_UPDATED',
    'SWITCH_DELETED',
    'SWITCH_ENABLED',
    'SWITCH_DISABLED',
    'STRATEGY_UPDATED',
    'APPROVAL_REQUESTED',
    'APPROVAL_APPROVED',
    'APPROVAL_REJECTED',
    'AUTO_ROLLBACK'
);

-- ============================================
-- Table: services
-- ============================================
CREATE TABLE services (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    owner VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_services_name ON services(name);
CREATE INDEX idx_services_owner ON services(owner);

-- ============================================
-- Table: users
-- ============================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(100),
    role VARCHAR(50) NOT NULL DEFAULT 'developer',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_department ON users(department);
CREATE INDEX idx_users_role ON users(role);

-- ============================================
-- Table: switches
-- ============================================
CREATE TABLE switches (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key VARCHAR(200) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    type switch_type NOT NULL DEFAULT 'BOOLEAN',
    scope switch_scope NOT NULL DEFAULT 'GLOBAL',
    service_id UUID NOT NULL REFERENCES services(id),
    owner VARCHAR(100) NOT NULL,
    status switch_status NOT NULL DEFAULT 'DRAFT',
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    boolean_value BOOLEAN DEFAULT FALSE,
    percentage_value INTEGER DEFAULT 0 CHECK (percentage_value >= 0 AND percentage_value <= 100),
    environment VARCHAR(50),
    tenant_id VARCHAR(100),
    require_approval BOOLEAN NOT NULL DEFAULT FALSE,
    auto_rollback_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auto_rollback_threshold DECIMAL(5,2) DEFAULT 5.00,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT check_scope_fields CHECK (
        (scope = 'GLOBAL' AND environment IS NULL AND tenant_id IS NULL) OR
        (scope = 'ENVIRONMENT' AND environment IS NOT NULL AND tenant_id IS NULL) OR
        (scope = 'TENANT' AND environment IS NOT NULL AND tenant_id IS NOT NULL)
    )
);

CREATE INDEX idx_switches_key ON switches(key);
CREATE INDEX idx_switches_service_id ON switches(service_id);
CREATE INDEX idx_switches_type ON switches(type);
CREATE INDEX idx_switches_scope ON switches(scope);
CREATE INDEX idx_switches_status ON switches(status);
CREATE INDEX idx_switches_owner ON switches(owner);
CREATE INDEX idx_switches_environment ON switches(environment);
CREATE INDEX idx_switches_tenant_id ON switches(tenant_id);
CREATE INDEX idx_switches_enabled ON switches(enabled);
CREATE INDEX idx_switches_name_trgm ON switches USING gin (name gin_trgm_ops);

-- ============================================
-- Table: strategies
-- ============================================
CREATE TABLE strategies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    switch_id UUID NOT NULL REFERENCES switches(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    operator strategy_operator NOT NULL DEFAULT 'AND',
    priority INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_strategies_switch_id ON strategies(switch_id);
CREATE INDEX idx_strategies_priority ON strategies(priority);

-- ============================================
-- Table: whitelist_conditions
-- ============================================
CREATE TABLE whitelist_conditions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    strategy_id UUID NOT NULL REFERENCES strategies(id) ON DELETE CASCADE,
    field whitelist_field NOT NULL,
    operator whitelist_operator NOT NULL,
    values TEXT[] NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_whitelist_conditions_strategy_id ON whitelist_conditions(strategy_id);
CREATE INDEX idx_whitelist_conditions_field ON whitelist_conditions(field);

-- ============================================
-- Table: switch_history
-- ============================================
CREATE TABLE switch_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    switch_id UUID NOT NULL REFERENCES switches(id) ON DELETE CASCADE,
    event_type event_type NOT NULL,
    old_value JSONB,
    new_value JSONB,
    operator_user VARCHAR(100) NOT NULL,
    remark VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_switch_history_switch_id ON switch_history(switch_id);
CREATE INDEX idx_switch_history_event_type ON switch_history(event_type);
CREATE INDEX idx_switch_history_created_at ON switch_history(created_at DESC);
CREATE INDEX idx_switch_history_operator ON switch_history(operator_user);

-- ============================================
-- Table: approvals
-- ============================================
CREATE TABLE approvals (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    switch_id UUID NOT NULL REFERENCES switches(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    requester VARCHAR(100) NOT NULL,
    approver VARCHAR(100) NOT NULL,
    status approval_status NOT NULL DEFAULT 'PENDING',
    change_content JSONB NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    rejected_at TIMESTAMP WITH TIME ZONE,
    reject_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_approvals_switch_id ON approvals(switch_id);
CREATE INDEX idx_approvals_requester ON approvals(requester);
CREATE INDEX idx_approvals_approver ON approvals(approver);
CREATE INDEX idx_approvals_status ON approvals(status);
CREATE INDEX idx_approvals_created_at ON approvals(created_at DESC);

-- ============================================
-- Table: scheduled_tasks
-- ============================================
CREATE TABLE scheduled_tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    switch_id UUID NOT NULL REFERENCES switches(id) ON DELETE CASCADE,
    task_type VARCHAR(50) NOT NULL,
    target_enabled BOOLEAN NOT NULL,
    execute_at TIMESTAMP WITH TIME ZONE NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_scheduled_tasks_switch_id ON scheduled_tasks(switch_id);
CREATE INDEX idx_scheduled_tasks_execute_at ON scheduled_tasks(execute_at);
CREATE INDEX idx_scheduled_tasks_status ON scheduled_tasks(status);

-- ============================================
-- Table: switch_stats
-- ============================================
CREATE TABLE switch_stats (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    switch_id UUID NOT NULL REFERENCES switches(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    total_evaluations BIGINT NOT NULL DEFAULT 0,
    true_count BIGINT NOT NULL DEFAULT 0,
    false_count BIGINT NOT NULL DEFAULT 0,
    error_count BIGINT NOT NULL DEFAULT 0,
    avg_latency_ms DECIMAL(10,2) DEFAULT 0,
    p99_latency_ms DECIMAL(10,2) DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(switch_id, date)
);

CREATE INDEX idx_switch_stats_switch_id ON switch_stats(switch_id);
CREATE INDEX idx_switch_stats_date ON switch_stats(date DESC);

-- ============================================
-- Table: switch_integration
-- ============================================
CREATE TABLE switch_integrations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    switch_id UUID NOT NULL REFERENCES switches(id) ON DELETE CASCADE,
    service_name VARCHAR(100) NOT NULL,
    sdk_version VARCHAR(50),
    last_poll_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(switch_id, service_name)
);

CREATE INDEX idx_switch_integrations_switch_id ON switch_integrations(switch_id);
CREATE INDEX idx_switch_integrations_service ON switch_integrations(service_name);

-- ============================================
-- Table: audit_logs
-- ============================================
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID,
    details JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_user ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
CREATE INDEX idx_audit_logs_resource ON audit_logs(resource_type, resource_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);

-- ============================================
-- Triggers for updated_at
-- ============================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_services_updated_at
    BEFORE UPDATE ON services
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_switches_updated_at
    BEFORE UPDATE ON switches
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_strategies_updated_at
    BEFORE UPDATE ON strategies
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_approvals_updated_at
    BEFORE UPDATE ON approvals
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_switch_stats_updated_at
    BEFORE UPDATE ON switch_stats
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_switch_integrations_updated_at
    BEFORE UPDATE ON switch_integrations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- Trigger for switch history
-- ============================================
CREATE OR REPLACE FUNCTION log_switch_change()
RETURNS TRIGGER AS $$
DECLARE
    event_type_var event_type;
    old_json JSONB;
    new_json JSONB;
BEGIN
    IF TG_OP = 'INSERT' THEN
        event_type_var = 'SWITCH_CREATED';
        old_json = NULL;
        new_json = to_jsonb(NEW);
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.enabled = FALSE AND NEW.enabled = TRUE THEN
            event_type_var = 'SWITCH_ENABLED';
        ELSIF OLD.enabled = TRUE AND NEW.enabled = FALSE THEN
            event_type_var = 'SWITCH_DISABLED';
        ELSE
            event_type_var = 'SWITCH_UPDATED';
        END IF;
        old_json = to_jsonb(OLD);
        new_json = to_jsonb(NEW);
    ELSIF TG_OP = 'DELETE' THEN
        event_type_var = 'SWITCH_DELETED';
        old_json = to_jsonb(OLD);
        new_json = NULL;
    END IF;

    INSERT INTO switch_history (switch_id, event_type, old_value, new_value, operator_user, remark)
    VALUES (
        COALESCE(NEW.id, OLD.id),
        event_type_var,
        old_json,
        new_json,
        COALESCE(current_setting('app.current_user', TRUE), 'system'),
        'System generated history record'
    );

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_switch_history
    AFTER INSERT OR UPDATE OR DELETE ON switches
    FOR EACH ROW EXECUTE FUNCTION log_switch_change();

-- ============================================
-- Initial Data
-- ============================================
INSERT INTO services (name, description, owner) VALUES
('user-service', '用户服务', 'zhangsan'),
('order-service', '订单服务', 'lisi'),
('payment-service', '支付服务', 'wangwu'),
('product-service', '商品服务', 'zhaoliu');

INSERT INTO users (username, email, name, department, role) VALUES
('admin', 'admin@example.com', '系统管理员', '技术部', 'admin'),
('zhangsan', 'zhangsan@example.com', '张三', '后端组', 'developer'),
('lisi', 'lisi@example.com', '李四', '后端组', 'developer'),
('wangwu', 'wangwu@example.com', '王五', '运维组', 'ops'),
('zhaoliu', 'zhaoliu@example.com', '赵六', '产品组', 'product');
