const axios = require('axios');

module.exports = async () => {
  const baseUrl = process.env.TEST_API_BASE_URL || 'http://localhost:8000';
  
  console.log(`Checking API connectivity at ${baseUrl}...`);
  
  try {
    const response = await axios.get(`${baseUrl}/health`, { timeout: 5000 });
    console.log(`API is healthy: ${response.data.status}`);
  } catch (error) {
    console.warn(`API not available at ${baseUrl}. Some tests may be skipped.`);
    console.warn(`Make sure the Python server is running: cd session323 && python3 main.py`);
  }
  
  global.__TEST_BASE_URL__ = baseUrl;
};
