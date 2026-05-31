const { generateRandomString, delay } = global;

describe('项目脚手架生成模块 - 正常业务流程测试', () => {
  let createdTemplateId = null;
  let createdTaskId = null;
  let createdProjectId = null;

  const testTemplate = {
    name: `测试模板`,
    description: 'Jest测试用的模板',
    language: 'go',
    framework: 'gin',
    version: '1.0.0',
    tags: ['test', 'jest', 'automated'],
    parameters: {
      database: { type: 'string', default: 'postgres' },
      cache: { type: 'string', default: 'redis' },
    },
    file_tree: {
      'main.go': 'package main',
      'go.mod': 'module test',
      'README.md': '# Test Project',
    },
    is_public: true,
    author: 'test-user',
  };

  describe('模板管理流程', () => {
    test('1.1 创建模板 - 成功创建新模板', async () => {
      const uniqueName = `${testTemplate.name}-${generateRandomString()}`;
      const response = await global.testAPI.post('/scaffold/templates', {
        ...testTemplate,
        name: uniqueName,
      });

      expect(response.status).toBe(201);
      expect(response.data).toHaveProperty('code', 200);
      expect(response.data.data).toHaveProperty('id');
      expect(response.data.data.name).toBe(uniqueName);
      expect(response.data.data.language).toBe(testTemplate.language);
      createdTemplateId = response.data.data.id;
      console.log(`      ✅ 创建模板成功: ${createdTemplateId}`);
    });

    test('1.2 查询模板 - 根据ID获取模板详情', async () => {
      const response = await global.testAPI.get(`/scaffold/templates/${createdTemplateId}`);
      expect(response.status).toBe(200);
      expect(response.data.data.id).toBe(createdTemplateId);
      expect(response.data.data.name).toContain('测试模板');
    });

    test('1.3 更新模板 - 修改模板描述和标签', async () => {
      const response = await global.testAPI.put(`/scaffold/templates/${createdTemplateId}`, {
        description: '更新后的测试模板描述',
        tags: ['updated', 'test', 'v2'],
      });
      expect(response.status).toBe(200);
      expect(response.data.code).toBe(200);
    });

    test('1.4 模板列表 - 分页查询模板', async () => {
      const response = await global.testAPI.get('/scaffold/templates?page=1&page_size=10');
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('items');
      expect(Array.isArray(response.data.data.items)).toBe(true);
      expect(response.data.data).toHaveProperty('total');
      expect(response.data.data.total).toBeGreaterThanOrEqual(1);
    });

    test('1.5 按语言筛选模板', async () => {
      const response = await global.testAPI.get('/scaffold/templates?language=go');
      expect(response.status).toBe(200);
      const goTemplates = response.data.data.items.filter(t => t.language === 'go');
      expect(goTemplates.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('项目生成流程', () => {
    test('2.1 触发项目生成 - 成功启动生成任务', async () => {
      const projectName = `测试项目-${generateRandomString()}`;
      const response = await global.testAPI.post('/scaffold/projects/generate', {
        name: projectName,
        description: 'Jest测试生成的项目',
        template_id: createdTemplateId,
        namespace: 'test-namespace',
        config: {
          database: 'mysql',
          cache: 'redis',
        },
        owner_id: 'test-user-001',
      });

      expect(response.status).toBe(201);
      expect(response.data.data).toHaveProperty('task_id');
      expect(response.data.data.status).toBeDefined();
      expect(response.data.data).toHaveProperty('project_id');
      createdTaskId = response.data.data.task_id;
      createdProjectId = response.data.data.project_id;
      console.log(`      ✅ 生成任务创建成功: ${createdTaskId}`);
    });

    test('2.2 查询任务状态', async () => {
      await delay(2000);
      const response = await global.testAPI.get(`/scaffold/tasks/${createdTaskId}/status`);
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('task_id', createdTaskId);
      expect(response.data.data).toHaveProperty('status');
      expect(response.data.data).toHaveProperty('progress');
    });

    test('2.3 获取项目列表', async () => {
      const response = await global.testAPI.get('/scaffold/projects?page=1&page_size=20');
      expect(response.status).toBe(200);
      expect(Array.isArray(response.data.data.items)).toBe(true);
    });

    test('2.4 获取项目详情', async () => {
      const response = await global.testAPI.get(`/scaffold/projects/${createdProjectId}`);
      expect(response.status).toBe(200);
      expect(response.data.data.id).toBe(createdProjectId);
    });
  });

  describe('备份恢复流程', () => {
    let createdBackupId = null;

    test('3.1 创建模板备份', async () => {
      const response = await global.testAPI.post('/scaffold/backups', {
        resource_type: 'template',
        resource_id: createdTemplateId,
        backup_type: 'manual',
        created_by: 'test-user',
      });
      expect(response.status).toBe(201);
      expect(response.data.data).toHaveProperty('backup_id');
      expect(response.data.data).toHaveProperty('checksum');
      createdBackupId = response.data.data.backup_id;
      console.log(`      ✅ 备份创建成功: ${createdBackupId}`);
    });

    test('3.2 获取备份列表', async () => {
      const response = await global.testAPI.get('/scaffold/backups');
      expect(response.status).toBe(200);
      expect(Array.isArray(response.data.data.items)).toBe(true);
    });

    test('3.3 验证备份完整性', async () => {
      const response = await global.testAPI.get(`/scaffold/backups/${createdBackupId}/verify`);
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('backup_id', createdBackupId);
      expect(response.data.data).toHaveProperty('valid');
      expect(typeof response.data.data.valid).toBe(true);
    });

    test('3.4 从备份恢复', async () => {
      const response = await global.testAPI.post('/scaffold/restore', {
        backup_id: createdBackupId,
        recovered_by: 'test-user',
      });
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('recovery_id');
      expect(response.data.data).toHaveProperty('status');
    });

    test('3.5 删除备份', async () => {
      const response = await global.testAPI.delete(`/scaffold/backups/${createdBackupId}`);
      expect(response.status).toBe(200);
    });
  });

  describe('任务检查点与断点续传', () => {
    test('4.1 获取任务检查点列表', async () => {
      const response = await global.testAPI.get(`/scaffold/tasks/${createdTaskId}/checkpoints`);
      expect(response.status).toBe(200);
      expect(Array.isArray(response.data.data)).toBe(true);
    });

    test('4.2 任务断点续传', async () => {
      const response = await global.testAPI.post(`/scaffold/tasks/${createdTaskId}/resume`);
      expect(response.status).toBe(200);
      expect(response.data.data).toHaveProperty('task_id', createdTaskId);
      expect(response.data.data).toHaveProperty('status', 'resumed');
    });
  });
});
