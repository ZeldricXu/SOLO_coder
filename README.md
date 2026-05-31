# Task Manager - 定时任务管理平台

一个基于Go语言实现的综合性定时任务管理平台，包含9个核心模块。

## 模块列表

### 1. 调度模块 (scheduler)
- 基于Cron表达式的定时任务调度
- 任务创建、修改、删除、触发
- 任务运行历史记录
- 支持秒级精度调度

### 2. SLO燃尽监控模块 (slomonitor)
- SLI指标实时计算
- 错误预算消耗追踪
- 燃尽速率监控
- 预算耗尽告警

### 3. 存储管理模块 (storage)
- 文件上传与下载
- 生命周期管理（TTL）
- 存储分级（标准/低频/归档）
- 自动GC过期文件

### 4. 告警规则评估引擎 (alerter)
- 告警规则管理
- 定时评估与触发
- 告警状态跟踪
- 支持多通道通知

### 5. 日志管道处理模块 (logpipeline)
- 日志收集与解析
- 多级过滤（级别、正则）
- 智能路由分发
- 日志聚合统计

### 6. 日志模块 (logger)
- 基于Zap的结构化日志
- 动态日志级别调整
- 高性能零分配

### 7. 服务依赖拓扑模块 (topology)
- 基于Span构建服务调用拓扑
- 关键路径分析
- 循环依赖检测
- 高错误服务识别

### 8. 分布式追踪采集模块 (tracing)
- Span数据接收
- 多种采样策略（概率、限流、错误优先）
- 尾部采样
- Trace完整存储

### 9. 通知模块 (notifier)
- 多通道支持（邮件、短信、Webhook）
- 送达追踪
- 指数退避重试
- 通知状态管理

## 技术栈

- **语言**: Go 1.21+
- **Web框架**: Gin
- **数据库**: GORM + SQLite (可切换PostgreSQL)
- **日志**: Zap
- **调度**: robfig/cron

## 快速开始

```bash
# 克隆项目
cd session144

# 下载依赖
go mod download

# 运行
go run cmd/main.go
```

服务将在 `http://localhost:8080` 启动。

## API接口

### 调度管理
```
POST   /api/v1/tasks              # 创建任务
GET    /api/v1/tasks              # 任务列表
GET    /api/v1/tasks/:id          # 任务详情
PUT    /api/v1/tasks/:id          # 更新任务
DELETE /api/v1/tasks/:id          # 删除任务
POST   /api/v1/tasks/:id/trigger  # 立即触发
GET    /api/v1/tasks/:id/runs     # 运行历史
```

### SLO管理
```
POST   /api/v1/slos               # 创建SLO
GET    /api/v1/slos               # SLO列表
GET    /api/v1/slos/:id/status    # SLO状态
POST   /api/v1/slos/:id/reset     # 重置预算
```

### 告警管理
```
POST   /api/v1/alerts/rules       # 创建规则
GET    /api/v1/alerts/rules       # 规则列表
GET    /api/v1/alerts             # 告警列表
POST   /api/v1/alerts/:id/resolve # 确认告警
```

### 文件存储
```
POST   /api/v1/files              # 上传文件
GET    /api/v1/files              # 文件列表
GET    /api/v1/files/:id          # 下载文件
DELETE /api/v1/files/:id          # 删除文件
GET    /api/v1/files/stats        # 存储统计
```

### 追踪
```
POST   /api/v1/traces/spans       # 上报Span
GET    /api/v1/traces/:id         # Trace详情
GET    /api/v1/traces             # Span列表
```

### 拓扑
```
GET    /api/v1/topology                        # 拓扑图
GET    /api/v1/topology/services/:name/deps    # 服务依赖
GET    /api/v1/topology/analysis/critical-path # 关键路径
POST   /api/v1/topology/snapshot               # 生成快照
```

### 通知
```
POST   /api/v1/notifications      # 发送通知
GET    /api/v1/notifications      # 通知列表
GET    /api/v1/notifications/stats # 统计
```

### 日志级别
```
GET    /api/v1/logger/level       # 获取日志级别
POST   /api/v1/logger/level       # 设置日志级别
```

## 典型使用示例

### 创建定时任务
```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "daily-backup",
    "cron_expr": "0 0 2 * * *",
    "command": "backup.sh",
    "enabled": true
  }'
```

### 创建SLO
```bash
curl -X POST http://localhost:8080/api/v1/slos \
  -H "Content-Type: application/json" \
  -d '{
    "name": "api-availability",
    "service_name": "api-gateway",
    "sli": "availability",
    "target_percent": 99.9,
    "error_budget": 0.1,
    "window_days": 30
  }'
```

### 上报Span
```bash
curl -X POST http://localhost:8080/api/v1/traces/spans \
  -H "Content-Type: application/json" \
  -d '{
    "trace_id": "trace-001",
    "span_id": "span-001",
    "service": "api-gateway",
    "operation": "GET /users",
    "start_time": "2026-05-17T10:00:00Z",
    "end_time": "2026-05-17T10:00:00.100Z",
    "status_code": 200
  }'
```

## 架构特点

1. **模块化设计**: 每个模块独立封装，低耦合高内聚
2. **高并发**: 充分利用Go的goroutine和channel
3. **可观测**: 完整的日志、追踪、指标体系
4. **可扩展**: 接口化设计，易于扩展新功能
5. **容错性**: 重试机制、熔断保护、优雅关闭
