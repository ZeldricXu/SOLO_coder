const axios = require('axios');

module.exports = async () => {
  console.log('\n=== Global Test Setup ===');

  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080';

  try {
    await axios.get(`${apiBaseUrl}/api/v1/health`, {
      timeout: 5000
    });
    console.log('API server is running and healthy');
  } catch (error) {
    console.warn('API server is not running. Some integration tests may fail.');
  }
};
