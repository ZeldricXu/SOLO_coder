import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    setupFiles: ['./test/setup.ts'],
    include: [
      'test/unit/**/*.test.ts',
      'test/integration/**/*.test.ts',
    ],
    exclude: [
      'node_modules',
      'dist',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: [
        'src/model/**',
        'src/inference/**',
        'src/feature-store/**',
        'src/abtest/**',
      ],
      exclude: [
        'src/config/**',
        'src/proto/**',
      ],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 70,
      },
    },
    retry: 0,
    testTimeout: 30000,
    hookTimeout: 60000,
    teardownTimeout: 10000,
  },
  resolve: {
    alias: {
      '@mlops/shared': path.resolve(__dirname, '../shared/src'),
    },
  },
});
