import type { Config } from 'jest';

const config: Config = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/tests'],
  moduleNameMapper: {
    '^@core/(.*)$': '<rootDir>/src/core/$1',
    '^@infrastructure/(.*)$': '<rootDir>/src/infrastructure/$1',
    '^@application/(.*)$': '<rootDir>/src/application/$1',
    '^@interfaces/(.*)$': '<rootDir>/src/interfaces/$1',
    '^@shared/(.*)$': '<rootDir>/src/shared/$1',
    '^@api/(.*)$': '<rootDir>/src/api/$1'
  },
  testMatch: ['**/*.test.ts'],
  collectCoverageFrom: [
    'src/core/**/*.ts',
    '!src/**/index.ts'
  ],
  setupFiles: ['<rootDir>/tests/jest.setup.ts'],
  maxWorkers: 1,
};

export default config;
