# SoloCoder Session138 - 日志级别动态调整平台

## 项目概述

这是一个轻量高效的服务平台，提供10个核心模块功能，以事件驱动架构为基础，支持快速迭代开发。

## 技术栈

- **语言**: Go 1.21+
- **Web框架**: Gin
- **数据库**: PostgreSQL + GORM
- **缓存**: Redis + go-redis
- **日志**: Zap (结构化日志)

## 项目结构

```
session138/
├── cmd/
│   └── main.go                 # 主入口文件
├── internal/
│   ├── catalog/                # 软件目录与发现模块
│   │   └── catalog.go
│   ├── scaffold/               # 项目脚手架生成模块
│   │   └── scaffold.go
│   ├── vulnerability/          # 依赖漏洞分析模块
│   │   └── vulnerability.go
│   ├── logger/                 # 日志模块（日志级别动态调整）
│   │   └── logger.go
│   ├── core/                   # 核心处理模块
│   │   └── core.go
│   ├── gateway/                # API网关模块
│   │   └── gateway.go
│   ├── contract/               # API契约测试模块
│   │   └── contract.go
│   ├── environment/            # 环境自助申请模块
│   │   └── environment.go
│   ├── documentation/          # 内部文档索引模块
│   │   └── documentation.go
│   └── featureflag/            # 特性开关管理模块
│       └── featureflag.go
├── pkg/
│   ├── models/                 # 数据模型
│   │   └── models.go
│   ├── config/                 # 配置管理
│   │   └── config.go
│   ├── utils/                  # 工具函数
│   │   └── utils.go
│   ├── database/               # 数据库连接
│   │   └── database.go
│   └── cache/                  # Redis缓存
│       └── redis.go
├── api/
│   └── openapi.yaml            # OpenAPI规范文档
├── configs/                    # 配置文件目录
├── templates/                  # 模板文件目录
├── go.mod
└── README.md
```

## 核心模块功能

### 1. 软件目录与发现模块 ([catalog.go](file:///Users/huangzitong/SoloCoder/session138/internal/catalog/catalog.go))
- 服务/库的元数据注册与检索
- 依赖关系展示与依赖图可视化
- 支持按类型、标签、关键字搜索

### 2. 项目脚手架生成模块 ([scaffold.go](file:///Users/huangzitong/SoloCoder/session138/internal/scaffold/scaffold.go))
- 基于模板生成项目骨架
- 参数化配置与交互式问答
- 支持Go、Python、React等多种项目类型

### 3. 依赖漏洞分析模块 ([vulnerability.go](file:///Users/huangzitong/SoloCoder/session138/internal/vulnerability/vulnerability.go))
- SBOM解析与漏洞扫描
- CVE漏洞匹配与严重程度分级
- 修复版本推荐与升级建议

### 4. 日志模块 - 日志级别动态调整 ([logger.go](file:///Users/huangzitong/SoloCoder/session138/internal/logger/logger.go))
- 支持按服务粒度动态调整日志级别
- Redis Pub/Sub 实时同步配置变更
- Zap结构化日志，支持文件滚动切割
- HTTP中间件自动记录请求日志

### 5. 核心处理模块 ([core.go](file:///Users/huangzitong/SoloCoder/session138/internal/core/core.go))
- 数据转换与标准化核心逻辑
- 可配置的转换规则引擎
- 处理超时自动取消与降级返回
- 指标收集与性能监控

### 6. API网关模块 ([gateway.go](file:///Users/huangzitong/SoloCoder/session138/internal/gateway/gateway.go))
- 动态路由注册与请求转发
- REST/GraphQL/gRPC协议转换
- 限流、超时控制
- 请求头注入与修改

### 7. API契约测试模块 ([contract.go](file:///Users/huangzitong/SoloCoder/session138/internal/contract/contract.go))
- OpenAPI/GraphQL Schema校验
- Mock Server自动生成与管理
- 契约测试用例自动生成
- 端点连通性验证

### 8. 环境自助申请模块 ([environment.go](file:///Users/huangzitong/SoloCoder/session138/internal/environment/environment.go))
- 预览环境按需创建
- TTL定时自动回收
- 使用量统计与报表
- 环境续期与管理

### 9. 内部文档索引模块 ([documentation.go](file:///Users/huangzitong/SoloCoder/session138/internal/documentation/documentation.go))
- 多源技术文档聚合
- 全文搜索与相关性排序
- 基于用户组的权限过滤
- 标签分类与筛选

### 10. 特性开关管理模块 ([featureflag.go](file:///Users/huangzitong/SoloCoder/session138/internal/featureflag/featureflag.go))
- 开关规则定义与管理
- 基于用户分群的定向放量
- 渐进式放量（按百分比）
- 多维度规则匹配（地区、邮箱、用户组）

## 核心API端点

### 日志级别管理
```bash
# 设置日志级别
POST /api/v1/logger/level
{"service": "user-service", "level": "debug"}

# 获取所有日志级别配置
GET /api/v1/logger/levels

# 重置服务日志级别
DELETE /api/v1/logger/level/{service}
```

### 服务目录
```bash
# 注册服务
POST /api/v1/catalog/services

# 搜索服务
POST /api/v1/catalog/services/search

# 获取依赖图
GET /api/v1/catalog/dependency-graph
```

### 项目脚手架
```bash
# 获取模板列表
GET /api/v1/scaffold/templates

# 生成项目
POST /api/v1/scaffold/generate
{"template_name": "go-service", "params": {"module_name": "github.com/example/app"}}
```

### 漏洞分析
```bash
# 分析SBOM
POST /api/v1/vulnerability/sbom/analyze

# 获取CVE列表
GET /api/v1/vulnerability/cves?severity=CRITICAL
```

### 特性开关
```bash
# 创建开关
POST /api/v1/feature-flags

# 评估开关
POST /api/v1/feature-flags/evaluate
{"flag_name": "new_ui", "user_id": "user123", "user_groups": ["beta-testers"]}
```

## 快速开始

### 环境要求
- Go 1.21+
- PostgreSQL 14+
- Redis 6+

### 安装依赖
```bash
cd session138
go mod download
```

### 配置环境变量
```bash
export SERVER_HOST=0.0.0.0
export SERVER_PORT=8080
export DB_HOST=localhost
export DB_PORT=5432
export DB_USER=postgres
export DB_PASSWORD=postgres
export DB_NAME=solocoder
export REDIS_HOST=localhost
export REDIS_PORT=6379
export LOG_LEVEL=info
```

### 启动服务
```bash
go run cmd/main.go
```

### 健康检查
```bash
curl http://localhost:8080/health
```

## 数据模型

### 核心实体模型 ([models.go](file:///Users/huangzitong/SoloCoder/session138/pkg/models/models.go))
- Entity: 通用实体模型
- ConfigDefinition: 配置定义模型
- RunInstance: 运行实例模型
- Snapshot: 统计快照模型
- Service: 服务元数据模型
- LogLevelConfig: 日志级别配置
- Environment: 预览环境
- FeatureFlag: 特性开关
- Vulnerability: 漏洞信息

## 架构设计

### 事件驱动架构
- 各模块通过事件进行解耦
- 支持异步处理与扩展

### 核心处理流程
```
输入 → 参数校验 → 加载配置 → 资源获取 → 核心处理 → 结果持久化 → 事件发布 → 输出
```

### 异常处理
- 处理超时自动取消
- 事务回滚机制
- 降级返回策略

## 已知风险与优化

1. **数据丢失风险**: 采用预写日志(WAL)机制确保持久化
2. **并发冲突**: 乐观锁配合重试机制，高冲突时自动降级为悲观锁
3. **配置漂移**: 配置版本化管理，定期Diff对比告警

## License

MIT
