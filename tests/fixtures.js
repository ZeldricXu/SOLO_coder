const { generateRandomString } = global;

const testFixtures = {
  scaffold: {
    createTemplate: (overrides = {}) => ({
      name: `测试模板-${generateRandomString()}`,
      description: 'Jest测试模板',
      language: 'go',
      framework: 'gin',
      version: '1.0.0',
      tags: ['test', 'automated'],
      parameters: { database: 'postgres' },
      file_tree: { 'main.go': 'package main' },
      is_public: true,
      author: 'test-user',
      ...overrides,
    }),

    generateProject: (templateId, overrides = {}) => ({
      name: `测试项目-${generateRandomString()}`,
      description: 'Jest测试生成项目',
      template_id: templateId,
      namespace: 'test-namespace',
      config: { database: 'mysql' },
      owner_id: 'test-user-001',
      ...overrides,
    }),

    createBackup: (resourceType, resourceId, overrides = {}) => ({
      resource_type: resourceType,
      resource_id: resourceId,
      backup_type: 'manual',
      created_by: 'test-user',
      ...overrides,
    }),
  },

  apicontract: {
    createSchema: (overrides = {}) => ({
      name: `测试Schema-${generateRandomString()}`,
      version: '1.0.0',
      schema_type: 'openapi',
      content: JSON.stringify({
        openapi: '3.0.0',
        info: { title: 'Test API', version: '1.0.0' },
        paths: {},
      }),
      format: 'json',
      service_name: 'test-service',
      metadata: { owner: 'test-team' },
      is_active: true,
      ...overrides,
    }),

    createGraphQLSchema: (overrides = {}) => ({
      name: `GraphQL测试-${generateRandomString()}`,
      version: '1.0.0',
      schema_type: 'graphql',
      content: `
        type Query {
          hello: String
        }
      `,
      format: 'yaml',
      ...overrides,
    }),

    createMockServer: (schemaId, overrides = {}) => ({
      schema_id: schemaId,
      name: `MockServer-${generateRandomString()}`,
      config: { port: 8080, delay_ms: 100 },
      ...overrides,
    }),

    createContractTest: (schemaId, overrides = {}) => ({
      schema_id: schemaId,
      name: `契约测试-${generateRandomString()}`,
      test_type: 'request_response',
      request: {
        method: 'GET',
        path: '/api/test',
        headers: {},
      },
      expected: {
        status: 200,
        headers: {},
        body: {},
      },
      ...overrides,
    }),
  },
};

module.exports = testFixtures;
