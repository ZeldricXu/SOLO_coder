const axios = require('axios');

beforeAll(async () => {
  const baseUrl = process.env.API_BASE_URL || 'http://localhost:8080';
  console.log(`🧪 测试环境初始化 - API端点: ${baseUrl}`);
  
  try {
    const response = await axios.get(`${baseUrl}/health`, { timeout: 5000 });
    console.log(`✅ 服务健康检查通过: ${response.data.status}`);
  } catch (error) {
    console.warn(`⚠️  服务健康检查失败，测试将以离线模式运行: ${error.message}`);
  }
});

afterAll(async () => {
  console.log('🧹 测试环境清理完成');
});

expect.extend({
  toBeValidApiResponse(received) {
    const pass = received && 
                 typeof received.code === 'number' && 
                 received.data !== undefined;
    return {
      pass,
      message: () => pass 
        ? 'expected response not to be a valid API response'
        : `expected response to be a valid API response with code and data fields, received ${JSON.stringify(received)}`
    };
  },
  
  toBeBetween(received, min, max) {
    const pass = typeof received === 'number' && received >= min && received <= max;
    return {
      pass,
      message: () => pass
        ? `expected ${received} not to be between ${min} and ${max}`
        : `expected ${received} to be between ${min} and ${max}`
    };
  },
  
  toBeValidUuid(received) {
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    const pass = typeof received === 'string' && uuidRegex.test(received);
    return {
      pass,
      message: () => pass
        ? `expected ${received} not to be a valid UUID`
        : `expected ${received} to be a valid UUID`
    };
  }
});
