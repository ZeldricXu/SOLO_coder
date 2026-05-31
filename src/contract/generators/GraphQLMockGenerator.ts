import { GraphQLSchema, MockServerConfig, MockEndpoint } from '../types';
import { IMockGenerator } from '../interfaces';
import { logger } from '../../utils/common';

export class GraphQLMockGenerator implements IMockGenerator<GraphQLSchema> {
  generate(schema: GraphQLSchema, config: Partial<MockServerConfig> = {}): MockServerConfig {
    const endpoints: MockEndpoint[] = [];

    endpoints.push({
      method: 'POST',
      path: '/graphql',
      response: {
        data: this.generateMockGraphQLResponse(schema),
      },
      statusCode: 200,
    });

    endpoints.push({
      method: 'GET',
      path: '/graphql',
      response: {
        data: this.generateMockGraphQLResponse(schema),
      },
      statusCode: 200,
    });

    logger.info(`Generated ${endpoints.length} mock endpoints from GraphQL schema`);

    return {
      port: config.port || 3000,
      endpoints,
      defaultResponse: { data: {} },
      defaultStatusCode: 200,
    };
  }

  private generateMockGraphQLResponse(schema: GraphQLSchema): Record<string, unknown> {
    const typeDefs = schema.typeDefs;
    const mockData: Record<string, unknown> = {};

    const typeMatches = typeDefs.match(/type\s+(\w+)\s*\{([^}]+)\}/g);
    if (typeMatches) {
      for (const typeMatch of typeMatches) {
        const nameMatch = typeMatch.match(/type\s+(\w+)/);
        if (nameMatch) {
          mockData[nameMatch[1].toLowerCase()] = {
            id: '1',
            name: 'Mock Name',
          };
        }
      }
    }

    return mockData;
  }
}

export const graphQLMockGenerator = new GraphQLMockGenerator();
