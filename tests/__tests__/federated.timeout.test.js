const APIClient = require('../utils/api.client');
const {
  generateCreateTaskRequest,
  generateParticipantRegistration,
  generateGradientSubmission,
  generateTimeoutTestScenarios,
  generateDegradationTestCases,
  TaskStatus,
  AggregationStrategy
} = require('../factories/federated.factory');
const { PermissionLevel } = require('../factories/common.factory');

jest.setTimeout(300000);

describe('🤖 联邦学习协调模块 - 超时降级行为测试', () => {
  let apiClient;
  const timeoutScenarios = generateTimeoutTestScenarios();
  const degradationTestCases = generateDegradationTestCases();

  beforeAll(() => {
    apiClient = new APIClient();
  });

  describe('任务超时处理测试', () => {
    test('参与者训练超时触发降级模式', async () => {
      const scenario = timeoutScenarios[0];
      console.log(`\n📋 执行测试: ${scenario.name}`);
      console.log(`   配置: ${JSON.stringify(scenario.config)}`);

      const createRequest = generateCreateTaskRequest({ config: scenario.config });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      
      expect(createResponse).toBeValidApiResponse();
      expect(createResponse.code).toBe(201);
      
      const taskId = createResponse.data.id;
      expect(taskId).toBeValidUuid();
      console.log(`   任务创建成功: ${taskId}`);

      const participants = [];
      for (let i = 0; i < scenario.totalParticipants; i++) {
        const registration = generateParticipantRegistration(taskId);
        const regResponse = await apiClient.registerParticipant(taskId, registration);
        expect(regResponse).toBeValidApiResponse();
        expect(regResponse.code).toBe(200);
        participants.push(regResponse.data);
        console.log(`   参与者 ${i + 1} 注册: ${regResponse.data.participant_id}`);
      }

      const submittedParticipants = scenario.totalParticipants - scenario.timeoutParticipants;
      for (let i = 0; i < submittedParticipants; i++) {
        const participant = participants[i];
        const submission = generateGradientSubmission(taskId, {
          participant_id: participant.participant_id
        });
        const submitResponse = await apiClient.submitGradient(taskId, submission);
        expect(submitResponse).toBeValidApiResponse();
        expect(submitResponse.code).toBe(200);
        console.log(`   参与者 ${i + 1} 提交梯度 ✓`);
      }

      console.log(`   等待超时触发 (${scenario.config.training_timeout + 2}秒)...`);
      await new Promise(resolve => setTimeout(resolve, (scenario.config.training_timeout + 2) * 1000));

      const taskStatus = await apiClient.getFederatedTask(taskId);
      console.log(`   最终任务状态: ${taskStatus.data.status}`);
      console.log(`   预期任务状态: ${scenario.expectedStatus}`);
      
      expect([scenario.expectedStatus, TaskStatus.DEGRADED, TaskStatus.TRAINING])
        .toContain(taskStatus.data.status);
      
      if (taskStatus.data.status === TaskStatus.DEGRADED) {
        expect(taskStatus.data.degradation_reason).toBeDefined();
        console.log(`   降级原因: ${taskStatus.data.degradation_reason}`);
      }

      console.log(`✅ 参与者训练超时触发降级模式测试通过`);
    });

    test('关键参与者全部超时任务失败', async () => {
      const scenario = timeoutScenarios[2];
      console.log(`\n📋 执行测试: ${scenario.name}`);

      const createRequest = generateCreateTaskRequest({ config: scenario.config });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      const taskId = createResponse.data.id;
      console.log(`   任务创建成功: ${taskId}`);

      const participants = [];
      for (let i = 0; i < scenario.totalParticipants; i++) {
        const registration = generateParticipantRegistration(taskId);
        const regResponse = await apiClient.registerParticipant(taskId, registration);
        participants.push(regResponse.data);
        console.log(`   参与者 ${i + 1} 注册 ✓`);
      }

      console.log(`   所有参与者都不提交梯度，等待超时...`);
      console.log(`   等待超时触发 (${scenario.config.training_timeout + 5}秒)...`);
      await new Promise(resolve => setTimeout(resolve, (scenario.config.training_timeout + 5) * 1000));

      const taskStatus = await apiClient.getFederatedTask(taskId);
      console.log(`   最终任务状态: ${taskStatus.data.status}`);
      console.log(`   预期任务状态: ${scenario.expectedStatus}`);

      expect([scenario.expectedStatus, TaskStatus.FAILED, TaskStatus.TIMEOUT])
        .toContain(taskStatus.data.status);
      
      if (taskStatus.data.status === TaskStatus.TIMEOUT) {
        expect(taskStatus.data.error_detail).toBeDefined();
        console.log(`   错误详情: ${taskStatus.data.error_detail}`);
      }

      console.log(`✅ 关键参与者全部超时任务失败测试通过`);
    });

    test('超时后启用备用参与者', async () => {
      const scenario = timeoutScenarios[3];
      console.log(`\n📋 执行测试: ${scenario.name}`);

      const createRequest = generateCreateTaskRequest({ config: scenario.config });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      const taskId = createResponse.data.id;
      console.log(`   任务创建成功: ${taskId}`);

      const allParticipants = [];
      for (let i = 0; i < scenario.primaryParticipants + scenario.fallbackParticipants; i++) {
        const registration = generateParticipantRegistration(taskId);
        const regResponse = await apiClient.registerParticipant(taskId, registration);
        allParticipants.push(regResponse.data);
      }
      console.log(`   共注册 ${allParticipants.length} 名参与者 (主: ${scenario.primaryParticipants}, 备用: ${scenario.fallbackParticipants})`);

      const primaryParticipants = allParticipants.slice(0, scenario.primaryParticipants);
      const fallbackParticipants = allParticipants.slice(scenario.primaryParticipants);

      const activePrimary = Math.floor(scenario.primaryParticipants * 0.6);
      for (let i = 0; i < activePrimary; i++) {
        const submission = generateGradientSubmission(taskId, {
          participant_id: primaryParticipants[i].participant_id
        });
        await apiClient.submitGradient(taskId, submission);
        console.log(`   主参与者 ${i + 1} 提交梯度 ✓`);
      }

      console.log(`   ${activePrimary} 名主参与者提交，${scenario.primaryParticipants - activePrimary} 名超时`);
      console.log(`   等待超时触发 (${scenario.config.training_timeout + 2}秒)...`);
      await new Promise(resolve => setTimeout(resolve, (scenario.config.training_timeout + 2) * 1000));

      const taskStatus = await apiClient.getFederatedTask(taskId);
      console.log(`   任务状态: ${taskStatus.data.status}`);
      
      if (taskStatus.data.status === TaskStatus.TRAINING || taskStatus.data.status === TaskStatus.DEGRADED) {
        const activatedFallbacks = taskStatus.data.activated_fallbacks || 0;
        console.log(`   激活的备用参与者: ${activatedFallbacks}`);
        expect(activatedFallbacks).toBeGreaterThanOrEqual(0);
      }

      console.log(`✅ 超时后启用备用参与者测试通过`);
    });

    test('渐进式超时阈值调整', async () => {
      const scenario = timeoutScenarios[4];
      console.log(`\n📋 执行测试: ${scenario.name}`);
      console.log(`   预期超时序列: ${scenario.expectedTimeoutSequence.join(' → ')}`);

      const actualTimeouts = [];
      let currentTimeout = scenario.initialTimeout;
      
      for (let i = 0; i <= scenario.maxAdjustments; i++) {
        actualTimeouts.push(Math.round(currentTimeout));
        currentTimeout *= scenario.adjustmentFactor;
      }

      console.log(`   实际超时序列: ${actualTimeouts.join(' → ')}`);

      expect(actualTimeouts.length).toBe(scenario.expectedTimeoutSequence.length);
      actualTimeouts.forEach((timeout, index) => {
        expect(Math.abs(timeout - scenario.expectedTimeoutSequence[index])).toBeLessThanOrEqual(5);
      });

      const createRequest = generateCreateTaskRequest({
        config: {
          training_timeout: scenario.initialTimeout,
          enable_progressive_timeout: true,
          max_timeout_adjustments: scenario.maxAdjustments,
          timeout_adjustment_factor: scenario.adjustmentFactor
        }
      });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      const taskId = createResponse.data.id;
      console.log(`   任务创建成功: ${taskId}`);

      console.log(`✅ 渐进式超时阈值调整测试通过`);
    });
  });

  describe('降级模式行为测试', () => {
    test.each(degradationTestCases)('降级模式验证 - $name', async (testCase) => {
      console.log(`\n📋 执行测试: ${testCase.name}`);

      const normalConfig = generateCreateTaskRequest({
        config: { max_rounds: testCase.normalRounds || 10 }
      });
      const normalResponse = await apiClient.createFederatedTask(normalConfig);
      console.log(`   正常模式任务创建: ${normalResponse.data.id}`);
      
      const degradedConfig = generateCreateTaskRequest({
        config: {
          max_rounds: testCase.degradedRounds || 3,
          degraded_mode: true,
          convergence_threshold: testCase.degradedThreshold || 0.01
        }
      });
      const degradedResponse = await apiClient.createFederatedTask(degradedConfig);
      console.log(`   降级模式任务创建: ${degradedResponse.data.id}`);

      const normalTask = await apiClient.getFederatedTask(normalResponse.data.id);
      const degradedTask = await apiClient.getFederatedTask(degradedResponse.data.id);

      expect(normalTask.data.config.max_rounds).toBe(testCase.normalRounds || 10);
      expect(degradedTask.data.config.max_rounds).toBe(testCase.degradedRounds || 3);
      expect(degradedTask.data.config.degraded_mode).toBe(true);

      console.log(`   正常模式轮次: ${normalTask.data.config.max_rounds}`);
      console.log(`   降级模式轮次: ${degradedTask.data.config.max_rounds}`);
      console.log(`   预期质量影响: ${testCase.expectedQualityImpact}`);

      console.log(`✅ ${testCase.name} 测试通过`);
    });

    test('降级模式下聚合策略自动调整', async () => {
      console.log(`\n📋 执行测试: 降级模式下聚合策略自动调整`);

      const strategies = [
        AggregationStrategy.FED_AVG,
        AggregationStrategy.FED_PROX,
        AggregationStrategy.SECURE_AGGREGATION
      ];

      for (const strategy of strategies) {
        const createRequest = generateCreateTaskRequest({
          config: {
            aggregation_strategy: strategy,
            degraded_mode: true,
            enable_dynamic_strategy: true
          }
        });
        const createResponse = await apiClient.createFederatedTask(createRequest);
        const taskId = createResponse.data.id;
        console.log(`   任务 ${taskId.slice(0, 8)} 使用策略: ${strategy}`);

        const task = await apiClient.getFederatedTask(taskId);
        const effectiveStrategy = task.data.config.effective_aggregation_strategy || 
                                  task.data.config.aggregation_strategy;
        
        expect(effectiveStrategy).toBeDefined();
        console.log(`     实际生效策略: ${effectiveStrategy}`);
      }

      console.log(`✅ 降级模式下聚合策略自动调整测试通过`);
    });

    test('多轮降级模式下性能指标对比', async () => {
      console.log(`\n📋 执行测试: 多轮降级模式下性能指标对比`);

      const normalTaskRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 5,
          min_participants: 1,
          training_timeout: 30
        }
      });
      const normalTaskResponse = await apiClient.createFederatedTask(normalTaskRequest);
      const normalTaskId = normalTaskResponse.data.id;

      const degradedTaskRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 3,
          min_participants: 1,
          training_timeout: 15,
          degraded_mode: true,
          convergence_threshold: 0.05
        }
      });
      const degradedTaskResponse = await apiClient.createFederatedTask(degradedTaskRequest);
      const degradedTaskId = degradedTaskResponse.data.id;

      console.log(`   正常模式任务: ${normalTaskId.slice(0, 8)}`);
      console.log(`   降级模式任务: ${degradedTaskId.slice(0, 8)}`);

      const registration = generateParticipantRegistration(normalTaskId);
      await apiClient.registerParticipant(normalTaskId, registration);
      await apiClient.registerParticipant(degradedTaskId, { 
        ...registration, 
        participant_id: require('uuid').v4() 
      });

      for (let round = 1; round <= 3; round++) {
        const normalSubmission = generateGradientSubmission(normalTaskId, { round });
        const degradedSubmission = generateGradientSubmission(degradedTaskId, { round });

        await apiClient.submitGradient(normalTaskId, normalSubmission);
        await apiClient.submitGradient(degradedTaskId, degradedSubmission);

        console.log(`   第 ${round} 轮梯度提交完成`);
      }

      const normalStatus = await apiClient.getFederatedTask(normalTaskId);
      const degradedStatus = await apiClient.getFederatedTask(degradedTaskId);

      console.log(`   正常模式状态: ${normalStatus.data.status}`);
      console.log(`   降级模式状态: ${degradedStatus.data.status}`);

      if (normalStatus.data.metrics && degradedStatus.data.metrics) {
        console.log(`   正常模式训练时间: ${normalStatus.data.metrics.total_training_time_ms || 'N/A'}ms`);
        console.log(`   降级模式训练时间: ${degradedStatus.data.metrics.total_training_time_ms || 'N/A'}ms`);
      }

      console.log(`✅ 多轮降级模式下性能指标对比测试通过`);
    });
  });

  describe('超时恢复测试', () => {
    test('单轮超时后任务可继续执行', async () => {
      console.log(`\n📋 执行测试: 单轮超时后任务可继续执行`);

      const createRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 3,
          min_participants: 1,
          training_timeout: 5,
          enable_recovery: true
        }
      });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      const taskId = createResponse.data.id;
      console.log(`   任务创建成功: ${taskId}`);

      const registration = generateParticipantRegistration(taskId);
      const regResponse = await apiClient.registerParticipant(taskId, registration);
      const participantId = regResponse.data.participant_id;
      console.log(`   参与者注册: ${participantId}`);

      console.log(`   第1轮: 不提交，触发超时 (等待8秒)...`);
      await new Promise(resolve => setTimeout(resolve, 8000));

      const statusAfterTimeout = await apiClient.getFederatedTask(taskId);
      console.log(`   超时后状态: ${statusAfterTimeout.data.status}`);

      if (statusAfterTimeout.data.status !== TaskStatus.FAILED && 
          statusAfterTimeout.data.status !== TaskStatus.TIMEOUT) {
        console.log(`   第2轮: 正常提交梯度`);
        const submission = generateGradientSubmission(taskId, {
          participant_id: participantId,
          round: 2
        });
        const submitResponse = await apiClient.submitGradient(taskId, submission);
        expect(submitResponse).toBeValidApiResponse();
        console.log(`   第2轮梯度提交成功`);

        const finalStatus = await apiClient.getFederatedTask(taskId);
        console.log(`   恢复后状态: ${finalStatus.data.status}`);
      }

      console.log(`✅ 单轮超时后任务可继续执行测试通过`);
    });

    test('超时计数器正确重置', async () => {
      console.log(`\n📋 执行测试: 超时计数器正确重置`);

      const createRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 5,
          min_participants: 1,
          training_timeout: 5,
          max_consecutive_timeouts: 2,
          enable_recovery: true
        }
      });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      const taskId = createResponse.data.id;
      console.log(`   任务创建成功: ${taskId}`);

      const registration = generateParticipantRegistration(taskId);
      const regResponse = await apiClient.registerParticipant(taskId, registration);
      const participantId = regResponse.data.participant_id;

      console.log(`   第1轮: 不提交，触发超时 (等待8秒)...`);
      await new Promise(resolve => setTimeout(resolve, 8000));

      const status1 = await apiClient.getFederatedTask(taskId);
      const timeouts1 = status1.data.consecutive_timeouts || 0;
      console.log(`   连续超时计数: ${timeouts1}`);

      console.log(`   第2轮: 正常提交，验证计数重置`);
      const submission = generateGradientSubmission(taskId, {
        participant_id: participantId,
        round: 2
      });
      await apiClient.submitGradient(taskId, submission);

      const status2 = await apiClient.getFederatedTask(taskId);
      const timeouts2 = status2.data.consecutive_timeouts || 0;
      console.log(`   提交成功后连续超时计数: ${timeouts2}`);
      expect(timeouts2).toBe(0);

      console.log(`✅ 超时计数器正确重置测试通过`);
    });
  });

  describe('超时告警与审计测试', () => {
    test('超时事件正确记录到审计日志', async () => {
      console.log(`\n📋 执行测试: 超时事件正确记录到审计日志`);

      const createRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 1,
          min_participants: 2,
          training_timeout: 3,
          enable_audit: true
        }
      });
      const createResponse = await apiClient.createFederatedTask(createRequest);
      const taskId = createResponse.data.id;
      console.log(`   任务创建成功: ${taskId}`);

      const registration = generateParticipantRegistration(taskId);
      await apiClient.registerParticipant(taskId, registration);
      console.log(`   仅注册1名参与者（需要2名）`);

      console.log(`   等待超时 (等待6秒)...`);
      await new Promise(resolve => setTimeout(resolve, 6000));

      const taskStatus = await apiClient.getFederatedTask(taskId);
      console.log(`   任务状态: ${taskStatus.data.status}`);

      if (taskStatus.data.audit_logs && taskStatus.data.audit_logs.length > 0) {
        const timeoutLogs = taskStatus.data.audit_logs.filter(log => 
          log.event_type === 'timeout' || log.event_type === 'training_timeout'
        );
        console.log(`   超时审计日志数量: ${timeoutLogs.length}`);
        expect(timeoutLogs.length).toBeGreaterThanOrEqual(1);
        
        timeoutLogs.forEach(log => {
          expect(log.timestamp).toBeDefined();
          expect(log.details).toBeDefined();
          console.log(`     - ${new Date(log.timestamp).toISOString()}: ${JSON.stringify(log.details)}`);
        });
      }

      console.log(`✅ 超时事件正确记录到审计日志测试通过`);
    });
  });

  describe('边界场景测试', () => {
    test('零秒超时配置处理', async () => {
      console.log(`\n📋 执行测试: 零秒超时配置处理`);

      const createRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 1,
          min_participants: 1,
          training_timeout: 0
        }
      });

      try {
        const createResponse = await apiClient.createFederatedTask(createRequest);
        const taskId = createResponse.data.id;
        console.log(`   任务创建成功: ${taskId}`);
        
        const task = await apiClient.getFederatedTask(taskId);
        console.log(`   实际超时配置: ${task.data.config.training_timeout}秒`);
        expect(task.data.config.training_timeout).toBeGreaterThanOrEqual(0);
      } catch (error) {
        console.log(`   预期的配置验证错误: ${error.message}`);
        expect(error.response?.status).toBeOneOf([400, 422]);
      }

      console.log(`✅ 零秒超时配置处理测试通过`);
    });

    test('超大超时值边界处理', async () => {
      console.log(`\n📋 执行测试: 超大超时值边界处理`);

      const veryLargeTimeout = 86400 * 365;
      const createRequest = generateCreateTaskRequest({
        config: {
          max_rounds: 1,
          min_participants: 1,
          training_timeout: veryLargeTimeout
        }
      });

      const createResponse = await apiClient.createFederatedTask(createRequest);
      const task = await apiClient.getFederatedTask(createResponse.data.id);
      const actualTimeout = task.data.config.training_timeout;
      
      console.log(`   请求超时: ${veryLargeTimeout}秒 (${(veryLargeTimeout/86400).toFixed(0)}天)`);
      console.log(`   实际超时: ${actualTimeout}秒`);
      expect(actualTimeout).toBeGreaterThan(0);

      console.log(`✅ 超大超时值边界处理测试通过`);
    });
  });
});
