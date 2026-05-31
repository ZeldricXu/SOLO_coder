# NFTIndexer - 跨链 NFT 元数据索引与查询服务

[![Java CI](https://github.com/nftindexer/nft-indexer/actions/workflows/ci.yml/badge.svg)](https://github.com/nftindexer/nft-indexer/actions/workflows/ci.yml)
[![Code Quality](https://github.com/nftindexer/nft-indexer/actions/workflows/code-quality.yml/badge.svg)](https://github.com/nftindexer/nft-indexer/actions/workflows/code-quality.yml)
[![Docker Build](https://github.com/nftindexer/nft-indexer/actions/workflows/docker.yml/badge.svg)](https://github.com/nftindexer/nft-indexer/actions/workflows/docker.yml)
[![SonarQube](https://sonarcloud.io/api/project_badges/measure?project=nft-indexer&metric=alert_status)](https://sonarcloud.io/dashboard?id=nft-indexer)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## 项目概述

NFTIndexer 是一个高性能的跨链 NFT 元数据索引与查询服务，为 NFT 市场开发团队提供统一、高效的跨链元数据查询解决方案。

### 核心功能模块

| 模块 | 功能描述 |
|------|----------|
| **资产跨链桥接模块** | 跨链消息验证，资产锁定与铸造的原子性保障 |
| **交易构造与签名模块** | 构造链上交易数据结构，管理多签策略与 Gas 优化 |
| **多签钱包协调模块** | 多签提案创建、签名收集与执行触发 |
| **地址派生与管理模块** | 基于 HD 钱包标准派生地址，管理地址簿与标签 |
| **Gas 费用预估模块** | 基于历史数据和当前网络状态预估交易 Gas 费用 |
| **合约事件监听模块** | 监听链上合约事件日志，事件触发后执行预定义回调 |
| **零知识证明验证模块** | 接收 ZKP 证明数据，执行电路验证并返回验证结果 |
| **链上数据索引模块** | 解析区块原始数据，构建结构化索引加速查询 |

### 技术栈

- **语言**: Java 17
- **Web 框架**: Spring Boot 3.x + Spring WebFlux
- **持久层**: MyBatis-Plus + Flyway
- **缓存**: Caffeine (L1) + Redis (L2)
- **监控**: Micrometer + Spring Actuator
- **构建工具**: Maven Wrapper
- **容器化**: Docker + Docker Compose
- **容器编排**: Kubernetes + Helm Charts
- **CI/CD**: GitHub Actions / GitLab CI

---

## 快速开始

### 环境要求

- JDK 17+
- Docker 24.0+
- Docker Compose 2.20+
- Helm 3.12+ (用于 Kubernetes 部署)
- kubectl (用于 Kubernetes 部署)

### 本地开发

#### 1. 克隆项目

```bash
git clone https://github.com/nftindexer/nft-indexer.git
cd nft-indexer
```

#### 2. 启动开发环境

```bash
# 使用 Makefile
make compose-dev

# 或直接使用 Docker Compose
docker compose up -d mysql redis
```

#### 3. 运行应用

```bash
# 使用 Makefile
make run-dev

# 或使用 Maven Wrapper
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

应用启动后访问:
- 主应用: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- 健康检查: http://localhost:8080/actuator/health
- Prometheus 指标: http://localhost:8080/actuator/prometheus

---

## 工程化体系

### 1. 构建系统 (Maven Wrapper)

项目使用 Maven Wrapper 确保构建环境一致性，无需手动安装 Maven。

```bash
# 编译
./mvnw compile

# 打包（跳过测试）
./mvnw package -DskipTests

# 完整构建（含测试和质量检查）
./mvnw clean verify

# 跳过质量检查的快速构建
./mvnw clean package -DskipTests -Pskip-quality
```

### 2. 代码质量门禁

集成多层静态分析和测试覆盖率检查，确保代码合入质量。

#### 质量检查工具

| 工具 | 用途 | 报告位置 |
|------|------|----------|
| **Checkstyle** | 代码风格检查 | `target/checkstyle-result.xml` |
| **PMD** | 代码缺陷检测 | `target/pmd.xml` |
| **SpotBugs** | Bug 模式检测 | `target/spotbugsXml.xml` |
| **JaCoCo** | 测试覆盖率 | `target/site/jacoco/` |

#### 质量阈值

| 指标 | 阈值 |
|------|------|
| 行覆盖率 | ≥ 80% |
| 分支覆盖率 | ≥ 70% |
| Checkstyle 违规 | 0 错误 |
| PMD 违规 | 0 错误 |
| SpotBugs 高优先级 | 0 |

#### 运行质量检查

```bash
# 使用 Makefile
make quality

# 或单独运行
./mvnw checkstyle:checkstyle     # Checkstyle
./mvnw pmd:pmd pmd:cpd-check     # PMD + CPD
./mvnw spotbugs:spotbugs spotbugs:check  # SpotBugs
./mvnw test jacoco:check         # 测试 + 覆盖率检查
```

### 3. 容器化 (Docker)

#### 镜像特点

- 多阶段构建，镜像体积小 (~200MB)
- Alpine 基础镜像，安全漏洞少
- 非 root 用户运行，提升安全性
- 健康检查集成
- JVM 参数调优

#### Dockerfile 结构

```dockerfile
# 构建阶段: 包含完整 JDK
FROM eclipse-temurin:17-jdk-alpine AS builder
...

# 运行阶段: 仅包含 JRE
FROM eclipse-temurin:17-jre-alpine
...
```

#### 构建与运行

```bash
# 构建镜像
docker build -t nftindexer/nft-indexer:latest .

# 运行容器
docker run -d -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  nftindexer/nft-indexer:latest

# 镜像漏洞扫描
docker scout cves nftindexer/nft-indexer:latest
trivy image nftindexer/nft-indexer:latest
```

### 4. Docker Compose 多服务编排

#### 服务清单

| 服务 | 端口 | 用途 |
|------|------|------|
| **app** | 8080 | NFTIndexer 应用 |
| **mysql** | 3306 | MySQL 数据库 |
| **redis** | 6379 | Redis 缓存 |
| **prometheus** | 9090 | 指标采集 |
| **grafana** | 3000 | 可视化监控 |
| **loki** | 3100 | 日志聚合 |
| **flyway** | - | 数据库迁移 |
| **sonarqube** | 9000 | 代码质量分析 |

#### 常用命令

```bash
# 启动所有服务
docker compose up -d

# 仅启动开发依赖服务
docker compose up -d mysql redis

# 查看日志
docker compose logs -f

# 停止并清理
docker compose down -v

# 使用生产环境配置
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### 5. CI/CD 流水线

#### GitHub Actions

工作流文件位于 `.github/workflows/` 目录:

| 工作流 | 触发条件 | 功能 |
|--------|----------|------|
| **pr-check.yml** | Pull Request | 代码质量检查、测试、构建、Docker 扫描 |
| **ci.yml** | push 到 main/develop | 完整 CI 流程、部署到测试环境 |
| **release.yml** | tag 推送 | 生产构建、镜像推送、版本发布 |
| **sonar.yml** | push 到 main | SonarQube 代码质量分析 |

##### PR 检查流水线

```
PR 提交
  ↓
代码质量检查 (Checkstyle/PMD/SpotBugs)
  ↓
单元测试 + 覆盖率检查
  ↓
构建验证
  ↓
Docker 镜像构建
  ↓
Trivy 漏洞扫描
  ↓
SonarQube 分析
  ↓
✅ PR 可合并
```

##### 生产发布流水线

```
Tag 推送 (vX.Y.Z)
  ↓
完整质量门禁
  ↓
生产构建
  ↓
多架构镜像构建
  ↓
漏洞扫描
  ↓
推送到容器注册表
  ↓
Helm Chart 发布
  ↓
自动部署到生产环境
  ↓
✅ 发布完成
```

#### GitLab CI

配置文件: `.gitlab-ci.yml`

包含 9 个阶段:

```
prepare → quality → build → test → security → package → containerize → deploy → release
```

### 6. 代码质量分析 (SonarQube)

#### 本地 SonarQube 分析

```bash
# 启动 SonarQube
docker compose up -d sonarqube

# 运行分析
mvn sonar:sonar \
  -Dsonar.projectKey=nft-indexer \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=your-token
```

#### 质量规则

- **可靠性**: 0 个 Bug，0 个漏洞
- **安全性**: 0 个热点，A 级安全评级
- **可维护性**: 技术债务率 < 3%，A 级
- **覆盖率**: ≥ 80%
- **重复率**: < 3%

### 7. Kubernetes 部署 (Helm Charts)

Helm Chart 位于 `helm/nft-indexer/` 目录。

#### Chart 结构

```
helm/nft-indexer/
├── Chart.yaml                    # Chart 元数据
├── values.yaml                   # 默认配置
├── values-staging.yaml           # 测试环境配置
├── values-production.yaml        # 生产环境配置
├── templates/
│   ├── _helpers.tpl             # 辅助模板
│   ├── deployment.yaml          # Deployment
│   ├── service.yaml             # Service
│   ├── ingress.yaml             # Ingress
│   ├── configmap.yaml           # ConfigMap (应用配置)
│   ├── secret.yaml              # Secret (敏感配置)
│   ├── serviceaccount.yaml      # ServiceAccount
│   ├── persistentvolumeclaim.yaml # PVC
│   ├── hpa.yaml                 # Horizontal Pod Autoscaler
│   ├── vpa.yaml                 # Vertical Pod Autoscaler
│   ├── servicemonitor.yaml      # Prometheus ServiceMonitor
│   ├── prometheusrule.yaml      # 告警规则
│   ├── networkpolicy.yaml       # 网络策略
│   └── poddisruptionbudget.yaml # Pod 中断预算
```

#### 部署到不同环境

```bash
# 添加 Helm 依赖
make helm-dep-up

# 开发/测试环境
make helm-install-staging

# 生产环境
make helm-install-prod

# 自定义安装
helm upgrade --install nft-indexer ./helm/nft-indexer \
  --namespace nftindexer \
  --create-namespace \
  --values ./helm/nft-indexer/values-production.yaml \
  --set image.tag=1.0.0 \
  --wait
```

#### 生产环境特性

- **高可用**: 5 副本，跨可用区调度
- **自动扩缩容**: CPU 60% 触发扩容，3-20 副本
- **零宕机部署**: RollingUpdate，maxSurge=50%，maxUnavailable=0
- **安全性**:
  - 非 root 用户运行
  - 只读根文件系统
  - 所有 capabilities 已删除
  - NetworkPolicy 网络隔离
- **可观测性**:
  - Prometheus 指标自动采集
  - 5xx 错误率、高延迟等告警规则
  - Liveness/Readiness/Startup 探针
- **资源规划**:
  - CPU: 1 请求 / 4 限制
  - 内存: 2Gi 请求 / 4Gi 限制
  - MySQL: 主从复制，100Gi SSD
  - Redis: 主从复制，50Gi SSD

---

## Makefile 常用命令

项目提供了完整的 Makefile 来简化日常操作:

```bash
# 查看所有命令
make help

# 构建相关
make build                    # 完整构建（含测试和质量检查）
make build-fast              # 快速构建（跳过测试和质量检查）
make quality                 # 运行所有质量检查

# Docker 相关
make docker-build            # 构建 Docker 镜像
make docker-push             # 推送镜像到仓库
make docker-scan             # 镜像漏洞扫描

# Docker Compose 相关
make compose-up              # 启动所有服务
make compose-down            # 停止所有服务
make compose-dev             # 启动开发环境

# Kubernetes / Helm 相关
make helm-install            # 安装到 Kubernetes
make helm-install-staging    # 安装到测试环境
make helm-install-prod       # 安装到生产环境

# 开发相关
make run-dev                 # 运行开发模式（热重载）
make run-debug               # 运行调试模式（端口 5005）
make format                  # 代码格式化
```

---

## 环境配置

### Profile 说明

| Profile | 用途 | 配置文件 |
|---------|------|----------|
| **local** | 本地开发 | `application-local.yml` |
| **dev** | 开发环境 | `application-dev.yml` |
| **test** | 测试环境 | `application-test.yml` |
| **docker** | Docker 环境 | `application-docker.yml` |
| **kubernetes** | Kubernetes 环境 | ConfigMap 注入 |
| **staging** | 预发布环境 | `application-staging.yml` |
| **prod** | 生产环境 | `application-prod.yml` |

### 环境变量

核心配置通过环境变量注入:

```bash
# 必需配置
export SPRING_PROFILES_ACTIVE=prod
export SPRING_R2DBC_URL=r2dbc:mysql://localhost:3306/nft_indexer
export SPRING_R2DBC_USERNAME=nftindexer
export SPRING_R2DBC_PASSWORD=your-password
export SPRING_DATA_REDIS_HOST=localhost

# JVM 配置
export JAVA_OPTS="-Xms1024m -Xmx2048m -XX:+UseG1GC"

# 可选配置
export JWT_SECRET=your-jwt-secret
export TZ=Asia/Shanghai
```

---

## 监控与告警

### 健康检查端点

| 端点 | 用途 |
|------|------|
| `/actuator/health` | 总体健康状态 |
| `/actuator/health/liveness` | 存活探针 |
| `/actuator/health/readiness` | 就绪探针 |
| `/actuator/metrics` | 所有可用指标 |
| `/actuator/prometheus` | Prometheus 格式指标 |
| `/actuator/info` | 应用信息 |
| `/actuator/loggers` | 日志级别管理 |

### 关键指标

- **JVM 指标**: 堆内存使用、GC 次数、线程数
- **HTTP 指标**: 请求量、错误率、响应时间（