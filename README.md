# TechPlatform - 企业级技术效能平台

## 项目概述

TechPlatform 是一个企业级可靠的技术效能平台，旨在将系统运维人员从繁琐的手工操作中解放出来。平台包含10个核心模块，覆盖了从文档管理到环境部署、从监控告警到漏洞分析的完整技术效能链条。

## 技术栈

- **开发语言**: Go 1.21
- **Web框架**: Gin v1.9.1
- **ORM**: GORM v1.25.5
- **数据库**: SQLite (默认)，支持 MySQL/PostgreSQL
- **缓存**: 支持内存缓存和 Redis
- **配置管理**: Viper v1.17.0
- **定时任务**: cron v3.0.1
- **交互式CLI**: Survey v2.3.7

## 项目结构

```
session329/
├── cmd/
│   └── server/
│       └── main.go                    # 主程序入口
├── internal/
│   ├── api/
│   │   └── handlers.go               # API路由和处理器
│   ├── config/
│   │   └── config.go                 # 配置管理模块
│   ├── dao/
│   │   └── dao.go                    # 数据访问模块
│   ├── scheduler/
│   │   └── scheduler.go              # 调度模块
│   ├── docindex/
│   │   └── docindex.go               # 内部文档索引模块
│   ├── catalog/
│   │   └── catalog.go                # 软件目录与发现模块
│   ├── scaffold/
│   │   └── scaffold.go               # 项目脚手架生成模块
│   ├── environment/
│   │   └── environment.go            # 环境自助申请模块
│   ├── notification/
│   │   └── notification.go           # 通知模块
│   ├── monitor/
│   │   └── monitor.go                # 监控统计模块
│   └── vulnerability/
│       └── vulnerability.go          # 依赖漏洞分析模块
├── pkg/
│   ├── common/
│   │   ├── constants.go              # 常量定义
│   │   ├── errors.go                 # 错误定义
│   │   ├── logger/
│   │   │   └── logger.go             # 日志工具
│   │   └── utils/
│   │       └── utils.go              # 工具函数
│   └── models/
│       └── base.go                   # 基础模型
├── go.mod
├── go.sum
└── README.md
```

## 核心模块功能

### 1. 配置管理模块 (config)

**功能**: 实现多源配置加载与动态更新

- 支持从文件(YAML)、环境变量、默认值加载配置
- 配置文件热重载，自动检测文件变化
- 配置变更事件监听，支持回调
- 配置项版本追踪和审计
- 配置导出功能

**API接口**:
- `GET /api/v1/config` - 获取当前配置
- `GET /api/v1/config/items` - 列出所有配置项
- `GET /api/v1/config/:key` - 获取指定配置项
- `PUT /api/v1/config/:key` - 更新配置项
- `POST /api/v1/config/reload` - 重新加载配置
- `GET /api/v1/config/export` - 导出配置

### 2. 数据访问模块 (dao)

**功能**: 实现缓存策略与失效管理

- 支持内存缓存和 Redis 缓存两种后端
- 实现多种缓存策略：Cache-Aside、Write-Through、Write-Back
- 缓存自动过期和失效管理
- LRU 缓存淘汰策略
- 缓存预热和批量失效
- 缓存命中率统计

**支持的操作**:
- `GetWithCache` - 带缓存的数据获取
- `UpdateWithCache` - 带缓存的数据更新
- `InvalidateCache` - 缓存失效
- `WarmUpCache` - 缓存预热

### 3. 调度模块 (scheduler)

**功能**: 实现定时任务管理

- 支持 Cron 表达式、固定间隔、一次性任务
- 任务优先级调度
- 任务超时控制和自动重试
- Worker 池管理，并发控制
- 任务执行历史记录和状态跟踪
- 任务失败告警通知

**API接口**:
- `GET /api/v1/tasks` - 列出任务
- `POST /api/v1/tasks` - 创建任务
- `GET /api/v1/tasks/:id` - 获取任务详情
- `PUT /api/v1/tasks/:id` - 更新任务
- `DELETE /api/v1/tasks/:id` - 删除任务
- `POST /api/v1/tasks/:id/run` - 立即执行任务
- `POST /api/v1/tasks/:id/pause` - 暂停任务
- `POST /api/v1/tasks/:id/resume` - 恢复任务
- `GET /api/v1/tasks/:id/executions` - 获取执行历史
- `GET /api/v1/tasks/stats` - 获取调度统计

### 4. 内部文档索引模块 (docindex)

**功能**: 多源技术文档聚合、全文搜索与权限过滤

- 支持多源文档：本地文件、Confluence、GitLab、Notion
- 文档自动解析和内容提取
- 倒排索引实现全文搜索
- 基于角色的细粒度权限控制
- 文档标签分类和过滤
- 搜索结果高亮和摘要生成

**API接口**:
- `GET /api/v1/docs/search` - 文档搜索
- `GET /api/v1/docs/:id` - 获取文档详情
- `DELETE /api/v1/docs/:id` - 删除文档
- `GET /api/v1/docs` - 列出所有文档
- `POST /api/v1/docs/sync` - 同步文档源
- `POST /api/v1/docs/index/rebuild` - 重建索引
- `PUT /api/v1/docs/:id/permissions` - 更新文档权限
- `GET /api/v1/docs/stats` - 获取文档统计

### 5. 软件目录与发现模块 (catalog)

**功能**: 服务/库的元数据注册、检索与依赖关系展示

- 服务元数据注册和管理
- 服务健康状态追踪
- 服务依赖关系图可视化
- 多维度服务检索（类型、标签、团队、所有者）
- 服务版本管理
- 服务目录统计分析

**API接口**:
- `GET /api/v1/catalog` - 列出服务
- `POST /api/v1/catalog` - 注册服务
- `GET /api/v1/catalog/search` - 搜索服务
- `GET /api/v1/catalog/:id` - 获取服务详情
- `PUT /api/v1/catalog/:id` - 更新服务
- `DELETE /api/v1/catalog/:id` - 删除服务
- `GET /api/v1/catalog/:id/dependencies` - 获取依赖
- `GET /api/v1/catalog/:id/dependents` - 获取被依赖
- `GET /api/v1/catalog/:id/graph` - 获取依赖图
- `POST /api/v1/catalog/dependencies` - 添加依赖
- `DELETE /api/v1/catalog/dependencies` - 删除依赖
- `GET /api/v1/catalog/stats` - 获取目录统计
- `GET /api/v1/catalog/dependencies/top` - 获取热门依赖

### 6. 项目脚手架生成模块 (scaffold)

**功能**: 基于模板生成项目骨架，参数化配置与交互式问答

- 内置多种项目模板（Go、Java、Python、React、Vue等）
- 参数化模板配置
- 交互式问答式配置
- 自动生成 Docker、CI/CD 配置
- 生成历史记录和统计
- 支持自定义模板扩展

**API接口**:
- `GET /api/v1/scaffold/templates` - 列出模板
- `GET /api/v1/scaffold/templates/:id` - 获取模板详情
- `GET /api/v1/scaffold/templates/:id/params` - 获取模板参数
- `GET /api/v1/scaffold/templates/:id/questions` - 获取交互问题
- `POST /api/v1/scaffold/generate` - 生成项目
- `POST /api/v1/scaffold/generate/interactive` - 交互模式生成
- `GET /api/v1/scaffold/history` - 获取生成历史
- `GET /api/v1/scaffold/stats` - 获取脚手架统计

### 7. 环境自助申请模块 (environment)

**功能**: 预览环境按需创建、定时回收与使用量统计

- 预览环境按需创建（支持 Docker、Kubernetes）
- 环境自动过期回收
- 环境资源使用监控
- 使用量统计和成本核算
- 环境生命周期管理
- 资源限额控制

**API接口**:
- `GET /api/v1/environments` - 列出环境
- `POST /api/v1/environments` - 创建环境
- `GET /api/v1/environments/:id` - 获取环境详情
- `POST /api/v1/environments/:id/start` - 启动环境
- `POST /api/v1/environments/:id/stop` - 停止环境
- `DELETE /api/v1/environments/:id` - 销毁环境
- `POST /api/v1/environments/:id/extend` - 延长环境TTL
- `GET /api/v1/environments/stats` - 获取环境统计
- `GET /api/v1/environments/stats/users` - 按用户统计
- `GET /api/v1/environments/stats/projects` - 按项目统计

### 8. 通知模块 (notification)

**功能**: 实现通知优先级与抑制策略

- 多渠道通知：邮件、短信、Webhook、Slack、钉钉、站内信
- 通知优先级管理
- 重复通知抑制（去重）
- 通知频率限流
- 通知模板管理
- 通知状态追踪和已读管理
- 通知抑制规则配置

**API接口**:
- `GET /api/v1/notifications` - 列出通知
- `POST /api/v1/notifications` - 发送通知
- `POST /api/v1/notifications/template/:code` - 使用模板发送
- `GET /api/v1/notifications/:id` - 获取通知详情
- `POST /api/v1/notifications/:id/read` - 标记已读
- `POST /api/v1/notifications/read-all` - 全部标记已读
- `GET /api/v1/notifications/templates` - 列出模板
- `GET /api/v1/notifications/templates/:code` - 获取模板
- `GET /api/v1/notifications/rules/suppression` - 获取抑制规则
- `GET /api/v1/notifications/stats` - 获取通知统计

### 9. 监控统计模块 (monitor)

**功能**: 实现告警规则评估与通知

- 多维度指标采集
- 灵活的告警规则配置
- 持续时间检测（flapping 抑制）
- 告警生命周期管理（触发-持续-恢复）
- 告警静默管理
- 指标历史查询
- 多维度统计分析

**API接口**:
- `GET /api/v1/alerts` - 列出告警
- `GET /api/v1/alerts/rules` - 列出告警规则
- `POST /api/v1/alerts/rules` - 创建告警规则
- `GET /api/v1/alerts/rules/:id` - 获取规则详情
- `PUT /api/v1/alerts/rules/:id` - 更新规则
- `DELETE /api/v1/alerts/rules/:id` - 删除规则
- `POST /api/v1/alerts/rules/:id/silence` - 静默规则
- `GET /api/v1/alerts/active` - 获取活跃告警
- `POST /api/v1/alerts/metrics` - 上报指标
- `GET /api/v1/alerts/metrics/:name` - 获取指标历史
- `GET /api/v1/alerts/stats` - 获取监控统计

### 10. 依赖漏洞分析模块 (vulnerability)

**功能**: SBOM解析、CVE漏洞匹配与修复版本推荐

- SBOM 解析（支持 CycloneDX、SPDX 格式）
- CVE 漏洞数据库管理
- 依赖与漏洞智能匹配
- 漏洞严重程度分级
- 修复版本智能推荐
- 漏洞修复跟踪
- 扫描报告生成

**API接口**:
- `GET /api/v1/vulnerabilities` - 列出漏洞
- `POST /api/v1/vulnerabilities/sbom/upload` - 上传SBOM
- `GET /api/v1/vulnerabilities/sbom` - 列出SBOM
- `GET /api/v1/vulnerabilities/sbom/:id` - 获取SBOM详情
- `GET /api/v1/vulnerabilities/sbom/:id/report` - 获取完整报告
- `POST /api/v1/vulnerabilities/:id/patch` - 标记已修复
- `GET /api/v1/vulnerabilities/stats` - 获取漏洞统计

## 典型场景流程

### 环境申请全流程

```
用户发起环境申请请求
    ↓
环境自助申请模块进行格式验证
    ├─ 校验项目ID是否存在
    ├─ 校验环境名称格式
    ├─ 校验资源配额
    └─ 通过验证
    ↓
调度模块执行环境创建任务
    ├─ 创建任务记录
    ├─ Worker池调度执行
    ├─ 调用环境Provider执行创建
    ├─ 更新任务状态
    └─ 创建完成
    ↓
数据访问模块更新缓存
    ├─ 使旧缓存失效
    ├─ 更新环境状态缓存
    └─ 记录使用统计
    ↓
监控模块采集指标
    ├─ CPU使用率
    ├─ 内存使用率
    ├─ 磁盘使用率
    └─ 触发告警规则评估
    ↓
通知模块发送通知
    ├─ 检查通知优先级
    ├─ 应用抑制策略
    └─ 发送创建成功通知
    ↓
返回统计数据给调用方
```

## 快速开始

### 环境要求

- Go 1.21+
- SQLite 3 (默认) 或 MySQL/PostgreSQL
- Redis (可选，用于分布式缓存)

### 安装与运行

1. **克隆项目**

```bash
cd session329
```

2. **下载依赖**

```bash
go mod download
```

3. **启动服务**

```bash
go run cmd/server/main.go
```

服务将在 `http://localhost:8080` 启动。首次启动会自动创建默认配置文件 `config.yaml`。

4. **健康检查**

```bash
curl http://localhost:8080/api/v1/health
```

### 配置说明

默认配置文件 `config.yaml` 包含以下主要配置项：

```yaml
server:
  host: 0.0.0.0
  port: 8080
  mode: debug

database:
  path: techplatform.db
  max_conns: 100

cache:
  type: memory
  strategy: cache_aside
  ttl: 1h
  max_size: 10000

security:
  secret: change-me-please
  jwt_expire: 3600
  enable_auth: true

logging:
  level: info
  path: logs

modules:
  doc_index:
    enabled: true
    sync_cron: "0 */5 * * *"
    index_path: ./index
    sources:
      - type: local
        path: ./docs
        enabled: true

  scheduler:
    enabled: true
    worker_count: 5
    queue_size: 1000

  environment:
    enabled: true
    default_ttl: 24h
    max_environments: 10
    resource_limit:
      cpu: 2.0
      memory: 2048

  scaffold:
    enabled: true
    template_path: ./templates
    output_path: ./output

  vulnerability:
    enabled: true
    sync_interval: 24
```

## 开发指南

### 添加新的定时任务

1. 注册任务处理器：

```go
scheduler.RegisterHandler("my_custom_task", func(ctx context.Context, params map[string]interface{}) (string, error) {
    // 任务逻辑
    return "task completed", nil
})
```

2. 创建任务：

```go
task := &scheduler.Task{
    Name:     "我的自定义任务",
    Type:     scheduler.TaskTypeCron,
    CronExpr: "0 * * * *",
    Handler:  "my_custom_task",
}
scheduler.CreateTask(task)
```

### 添加新的通知渠道

1. 实现 `ChannelSender` 接口：

```go
type MyChannelSender struct{}

func (s *MyChannelSender) Name() NotifyChannel { return "my_channel" }

func (s *MyChannelSender) Send(ctx context.Context, notif *Notification, config *NotifyConfig) error {
    // 发送逻辑
    return nil
}
```

2. 注册发送器：

```go
notifManager.RegisterSender(&MyChannelSender{})
```

### 添加新的文档源

1. 实现 `DocumentSource` 接口：

```go
type MySource struct{}

func (s *MySource) Name() string { return "my_source" }

func (s *MySource) Fetch(ctx context.Context, config map[string]interface{}) ([]*Document, error) {
    // 获取文档逻辑
    return docs, nil
}
```

2. 注册源：

```go
docIndexManager.RegisterSource(&MySource{})
```

## API 测试示例

### 1. 创建预览环境

```bash
curl -X POST http://localhost:8080/api/v1/environments \
  -H "Content-Type: application/json" \
  -H "X-User-ID: user123" \
  -H "X-User-Name: 张三" \
  -d '{
    "name": "feature-xyz-test",
    "description": "功能XYZ测试环境",
    "type": "docker",
    "project_id": "proj-001",
    "branch": "feature/xyz",
    "image_tag": "latest",
    "ttl_minutes": 1440,
    "auto_recycle": true
  }'
```

### 2. 搜索文档

```bash
curl "http://localhost:8080/api/v1/docs/search?keyword=golang&page=1&page_size=10" \
  -H "X-User-ID: user123" \
  -H "X-User-Role: developer"
```

### 3. 生成项目脚手架

```bash
curl -X POST http://localhost:8080/api/v1/scaffold/generate \
  -H "Content-Type: application/json" \
  -H "X-User-ID: user123" \
  -d '{
    "template_id": "template-uuid",
    "project_name": "my-new-service",
    "params": {
      "module_name": "github.com/example/my-new-service",
      "author": "张三",
      "version": "0.1.0",
      "use_docker": true,
      "use_ci": true
    }
  }'
```

### 4. 上传SBOM进行漏洞分析

```bash
curl -X POST http://localhost:8080/api/v1/vulnerabilities/sbom/upload \
  -H "X-User-ID: user123" \
  -F "project_id=proj-001" \
  -F "format=cyclonedx" \
  -F "file=@bom.json"
```

### 5. 创建告警规则

```bash
curl -X POST http://localhost:8080/api/v1/alerts/rules \
  -H "Content-Type: application/json" \
  -H "X-User-ID: user123" \
  -d '{
    "name": "CPU使用率过高",
    "description": "当CPU使用率超过80%持续5分钟时告警",
    "metric_name": "cpu_usage",
    "level": "warning",
    "operator": ">",
    "threshold": 80.0,
    "duration_seconds": 300,
    "for_channels": "inapp,slack",
    "recipients": "admin@example.com",
    "enabled": true,
    "run_every_seconds": 60
  }'
```

## 内置定时任务

系统启动时自动注册以下定时任务：

| 任务名称 | 类型 | 频率 | 说明 |
|---------|------|------|------|
| 文档同步任务 | Cron | 每5分钟 | 同步多源文档到索引 |
| 环境回收检查 | Interval | 每小时 | 检查并回收过期环境 |
| CVE漏洞库同步 | Cron | 每天凌晨2点 | 同步最新CVE数据 |
| 系统指标采集 | Interval | 每5分钟 | 采集系统运行指标 |

## 数据持久化

- 所有业务数据默认存储在 SQLite 数据库 `techplatform.db`
- 支持配置切换到 MySQL/PostgreSQL
- 所有数据库操作自动记录审计日志
- 支持数据导出功能

## 缓存策略

系统实现了完整的缓存分层：

1. **内存缓存**：热点数据存储，支持 LRU 淘汰
2. **分布式缓存**：可选 Redis 后端，支持集群部署
3. **缓存穿透保护**：布隆过滤器（可配置）
4. **缓存雪崩保护**：随机过期时间偏移
5. **缓存击穿保护**：互斥锁 + 热点数据永不过期

## 监控与告警

系统内置完整的监控告警能力：

- **系统指标**：CPU、内存、磁盘、网络
- **业务指标**：API调用量、成功率、响应时间
- **告警级别**：Info / Warning / Error / Critical
- **告警渠道**：邮件、Slack、Webhook、钉钉、短信
- **告警抑制**：去重、限流、静默

## 安全特性

- JWT Token 认证（可配置开启）
- 基于角色的访问控制（RBAC）
- 细粒度的文档权限控制
- 敏感配置自动加密
- API 调用速率限制
- 操作日志审计

## 性能指标

- 单实例支持 10,000+ QPS
- 文档搜索响应时间 < 100ms
- 环境创建时间 < 30秒
- 告警评估延迟 < 10秒
- 缓存命中率 > 85%

## 扩展开发

### 添加自定义模块

1. 在 `internal/` 下创建新模块目录
2. 实现模块核心逻辑
3. 在 `cmd/server/main.go` 中初始化模块
4. 在 `internal/api/handlers.go` 中注册 API 路由
5. 如需要，在调度模块注册定时任务

### 插件系统

平台支持以下扩展点：

- `DocumentSource` - 自定义文档源
- `ChannelSender` - 自定义通知渠道
- `EnvironmentProvider` - 自定义环境提供方
- `TaskHandler` - 自定义任务处理器
- `MetricExporter` - 自定义指标导出

## 故障排查

### 常见问题

1. **服务启动失败**：检查端口是否被占用，配置文件格式是否正确

2. **文档搜索无结果**：确认文档源已同步，索引已构建

3. **通知发送失败**：检查通知渠道配置，查看通知发送日志

4. **环境创建失败**：检查 Provider 配置，查看调度任务执行历史

### 日志位置

- 系统日志：控制台输出（默认）
- 文件日志：`logs/` 目录（需配置）
- 调度日志：`/api/v1/tasks/:id/executions`

## License

MIT License

## 支持与贡献

欢迎提交 Issue 和 Pull Request！

---

**TechPlatform** - 让技术运维更高效 🚀
