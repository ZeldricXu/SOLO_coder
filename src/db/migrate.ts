import { db } from './index';
import { logger } from '../utils/logger';

const migrations = [
  `
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
    
    CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid AS $$
    BEGIN
      RETURN current_setting('app.current_tenant', true)::uuid;
    EXCEPTION WHEN OTHERS THEN
      RETURN NULL;
    END;
    $$ LANGUAGE plpgsql STABLE;

    CREATE POLICY tenant_isolation_policy ON ALL TABLES
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
  `,

  `
    CREATE TABLE IF NOT EXISTS tenants (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      name VARCHAR(255) NOT NULL,
      config JSONB DEFAULT '{}',
      rate_limits JSONB DEFAULT '{}',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS templates (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      notification_type VARCHAR(50) NOT NULL,
      locale VARCHAR(10) NOT NULL DEFAULT 'en',
      name VARCHAR(255) NOT NULL,
      subject_template TEXT,
      body_template TEXT NOT NULL,
      html_template TEXT,
      variables JSONB DEFAULT '[]',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW(),
      UNIQUE(tenant_id, notification_type, locale)
    );

    CREATE TABLE IF NOT EXISTS delivery_logs (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      delivery_id UUID NOT NULL,
      tenant_id UUID NOT NULL,
      notification_type VARCHAR(50) NOT NULL,
      channel VARCHAR(20) NOT NULL,
      provider VARCHAR(50),
      recipient VARCHAR(255) NOT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'pending',
      priority VARCHAR(20) NOT NULL DEFAULT 'medium',
      message_id VARCHAR(255),
      error_message TEXT,
      metadata JSONB DEFAULT '{}',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE INDEX idx_delivery_logs_delivery_id ON delivery_logs(delivery_id);
    CREATE INDEX idx_delivery_logs_recipient ON delivery_logs(recipient);
    CREATE INDEX idx_delivery_logs_created_at ON delivery_logs(created_at DESC);
    CREATE INDEX idx_delivery_logs_status ON delivery_logs(status);
  `,

  `
    CREATE TABLE IF NOT EXISTS user_preferences (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      user_id VARCHAR(255) NOT NULL,
      channel_preferences JSONB DEFAULT '[]',
      do_not_disturb JSONB DEFAULT '{}',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW(),
      UNIQUE(tenant_id, user_id)
    );

    CREATE TABLE IF NOT EXISTS webhook_endpoints (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      url VARCHAR(2048) NOT NULL,
      signing_secret VARCHAR(255) NOT NULL,
      event_types JSONB DEFAULT '[]',
      retry_config JSONB DEFAULT '{}',
      enabled BOOLEAN DEFAULT true,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS webhook_logs (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      endpoint_id UUID NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,
      event_type VARCHAR(100) NOT NULL,
      request_headers JSONB DEFAULT '{}',
      request_body JSONB DEFAULT '{}',
      response_status INTEGER,
      response_body TEXT,
      attempts INTEGER DEFAULT 0,
      status VARCHAR(20) NOT NULL DEFAULT 'pending',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE INDEX idx_webhook_logs_endpoint ON webhook_logs(endpoint_id);
    CREATE INDEX idx_webhook_logs_created_at ON webhook_logs(created_at DESC);
  `,

  `
    CREATE TABLE IF NOT EXISTS routing_rules (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      name VARCHAR(255) NOT NULL,
      conditions JSONB DEFAULT '[]',
      actions JSONB DEFAULT '[]',
      priority INTEGER NOT NULL DEFAULT 0,
      enabled BOOLEAN DEFAULT true,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS channel_health (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      channel VARCHAR(20) NOT NULL,
      provider VARCHAR(50),
      available BOOLEAN DEFAULT true,
      latency_ms INTEGER,
      last_checked TIMESTAMPTZ DEFAULT NOW(),
      quota_used INTEGER DEFAULT 0,
      quota_total INTEGER,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW(),
      UNIQUE(tenant_id, channel, provider)
    );

    CREATE TABLE IF NOT EXISTS audit_logs (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      actor VARCHAR(255) NOT NULL,
      action VARCHAR(50) NOT NULL,
      resource_type VARCHAR(50) NOT NULL,
      resource_id UUID,
      changes JSONB DEFAULT '{}',
      created_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE INDEX idx_audit_logs_tenant ON audit_logs(tenant_id);
    CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at DESC);
  `,

  `
    ALTER TABLE templates ENABLE ROW LEVEL SECURITY;
    ALTER TABLE delivery_logs ENABLE ROW LEVEL SECURITY;
    ALTER TABLE user_preferences ENABLE ROW LEVEL SECURITY;
    ALTER TABLE webhook_endpoints ENABLE ROW LEVEL SECURITY;
    ALTER TABLE webhook_logs ENABLE ROW LEVEL SECURITY;
    ALTER TABLE routing_rules ENABLE ROW LEVEL SECURITY;
    ALTER TABLE channel_health ENABLE ROW LEVEL SECURITY;
    ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;

    DROP POLICY IF EXISTS templates_tenant_isolation ON templates;
    CREATE POLICY templates_tenant_isolation ON templates
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS delivery_logs_tenant_isolation ON delivery_logs;
    CREATE POLICY delivery_logs_tenant_isolation ON delivery_logs
      USING (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS user_preferences_tenant_isolation ON user_preferences;
    CREATE POLICY user_preferences_tenant_isolation ON user_preferences
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS webhook_endpoints_tenant_isolation ON webhook_endpoints;
    CREATE POLICY webhook_endpoints_tenant_isolation ON webhook_endpoints
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS webhook_logs_tenant_isolation ON webhook_logs;
    CREATE POLICY webhook_logs_tenant_isolation ON webhook_logs
      USING (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS routing_rules_tenant_isolation ON routing_rules;
    CREATE POLICY routing_rules_tenant_isolation ON routing_rules
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS channel_health_tenant_isolation ON channel_health;
    CREATE POLICY channel_health_tenant_isolation ON channel_health
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS audit_logs_tenant_isolation ON audit_logs;
    CREATE POLICY audit_logs_tenant_isolation ON audit_logs
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());
  `,

  `
    ALTER TABLE templates 
    ADD COLUMN IF NOT EXISTS is_system_default BOOLEAN DEFAULT false;

    DROP INDEX IF EXISTS templates_unique_constraint;
    ALTER TABLE templates 
    DROP CONSTRAINT IF EXISTS templates_tenant_id_notification_type_locale_key;
    
    ALTER TABLE templates 
    ADD CONSTRAINT templates_unique_constraint 
    UNIQUE(tenant_id, notification_type, locale, is_system_default);

    CREATE INDEX IF NOT EXISTS idx_templates_system_default ON templates(is_system_default);
  `,

  `
    CREATE TABLE IF NOT EXISTS orchestration_sequences (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      name VARCHAR(255) NOT NULL,
      description TEXT,
      steps JSONB NOT NULL DEFAULT '[]',
      trigger_type VARCHAR(20) NOT NULL DEFAULT 'manual',
      trigger_event VARCHAR(100),
      enabled BOOLEAN DEFAULT true,
      created_by VARCHAR(255) NOT NULL,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      updated_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS orchestration_instances (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      sequence_id UUID NOT NULL REFERENCES orchestration_sequences(id) ON DELETE CASCADE,
      tenant_id UUID NOT NULL,
      recipient JSONB NOT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'pending',
      current_step INTEGER NOT NULL DEFAULT 0,
      template_variables JSONB,
      started_at TIMESTAMPTZ DEFAULT NOW(),
      completed_at TIMESTAMPTZ,
      metadata JSONB DEFAULT '{}'
    );

    CREATE TABLE IF NOT EXISTS orchestration_step_executions (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      instance_id UUID NOT NULL REFERENCES orchestration_instances(id) ON DELETE CASCADE,
      sequence_id UUID NOT NULL,
      step_id VARCHAR(100) NOT NULL,
      delivery_id UUID,
      status VARCHAR(20) NOT NULL DEFAULT 'pending',
      scheduled_at TIMESTAMPTZ,
      started_at TIMESTAMPTZ,
      completed_at TIMESTAMPTZ,
      result JSONB,
      error_message TEXT,
      metadata JSONB DEFAULT '{}',
      created_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS idx_orchestration_sequences_tenant ON orchestration_sequences(tenant_id);
    CREATE INDEX IF NOT EXISTS idx_orchestration_instances_tenant ON orchestration_instances(tenant_id);
    CREATE INDEX IF NOT EXISTS idx_orchestration_instances_sequence ON orchestration_instances(sequence_id);
    CREATE INDEX IF NOT EXISTS idx_orchestration_instances_status ON orchestration_instances(status);
    CREATE INDEX IF NOT EXISTS idx_orchestration_executions_instance ON orchestration_step_executions(instance_id);
    CREATE INDEX IF NOT EXISTS idx_orchestration_executions_delivery ON orchestration_step_executions(delivery_id);
    CREATE INDEX IF NOT EXISTS idx_orchestration_executions_status ON orchestration_step_executions(status);
  `,

  `
    CREATE INDEX IF NOT EXISTS idx_delivery_logs_tenant ON delivery_logs(tenant_id);
    CREATE INDEX IF NOT EXISTS idx_delivery_logs_channel ON delivery_logs(channel);
    CREATE INDEX IF NOT EXISTS idx_delivery_logs_notification_type ON delivery_logs(notification_type);
    CREATE INDEX IF NOT EXISTS idx_delivery_logs_provider ON delivery_logs(provider);
    CREATE INDEX IF NOT EXISTS idx_delivery_logs_updated_at ON delivery_logs(updated_at);

    CREATE TABLE IF NOT EXISTS delivery_stats_mv (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      tenant_id UUID NOT NULL,
      date DATE NOT NULL,
      channel VARCHAR(20),
      notification_type VARCHAR(50),
      provider VARCHAR(50),
      status VARCHAR(20),
      count INTEGER NOT NULL DEFAULT 0,
      avg_latency_ms NUMERIC,
      p95_latency_ms NUMERIC,
      p99_latency_ms NUMERIC,
      UNIQUE(tenant_id, date, channel, notification_type, provider, status)
    );

    CREATE INDEX IF NOT EXISTS idx_delivery_stats_mv_date ON delivery_stats_mv(date DESC);
    CREATE INDEX IF NOT EXISTS idx_delivery_stats_mv_tenant ON delivery_stats_mv(tenant_id);
  `,

  `
    ALTER TABLE orchestration_sequences ENABLE ROW LEVEL SECURITY;
    ALTER TABLE orchestration_instances ENABLE ROW LEVEL SECURITY;
    ALTER TABLE orchestration_step_executions ENABLE ROW LEVEL SECURITY;

    DROP POLICY IF EXISTS orchestration_sequences_tenant_isolation ON orchestration_sequences;
    CREATE POLICY orchestration_sequences_tenant_isolation ON orchestration_sequences
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS orchestration_instances_tenant_isolation ON orchestration_instances;
    CREATE POLICY orchestration_instances_tenant_isolation ON orchestration_instances
      USING (tenant_id = current_tenant_id())
      WITH CHECK (tenant_id = current_tenant_id());

    DROP POLICY IF EXISTS orchestration_step_executions_tenant_isolation ON orchestration_step_executions;
    CREATE POLICY orchestration_step_executions_tenant_isolation ON orchestration_step_executions
      USING (tenant_id = current_tenant_id());
  `,
];

export async function runMigrations(): Promise<void> {
  logger.info('Starting database migrations...');
  
  for (let i = 0; i < migrations.length; i++) {
    logger.info(`Running migration ${i + 1}/${migrations.length}`);
    try {
      await db.query(migrations[i]);
      logger.info(`Migration ${i + 1} completed successfully`);
    } catch (err) {
      logger.error(`Migration ${i + 1} failed`, err);
      throw err;
    }
  }
  
  logger.info('All migrations completed successfully');
}

if (require.main === module) {
  runMigrations()
    .then(() => process.exit(0))
    .catch((err) => {
      logger.error('Migration failed', err);
      process.exit(1);
    });
}
