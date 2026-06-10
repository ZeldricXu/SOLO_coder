import { ConfigData, ConfigValue, EnvironmentConfig } from '../../types'
import { SchemaFieldConfig, SchemaConfig } from '../../schemas/SchemaValidator'

export function createDevConfig(overrides: Partial<ConfigData> = {}): ConfigData {
  return {
    app: {
      name: 'my-service',
      port: 3000,
      debug: true,
    },
    db: {
      host: 'localhost',
      port: 5432,
      name: 'myapp_dev',
      user: 'dev_user',
      password: 'dev_pass',
      pool: {
        min: 2,
        max: 10,
      },
      replica: {
        host: 'localhost',
        port: 5433,
      },
    },
    cache: {
      host: 'localhost',
      port: 6379,
      ttl: 300,
    },
    rateLimit: {
      windowMs: 60000,
      max: 1000,
    },
    logLevel: 'debug',
    ...overrides,
  }
}

export function createStagingConfig(overrides: Partial<ConfigData> = {}): ConfigData {
  return {
    app: {
      name: 'my-service',
      port: 3000,
      debug: false,
    },
    db: {
      host: 'staging-db.internal',
      port: 5432,
      name: 'myapp_staging',
      user: 'staging_user',
      password: 'staging_pass',
      pool: {
        min: 5,
        max: 20,
      },
      replica: {
        host: 'staging-replica.internal',
        port: 5432,
      },
    },
    cache: {
      host: 'staging-cache.internal',
      port: 6379,
      ttl: 600,
    },
    rateLimit: {
      windowMs: 60000,
      max: 500,
    },
    logLevel: 'info',
    ...overrides,
  }
}

export function createProdConfig(overrides: Partial<ConfigData> = {}): ConfigData {
  return {
    app: {
      name: 'my-service',
      port: 8080,
      debug: false,
    },
    db: {
      host: 'prod-db.internal',
      port: 5432,
      name: 'myapp_prod',
      user: 'prod_user',
      password: 'prod_secret_pass',
      pool: {
        min: 10,
        max: 50,
      },
      replica: {
        host: 'prod-replica.internal',
        port: 5432,
      },
    },
    cache: {
      host: 'prod-cache.internal',
      port: 6379,
      ttl: 1200,
    },
    rateLimit: {
      windowMs: 60000,
      max: 100,
    },
    logLevel: 'warn',
    ...overrides,
  }
}

export function createEmptyConfig(): ConfigData {
  return {}
}

export function createLargeConfig(): ConfigData {
  const certPem = '-----BEGIN CERTIFICATE-----\n' + 'A'.repeat(10240) + '\n-----END CERTIFICATE-----'
  return {
    tls: {
      cert: certPem,
      key: certPem,
    },
    app: {
      name: 'large-config-service',
    },
  }
}

export function createEnvFileContent(pairs: Record<string, string>): string {
  return Object.entries(pairs)
    .map(([k, v]) => `${k}=${v}`)
    .join('\n')
}

export function createDevEnvContent(): string {
  return createEnvFileContent({
    APP_NAME: 'my-service',
    APP_PORT: '3000',
    APP_DEBUG: 'true',
    DB_HOST: 'localhost',
    DB_PORT: '5432',
    DB_NAME: 'myapp_dev',
    DB_PASSWORD: 'dev_pass',
    LOGLEVEL: 'debug',
  })
}

export function createSchemaConfig(overrides: Partial<SchemaConfig> = {}): SchemaConfig {
  return {
    $schema: 'config-flow-schema/v1',
    version: '1.0.0',
    fields: [
      {
        key: 'app',
        type: 'object',
        required: true,
        properties: [
          { key: 'name', type: 'string', required: true, min: 1, max: 100 },
          { key: 'port', type: 'integer', required: true, min: 1, max: 65535 },
          { key: 'debug', type: 'boolean', required: false, default: false },
        ],
      },
      {
        key: 'db',
        type: 'object',
        required: true,
        properties: [
          { key: 'host', type: 'string', required: true, pattern: '^[a-zA-Z0-9._-]+$' },
          { key: 'port', type: 'integer', required: true, min: 1, max: 65535 },
          { key: 'name', type: 'string', required: true },
          { key: 'password', type: 'string', required: true, min: 8 },
          { key: 'replica', type: 'object', required: false, properties: [
            { key: 'host', type: 'string', required: false },
            { key: 'port', type: 'integer', required: false, min: 1, max: 65535 },
          ]},
        ],
      },
      {
        key: 'logLevel',
        type: 'string',
        required: true,
        enum: ['debug', 'info', 'warn', 'error'],
      },
      {
        key: 'rateLimit',
        type: 'object',
        required: false,
        properties: [
          { key: 'windowMs', type: 'number', required: false, min: 1000 },
          { key: 'max', type: 'integer', required: false, min: 1, max: 10000 },
        ],
      },
    ],
    ...overrides,
  }
}

export function createNginxTemplate(): string {
  return `upstream {{app.name}} {
    server 127.0.0.1:{{app.port}};
}

server {
    listen 80;
    server_name {{app.name}}.example.com;

    location / {
        proxy_pass http://{{app.name}};
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    {{#if app.debug}}
    location /debug {
        proxy_pass http://{{app.name}}/debug;
    }
    {{/if}}

    {{#if rateLimit}}
    limit_req zone=api burst={{rateLimit.max}} nodelay;
    {{/if}}

    access_log /var/log/nginx/{{app.name}}_access.log;
    error_log /var/log/nginx/{{app.name}}_error.log {{logLevel}};
}`
}

export function createDockerComposeTemplate(): string {
  return `version: '3.8'
services:
  {{app.name}}:
    image: my-registry/{{app.name}}:latest
    ports:
      - "{{app.port}}:{{app.port}}"
    environment:
      - DB_HOST={{db.host}}
      - DB_PORT={{db.port}}
      - DB_NAME={{db.name}}
      - LOG_LEVEL={{logLevel}}
    {{#if db.replica}}
      - DB_REPLICA_HOST={{db.replica.host}}
    {{/if}}
    restart: unless-stopped`
}

export function createAppPropertiesTemplate(): string {
  return `# Generated for {{_meta.environment}}
app.name={{app.name}}
app.port={{app.port}}
app.debug={{app.debug}}

db.host={{db.host}}
db.port={{db.port}}
db.name={{db.name}}
db.password={{db.password}}
{{#if db.replica}}
db.replica.host={{db.replica.host}}
db.replica.port={{db.replica.port}}
{{/if}}

log.level={{logLevel}}
{{#if rateLimit}}
rate.limit.window={{rateLimit.windowMs}}
rate.limit.max={{rateLimit.max}}
{{/if}}`
}
