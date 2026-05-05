import { existsSync, mkdirSync, unlinkSync } from 'fs';
import { join } from 'path';

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
  jest.clearAllMocks();
});

export function createTestDbPath(): string {
  const testDataDir = join(process.cwd(), 'test-data');
  if (!existsSync(testDataDir)) {
    mkdirSync(testDataDir, { recursive: true });
  }
  return join(testDataDir, `test-${Date.now()}.db`);
}

export function cleanupTestDb(path: string): void {
  if (existsSync(path)) {
    unlinkSync(path);
  }
}

export async function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}
