const APIClient = require('../utils/api.client');
const {
  generateMaskingRequest,
  generateSensitiveDataRecord,
  generateBatchMaskingRequest,
  generateConcurrentTestScenarios,
  generateIsolationTestCases,
  getReplacementForType,
  MaskingRuleType
} = require('../factories/masking.factory');
const { PermissionLevel } = require('../factories/common.factory');

jest.setTimeout(120000);

describe('🎭 动态数据脱敏模块 - 并发隔离级别测试', () => {
  let apiClient;
  const concurrentScenarios = generateConcurrentTestScenarios();
  const isolationTestCases = generateIsolationTestCases();

  beforeAll(() => {
    apiClient = new APIClient();
  });

  describe('权限级别隔离测试', () => {
    test.each(isolationTestCases)('权限隔离 - $name', async (testCase) => {
      console.log(`\n📋 执行测试: ${testCase.name}`);

      const request = generateMaskingRequest({ data: testCase.data });
      const authHeader = createAuthHeader(testCase.permission);

      const response = await apiClient.maskData({
        ...request,
        auth_context: { permission_level: testCase.permission }
      });

      expect(response).toBeValidApiResponse();
      expect(response.code).toBe(200);

      const maskedData = response.data.masked_data;
      
      if (testCase.expectedMasked === false) {
        expect(maskedData.email).toBe(testCase.data.email);
        expect(maskedData.phone).toBe(testCase.data.phone);
        expect(maskedData.id_card).toBe(testCase.data.id_card);
        expect(maskedData.bank_card).toBe(testCase.data.bank_card);
        console.log(`   ✅ 管理员权限，所有字段未脱敏`);
      }

      if (testCase.expectedFullMasking) {
        expect(maskedData.email).not.toBe(testCase.data.email);
        expect(maskedData.phone).not.toBe(testCase.data.phone);
        expect(maskedData.id_card).not.toBe(testCase.data.id_card);
        expect(maskedData.bank_card).not.toBe(testCase.data.bank_card);
        expect(maskedData.name).not.toBe(testCase.data.name);
        expect(maskedData.address).not.toBe(testCase.data.address);
        console.log(`   ✅ 只读权限，所有字段完全脱敏`);
      }

      if (testCase.expectedStrictMasking) {
        testCase.maskedFields.forEach(field => {
          expect(maskedData[field]).not.toBe(testCase.data[field]);
        });
        console.log(`   ✅ 受限权限，敏感字段严格脱敏`);
      }
    });
  });

  describe('并发隔离测试', () => {
    test('不同权限用户同时访问同一数据 - 结果互不干扰', async () => {
      const scenario = concurrentScenarios[0];
      console.log(`\n📋 执行测试: ${scenario.name}`);
      console.log(`   并发数: ${scenario.concurrency}, 迭代次数: ${scenario.iterations}`);

      const sharedData = generateSensitiveDataRecord();
      
      for (let iteration = 0; iteration < scenario.iterations; iteration++) {
        const promises = scenario.users.map(user => {
          const request = generateMaskingRequest({ data: sharedData });
          return apiClient.maskData({
            ...request,
            auth_context: { permission_level: user.permission }
          });
        });

        const responses = await Promise.all(promises);
        
        responses.forEach((response, index) => {
          expect(response).toBeValidApiResponse();
          expect(response.code).toBe(200);
          
          const user = scenario.users[index];
          const maskedData = response.data.masked_data;
          
          if (user.expectedMaskingLevel === 'none') {
            expect(maskedData.id_card).toBe(sharedData.id_card);
            expect(maskedData.bank_card).toBe(sharedData.bank_card);
          } else if (user.expectedMaskingLevel === 'full') {
            expect(maskedData.id_card).not.toBe(sharedData.id_card);
            expect(maskedData.bank_card).not.toBe(sharedData.bank_card);
          }
        });

        if (iteration % 20 === 0) {
          console.log(`   进度: ${iteration + 1}/${scenario.iterations} 轮完成`);
        }
      }

      console.log(`✅ 并发隔离测试通过，${scenario.iterations} 轮并发访问结果互不干扰`);
    });

    test('同一用户并发访问不同数据 - 数据隔离', async () => {
      const scenario = concurrentScenarios[1];
      console.log(`\n📋 执行测试: ${scenario.name}`);
      console.log(`   并发数: ${scenario.concurrency}, 数据量: ${scenario.dataCount}`);

      const testData = Array.from({ length: scenario.dataCount }, () => 
        generateSensitiveDataRecord()
      );

      const promises = testData.slice(0, scenario.concurrency).map(data => {
        const request = generateMaskingRequest({ data });
        return apiClient.maskData({
          ...request,
          auth_context: { permission_level: scenario.user.permission }
        });
      });

      const responses = await Promise.all(promises);
      
      const maskedEmails = responses.map(r => r.data.masked_data.email);
      const uniqueEmails = new Set(maskedEmails);
      
      expect(responses.length).toBe(scenario.concurrency);
      responses.forEach(response => {
        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(200);
      });

      if (scenario.expectedIsolation) {
        expect(uniqueEmails.size).toBe(scenario.concurrency);
      }

      console.log(`✅ 同一用户并发访问不同数据隔离测试通过`);
    });

    test('高并发下脱敏规则一致性', async () => {
      const scenario = concurrentScenarios[2];
      console.log(`\n📋 执行测试: ${scenario.name}`);
      console.log(`   并发数: ${scenario.concurrency}, 迭代次数: ${scenario.iterations}`);

      const testData = generateSensitiveDataRecord();
      let ruleConsistencyFailures = 0;
      const results = [];

      for (let i = 0; i < scenario.iterations; i += scenario.concurrency) {
        const batchSize = Math.min(scenario.concurrency, scenario.iterations - i);
        const promises = [];

        for (let j = 0; j < batchSize; j++) {
          const request = generateMaskingRequest({ data: testData });
          promises.push(apiClient.maskData({
            ...request,
            auth_context: { permission_level: PermissionLevel.RESTRICTED }
          }));
        }

        const batchResponses = await Promise.all(promises);
        results.push(...batchResponses);

        batchResponses.forEach(response => {
          const maskedEmail = response.data.masked_data.email;
          if (!isValidMaskedEmail(maskedEmail, testData.email)) {
            ruleConsistencyFailures++;
          }
        });

        if (i % 50 === 0) {
          console.log(`   进度: ${i + batchSize}/${scenario.iterations} 请求完成，失败: ${ruleConsistencyFailures}`);
        }
      }

      const consistencyRate = 1 - (ruleConsistencyFailures / results.length);
      console.log(`   规则一致性: ${(consistencyRate * 100).toFixed(2)}%`);
      expect(consistencyRate).toBeGreaterThanOrEqual(scenario.expectedRuleConsistency);

      const firstResult = results[0].data.masked_data;
      const inconsistentResults = results.filter(r => 
        JSON.stringify(r.data.masked_data) !== JSON.stringify(firstResult)
      );
      expect(inconsistentResults.length).toBe(0);

      console.log(`✅ 高并发下脱敏规则一致性测试通过`);
    });

    test('批量数据处理并发隔离', async () => {
      console.log(`\n📋 执行测试: 批量数据处理并发隔离`);

      const batchCount = 5;
      const batchSize = 100;
      const promises = [];

      for (let i = 0; i < batchCount; i++) {
        const batchRequest = generateBatchMaskingRequest(batchSize);
        promises.push(apiClient.maskData({
          ...batchRequest,
          auth_context: { permission_level: PermissionLevel.RESTRICTED }
        }));
      }

      const responses = await Promise.all(promises);
      
      responses.forEach((response, index) => {
        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(200);
        expect(response.data.masked_records.length).toBe(batchSize);
        console.log(`   批次 ${index + 1}: ${batchSize} 条数据处理完成`);
      });

      console.log(`✅ 批量数据处理并发隔离测试通过，共处理 ${batchCount * batchSize} 条数据`);
    });
  });

  describe('字段级隔离测试', () => {
    test('不同字段类型脱敏规则正确应用', async () => {
      console.log(`\n📋 执行测试: 不同字段类型脱敏规则正确应用`);

      const testData = generateSensitiveDataRecord();
      const fields = ['email', 'phone', 'id_card', 'bank_card', 'name', 'address'];

      for (const field of fields) {
        const request = generateMaskingRequest({ 
          data: testData, 
          fields: [field] 
        });
        
        const response = await apiClient.maskData({
          ...request,
          auth_context: { permission_level: PermissionLevel.READ_ONLY }
        });

        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(200);

        const maskedData = response.data.masked_data;
        expect(maskedData[field]).not.toBe(testData[field]);
        expect(isValidMaskedValue(maskedData[field], field)).toBe(true);
        
        console.log(`   ${field}: ${testData[field]} → ${maskedData[field]} ✓`);
      }

      console.log(`✅ 不同字段类型脱敏规则测试通过`);
    });

    test('部分字段脱敏不影响其他字段', async () => {
      console.log(`\n📋 执行测试: 部分字段脱敏不影响其他字段`);

      const testData = generateSensitiveDataRecord();
      const fieldsToMask = ['email', 'phone'];
      const fieldsToPreserve = ['id_card', 'bank_card', 'name', 'address', 'salary'];

      const request = generateMaskingRequest({ 
        data: testData, 
        fields: fieldsToMask 
      });
      
      const response = await apiClient.maskData({
        ...request,
        auth_context: { permission_level: PermissionLevel.RESTRICTED }
      });

      expect(response).toBeValidApiResponse();
      expect(response.code).toBe(200);

      const maskedData = response.data.masked_data;

      fieldsToMask.forEach(field => {
        expect(maskedData[field]).not.toBe(testData[field]);
      });

      fieldsToPreserve.forEach(field => {
        expect(maskedData[field]).toBe(testData[field]);
      });

      console.log(`✅ 部分字段脱敏不影响其他字段测试通过`);
    });
  });

  describe('跨请求隔离测试', () => {
    test('不同请求上下文数据隔离', async () => {
      console.log(`\n📋 执行测试: 不同请求上下文数据隔离`);

      const contexts = [
        { source: 'api', purpose: 'customer-support' },
        { source: 'batch', purpose: 'data-analytics' },
        { source: 'internal', purpose: 'audit' },
        { source: 'third-party', purpose: 'data-sharing' }
      ];

      const testData = generateSensitiveDataRecord();
      const results = [];

      for (const ctx of contexts) {
        const request = generateMaskingRequest({ 
          data: testData,
          context: ctx 
        });
        
        const response = await apiClient.maskData({
          ...request,
          auth_context: { permission_level: PermissionLevel.RESTRICTED }
        });

        results.push({
          context: ctx,
          maskedData: response.data.masked_data,
          maskingLevel: response.data.masking_level
        });
      }

      const maskingLevels = results.map(r => r.maskingLevel);
      const uniqueLevels = new Set(maskingLevels);
      
      console.log(`   上下文结果:`);
      results.forEach(r => {
        console.log(`     ${r.context.source}/${r.context.purpose}: ${r.maskingLevel}`);
      });

      expect(uniqueLevels.size).toBeGreaterThanOrEqual(2);
      console.log(`✅ 不同请求上下文数据隔离测试通过`);
    });

    test('请求ID追踪与数据隔离', async () => {
      console.log(`\n📋 执行测试: 请求ID追踪与数据隔离`);

      const testData = generateSensitiveDataRecord();
      const requestIds = Array.from({ length: 5 }, () => require('uuid').v4());
      const results = [];

      for (const requestId of requestIds) {
        const request = generateMaskingRequest({ 
          data: testData,
          context: { request_id: requestId } 
        });
        
        const response = await apiClient.maskData({
          ...request,
          auth_context: { permission_level: PermissionLevel.RESTRICTED }
        });

        results.push({
          requestId,
          traceId: response.data.trace_id,
          maskedData: response.data.masked_data
        });
      }

      const traceIds = results.map(r => r.traceId);
      const uniqueTraceIds = new Set(traceIds);
      expect(uniqueTraceIds.size).toBe(requestIds.length);

      console.log(`✅ 请求ID追踪与数据隔离测试通过`);
    });
  });

  describe('边缘场景测试', () => {
    test('空数据处理不影响其他请求', async () => {
      console.log(`\n📋 执行测试: 空数据处理不影响其他请求`);

      const normalData = generateSensitiveDataRecord();
      const emptyData = {};

      const [normalResponse, emptyResponse] = await Promise.all([
        apiClient.maskData({
          ...generateMaskingRequest({ data: normalData }),
          auth_context: { permission_level: PermissionLevel.RESTRICTED }
        }),
        apiClient.maskData({
          ...generateMaskingRequest({ data: emptyData }),
          auth_context: { permission_level: PermissionLevel.RESTRICTED }
        })
      ]);

      expect(normalResponse).toBeValidApiResponse();
      expect(normalResponse.code).toBe(200);
      expect(normalResponse.data.masked_data.email).not.toBe(normalData.email);

      expect(emptyResponse).toBeValidApiResponse();
      expect(emptyResponse.code).toBe(200);
      expect(Object.keys(emptyResponse.data.masked_data).length).toBe(0);

      console.log(`✅ 空数据处理不影响其他请求测试通过`);
    });

    test('超大字段值脱敏隔离', async () => {
      console.log(`\n📋 执行测试: 超大字段值脱敏隔离`);

      const longString = 'A'.repeat(10000);
      const testData = generateSensitiveDataRecord({
        extraFields: { long_field: longString }
      });

      const request = generateMaskingRequest({ 
        data: testData,
        fields: ['email', 'long_field']
      });
      
      const response = await apiClient.maskData({
        ...request,
        auth_context: { permission_level: PermissionLevel.RESTRICTED }
      });

      expect(response).toBeValidApiResponse();
      expect(response.code).toBe(200);
      expect(response.data.masked_data.email).not.toBe(testData.email);
      expect(response.data.masked_data.long_field).not.toBe(longString);
      expect(response.data.masked_data.long_field.length).toBeLessThan(longString.length);

      console.log(`✅ 超大字段值脱敏隔离测试通过`);
    });

    test('特殊字符数据脱敏正确性', async () => {
      console.log(`\n📋 执行测试: 特殊字符数据脱敏正确性`);

      const specialData = generateSensitiveDataRecord({
        email: 'test+special@domain.com',
        phone: '+86-138-0000-0000',
        name: '欧阳·振华'
      });

      const request = generateMaskingRequest({ data: specialData });
      const response = await apiClient.maskData({
        ...request,
        auth_context: { permission_level: PermissionLevel.RESTRICTED }
      });

      expect(response).toBeValidApiResponse();
      expect(response.code).toBe(200);
      expect(response.data.masked_data.email).not.toBe(specialData.email);
      expect(response.data.masked_data.phone).not.toBe(specialData.phone);
      expect(response.data.masked_data.name).not.toBe(specialData.name);

      console.log(`✅ 特殊字符数据脱敏正确性测试通过`);
    });
  });

  describe('性能与一致性压力测试', () => {
    test('1000次请求脱敏结果一致性', async () => {
      console.log(`\n📋 执行测试: 1000次请求脱敏结果一致性`);

      const testData = generateSensitiveDataRecord();
      const results = [];
      const batchSize = 100;

      for (let i = 0; i < 1000; i += batchSize) {
        const promises = [];
        for (let j = 0; j < batchSize; j++) {
          const request = generateMaskingRequest({ data: testData });
          promises.push(apiClient.maskData({
            ...request,
            auth_context: { permission_level: PermissionLevel.RESTRICTED }
          }));
        }
        
        const batchResults = await Promise.all(promises);
        results.push(...batchResults);
        
        console.log(`   进度: ${Math.min(i + batchSize, 1000)}/1000`);
      }

      const firstResult = JSON.stringify(results[0].data.masked_data);
      const inconsistent = results.filter(r => 
        JSON.stringify(r.data.masked_data) !== firstResult
      );

      expect(inconsistent.length).toBe(0);
      console.log(`✅ 1000次请求脱敏结果一致性测试通过，全部结果一致`);
    });
  });
});

function createAuthHeader(permissionLevel) {
  return `Bearer ${Buffer.from(JSON.stringify({ permission_level: permissionLevel })).toString('base64')}`;
}

function isValidMaskedEmail(masked, original) {
  if (masked === original) return false;
  if (masked.includes('***')) return true;
  if (masked.length !== original.length) return true;
  return masked !== original;
}

function isValidMaskedValue(value, fieldType) {
  const expectedReplacement = getReplacementForType(MaskingRuleType[fieldType.toUpperCase()]);
  return value !== undefined && value !== null;
}
