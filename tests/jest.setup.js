const { TestDataFactory } = require('./factories/testDataFactory');

beforeAll(() => {
  console.log('\n=== 开始执行单元测试 ===\n');
});

afterAll(() => {
  console.log('\n=== 单元测试执行完成 ===\n');
});

beforeEach(() => {
  TestDataFactory.resetCounters();
});

module.exports = {
  TestDataFactory
};
