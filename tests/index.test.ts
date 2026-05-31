import {
  FlowDesigner,
  ApiGateway,
  AuthService,
  RateLimiter,
  ConfigManager,
  EnvConfigSource,
  ConnectionPool,
  QueryOptimizer,
  StorageManager,
  BillingManager,
  CoreProcessor,
  SkillGraphManager,
  TaskScheduler,
  Logger,
  createLogger
} from '../src';

describe('模块导出测试', () => {
  it('应该导出所有模块', () => {
    expect(FlowDesigner).toBeDefined();
    expect(ApiGateway).toBeDefined();
    expect(AuthService).toBeDefined();
    expect(RateLimiter).toBeDefined();
    expect(ConfigManager).toBeDefined();
    expect(EnvConfigSource).toBeDefined();
    expect(ConnectionPool).toBeDefined();
    expect(QueryOptimizer).toBeDefined();
    expect(StorageManager).toBeDefined();
    expect(BillingManager).toBeDefined();
    expect(CoreProcessor).toBeDefined();
    expect(SkillGraphManager).toBeDefined();
    expect(TaskScheduler).toBeDefined();
    expect(Logger).toBeDefined();
    expect(createLogger).toBeDefined();
  });
});

describe('核心处理模块', () => {
  let coreProcessor: CoreProcessor;

  beforeEach(() => {
    coreProcessor = new CoreProcessor();
  });

  afterEach(() => {
    coreProcessor.destroy();
  });

  it('应该注册和执行处理器', async () => {
    coreProcessor.registerHandler({
      name: 'test.handler',
      handler: (payload: { value: number }) => ({ result: payload.value * 2 })
    });

    const result = await coreProcessor.processRequest('test.handler', { value: 5 });
    expect(result.success).toBe(true);
    expect(result.data).toEqual({ result: 10 });
  });

  it('应该创建和管理资源', () => {
    const resource = coreProcessor.createResource('task', { timeout: 30 }, { env: 'test' });
    expect(resource.id).toBeDefined();
    expect(resource.type).toBe('task');
    expect(resource.status).toBe('pending');

    const fetched = coreProcessor.getResource(resource.id);
    expect(fetched.id).toBe(resource.id);
  });

  it('应该返回统计信息', () => {
    const stats = coreProcessor.getStats();
    expect(stats.registeredHandlers).toBe(0);
    expect(stats.totalResources).toBe(0);
  });
});

describe('日志模块', () => {
  let logger: Logger;

  beforeEach(() => {
    logger = createLogger({ enableConsole: false });
  });

  afterEach(() => {
    logger.destroy();
  });

  it('应该记录不同级别的日志', () => {
    logger.info('测试信息日志');
    logger.error('测试错误日志', { error: new Error('测试错误') });

    const logs = logger.getLogs();
    expect(logs.length).toBeGreaterThan(0);
    expect(logs.some(l => l.level === 'info')).toBe(true);
    expect(logs.some(l => l.level === 'error')).toBe(true);
  });

  it('应该动态调整日志级别', () => {
    logger.setLevel('warn');
    expect(logger.getLevel()).toBe('warn');

    logger.setLevel('debug', 'test-module');
    expect(logger.getLevel('test-module')).toBe('debug');
  });

  it('应该支持子日志器', () => {
    const childLogger = logger.child({ module: 'test', defaultMetadata: { foo: 'bar' } });
    childLogger.info('测试子日志器');

    const logs = logger.getLogs();
    expect(logs[0].module).toBe('test');
  });
});

describe('调度模块', () => {
  let scheduler: TaskScheduler;

  beforeEach(() => {
    scheduler = new TaskScheduler();
    scheduler.registerHandler('test.task', () => {
      console.log('执行测试任务');
    });
  });

  afterEach(() => {
    scheduler.destroy();
  });

  it('应该创建任务', () => {
    const task = scheduler.createTask('test.task', { value: 42 }, { priority: 'high' });
    expect(task.id).toBeDefined();
    expect(task.type).toBe('test.task');
    expect(task.status).toBe('pending');
  });

  it('应该列出任务', () => {
    scheduler.createTask('test.task', {});
    scheduler.createTask('test.task', {}, { priority: 'high' });

    const tasks = scheduler.listTasks();
    expect(tasks.length).toBe(2);
  });
});

describe('技能图谱模块', () => {
  let skillGraph: SkillGraphManager;

  beforeEach(() => {
    skillGraph = new SkillGraphManager();
  });

  it('应该创建技能', () => {
    const skill = skillGraph.createSkill(
      'TypeScript',
      'TypeScript 编程语言',
      'technical',
      ['beginner', 'intermediate', 'advanced', 'expert'],
      [],
      ['programming', 'frontend']
    );

    expect(skill.id).toBeDefined();
    expect(skill.name).toBe('TypeScript');
  });

  it('应该添加员工并设置技能', () => {
    const skill = skillGraph.createSkill('JavaScript', 'JS 基础', 'technical');
    const employee = skillGraph.addEmployee('张三', 'zhangsan@example.com', '技术部', '工程师');

    const employeeSkill = skillGraph.setEmployeeSkill(employee.id, skill.id, 'intermediate', '经理');
    expect(employeeSkill.level).toBe('intermediate');

    const skills = skillGraph.getEmployeeSkills(employee.id);
    expect(skills.length).toBe(1);
  });
});

describe('计费模块', () => {
  let billingManager: BillingManager;

  beforeEach(() => {
    billingManager = new BillingManager({
      pricePerApiCall: 0.001,
      pricePerStorageGb: 0.05,
      pricePerComputeUnit: 0.01,
      pricePerBandwidthGb: 0.02,
      currency: 'CNY',
      cycleDays: 30
    });
  });

  it('应该记录用量', () => {
    const record = billingManager.recordApiCall('tenant_001', '/api/v1/test');
    expect(record.tenantId).toBe('tenant_001');
    expect(record.resourceType).toBe('api_calls');
    expect(record.quantity).toBe(1);
  });

  it('应该生成发票', () => {
    billingManager.recordApiCall('tenant_001');
    billingManager.recordApiCall('tenant_001');

    const invoice = billingManager.generateInvoice('tenant_001');
    expect(invoice.tenantId).toBe('tenant_001');
    expect(invoice.items.length).toBeGreaterThan(0);
    expect(invoice.total).toBeGreaterThan(0);
  });
});

describe('配置管理模块', () => {
  let configManager: ConfigManager;

  beforeEach(() => {
    configManager = new ConfigManager();
    configManager.addSource(new EnvConfigSource());
  });

  afterEach(async () => {
    await configManager.destroy();
  });

  it('应该初始化配置', async () => {
    await configManager.initialize();
    const appConfig = configManager.getAppConfig();
    expect(appConfig).toBeDefined();
  });

  it('应该支持变更监听', async () => {
    await configManager.initialize();

    const mockListener = jest.fn();
    const unsub = configManager.onChange(mockListener);

    configManager.set('test.key', 'test.value');

    expect(configManager.get('test.key')).toBe('test.value');
    unsub();
  });
});

describe('流程设计模块', () => {
  let flowDesigner: FlowDesigner;

  beforeEach(() => {
    flowDesigner = new FlowDesigner();
  });

  it('应该创建流程', () => {
    const flow = flowDesigner.createFlow('测试流程', 'user_001');
    expect(flow.id).toBeDefined();
    expect(flow.name).toBe('测试流程');
  });

  it('应该添加节点和连线', () => {
    const flow = flowDesigner.createFlow('测试流程', 'user_001');

    const node1 = flowDesigner.addNode(flow.id, {
      type: 'start',
      name: '开始节点',
      config: {}
    });

    const node2 = flowDesigner.addNode(flow.id, {
      type: 'end',
      name: '结束节点',
      config: {}
    });

    const connection = flowDesigner.addConnection(flow.id, {
      sourceNodeId: node1.id,
      targetNodeId: node2.id,
      sourcePort: 'output',
      targetPort: 'input'
    });

    expect(connection.id).toBeDefined();
  });

  it('应该验证流程', () => {
    const flow = flowDesigner.createFlow('测试流程', 'user_001');
    const validation = flowDesigner.validateFlow(flow.id);
    expect(validation.isValid).toBe(false);
  });
});
