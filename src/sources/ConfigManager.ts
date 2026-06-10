import { ConfigSource, BaseConfigSource } from './ConfigSource'
import { VaultSource } from './VaultSource'
import { SSMSource } from './SSMSource'
import { ConfigMapSource } from './ConfigMapSource'
import { EnvSource } from './EnvSource'
import { ConfigData, ConfigValue, EnvironmentConfig, ConfigSourceConfig } from '../types'

export class Environment {
  readonly name: string
  readonly sources: ConfigSource[]
  readonly labels?: Record<string, string>

  constructor(name: string, sources: ConfigSource[], labels?: Record<string, string>) {
    this.name = name
    this.sources = sources.sort((a, b) => b.priority - a.priority)
    this.labels = labels
  }

  async loadAll(): Promise<ConfigData> {
    const merged: ConfigData = {}

    for (const source of this.sources) {
      try {
        const data = await source.load()
        this.deepMerge(merged, data)
      } catch (error) {
        console.warn(`Failed to load from source ${source.name} (${source.type}): ${(error as Error).message}`)
      }
    }

    return merged
  }

  async get(key: string): Promise<ConfigValue | undefined> {
    for (const source of this.sources) {
      try {
        const value = await source.get(key)
        if (value !== undefined) {
          return value
        }
      } catch (error) {
        console.warn(`Failed to get from source ${source.name}: ${(error as Error).message}`)
      }
    }
    return undefined
  }

  async set(key: string, value: ConfigValue, sourceType?: string): Promise<void> {
    const source = sourceType
      ? this.sources.find((s) => s.type === sourceType)
      : this.sources[0]

    if (!source) {
      throw new Error(`No suitable source found for type: ${sourceType || 'default'}`)
    }

    await source.set(key, value)
  }

  async delete(key: string, sourceType?: string): Promise<void> {
    const source = sourceType
      ? this.sources.find((s) => s.type === sourceType)
      : this.sources[0]

    if (!source) {
      throw new Error(`No suitable source found`)
    }

    await source.delete(key)
  }

  async listKeys(): Promise<string[]> {
    const keys = new Set<string>()

    for (const source of this.sources) {
      try {
        const sourceKeys = await source.listKeys()
        sourceKeys.forEach((k) => keys.add(k))
      } catch (error) {
        console.warn(`Failed to list keys from source ${source.name}: ${(error as Error).message}`)
      }
    }

    return Array.from(keys).sort()
  }

  private deepMerge(target: ConfigData, source: ConfigData): void {
    for (const key of Object.keys(source)) {
      const targetValue = target[key]
      const sourceValue = source[key]

      if (
        sourceValue !== null &&
        typeof sourceValue === 'object' &&
        !Array.isArray(sourceValue) &&
        targetValue !== null &&
        typeof targetValue === 'object' &&
        !Array.isArray(targetValue)
      ) {
        this.deepMerge(targetValue as ConfigData, sourceValue as ConfigData)
      } else {
        target[key] = sourceValue
      }
    }
  }

  getSourceByType(type: string): ConfigSource | undefined {
    return this.sources.find((s) => s.type === type)
  }

  getHighestPrioritySource(): ConfigSource {
    return this.sources[0]
  }
}

export class ConfigManager {
  private environments: Map<string, Environment> = new Map()

  addEnvironment(env: EnvironmentConfig): Environment {
    const sources = env.sources.map((config) => this.createSource(config))
    const environment = new Environment(env.name, sources, env.labels)
    this.environments.set(env.name, environment)
    return environment
  }

  private createSource(config: ConfigSourceConfig): ConfigSource {
    const name = `${config.type}-${config.priority}`

    switch (config.type) {
      case 'env':
        return new EnvSource(name, config.priority, config.options as Record<string, unknown>)
      case 'vault':
        return new VaultSource(name, config.priority, config.options as any)
      case 'ssm':
        return new SSMSource(name, config.priority, config.options as any)
      case 'configmap':
        return new ConfigMapSource(name, config.priority, config.options as any)
      case 'default':
        return new DefaultSource(name, config.priority, config.options as { defaults: ConfigData })
      default:
        throw new Error(`Unsupported source type: ${config.type}`)
    }
  }

  getEnvironment(name: string): Environment | undefined {
    return this.environments.get(name)
  }

  listEnvironments(): string[] {
    return Array.from(this.environments.keys()).sort()
  }

  async loadAll(): Promise<Map<string, ConfigData>> {
    const result = new Map<string, ConfigData>()

    for (const [name, env] of this.environments) {
      result.set(name, await env.loadAll())
    }

    return result
  }
}

class DefaultSource extends BaseConfigSource {
  readonly type = 'default'
  readonly priority: number
  readonly name: string
  private data: ConfigData

  constructor(name: string, priority: number, options: { defaults: ConfigData }) {
    super()
    this.name = name
    this.priority = priority
    this.data = options.defaults || {}
  }

  async load(): Promise<ConfigData> {
    return { ...this.data }
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
    return Object.keys(this.data)
  }
}
