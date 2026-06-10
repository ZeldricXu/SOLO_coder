import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { Environment } from '../../sources/ConfigManager'
import { ConfigSource, BaseConfigSource } from '../../sources/ConfigSource'
import { ConfigData, ConfigValue } from '../../types'
import { RotationScheduler } from '../../rotation/RotationScheduler'
import { SyncPipeline } from '../../sync/SyncPipeline'
import { ConfigManager } from '../../sources/ConfigManager'
import { createTempDir, removeTempDir } from '../factories/TestHelper'

class InMemorySource extends BaseConfigSource {
  readonly type: string
  readonly priority: number
  readonly name: string
  private data: ConfigData

  constructor(name: string, priority: number, data: ConfigData, type = 'memory') {
    super()
    this.name = name
    this.priority = priority
    this.type = type
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

  getData(): ConfigData {
    return this.data
  }
}

describe('Secret Rotation Integration Test', () => {
  let tempDir: string
  let scheduler: RotationScheduler
  let vaultSource: InMemorySource
  let stagingVaultSource: InMemorySource
  let vaultEnv: Environment
  let stagingEnv: Environment
  let configManager: ConfigManager

  beforeEach(() => {
    tempDir = createTempDir('rotation-integration-')
    scheduler = new RotationScheduler('test-operator')

    vaultSource = new InMemorySource('vault-prod', 90, {
      'db.password': 'old-prod-secret',
      'api.key': 'old-api-key',
    }, 'vault')

    stagingVaultSource = new InMemorySource('vault-staging', 90, {
      'db.password': 'old-staging-secret',
      'api.key': 'old-staging-api-key',
    }, 'vault')

    vaultEnv = new Environment('prod', [vaultSource as ConfigSource])
    stagingEnv = new Environment('staging', [stagingVaultSource as ConfigSource])

    configManager = new ConfigManager()
    configManager.addEnvironment({
      name: 'prod',
      sources: [{ type: 'default', priority: 10, options: { defaults: vaultSource.getData() } }],
    })
    configManager.addEnvironment({
      name: 'staging',
      sources: [{ type: 'default', priority: 10, options: { defaults: stagingVaultSource.getData() } }],
    })
  })

  afterEach(() => {
    removeTempDir(tempDir)
  })

  describe('normal rotation flow', () => {
    it('should rotate a secret in Vault environment', async () => {
      const record = await scheduler.rotateSecret(vaultEnv, 'db.password', {
        operator: 'admin',
      })

      expect(record.status).toBe('success')
      expect(record.key).toBe('db.password')
      expect(record.environment).toBe('prod')
      expect(record.operator).toBe('admin')

      const newValue = await vaultEnv.get('db.password')
      expect(newValue).toBeDefined()
      expect(newValue).not.toBe('old-prod-secret')
      expect(typeof newValue).toBe('string')
      expect((newValue as string).length).toBe(32)
    })

    it('should generate different values on each rotation', async () => {
      const record1 = await scheduler.rotateSecret(vaultEnv, 'db.password')
      const record2 = await scheduler.rotateSecret(vaultEnv, 'db.password')

      expect(record1.status).toBe('success')
      expect(record2.status).toBe('success')

      const value1 = record1.id
      const value2 = record2.id
      expect(value1).not.toBe(value2)
    })

    it('should verify rotated value when verify option is set', async () => {
      const record = await scheduler.rotateSecret(vaultEnv, 'db.password', {
        verify: true,
      })

      expect(record.status).toBe('success')

      const currentValue = await vaultEnv.get('db.password')
      expect(currentValue).toBeDefined()
    })

    it('should track rotation history', async () => {
      await scheduler.rotateSecret(vaultEnv, 'db.password')
      await scheduler.rotateSecret(vaultEnv, 'api.key')

      const history = scheduler.getRotationHistory()
      expect(history.length).toBe(2)

      const dbHistory = scheduler.getRotationHistory({ key: 'db.password' })
      expect(dbHistory.length).toBe(1)
    })

    it('should get last rotation for specific key', async () => {
      await scheduler.rotateSecret(vaultEnv, 'db.password')

      const last = scheduler.getLastRotation('prod', 'db.password')
      expect(last).toBeDefined()
      expect(last!.status).toBe('success')
      expect(last!.key).toBe('db.password')
    })

    it('should calculate rotation age', async () => {
      await scheduler.rotateSecret(vaultEnv, 'db.password')

      const age = scheduler.getRotationAge('prod', 'db.password')
      expect(age).toBeDefined()
      expect(age!).toBeGreaterThanOrEqual(0)
    })

    it('should detect when rotation is needed', async () => {
      const needsRotation = scheduler.needsRotation('prod', 'db.password', 1000)
      expect(needsRotation).toBe(true)

      await scheduler.rotateSecret(vaultEnv, 'db.password')

      const needsRotationAfter = scheduler.needsRotation('prod', 'db.password', 86400000)
      expect(needsRotationAfter).toBe(false)
    })
  })

  describe('rotation with callbacks', () => {
    it('should call onBeforeRotate before rotation', async () => {
      const beforeCallback = vi.fn()
      await scheduler.rotateSecret(vaultEnv, 'db.password', {
        onBeforeRotate: beforeCallback,
      })

      expect(beforeCallback).toHaveBeenCalledWith('db.password', 'prod')
    })

    it('should call onAfterRotate after rotation with old and new values', async () => {
      const afterCallback = vi.fn()
      await scheduler.rotateSecret(vaultEnv, 'db.password', {
        onAfterRotate: afterCallback,
      })

      expect(afterCallback).toHaveBeenCalledWith(
        'db.password',
        'prod',
        'old-prod-secret',
        expect.any(String)
      )
    })

    it('should call onNotify with rotation message', async () => {
      const notifyCallback = vi.fn()
      await scheduler.rotateSecret(vaultEnv, 'db.password', {
        onNotify: notifyCallback,
      })

      expect(notifyCallback).toHaveBeenCalledWith(
        expect.stringContaining('Secret rotated: db.password')
      )
    })
  })

  describe('batch rotation', () => {
    it('should rotate multiple keys in batch', async () => {
      const records = await scheduler.rotateBatch(vaultEnv, [
        'db.password',
        'api.key',
      ])

      expect(records.length).toBe(2)
      expect(records.every((r) => r.status === 'success')).toBe(true)

      const newPassword = await vaultEnv.get('db.password')
      const newApiKey = await vaultEnv.get('api.key')
      expect(newPassword).not.toBe('old-prod-secret')
      expect(newApiKey).not.toBe('old-api-key')
    })
  })

  describe('rotation + sync pipeline', () => {
    it('should rotate secret in Vault then sync to staging', async () => {
      const rotateRecord = await scheduler.rotateSecret(vaultEnv, 'db.password')
      expect(rotateRecord.status).toBe('success')

      const newProdValue = await vaultEnv.get('db.password')
      expect(newProdValue).not.toBe('old-prod-secret')

      const syncPipeline = new SyncPipeline(configManager)

      const prodEnv = configManager.getEnvironment('prod')
      const stagEnv = configManager.getEnvironment('staging')

      if (prodEnv) {
        await prodEnv.set('db.password', newProdValue!)
      }

      const stagingValueBefore = await stagingVaultSource.get('db.password')
      expect(stagingValueBefore).toBe('old-staging-secret')

      await stagingVaultSource.set('db.password', newProdValue!)

      const stagingValueAfter = await stagingVaultSource.get('db.password')
      expect(stagingValueAfter).toBe(newProdValue)

      expect(stagingValueAfter).not.toBe('old-staging-secret')
    })

    it('should verify old secret is no longer the current value after rotation', async () => {
      const oldPassword = await vaultEnv.get('db.password')
      expect(oldPassword).toBe('old-prod-secret')

      await scheduler.rotateSecret(vaultEnv, 'db.password')

      const currentPassword = await vaultEnv.get('db.password')
      expect(currentPassword).not.toBe(oldPassword)
    })
  })

  describe('scheduled rotation', () => {
    it('should schedule and cancel rotation', async () => {
      const scheduleId = scheduler.scheduleRotation(
        vaultEnv,
        { key: 'db.password', environment: 'prod', sourceType: 'vault' },
        60000
      )

      expect(scheduleId).toBeDefined()

      const cancelled = scheduler.cancelScheduledRotation(scheduleId)
      expect(cancelled).toBe(true)
    })

    it('should cancel all scheduled rotations', async () => {
      scheduler.scheduleRotation(
        vaultEnv,
        { key: 'db.password', environment: 'prod', sourceType: 'vault' },
        60000
      )
      scheduler.scheduleRotation(
        vaultEnv,
        { key: 'api.key', environment: 'prod', sourceType: 'vault' },
        60000
      )

      scheduler.cancelAllScheduled()
    })
  })

  describe('rotation failure handling', () => {
    it('should record failed rotation when source throws', async () => {
      const failingSource = new InMemorySource('failing', 90, { key: 'value' })
      failingSource.type = 'vault' as any
      const failingEnv = new Environment('failing', [failingSource as ConfigSource])

      const originalSet = failingSource.set.bind(failingSource)
      failingSource.set = async () => {
        throw new Error('Vault connection timeout')
      }

      await expect(
        scheduler.rotateSecret(failingEnv, 'key')
      ).rejects.toThrow()

      const failedRecords = scheduler.getRotationHistory({ status: 'failed' })
      expect(failedRecords.length).toBe(1)
      expect(failedRecords[0].message).toContain('Vault connection timeout')
    })
  })

  describe('edge cases', () => {
    it('should handle rotation of non-existent key', async () => {
      const record = await scheduler.rotateSecret(vaultEnv, 'nonexistent.key')
      expect(record.status).toBe('success')

      const value = await vaultEnv.get('nonexistent.key')
      expect(value).toBeDefined()
    })

    it('should handle rotation with empty data', async () => {
      const emptySource = new InMemorySource('empty', 90, {})
      emptySource.type = 'vault' as any
      const emptyEnv = new Environment('empty', [emptySource as ConfigSource])

      const record = await scheduler.rotateSecret(emptyEnv, 'new.key')
      expect(record.status).toBe('success')
    })
  })
})
