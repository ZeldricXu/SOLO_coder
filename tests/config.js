module.exports = {
  baseURL: process.env.TEST_BASE_URL || 'http://localhost:8080',
  apiPrefix: '/api/v1',
  timeout: {
    normal: 5000,
    long: 15000,
    concurrent: 30000,
  },
  concurrency: {
    maxRequests: 50,
    delayBetweenBatches: 100,
  },
  testData: {
    invalidIDs: ['', null, undefined, 'invalid-id-format', '123', 'a'.repeat(100)],
    boundaryValues: {
      maxNameLength: 128,
      maxVersionLength: 32,
      minPageSize: 1,
      maxPageSize: 100,
    },
  },
};
