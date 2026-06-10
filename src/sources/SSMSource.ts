import { BaseConfigSource } from './ConfigSource'
import { ConfigData, ConfigValue } from '../types'

interface SSMSourceOptions {
  region?: string
  pathPrefix: string
  withDecryption?: boolean
  recursive?: boolean
  accessKeyId?: string
  secretAccessKey?: string
}

export class SSMSource extends BaseConfigSource {
  readonly type = 'ssm'
  readonly priority: number
  readonly name: string

  private options: SSMSourceOptions
  private client: any
  private data: ConfigData = {}
  private loaded = false

  constructor(name: string, priority: number, options: SSMSourceOptions) {
    super()
    this.name = name
    this.priority = priority
    this.options = {
      withDecryption: true,
      recursive: true,
      ...options,
    }
  }

  private async initClient(): Promise<void> {
    if (this.client) return

    const { SSMClient } = await import('@aws-sdk/client-ssm')
    const config: Record<string, unknown> = {
      region: this.options.region || process.env.AWS_REGION || 'us-east-1',
    }

    if (this.options.accessKeyId && this.options.secretAccessKey) {
      config.credentials = {
        accessKeyId: this.options.accessKeyId,
        secretAccessKey: this.options.secretAccessKey,
      }
    }

    this.client = new SSMClient(config)
  }

  private stripPrefix(path: string): string {
    const prefix = this.options.pathPrefix.replace(/\/$/, '')
    return path.startsWith(prefix) ? path.slice(prefix.length + 1) : path
  }

  private normalizeKey(key: string): string {
    return key.replace(/\//g, '.')
  }

  private denormalizeKey(key: string): string {
    return key.replace(/\./g, '/')
  }

  private convertValue(value: string): ConfigValue {
    if (value === 'true') return true
    if (value === 'false') return false
    if (value === 'null') return null
    if (value === 'undefined') return undefined as unknown as ConfigValue
    const num = Number(value)
    if (!isNaN(num) && value.trim() !== '') return num
    try {
      return JSON.parse(value) as ConfigValue
    } catch {
      return value
    }
  }

  private valueToString(value: ConfigValue): string {
    if (typeof value === 'string') return value
    return JSON.stringify(value)
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
      const { GetParametersByPathCommand } = await import('@aws-sdk/client-ssm')
      const params: Record<string, unknown> = {
        Path: this.options.pathPrefix,
        Recursive: this.options.recursive,
        WithDecryption: this.options.withDecryption,
      }

      let nextToken: string | undefined
      this.data = {}

      do {
        if (nextToken) {
          params.NextToken = nextToken
        }

        const command = new GetParametersByPathCommand(params as any)
        const response = await this.client.send(command)

        if (response.Parameters) {
          for (const param of response.Parameters) {
            const stripped = this.stripPrefix(param.Name)
            const key = this.normalizeKey(stripped)
            const value = this.convertValue(param.Value || '')
            this.setNestedValue(this.data, key, value)
          }
        }

        nextToken = response.NextToken
      } while (nextToken)

      this.loaded = true
      return this.flattenData(this.data as Record<string, unknown>)
    } catch (error) {
      throw new Error(`Failed to load from SSM: ${(error as Error).message}`)
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
      const { PutParameterCommand } = await import('@aws-sdk/client-ssm')
      const paramName = `${this.options.pathPrefix.replace(/\/$/, '')}/${this.denormalizeKey(key)}`

      const command = new PutParameterCommand({
        Name: paramName,
        Value: this.valueToString(value),
        Type: typeof value === 'string' && value.length > 1000 ? 'SecureString' : 'String',
        Overwrite: true,
      })

      await this.client.send(command)
      this.setNestedValue(this.data, key, value)
    } catch (error) {
      throw new Error(`Failed to write to SSM: ${(error as Error).message}`)
    }
  }

  async delete(key: string): Promise<void> {
    await this.initClient()

    try {
      const { DeleteParameterCommand } = await import('@aws-sdk/client-ssm')
      const paramName = `${this.options.pathPrefix.replace(/\/$/, '')}/${this.denormalizeKey(key)}`

      const command = new DeleteParameterCommand({ Name: paramName })
      await this.client.send(command)

      const parts = key.split('.')
      let target = this.data
      for (let i = 0; i < parts.length - 1; i++) {
        const part = parts[i]
        if (!target[part] || typeof target[part] !== 'object' || Array.isArray(target[part])) {
          return
        }
        target = target[part] as ConfigData
      }
      delete target[parts[parts.length - 1]]
    } catch (error) {
      throw new Error(`Failed to delete from SSM: ${(error as Error).message}`)
    }
  }

  async listKeys(): Promise<string[]> {
    if (!this.loaded) {
      await this.load()
    }
    return Object.keys(this.flattenData(this.data as Record<string, unknown>))
  }
}
