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

  constructor(config: SchemaConfig) {
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

  validate(data: ConfigData, environment: string): ValidationReport {
    const errors: ValidationError[] = []

    const result = this.zodSchema.safeParse(data)

    if (result.success) {
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
        actual: this.getActualValue(data, issue.path),
        schemaPath: path,
      })
    }

    return {
      environment,
      valid: false,
      errors,
      timestamp: Date.now(),
    }
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
