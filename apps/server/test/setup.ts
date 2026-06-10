import { vi, beforeAll, afterAll, beforeEach, afterEach } from 'vitest';
import { faker } from '@faker-js/faker';
import nock from 'nock';

process.env.NODE_ENV = 'test';
process.env.LOG_LEVEL = 'silent';
process.env.STORAGE_BACKEND = 'local';
process.env.LOCAL_STORAGE_PATH = '/tmp/mlops-test-storage';
process.env.REDIS_URL = 'redis://localhost:6379';
process.env.DATABASE_URL = 'postgresql://mlops:mlops123@localhost:5432/mlops_test';

beforeAll(() => {
  nock.disableNetConnect();
  nock.enableNetConnect('127.0.0.1');
});

afterAll(() => {
  nock.enableNetConnect();
  nock.cleanAll();
});

beforeEach(() => {
  vi.useFakeTimers();
  faker.seed(Date.now() % 2147483647);
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
  nock.cleanAll();
});
