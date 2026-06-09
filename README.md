# Multi-Tenant Content Management System (CMS) API

企业级多租户内容管理后端API，基于TypeScript + Fastify + Prisma + Drizzle构建的SaaS平台。

## 技术栈

- **框架**: Fastify 4.x
- **语言**: TypeScript 5.x
- **ORM**: Prisma 5.x + Drizzle ORM 0.29.x
- **数据库**: PostgreSQL 14+
- **搜索引擎**: Elasticsearch 8.x
- **缓存/消息队列**: Redis 7.x + BullMQ 5.x
- **验证**: Zod 3.x + Ajv 8.x
- **日志**: Pino 8.x

## 核心模块

### 1. 租户隔离与路由模块 (`src/modules/tenant/`)

- **租户识别**: 支持Host头、API Key、租户代码三种识别方式
- **数据库隔离**: Schema-based多租户架构，每个租户独立PostgreSQL Schema
- **连接池管理**: 租户独立连接池，闲置自动回收（5分钟）
- **缓存隔离**: Redis客户端按租户分片，数据物理隔离
- **套餐限制**: free/starter/professional/enterprise四档套餐

### 2. 动态内容模型引擎 (`src/modules/content-model/`)

- **JSON Schema驱动**: 字段结构、验证规则、搜索配置全部由JSON Schema定义
- **运行时建表**: 根据Schema动态创建PostgreSQL表和索引
- **字段类型扩展**: string/text/integer/float/boolean/date/datetime/json/reference
- **向前兼容迁移**: Schema变更时自动迁移历史数据，类型安全转换

### 3. 内容版本管理 (`src/modules/version-control/`)

- **自动快照**: 每次编辑自动产生完整快照，保留完整历史
- **Diff计算**: 基于rfc6902的JSON Patch差异比较
- **版本回溯**: 一键回滚到任意历史版本
- **状态分离**: 草稿态(draft)、审核态(under_review)、发布态(published)独立存储

### 4. 审批工作流引擎 (`src/modules/workflow/`)

- **自定义审批链**: 租户可配置DAG工作流，支持串签、并签、条件分支
- **审批类型**: one(或签)、all(会签)、percentage(百分比通过)
- **条件分支**: 运行时动态计算表达式，支持内容字段、用户角色等条件
- **防篡改记录**: 每条审批记录带HMAC签名，完整可审计

### 5. 全文检索服务 (`src/modules/search/`)

- **物理隔离**: 每个租户每个模型独立Elasticsearch索引
- **中文分词**: IK分词器支持ik_max_word(细粒度)和ik_smart(粗粒度)
- **字段权重**: 可配置每个字段的搜索权重boost值
- **搜索增强**: 模糊搜索、高亮、过滤、排序、自动补全

### 6. 内容分发API (`src/modules/cdn/`)

- **多区域发布**: 7个CDN区域（阿里云、七牛、Cloudflare、AWS）
- **缓存预热**: 主动预热CDN边缘节点
- **缓存失效**: 精准URL失效，异步执行带重试
- **状态追踪**: 每个内容每个区域独立发布状态追踪

### 7. Webhook通知系统 (`src/modules/webhook/`)

- **事件驱动**: 10+种内容变更事件可订阅
- **HMAC签名**: SHA256签名验证，防止伪造
- **指数退避重试**: 1秒、2秒、4秒、8秒...最多5次重试
- **完整日志**: 所有推送记录、响应内容、重试过程完整记录

### 8. 用量统计与限流 (`src/modules/usage/`)

- **API调用计数**: 分钟级和日级双层限流
- **存储监控**: 实时估算存储使用量
- **配额检查**: 创建/更新操作前检查套餐配额
- **硬限流**: 超限返回429状态码，附带Retry-After头

## 快速开始

### 1. 安装依赖

```bash
cd DF1-67
npm install
```

### 2. 配置环境变量

```bash
cp .env.example .env
```

编辑 `.env` 文件，配置数据库、Redis、Elasticsearch连接信息。

### 3. 数据库初始化

```bash
# 生成Prisma Client
npm run prisma:generate

# 运行Prisma迁移
npm run prisma:migrate

# 运行Drizzle迁移（用于动态Schema）
npm run drizzle:migrate
```

### 4. 启动开发服务器

```bash
npm run dev
```

服务器将在 `http://localhost:3000` 启动，Swagger文档在 `http://localhost:3000/docs`。

## 项目结构

```
DF1-67/
├── src/
│   ├── config/           # 配置管理
│   ├── types/            # TypeScript类型定义
│   ├── utils/            # 工具函数（加密、Diff、日志）
│   ├── modules/
│   │   ├── tenant/       # 租户隔离与路由
│   │   ├── content-model/ # 动态内容模型
│   │   ├── version-control/ # 内容版本管理
│   │   ├── workflow/     # 审批工作流
│   │   ├── search/       # 全文检索
│   │   ├── cdn/          # CDN内容分发
│   │   ├── webhook/      # Webhook通知
│   │   └── usage/        # 用量统计与限流
│   ├── app.ts            # Fastify应用配置
│   └── server.ts         # 服务入口
├── prisma/               # Prisma Schema和迁移
├── drizzle/              # Drizzle Schema和迁移
├── package.json
├── tsconfig.json
└── README.md
```

## API 认证

### API Key 认证（推荐）

```bash
curl -H "X-API-Key: sk_live_your_api_key_here" \
     https://api.example.com/api/v1/health
```

### Host 头认证

```bash
curl -H "Host: tenant-code.example.com" \
     https://api.example.com/api/v1/health
```

## 套餐限制对比

| 功能 | Free | Starter | Professional | Enterprise |
|------|------|---------|--------------|------------|
| 内容模型 | 5 | 20 | 100 | 无限 |
| 内容条目 | 1000 | 10,000 | 100,000 | 无限 |
| 每内容版本 | 10 | 50 | 200 | 1000 |
| API调用/天 | 1000 | 10,000 | 100,000 | 1,000,000 |
| 限流/分钟 | 60 | 300 | 1000 | 5000 |
| 存储容量 | 1GB | 10GB | 100GB | 1TB |
| Elasticsearch | ❌ | ✅ | ✅ | ✅ |
| CDN | ❌ | ✅ | ✅ | ✅ |
| Webhook | ❌ | ✅(5个) | ✅(20个) | ✅(100个) |
| 工作流 | ❌ | ✅(10/月) | ✅(1000/月) | ✅(无限) |

## 数据库架构

核心数据表：
- `tenants` - 租户表
- `tenant_usages` - 租户用量统计
- `content_models` - 内容模型定义（JSON Schema）
- `content_entries` - 内容条目主表
- `content_versions` - 内容版本快照
- `workflow_definitions` - 工作流定义
- `workflow_instances` - 工作流实例
- `search_configs` - 搜索配置
- `cdn_publish_statuses` - CDN发布状态
- `webhook_configs` - Webhook配置
- `webhook_deliveries` - Webhook推送记录

## 核心设计模式

1. **Repository模式**: 每个模块独立Service封装数据访问
2. **Plugin模式**: Fastify插件实现横切关注点（租户上下文、限流、日志）
3. **CQRS简化版**: 写入走PostgreSQL，查询可走Elasticsearch
4. **Outbox模式**: 异步任务通过BullMQ队列保证最终一致性
5. **Circuit Breaker**: 外部服务调用集成熔断机制

## 脚本命令

```bash
npm run dev              # 开发模式（热重载）
npm run build            # TypeScript编译
npm run start            # 生产模式启动
npm run lint             # ESLint检查
npm run typecheck        # TypeScript类型检查
npm run prisma:generate  # 生成Prisma Client
npm run prisma:migrate   # 运行Prisma迁移
npm run drizzle:generate # 生成Drizzle迁移
npm run drizzle:migrate  # 运行Drizzle迁移
```

## 许可证

MIT
