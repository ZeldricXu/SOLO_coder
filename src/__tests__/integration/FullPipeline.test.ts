import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { ConfigManager, Environment } from '../../sources/ConfigManager'
import { BaseConfigSource } from '../../sources/ConfigSource'
import { ConfigSource } from '../../sources/ConfigSource'
import { ConfigData, ConfigValue, ConfigSourceConfig } from '../../types'
import { DiffEngine } from '../../engine/DiffEngine'
import { SchemaValidator, SchemaConfig } from '../../schemas/SchemaValidator'
import { TemplateRenderer } from '../../renderer/TemplateRenderer'
import { GitTracker } from '../../git/GitTracker'
import { SyncPipeline } from '../../sync/SyncPipeline'
import { createTempDir, removeTempDir, writeEnvFile, writeTemplate, readOutputFile } from '../factories/TestHelper'
import {
  createDevConfig,
  createStagingConfig,
  createProdConfig,
  createDevEnvContent,
  createNginxTemplate,
  createSchemaConfig,
} from '../factories/TestDataFactory'

class InMemorySource extends BaseConfigSource {
  readonly type = 'memory'
  readonly priority: number
  readonly name: string
  private data: ConfigData

  constructor(name: string, priority: number, data: ConfigData) {
    super()
    this.name = name
    this.priority = priority
    this.data = {}
    for (const [key, value] of Object.entries(data)) {
      this.setNestedValue(this.data, key, value)
    }
  }

  private flattenData(obj: Record<string, unknown>, prefix = ''): ConfigData {
    const result: ConfigData = {}
    for (const [key, value] of Object.entries(obj)) {
      const fullKey = prefix ? `${prefix}.${key}` : key
      if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        Object.assign(result, this.flattenData(value as Record<string, unknown>, fullKey))
      } else {
        result[fullKey] = value as ConfigValue
      }
    }
    return result
  }

  async load(): Promise<ConfigData> {
    return this.flattenData(this.data as Record<string, unknown>)
  }

  async get(key: string): Promise<ConfigValue | undefined> {
    return this.getNestedValue(this.data, key)
  }

  async set(key: string, value: ConfigValue): Promise<void> {
    this.setNestedValue(this.data, key, value)
  }

  async delete(key: string): Promise<void> {
    const parts = key.split('.')
    let target = this.data
    for (let i = 0; i < parts.length - 1; i++) {
      if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
        return
      }
      target = target[parts[i]] as ConfigData
    }
    delete target[parts[parts.length - 1]]
  }

  async listKeys(): Promise<string[]> {
    return Object.keys(this.flattenData(this.data as Record<string, unknown>))
  }
}

describe('Full Pipeline Integration Test', () => {
  let tempDir: string
  let configManager: ConfigManager
  let diffEngine: DiffEngine
  let schemaValidator: SchemaValidator
  let templateRenderer: TemplateRenderer
  let gitTracker: GitTracker
  let syncPipeline: SyncPipeline

  let devEnv: Environment
  let stagingEnv: Environment
  let prodEnv: Environment

  let devConfig: ConfigData
  let stagingConfig: ConfigData
  let prodConfig: ConfigData

  beforeEach(async () => {
    tempDir = createTempDir('pipeline-integration-')

    devConfig = createDevConfig()
    stagingConfig = createStagingConfig()
    prodConfig = createProdConfig()

    configManager = new ConfigManager()

    const envConfig: EnvironmentConfig = {
      name: 'dev',
      sources: [
        { type: 'default', priority: 10, options: { defaults: devConfig } },
      ],
    }
    const stagingEnvConfig: EnvironmentConfig = {
      name: 'staging',
      sources: [
        { type: 'default', priority: 10, options: { defaults: stagingConfig } },
      ],
    }
    const prodEnvConfig: EnvironmentConfig = {
      name: 'prod',
      sources: [
        { type: 'default', priority: 10, options: { defaults: prodConfig } },
      ],
    }

    devEnv = configManager.addEnvironment(envConfig)
    stagingEnv = configManager.addEnvironment(stagingEnvConfig)
    prodEnv = configManager.addEnvironment(prodEnvConfig)

    diffEngine = new DiffEngine()
    schemaValidator = new SchemaValidator(createSchemaConfig())
    templateRenderer = new TemplateRenderer()

    const gitRepoPath = path.join(tempDir, 'config-repo')
    fs.mkdirSync(gitRepoPath, { recursive: true })
    gitTracker = new GitTracker(gitRepoPath)
    await gitTracker.ensureInitialized({ operator: 'test-user' })

    syncPipeline = new SyncPipeline(configManager)
  })

  afterEach(() => {
    removeTempDir(tempDir)
  })

  describe('Step 1: Source connectors read configuration', () => {
    it('should load all three environments', async () => {
      const allData = await configManager.loadAll()

      expect(allData.get('dev')).toBeDefined()
      expect(allData.get('staging')).toBeDefined()
      expect(allData.get('prod')).toBeDefined()

      expect(allData.get('dev')!['app.name']).toBe('my-service')
      expect(allData.get('prod')!['app.port']).toBe(8080)
    })

    it('should read individual keys from each environment', async () => {
      expect(await devEnv.get('app.name')).toBe('my-service')
      expect(await stagingEnv.get('db.host')).toBe('staging-db.internal')
      expect(await prodEnv.get('logLevel')).toBe('warn')
    })

    it('should list keys for each environment', async () => {
      const devKeys = await devEnv.listKeys()
      expect(devKeys.length).toBeGreaterThan(0)
    })
  })

  describe('Step 2: Schema validation across environments', () => {
    it('should validate dev config successfully', () => {
      const report = schemaValidator.validate(devConfig, 'dev')
      expect(report.valid).toBe(true)
      expect(report.errors).toEqual([])
    })

    it('should validate staging config successfully', () => {
      const report = schemaValidator.validate(stagingConfig, 'staging')
      expect(report.valid).toBe(true)
    })

    it('should validate prod config successfully', () => {
      const report = schemaValidator.validate(prodConfig, 'prod')
      expect(report.valid).toBe(true)
    })

    it('should reject config with type errors', () => {
      const badConfig = {
        ...devConfig,
        app: { name: 'my-service', port: 'not-a-number', debug: true },
      }
      const report = schemaValidator.validate(badConfig, 'bad-env')
      expect(report.valid).toBe(false)
      const portError = report.errors.find((e) => e.key === 'app.port')
      expect(portError).toBeDefined()
      expect(portError!.expected).toContain('integer')
    })
  })

  describe('Step 3: Diff detection between dev and prod', () => {
    it('should detect configuration drift between dev and prod', () => {
      const report = diffEngine.compare(devConfig, prodConfig, 'dev', 'prod')

      expect(report.summary.total).toBeGreaterThan(0)
      expect(report.summary.changed).toBeGreaterThan(0)

      const portDiff = report.diffs.find((d) => d.path === 'app.port')
      expect(portDiff).toBeDefined()
      expect(portDiff!.before).toBe(3000)
      expect(portDiff!.after).toBe(8080)

      const hostDiff = report.diffs.find((d) => d.path === 'db.host')
      expect(hostDiff).toBeDefined()
      expect(hostDiff!.before).toBe('localhost')
      expect(hostDiff!.after).toBe('prod-db.internal')

      const debugDiff = report.diffs.find((d) => d.path === 'app.debug')
      expect(debugDiff).toBeDefined()
      expect(debugDiff!.before).toBe(true)
      expect(debugDiff!.after).toBe(false)
    })

    it('should format diff output in git-style', () => {
      const report = diffEngine.compare(devConfig, prodConfig, 'dev', 'prod')
      const formatted = diffEngine.formatDiff(report, false)

      expect(formatted).toContain('Diff between dev and prod')
      expect(formatted).toContain('Summary:')
    })
  })

  describe('Step 4: Template rendering with prod config', () => {
    it('should render nginx.conf from prod config', () => {
      const templatePath = writeTemplate(tempDir, 'nginx.conf.hbs', createNginxTemplate())
      const outputPath = path.join(tempDir, 'nginx.conf')

      const result = templateRenderer.render({
        templatePath,
        outputPath,
        context: prodConfig,
        environment: 'prod',
      })

      expect(result.success).toBe(true)

      const content = readOutputFile(outputPath)
      expect(content).toContain('upstream my-service')
      expect(content).toContain('127.0.0.1:8080')
      expect(content).toContain('my-service.example.com')
      expect(content).toContain('proxy_pass')
      expect(content).toContain('error_log')
      expect(content).not.toContain('debug-mode')
    })

    it('should render nginx.conf with debug block for dev config', () => {
      const templatePath = writeTemplate(tempDir, 'nginx-dev.conf.hbs', createNginxTemplate())
      const outputPath = path.join(tempDir, 'nginx-dev.conf')

      const result = templateRenderer.render({
        templatePath,
        outputPath,
        context: devConfig,
        environment: 'dev',
      })

      expect(result.success).toBe(true)
      const content = readOutputFile(outputPath)
      expect(content).toContain('/debug')
    })
  })

  describe('Step 5: Git version tracking', () => {
    it('should save snapshots and commit to git', async () => {
      gitTracker.saveAllSnapshots({
        dev: devConfig,
        staging: stagingConfig,
        prod: prodConfig,
      })

      const commit = await gitTracker.commitChanges('initial config commit', {
        operator: 'test-user',
      })

      expect(commit).not.toBeNull()
      expect(commit!.message).toContain('test-user')
      expect(commit!.message).toContain('initial config commit')
    })

    it('should retrieve commit log', async () => {
      gitTracker.saveAllSnapshots({ dev: devConfig })
      await gitTracker.commitChanges('first commit', { operator: 'test-user' })

      gitTracker.saveAllSnapshots({ dev: { ...devConfig, logLevel: 'warn' } })
      await gitTracker.commitChanges('change log level', { operator: 'test-user' })

      const log = await gitTracker.log({ environment: 'dev' })
      expect(log.length).toBeGreaterThanOrEqual(2)
    })

    it('should load environment snapshot', async () => {
      gitTracker.saveEnvironmentSnapshot('dev', devConfig)
      await gitTracker.commitChanges('save dev', { operator: 'test-user' })

      const loaded = await gitTracker.loadEnvironmentSnapshot('dev')
      expect(loaded).not.toBeNull()
      expect(loaded!['app.name']).toBe('my-service')
    })

    it('should track key history across commits', async () => {
      gitTracker.saveEnvironmentSnapshot('dev', devConfig)
      await gitTracker.commitChanges('initial dev', { operator: 'test-user' })

      const modifiedDev = { ...devConfig, logLevel: 'info' }
      gitTracker.saveEnvironmentSnapshot('dev', modifiedDev)
      await gitTracker.commitChanges('change logLevel', { operator: 'test-user' })

      const history = await gitTracker.getKeyHistory('dev', 'logLevel')
      expect(history.length).toBeGreaterThanOrEqual(1)
    })
  })

  describe('Step 6: Sync pipeline', () => {
    it('should preview sync from dev to staging', async () => {
      const previews = await syncPipeline.previewSync({
        key: 'logLevel',
        sourceEnvironment: 'dev',
        targetEnvironments: ['staging'],
      })

      expect(previews.length).toBe(1)
      expect(previews[0].action).toBe('update')
      expect(previews[0].newValue).toBe('debug')
    })

    it('should execute dry-run sync', async () => {
      const result = await syncPipeline.executeBatch(
        [{
          key: 'logLevel',
          sourceEnvironment: 'dev',
          targetEnvironments: ['staging'],
        }],
        { dryRun: true }
      )

      expect(result.summary.total).toBe(1)
      expect(result.results[0].message).toContain('dry-run')
    })

    it('should execute actual sync and verify', async () => {
      const result = await syncPipeline.executeBatch(
        [{
          key: 'logLevel',
          sourceEnvironment: 'dev',
          targetEnvironments: ['staging'],
        }],
        { verifyAfter: true }
      )

      expect(result.summary.success).toBe(1)

      const stagingValue = await stagingEnv.get('logLevel')
      expect(stagingValue).toBe('debug')
    })

    it('should sync with schema validation', async () => {
      const result = await syncPipeline.executeBatch(
        [{
          key: 'app.port',
          sourceEnvironment: 'prod',
          targetEnvironments: ['staging'],
        }],
        { validateBefore: true, validator: schemaValidator }
      )

      expect(result.summary.success).toBe(1)
    })
  })

  describe('Step 7: Env file source integration', () => {
    it('should read from .env file and integrate with pipeline', async () => {
      const envPath = writeEnvFile(tempDir, '.env.dev', createDevEnvContent())

      const { EnvSource } = await import('../../sources/EnvSource')
      const envSource = new EnvSource('env-dev', 100, {
        filePath: envPath,
        useProcessEnv: false,
      })

      const data = await envSource.load()
      expect(data['app.name']).toBe('my-service')
      expect(data['app.port']).toBe(3000)
      expect(data['db.host']).toBe('localhost')
    })
  })

  describe('End-to-end: Complete pipeline workflow', () => {
    it('should run full pipeline: load → validate → diff → render → sync → git', async () => {
      const allData = await configManager.loadAll()
      expect(allData.size).toBe(3)

      const devReport = schemaValidator.validate(devConfig, 'dev')
      const prodReport = schemaValidator.validate(prodConfig, 'prod')
      expect(devReport.valid).toBe(true)
      expect(prodReport.valid).toBe(true)

      const diffReport = diffEngine.compare(devConfig, prodConfig, 'dev', 'prod')
      expect(diffReport.summary.total).toBeGreaterThan(0)

      const templatePath = writeTemplate(tempDir, 'nginx-e2e.hbs', createNginxTemplate())
      const nginxOutput = path.join(tempDir, 'nginx-e2e.conf')
      const renderResult = templateRenderer.render({
        templatePath,
        outputPath: nginxOutput,
        context: prodConfig,
        environment: 'prod',
      })
      expect(renderResult.success).toBe(true)

      const syncResult = await syncPipeline.executeBatch(
        [{
          key: 'rateLimit.max',
          sourceEnvironment: 'dev',
          targetEnvironments: ['staging'],
        }],
        { verifyAfter: true }
      )
      expect(syncResult.summary.success).toBe(1)

      gitTracker.saveAllSnapshots({
        dev: devConfig,
        staging: stagingConfig,
        prod: prodConfig,
      })
      const gitCommit = await gitTracker.commitChanges('e2e: full pipeline sync', {
        operator: 'ci-bot',
      })
      expect(gitCommit).not.toBeNull()
      expect(gitCommit!.message).toContain('ci-bot')

      const log = await gitTracker.log({})
      expect(log.length).toBeGreaterThan(0)
    })
  })
})
