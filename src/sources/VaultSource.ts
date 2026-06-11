import { BaseConnector, RetryPolicy } from './BaseConnector'
import { ConfigValue } from '../types'

interface VaultSourceOptions {
  endpoint?: string
  token?: string
  path: string
  namespace?: string
  roleId?: string
  secretId?: string
  retryPolicy?: Partial<RetryPolicy>
  loadTimeoutMs?: number
}

export class VaultSource extends BaseConnector {
  readonly type = 'vault'
  readonly priority: number
  readonly name: string

  protected readonly sourceName = 'Vault'

  private options: VaultSourceOptions

  constructor(name: string, priority: number, options: VaultSourceOptions) {
    super(options.retryPolicy, options.loadTimeoutMs)
    this.name = name
    this.priority = priority
    this.options = options
  }

  protected async initClient(): Promise<void> {
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

  protected async fetchConfig(): Promise<Record<string, ConfigValue>> {
    try {
      const result = await this.client.read(this.options.path)
      const rawData = result?.data?.data || result?.data || {}
      return this.flattenData(rawData) as Record<string, ConfigValue>
    } catch (error) {
      throw new Error(`Failed to load from Vault: ${(error as Error).message}`)
    }
  }

  protected async writeConfig(key: string, value: ConfigValue): Promise<void> {
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
    } catch (error) {
      throw new Error(`Failed to write to Vault: ${(error as Error).message}`)
    }
  }

  protected async deleteConfig(key: string): Promise<void> {
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
    } catch (error) {
      throw new Error(`Failed to delete from Vault: ${(error as Error).message}`)
    }
  }
}
