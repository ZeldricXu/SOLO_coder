import { BaseConfigSource } from './ConfigSource'
import { ConfigData, ConfigValue } from '../types'

export interface RetryPolicy {
  maxRetries: number
  backoffMultiplier: number
  initialDelayMs: number
  retryableErrorTypes: string[]
}

export const DEFAULT_RETRY_POLICY: RetryPolicy = {
  maxRetries: 3,
  backoffMultiplier: 2,
  initialDelayMs: 100,
  retryableErrorTypes: [
    'ETIMEDOUT',
    'ECONNRESET',
    'ECONNREFUSED',
    'EHOSTUNREACH',
    'ENETUNREACH',
    'timeout',
    'connection refused',
    'rate limit',
    'too many requests',
    '429',
    '500',
    '502',
    '503',
    '504',
  ],
}

export abstract class BaseConnector extends BaseConfigSource {
  abstract readonly type: string
  abstract readonly priority: number
  abstract readonly name: string

  protected abstract readonly sourceName: string

  protected data: ConfigData = {}
  protected loaded = false
  protected client: any = null
  protected retryPolicy: RetryPolicy
  protected loadTimeoutMs: number = 30000

  constructor(retryPolicy?: Partial<RetryPolicy>, loadTimeoutMs?: number) {
    super()
    this.retryPolicy = { ...DEFAULT_RETRY_POLICY, ...retryPolicy }
    if (loadTimeoutMs) this.loadTimeoutMs = loadTimeoutMs
  }

  protected abstract initClient(): Promise<void>
  protected abstract fetchConfig(): Promise<Record<string, ConfigValue>>
  protected abstract writeConfig(key: string, value: ConfigValue): Promise<void>
  protected abstract deleteConfig(key: string): Promise<void>

  protected isRetryable(error: Error): boolean {
    const msg = (error.message || '').toLowerCase()
    return this.retryPolicy.retryableErrorTypes.some((type) =>
      msg.includes(type.toLowerCase())
    )
  }

  protected async sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms))
  }

  protected async withRetry<T>(
    operation: () => Promise<T>,
    operationName: string,
  ): Promise<T> {
    let lastError: unknown = null

    for (let attempt = 0; attempt <= this.retryPolicy.maxRetries; attempt++) {
      try {
        return await operation()
      } catch (error) {
        lastError = error
        if (attempt >= this.retryPolicy.maxRetries) break
        if (!this.isRetryable(error as Error)) break

        const delay =
          this.retryPolicy.initialDelayMs *
          Math.pow(this.retryPolicy.backoffMultiplier, attempt)
        await this.sleep(delay)
      }
    }

    throw new Error(
      `${operationName} failed after ${this.retryPolicy.maxRetries + 1} attempts: ${
        (lastError as Error).message
      }`
    )
  }

  protected async withTimeout<T>(
    promise: Promise<T>,
    timeoutMs: number,
    operationName: string,
  ): Promise<T> {
    let timeoutId: NodeJS.Timeout
    const timeoutPromise = new Promise<never>((_, reject) => {
      timeoutId = setTimeout(
        () => reject(new Error(`${operationName} timed out after ${timeoutMs}ms`)),
        timeoutMs,
      )
    })

    try {
      return await Promise.race([promise, timeoutPromise])
    } finally {
      clearTimeout(timeoutId!)
    }
  }

  protected flattenData(obj: Record<string, unknown>, prefix = ''): ConfigData {
    const result: ConfigData = {}
    for (const [key, value] of Object.entries(obj)) {
      const fullKey = prefix ? `${prefix}.${key}` : key
      if (
        value !== null &&
        typeof value === 'object' &&
        !Array.isArray(value)
      ) {
        Object.assign(
          result,
          this.flattenData(value as Record<string, unknown>, fullKey)
        )
      } else {
        result[fullKey] = value as ConfigValue
      }
    }
    return result
  }

  async load(): Promise<ConfigData> {
    await this.initClient()

    const result = await this.withRetry(
      () =>
        this.withTimeout(
          this.fetchConfig(),
          this.loadTimeoutMs,
          `${this.sourceName} fetch`,
        ),
      `${this.sourceName} fetch`,
    )

    this.data = {}
    for (const [key, value] of Object.entries(result)) {
      this.setNestedValue(this.data, key, value)
    }
    this.loaded = true
    return this.flattenData(this.data as Record<string, unknown>)
  }

  async get(key: string): Promise<ConfigValue | undefined> {
    if (!this.loaded) await this.load()
    return this.getNestedValue(this.data, key)
  }

  async set(key: string, value: ConfigValue): Promise<void> {
    await this.initClient()

    await this.withRetry(
      () =>
        this.withTimeout(
          this.writeConfig(key, value),
          this.loadTimeoutMs,
          `${this.sourceName} write`,
        ),
      `${this.sourceName} write`,
    )

    this.setNestedValue(this.data, key, value)
  }

  async delete(key: string): Promise<void> {
    await this.initClient()

    await this.withRetry(
      () =>
        this.withTimeout(
          this.deleteConfig(key),
          this.loadTimeoutMs,
          `${this.sourceName} delete`,
        ),
      `${this.sourceName} delete`,
    )

    const parts = key.split('.')
    let target = this.data
    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i]
      if (
        !target[part] ||
        typeof target[part] !== 'object' ||
        Array.isArray(target[part])
      ) {
        return
      }
      target = target[part] as ConfigData
    }
    delete target[parts[parts.length - 1]]
  }

  async listKeys(): Promise<string[]> {
    if (!this.loaded) await this.load()
    return Object.keys(this.flattenData(this.data as Record<string, unknown>))
  }
}
