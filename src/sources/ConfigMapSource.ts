import { BaseConfigSource } from './ConfigSource'
import { ConfigData, ConfigValue } from '../types'
import * as yaml from 'js-yaml'

interface ConfigMapSourceOptions {
  namespace?: string
  name: string
  kubeconfig?: string
  context?: string
  dataKey?: string
}

export class ConfigMapSource extends BaseConfigSource {
  readonly type = 'configmap'
  readonly priority: number
  readonly name: string

  private options: ConfigMapSourceOptions
  private k8sApi: any
  private data: ConfigData = {}
  private loaded = false

  constructor(name: string, priority: number, options: ConfigMapSourceOptions) {
    super()
    this.name = name
    this.priority = priority
    this.options = {
      namespace: 'default',
      ...options,
    }
  }

  private async initClient(): Promise<void> {
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
      const response = await this.k8sApi.readNamespacedConfigMap(
        this.options.name,
        this.options.namespace
      )

      const cmData = response.body?.data || {}
      const parsed = this.parseConfigMapData(cmData)
      this.data = this.flattenData(parsed)
      this.loaded = true
      return this.data
    } catch (error) {
      if ((error as { response?: { statusCode: number } }).response?.statusCode === 404) {
        this.data = {}
        this.loaded = true
        return {}
      }
      throw new Error(`Failed to load from ConfigMap: ${(error as Error).message}`)
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

      this.setNestedValue(this.data, key, value)
    } catch (error) {
      throw new Error(`Failed to write to ConfigMap: ${(error as Error).message}`)
    }
  }

  async delete(key: string): Promise<void> {
    await this.initClient()

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

      const parts = key.split('.')
      let targetData = this.data
      for (let i = 0; i < parts.length - 1; i++) {
        const part = parts[i]
        if (!targetData[part] || typeof targetData[part] !== 'object' || Array.isArray(targetData[part])) {
          return
        }
        targetData = targetData[part] as ConfigData
      }
      delete targetData[parts[parts.length - 1]]
    } catch (error) {
      throw new Error(`Failed to delete from ConfigMap: ${(error as Error).message}`)
    }
  }

  async listKeys(): Promise<string[]> {
    if (!this.loaded) {
      await this.load()
    }
    return Object.keys(this.data)
  }
}
