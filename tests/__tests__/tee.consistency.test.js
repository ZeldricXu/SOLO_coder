const APIClient = require('../utils/api.client');
const {
  generateCreateEnclaveRequest,
  generateAttestationRequest,
  generateSecureFunctionPayload,
  generateSignedTEERequest,
  generateTEETestCases,
  EnclaveStatus
} = require('../factories/tee.factory');
const { PermissionLevel } = require('../factories/common.factory');

jest.setTimeout(120000);

describe('🔒 可信执行环境模块 - 数据一致性保障测试', () => {
  let apiClient;
  const testCases = generateTEETestCases();

  beforeAll(() => {
    apiClient = new APIClient();
  });

  describe('Enclave数据一致性测试', () => {
    test('同一enclave多次查询数据一致性', async () => {
      const scenario = testCases.consistencyScenarios[0];
      console.log(`\n📋 执行测试: ${scenario.name}`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      
      expect(createResponse).toBeValidApiResponse();
      expect(createResponse.code).toBe(201);
      
      const enclaveId = createResponse.data.id;
      expect(enclaveId).toBeValidUuid();

      const results = [];
      for (let i = 0; i < scenario.iterations; i++) {
        const response = await apiClient.getEnclave(enclaveId);
        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(200);
        results.push(response.data);
        
        if (i > 0) {
          expect(response.data.id).toBe(results[0].id);
          expect(response.data.name).toBe(results[0].name);
          expect(response.data.status).toBe(results[0].status);
          expect(JSON.stringify(response.data.config))
            .toBe(JSON.stringify(results[0].config));
        }
      }

      const uniqueResults = new Set(results.map(r => JSON.stringify(r)));
      const consistencyRate = uniqueResults.size / results.length;
      expect(consistencyRate).toBe(scenario.expectedConsistencyRate);
      
      console.log(`✅ 数据一致性验证通过，共 ${results.length} 次查询，一致率: ${(consistencyRate * 100).toFixed(2)}%`);
    });

    test('并发enclave创建状态一致性', async () => {
      const scenario = testCases.consistencyScenarios[1];
      console.log(`\n📋 执行测试: ${scenario.name}`);

      const createPromises = [];
      for (let i = 0; i < scenario.concurrency; i++) {
        const request = generateCreateEnclaveRequest();
        createPromises.push(apiClient.createEnclave(request));
      }

      const responses = await Promise.all(createPromises);
      
      responses.forEach((response, index) => {
        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(201);
        expect(response.data.status).toBe(scenario.expectedStatus);
        console.log(`   Enclave ${index + 1}: ${response.data.id} - ${response.data.status}`);
      });

      const ids = responses.map(r => r.data.id);
      const uniqueIds = new Set(ids);
      expect(uniqueIds.size).toBe(scenario.concurrency);
      
      console.log(`✅ 并发创建一致性验证通过，${scenario.concurrency} 个enclave全部创建成功`);
    });

    test('enclave状态流转一致性', async () => {
      const scenario = testCases.consistencyScenarios[2];
      console.log(`\n📋 执行测试: ${scenario.name}`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;
      
      const stateTransitions = [];
      
      const startResponse = await apiClient.startEnclave(enclaveId);
      stateTransitions.push({
        action: 'start',
        status: startResponse.data.status,
        timestamp: Date.now()
      });

      await new Promise(resolve => setTimeout(resolve, 1000));
      
      const getResponse1 = await apiClient.getEnclave(enclaveId);
      stateTransitions.push({
        action: 'get_after_start',
        status: getResponse1.data.status,
        timestamp: Date.now()
      });

      const stopResponse = await apiClient.stopEnclave(enclaveId);
      stateTransitions.push({
        action: 'stop',
        status: stopResponse.data.status,
        timestamp: Date.now()
      });

      await new Promise(resolve => setTimeout(resolve, 500));
      
      const getResponse2 = await apiClient.getEnclave(enclaveId);
      stateTransitions.push({
        action: 'get_after_stop',
        status: getResponse2.data.status,
        timestamp: Date.now()
      });

      const terminateResponse = await apiClient.terminateEnclave(enclaveId);
      stateTransitions.push({
        action: 'terminate',
        status: EnclaveStatus.TERMINATED,
        timestamp: Date.now()
      });

      console.log(`   状态流转记录:`);
      stateTransitions.forEach((t, i) => {
        console.log(`     ${i + 1}. ${t.action}: ${t.status} (${new Date(t.timestamp).toISOString()})`);
      });

      expect(stateTransitions[0].status).toBe(EnclaveStatus.RUNNING);
      expect(stateTransitions[2].status).toBe(EnclaveStatus.STOPPED);
      
      console.log(`✅ 状态流转一致性验证通过`);
    });
  });

  describe('远程证明数据一致性', () => {
    test('同一挑战多次证明结果一致性', async () => {
      console.log(`\n📋 执行测试: 同一挑战多次证明结果一致性`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const challenge = require('crypto').randomBytes(32).toString('hex');
      const attestationRequest = generateAttestationRequest(enclaveId, { challenge });

      const results = [];
      for (let i = 0; i < 5; i++) {
        const response = await apiClient.generateAttestation(enclaveId, attestationRequest);
        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(200);
        results.push(response.data);
      }

      results.forEach((result, i) => {
        expect(result.challenge).toBe(challenge);
        expect(result.enclave_id).toBe(enclaveId);
        expect(result.quote).toBeDefined();
        expect(result.signature).toBeDefined();
        expect(result.timestamp).toBeDefined();
        
        if (i > 0) {
          expect(result.quote).toBe(results[0].quote);
        }
      });

      console.log(`✅ 远程证明结果一致性验证通过`);
    });

    test('不同挑战生成不同证明', async () => {
      console.log(`\n📋 执行测试: 不同挑战生成不同证明`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const challenges = Array.from({ length: 3 }, () => 
        require('crypto').randomBytes(32).toString('hex')
      );
      
      const results = [];
      for (const challenge of challenges) {
        const attestationRequest = generateAttestationRequest(enclaveId, { challenge });
        const response = await apiClient.generateAttestation(enclaveId, attestationRequest);
        results.push(response.data);
      }

      const quotes = results.map(r => r.quote);
      const uniqueQuotes = new Set(quotes);
      expect(uniqueQuotes.size).toBe(challenges.length);

      console.log(`✅ 不同挑战生成不同证明验证通过`);
    });
  });

  describe('安全函数执行一致性', () => {
    test('相同输入多次执行结果一致性', async () => {
      console.log(`\n📋 执行测试: 相同输入多次执行结果一致性`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const payload = generateSecureFunctionPayload('compute_hash', { 
        data: 'test-data-12345' 
      });
      const signedRequest = generateSignedTEERequest(payload, PermissionLevel.ADMIN);

      const results = [];
      for (let i = 0; i < 10; i++) {
        const response = await apiClient.executeSecureFunction(enclaveId, signedRequest);
        expect(response).toBeValidApiResponse();
        expect(response.code).toBe(200);
        results.push(response.data);
        
        if (i > 0) {
          expect(response.data.result).toBe(results[0].result);
          expect(response.data.checksum).toBe(results[0].checksum);
        }
      }

      const uniqueResults = new Set(results.map(r => r.result));
      expect(uniqueResults.size).toBe(1);

      console.log(`✅ 安全函数执行一致性验证通过，10次执行结果完全一致`);
    });

    test('不同输入产生不同输出', async () => {
      console.log(`\n📋 执行测试: 不同输入产生不同输出`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const inputs = ['data-a', 'data-b', 'data-c'];
      const results = [];

      for (const input of inputs) {
        const payload = generateSecureFunctionPayload('compute_hash', { data: input });
        const signedRequest = generateSignedTEERequest(payload, PermissionLevel.ADMIN);
        const response = await apiClient.executeSecureFunction(enclaveId, signedRequest);
        results.push(response.data.result);
      }

      const uniqueResults = new Set(results);
      expect(uniqueResults.size).toBe(inputs.length);

      console.log(`✅ 不同输入产生不同输出验证通过`);
    });

    test('并发安全函数执行数据隔离', async () => {
      console.log(`\n📋 执行测试: 并发安全函数执行数据隔离`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const concurrentCount = 20;
      const promises = [];

      for (let i = 0; i < concurrentCount; i++) {
        const payload = generateSecureFunctionPayload('compute_hash', { 
          data: `concurrent-test-${i}` 
        });
        const signedRequest = generateSignedTEERequest(payload, PermissionLevel.ADMIN);
        promises.push(apiClient.executeSecureFunction(enclaveId, signedRequest));
      }

      const responses = await Promise.all(promises);
      
      const results = responses.map(r => r.data.result);
      const uniqueResults = new Set(results);
      
      expect(uniqueResults.size).toBe(concurrentCount);
      expect(responses.every(r => r.code === 200)).toBe(true);

      console.log(`✅ 并发执行数据隔离验证通过，${concurrentCount} 个并发请求全部成功且结果唯一`);
    });
  });

  describe('心跳与状态一致性', () => {
    test('心跳更新后状态一致性', async () => {
      console.log(`\n📋 执行测试: 心跳更新后状态一致性`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const beforeHeartbeat = await apiClient.getEnclave(enclaveId);
      const beforeTime = beforeHeartbeat.data.last_heartbeat;

      await new Promise(resolve => setTimeout(resolve, 1000));
      await apiClient.heartbeat(enclaveId);

      const afterHeartbeat = await apiClient.getEnclave(enclaveId);
      const afterTime = afterHeartbeat.data.last_heartbeat;

      expect(afterTime).not.toBe(beforeTime);
      expect(new Date(afterTime).getTime()).toBeGreaterThan(new Date(beforeTime).getTime());
      expect(afterHeartbeat.data.status).toBe(EnclaveStatus.RUNNING);

      console.log(`✅ 心跳更新后状态一致性验证通过`);
    });

    test('长时间运行enclave状态持久化一致性', async () => {
      console.log(`\n📋 执行测试: 长时间运行enclave状态持久化一致性`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;
      const initialState = JSON.stringify(createResponse.data);

      await apiClient.startEnclave(enclaveId);
      
      for (let i = 0; i < 5; i++) {
        await new Promise(resolve => setTimeout(resolve, 500));
        await apiClient.heartbeat(enclaveId);
      }

      const finalResponse = await apiClient.getEnclave(enclaveId);
      expect(finalResponse.data.id).toBe(createResponse.data.id);
      expect(finalResponse.data.name).toBe(createResponse.data.name);
      expect(finalResponse.data.status).toBe(EnclaveStatus.RUNNING);
      expect(JSON.stringify(finalResponse.data.config)).toBe(JSON.stringify(createResponse.data.config));

      console.log(`✅ 长时间运行状态持久化一致性验证通过`);
    });
  });

  describe('数据完整性校验', () => {
    test('二进制响应校验和验证', async () => {
      console.log(`\n📋 执行测试: 二进制响应校验和验证`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const payload = generateSecureFunctionPayload('secure_compute', { value: 42 });
      const signedRequest = generateSignedTEERequest(payload, PermissionLevel.ADMIN);
      const response = await apiClient.executeSecureFunction(enclaveId, signedRequest);

      const binaryData = response.data;
      expect(binaryData.data).toBeDefined();
      expect(binaryData.length).toBeDefined();
      expect(binaryData.checksum).toBeDefined();

      const decodedData = Buffer.from(binaryData.data, 'base64');
      expect(decodedData.length).toBe(binaryData.length);

      const crypto = require('crypto');
      const computedChecksum = crypto.createHash('sha256').update(decodedData).digest('hex');
      expect(computedChecksum).toBe(binaryData.checksum);

      console.log(`✅ 二进制响应校验和验证通过`);
    });

    test('签名验证防止数据篡改', async () => {
      console.log(`\n📋 执行测试: 签名验证防止数据篡改`);

      const createRequest = generateCreateEnclaveRequest();
      const createResponse = await apiClient.createEnclave(createRequest);
      const enclaveId = createResponse.data.id;

      await apiClient.startEnclave(enclaveId);
      await new Promise(resolve => setTimeout(resolve, 2000));

      const payload = generateSecureFunctionPayload('sensitive_operation', { amount: 1000 });
      const signedRequest = generateSignedTEERequest(payload, PermissionLevel.ADMIN);
      
      const tamperedRequest = JSON.parse(JSON.stringify(signedRequest));
      tamperedRequest.payload.data.amount = 999999;

      await expect(apiClient.executeSecureFunction(enclaveId, tamperedRequest))
        .rejects
        .toThrow();

      console.log(`✅ 签名验证防止数据篡改测试通过`);
    });
  });
});
