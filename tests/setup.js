const axios = require('axios');
const config = require('./config');
require('./matchers');

const axiosInstance = axios.create({
  baseURL: config.baseURL + config.apiPrefix,
  timeout: config.timeout.normal,
  headers: {
    'Content-Type': 'application/json',
    'X-Test-Request': 'true',
  },
});

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.code === 'ECONNABORTED' || error.code === 'ECONNREFUSED') {
      console.warn(`\n⚠️  无法连接到测试服务器 ${config.baseURL}`);
      console.warn('   请确保后端服务已启动，或设置 TEST_BASE_URL 环境变量\n');
    }
    return Promise.reject(error);
  }
);

global.testAPI = axiosInstance;
global.testConfig = config;

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
global.delay = delay;

const generateRandomString = (length = 8) => {
  return Math.random().toString(36).substring(2, 2 + length);
};
global.generateRandomString = generateRandomString;

const retryOperation = async (operation, maxRetries = 3, delayMs = 1000) => {
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await operation();
    } catch (error) {
      if (i === maxRetries - 1) throw error;
      await delay(delayMs);
    }
  }
};
global.retryOperation = retryOperation;

beforeAll(async () => {
  console.log('\n' + '='.repeat(60));
  console.log('🚀 DepGuard API 集成测试启动');
  console.log(`📍 目标地址: ${config.baseURL}`);
  console.log('='.repeat(60) + '\n');

  try {
    await axiosInstance.get('/health', {
      baseURL: config.baseURL,
      timeout: 5000,
    });
    console.log('✅ 后端服务连接正常\n');
  } catch (error) {
    console.log('⚠️  后端服务未连接，部分测试将跳过实际调用\n');
  }
});

afterAll(async () => {
  console.log('\n' + '='.repeat(60));
  console.log('🏁 测试执行完成');
  console.log('='.repeat(60) + '\n');
});
