import { beforeAll } from 'vitest';

beforeAll(() => {
  Object.defineProperty(globalThis, 'navigator', {
    value: { gpu: undefined },
    writable: true,
  });
});
