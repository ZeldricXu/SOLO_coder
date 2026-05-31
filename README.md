# Task Execution Status Tracking System

生产级高可用的任务执行状态追踪系统，提供完整的DevOps工具链支持。

## 项目概述

本系统是一个事件驱动架构的综合平台，包含10个核心模块，为系统运维人员提供高效、可靠的任务管理和DevOps支持能力。

## 技术栈

- **语言**: Go 1.21
- **Web框架**: Gin v1.9.1
- **日志**: Zap (结构化日志)
- **配置**: YAML
- **唯一ID**: UUID

## 核心模块

### 1. 调度模块 (Scheduler)
- 任务提交、状态追踪、重试机制
- 多worker并发处理
- 任务优先级支持
- 事件驱动架构

### 2. 代码质量门禁模块 (Quality Gate)
- 多语言静态分析 (Go, Python, Java, JavaScript, TypeScript, C++, Rust)
- 可配置的规则引擎
- 质量门禁阈值配置
- 详细的分析报告

### 3. 配置管理模块 (Config)
- 配置校验与默认值管理
- 支持多命名空间
- 可插拔的验证器
- 版本管理

### 4. 存储管理模块 (Storage)
- 数据持久化与内存缓存
- 自动备份与恢复
- GZIP压缩存储
- 旧备份自动清理

### 5. API契约测试模块 (API Contract)
- OpenAPI/GraphQL/JSON Schema校验
- Mock Server自动生成
- 请求/响应验证
- 动态端点配置

### 6. 环境自助申请模块 (Environment)
- 预览环境按需创建
- 定时自动回收
- 使用量统计
- 配额管理

### 7. 项目脚手架生成模块 (Scaffold)
- 基于模板的项目生成
- 参数化配置
- 交互式问答支持
- 内置Go/React/Python模板

### 8. 日志模块 (Logger)
- 结构化日志输出
- 支持JSON/Console格式
- 多级别日志
- 高性能零分配

### 9. 特性开关管理模块 (Feature Flag)
- 开关规则定义
- 用户分群定向
- 渐进式放量
- 评估统计

### 10. API网关模块 (API Gateway)
- 请求日志记录
- 分布式链路追踪
- 路由管理
- 性能统计

## 快速开始

### 安装依赖

```bash
cd session149
go mod download
```

### 编译运行

```bash
go build -o bin/tasktracker ./cmd/main.go
./bin/tasktracker
```

### 健康检查

```bash
curl http://localhost:8080/health
```

## API接口文档

### 调度模块

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/tasks | 创建任务 |
| GET | /api/v1/tasks | 任务列表 |
| GET | /api/v1/tasks/:id/status | 任务状态 |
| POST | /api/v1/tasks/batch | 批量操作 |
| POST | /api/v1/tasks/:id/cancel | 取消任务 |
| GET | /api/v1/tasks/stats | 调度统计 |

### 代码质量门禁

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/quality/rules | 规则列表 |
| POST | /api/v1/quality/analyze | 代码分析 |
| GET | /api/v1/quality/thresholds | 阈值配置 |
| POST | /api/v1/quality/thresholds | 设置阈值 |

### 存储管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/storage/:key | 保存数据 |
| GET | /api/v1/storage/:key | 加载数据 |
| DELETE | /api/v1/storage/:key | 删除数据 |
| POST | /api/v1/storage/:key/backup | 创建备份 |
| POST | /api/v1/storage/backups/:backupId/restore | 恢复备份 |
| GET | /api/v1/storage/backups | 备份列表 |

### API契约测试

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/contracts/schemas | 加载Schema |
| GET | /api/v1/contracts/schemas | Schema列表 |
| POST | /api/v1/contracts/validate/:schemaId | 校验Payload |
| POST | /api/v1/contracts/mock/endpoints | 添加Mock端点 |
| GET | /api/v1/contracts/mock/endpoints | Mock端点列表 |
| POST | /api/v1/contracts/mock/start | 启动Mock Server |
| POST | /api/v1/contracts/mock/stop | 停止Mock Server |

### 环境管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/environments | 创建环境 |
| GET | /api/v1/environments | 环境列表 |
| GET | /api/v1/environments/:id | 环境详情 |
| POST | /api/v1/environments/:id/start | 启动环境 |
| POST | /api/v1/environments/:id/stop | 停止环境 |
| DELETE | /api/v1/environments/:id | 销毁环境 |
| POST | /api/v1/environments/:id/extend | 延长TTL |
| GET | /api/v1/environments/usage/stats | 使用统计 |
| GET | /api/v1/environments/usage/quota | 配额使用 |

### 项目脚手架

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/scaffold/templates | 模板列表 |
| GET | /api/v1/scaffold/templates/:name/questions | 获取交互式问题 |
| POST | /api/v1/scaffold/generate | 生成项目 |

### 特性开关

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/features | 创建开关 |
| GET | /api/v1/features | 开关列表 |
| GET | /api/v1/features/:id | 开关详情 |
| PUT | /api/v1/features/:id | 更新开关 |
| DELETE | /api/v1/features/:id | 删除开关 |
| POST | /api/v1/features/:id/evaluate | 评估开关 |
| POST | /api/v1/features/segments | 创建分群 |
| GET | /api/v1/features/segments | 分群列表 |
| GET | /api/v1/features/stats | 评估统计 |

### API网关

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/gateway/routes | 路由列表 |
| POST | /api/v1/gateway/routes | 添加路由 |
| DELETE | /api/v1/gateway/routes | 删除路由 |
| GET | /api/v1/gateway/logs | 请求日志 |
| GET | /api/v1/gateway/traces/:traceId | 链路追踪 |
| GET | /api/v1/gateway/stats | 网关统计 |

## 使用示例

### 创建任务

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "data-processing",
    "type": "quality_analysis",
    "payload": {"project_id": "proj_123"},
    "priority": 1
  }'
```

### 代码质量分析

```bash
curl -X POST http://localhost:8080/api/v1/quality/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "project_id": "my-project",
    "language": "go",
    "files": {
      "main.go": "package main\n\nimport \"fmt\"\n\nfunc main() {\n    fmt.Println(\"hello\")\n}"
    }
  }'
```

### 创建预览环境

```bash
curl -X POST http://localhost:8080/api/v1/environments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "feature-x-preview",
    "type": "preview",
    "owner": "user@example.com",
    "project_id": "proj_123",
    "branch": "feature/x"
  }'
```

### 生成项目脚手架

```bash
curl -X POST http://localhost:8080/api/v1/scaffold/generate \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-service",
    "template_name": "go-service",
    "params": {
      "module_name": "github.com/example/myservice",
      "service_name": "my-service",
      "port": 8080
    },
    "output_dir": "./generated",
    "overwrite": true
  }'
```

## 架构设计

### 核心流程

```
请求输入 → 参数校验 → 配置加载 → 资源分配 → 核心处理 → 结果持久化 → 事件发射 → 响应输出
```

### 异常处理

- 存储故障: 自动重试3次后失败告警
- 超时处理: 可配置的任务超时机制
- 资源隔离: 信号量控制最大并发数

### 数据模型

- **Entity**: 核心实体模型
- **Config**: 配置定义模型
- **RunInstance**: 运行实例模型
- **Snapshot**: 统计快照模型

## 目录结构

```
session149/
├── cmd/
│   └── main.go              # 主程序入口
├── internal/
│   ├── logger/              # 日志模块
│   ├── config/              # 配置管理
│   ├── scheduler/           # 调度模块
│   ├── qualitygate/         # 代码质量门禁
│   ├── storage/             # 存储管理
│   ├── apicontract/         # API契约测试
│   ├── environment/         # 环境管理
│   ├── scaffold/            # 脚手架生成
│   ├── featureflag/         # 特性开关
│   ├── gateway/             # API网关
│   └── models/              # 数据模型
├── configs/
│   └── config.yaml          # 示例配置
├── templates/               # 模板目录
├── go.mod
└── README.md
```

## 已知风险与优化策略

1. **单点故障**: 实现主备切换或无状态水平扩展，配合健康检测自动摘除故障节点
2. **安全漏洞**: 集成依赖扫描工具到CI流程，阻断含高危漏洞的构建
3. **资源耗尽**: 引入信号量或Channel控制最大并发数，超额请求排队或快速拒绝

## License

MIT
