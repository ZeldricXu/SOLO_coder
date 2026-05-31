process.env.NODE_ENV = 'test';

beforeAll(() => {
  console.log('\n========================================');
  console.log('  LLMGateway 自动化测试套件启动');
  console.log('  测试环境:', process.env.NODE_ENV);
  console.log('  测试目标:', process.env.API_BASE_URL || 'http://localhost:8080');
  console.log('========================================\n');
});

afterAll(() => {
  console.log('\n========================================');
  console.log('  测试套件执行完成');
  console.log('========================================\n');
});

beforeEach(() => {
  jest.clearAllMocks();
});
