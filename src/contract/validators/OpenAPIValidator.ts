import { OpenAPISchema, ValidationResult, ValidationError } from '../types';
import { ISchemaValidator } from '../interfaces';
import Ajv, { ValidateFunction } from 'ajv';
import { logger } from '../../utils/common';
import { LRUCache } from '../../utils/cache';

export class OpenAPIValidator implements ISchemaValidator<OpenAPISchema> {
  private ajv: Ajv;
  private schemaCache: LRUCache<string, ValidateFunction>;
  private static readonly DEFAULT_CACHE_SIZE = 50;
  private static readonly DEFAULT_CACHE_TTL = 30 * 60 * 1000;

  constructor(cacheSize?: number, cacheTTLMs?: number) {
    this.ajv = new Ajv({
      strict: false,
      allErrors: true,
      discriminator: true,
    });

    this.schemaCache = new LRUCache<string, ValidateFunction>({
      maxSize: cacheSize || OpenAPIValidator.DEFAULT_CACHE_SIZE,
      ttlMs: cacheTTLMs || OpenAPIValidator.DEFAULT_CACHE_TTL,
    });
  }

  validateSchema(schema: OpenAPISchema): ValidationResult {
    const result: ValidationResult = {
      valid: true,
      errors: [],
      warnings: [],
    };

    try {
      const parsed = schema as OpenAPISchema;

      if (!parsed.openapi) {
        result.valid = false;
        result.errors.push({
          path: 'openapi',
          message: 'openapi version is required',
        });
      }

      if (!parsed.info?.title) {
        result.valid = false;
        result.errors.push({
          path: 'info.title',
          message: 'info.title is required',
        });
      }

      if (!parsed.info?.version) {
        result.valid = false;
        result.errors.push({
          path: 'info.version',
          message: 'info.version is required',
        });
      }

      if (!parsed.paths || typeof parsed.paths !== 'object') {
        result.valid = false;
        result.errors.push({
          path: 'paths',
          message: 'paths must be an object',
        });
      }

      if (parsed.openapi && !parsed.openapi.startsWith('3.')) {
        result.warnings.push('Only OpenAPI 3.x is fully supported');
      }

      logger.info(`OpenAPI schema validation completed`, {
        valid: result.valid,
        errorCount: result.errors.length,
        warningCount: result.warnings.length,
      });

    } catch (error) {
      result.valid = false;
      result.errors.push({
        path: '',
        message: error instanceof Error ? error.message : 'Invalid schema',
      });
    }

    return result;
  }

  validateRequest(
    schema: OpenAPISchema,
    path: string,
    method: string,
    request: { body?: unknown; params?: Record<string, string>; query?: Record<string, string> }
  ): ValidationResult {
    const result: ValidationResult = {
      valid: true,
      errors: [],
      warnings: [],
    };

    const pathItem = (schema.paths as Record<string, unknown>)[path];
    if (!pathItem) {
      result.valid = false;
      result.errors.push({
        path,
        message: `Path ${path} not found in schema`,
      });
      return result;
    }

    const operation = (pathItem as Record<string, unknown>)[method.toLowerCase()];
    if (!operation) {
      result.valid = false;
      result.errors.push({
        path: `${path}.${method}`,
        message: `Method ${method} not found for path ${path}`,
      });
      return result;
    }

    if (request.body) {
      const requestBody = (operation as Record<string, unknown>).requestBody;
      if (requestBody) {
        const content = (requestBody as Record<string, unknown>).content as Record<string, unknown>;
        const jsonContent = content?.['application/json'];
        if (jsonContent) {
          const bodySchema = (jsonContent as Record<string, unknown>).schema;
          if (bodySchema) {
            const valid = this.validateAgainstSchema(request.body, bodySchema);
            if (!valid.valid) {
              result.valid = false;
              result.errors.push(...valid.errors);
            }
          }
        }
      }
    }

    return result;
  }

  validateResponse(
    schema: OpenAPISchema,
    path: string,
    method: string,
    statusCode: number,
    response: unknown
  ): ValidationResult {
    const result: ValidationResult = {
      valid: true,
      errors: [],
      warnings: [],
    };

    const pathItem = (schema.paths as Record<string, unknown>)[path];
    const operation = pathItem ? (pathItem as Record<string, unknown>)[method.toLowerCase()] : null;
    const responses = operation ? (operation as Record<string, unknown>).responses as Record<string, unknown> : null;

    if (!responses) {
      result.warnings.push(`No responses defined for ${method} ${path}`);
      return result;
    }

    const responseDef = responses[statusCode.toString()] || responses['default'];
    if (!responseDef) {
      result.warnings.push(`No response defined for status ${statusCode}`);
      return result;
    }

    const content = (responseDef as Record<string, unknown>).content as Record<string, unknown>;
    const jsonContent = content?.['application/json'];
    if (jsonContent) {
      const bodySchema = (jsonContent as Record<string, unknown>).schema;
      if (bodySchema) {
        const valid = this.validateAgainstSchema(response, bodySchema);
        if (!valid.valid) {
          result.valid = false;
          result.errors.push(...valid.errors);
        }
      }
    }

    return result;
  }

  private validateAgainstSchema(data: unknown, schema: unknown): ValidationResult {
    const result: ValidationResult = {
      valid: true,
      errors: [],
      warnings: [],
    };

    try {
      const cacheKey = JSON.stringify(schema);
      let validate = this.schemaCache.get(cacheKey);

      if (!validate) {
        validate = this.ajv.compile(schema as object);
        this.schemaCache.set(cacheKey, validate);
      }

      const valid = validate(data);

      if (!valid && validate.errors) {
        result.valid = false;
        result.errors = validate.errors.map(e => ({
          path: e.instancePath,
          message: e.message || 'Validation error',
          expected: e.params?.type,
          actual: typeof data,
        }));
      }
    } catch (error) {
      result.valid = false;
      result.errors.push({
        path: '',
        message: error instanceof Error ? error.message : 'Schema validation error',
      });
    }

    return result;
  }

  getCacheStats() {
    return this.schemaCache.getStats();
  }

  clearCache(): void {
    this.schemaCache.clear();
    logger.info('OpenAPIValidator cache cleared');
  }

  destroy(): void {
    this.schemaCache.destroy();
    logger.info('OpenAPIValidator destroyed');
  }
}

export const openAPIValidator = new OpenAPIValidator();
