jest.setTimeout(30000);

process.env.API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
process.env.TEST_CHAIN_ID = process.env.TEST_CHAIN_ID || '1';

beforeAll(async () => {
  console.log('\n=== Test Suite Setup ===');
  console.log(`API Base URL: ${process.env.API_BASE_URL}`);
  console.log(`Test Chain ID: ${process.env.TEST_CHAIN_ID}`);
});

afterAll(async () => {
  console.log('\n=== Test Suite Teardown ===');
});
