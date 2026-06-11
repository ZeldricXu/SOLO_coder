import { z, ZodSchema, ZodTypeAny } from 'zod'
import { ConfigData, ConfigValue, ValidationError, ValidationReport } from '../types'

export interface SchemaFieldConfig {
  key: string
  type: 'string' | 'number' | 'boolean' | 'object' | 'array' | 'integer'
  required?: boolean
  default?: ConfigValue
  description?: string
  min?: number
  max?: number
  pattern?: string
  enum?: ConfigValue[]
  items?: SchemaFieldConfig
  properties?: SchemaFieldConfig[]
}

export interface SchemaConfig {
  $schema: string
  version: string
  fields: SchemaFieldConfig[]
}

export class SchemaValidator {
  private zodSchema: ZodSchema
  private fieldConfigs: Map<string, SchemaFieldConfig> = new Map()
  private validationCache = new Map<string, ValidationError[]>()
  private accessedKeys = new Set<string>()
  private config: SchemaConfig

  constructor(config: SchemaConfig) {
    this.config = config
    this.zodSchema = this.buildSchema(config)
    this.indexFields(config.fields)
  }

  private indexFields(fields: SchemaFieldConfig[], prefix = ''): void {
    for (const field of fields) {
      const fullKey = prefix ? `${prefix}.${field.key}` : field.key
      this.fieldConfigs.set(fullKey, field)

      if (field.type === 'object' && field.properties) {
        this.indexFields(field.properties, fullKey)
      }

      if (field.type === 'array' && field.items?.type === 'object' && field.items.properties) {
        this.indexFields(field.items.properties, `${fullKey}[]`)
      }
    }
  }

  private buildSchema(config: SchemaConfig): ZodSchema {
    const shape: Record<string, ZodTypeAny> = {}

    for (const field of config.fields) {
      shape[field.key] = this.buildFieldSchema(field)
    }

    return z.object(shape)
  }

  private buildFieldSchema(field: SchemaFieldConfig): ZodTypeAny {
    let schema: ZodTypeAny

    switch (field.type) {
      case 'string':
        schema = z.string()
        if (field.min !== undefined) schema = (schema as z.ZodString).min(field.min)
        if (field.max !== undefined) schema = (schema as z.ZodString).max(field.max)
        if (field.pattern) schema = (schema as z.ZodString).regex(new RegExp(field.pattern))
        break

      case 'number':
        schema = z.number()
        if (field.min !== undefined) schema = (schema as z.ZodNumber).min(field.min)
        if (field.max !== undefined) schema = (schema as z.ZodNumber).max(field.max)
        break

      case 'integer':
        schema = z.number().int()
        if (field.min !== undefined) schema = (schema as z.ZodNumber).min(field.min)
        if (field.max !== undefined) schema = (schema as z.ZodNumber).max(field.max)
        break

      case 'boolean':
        schema = z.boolean()
        break

      case 'object':
        if (field.properties) {
          const objShape: Record<string, ZodTypeAny> = {}
          for (const prop of field.properties) {
            objShape[prop.key] = this.buildFieldSchema(prop)
          }
          schema = z.object(objShape)
        } else {
          schema = z.record(z.unknown())
        }
        break

      case 'array':
        if (field.items) {
          schema = z.array(this.buildFieldSchema(field.items))
        } else {
          schema = z.array(z.unknown())
        }
        if (field.min !== undefined) schema = (schema as z.ZodArray<ZodTypeAny>).min(field.min)
        if (field.max !== undefined) schema = (schema as z.ZodArray<ZodTypeAny>).max(field.max)
        break

      default:
        schema = z.unknown()
    }

    if (field.enum && field.enum.length > 0) {
      schema = z.enum(field.enum as [string, ...string[]])
    }

    if (!field.required) {
      schema = schema.optional()
      if (field.default !== undefined) {
        schema = schema.default(field.default as never)
      }
    }

    return schema
  }

  private unflattenData(data: ConfigData): ConfigData {
    const result: ConfigData = {}
    for (const [key, value] of Object.entries(data)) {
      const parts = key.split('.')
      let current = result
      for (let i = 0; i < parts.length - 1; i++) {
        if (!(parts[i] in current) || typeof current[parts[i]] !== 'object' || current[parts[i]] === null || Array.isArray(current[parts[i]])) {
          current[parts[i]] = {}
        }
        current = current[parts[i]] as ConfigData
      }
      current[parts[parts.length - 1]] = value
    }
    return result
  }

  private resolveExpected(issue: z.ZodIssue): string {
    const path = issue.path.join('.')
    const fieldConfig = this.fieldConfigs.get(path)

    let expected = fieldConfig?.type || 'unknown'
    if (fieldConfig?.enum) {
      expected = `one of [${fieldConfig.enum.join(', ')}]`
    } else if (fieldConfig?.pattern) {
      expected = `string matching /${fieldConfig.pattern}/`
    } else if (fieldConfig?.min !== undefined || fieldConfig?.max !== undefined) {
      const constraints: string[] = []
      if (fieldConfig.min !== undefined) constraints.push(`min=${fieldConfig.min}`)
      if (fieldConfig.max !== undefined) constraints.push(`max=${fieldConfig.max}`)
      expected = `${fieldConfig.type} (${constraints.join(', ')})`
    }
    return expected
  }

  validate(data: ConfigData, environment: string): ValidationReport {
    const errors: ValidationError[] = []

    const nestedData = this.unflattenData(data)
    const result = this.zodSchema.safeParse(nestedData)

    if (result.success) {
      const allSchemaKeys = this.collectSchemaKeys(this.config.fields)
      for (const key of allSchemaKeys) {
        if (!this.validationCache.has(key)) {
          this.validationCache.set(key, [])
        }
      }
      return {
        environment,
        valid: true,
        errors: [],
        timestamp: Date.now(),
      }
    }

    for (const issue of result.error.issues) {
      const path = issue.path.join('.')
      const fieldConfig = this.fieldConfigs.get(path)

      let expected = fieldConfig?.type || 'unknown'
      if (fieldConfig?.enum) {
        expected = `one of [${fieldConfig.enum.join(', ')}]`
      } else if (fieldConfig?.pattern) {
        expected = `string matching /${fieldConfig.pattern}/`
      } else if (fieldConfig?.min !== undefined || fieldConfig?.max !== undefined) {
        const constraints: string[] = []
        if (fieldConfig.min !== undefined) constraints.push(`min=${fieldConfig.min}`)
        if (fieldConfig.max !== undefined) constraints.push(`max=${fieldConfig.max}`)
        expected = `${fieldConfig.type} (${constraints.join(', ')})`
      }

      errors.push({
        key: path,
        environment,
        message: issue.message,
        expected,
        actual: this.getActualValue(nestedData, issue.path),
        schemaPath: path,
      })
    }

    for (const err of errors) {
      const existing = this.validationCache.get(err.key) || []
      if (!existing.some((e) => e.message === err.message)) {
        existing.push(err)
      }
      this.validationCache.set(err.key, existing)
    }
    const allSchemaKeys = this.collectSchemaKeys(this.config.fields)
    for (const key of allSchemaKeys) {
      if (!this.validationCache.has(key)) {
        this.validationCache.set(key, [])
      }
    }

    return {
      environment,
      valid: false,
      errors,
      timestamp: Date.now(),
    }
  }

  validatePath(data: ConfigData, environment: string, path: string): ValidationReport {
    const nestedData = this.unflattenData(data)
    const pathParts = path.split('.')
    const errors: ValidationError[] = []

    const cacheKey = path
    const cached = this.validationCache.get(cacheKey)
    if (cached !== undefined) {
      return {
        valid: cached.length === 0,
        environment,
        errors: cached,
        totalErrors: cached.length,
        validatedAt: Date.now(),
        cached: true,
        timestamp: Date.now(),
      }
    }

    let current: any = nestedData
    for (const part of pathParts) {
      if (current === null || current === undefined) break
      current = current[part]
    }

    const partialConfig: any = {}
    let pc = partialConfig
    for (let i = 0; i < pathParts.length; i++) {
      if (i === pathParts.length - 1) {
        pc[pathParts[i]] = current
      } else {
        pc[pathParts[i]] = {}
        pc = pc[pathParts[i]]
      }
    }

    const result = this.zodSchema.safeParse(partialConfig)

    if (!result.success) {
      for (const issue of result.error.issues) {
        const issuePath = issue.path.join('.')
        if (issuePath === path || issuePath.startsWith(path + '.')) {
          const expected = this.resolveExpected(issue)
          errors.push({
            key: issuePath,
            environment,
            message: issue.message,
            expected,
            actual: this.getActualValue(nestedData, issue.path),
            schemaPath: issuePath,
          })
        }
      }
    }

    this.validationCache.set(cacheKey, [...errors])

    return {
      valid: errors.length === 0,
      environment,
      errors,
      totalErrors: errors.length,
      validatedAt: Date.now(),
      cached: false,
      timestamp: Date.now(),
    }
  }

  markAccessed(path: string): void {
    this.accessedKeys.add(path)
    const parts = path.split('.')
    for (let i = 1; i < parts.length; i++) {
      this.accessedKeys.add(parts.slice(0, i).join('.'))
    }
  }

  clearAccessTracker(): void {
    this.accessedKeys.clear()
  }

  validateAccessed(data: ConfigData, environment: string): ValidationReport {
    if (this.accessedKeys.size === 0) {
      return this.validate(data, environment)
    }

    const allErrors: ValidationError[] = []
    for (const path of this.accessedKeys) {
      const report = this.validatePath(data, environment, path)
      allErrors.push(...report.errors)
    }

    return {
      valid: allErrors.length === 0,
      environment,
      errors: allErrors,
      totalErrors: allErrors.length,
      validatedAt: Date.now(),
      accessedKeyCount: this.accessedKeys.size,
      timestamp: Date.now(),
    }
  }

  startBackgroundValidation(
    data: ConfigData,
    environment: string,
    yieldIntervalMs = 10,
  ): Promise<ValidationReport> {
    const nestedData = this.unflattenData(data)
    const allKeys = this.collectSchemaKeys(this.config.fields)

    let currentIndex = 0

    return new Promise((resolve) => {
      const processBatch = () => {
        const startTime = Date.now()
        const errors: ValidationError[] = []

        while (currentIndex < allKeys.length) {
          const key = allKeys[currentIndex]
          currentIndex++

          if (this.validationCache.has(key)) {
            const cached = this.validationCache.get(key)!
            errors.push(...cached)
            continue
          }

          const result = this.validatePath(data, environment, key)
          errors.push(...result.errors)

          if (Date.now() - startTime > yieldIntervalMs) {
            setImmediate(processBatch)
            return
          }
        }

        resolve({
          valid: errors.length === 0,
          environment,
          errors,
          totalErrors: errors.length,
          validatedAt: Date.now(),
          background: true,
          timestamp: Date.now(),
        })
      }

      setImmediate(processBatch)
    })
  }

  private collectSchemaKeys(
    fields: SchemaFieldConfig[],
    prefix = '',
  ): string[] {
    const keys: string[] = []
    for (const field of fields) {
      const fullKey = prefix ? `${prefix}.${field.key}` : field.key
      keys.push(fullKey)
      if (field.type === 'object' && field.properties) {
        keys.push(...this.collectSchemaKeys(field.properties, fullKey))
      }
    }
    return keys
  }

  validateValue(key: string, value: ConfigValue, environment: string): ValidationError | null {
    const fieldConfig = this.fieldConfigs.get(key)
    if (!fieldConfig) {
      return {
        key,
        environment,
        message: `No schema defined for key: ${key}`,
        expected: 'defined in schema',
        actual: String(value),
        schemaPath: key,
      }
    }

    const fieldSchema = this.buildFieldSchema(fieldConfig)
    const result = fieldSchema.safeParse(value)

    if (result.success) return null

    const issue = result.error.issues[0]
    let expected: string = fieldConfig.type
    if (fieldConfig.enum) {
      expected = `one of [${fieldConfig.enum.join(', ')}]`
    } else if (fieldConfig.pattern) {
      expected = `string matching /${fieldConfig.pattern}/`
    }

    return {
      key,
      environment,
      message: issue.message,
      expected,
      actual: String(value),
      schemaPath: key,
    }
  }

  private getActualValue(data: ConfigData, path: (string | number)[]): string {
    let current: ConfigValue = data

    for (const part of path) {
      if (current === null || current === undefined) break
      if (typeof current === 'object' && !Array.isArray(current)) {
        current = (current as ConfigData)[String(part)]
      } else if (Array.isArray(current) && typeof part === 'number') {
        current = current[part]
      } else {
        break
      }
    }

    if (current === undefined) return 'undefined'
    if (current === null) return 'null'
    if (typeof current === 'object') return JSON.stringify(current)
    return String(current)
  }

  getFieldConfig(key: string): SchemaFieldConfig | undefined {
    return this.fieldConfigs.get(key)
  }

  getAllFieldKeys(): string[] {
    return Array.from(this.fieldConfigs.keys())
  }

  clearCache(): void {
    this.validationCache.clear()
  }

  getCacheStats(): { size: number } {
    return { size: this.validationCache.size }
  }

  static loadFromFile(filePath: string): SchemaValidator {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const config = require(filePath) as SchemaConfig
    return new SchemaValidator(config)
  }

  static fromJSON(json: string): SchemaValidator {
    const config = JSON.parse(json) as SchemaConfig
    return new SchemaValidator(config)
  }
}
