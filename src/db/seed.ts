import { db } from './index';
import { logger } from '../utils/logger';

async function seedTenants() {
  const tenants = [
    { name: 'Default Tenant', config: {}, rate_limits: {} },
    { name: 'E-commerce Platform', config: {}, rate_limits: {} },
    { name: 'SaaS Business', config: {}, rate_limits: {} },
  ];

  for (const tenant of tenants) {
    const existing = await db.query(
      'SELECT id FROM tenants WHERE name = $1',
      [tenant.name]
    );
    
    if (existing.rowCount === 0) {
      await db.query(
        'INSERT INTO tenants (name, config, rate_limits) VALUES ($1, $2, $3)',
        [tenant.name, tenant.config, tenant.rate_limits]
      );
      logger.info(`Created tenant: ${tenant.name}`);
    }
  }
}

async function seedTemplates() {
  const result = await db.query('SELECT id FROM tenants LIMIT 1');
  if (result.rowCount === 0) return;
  
  const tenantId = result.rows[0].id;
  await db.setTenantContext(tenantId);

  const templates = [
    {
      notification_type: 'password_reset',
      locale: 'en',
      name: 'Password Reset Email',
      subject_template: 'Reset Your Password - {{app_name}}',
      body_template: 'Hi {{name}},\n\nClick here to reset your password: {{reset_link}}\n\nThis link expires in 1 hour.',
      html_template: '<p>Hi {{name}},</p><p>Click <a href="{{reset_link}}">here</a> to reset your password.</p>',
      variables: ['name', 'reset_link', 'app_name'],
    },
    {
      notification_type: 'account_verification',
      locale: 'en',
      name: 'Account Verification',
      subject_template: 'Verify Your Email - {{app_name}}',
      body_template: 'Hi {{name}},\n\nYour verification code is: {{code}}\n\nEnter this code to verify your account.',
      html_template: '<p>Hi {{name}},</p><p>Your verification code is: <strong>{{code}}</strong></p>',
      variables: ['name', 'code', 'app_name'],
    },
    {
      notification_type: 'transactional',
      locale: 'en',
      name: 'Order Confirmation',
      subject_template: 'Order Confirmation #{{order_id}}',
      body_template: 'Hi {{name}},\n\nYour order #{{order_id}} has been confirmed.\n\nTotal: ${{amount}}\n\n{{#if is_high_value}}<strong>High value order!</strong>{{/if}}',
      html_template: '<p>Hi {{name}},</p><p>Your order #{{order_id}} has been confirmed.</p><p>Total: ${{amount}}</p>{{#if is_high_value}}<p style="color:red;"><strong>High value order!</strong></p>{{/if}}',
      variables: ['name', 'order_id', 'amount', 'is_high_value'],
    },
  ];

  for (const tpl of templates) {
    const existing = await db.query(
      `SELECT id FROM templates WHERE tenant_id = $1 AND notification_type = $2 AND locale = $3`,
      [tenantId, tpl.notification_type, tpl.locale]
    );
    
    if (existing.rowCount === 0) {
      await db.query(
        `INSERT INTO templates (tenant_id, notification_type, locale, name, subject_template, body_template, html_template, variables)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
        [tenantId, tpl.notification_type, tpl.locale, tpl.name, tpl.subject_template, tpl.body_template, tpl.html_template, tpl.variables]
      );
      logger.info(`Created template: ${tpl.name}`);
    }
  }

  await db.clearTenantContext();
}

async function runSeed() {
  logger.info('Starting database seeding...');
  
  await seedTenants();
  await seedTemplates();
  
  logger.info('Seeding completed successfully');
}

if (require.main === module) {
  runSeed()
    .then(() => process.exit(0))
    .catch((err) => {
      logger.error('Seeding failed', err);
      process.exit(1);
    });
}
