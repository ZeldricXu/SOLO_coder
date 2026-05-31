module.exports = {
  testEnvironment: 'node',
  testMatch: ['**/*.test.js'],
  testTimeout: 30000,
  verbose: true,
  collectCoverageFrom: [
    '**/*.js',
    '!testDataFactory.js',
    '!jest.config.js'
  ],
  coverageDirectory: './coverage',
  coverageReporters: ['text', 'html', 'lcov'],
  setupFiles: ['./setup.js'],
  globalSetup: './globalSetup.js',
  globalTeardown: './globalTeardown.js'
};
