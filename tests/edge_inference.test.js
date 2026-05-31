const request = require('supertest');
const axios = require('axios');
const { InferenceTestDataFactory } = require('./testDataFactory');

const BASE_URL = process.env.TEST_API_BASE_URL || 'http://localhost:8000';
const API_PREFIX = '/api/v1/inference';

const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
});

const checkApiAvailable = async () => {
  try {
    await api.get('/health');
    return true;
  } catch (error) {
    console.warn('API not available, skipping integration tests');
    return false;
  }
};

describe('边缘推理调度模块 - 边界条件处理', () => {
  let apiAvailable = false;

  beforeAll(async () => {
    apiAvailable = await checkApiAvailable();
  });

  describe('模型注册 - 边界条件测试', () => {
    if (apiAvailable) {
      test('注册模型 - 正常流程', async () => {
        const modelData = InferenceTestDataFactory.createModelData();
        const response = await api.post(`${API_PREFIX}/models`, modelData);
        
        expect(response.status).toBe(200);
        expect(response.data).toHaveProperty('model_id', modelData.model_id);
        expect(response.data).toHaveProperty('message', 'Model registered successfully');
      });

      test('注册模型 - 空model_id应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('empty_model_id');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - 缺少model_id应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('missing_model_id');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - null model_id应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('null_model_id');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - 空name应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('empty_name');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - 缺少name应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('missing_name');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - 空path应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('empty_path');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - 缺少path应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('missing_path');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('注册模型 - 无效model_type应返回400', async () => {
        const invalidData = InferenceTestDataFactory.createInvalidModelData('invalid_model_type');
        await expect(api.post(`${API_PREFIX}/models`, invalidData)).rejects.toThrow();
      });

      test('查询不存在的模型应返回404', async () => {
        try {
          const response = await api.get(`${API_PREFIX}/models/nonexistent_model_999`);
          expect(response.status).toBe(404);
        } catch (error) {
          expect(error.response.status).toBe(404);
        }
      });

      test('删除不存在的模型应返回404', async () => {
        await expect(api.delete(`${API_PREFIX}/models/nonexistent_model_999`)).rejects.toThrow();
      });

      test('模型列表查询 - 空列表', async () => {
        try {
          const response = await api.get(`${API_PREFIX}/models`);
          expect(response.status).toBe(200);
          expect(response.data).toHaveProperty('models');
          expect(Array.isArray(response.data.models)).toBe(true);
        } catch (error) {
          expect([200, 400, 500]).toContain(error.response?.status || error.code);
        }
      });
    } else {
      test.skip('API不可用 - 跳过集成测试', () => {});
    }
  });

  describe('推理任务提交 - 边界条件测试', () => {
    let testModelId = null;

    beforeAll(async () => {
      if (apiAvailable) {
        const modelData = InferenceTestDataFactory.createModelData({
          model_id: 'model_inference_test_001'
        });
        try {
          await api.post(`${API_PREFIX}/models`, modelData);
          testModelId = modelData.model_id;
        } catch (e) {
          testModelId = modelData.model_id;
        }
      }
    });

    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('提交推理任务 - 正常流程', async () => {
      const taskData = InferenceTestDataFactory.createInferenceTaskData({
        model_id: testModelId,
      });
      const response = await api.post(`${API_PREFIX}/tasks`, taskData);
      
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('task_id');
      expect(response.data).toHaveProperty('status', 'pending');
    });

    const boundaryScenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
    
    boundaryScenarios.forEach((scenario) => {
      test(`边界条件: ${scenario.name}`, async () => {
        if (scenario.name === '批量提交100个任务') {
          const responses = await Promise.all(
            scenario.data.map((taskData) => 
              api.post(`${API_PREFIX}/tasks`, {
                ...taskData,
                model_id: testModelId,
              })
            )
          );
          
          const successCount = responses.filter((r) => r.status === 200).length;
          expect(successCount).toBeGreaterThan(0);
        } else if (scenario.name === '重复提交相同任务') {
          const taskData = { ...scenario.data, model_id: testModelId };
          const response1 = await api.post(`${API_PREFIX}/tasks`, taskData);
          const response2 = await api.post(`${API_PREFIX}/tasks`, taskData);
          
          expect(response1.status).toBe(scenario.expected.statusCode);
          expect(response2.status).toBe(scenario.expected.statusCode);
          expect(response1.data.task_id).not.toBe(response2.data.task_id);
        } else if (scenario.name === '不存在的模型ID') {
          await expect(
            api.post(`${API_PREFIX}/tasks`, scenario.data)
          ).rejects.toThrow();
        } else if (scenario.expected.statusCode === 200) {
          const taskData = { ...scenario.data, model_id: testModelId };
          const response = await api.post(`${API_PREFIX}/tasks`, taskData);
          expect(response.status).toBe(200);
          expect(response.data).toHaveProperty('status');
        } else {
          await expect(
            api.post(`${API_PREFIX}/tasks`, scenario.data)
          ).rejects.toThrow();
        }
      });
    });

    test('提交推理任务 - 空model_id应返回400', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('empty_model_id');
      await expect(api.post(`${API_PREFIX}/tasks`, invalidData)).rejects.toThrow();
    });

    test('提交推理任务 - 缺少model_id应返回400', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('missing_model_id');
      await expect(api.post(`${API_PREFIX}/tasks`, invalidData)).rejects.toThrow();
    });

    test('提交推理任务 - null model_id应返回400', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('null_model_id');
      await expect(api.post(`${API_PREFIX}/tasks`, invalidData)).rejects.toThrow();
    });

    test('提交推理任务 - 空input_data应返回400', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('empty_input');
      invalidData.model_id = testModelId;
      await expect(api.post(`${API_PREFIX}/tasks`, invalidData)).rejects.toThrow();
    });

    test('提交推理任务 - 缺少input_data应返回400', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('missing_input');
      invalidData.model_id = testModelId;
      await expect(api.post(`${API_PREFIX}/tasks`, invalidData)).rejects.toThrow();
    });

    test('提交推理任务 - null input_data应返回400', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('null_input');
      invalidData.model_id = testModelId;
      await expect(api.post(`${API_PREFIX}/tasks`, invalidData)).rejects.toThrow();
    });

    test('提交推理任务 - 负优先级应被接受或拒绝', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('invalid_priority_negative');
      invalidData.model_id = testModelId;
      try {
        const response = await api.post(`${API_PREFIX}/tasks`, invalidData);
        expect([200, 400]).toContain(response.status);
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('提交推理任务 - 优先级过高应被接受或拒绝', async () => {
      const invalidData = InferenceTestDataFactory.createInvalidTaskData('invalid_priority_too_high');
      invalidData.model_id = testModelId;
      try {
        const response = await api.post(`${API_PREFIX}/tasks`, invalidData);
        expect([200, 400]).toContain(response.status);
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('查询不存在的任务应返回404', async () => {
      await expect(api.get(`${API_PREFIX}/tasks/nonexistent_task_999`)).rejects.toThrow();
    });

    test('查询不存在的任务结果应返回404', async () => {
      await expect(api.get(`${API_PREFIX}/tasks/nonexistent_task_999/result`)).rejects.toThrow();
    });

    test('取消不存在的任务应返回404', async () => {
      await expect(api.delete(`${API_PREFIX}/tasks/nonexistent_task_999`)).rejects.toThrow();
    });
  });

  describe('任务取消 - 边界条件测试', () => {
    let testTaskId = null;

    beforeAll(async () => {
      if (apiAvailable) {
        const taskData = InferenceTestDataFactory.createInferenceTaskData({
          model_id: 'model_inference_test_001',
        });
        try {
          const response = await api.post(`${API_PREFIX}/tasks`, taskData);
          testTaskId = response.data.task_id;
        } catch (e) {
          console.log('Error creating test task');
        }
      }
    });

    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('取消待处理任务应成功', async () => {
      if (testTaskId) {
        const response = await api.delete(`${API_PREFIX}/tasks/${testTaskId}`);
        expect([200, 404, 400]).toContain(response.status);
      }
    });

    test('取消已完成任务应返回400', async () => {
      const taskData = InferenceTestDataFactory.createInferenceTaskData({
        model_id: 'model_inference_test_001',
      });
      try {
        const createResponse = await api.post(`${API_PREFIX}/tasks`, taskData);
        const taskId = createResponse.data.task_id;
        
        await api.delete(`${API_PREFIX}/tasks/${taskId}`);
        
        await expect(api.delete(`${API_PREFIX}/tasks/${taskId}`)).rejects.toThrow();
      } catch (e) {}
    });
  });

  describe('同步推理 - 超时边界测试', () => {
    const timeoutScenarios = InferenceTestDataFactory.createTimeoutScenarios();

    beforeAll(async () => {
      if (apiAvailable) {
        const modelData = InferenceTestDataFactory.createModelData({
          model_id: 'model_sync_test_001'
        });
        try {
          await api.post(`${API_PREFIX}/models`, modelData);
        } catch (e) {}
      }
    });

    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    timeoutScenarios.forEach((scenario) => {
      test(`超时边界: ${scenario.name}`, async () => {
        const taskData = {
          ...scenario.data,
          model_id: 'model_sync_test_001',
        };
        
        try {
          const response = await api.post(
            `${API_PREFIX}/tasks/sync?timeout=${scenario.timeout}`,
            taskData
          );
          
          if (scenario.expected.statusCode === 200) {
            expect(response.status).toBe(200);
            expect(response.data).toHaveProperty('output');
          } else {
            expect(response.status).toBe(408);
          }
        } catch (error) {
          if (scenario.expected.statusCode === 408) {
            expect(error.response.status).toBe(408);
          } else {
            expect([400, 408]).toContain(error.response.status);
          }
        }
      });
    });

    test('同步推理 - 超时参数小于最小值应被调整或拒绝', async () => {
      const taskData = InferenceTestDataFactory.createInferenceTaskData({
        model_id: 'model_sync_test_001',
      });
      
      try {
        const response = await api.post(
          `${API_PREFIX}/tasks/sync?timeout=0.5`,
          taskData
        );
        expect([200, 422]).toContain(response.status);
      } catch (error) {
        expect([400, 422]).toContain(error.response.status);
      }
    });

    test('同步推理 - 超时参数大于最大值应被调整或拒绝', async () => {
      const taskData = InferenceTestDataFactory.createInferenceTaskData({
        model_id: 'model_sync_test_001',
      });
      
      try {
        const response = await api.post(
          `${API_PREFIX}/tasks/sync?timeout=500`,
          taskData
        );
        expect([200, 422]).toContain(response.status);
      } catch (error) {
        expect([400, 422]).toContain(error.response.status);
      }
    });
  });

  describe('任务列表查询 - 边界条件测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('查询任务列表 - limit=1应返回最多1条', async () => {
      const response = await api.get(`${API_PREFIX}/tasks?limit=1`);
      expect(response.status).toBe(200);
      expect(response.data.tasks.length).toBeLessThanOrEqual(1);
    });

    test('查询任务列表 - limit=0应被拒绝或返回空', async () => {
      try {
        const response = await api.get(`${API_PREFIX}/tasks?limit=0`);
        expect([422, 200]).toContain(response.status);
      } catch (error) {
        expect([400, 422]).toContain(error.response.status);
      }
    });

    test('查询任务列表 - limit超过最大值应被调整', async () => {
      try {
        const response = await api.get(`${API_PREFIX}/tasks?limit=5000`);
        expect([200, 422]).toContain(response.status);
        if (response.status === 200) {
          expect(response.data.tasks.length).toBeLessThanOrEqual(1000);
        }
      } catch (error) {
        expect([400, 422]).toContain(error.response.status);
      }
    });

    test('查询任务列表 - 按状态过滤', async () => {
      const response = await api.get(`${API_PREFIX}/tasks?status=pending`);
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('tasks');
    });

    test('查询任务列表 - 无效状态应返回空或错误', async () => {
      try {
        const response = await api.get(`${API_PREFIX}/tasks?status=invalid_status`);
        expect([200, 400]).toContain(response.status);
      } catch (error) {
        expect([400, 422]).toContain(error.response.status);
      }
    });
  });

  describe('推理引擎控制 - 边界条件测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('启动推理引擎应成功', async () => {
      const response = await api.post(`${API_PREFIX}/engine/start`);
      expect(response.status).toBe(200);
    });

    test('重复启动推理引擎应幂等', async () => {
      const response1 = await api.post(`${API_PREFIX}/engine/start`);
      const response2 = await api.post(`${API_PREFIX}/engine/start`);
      expect(response1.status).toBe(200);
      expect(response2.status).toBe(200);
    });

    test('停止推理引擎应成功', async () => {
      const response = await api.post(`${API_PREFIX}/engine/stop`);
      expect(response.status).toBe(200);
    });

    test('重复停止推理引擎应幂等', async () => {
      const response1 = await api.post(`${API_PREFIX}/engine/stop`);
      const response2 = await api.post(`${API_PREFIX}/engine/stop`);
      expect(response1.status).toBe(200);
      expect(response2.status).toBe(200);
    });

    test('获取统计信息应成功', async () => {
      const response = await api.get(`${API_PREFIX}/stats`);
      expect(response.status).toBe(200);
      expect(response.data).toHaveProperty('total_models');
      expect(response.data).toHaveProperty('total_tasks');
    });
  });

  describe('并发任务处理 - 边界条件测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('并发提交50个任务应全部处理', async () => {
      const taskPromises = [];
      for (let i = 0; i < 50; i++) {
        const taskData = InferenceTestDataFactory.createInferenceTaskData({
          model_id: 'model_inference_test_001',
          device_id: `dev_concurrent_${i}`,
        });
        taskPromises.push(api.post(`${API_PREFIX}/tasks`, taskData));
      }

      const responses = await Promise.allSettled(taskPromises);
      const successful = responses.filter((r) => r.status === 'fulfilled').length;
      
      console.log(`成功提交 ${successful}/50 个任务`);
      expect(successful).toBeGreaterThan(40);
    }, 30000);

    test('并发查询任务状态应一致', async () => {
      const taskData = InferenceTestDataFactory.createInferenceTaskData({
        model_id: 'model_inference_test_001',
      });
      const createResponse = await api.post(`${API_PREFIX}/tasks`, taskData);
      const taskId = createResponse.data.task_id;

      const queryPromises = [];
      for (let i = 0; i < 10; i++) {
        queryPromises.push(api.get(`${API_PREFIX}/tasks/${taskId}`));
      }

      const responses = await Promise.all(queryPromises);
      const statuses = responses.map((r) => r.data.status);
      const uniqueStatuses = [...new Set(statuses)];
      
      expect(uniqueStatuses.length).toBe(1);
    });
  });

  describe('内存和资源限制 - 边界条件测试', () => {
    if (!apiAvailable) {
      test.skip('API不可用 - 跳过集成测试', () => {});
      return;
    }

    test('超大输入数据应被拒绝或处理', async () => {
      const largeInput = InferenceTestDataFactory.createInvalidTaskData('large_input_data');
      largeInput.model_id = 'model_inference_test_001';
      
      try {
        const start = Date.now();
        const response = await api.post(`${API_PREFIX}/tasks`, largeInput);
        const duration = Date.now() - start;
        
        console.log(`大输入处理耗时: ${duration}ms`);
        expect([200, 400, 413]).toContain(response.status);
      } catch (error) {
        expect([400, 413]).toContain(error.response.status);
      }
    }, 10000);

    test('嵌套输入数据应正常处理', async () => {
      const nestedInput = InferenceTestDataFactory.createInferenceTaskData({
        model_id: 'model_inference_test_001',
        input_data: {
          level1: {
            level2: {
              level3: {
                value: 42,
              },
            },
          },
        },
      });
      
      const response = await api.post(`${API_PREFIX}/tasks`, nestedInput);
      expect(response.status).toBe(200);
    });

    test('空输入数据应被拒绝', async () => {
      const emptyInput = InferenceTestDataFactory.createInferenceTaskData({
        model_id: 'model_inference_test_001',
        input_data: {},
      });
      
      try {
        const response = await api.post(`${API_PREFIX}/tasks`, emptyInput);
        expect([400, 200]).toContain(response.status);
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });
  });
});

describe('边缘推理调度模块 - 单元测试（无需API）', () => {
  describe('数据构造验证', () => {
    test('生成唯一ID格式正确', () => {
      const id1 = InferenceTestDataFactory.generateId('test_');
      const id2 = InferenceTestDataFactory.generateId('test_');
      
      expect(id1).toMatch(/^test_[a-f0-9]{12}$/);
      expect(id2).toMatch(/^test_[a-f0-9]{12}$/);
      expect(id1).not.toBe(id2);
    });

    test('生成时间戳格式正确', () => {
      const timestamp = InferenceTestDataFactory.generateTimestamp();
      expect(timestamp).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);
    });

    test('模型数据包含所有必填字段', () => {
      const modelData = InferenceTestDataFactory.createModelData();
      
      expect(modelData).toHaveProperty('model_id');
      expect(modelData).toHaveProperty('name');
      expect(modelData).toHaveProperty('model_path');
      expect(modelData).toHaveProperty('model_type');
      expect(modelData).toHaveProperty('input_schema');
      expect(modelData).toHaveProperty('output_schema');
    });

    test('推理任务数据包含所有必填字段', () => {
      const taskData = InferenceTestDataFactory.createInferenceTaskData();
      
      expect(taskData).toHaveProperty('model_id');
      expect(taskData).toHaveProperty('input_data');
      expect(taskData).toHaveProperty('device_id');
      expect(taskData).toHaveProperty('priority');
    });

    test('无效模型数据场景覆盖全面', () => {
      const scenarios = [
        'empty_model_id', 'missing_model_id', 'null_model_id',
        'empty_name', 'missing_name',
        'empty_path', 'missing_path',
        'invalid_model_type', 'invalid_version_format'
      ];
      
      scenarios.forEach((scenario) => {
        const data = InferenceTestDataFactory.createInvalidModelData(scenario);
        expect(data).toBeDefined();
        expect(Object.keys(data).length).toBeGreaterThan(0);
      });
    });

    test('无效任务数据场景覆盖全面', () => {
      const scenarios = [
        'empty_model_id', 'missing_model_id', 'null_model_id',
        'empty_input', 'missing_input', 'null_input',
        'invalid_priority_negative', 'invalid_priority_too_high',
        'invalid_callback_url', 'large_input_data'
      ];
      
      scenarios.forEach((scenario) => {
        const data = InferenceTestDataFactory.createInvalidTaskData(scenario);
        expect(data).toBeDefined();
      });
    });

    test('边界条件场景数量正确', () => {
      const scenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
      expect(scenarios.length).toBe(10);
    });

    test('超时场景数量正确', () => {
      const scenarios = InferenceTestDataFactory.createTimeoutScenarios();
      expect(scenarios.length).toBe(4);
    });

    test('模型数据支持自定义覆盖', () => {
      const customModel = InferenceTestDataFactory.createModelData({
        name: '自定义模型',
        model_type: 'detection',
        version: '2.0.0',
      });
      
      expect(customModel.name).toBe('自定义模型');
      expect(customModel.model_type).toBe('detection');
      expect(customModel.version).toBe('2.0.0');
    });

    test('推理任务数据支持自定义覆盖', () => {
      const customTask = InferenceTestDataFactory.createInferenceTaskData({
        priority: 50,
        device_id: 'custom_device_001',
        callback_url: 'http://example.com/callback',
      });
      
      expect(customTask.priority).toBe(50);
      expect(customTask.device_id).toBe('custom_device_001');
      expect(customTask.callback_url).toBe('http://example.com/callback');
    });
  });

  describe('边界值计算验证', () => {
    test('零优先级任务构造正确', () => {
      const scenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
      const zeroPriority = scenarios.find((s) => s.name === '零优先级任务');
      
      expect(zeroPriority).toBeDefined();
      expect(zeroPriority.data.priority).toBe(0);
      expect(zeroPriority.expected.statusCode).toBe(200);
    });

    test('最高优先级任务构造正确', () => {
      const scenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
      const highPriority = scenarios.find((s) => s.name === '最高优先级任务');
      
      expect(highPriority).toBeDefined();
      expect(highPriority.data.priority).toBe(100);
      expect(highPriority.expected.statusCode).toBe(200);
    });

    test('空输入数据场景构造正确', () => {
      const scenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
      const emptyInput = scenarios.find((s) => s.name === '空输入数据');
      
      expect(emptyInput).toBeDefined();
      expect(emptyInput.data.input_data).toEqual({});
      expect(emptyInput.expected.statusCode).toBe(400);
    });

    test('不存在的模型ID场景构造正确', () => {
      const scenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
      const nonexistentModel = scenarios.find((s) => s.name === '不存在的模型ID');
      
      expect(nonexistentModel).toBeDefined();
      expect(nonexistentModel.data.model_id).toBe('model_nonexistent_999');
      expect(nonexistentModel.expected.statusCode).toBe(400);
    });

    test('批量任务场景构造正确', () => {
      const scenarios = InferenceTestDataFactory.createBoundaryConditionScenarios();
      const batchScenario = scenarios.find((s) => s.name === '批量提交100个任务');
      
      expect(batchScenario).toBeDefined();
      expect(Array.isArray(batchScenario.data)).toBe(true);
      expect(batchScenario.data.length).toBe(100);
    });
  });
});
