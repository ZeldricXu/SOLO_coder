import { ConfigData, ConfigValue } from '../types'

export interface ConfigSource {
  readonly type: string
  readonly priority: number
  readonly name: string

  load(): Promise<ConfigData>
  get(key: string): Promise<ConfigValue | undefined>
  set(key: string, value: ConfigValue): Promise<void>
  delete(key: string): Promise<void>
  listKeys(): Promise<string[]>
  exists(key: string): Promise<boolean>
}

export abstract class BaseConfigSource implements ConfigSource {
  abstract readonly type: string
  abstract readonly priority: number
  abstract readonly name: string

  abstract load(): Promise<ConfigData>
  abstract get(key: string): Promise<ConfigValue | undefined>
  abstract set(key: string, value: ConfigValue): Promise<void>
  abstract delete(key: string): Promise<void>
  abstract listKeys(): Promise<string[]>

  async exists(key: string): Promise<boolean> {
    const value = await this.get(key)
    return value !== undefined
  }

  protected getNestedValue(data: ConfigData, path: string): ConfigValue | undefined {
    const parts = path.split('.')
    let current: ConfigValue = data

    for (const part of parts) {
      if (current === null || current === undefined) {
        return undefined
      }
      if (typeof current === 'object' && !Array.isArray(current)) {
        current = (current as ConfigData)[part]
      } else {
        return undefined
      }
    }

    return current
  }

  protected setNestedValue(data: ConfigData, path: string, value: ConfigValue): void {
    const parts = path.split('.')
    let current: ConfigData = data

    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i]
      if (!(part in current) || typeof current[part] !== 'object' || current[part] === null || Array.isArray(current[part])) {
        current[part] = {}
      }
      current = current[part] as ConfigData
    }

    current[parts[parts.length - 1]] = value
  }
}
