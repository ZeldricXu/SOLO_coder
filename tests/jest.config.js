module.exports = {
  testEnvironment: 'node',
  testMatch: [
    '**/__tests__/**/*.test.js',
    '**/?(*.)+(spec|test).js'
  ],
  testPathIgnorePatterns: [
    '/node_modules/',
    '/fixtures/',
    '/factories/'
  ],
  setupFilesAfterEnv: [
    '<rootDir>/jest.setup.js'
  ],
  testTimeout: 30000,
  verbose: true,
  collectCoverage: false,
  collectCoverageFrom: [
    '**/*.js',
    '!**/node_modules/**',
    '!**/coverage/**',
    '!jest.config.js'
  ],
  reporters: [
    'default',
    [
      'jest-junit',
      {
        outputDirectory: './reports',
        outputName: 'junit.xml'
      }
    ]
  ],
  maxConcurrency: 10
};
