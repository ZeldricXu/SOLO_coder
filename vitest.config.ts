import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    setupFiles: ['./src/tests/setup.ts'],
    testTimeout: 60000,
    hookTimeout: 60000,
    include: ['src/tests/**/*.test.ts', 'src/tests/**/*.spec.ts'],
    exclude: ['node_modules', '.next'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      include: [
        'src/lib/integrations/**/*.ts',
        'src/lib/search/**/*.ts',
        'src/lib/collab/**/*.ts',
        'src/server/services/**/*.ts',
      ],
      exclude: ['**/*.d.ts', '**/types.ts'],
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
