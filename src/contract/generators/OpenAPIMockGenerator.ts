import { OpenAPISchema, MockServerConfig, MockEndpoint } from '../types';
import { IMockGenerator } from '../interfaces';
import { logger } from '../../utils/common';

export class OpenAPIMockGenerator implements IMockGenerator<OpenAPISchema> {
  generate(schema: OpenAPISchema, config: Partial<MockServerConfig> = {}): MockServerConfig {
    const endpoints: MockEndpoint[] = [];
    const paths = schema.paths as Record<string, Record<string, unknown>>;

    for (const [path, methods] of Object.entries(paths)) {
      for (const [method, operation] of Object.entries(methods)) {
        if (['get', 'post', 'put', 'delete', 'patch'].includes(method)) {
          const op = operation as Record<string, unknown>;
          const responses = op.responses as Record<string, Record<string, unknown>>;

          let statusCode = 200;
          let response: unknown = {};

          if (responses) {
            const successStatus = Object.keys(responses).find(s => parseInt(s) >= 200 && parseInt(s) < 300) || '200';
            statusCode = parseInt(successStatus);

            const successResponse = responses[successStatus];
            if (successResponse?.content) {
              const content = successResponse.content as Record<string, Record<string, unknown>>;
              const jsonContent = content['application/json'];
              if (jsonContent?.example) {
                response = jsonContent.example;
              } else if (jsonContent?.examples) {
                const examples = jsonContent.examples as Record<string, Record<string, unknown>>;
                const firstExample = Object.values(examples)[0];
                response = firstExample?.value || {};
              }
            }
          }

          endpoints.push({
            method: method.toUpperCase(),
            path,
            response: this.generateMockResponse(response),
            statusCode,
          });
        }
      }
    }

    logger.info(`Generated ${endpoints.length} mock endpoints from OpenAPI schema`);

    return {
      port: config.port || 3000,
      endpoints,
      defaultResponse: config.defaultResponse || { message: 'Mock response' },
      defaultStatusCode: config.defaultStatusCode || 200,
    };
  }

  private generateMockResponse(template: unknown): unknown {
    if (template === null || template === undefined) {
      return null;
    }

    if (Array.isArray(template)) {
      return template.length > 0
        ? [this.generateMockResponse(template[0]), this.generateMockResponse(template[0])]
        : [];
    }

    if (typeof template === 'object') {
      const result: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(template as Record<string, unknown>)) {
        result[key] = this.generateMockResponse(value);
      }
      return result;
    }

    if (typeof template === 'string') {
      return template || 'mock-string';
    }

    if (typeof template === 'number') {
      return template || 42;
    }

    if (typeof template === 'boolean') {
      return template || false;
    }

    return template;
  }
}

export const openAPIMockGenerator = new OpenAPIMockGenerator();
