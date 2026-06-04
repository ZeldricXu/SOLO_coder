import { vi, beforeEach, afterEach } from 'vitest';
import nock from 'nock';

process.env.NODE_ENV = 'test';
process.env.DATABASE_URL = process.env.TEST_DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/notification_gateway_test';
process.env.REDIS_URL = process.env.TEST_REDIS_URL || 'redis://localhost:6379/1';
process.env.SENDGRID_API_KEY = 'test-sendgrid-key';
process.env.JWT_SECRET = 'test-jwt-secret';

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  if (!nock.isActive()) {
    nock.activate();
  }
});

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  nock.cleanAll();
  nock.enableNetConnect();
});

vi.setConfig({
  testTimeout: 60000,
});
