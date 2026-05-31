import { GraphQLSchema, ValidationResult } from '../types';
import { ISchemaValidator } from '../interfaces';
import { parse, validate, buildSchema, GraphQLSchema as GraphQLSchemaType } from 'graphql';
import { logger } from '../../utils/common';
import { LRUCache } from '../../utils/cache';

export class GraphQLValidator implements ISchemaValidator<GraphQLSchema> {
  private schemaCache: LRUCache<string, GraphQLSchemaType>;
  private static readonly DEFAULT_CACHE_SIZE = 50;
  private static readonly DEFAULT_CACHE_TTL = 30 * 60 * 1000;

  constructor(cacheSize?: number, cacheTTLMs?: number) {
    this.schemaCache = new LRUCache<string, GraphQLSchemaType>({
      maxSize: cacheSize || GraphQLValidator.DEFAULT_CACHE_SIZE,
      ttlMs: cacheTTLMs || GraphQLValidator.DEFAULT_CACHE_TTL,
    });
  }

  validateSchema(schema: GraphQLSchema): ValidationResult {
    const result: ValidationResult = {
      valid: true,
      errors: [],
      warnings: [],
    };

    try {
      if (!schema.typeDefs || schema.typeDefs.trim() === '') {
        result.valid = false;
        result.errors.push({
          path: 'typeDefs',
          message: 'typeDefs is required',
        });
        return result;
      }

      const builtSchema = buildSchema(schema.typeDefs);
      this.schemaCache.set(schema.typeDefs, builtSchema);

      const queryType = builtSchema.getType(schema.queryType || 'Query');
      if (!queryType) {
        result.warnings.push(`Query type ${schema.queryType} not found`);
      }

      logger.info(`GraphQL schema validation completed`, {
        valid: result.valid,
        errorCount: result.errors.length,
      });

    } catch (error) {
      result.valid = false;
      result.errors.push({
        path: 'typeDefs',
        message: error instanceof Error ? error.message : 'Invalid GraphQL schema',
      });
    }

    return result;
  }

  validateQuery(schema: GraphQLSchema, query: string): ValidationResult {
    const result: ValidationResult = {
      valid: true,
      errors: [],
      warnings: [],
    };

    try {
      let graphQLSchema = this.schemaCache.get(schema.typeDefs);
      if (!graphQLSchema) {
        graphQLSchema = buildSchema(schema.typeDefs);
        this.schemaCache.set(schema.typeDefs, graphQLSchema);
      }

      const documentAST = parse(query);
      const validationErrors = validate(graphQLSchema, documentAST);

      if (validationErrors.length > 0) {
        result.valid = false;
        result.errors = validationErrors.map(e => ({
          path: e.path ? e.path.join('.') : '',
          message: e.message,
        }));
      }

    } catch (error) {
      result.valid = false;
      result.errors.push({
        path: '',
        message: error instanceof Error ? error.message : 'Query validation error',
      });
    }

    return result;
  }

  validateMutation(schema: GraphQLSchema, mutation: string): ValidationResult {
    return this.validateQuery(schema, mutation);
  }

  validateSubscription(schema: GraphQLSchema, subscription: string): ValidationResult {
    return this.validateQuery(schema, subscription);
  }

  getCacheStats() {
    return this.schemaCache.getStats();
  }

  clearCache(): void {
    this.schemaCache.clear();
    logger.info('GraphQLValidator cache cleared');
  }

  destroy(): void {
    this.schemaCache.destroy();
    logger.info('GraphQLValidator destroyed');
  }
}

export const graphQLValidator = new GraphQLValidator();
