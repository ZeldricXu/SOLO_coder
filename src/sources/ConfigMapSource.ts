import { BaseConnector, RetryPolicy } from './BaseConnector'
import { ConfigData, ConfigValue } from '../types'
import * as yaml from 'js-yaml'

interface ConfigMapSourceOptions {
  namespace?: string
  name: string
  kubeconfig?: string
  context?: string
  dataKey?: string
  retryPolicy?: Partial<RetryPolicy>
  loadTimeoutMs?: number
}

export class ConfigMapSource extends BaseConnector {
  readonly type = 'configmap'
  readonly priority: number
  readonly name: string

  protected readonly sourceName = 'ConfigMap'

  private options: ConfigMapSourceOptions
  protected k8sApi: any = null

  constructor(name: string, priority: number, options: ConfigMapSourceOptions) {
    super(options.retryPolicy, options.loadTimeoutMs)
    this.name = name
    this.priority = priority
    this.options = {
      namespace: 'default',
      ...options,
    }
  }

  protected async initClient(): Promise<void> {
    if (this.k8sApi) return

    const k8s = await import('@kubernetes/client-node')
    const kc = new k8s.KubeConfig()

    if (this.options.kubeconfig) {
      kc.loadFromFile(this.options.kubeconfig)
    } else {
      kc.loadFromDefault()
    }

    if (this.options.context) {
      kc.setCurrentContext(this.options.context)
    }

    this.k8sApi = kc.makeApiClient(k8s.CoreV1Api)
  }

  private parseConfigMapData(cmData: Record<string, string>): ConfigData {
    if (this.options.dataKey) {
      const value = cmData[this.options.dataKey]
      if (!value) return {}

      if (this.options.dataKey.endsWith('.json')) {
        return JSON.parse(value) as ConfigData
      } else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
        return yaml.load(value) as ConfigData
      } else if (this.options.dataKey.endsWith('.env')) {
        return this.parseEnvContent(value)
      }
    }

    const result: ConfigData = {}
    for (const [key, value] of Object.entries(cmData)) {
      result[key] = this.parseValue(value)
    }
    return result
  }

  private parseEnvContent(content: string): ConfigData {
    const result: ConfigData = {}
    const lines = content.split('\n')

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue

      const eqIndex = trimmed.indexOf('=')
      const key = trimmed.slice(0, eqIndex).trim()
      const value = trimmed.slice(eqIndex + 1).trim().replace(/^["']|["']$/g, '')
      result[key] = this.parseValue(value)
    }

    return result
  }

  private parseValue(value: string): ConfigValue {
    if (value === 'true') return true
    if (value === 'false') return false
    if (value === 'null') return null
    if (value === '') return ''
    const num = Number(value)
    if (!isNaN(num) && value.trim() !== '') return num
    return value
  }

  private serializeValue(value: ConfigValue): string {
    if (typeof value === 'string') return value
    return JSON.stringify(value)
  }

  protected async fetchConfig(): Promise<Record<string, ConfigValue>> {
    try {
      const response = await this.k8sApi.readNamespacedConfigMap(
        this.options.name,
        this.options.namespace
      )

      const cmData = response.body?.data || {}
      const parsed = this.parseConfigMapData(cmData)
      return this.flattenData(parsed as Record<string, unknown>) as Record<string, ConfigValue>
    } catch (error) {
      if ((error as { response?: { statusCode: number } }).response?.statusCode === 404) {
        return {}
      }
      throw new Error(`Failed to load from ConfigMap: ${(error as Error).message}`)
    }
  }

  protected async writeConfig(key: string, value: ConfigValue): Promise<void> {
    try {
      const response = await this.k8sApi.readNamespacedConfigMap(
        this.options.name,
        this.options.namespace
      )

      const currentData = response.body?.data || {}

      if (this.options.dataKey) {
        const existingContent = currentData[this.options.dataKey] || ''
        let parsed: ConfigData

        if (this.options.dataKey.endsWith('.json')) {
          parsed = existingContent ? (JSON.parse(existingContent) as ConfigData) : {}
        } else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
          parsed = existingContent ? (yaml.load(existingContent) as ConfigData) : {}
        } else if (this.options.dataKey.endsWith('.env')) {
          parsed = this.parseEnvContent(existingContent)
        } else {
          parsed = existingContent ? (JSON.parse(existingContent) as ConfigData) : {}
        }

        this.setNestedValue(parsed, key, value)

        if (this.options.dataKey.endsWith('.json')) {
          currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2)
        } else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
          currentData[this.options.dataKey] = yaml.dump(parsed)
        } else if (this.options.dataKey.endsWith('.env')) {
          const flat = this.flattenData(parsed)
          currentData[this.options.dataKey] = Object.entries(flat)
            .map(([k, v]) => `${k}=${this.serializeValue(v)}`)
            .join('\n')
        } else {
          currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2)
        }
      } else {
        currentData[key] = this.serializeValue(value)
      }

      await this.k8sApi.patchNamespacedConfigMap(
        this.options.name,
        this.options.namespace,
        { data: currentData },
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        { headers: { 'Content-Type': 'application/merge-patch+json' } }
      )
    } catch (error) {
      if ((error as { response?: { statusCode: number } }).response?.statusCode === 404) {
        throw new Error(`Failed to write to ConfigMap: ConfigMap not found`)
      }
      throw new Error(`Failed to write to ConfigMap: ${(error as Error).message}`)
    }
  }

  protected async deleteConfig(key: string): Promise<void> {
    try {
      const response = await this.k8sApi.readNamespacedConfigMap(
        this.options.name,
        this.options.namespace
      )

      const currentData = response.body?.data || {}

      if (this.options.dataKey) {
        const existingContent = currentData[this.options.dataKey] || ''
        let parsed: ConfigData

        if (this.options.dataKey.endsWith('.json')) {
          parsed = existingContent ? (JSON.parse(existingContent) as ConfigData) : {}
        } else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
          parsed = existingContent ? (yaml.load(existingContent) as ConfigData) : {}
        } else {
          parsed = existingContent ? (JSON.parse(existingContent) as ConfigData) : {}
        }

        const parts = key.split('.')
        let target = parsed
        for (let i = 0; i < parts.length - 1; i++) {
          if (!target[parts[i]] || typeof target[parts[i]] !== 'object' || Array.isArray(target[parts[i]])) {
            return
          }
          target = target[parts[i]] as ConfigData
        }
        delete target[parts[parts.length - 1]]

        if (this.options.dataKey.endsWith('.json')) {
          currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2)
        } else if (this.options.dataKey.endsWith('.yaml') || this.options.dataKey.endsWith('.yml')) {
          currentData[this.options.dataKey] = yaml.dump(parsed)
        } else {
          currentData[this.options.dataKey] = JSON.stringify(parsed, null, 2)
        }
      } else {
        delete currentData[key]
      }

      await this.k8sApi.patchNamespacedConfigMap(
        this.options.name,
        this.options.namespace,
        { data: currentData },
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        { headers: { 'Content-Type': 'application/merge-patch+json' } }
      )
    } catch (error) {
      if ((error as { response?: { statusCode: number } }).response?.statusCode === 404) {
        throw new Error(`Failed to delete from ConfigMap: ConfigMap not found`)
      }
      throw new Error(`Failed to delete from ConfigMap: ${(error as Error).message}`)
    }
  }
}
