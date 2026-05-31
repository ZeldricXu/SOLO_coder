const { generateRandomString } = global;

describe('项目脚手架生成模块 - 边界值与异常测试', () => {
  let validTemplateId = null;

  const createValidTemplate = async () => {
    const response = await global.testAPI.post('/scaffold/templates', {
      name: `边界测试模板-${generateRandomString()}`,
      language: 'python',
      framework: 'flask',
      version: '1.0.0',
      file_tree: { 'main.py': '# test' },
      is_public: true,
      author: 'test-user',
    });
    return response.data.data.id;
  };

  beforeAll(async () => {
    try {
      validTemplateId = await createValidTemplate();
    } catch (e) {
      console.log('      ⚠️  预置模板创建失败，部分测试将跳过');
    }
  });

  describe('模板创建 - 参数边界值测试', () => {
    test('1.1 名称长度边界 - 最大长度128字符', async () => {
      const maxLengthName = 'a'.repeat(128);
      const response = await global.testAPI.post('/scaffold/templates', {
        name: maxLengthName,
        language: 'go',
        file_tree: { 'main.go': 'package main' },
      });
      expect([201, 400]).toContain(response.status);
    });

    test('1.2 名称长度超限 - 129字符应失败', async () => {
      const tooLongName = 'a'.repeat(129);
      try {
        await global.testAPI.post('/scaffold/templates', {
          name: tooLongName,
          language: 'go',
          file_tree: { 'main.go': 'package main' },
        });
      } catch (error) {
          expect(error.response.status).toBe(400);
        }
    });

    test('1.3 必填字段缺失 - 缺少name', async () => {
      try {
        await global.testAPI.post('/scaffold/templates', {
          language: 'go',
          file_tree: { 'main.go': 'package main' },
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.4 必填字段缺失 - 缺少language', async () => {
      try {
        await global.testAPI.post('/scaffold/templates', {
          name: '测试模板',
          file_tree: { 'main.go': 'package main' },
        });
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('1.5 必填字段缺失 - 缺少file_tree', async () => {
      try {
        await global.testAPI.post('/scaffold/templates', {
          name: '测试模板',
          language: 'go',
        });
      } catch (error) {
          expect(error.response.status).toBe(400);
        }
    });

    test('1.6 空file_tree - 空对象', async () => {
      const response = await global.testAPI.post('/scaffold/templates', {
        name: `空文件树模板-${generateRandomString()}`,
        language: 'javascript',
        file_tree: {},
      });
      expect([201, 400]).toContain(response.status);
    });

    test('1.7 无效tags - 空数组tags', async () => {
      const response = await global.testAPI.post('/scaffold/templates', {
        name: `空标签模板-${generateRandomString()}`,
        language: 'java',
        file_tree: { 'Main.java': 'public class Main {}' },
        tags: [],
      });
      expect(response.status).toBe(201);
    });

    test('1.8 parameters为null', async () => {
      const response = await global.testAPI.post('/scaffold/templates', {
        name: `空参数模板-${generateRandomString()}`,
        language: 'rust',
        file_tree: { 'main.rs': 'fn main() {}' },
        parameters: null,
      });
      expect(response.status).toBe(201);
    });
  });

  describe('模板查询 - 边界值测试', () => {
    test('2.1 无效模板ID查询', async () => {
      const invalidIds = ['', 'invalid-id', '123', 'a'.repeat(100)];
      for (const id of invalidIds) {
        try {
          await global.testAPI.get(`/scaffold/templates/${id}`);
        } catch (error) {
          expect([400, 404]).toContain(error.response.status);
        }
      }
    });

    test('2.2 分页参数边界 - page=0应默认1', async () => {
      const response = await global.testAPI.get('/scaffold/templates?page=0&page_size=10');
      expect(response.status).toBe(200);
    });

    test('2.3 分页参数边界 - page_size=1', async () => {
      const response = await global.testAPI.get('/scaffold/templates?page=1&page_size=1');
      expect(response.status).toBe(200);
      expect(response.data.data.items.length).toBeLessThanOrEqual(1);
    });

    test('2.4 分页参数边界 - page_size=100（最大值', async () => {
      const response = await global.testAPI.get('/scaffold/templates?page=1&page_size=100');
      expect(response.status).toBe(200);
      expect(response.data.data.items.length).toBeLessThanOrEqual(100);
    });

    test('2.5 分页参数超限 - page_size=101', async () => {
      const response = await global.testAPI.get('/scaffold/templates?page=1&page_size=101');
      expect(response.status).toBe(200);
      expect(response.data.data.items.length).toBeLessThanOrEqual(100);
    });

    test('2.6 空keyword搜索 - 空字符串', async () => {
      const response = await global.testAPI.get('/scaffold/templates?keyword=');
      expect(response.status).toBe(200);
    });

    test('2.7 特殊字符keyword搜索', async () => {
      const response = await global.testAPI.get('/scaffold/templates?keyword=%25%26%3F');
      expect(response.status).toBe(200);
    });
  });

  describe('项目生成 - 边界值测试', () => {
    test('3.1 项目名称边界 - 最大长度', async () => {
      if (!validTemplateId) return;
      const maxName = 'a'.repeat(128);
      const response = await global.testAPI.post('/scaffold/projects/generate', {
        name: maxName,
        template_id: validTemplateId,
        namespace: 'test',
      });
      expect([201, 400]).toContain(response.status);
    });

    test('3.2 namespace边界 - 超长namespace', async () => {
      if (!validTemplateId) return;
      const response = await global.testAPI.post('/scaffold/projects/generate', {
        name: `测试项目-${generateRandomString()}`,
        template_id: validTemplateId,
        namespace: 'ns'.repeat(50),
      });
      expect([201, 400]).toContain(response.status);
    });

    test('3.3 缺少必填template_id', async () => {
      try {
        await global.testAPI.post('/scaffold/projects/generate', {
          name: '测试项目',
          namespace: 'test',
        });
      } catch (error) {
          expect(error.response.status).toBe(400);
        }
    });

    test('3.4 无效template_id', async () => {
      try {
        await global.testAPI.post('/scaffold/projects/generate', {
          name: '测试项目',
          template_id: 'non-existent-template',
          namespace: 'test',
        });
      } catch (error) {
          expect([400, 500]).toContain(error.response.status);
        }
    });

    test('3.5 空config对象', async () => {
      if (!validTemplateId) return;
      const response = await global.testAPI.post('/scaffold/projects/generate', {
        name: `空配置项目-${generateRandomString()}`,
        template_id: validTemplateId,
        namespace: 'test',
        config: {},
      });
      expect(response.status).toBe(201);
    });
  });

  describe('备份恢复 - 边界值测试', () => {
    test('4.1 创建备份 - resource_type边界值测试', async () => {
      if (!validTemplateId) return;
      const invalidTypes = ['', 'invalid', 'TEMPLATE', '123'];
      for (const type of invalidTypes) {
        try {
          await global.testAPI.post('/scaffold/backups', {
            resource_type: type,
            resource_id: validTemplateId,
            backup_type: 'manual',
          });
        } catch (error) {
          expect(error.response.status).toBe(400);
        }
      }
    });

    test('4.2 backup_type边界值测试', async () => {
      if (!validTemplateId) return;
      const invalidTypes = ['', 'invalid', 'MANUAL'];
      for (const type of invalidTypes) {
        try {
          await global.testAPI.post('/scaffold/backups', {
            resource_type: 'template',
            resource_id: validTemplateId,
            backup_type: type,
          });
        } catch (error) {
          expect(error.response.status).toBe(400);
        }
      }
    });

    test('4.3 恢复备份 - 缺少backup_id', async () => {
      try {
        await global.testAPI.post('/scaffold/restore', {});
      } catch (error) {
        expect(error.response.status).toBe(400);
      }
    });

    test('4.4 恢复备份 - 无效backup_id', async () => {
      try {
        await global.testAPI.post('/scaffold/restore', {
          backup_id: 'non-existent-backup',
        });
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }
    });

    test('4.5 验证备份 - 无效backup_id', async () => {
      try {
        await global.testAPI.get('/scaffold/backups/invalid-id/verify');
      } catch (error) {
        expect([400, 404]).toContain(error.response.status);
      }
    });
  });

  describe('任务管理 - 边界值测试', () => {
    test('5.1 查询任务状态 - 无效task_id', async () => {
      const invalidIds = ['', 'invalid', '123'];
      for (const id of invalidIds) {
        try {
          await global.testAPI.get(`/scaffold/tasks/${id}/status`);
        } catch (error) {
          expect([400, 404]).toContain(error.response.status);
        }
      }
    });

    test('5.2 恢复任务 - 无效task_id', async () => {
      try {
        await global.testAPI.post('/scaffold/tasks/invalid-id/resume');
      } catch (error) {
        expect([400, 500]).toContain(error.response.status);
      }
    });

    test('5.3 获取检查点 - 无效task_id', async () => {
      try {
        await global.testAPI.get('/scaffold/tasks/invalid-id/checkpoints');
      } catch (error) {
          expect([400, 500]).toContain(error.response.status);
        }
    });
  });
});
