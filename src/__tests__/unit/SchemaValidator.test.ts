import { describe, it, expect } from 'vitest'
import { SchemaValidator, SchemaConfig } from '../../schemas/SchemaValidator'
import {
  createDevConfig,
  createStagingConfig,
  createProdConfig,
  createEmptyConfig,
  createSchemaConfig,
} from '../factories/TestDataFactory'

describe('SchemaValidator', () => {
  let validator: SchemaValidator
  let schemaConfig: SchemaConfig

  beforeEach(() => {
    schemaConfig = createSchemaConfig()
    validator = new SchemaValidator(schemaConfig)
  })

  describe('normal path - valid configs', () => {
    it('should return empty violations for valid dev config', () => {
      const report = validator.validate(createDevConfig(), 'dev')
      expect(report.valid).toBe(true)
      expect(report.errors).toEqual([])
      expect(report.environment).toBe('dev')
    })

    it('should return empty violations for valid staging config', () => {
      const report = validator.validate(createStagingConfig(), 'staging')
      expect(report.valid).toBe(true)
      expect(report.errors).toEqual([])
    })

    it('should return empty violations for valid prod config', () => {
      const report = validator.validate(createProdConfig(), 'prod')
      expect(report.valid).toBe(true)
      expect(report.errors).toEqual([])
    })

    it('should accept config with optional fields omitted', () => {
      const minimalConfig = {
        app: { name: 'my-service', port: 3000 },
        db: { host: 'localhost', port: 5432, name: 'mydb', password: 'secretpass' },
        logLevel: 'info',
      }
      const report = validator.validate(minimalConfig, 'minimal')
      expect(report.valid).toBe(true)
    })

    it('should apply defaults for optional fields with defaults', () => {
      const config = {
        app: { name: 'svc', port: 8080 },
        db: { host: 'db.local', port: 5432, name: 'db', password: 'longpassword' },
        logLevel: 'debug',
      }
      const report = validator.validate(config, 'default-test')
      expect(report.valid).toBe(true)
    })
  })

  describe('normal path - invalid configs', () => {
    it('should detect type mismatch: string where number expected', () => {
      const invalidConfig = {
        ...createDevConfig(),
        app: { name: 'my-service', port: 'not-a-number', debug: true },
      }
      const report = validator.validate(invalidConfig, 'type-mismatch')
      expect(report.valid).toBe(false)
      expect(report.errors.length).toBeGreaterThan(0)

      const portError = report.errors.find((e) => e.key === 'app.port')
      expect(portError).toBeDefined()
      expect(portError!.expected).toContain('integer')
      expect(portError!.actual).toBe('not-a-number')
    })

    it('should detect missing required field', () => {
      const invalidConfig = {
        app: { port: 3000, debug: true },
        db: { host: 'localhost', port: 5432, name: 'mydb', password: 'longpass' },
        logLevel: 'info',
      }
      const report = validator.validate(invalidConfig, 'missing-required')
      expect(report.valid).toBe(false)

      const nameError = report.errors.find((e) => e.key === 'app.name')
      expect(nameError).toBeDefined()
      expect(nameError!.message).toContain('Required')
    })

    it('should detect enum violation', () => {
      const invalidConfig = {
        ...createDevConfig(),
        logLevel: 'verbose',
      }
      const report = validator.validate(invalidConfig, 'enum-violation')
      expect(report.valid).toBe(false)

      const logError = report.errors.find((e) => e.key === 'logLevel')
      expect(logError).toBeDefined()
    })

    it('should detect value below minimum constraint', () => {
      const invalidConfig = {
        ...createDevConfig(),
        app: { name: 'my-service', port: 0, debug: true },
      }
      const report = validator.validate(invalidConfig, 'min-violation')
      expect(report.valid).toBe(false)

      const portError = report.errors.find((e) => e.key === 'app.port')
      expect(portError).toBeDefined()
      expect(portError!.expected).toContain('min=1')
    })

    it('should detect value above maximum constraint', () => {
      const invalidConfig = {
        ...createDevConfig(),
        app: { name: 'my-service', port: 99999, debug: true },
      }
      const report = validator.validate(invalidConfig, 'max-violation')
      expect(report.valid).toBe(false)

      const portError = report.errors.find((e) => e.key === 'app.port')
      expect(portError).toBeDefined()
      expect(portError!.expected).toContain('max=65535')
    })

    it('should detect regex pattern violation', () => {
      const invalidConfig = {
        ...createDevConfig(),
        db: { host: 'invalid host!', port: 5432, name: 'mydb', password: 'longpass' },
      }
      const report = validator.validate(invalidConfig, 'pattern-violation')
      expect(report.valid).toBe(false)

      const hostError = report.errors.find((e) => e.key === 'db.host')
      expect(hostError).toBeDefined()
    })

    it('should detect string too short (min length)', () => {
      const invalidConfig = {
        ...createDevConfig(),
        db: { host: 'db', port: 5432, name: 'mydb', password: 'short' },
      }
      const report = validator.validate(invalidConfig, 'min-length')
      expect(report.valid).toBe(false)

      const passError = report.errors.find((e) => e.key === 'db.password')
      expect(passError).toBeDefined()
    })
  })

  describe('exception path - nested object deep field violations', () => {
    it('should pinpoint deep nested field violation: db.replica.host', () => {
      const schemaWithDeepNesting: SchemaConfig = {
        $schema: 'config-flow-schema/v1',
        version: '1.0.0',
        fields: [
          {
            key: 'db',
            type: 'object',
            required: true,
            properties: [
              {
                key: 'replica',
                type: 'object',
                required: true,
                properties: [
                  {
                    key: 'host',
                    type: 'string',
                    required: true,
                    pattern: '^[a-zA-Z0-9._-]+$',
                  },
                  {
                    key: 'port',
                    type: 'integer',
                    required: true,
                    min: 1,
                    max: 65535,
                  },
                ],
              },
            ],
          },
        ],
      }

      const deepValidator = new SchemaValidator(schemaWithDeepNesting)

      const invalidConfig = {
        db: {
          replica: {
            host: 'invalid host!',
            port: 99999,
          },
        },
      }

      const report = deepValidator.validate(invalidConfig, 'deep-nested')
      expect(report.valid).toBe(false)

      const hostError = report.errors.find((e) => e.schemaPath === 'db.replica.host')
      expect(hostError).toBeDefined()
      expect(hostError!.actual).toBe('invalid host!')

      const portError = report.errors.find((e) => e.schemaPath === 'db.replica.port')
      expect(portError).toBeDefined()
    })

    it('should report multiple deep field violations simultaneously', () => {
      const invalidConfig = {
        ...createDevConfig(),
        app: { name: '', port: -1, debug: 'yes' },
        db: { host: '', port: 0, name: '', password: 'x' },
        logLevel: 'invalid',
      }

      const report = validator.validate(invalidConfig, 'multi-violation')
      expect(report.valid).toBe(false)
      expect(report.errors.length).toBeGreaterThanOrEqual(3)
    })
  })

  describe('validateValue - single field validation', () => {
    it('should return null for valid value', () => {
      const error = validator.validateValue('logLevel', 'info', 'dev')
      expect(error).toBeNull()
    })

    it('should return error for invalid value', () => {
      const error = validator.validateValue('logLevel', 'verbose', 'dev')
      expect(error).not.toBeNull()
      expect(error!.key).toBe('logLevel')
      expect(error!.environment).toBe('dev')
    })

    it('should return error for key not in schema', () => {
      const error = validator.validateValue('unknown.key', 'value', 'dev')
      expect(error).not.toBeNull()
      expect(error!.message).toContain('No schema defined')
    })
  })

  describe('edge cases', () => {
    it('should handle empty config without crashing', () => {
      const report = validator.validate(createEmptyConfig(), 'empty')
      expect(report.valid).toBe(false)
      expect(report.timestamp).toBeDefined()
    })

    it('should include timestamp in report', () => {
      const report = validator.validate(createDevConfig(), 'dev')
      expect(report.timestamp).toBeGreaterThan(0)
    })

    it('should getFieldConfig for indexed fields', () => {
      const config = validator.getFieldConfig('app.name')
      expect(config).toBeDefined()
      expect(config!.type).toBe('string')
    })

    it('should getAllFieldKeys', () => {
      const keys = validator.getAllFieldKeys()
      expect(keys).toContain('app')
      expect(keys).toContain('app.name')
      expect(keys).toContain('app.port')
      expect(keys).toContain('db')
      expect(keys).toContain('db.host')
    })

    it('should load from JSON string', () => {
      const jsonStr = JSON.stringify(createSchemaConfig())
      const loaded = SchemaValidator.fromJSON(jsonStr)
      expect(loaded).toBeInstanceOf(SchemaValidator)
    })
  })
})
