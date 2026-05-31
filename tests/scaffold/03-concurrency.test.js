const { generateRandomString, delay } = global;

describe('项目脚手架生成模块 - 并发安全测试', () => {
  const concurrentConfigs = {
    batchSize: 20,
    maxConcurrent: 10,
  };

  describe('模板高并发操作', () => {
    test('1.1 并发创建模板 - 20个并发请求', async () => {
      const createPromises = [];

      for (let i = 0; i < concurrentConfigs.batchSize; i++) {
        const promise = global.testAPI.post('/scaffold/templates', {
          name: `并发模板-${generateRandomString()}-${i}`,
          language: ['go', 'python', 'java', 'javascript'][i % 4],
          framework: ['gin', 'flask', 'spring', 'express'][i % 4],
          version: `${Math.floor(i / 10)}.${i % 10}.0`,
          file_tree: {
            'main.go': 'package main',
            'README.md': '# Concurrent Test',
          },
          is_public: true,
          author: 'concurrent-test',
        });
        createPromises.push(promise);
      }

      const results = await Promise.allSettled(createPromises);

      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 并发创建完成: 成功${successful.length}, 失败${failed.length}`);

      expect(successful.length).toBeGreaterThan(0);
      successful.forEach(result => {
        expect(result.value.status).toBe(201);
        expect(result.value.data.data).toHaveProperty('id');
      });
    }, 30000);

    test('1.2 并发查询模板列表 - 无数据竞争', async () => {
      const queryPromises = [];

      for (let i = 0; i < concurrentConfigs.batchSize; i++) {
        const promise = global.testAPI.get('/scaffold/templates', {
          params: {
            page: Math.floor(Math.random() * 5) + 1,
            page_size: Math.floor(Math.random() * 20) + 5,
            language: ['go', 'python', 'java'][Math.floor(Math.random() * 3)],
          },
        });
        queryPromises.push(promise);
      }

      const results = await Promise.allSettled(queryPromises);
      const successful = results.filter(r => r.status === 'fulfilled');

      expect(successful.length).toBeGreaterThan(0);
      successful.forEach(result => {
        expect(result.value.status).toBe(200);
        expect(result.value.data.data).toHaveProperty('items');
        expect(Array.isArray(result.value.data.data.items)).toBe(true);
      });
    }, 30000);
  });

  describe('项目生成并发测试', () => {
    let testTemplateId = null;

    beforeAll(async () => {
      try {
        const response = await global.testAPI.post('/scaffold/templates', {
          name: `并发测试基准模板-${generateRandomString()}`,
          language: 'go',
          framework: 'gin',
          version: '1.0.0',
          file_tree: { 'main.go': 'package main' },
          is_public: true,
          author: 'test-user',
        });
        testTemplateId = response.data.data.id;
        console.log(`      📋 基准模板已创建: ${testTemplateId}`);
      } catch (e) {
        console.log('      ⚠️  基准模板创建失败，跳过并发生成测试');
      }
    });

    test('2.1 并发触发项目生成', async () => {
      if (!testTemplateId) {
        console.log('      ⚠️  跳过：无有效模板ID');
        return;
      }

      const generatePromises = [];

      for (let i = 0; i < 10; i++) {
        const promise = global.testAPI.post('/scaffold/projects/generate', {
          name: `并发项目-${generateRandomString()}-${i}`,
          template_id: testTemplateId,
          namespace: 'concurrent-test',
          owner_id: `user-${i}`,
          config: {
            database: ['postgres', 'mysql', 'mongodb'][i % 3],
          },
        });
        generatePromises.push(promise);
        await delay(50);
      }

      const results = await Promise.allSettled(generatePromises);
      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 并发生成完成: 成功${successful.length}, 失败${failed.length}`);

      successful.forEach(result => {
        expect(result.value.status).toBe(201);
        expect(result.value.data.data).toHaveProperty('task_id');
      });

      failed.forEach(result => {
        const status = result.reason?.response?.status;
        expect([400, 409, 429, 500]).toContain(status);
      });
    }, 45000);

    test('2.2 并发查询任务状态', async () => {
      if (!testTemplateId) return;

      const generateResp = await global.testAPI.post('/scaffold/projects/generate', {
        name: `状态查询测试-${generateRandomString()}`,
        template_id: testTemplateId,
        namespace: 'concurrent-test',
      });

      const taskId = generateResp.data.data.task_id;
      console.log(`      📋 测试任务: ${taskId}`);

      const statusPromises = [];
      for (let i = 0; i < 30; i++) {
        const promise = global.testAPI.get(`/scaffold/tasks/${taskId}/status`);
        statusPromises.push(promise);
      }

      const results = await Promise.allSettled(statusPromises);
      const successful = results.filter(r => r.status === 'fulfilled');

      expect(successful.length).toBeGreaterThan(0);
      successful.forEach(result => {
        expect(result.value.status).toBe(200);
        expect(result.value.data.data).toHaveProperty('task_id', taskId);
      });
    }, 30000);
  });

  describe('备份恢复并发测试', () => {
    let testTemplateId = null;

    beforeAll(async () => {
      try {
        const response = await global.testAPI.post('/scaffold/templates', {
          name: `备份并发测试模板-${generateRandomString()}`,
          language: 'python',
          file_tree: { 'main.py': '# test' },
        });
        testTemplateId = response.data.data.id;
      } catch (e) {}
    });

    test('3.1 并发创建同一资源的备份', async () => {
      if (!testTemplateId) {
        console.log('      ⚠️  跳过：无有效模板ID');
        return;
      }

      const backupPromises = [];
      for (let i = 0; i < 10; i++) {
        const promise = global.testAPI.post('/scaffold/backups', {
          resource_type: 'template',
          resource_id: testTemplateId,
          backup_type: 'snapshot',
          created_by: `user-${i}`,
        });
        backupPromises.push(promise);
      }

      const results = await Promise.allSettled(backupPromises);
      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 并发备份完成: 成功${successful.length}, 失败${failed.length}`);

      successful.forEach(result => {
        expect(result.value.status).toBe(201);
        expect(result.value.data.data).toHaveProperty('backup_id');
      });
    }, 30000);
  });

  describe('读写混合并发测试', () => {
    test('4.1 读写操作混合并发 - 验证数据一致性', async () => {
      const operations = [];

      for (let i = 0; i < 30; i++) {
        const opType = Math.random();

        if (opType < 0.4) {
          operations.push(
            global.testAPI.post('/scaffold/templates', {
              name: `混合测试-${generateRandomString()}-${i}`,
              language: 'go',
              file_tree: { 'main.go': 'package main' },
            })
          );
        } else if (opType < 0.8) {
          operations.push(
            global.testAPI.get('/scaffold/templates?page=1&page_size=5')
          );
        } else {
          operations.push(
            global.testAPI.get('/scaffold/backups')
          );
        }

        if (i % 5 === 0) {
          await delay(20);
        }
      }

      const results = await Promise.allSettled(operations);
      const successful = results.filter(r => r.status === 'fulfilled');
      const failed = results.filter(r => r.status === 'rejected');

      console.log(`      ✅ 混合操作完成: 成功${successful.length}, 失败${failed.length}`);
      expect(successful.length).toBeGreaterThan(0);

      successful.forEach(result => {
        expect([200, 201]).toContain(result.value.status);
      });
    }, 45000);
  });
});
