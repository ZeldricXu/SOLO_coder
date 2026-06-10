import { BaseConfigSource } from './ConfigSource'
import { ConfigData, ConfigValue } from '../types'

interface VaultSourceOptions {
  endpoint?: string
  token?: string
  path: string
  namespace?: string
  roleId?: string
  secretId?: string
}

export class VaultSource extends BaseConfigSource {
  readonly type = 'vault'
  readonly priority: number
  readonly name: string

  private options: VaultSourceOptions
  private client: any
  private data: ConfigData = {}
  private loaded = false

  constructor(name: string, priority: number, options: VaultSourceOptions) {
    super()
    this.name = name
    this.priority = priority
    this.options = options
  }

  private async initClient(): Promise<void> {
    if (this.client) return

    const vault = await import('node-vault')
    const clientOptions: Record<string, unknown> = {
      apiVersion: 'v1',
      endpoint: this.options.endpoint || process.env.VAULT_ADDR || 'http://127.0.0.1:8200',
    }

    if (this.options.namespace) {
      clientOptions.namespace = this.options.namespace
    }

    this.client = vault.default(clientOptions as any) as any

    if (this.options.roleId && this.options.secretId) {
      const result = await (this.client as any).approleLogin({
        role_id: this.options.roleId,
        secret_id: this.options.secretId,
      })
      ;(this.client as any).token = result.auth.client_token
    } else {
      (this.client as any).token = this.options.token || process.env.VAULT_TOKEN
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
    await this.initClient()

    try {
      const result = await this.client.read(this.options.path)
      const rawData = result?.data?.data || result?.data || {}
      this.data = rawData as ConfigData
      this.loaded = true
      return this.flattenData(rawData)
    } catch (error) {
      throw new Error(`Failed to load from Vault: ${(error as Error).message}`)
    }
  }

  async get(key: string): Promise<ConfigValue | undefined> {
    if (!this.loaded) {
      await this.load()
    }
    return this.getNestedValue(this.data, key)
  }

  async set(key: string, value: ConfigValue): Promise<void> {
    await this.initClient()

    try {
      const current = await this.client.read(this.options.path)
      const existingData = current?.data?.data || current?.data || {}

      const parts = key.split('.')
      let target = existingData
      for (let i = 0; i < parts.length - 1; i++) {
        if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
          target[parts[i]] = {}
        }
        target = target[parts[i]] as Record<string, unknown>
      }
      target[parts[parts.length - 1]] = value

      await this.client.write(this.options.path, { data: existingData })

      this.setNestedValue(this.data, key, value)
    } catch (error) {
      throw new Error(`Failed to write to Vault: ${(error as Error).message}`)
    }
  }

  async delete(key: string): Promise<void> {
    await this.initClient()

    try {
      const current = await this.client.read(this.options.path)
      const existingData = current?.data?.data || current?.data || {}

      const parts = key.split('.')
      let target = existingData
      for (let i = 0; i < parts.length - 1; i++) {
        if (!target[parts[i]] || typeof target[parts[i]] !== 'object') {
          return
        }
        target = target[parts[i]] as Record<string, unknown>
      }
      delete target[parts[parts.length - 1]]

      await this.client.write(this.options.path, { data: existingData })

      const data = this.data
      let targetData = data
      for (let i = 0; i < parts.length - 1; i++) {
        const part = parts[i]
        if (!targetData[part] || typeof targetData[part] !== 'object' || Array.isArray(targetData[part])) {
          return
        }
        targetData = targetData[part] as ConfigData
      }
      delete targetData[parts[parts.length - 1]]
    } catch (error) {
      throw new Error(`Failed to delete from Vault: ${(error as Error).message}`)
    }
  }

  async listKeys(): Promise<string[]> {
    if (!this.loaded) {
      await this.load()
    }
    return Object.keys(this.flattenData(this.data as Record<string, unknown>))
  }
}
