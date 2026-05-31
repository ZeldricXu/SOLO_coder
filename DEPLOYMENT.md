# 部署指南

本文档介绍如何部署日志轮转与归档平台到不同环境。

## 目录结构

```
session129/
├── .github/workflows/
│   ├── ci.yml                 # CI 流水线配置
│   └── cd.yml                 # CD 流水线配置
├── configs/
│   ├── config.dev.yaml        # 开发环境配置
│   ├── config.staging.yaml    # 预发布环境配置
│   └── config.prod.yaml       # 生产环境配置
├── deploy/
│   ├── helm/logrotate/        # Helm Chart
│   │   ├── Chart.yaml
│   │   ├── values.yaml
│   │   ├── values.staging.yaml
│   │   ├── values.production.yaml
│   │   └── templates/
│   │       ├── _helpers.tpl
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       ├── ingress.yaml
│   │       ├── serviceaccount.yaml
│   │       └── hpa.yaml
│   └── prometheus/
│       └── prometheus.yml     # Prometheus 配置
├── scripts/
│   └── deploy.sh              # 部署脚本
├── docker-compose.yml         # Docker Compose 编排
├── Dockerfile                 # 容器镜像构建
├── Makefile                   # 构建脚本
└── .golangci.yml              # 代码规范配置
```

## 快速开始

### 本地开发

```bash
# 安装依赖
make deps

# 安装开发工具
make install-tools

# 运行开发环境
make run
# 或指定环境
ENV=staging make run
```

### 代码检查

```bash
# 格式化代码
make fmt

# 运行所有检查
make check

# 运行单元测试
make test

# 生成覆盖率报告
make test-coverage
```

### 本地构建

```bash
# 构建当前平台
make build

# 构建所有平台 (linux/darwin, amd64/arm64)
make build-all
```

### Docker 构建

```bash
# 构建镜像
make docker-build

# 运行容器
make docker-run

# 使用 Docker Compose 启动完整栈
make compose-up
# 停止服务
make compose-down
```

## 环境配置

### 开发环境 (Development)

- 日志级别: Debug
- 控制台输出启用
- 数据库: 本地 PostgreSQL
- Redis: 本地 Redis
- 链路追踪: 可选

```bash
cp .env.example .env
# 修改 .env 中的配置

ENV=dev make run
```

### 预发布环境 (Staging)

- 日志级别: Info
- 文件输出启用
- 完整的监控链路
- 与生产环境一致的配置

部署到预发布环境:

```bash
# 使用部署脚本
./scripts/deploy.sh staging --tag v1.0.0-rc1

# 或手动使用 Helm
helm upgrade --install logrotate ./deploy/helm/logrotate \
  --namespace logrotate \
  --create-namespace \
  --values ./deploy/helm/logrotate/values.staging.yaml \
  --set image.tag=v1.0.0-rc1
```

### 生产环境 (Production)

- 日志级别: Warn
- 高可用配置 (3副本起步)
- 自动扩缩容启用
- 完整的安全配置

部署到生产环境:

```bash
# 使用部署脚本
./scripts/deploy.sh production --tag v1.0.0

# 或手动使用 Helm
helm upgrade --install logrotate ./deploy/helm/logrotate \
  --namespace logrotate \
  --create-namespace \
  --values ./deploy/helm/logrotate/values.production.yaml \
  --set image.tag=v1.0.0
```

## CI/CD 流水线

### CI 流程 (ci.yml)

触发条件:
- Push 到 main/develop 或 feature/bugfix/hotfix/release 分支
- Pull Request 到 main/develop
- 手动触发

执行步骤:
1. **Setup**: 生成版本号
2. **Lint**: 运行 golangci-lint
3. **Test**: 跨平台运行单元测试, 生成覆盖率报告
4. **Security Scan**: 运行 gosec 安全扫描
5. **Build**: 构建多平台二进制
6. **Docker Build**: 构建并推送镜像 (仅 main/develop/release 分支)

### CD 流程 (cd.yml)

触发条件:
- Tag 推送 (v* 正式版本)
- Release 发布
- 手动触发

执行步骤:
1. **Prepare**: 确定部署环境和版本
2. **Staging Deploy**: 部署到预发布环境
3. **Production Deploy**: 部署到生产环境 (金丝雀发布)
4. **Notify**: 发送部署通知

## Kubernetes 部署

### 前置要求

- Kubernetes 1.24+
- Helm 3.10+
- kubectl 配置正确
- 镜像仓库访问权限

### Helm 部署

```bash
# 添加依赖
helm dependency update ./deploy/helm/logrotate

# 安装/升级
helm upgrade --install logrotate ./deploy/helm/logrotate \
  --namespace logrotate \
  --create-namespace \
  --values ./deploy/helm/logrotate/values.production.yaml \
  --set image.repository=registry.example.com/logrotate \
  --set image.tag=v1.0.0

# 验证
kubectl get pods -n logrotate
kubectl get svc -n logrotate
kubectl get ingress -n logrotate
```

### 核心配置

**自动扩缩容 (HPA)**:
- 最小副本: 3
- 最大副本: 20
- CPU 目标: 70%
- 内存目标: 80%

** Pod 安全策略**:
- 非 root 用户运行
- 只读根文件系统
- 禁用所有能力

**Pod 分布**:
- 跨节点反亲和
- 跨可用区分布

**滚动更新**:
- 最大超出: 25%
- 最大不可用: 25%
- 最小就绪时间: 30秒

## 监控与告警

### Prometheus 配置

部署已包含 Prometheus 监控:

```bash
# 查看监控目标
kubectl port-forward svc/prometheus-server 9090:9090 -n logrotate
# 访问 http://localhost:9090
```

关键指标:
- `http_requests_total`: HTTP 请求总数
- `http_request_duration_seconds`: HTTP 请求延迟
- `logrotate_tasks_total`: 任务总数
- `logrotate_task_duration_seconds`: 任务执行时间

### Grafana 仪表板

```bash
# 访问 Grafana
kubectl port-forward svc/grafana 3000:80 -n logrotate
# 默认账号: admin/admin
```

### 健康检查

- **Liveness Probe**: `/health` (30秒间隔)
- **Readiness Probe**: `/health` (5秒间隔)
- **Startup Probe**: `/health` (最多等待150秒)

## 预提交钩子

使用 pre-commit 框架确保代码质量:

```bash
# 安装 pre-commit
pip install pre-commit

# 安装钩子
pre-commit install

# 手动运行所有检查
pre-commit run --all-files
```

包含的钩子:
- 代码格式检查 (gofmt, goimports)
- 静态分析 (golangci-lint)
- 安全扫描 (gosec)
- 拼写检查
- Dockerfile 检查
- GitHub Actions 检查

## 故障排查

### 常见问题

**1. 构建失败**
```bash
# 清理并重新构建
make clean
make deps
make build
```

**2. 依赖问题**
```bash
# 更新依赖
make deps-update
make tidy
```

**3. 部署失败**
```bash
# 查看部署状态
kubectl describe deployment logrotate -n logrotate

# 查看 Pod 日志
kubectl logs -l app.kubernetes.io/name=logrotate -n logrotate

# 查看事件
kubectl get events -n logrotate --sort-by='.lastTimestamp'
```

**4. Helm 回滚**
```bash
# 查看历史版本
helm history logrotate -n logrotate

# 回滚到上一版本
helm rollback logrotate -n logrotate
```

## 最佳实践

1. **配置管理**: 使用环境变量或 Secrets 管理敏感配置, 不要硬编码
2. **版本控制**: 所有配置文件都应纳入版本控制
3. **不可变部署**: 使用镜像标签, 避免使用 latest
4. **健康检查**: 确保 liveness/readiness 探针正确配置
5. **资源限制**: 为所有容器设置合理的资源请求和限制
6. **日志聚合**: 将日志输出到标准输出, 由集群日志系统收集
7. **安全扫描**: 定期运行安全扫描, 及时修复漏洞
8. **灰度发布**: 生产环境使用金丝雀发布或蓝绿部署

## 附录

### 环境变量清单

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `ENV` | 运行环境 | development |
| `CONFIG_FILE` | 配置文件路径 | configs/config.yaml |
| `DB_HOST` | 数据库地址 | localhost |
| `DB_PORT` | 数据库端口 | 5432 |
| `DB_USER` | 数据库用户 | postgres |
| `DB_PASSWORD` | 数据库密码 | postgres |
| `DB_NAME` | 数据库名 | logrotate |
| `REDIS_HOST` | Redis 地址 | localhost |
| `REDIS_PORT` | Redis 端口 | 6379 |
| `REDIS_PASSWORD` | Redis 密码 | - |
| `JWT_SECRET` | JWT 密钥 | - |
| `SENTRY_DSN` | Sentry DSN | - |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP 端点 | - |

### 端口清单

| 端口 | 服务 | 说明 |
|------|------|------|
| 8080 | logrotate | 应用服务 |
| 5432 | postgresql | 数据库 |
| 6379 | redis | 缓存 |
| 9090 | prometheus | 监控 |
| 3000 | grafana | 仪表板 |
| 16686 | jaeger | 链路追踪 |
