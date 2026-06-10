import * as crypto from 'crypto'
import { ConfigSource } from '../sources'
import { Environment } from '../sources/ConfigManager'
import { RotationRecord, SecretRotationConfig, ConfigValue } from '../types'

export type KeyGenerator = (length?: number, characters?: string) => string

export interface RotationOptions {
  onBeforeRotate?: (key: string, environment: string) => Promise<void> | void
  onAfterRotate?: (key: string, environment: string, oldValue: ConfigValue | undefined, newValue: ConfigValue) => Promise<void> | void
  onNotify?: (message: string) => Promise<void> | void
  operator?: string
  verify?: boolean
}

export class RotationScheduler {
  private records: RotationRecord[] = []
  private scheduledRotations: Map<string, NodeJS.Timeout> = new Map()
  private defaultOperator: string

  constructor(defaultOperator?: string) {
    this.defaultOperator = defaultOperator || process.env.USER || 'system'
  }

  private generateRandomKey(length = 32, characters = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?'): string {
    const array = new Uint32Array(length)
    crypto.randomFillSync(array)
    let result = ''
    for (let i = 0; i < length; i++) {
      result += characters[array[i] % characters.length]
    }
    return result
  }

  async rotateSecret(
    environment: Environment,
    key: string,
    options: RotationOptions = {}
  ): Promise<RotationRecord> {
    const operator = options.operator || this.defaultOperator
    const recordId = this.generateRecordId()

    let sourceType = 'default'
    const vaultSource = environment.getSourceByType('vault')
    const ssmSource = environment.getSourceByType('ssm')
    const source: ConfigSource | undefined = vaultSource || ssmSource

    if (vaultSource) sourceType = 'vault'
    else if (ssmSource) sourceType = 'ssm'

    let oldValue: ConfigValue | undefined
    let newValue: ConfigValue

    try {
      if (options.onBeforeRotate) {
        await options.onBeforeRotate(key, environment.name)
      }

      if (source) {
        oldValue = await source.get(key)
      } else {
        oldValue = await environment.get(key)
      }

      newValue = this.generateRandomKey(32)

      const highestSource = source || environment.getHighestPrioritySource()
      await highestSource.set(key, newValue)

      if (options.verify) {
        const verifiedValue = await environment.get(key)
        if (verifiedValue !== newValue) {
          throw new Error(`Verification failed: value does not match after rotation`)
        }
      }

      if (options.onAfterRotate) {
        await options.onAfterRotate(key, environment.name, oldValue, newValue)
      }

      if (options.onNotify) {
        await options.onNotify(`Secret rotated: ${key} in ${environment.name} by ${operator}`)
      }

      const record: RotationRecord = {
        id: recordId,
        key,
        environment: environment.name,
        sourceType,
        timestamp: Date.now(),
        operator,
        status: 'success',
      }
      this.records.push(record)
      return record
    } catch (error) {
      const record: RotationRecord = {
        id: recordId,
        key,
        environment: environment.name,
        sourceType,
        timestamp: Date.now(),
        operator,
        status: 'failed',
        message: (error as Error).message,
      }
      this.records.push(record)
      throw error
    }
  }

  async rotateBatch(
    environment: Environment,
    keys: string[],
    options: RotationOptions = {}
  ): Promise<RotationRecord[]> {
    const results: RotationRecord[] = []
    for (const key of keys) {
      try {
        const record = await this.rotateSecret(environment, key, options)
        results.push(record)
      } catch (error) {
        const failedRecord: RotationRecord = {
          id: this.generateRecordId(),
          key,
          environment: environment.name,
          sourceType: 'unknown',
          timestamp: Date.now(),
          operator: options.operator || this.defaultOperator,
          status: 'failed',
          message: (error as Error).message,
        }
        results.push(failedRecord)
      }
    }
    return results
  }

  scheduleRotation(
    environment: Environment,
    config: SecretRotationConfig,
    intervalMs: number,
    options: RotationOptions = {}
  ): string {
    const scheduleId = `${environment.name}:${config.key}:${Date.now()}`

    const timeout = setInterval(async () => {
      try {
        await this.rotateSecret(environment, config.key, options)
      } catch (error) {
        console.error(`Scheduled rotation failed for ${config.key} in ${environment.name}:`, error)
      }
    }, intervalMs)

    this.scheduledRotations.set(scheduleId, timeout)
    return scheduleId
  }

  cancelScheduledRotation(scheduleId: string): boolean {
    const timeout = this.scheduledRotations.get(scheduleId)
    if (timeout) {
      clearInterval(timeout)
      this.scheduledRotations.delete(scheduleId)
      return true
    }
    return false
  }

  cancelAllScheduled(): void {
    for (const timeout of this.scheduledRotations.values()) {
      clearInterval(timeout)
    }
    this.scheduledRotations.clear()
  }

  getRotationHistory(filters?: {
    environment?: string
    key?: string
    operator?: string
    status?: 'success' | 'failed'
    since?: number
    until?: number
  }): RotationRecord[] {
    return this.records.filter((record) => {
      if (filters?.environment && record.environment !== filters.environment) return false
      if (filters?.key && record.key !== filters.key) return false
      if (filters?.operator && record.operator !== filters.operator) return false
      if (filters?.status && record.status !== filters.status) return false
      if (filters?.since && record.timestamp < filters.since) return false
      if (filters?.until && record.timestamp > filters.until) return false
      return true
    }).sort((a, b) => b.timestamp - a.timestamp)
  }

  getLastRotation(environment: string, key: string): RotationRecord | undefined {
    return this.records
      .filter((r) => r.environment === environment && r.key === key && r.status === 'success')
      .sort((a, b) => b.timestamp - a.timestamp)[0]
  }

  getRotationAge(environment: string, key: string): number | undefined {
    const last = this.getLastRotation(environment, key)
    if (!last) return undefined
    return Date.now() - last.timestamp
  }

  needsRotation(environment: string, key: string, maxAgeMs: number): boolean {
    const age = this.getRotationAge(environment, key)
    if (age === undefined) return true
    return age > maxAgeMs
  }

  private generateRecordId(): string {
    return 'rot_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
  }

  setRecords(records: RotationRecord[]): void {
    this.records = records
  }

  getAllRecords(): RotationRecord[] {
    return [...this.records]
  }
}
