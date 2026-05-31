/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts', '**/?(*.)+(spec|test).ts'],
  collectCoverageFrom: [
    'src/modules/data-processing/**/*.ts',
    'src/modules/adversarial/**/*.ts',
    'src/modules/gpu-scheduler/**/*.ts',
    'src/modules/notification/**/*.ts',
    'src/infrastructure/cache/MemoryCache.ts',
    '!src/**/*.d.ts',
    '!src/**/index.ts',
    '!**/node_modules/**'
  ],
  coverageThreshold: {
    global: {
      branches: 60,
      functions: 60,
      lines: 70,
      statements: 70
    }
  },
  coverageReporters: ['text', 'lcov', 'html'],
  coverageDirectory: 'coverage',
  setupFilesAfterEnv: ['<rootDir>/src/__tests__/setup.ts'],
  moduleNameMapper: {
    '@/(.*)': '<rootDir>/src/$1',
    '@core/(.*)': '<rootDir>/src/core/$1',
    '@modules/(.*)': '<rootDir>/src/modules/$1',
    '@infrastructure/(.*)': '<rootDir>/src/infrastructure/$1',
    '@common/(.*)': '<rootDir>/src/common/$1'
  },
  testTimeout: 30000,
  verbose: true,
  forceExit: true,
  clearMocks: true,
  resetMocks: true,
  restoreMocks: true
};
