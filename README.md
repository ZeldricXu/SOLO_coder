# 数据转换与标准化核心系统

生产级高可用的数据转换与标准化核心逻辑系统，专注于容器镜像分发、数据处理、API网关、流量控制、mTLS管理、故障注入、配置管理、数据访问和存储管理等核心能力。

## 技术栈

- **语言**: TypeScript
- **运行时**: Node.js
- **Web框架**: Express
- **类型校验**: Zod
- **日志**: Winston
- **缓存**: ioredis + node-cache

## 系统架构

### 模块划分

1. **容器镜像分发模块** (`src/modules/image-distribution/`)
   - 镜像分层拉取
   - P2P分发加速
   - 跨Registry同步

2. **核心处理模块** (`src/modules/core/`)
   - 数据转换与标准化
   - 资源池管理
   - 重试与超时机制

3. **API网关模块** (`src/modules/api-gateway/`)
   - 请求日志记录
   - 分布式链路追踪
   - 性能指标采集

4. **流量策略控制模块** (`src/modules/traffic-control/`)
   - 金丝雀发布
   - 蓝绿部署
   - 流量镜像
   - 熔断配置

5. **mTLS证书管理模块** (`src/modules/mtls/`)
   - 证书自动签发
   - 轮转策略配置
   - 吊销列表管理

6. **故障注入编排模块** (`src/modules/fault-injection/`)
   - 故障场景定义
   - 注入范围控制
   - 自动回滚机制

7. **配置管理模块** (`src/modules/config/`)
   - 多源配置加载
   - 动态更新监听
   - 配置版本控制

8. **数据访问模块** (`src/modules/data-access/`)
   - Schema版本控制
   - 数据迁移管理
   - CRUD操作封装

9. **存储管理模块** (`src/modules/storage/`)
   - 数据备份与恢复
   - 快照管理
   - 保留策略执行

## 快速开始

### 安装依赖

```bash
cd session150
npm install
```

### 配置环境变量

```bash
cp .env.example .env
```

### 启动开发服务器

```bash
npm run dev
```

### 构建生产版本

```bash
npm run build
npm start
```

## API接口

### 资源管理

- `POST /api/v1/resources` - 创建资源
- `GET /api/v1/resources/:id/status` - 查询资源状态
- `POST /api/v1/resources/batch` - 批量操作

### 配置管理

- `GET /api/v1/configs` - 列出所有配置
- `GET /api/v1/configs/:namespace` - 获取命名空间配置
- `POST /api/v1/configs/:namespace` - 更新配置

### 流量控制

- `GET /api/v1/traffic/canaries` - 列出金丝雀配置
- `POST /api/v1/traffic/canaries` - 创建金丝雀配置
- `GET /api/v1/traffic/bluegreens` - 列出蓝绿配置
- `POST /api/v1/traffic/bluegreens/:id/switch` - 切换蓝绿环境

### mTLS管理

- `GET /api/v1/mtls/certificates` - 列出证书
- `POST /api/v1/mtls/certificates` - 签发证书
- `POST /api/v1/mtls/certificates/:id/rotate` - 轮转证书
- `POST /api/v1/mtls/certificates/:id/revoke` - 吊销证书

### 故障注入

- `GET /api/v1/fault/scenarios` - 列出故障场景
- `POST /api/v1/fault/scenarios` - 创建故障场景
- `POST /api/v1/fault/scenarios/:id/start` - 启动故障注入
- `POST /api/v1/fault/scenarios/:id/stop` - 停止故障注入

### 数据访问

- `GET /api/v1/data/schemas` - 列出Schema
- `POST /api/v1/data/schemas` - 创建Schema
- `POST /api/v1/data/:schema` - 插入数据
- `GET /api/v1/data/:schema` - 查询数据

### 存储管理

- `GET /api/v1/storage/backups` - 列出备份
- `POST /api/v1/storage/backups` - 创建备份
- `POST /api/v1/storage/backups/:id/restore` - 恢复备份

### 镜像分发

- `POST /api/v1/images/pull` - 拉取镜像
- `POST /api/v1/images/sync` - 同步镜像
- `GET /api/v1/images/sync-status` - 同步状态

### 可观测性

- `GET /health` - 健康检查
- `GET /api/v1/status` - 系统状态
- `GET /api/v1/traces/:traceId` - 链路追踪
- `GET /api/v1/logs/recent` - 最近日志

## 核心处理流程

```typescript
function executeHandler(request):
    ctx = initContext(request.traceId)
    try:
        validateParams(request.params)
        config = loadConfig(request.namespace)
        resource = acquireResource(config.poolSize)
        try:
            result = processCore(request.payload, config.rules)
            persistResult(result)
            emitEvent('task.completed', buildEvent(result))
            return successResponse(result)
        finally:
            releaseResource(resource)
    catch ValidationError as e:
        return errorResponse(422, e.details)
    catch TimeoutError:
        return errorResponse(504, '上游服务响应超时')
    catch Exception as e:
        rollbackTransaction(ctx)
        return errorResponse(500, '内部处理错误')
    finally:
        recordMetrics(ctx)
        ctx.cleanup()
```

## 数据模型

### 核心实体模型

```json
{
  "id": "ent_001",
  "type": "record",
  "status": "completed",
  "attributes": {"key": "value"},
  "created_at": "2026-05-11T08:00:00Z",
  "updated_at": "2026-05-11T09:00:00Z"
}
```

### 配置定义模型

```json
{
  "config_id": "cfg_001",
  "namespace": "staging",
  "version": 3,
  "parameters": {"timeout": 30, "retries": 3},
  "enabled": true,
  "applied_at": "2026-05-11T08:30:00Z"
}
```

## 项目结构

```
session150/
├── src/
│   ├── index.ts                 # 主入口文件
│   ├── types/                   # 类型定义
│   ├── schemas/                 # Zod校验Schema
│   ├── utils/                   # 工具函数
│   │   ├── logger.ts
│   │   ├── helpers.ts
│   │   ├── eventBus.ts
│   │   └── resourcePool.ts
│   └── modules/                 # 业务模块
│       ├── config/              # 配置管理
│       ├── core/                # 核心处理
│       ├── image-distribution/  # 镜像分发
│       ├── api-gateway/         # API网关
│       ├── traffic-control/     # 流量控制
│       ├── mtls/                # mTLS管理
│       ├── fault-injection/     # 故障注入
│       ├── data-access/         # 数据访问
│       └── storage/             # 存储管理
├── config/                      # 配置文件
├── package.json
├── tsconfig.json
└── README.md
```

## 开发

### 代码规范

```bash
npm run lint
```

### 运行测试

```bash
npm test
```

## 许可证

MIT
