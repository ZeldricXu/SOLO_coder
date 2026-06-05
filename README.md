# MLOps Platform

统一的机器学习模型服务化平台，基于 TypeScript 全栈技术栈构建。

## 技术栈

- **前端**: Next.js 14 + React 18 + TypeScript + Tailwind CSS
- **后端**: Node.js + Fastify + TypeScript
- **数据库**: PostgreSQL + Prisma ORM
- **缓存**: Redis
- **对象存储**: S3 (MinIO for local)
- **通信协议**: REST + gRPC
- **包管理**: npm workspaces + Turbo

## 模块功能

### 1. 模型注册中心 (Model Registry)
- ✅ 模型版本管理（语义化版本）
- ✅ 元数据索引（标签、所有者、团队）
- ✅ S3/本地双存储后端
- ✅ 模型格式自动识别（pkl、onnx、pt、joblib、h5、pb、custom）
- ✅ 加载器自动匹配（6种内置加载器）
- ✅ 模型文件上传下载

### 2. 在线推理网关 (Inference Gateway)
- ✅ REST 和 gRPC 双协议支持
- ✅ 请求批处理合并（Dynamic Batching）
- ✅ 动态 batch size 调优
- ✅ 模型热加载（不中断服务）
- ✅ 推理结果缓存（Redis）
- ✅ 推理指标记录（延迟、吞吐量、错误率）

### 3. 实验追踪看板 (Experiment Tracking)
- ✅ 超参数记录
- ✅ 指标可视化对比（多实验对比）
- ✅ 实验血缘依赖图（React Flow）
- ✅ 实验运行管理（启动、完成、失败状态）
- ✅ 指标图表（Recharts）
- ✅ 代码来源追踪（Git commit）

### 4. 特征存储服务 (Feature Store)
- ✅ 离线特征计算和在线特征查询双模式
- ✅ 特征版本管理
- ✅ TTL 过期策略
- ✅ 特征分布统计（均值、方差、直方图）
- ✅ 在线缓存（Redis）+ 离线存储（S3）
- ✅ 批量数据导入

### 5. A/B 实验配置引擎 (A/B Testing)
- ✅ 用户分桶策略（user_id、session_id、device_id、自定义）
- ✅ 流量分配权重
- ✅ 实时指标计算
- ✅ 显著性检验（t检验、p值计算）
- ✅ 多变体支持（含对照组）
- ✅ 定向规则（用户属性过滤）

### 6. 告警与监控 (Monitoring & Alerting)
- ✅ 推理延迟 P50/P95/P99/P999 监控
- ✅ 模型漂移检测（KS检验、t检验、Mann-Whitney U）
- ✅ 特征分布偏移告警
- ✅ 错误率监控
- ✅ 吞吐量监控
- ✅ 多通道通知（Email、Slack、Webhook、PagerDuty）
- ✅ 告警确认和解决工作流

## 项目结构

```
DF1-62/
├── apps/
│   ├── server/                 # Node.js 后端服务
│   │   ├── prisma/             # Prisma schema 和 migrations
│   │   └── src/
│   │       ├── abtest/         # A/B测试引擎
│   │       ├── config/         # 配置（数据库、Redis、日志）
│   │       ├── experiment/     # 实验追踪服务
│   │       ├── feature-store/  # 特征存储服务
│   │       ├── grpc/           # gRPC 服务
│   │       ├── inference/      # 推理网关
│   │       ├── model/          # 模型注册和加载器
│   │       ├── monitoring/     # 监控告警服务
│   │       ├── proto/          # Protocol Buffer 定义
│   │       ├── storage/        # S3/本地存储抽象
│   │       └── index.ts        # 服务入口
│   └── web/                    # Next.js 前端
│       ├── app/                # App Router 页面
│       ├── components/         # React 组件
│       ├── lib/                # API 客户端和工具函数
│       └── store/              # Zustand 状态管理
├── packages/
│   └── shared/                 # 共享类型和验证
│       └── src/
│           ├── types/          # TypeScript 类型定义
│           └── validation/     # Zod 验证 schema
├── docker-compose.yml          # PostgreSQL、Redis、MinIO
└── turbo.json                  # Turbo 构建配置
```

## 快速开始

### 1. 启动基础设施

```bash
cd DF1-62
npm run docker:up
```

这将启动：
- PostgreSQL (端口 5432)
- Redis (端口 6379)
- MinIO (端口 9000/9001)

### 2. 安装依赖

```bash
npm install
```

### 3. 初始化数据库

```bash
npm run db:generate
npm run db:push
```

### 4. 启动开发服务

```bash
# 同时启动前端和后端
npm run dev

# 或分别启动
npm run dev --workspace=@mlops/server
npm run dev --workspace=@mlops/web
```

服务地址：
- 前端: http://localhost:3000
- 后端 REST API: http://localhost:3001
- 后端 gRPC: http://localhost:50051
- MinIO Console: http://localhost:9001

### 5. 环境变量

复制 `.env.example` 到 `.env` 并根据需要修改：

```bash
cp .env.example .env
```

## API 端点

### 模型注册中心
- `POST /api/v1/models` - 创建模型
- `GET /api/v1/models` - 列出模型
- `GET /api/v1/models/:id` - 获取模型详情
- `POST /api/v1/models/:id/versions` - 上传模型版本
- `GET /api/v1/versions/:id/download` - 下载模型

### 推理网关
- `POST /api/v1/inference` - 同步推理
- `POST /api/v1/inference/batch` - 批量推理
- `GET /api/v1/inference/status` - 网关状态
- `POST /api/v1/models/:id/load` - 加载模型到内存
- `POST /api/v1/models/:id/unload` - 卸载模型

### 实验追踪
- `POST /api/v1/experiments` - 创建实验
- `GET /api/v1/experiments` - 列出实验
- `POST /api/v1/runs` - 开始运行
- `PATCH /api/v1/runs/:id` - 更新运行
- `POST /api/v1/runs/compare` - 对比运行
- `GET /api/v1/runs/:id/lineage` - 获取血缘图

### 特征存储
- `POST /api/v1/feature-sets` - 创建特征集
- `GET /api/v1/feature-sets` - 列出特征集
- `POST /api/v1/features/get` - 获取在线特征
- `POST /api/v1/features/ingest` - 导入特征数据
- `GET /api/v1/feature-sets/:id/features/:name/distribution` - 特征分布

### A/B 测试
- `POST /api/v1/ab-tests` - 创建实验
- `GET /api/v1/ab-tests` - 列出实验
- `POST /api/v1/ab-tests/assign` - 获取变体分配
- `POST /api/v1/ab-tests/track` - 上报事件
- `POST /api/v1/ab-tests/:id/results` - 计算统计结果
- `GET /api/v1/ab-tests/:id/stats` - 实时统计

### 监控告警
- `POST /api/v1/alerts` - 创建告警规则
- `GET /api/v1/alerts` - 列出告警
- `PATCH /api/v1/alerts/:id/status` - 更新告警状态
- `POST /api/v1/drift-configs` - 创建漂移检测配置
- `POST /api/v1/drift-configs/:id/run` - 执行漂移检测
- `GET /api/v1/metrics/latency` - 延迟指标
- `GET /api/v1/monitoring/dashboard` - 仪表盘数据

## 核心特性说明

### 动态批处理 (Dynamic Batching)

推理网关实现了智能批处理：
- 请求在队列中等待最多 `INFERENCE_BATCH_TIMEOUT_MS` 毫秒
- 累积到 `INFERENCE_BATCH_MAX_SIZE` 个请求时立即执行
- 超时后强制执行当前批次
- 支持动态调整 batch size 优化吞吐量

### 模型热加载

- 新模型版本上传后自动检测
- 加载过程中旧版本继续服务
- 加载完成后原子切换流量
- 支持版本回滚

### 模型漂移检测

支持多种统计检验：
- **Kolmogorov-Smirnov (KS) 检验**: 比较分布
- **t检验**: 比较均值差异
- **Mann-Whitney U 检验**: 非参数检验
- **卡方检验**: 分类特征
- **对抗验证**: 机器学习方法检测分布变化

### 显著性检验

A/B测试使用：
- 双样本t检验比较均值
- 计算p值和置信区间
- 支持最小可检测效应(MDE)配置
- 自动计算所需样本量

## 数据库 Schema

主要数据表：
- `Model` / `ModelVersion` - 模型和版本
- `Experiment` / `ExperimentRun` - 实验和运行
- `FeatureSet` / `FeatureSetVersion` - 特征集和版本
- `ABTest` / `ABVariant` - A/B测试和变体
- `Alert` / `AlertEvent` - 告警规则和事件
- `DriftDetectionConfig` / `DriftDetectionResult` - 漂移检测
- `InferenceMetrics` - 推理指标

## License

MIT
