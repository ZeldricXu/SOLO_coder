import { BaseConnector, RetryPolicy } from './BaseConnector'
import { ConfigData, ConfigValue } from '../types'
import * as fs from 'fs'
import * as path from 'path'
import * as dotenv from 'dotenv'

interface EnvSourceOptions {
  filePath?: string
  useProcessEnv?: boolean
  prefix?: string
  lowerCaseKeys?: boolean
  retryPolicy?: Partial<RetryPolicy>
  loadTimeoutMs?: number
}

export class EnvSource extends BaseConnector {
  readonly type = 'env'
  readonly priority: number
  readonly name: string

  protected readonly sourceName = 'Env'

  private options: EnvSourceOptions

  constructor(name: string, priority: number, options: EnvSourceOptions = {}) {
    super(options.retryPolicy, options.loadTimeoutMs)
    this.name = name
    this.priority = priority
    this.options = {
      useProcessEnv: true,
      lowerCaseKeys: true,
      ...options,
    }
  }

  protected async initClient(): Promise<void> {
    return Promise.resolve()
  }

  private parseValue(value: string): ConfigValue {
    if (value === 'true') return true
    if (value === 'false') return false
    if (value === 'null') return null
    if (value === '') return ''
    const num = Number(value)
    if (!isNaN(num) && value.trim() !== '') return num
    try {
      return JSON.parse(value) as ConfigValue
    } catch {
      return value
    }
  }

  private normalizeKey(key: string): string {
    let normalized = key

    if (this.options.prefix) {
      if (!normalized.startsWith(this.options.prefix)) {
        return ''
      }
      normalized = normalized.slice(this.options.prefix.length)
    }

    if (this.options.lowerCaseKeys) {
      normalized = normalized.toLowerCase()
    }

    return normalized.replace(/_/g, '.')
  }

  protected async fetchConfig(): Promise<Record<string, ConfigValue>> {
    const flatResult: Record<string, ConfigValue> = {}

    if (this.options.filePath) {
      const resolvedPath = path.resolve(this.options.filePath)
      if (fs.existsSync(resolvedPath)) {
        const content = fs.readFileSync(resolvedPath, 'utf-8')
        const parsed = dotenv.parse(content)

        for (const [key, value] of Object.entries(parsed)) {
          const normalized = this.normalizeKey(key)
          if (normalized) {
            flatResult[normalized] = this.parseValue(value)
          }
        }
      }
    }

    if (this.options.useProcessEnv) {
      for (const [key, value] of Object.entries(process.env)) {
        const normalized = this.normalizeKey(key)
        if (normalized && value !== undefined) {
          flatResult[normalized] = this.parseValue(value)
        }
      }
    }

    return flatResult
  }

  protected async writeConfig(key: string, value: ConfigValue): Promise<void> {
    this.setNestedValue(this.data, key, value)

    if (this.options.filePath) {
      const resolvedPath = path.resolve(this.options.filePath)
      let lines: string[] = []

      if (fs.existsSync(resolvedPath)) {
        lines = fs.readFileSync(resolvedPath, 'utf-8').split('\n')
      }

      const envKey = key.toUpperCase().replace(/\./g, '_')
      if (this.options.prefix) {
        const fullKey = this.options.prefix + envKey
        const lineIndex = lines.findIndex((l) => l.startsWith(fullKey + '=') || l.startsWith(fullKey + ' ='))

        const serialized = typeof value === 'string' ? value : JSON.stringify(value)
        const newLine = `${fullKey}=${serialized}`

        if (lineIndex >= 0) {
          lines[lineIndex] = newLine
        } else {
          lines.push(newLine)
        }
      }

      fs.writeFileSync(resolvedPath, lines.join('\n'))
    }
  }

  protected async deleteConfig(key: string): Promise<void> {
    const parts = key.split('.')
    let target = this.data
    for (let i = 0; i < parts.length - 1; i++) {
      if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
        return
      }
      target = target[parts[i]] as ConfigData
    }
    delete target[parts[parts.length - 1]]

    if (this.options.filePath) {
      const resolvedPath = path.resolve(this.options.filePath)
      if (fs.existsSync(resolvedPath)) {
        const envKey = key.toUpperCase().replace(/\./g, '_')
        const fullKey = this.options.prefix ? this.options.prefix + envKey : envKey

        let lines = fs.readFileSync(resolvedPath, 'utf-8').split('\n')
        lines = lines.filter((l) => !l.startsWith(fullKey + '=') && !l.startsWith(fullKey + ' ='))
        fs.writeFileSync(resolvedPath, lines.join('\n'))
      }
    }
  }
}
