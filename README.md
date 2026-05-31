# 认证鉴权与速率限制平台

一个轻量高效的企业级平台，提供完整的认证鉴权、速率限制、流程编排、计费计量等核心能力。

## 技术栈

- **语言**: TypeScript
- **运行时**: Node.js + Express
- **ORM**: Prisma (可选)
- **缓存**: ioredis + node-cache
- **校验**: Zod
- **测试**: Jest

## 核心模块

| 模块 | 功能描述 |
|------|----------|
| **可视化流程设计模块** | 拖拽式流程设计器，节点配置与连线规则校验 |
| **API网关模块** | 实现认证鉴权与速率限制 |
| **配置管理模块** | 多源配置加载与动态更新 |
| **数据访问模块** | 数据库连接池管理与查询优化 |
| **存储管理模块** | 数据备份与恢复 |
| **用量计量与计费模块** | 租户资源用量采集、按量计费与账单生成 |
| **核心处理模块** | 请求处理与响应生成的核心逻辑 |
| **技能图谱建模模块** | 技能树定义、员工能力评估与学习路径推荐 |
| **调度模块** | 任务执行状态追踪 |
| **日志模块** | 日志级别动态调整 |

## 快速开始

### 安装依赖

```bash
cd session152
npm install
```

### 配置环境变量

复制 `.env.example` 为 `.env` 并修改配置：

```bash
cp .env.example .env
```

### 编译项目

```bash
npm run build
```

### 启动服务

```bash
npm start
```

### 开发模式

```bash
npm run dev
```

### 运行测试

```bash
npm test
```

### 代码检查

```bash
npm run lint
```

## API 接口

### 健康检查
```
GET /health
```

### 系统统计
```
GET /api/v1/stats
```

### 资源管理
```
POST   /api/v1/resources              # 创建资源
GET    /api/v1/resources              # 资源列表
GET    /api/v1/resources/:id/status   # 资源状态
POST   /api/v1/resources/batch        # 批量操作
```

### 任务调度
```
POST   /api/v1/tasks                  # 创建任务
GET    /api/v1/tasks                  # 任务列表
POST   /api/v1/tasks/:id/execute      # 执行任务
```

### 日志管理
```
GET    /api/v1/logs                   # 获取日志
POST   /api/v1/logs/level             # 调整日志级别
```

### 技能图谱
```
POST   /api/v1/skills                 # 创建技能
GET    /api/v1/skills                 # 技能列表
POST   /api/v1/employees              # 添加员工
GET    /api/v1/employees              # 员工列表
POST   /api/v1/employees/:id/skills   # 设置员工技能
GET    /api/v1/employees/:id/skills   # 获取员工技能
```

### 计费管理
```
POST   /api/v1/billing/usage          # 记录用量
GET    /api/v1/billing/invoices       # 发票列表
POST   /api/v1/billing/invoices/:id/issue  # 开具发票
GET    /api/v1/billing/tenants/:id/summary # 租户账单摘要
```

### 流程设计
```
POST   /api/v1/flows                  # 创建流程
GET    /api/v1/flows                  # 流程列表
POST   /api/v1/flows/:id/nodes        # 添加节点
POST   /api/v1/flows/:id/connections  # 添加连线
POST   /api/v1/flows/:id/validate     # 验证流程
```

### 存储管理
```
POST   /api/v1/storage/backup         # 创建备份
GET    /api/v1/storage/backups        # 备份列表
POST   /api/v1/storage/backups/:id/restore # 恢复备份
```

## 架构设计

### 模块化单体架构

```
┌─────────────────────────────────────────────────────────┐
│                        API Gateway                      │
│              (认证 / 授权 / 速率限制)                   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  核心处理  │  │  任务调度  │  │  配置管理        │  │
│  │  模块      │  │  模块      │  │  模块            │  │
│  └────────────┘  └────────────┘  └──────────────────┘  │
│                                                         │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  流程设计  │  │  技能图谱  │  │  用量计费        │  │
│  │  模块      │  │  模块      │  │  模块            │  │
│  └────────────┘  └────────────┘  └──────────────────┘  │
│                                                         │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  数据访问  │  │  存储管理  │  │  日志管理        │  │
│  │  模块      │  │  模块      │  │  模块            │  │
│  └────────────┘  └────────────┘  └──────────────────┘  │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 核心处理流程

```
请求 → API网关(认证/限流) → 核心处理器 → 执行业务逻辑
                                  ↓
                           记录指标/事件
                                  ↓
                           返回响应结果
```

## 核心数据模型

### 实体模型
```typescript
{
  id: "ent_001",
  type: "resource",
  status: "pending",
  attributes: { key: "value" },
  created_at: "2026-05-11T08:00:00Z",
  updated_at: "2026-05-11T09:00:00Z"
}
```

### 配置定义模型
```typescript
{
  config_id: "cfg_001",
  namespace: "development",
  version: 3,
  parameters: { timeout: 30, retries: 3 },
  enabled: true,
  applied_at: "2026-05-11T08:30:00Z"
}
```

### 运行实例模型
```typescript
{
  run_id: "run_001",
  entity_id: "ent_001",
  phase: "finalizing",
  progress: 0.75,
  started_at: "2026-05-11T08:00:00Z",
  completed_at: null,
  error_detail: null
}
```

## 使用示例

### 核心处理器

```typescript
import { CoreProcessor } from './src';

const processor = new CoreProcessor();

processor.registerHandler({
  name: 'user.create',
  handler: async (payload, context) => {
    // 业务逻辑
    return { userId: generateId() };
  }
});

const result = await processor.processRequest('user.create', 
  { name: '张三', email: 'zhangsan@example.com' },
  { tenantId: 'tenant_001' }
);
```

### 日志动态调整

```typescript
import { getLogger } from './src';

const logger = getLogger();

// 设置全局日志级别
logger.setLevel('debug');

// 设置特定模块的日志级别
logger.setModuleLevel('http', 'warn');

// 记录日志
logger.info('用户登录成功', { 
  module: 'auth',
  userId: 'user_001',
  metadata: { ip: '127.0.0.1' }
});
```

### 任务调度

```typescript
import { TaskScheduler } from './src';

const scheduler = new TaskScheduler();

scheduler.registerHandler('report.daily', async (task) => {
  // 生成每日报表
  console.log('生成报表:', task.payload.date);
});

// 创建定时任务
scheduler.createTask('report.daily', { date: '2026-05-11' }, {
  schedule: { type: 'recurring', intervalMs: 24 * 60 * 60 * 1000 }
});

scheduler.start();
```

### 技能图谱

```typescript
import { SkillGraphManager } from './src';

const skillGraph = new SkillGraphManager();

// 创建技能
const tsSkill = skillGraph.createSkill(
  'TypeScript',
  'TypeScript 编程语言',
  'technical'
);

// 添加员工
const employee = skillGraph.addEmployee(
  '张三',
  'zhangsan@example.com',
  '技术部',
  '高级工程师'
);

// 评估技能
skillGraph.assessSkill(
  employee.id,
  tsSkill.id,
  'advanced',
  '技术经理',
  90,
  '技能评估优秀'
);

// 推荐学习路径
const path = skillGraph.recommendLearningPath(employee.id, '技术负责人');
```

## 已知风险与优化策略

### 单点故障
- **风险**: 核心模块的单实例部署可能成为瓶颈
- **优化**: 实现主备切换或无状态水平扩展，配合健康检测自动摘除故障节点

### 安全漏洞
- **风险**: 第三方依赖可能包含已知 CVE
- **优化**: 集成依赖扫描工具到 CI 流程，阻断含高危漏洞的构建

### 资源耗尽
- **风险**: 无限制的并发请求可能耗尽连接池或文件句柄
- **优化**: 引入信号量或 Channel 控制最大并发数，超额请求排队或快速拒绝

## 许可证

MIT License
