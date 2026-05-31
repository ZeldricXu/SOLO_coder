const { config } = require('dotenv');
config({ path: '.env.test' });

module.exports = async () => {
  console.log('\n=== Test Suite Setup ===');
  console.log(`API Base URL: ${process.env.API_BASE_URL}`);
  console.log(`Test Timeout: ${process.env.TEST_TIMEOUT}ms`);
  console.log('========================\n');
  
  global.__TEST_START_TIME__ = Date.now();
};
