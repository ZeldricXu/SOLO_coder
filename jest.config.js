module.exports = {
  testEnvironment: 'node',
  testTimeout: 30000,
  setupFilesAfterEnv: ['./tests/setup.js'],
  testMatch: ['**/tests/**/*.test.js'],
  verbose: true,
  forceExit: true,
  detectOpenHandles: false,
};
