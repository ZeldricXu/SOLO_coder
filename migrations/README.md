# 数据库迁移说明

本目录使用 [sqlx](https://github.com/launchbadge/sqlx) 迁移工具管理数据库 Schema 变更。

## 迁移文件命名规范

sqlx 迁移文件使用以下格式：

```
<YYYYMMDDHHMMSS>_<描述>.up.sql    # 升级脚本
<YYYYMMDDHHMMSS>_<描述>.down.sql  # 降级脚本
```

时间戳必须唯一，用于确定迁移的执行顺序。

## 使用 sqlx-cli 运行迁移

### 1. 安装 sqlx-cli

```bash
cargo install sqlx-cli --no-default-features --features postgres
```

### 2. 配置数据库连接

设置环境变量：

```bash
export DATABASE_URL="postgres://collab:collab-dev@localhost:5432/collab"
```

### 3. 常用命令

```bash
# 查看迁移状态
sqlx migrate info

# 执行所有未执行的迁移
sqlx migrate run

# 回滚最后一次迁移
sqlx migrate revert

# 创建新的迁移文件
sqlx migrate add <描述>
```

## 在 Docker/Kubernetes 中使用 init container

在部署架构中，迁移可以作为 **init container** 运行，确保应用启动前数据库 Schema 就绪。

### docker-compose 方式

本项目的 `docker-compose.yml` 中已包含 `migrate` 服务作为示例。实际使用时：

1. 可在镜像中预装 `sqlx-cli`，或使用独立的 `rust:alpine` 镜像
2. init container 执行：
   ```bash
   sqlx migrate run --database-url $DATABASE_URL
   ```

### 备选方案

本项目的 Rust 应用在启动时会调用 `repo.init_schema()`，使用 `CREATE TABLE IF NOT EXISTS` 自动初始化基础表结构。
因此即使不手动运行迁移，应用首次启动时也能建立基础 Schema。

推荐在生产环境使用 sqlx 迁移进行版本化管理，开发环境可依赖应用自动初始化。

## 当前迁移

- **20250101000001_create_collab_tables**: 初始 Schema，包含 documents、operation_logs、snapshots、document_permissions、share_links 五张核心表及相关索引。
