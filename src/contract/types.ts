import { z } from 'zod';

export const OpenAPISchemaSchema = z.object({
  openapi: z.string(),
  info: z.object({
    title: z.string(),
    version: z.string(),
    description: z.string().optional(),
  }),
  paths: z.record(z.unknown()),
  components: z.record(z.unknown()).optional(),
  servers: z.array(z.unknown()).optional(),
});

export type OpenAPISchema = z.infer<typeof OpenAPISchemaSchema>;

export const GraphQLSchemaSchema = z.object({
  typeDefs: z.string(),
  queryType: z.string().default('Query'),
  mutationType: z.string().default('Mutation'),
});

export type GraphQLSchema = z.infer<typeof GraphQLSchemaSchema>;

export const ContractConfigSchema = z.object({
  contractId: z.string(),
  name: z.string(),
  version: z.string(),
  type: z.enum(['openapi', 'graphql']),
  schema: z.unknown(),
  mockConfig: z.object({
    enabled: z.boolean().default(true),
    port: z.number().default(3000),
    delayMs: z.number().default(0),
    errorRate: z.number().min(0).max(1).default(0),
    examples: z.record(z.unknown()).default({}),
  }).optional(),
  validation: z.object({
    enabled: z.boolean().default(true),
    strictMode: z.boolean().default(false),
  }).optional(),
});

export type ContractConfig = z.infer<typeof ContractConfigSchema>;

export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: string[];
}

export interface ValidationError {
  path: string;
  message: string;
  expected?: string;
  actual?: string;
}

export interface MockEndpoint {
  method: string;
  path: string;
  response: unknown;
  statusCode: number;
  delayMs?: number;
}

export interface MockServerConfig {
  port: number;
  endpoints: MockEndpoint[];
  defaultResponse?: unknown;
  defaultStatusCode?: number;
}
