import { BaseConnector, RetryPolicy } from './BaseConnector'
import { ConfigValue } from '../types'

interface SSMSourceOptions {
  region?: string
  pathPrefix: string
  withDecryption?: boolean
  recursive?: boolean
  accessKeyId?: string
  secretAccessKey?: string
  retryPolicy?: Partial<RetryPolicy>
  loadTimeoutMs?: number
}

export class SSMSource extends BaseConnector {
  readonly type = 'ssm'
  readonly priority: number
  readonly name: string

  protected readonly sourceName = 'SSM'

  private options: SSMSourceOptions

  constructor(name: string, priority: number, options: SSMSourceOptions) {
    super(options.retryPolicy, options.loadTimeoutMs)
    this.name = name
    this.priority = priority
    this.options = {
      withDecryption: true,
      recursive: true,
      ...options,
    }
  }

  protected async initClient(): Promise<void> {
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

  protected async fetchConfig(): Promise<Record<string, ConfigValue>> {
    try {
      const { GetParametersByPathCommand } = await import('@aws-sdk/client-ssm')
      const params: Record<string, unknown> = {
        Path: this.options.pathPrefix,
        Recursive: this.options.recursive,
        WithDecryption: this.options.withDecryption,
      }

      let nextToken: string | undefined
      const flatResult: Record<string, ConfigValue> = {}

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
            flatResult[key] = value
          }
        }

        nextToken = response.NextToken
      } while (nextToken)

      return flatResult
    } catch (error) {
      throw new Error(`Failed to load from SSM: ${(error as Error).message}`)
    }
  }

  protected async writeConfig(key: string, value: ConfigValue): Promise<void> {
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
    } catch (error) {
      throw new Error(`Failed to write to SSM: ${(error as Error).message}`)
    }
  }

  protected async deleteConfig(key: string): Promise<void> {
    try {
      const { DeleteParameterCommand } = await import('@aws-sdk/client-ssm')
      const paramName = `${this.options.pathPrefix.replace(/\/$/, '')}/${this.denormalizeKey(key)}`

      const command = new DeleteParameterCommand({ Name: paramName })
      await this.client.send(command)
    } catch (error) {
      throw new Error(`Failed to delete from SSM: ${(error as Error).message}`)
    }
  }
}
