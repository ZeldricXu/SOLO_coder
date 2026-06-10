import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    setupFiles: ['./tests/pre-setup.ts', './tests/setup.ts'],
    testTimeout: 120000,
    hookTimeout: 120000,
    teardownTimeout: 60000,
    threads: true,
    maxThreads: 4,
    minThreads: 1,
    include: ['tests/**/*.test.ts'],
    exclude: ['node_modules', 'dist'],
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@config': path.resolve(__dirname, './src/config'),
      '@modules': path.resolve(__dirname, './src/modules'),
      '@common': path.resolve(__dirname, './src/common'),
      '@utils': path.resolve(__dirname, './src/utils'),
      '@types': path.resolve(__dirname, './src/types'),
    },
  },
});
