const request = require('supertest');

beforeAll(async () => {
  jest.setTimeout(parseInt(process.env.TEST_TIMEOUT) || 30000);
});

afterEach(() => {
  jest.clearAllMocks();
});

global.api = request(process.env.API_BASE_URL || 'http://localhost:8000');
global.basePath = `/api/${process.env.API_VERSION || 'v1'}`;
