import * as fs from 'fs'
import * as path from 'path'
import * as yaml from 'js-yaml'
import { AppConfig, EnvironmentConfig, NotificationConfig } from '../types'

const DEFAULT_CONFIG_NAME = 'config-flow.yaml'

export interface CliConfig {
  projectRoot: string
  storagePath: string
  gitRepoPath: string
  schemaPath: string
  environments: EnvironmentConfig[]
  notifications?: NotificationConfig[]
  defaultOperator?: string
}

export function findConfigFile(customPath?: string): string | null {
  if (customPath) {
    const abs = path.resolve(customPath)
    return fs.existsSync(abs) ? abs : null
  }

  const candidates = [
    DEFAULT_CONFIG_NAME,
    '.config-flow.yaml',
    'config-flow.yml',
    '.config-flow.yml',
  ]

  let currentDir = process.cwd()
  let found = false
  while (!found) {
    for (const candidate of candidates) {
      const full = path.join(currentDir, candidate)
      if (fs.existsSync(full)) return full
    }
    const parent = path.dirname(currentDir)
    if (parent === currentDir) {
      found = true
    } else {
      currentDir = parent
    }
  }

  return null
}

export function loadConfig(customPath?: string): CliConfig {
  const configPath = findConfigFile(customPath)
  if (!configPath) {
    return getDefaultConfig()
  }

  const projectRoot = path.dirname(configPath)
  const content = fs.readFileSync(configPath, 'utf-8')
  const rawConfig = (yaml.load(content) || {}) as Record<string, unknown>

  return parseConfig(rawConfig, projectRoot)
}

function getDefaultConfig(): CliConfig {
  const projectRoot = process.cwd()
  return {
    projectRoot,
    storagePath: path.join(projectRoot, '.config-flow', 'history.db'),
    gitRepoPath: path.join(projectRoot, '.config-flow', 'git-repo'),
    schemaPath: path.join(projectRoot, 'config-schema.json'),
    environments: [],
    defaultOperator: process.env.USER || 'system',
  }
}

function parseConfig(raw: Record<string, unknown>, projectRoot: string): CliConfig {
  const config: CliConfig = {
    projectRoot,
    storagePath: path.resolve(projectRoot, String(raw.storagePath || '.config-flow/history.db')),
    gitRepoPath: path.resolve(projectRoot, String(raw.gitRepoPath || '.config-flow/git-repo')),
    schemaPath: path.resolve(projectRoot, String(raw.schemaPath || 'config-schema.json')),
    environments: parseEnvironments(raw.environments, projectRoot),
    notifications: parseNotifications(raw.notifications),
    defaultOperator: raw.defaultOperator as string | undefined,
  }

  return config
}

function parseEnvironments(raw: unknown, projectRoot: string): EnvironmentConfig[] {
  if (!Array.isArray(raw)) return []

  return (raw as Record<string, unknown>[]).map((envRaw) => {
    const env: EnvironmentConfig = {
      name: String(envRaw.name),
      sources: [],
      labels: envRaw.labels as Record<string, string> | undefined,
    }

    if (Array.isArray(envRaw.sources)) {
      env.sources = (envRaw.sources as Record<string, unknown>[]).map((sRaw) => {
        const source = {
          type: String(sRaw.type) as EnvironmentConfig['sources'][0]['type'],
          priority: Number(sRaw.priority || 0),
          options: sRaw.options && typeof sRaw.options === 'object' ? { ...sRaw.options as object } : {} as Record<string, unknown>,
        }

        if (source.type === 'env' && source.options.filePath) {
          source.options.filePath = path.resolve(projectRoot, String(source.options.filePath))
        }

        return source
      })
    }

    return env
  })
}

function parseNotifications(raw: unknown): NotificationConfig[] | undefined {
  if (!Array.isArray(raw)) return undefined

  return (raw as Record<string, unknown>[]).map((nRaw) => ({
    type: String(nRaw.type) as NotificationConfig['type'],
    config: (nRaw.config || {}) as Record<string, unknown>,
  }))
}

export function saveConfig(config: CliConfig, outputPath?: string): string {
  const targetPath = outputPath || path.join(config.projectRoot, DEFAULT_CONFIG_NAME)

  const serializable = {
    storagePath: path.relative(config.projectRoot, config.storagePath),
    gitRepoPath: path.relative(config.projectRoot, config.gitRepoPath),
    schemaPath: path.relative(config.projectRoot, config.schemaPath),
    environments: config.environments.map((env) => ({
      name: env.name,
      labels: env.labels,
      sources: env.sources.map((s) => {
        const opt = { ...s.options }
        if (s.type === 'env' && opt.filePath) {
          opt.filePath = path.relative(config.projectRoot, String(opt.filePath))
        }
        return {
          type: s.type,
          priority: s.priority,
          options: opt,
        }
      }),
    })),
    notifications: config.notifications,
    defaultOperator: config.defaultOperator,
  }

  const yamlContent = yaml.dump(serializable, { lineWidth: -1, quotingType: '"', forceQuotes: true })
  fs.writeFileSync(targetPath, yamlContent)
  return targetPath
}

export function generateSampleConfig(outputDir: string): string {
  const sample: Record<string, unknown> = {
    storagePath: '.config-flow/history.db',
    gitRepoPath: '.config-flow/git-repo',
    schemaPath: 'config-schema.json',
    defaultOperator: process.env.USER || 'system',
    environments: [
      {
        name: 'development',
        labels: { tier: 'dev' },
        sources: [
          { type: 'default', priority: 10, options: { defaults: { app: { port: 3000, debug: true } } } },
          { type: 'env', priority: 100, options: { filePath: '.env.dev', useProcessEnv: true, prefix: 'APP_' } },
        ],
      },
      {
        name: 'staging',
        labels: { tier: 'staging' },
        sources: [
          { type: 'default', priority: 10, options: { defaults: { app: { port: 3000 } } } },
          { type: 'configmap', priority: 50, options: { namespace: 'staging', name: 'app-config', dataKey: 'app.json' } },
          { type: 'vault', priority: 80, options: { path: 'secret/data/staging/app' } },
        ],
      },
      {
        name: 'production',
        labels: { tier: 'prod' },
        sources: [
          { type: 'default', priority: 10, options: { defaults: { app: { port: 3000 } } } },
          { type: 'ssm', priority: 50, options: { region: 'us-east-1', pathPrefix: '/prod/app/' } },
          { type: 'vault', priority: 80, options: { path: 'secret/data/prod/app' } },
        ],
      },
    ],
    notifications: [
      {
        type: 'slack',
        config: {
          webhookUrl: 'https://hooks.slack.com/services/XXX/YYY/ZZZ',
          username: 'ConfigFlow Bot',
          channel: '#config-alerts',
        },
      },
    ],
  }

  const targetPath = path.join(outputDir, DEFAULT_CONFIG_NAME)
  fs.writeFileSync(targetPath, yaml.dump(sample, { lineWidth: -1, quotingType: '"', forceQuotes: true }))
  return targetPath
}

export function generateSampleSchema(outputDir: string): string {
  const schema = {
    $schema: 'https://github.com/config-flow/schema/v1',
    version: '1.0.0',
    fields: [
      {
        key: 'app',
        type: 'object',
        required: true,
        description: 'Application configuration',
        properties: [
          { key: 'port', type: 'integer', required: true, min: 1, max: 65535, description: 'Server port' },
          { key: 'debug', type: 'boolean', required: false, default: false, description: 'Debug mode' },
          { key: 'name', type: 'string', required: true, min: 1, max: 100, description: 'App name' },
          { key: 'environment', type: 'string', required: true, enum: ['development', 'staging', 'production'] },
        ],
      },
      {
        key: 'database',
        type: 'object',
        required: true,
        description: 'Database configuration',
        properties: [
          { key: 'host', type: 'string', required: true, pattern: '^[a-zA-Z0-9._-]+$' },
          { key: 'port', type: 'integer', required: true, min: 1, max: 65535, default: 5432 },
          { key: 'username', type: 'string', required: true, min: 1 },
          { key: 'password', type: 'string', required: true, min: 8 },
          { key: 'name', type: 'string', required: true, min: 1 },
        ],
      },
      {
        key: 'rateLimit',
        type: 'object',
        required: false,
        description: 'Rate limiting configuration',
        properties: [
          { key: 'maxRequests', type: 'integer', required: false, min: 1, max: 1000000, default: 100 },
          { key: 'windowMs', type: 'integer', required: false, min: 1000, default: 60000 },
        ],
      },
    ],
  }

  const targetPath = path.join(outputDir, 'config-schema.json')
  fs.writeFileSync(targetPath, JSON.stringify(schema, null, 2) + '\n')
  return targetPath
}

export function validateCliConfig(config: CliConfig): string[] {
  const errors: string[] = []

  if (!config.projectRoot) errors.push('projectRoot is required')
  if (!config.storagePath) errors.push('storagePath is required')
  if (!config.gitRepoPath) errors.push('gitRepoPath is required')

  const envNames = new Set<string>()
  for (const env of config.environments) {
    if (!env.name) {
      errors.push('Environment name is required')
      continue
    }
    if (envNames.has(env.name)) {
      errors.push(`Duplicate environment name: ${env.name}`)
    }
    envNames.add(env.name)

    if (!env.sources || env.sources.length === 0) {
      errors.push(`Environment ${env.name} has no sources configured`)
      continue
    }

    for (const source of env.sources) {
      if (!['vault', 'ssm', 'configmap', 'env', 'default'].includes(source.type)) {
        errors.push(`Environment ${env.name}: unknown source type ${source.type}`)
      }
    }
  }

  return errors
}

export function configToAppConfig(config: CliConfig): AppConfig {
  return {
    projectRoot: config.projectRoot,
    storagePath: config.storagePath,
    gitRepoPath: config.gitRepoPath,
    environments: config.environments,
    notifications: config.notifications,
    schemaPath: config.schemaPath,
  }
}
