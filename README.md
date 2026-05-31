# SmartFlow - 智能工单路由分配系统

基于技能匹配与负载均衡的工单路由分配系统，提供完整的企业级工单管理解决方案。

## 技术栈

- **Java 17** + **Spring Boot 3.2.5** + **Spring WebFlux** (响应式编程)
- **MyBatis-Plus 3.5.5** (多租户插件、分页、逻辑删除)
- **Flyway 9.22.3** (数据库版本迁移)
- **MySQL 8.0** + **Redis 7**
- **Docker** + **Kubernetes** (容器化部署)

## 模块说明

| 模块 | 说明 |
|------|------|
| smartflow-common | 公共模块 - 基础实体、DTO、工具类 |
| smartflow-persistence | 持久层模块 - MyBatis-Plus与Flyway |
| smartflow-ticket-assignment | 工单智能分配模块 - 技能匹配与负载均衡 |
| smartflow-approval-engine | 审批规则引擎模块 - 条件分支、会签/或签 |
| smartflow-metering-billing | 用量计量与计费模块 - 按量计费与账单生成 |
| smartflow-multitenant | 多租户隔离策略模块 - 数据隔离与配额管理 |
| smartflow-process-designer | 可视化流程设计模块 - 拖拽式流程设计 |
| smartflow-skill-graph | 技能图谱建模模块 - 技能树与能力评估 |
| smartflow-document-compare | 文档智能比对模块 - 差异分析与高亮 |
| smartflow-sla-monitor | SLA时效监控模块 - 倒计时与超时升级 |
| smartflow-boot | 主启动模块 |

## 快速开始

### 本地开发

```bash
# 1. 启动依赖服务
docker-compose -f docker-compose.dev.yml up -d

# 2. 编译项目
mvn clean compile -Pdev

# 3. 运行测试
mvn test -Pdev

# 4. 启动应用
mvn spring-boot:run -pl smartflow-boot -Pdev
```

### 代码质量检查

```bash
# 运行完整代码质量检查
mvn clean verify -Pcode-quality

# 单独运行检查
mvn checkstyle:check      # Checkstyle 代码风格检查
mvn pmd:check              # PMD 静态代码分析
mvn spotbugs:check         # SpotBugs Bug检测
mvn jacoco:report          # 生成测试覆盖率报告
```

### 多环境构建

```bash
# 开发环境
mvn clean package -Pdev

# 预发布环境
mvn clean package -Pstaging

# 生产环境
mvn clean package -Pprod
```

### Docker 部署

```bash
# 构建镜像
docker build -t smartflow:latest .

# 或使用 docker-compose
docker-compose up -d
```

## API 文档

启动应用后访问：http://localhost:8080/swagger-ui.html

## 健康检查

- Liveness: http://localhost:8080/actuator/health/liveness
- Readiness: http://localhost:8080/actuator/health/readiness
- Metrics: http://localhost:8080/actuator/metrics
- Prometheus: http://localhost:8080/actuator/prometheus

## 项目结构

```
session142/
├── checkstyle/              # Checkstyle 配置
├── pmd/                     # PMD 配置
├── spotbugs/                # SpotBugs 配置
├── monitoring/              # 监控配置 (Prometheus, Grafana)
├── .github/workflows/       # GitHub Actions CI/CD
├── smartflow-*/             # 业务模块
├── Dockerfile               # Docker 镜像构建
├── docker-compose.yml       # 完整环境编排
└── pom.xml                  # Maven 父 POM
```

## 许可证

MIT License
