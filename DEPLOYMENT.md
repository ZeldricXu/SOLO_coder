# FlowPlatform 部署手册

## 1. 环境要求

### 1.1 硬件最低配置

| 组件 | CPU | 内存 | 磁盘 |
|------|-----|------|------|
| 应用服务器 | 2核 | 4GB | 20GB |
| MySQL | 2核 | 4GB | 50GB（取决于数据量） |
| Redis | 1核 | 2GB | 5GB |

### 1.2 软件依赖

| 软件 | 最低版本 | 说明 |
|------|---------|------|
| JDK | 17+ | 推荐 Eclipse Temurin |
| MySQL | 8.0+ | 需要 InnoDB 引擎 |
| Redis | 6.0+ | 用于缓存和会话存储 |
| Docker | 20.10+ | 仅容器化部署需要 |
| Docker Compose | 2.0+ | 仅单机容器化部署需要 |

### 1.3 网络要求

- 应用服务器需访问 MySQL 3306 端口
- 应用服务器需访问 Redis 6379 端口
- 如需邮件通知，需访问邮件服务器 SMTP 端口（587/465）
- 如需企业微信通知，需访问公网 HTTPS（qyapi.weixin.qq.com）

---

## 2. 数据库初始化

### 2.1 创建数据库和用户

```sql
CREATE DATABASE IF NOT EXISTS flow_platform
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE USER 'flow_app'@'%' IDENTIFIED BY '<YOUR_SECURE_PASSWORD>';
GRANT ALL PRIVILEGES ON flow_platform.* TO 'flow_app'@'%';
FLUSH PRIVILEGES;
```

### 2.2 Flyway 自动迁移

应用启动时会自动执行 Flyway 迁移脚本（`db/migration/` 目录）。

- **首次部署**：V1 初始化脚本会自动创建全部表结构和默认数据
- **版本升级**：新增的迁移脚本会在启动时自动执行

如需手动执行迁移：

```bash
export FLYWAY_URL=jdbc:mysql://localhost:3306/flow_platform
export FLYWAY_USER=flow_app
export FLYWAY_PASSWORD=<YOUR_SECURE_PASSWORD>
./mvnw flyway:migrate
```

### 2.3 默认账号

| 账号 | 密码 | 角色 | 说明 |
|------|------|------|------|
| admin | admin123 | 超级管理员 | **首次登录后请立即修改密码** |

---

## 3. 应用启动

### 3.1 JAR 直接运行

```bash
java -jar flow-platform-1.0.0.jar \
  --spring.profiles.active=prod \
  --spring.datasource.url=jdbc:mysql://<MYSQL_HOST>:3306/flow_platform \
  --spring.datasource.username=flow_app \
  --spring.datasource.password=<DB_PASSWORD> \
  --spring.data.redis.host=<REDIS_HOST> \
  --spring.data.redis.password=<REDIS_PASSWORD> \
  --flow-platform.encryption-key=<32_CHAR_ENCRYPTION_KEY>
```

### 3.2 JVM 参数建议

**开发环境**：
```bash
java -Xms256m -Xmx512m -jar flow-platform-1.0.0.jar
```

**生产环境**：
```bash
java \
  -Xms1g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/flow-platform/ \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai \
  -jar flow-platform-1.0.0.jar \
  --spring.profiles.active=prod
```

### 3.3 Docker Compose 部署

```bash
# 修改环境变量
cp .env.example .env
vi .env

# 启动
docker compose up -d

# 查看日志
docker compose logs -f app

# 停止
docker compose down
```

### 3.4 Kubernetes 部署

```bash
# 1. 创建命名空间
kubectl apply -f k8s/namespace.yaml

# 2. 修改 Secret 中的敏感信息
vi k8s/secret.yaml

# 3. 修改 ConfigMap 中的数据库/Redis 地址
vi k8s/configmap.yaml

# 4. 按顺序应用资源
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/hpa.yaml
kubectl apply -f k8s/networkpolicy.yaml

# 5. 配置 Ingress（修改域名为实际域名）
vi k8s/ingress.yaml
kubectl apply -f k8s/ingress.yaml

# 6. 检查部署状态
kubectl get pods -n flow-platform
kubectl logs -f deployment/flow-platform -n flow-platform
```

---

## 4. 配置管理

### 4.1 Spring Profiles

| Profile | 用途 | 说明 |
|---------|------|------|
| dev | 本地开发 | Thymeleaf 禁用缓存、DEBUG 日志、无 Redis |
| staging | 预发布 | 接近生产配置、WARN 日志 |
| prod | 生产环境 | 优雅停机、独立管理端口、日志文件滚动 |

### 4.2 环境变量覆盖

所有配置项均可通过环境变量覆盖，Spring Boot 自动将 `SPRING_` 前缀的环境变量映射到配置：

| 环境变量 | 说明 | 示例 |
|---------|------|------|
| SPRING_DATASOURCE_URL | 数据库连接 URL | jdbc:mysql://mysql:3306/flow_platform |
| SPRING_DATASOURCE_USERNAME | 数据库用户名 | flow_app |
| SPRING_DATASOURCE_PASSWORD | 数据库密码 | （必须通过 Secret 注入） |
| SPRING_REDIS_HOST | Redis 主机 | redis |
| SPRING_REDIS_PORT | Redis 端口 | 6379 |
| SPRING_REDIS_PASSWORD | Redis 密码 | （必须通过 Secret 注入） |
| SPRING_MAIL_HOST | SMTP 服务器 | smtp.exmail.qq.com |
| SPRING_MAIL_PASSWORD | 邮箱密码 | （必须通过 Secret 注入） |
| FLOW_PLATFORM_ENCRYPTION_KEY | 数据加密密钥 | 32 字符随机字符串 |
| FLOW_PLATFORM_WECHAT_WEBHOOK | 企业微信 Webhook | https://qyapi.weixin.qq.com/... |
| FLOW_PLATFORM_UPLOAD_PATH | 文件上传路径 | /data/flow-platform/uploads |

### 4.3 敏感信息处理

- **Docker 部署**：通过 `.env` 文件或 `docker compose` 环境变量注入
- **K8s 部署**：通过 Secret 资源管理，可结合 K8s 外部密钥管理工具（Vault、Sealed Secrets）
- **禁止**将密码、密钥等硬编码在任何配置文件或镜像中

---

## 5. HTTPS 证书配置

### 5.1 Nginx 反向代理（推荐）

```nginx
server {
    listen 443 ssl http2;
    server_name flow.yourcompany.com;

    ssl_certificate     /etc/nginx/ssl/flow.yourcompany.com.crt;
    ssl_certificate_key /etc/nginx/ssl/flow.yourcompany.com.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    client_max_body_size 50m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name flow.yourcompany.com;
    return 301 https://$host$request_uri;
}
```

### 5.2 K8s Ingress + cert-manager

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: flow-platform-tls
  namespace: flow-platform
spec:
  secretName: flow-platform-tls
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
    - flow.yourcompany.com
```

### 5.3 Spring Boot 内置 SSL（不推荐用于生产）

```bash
keytool -genkeypair -alias flow-platform \
  -keyalg RSA -keysize 2048 \
  -keystore keystore.p12 -validity 365 \
  -storetype PKCS12

java -jar flow-platform-1.0.0.jar \
  --server.ssl.key-store=keystore.p12 \
  --server.ssl.key-store-password=<KEYSTORE_PASSWORD> \
  --server.ssl.key-store-type=PKCS12
```

---

## 6. 健康检查与监控

### 6.1 Actuator 端点

| 端点 | 用途 | 访问 |
|------|------|------|
| /actuator/health | 健康状态 | 包含 DB + Redis 连接检查 |
| /actuator/health/liveness | 存活探针 | K8s livenessProbe |
| /actuator/health/readiness | 就绪探针 | K8s readinessProbe |
| /actuator/info | 应用信息 | 构建版本等 |
| /actuator/metrics | 应用指标 | JVM、HTTP 请求等 |
| /actuator/prometheus | Prometheus 格式指标 | 对接 Grafana |

### 6.2 探针配置（K8s 已内置）

- **Startup Probe**：启动阶段，允许 30 次失败（~5 分钟），适配 JVM 慢启动
- **Liveness Probe**：运行阶段，每 15 秒检查一次，失败则重启容器
- **Readiness Probe**：就绪检查，每 10 秒检查一次，失败则摘除流量

### 6.3 日志

- **开发环境**：控制台输出，DEBUG 级别
- **生产环境**：写入 `/var/log/flow-platform/application.log`，自动滚动（100MB/文件，保留30天，总量5GB上限）

---

## 7. 备份策略

### 7.1 数据库备份

**自动备份脚本**：`scripts/backup-db.sh`

```bash
# 手动备份
./scripts/backup-db.sh /backup/flow-platform

# Cron 定时备份（每天凌晨 2 点）
0 2 * * * /opt/flow-platform/scripts/backup-db.sh /backup/flow-platform >> /var/log/flow-platform/backup.log 2>&1
```

**备份策略**：
- 全量备份：每日一次，使用 `mysqldump --single-transaction`（不锁表）
- 保留策略：保留 30 天备份文件
- 异地备份：建议通过 `rsync` 或对象存储同步到异地

### 7.2 上传文件备份

```bash
rsync -avz /data/flow-platform/uploads/ backup-server:/backup/flow-platform/uploads/
```

### 7.3 Redis 数据

Redis 开启 AOF 持久化（docker-compose 已配置 `appendonly yes`），RDB 快照作为补充。

---

## 8. 版本升级

### 8.1 升级步骤

```bash
# 1. 备份数据库
./scripts/backup-db.sh

# 2. 停止应用
docker compose down app
# 或 kubectl scale deployment flow-platform --replicas=0 -n flow-platform

# 3. 更新镜像
docker compose pull app
# 或 kubectl set image deployment/flow-platform app=ghcr.io/flowplatform/app:<NEW_VERSION> -n flow-platform

# 4. 启动新版本（Flyway 自动迁移）
docker compose up -d app
# 或 kubectl scale deployment flow-platform --replicas=2 -n flow-platform

# 5. 验证
curl http://localhost:8080/actuator/health
```

### 8.2 回滚

```bash
# Docker
docker compose down app
docker tag ghcr.io/flowplatform/app:<PREVIOUS_VERSION> ghcr.io/flowplatform/app:latest
docker compose up -d app

# Kubernetes
kubectl rollout undo deployment/flow-platform -n flow-platform
```

---

## 9. 常见问题

### Q: 启动报 Flyway 迁移校验失败
A: 检查 `flyway_schema_history` 表，确认迁移脚本的 checksum 是否被手动修改。如确认需重新基线：
```bash
./mvnw flyway:baseline -Dflyway.baselineVersion=<VERSION>
```

### Q: 数据库连接池耗尽
A: 调整 `spring.datasource.hikari.maximum-pool-size`（默认 20），并检查是否有慢查询或连接泄漏。

### Q: Redis 连接超时
A: 检查 Redis `maxclients` 配置和网络连通性。调整 `spring.data.redis.lettuce.pool.max-active`。

### Q: 文件上传失败
A: 检查 `flow-platform.upload-path` 目录是否存在且应用有写入权限。Nginx 场景检查 `client_max_body_size`。

### Q: 邮件发送失败
A: 检查 SMTP 配置（host/port/username/password），确认防火墙允许出站 587/465 端口。
