import { BaseConfigSource } from './ConfigSource'
import { ConfigData, ConfigValue } from '../types'
import * as fs from 'fs'
import * as path from 'path'
import * as dotenv from 'dotenv'

interface EnvSourceOptions {
  filePath?: string
  useProcessEnv?: boolean
  prefix?: string
  lowerCaseKeys?: boolean
}

export class EnvSource extends BaseConfigSource {
  readonly type = 'env'
  readonly priority: number
  readonly name: string

  private options: EnvSourceOptions
  private data: ConfigData = {}
  private loaded = false

  constructor(name: string, priority: number, options: EnvSourceOptions = {}) {
    super()
    this.name = name
    this.priority = priority
    this.options = {
      useProcessEnv: true,
      lowerCaseKeys: true,
      ...options,
    }
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
    this.data = {}

    if (this.options.filePath) {
      const resolvedPath = path.resolve(this.options.filePath)
      if (fs.existsSync(resolvedPath)) {
        const content = fs.readFileSync(resolvedPath, 'utf-8')
        const parsed = dotenv.parse(content)

        for (const [key, value] of Object.entries(parsed)) {
          const normalized = this.normalizeKey(key)
          if (normalized) {
            this.setNestedValue(this.data, normalized, this.parseValue(value))
          }
        }
      }
    }

    if (this.options.useProcessEnv) {
      for (const [key, value] of Object.entries(process.env)) {
        const normalized = this.normalizeKey(key)
        if (normalized && value !== undefined) {
          this.setNestedValue(this.data, normalized, this.parseValue(value))
        }
      }
    }

    this.loaded = true
    return this.flattenData(this.data as Record<string, unknown>)
  }

  async get(key: string): Promise<ConfigValue | undefined> {
    if (!this.loaded) {
      await this.load()
    }
    return this.getNestedValue(this.data, key)
  }

  async set(key: string, value: ConfigValue): Promise<void> {
    if (!this.loaded) {
      await this.load()
    }

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

  async delete(key: string): Promise<void> {
    if (!this.loaded) {
      await this.load()
    }

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

  async listKeys(): Promise<string[]> {
    if (!this.loaded) {
      await this.load()
    }
    return Object.keys(this.flattenData(this.data as Record<string, unknown>))
  }
}
