/** @type {import('jest').Config} */
module.exports = {
  testEnvironment: 'node',
  testMatch: ['**/test/**/*.test.js'],
  testTimeout: 30000,
  verbose: true,
  collectCoverageFrom: [
    'test/**/*.js',
    '!test/data/**'
  ],
  coverageDirectory: 'coverage',
  coverageReporters: ['text', 'lcov', 'html'],
  setupFilesAfterEnv: ['./test/setup.js'],
  globalSetup: './test/global-setup.js',
  globalTeardown: './test/global-teardown.js'
};
