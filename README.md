# DevKit CLI - 一站式开发者工具箱

[![CI](https://github.com/devkit-cli/devkit/actions/workflows/ci.yml/badge.svg)](https://github.com/devkit-cli/devkit/actions/workflows/ci.yml)
[![PyPI version](https://badge.fury.io/py/devkit-cli.svg)](https://badge.fury.io/py/devkit-cli)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Python Version](https://img.shields.io/pypi/pyversions/devkit-cli.svg)](https://pypi.org/project/devkit-cli/)

DevKit 是一个强大的命令行开发者工具箱，集成了日常开发中常用的工具，帮助你提升开发效率。

## ✨ 功能特性

| 类别 | 功能 |
|------|------|
| **数据处理** | JSON/YAML/TOML 格式化、转换、Diff、jq 查询 |
| **编解码** | Base64、URL、Hex、Hash、UUID、JWT |
| **加密安全** | AES/RSA 加密、JWT 签发/验证、证书、密码哈希 |
| **网络诊断** | 端口检测、HTTP 请求、DNS 解析、IP 查询、WebSocket |
| **时间工具** | 时区转换、时间戳、Cron 解析、日历 |
| **正则调试** | 匹配测试、捕获组、替换预览 |
| **文件处理** | 批量重命名、编码转换、分割合并 |
| **Git 助手** | 提交统计、变更日志、分支清理 |
| **代码生成** | JSON→多语言类型、SQL→ORM、OpenAPI→客户端 |
| **数据库** | MySQL/PostgreSQL/SQLite 查询、Shell、导出 |
| **API 测试** | HTTP 请求、Collection、断言、性能测试 |
| **系统监控** | TUI 实时仪表盘、CPU/内存/磁盘/网络/进程 |

## 🚀 快速开始

### 安装

```bash
# 核心安装（不含可选依赖）
pip install devkit-cli

# 安装全部功能
pip install devkit-cli[all]

# 按功能分组安装
pip install devkit-cli[db]      # 数据库功能
pip install devkit-cli[sysmon]  # 系统监控
pip install devkit-cli[openapi] # OpenAPI 支持
```

### 初始化配置

```bash
# 交互式初始化配置向导
devkit config init

# 设置主密钥（用于加密敏感配置，推荐）
export DEVKIT_MASTER_KEY="your-secret-key-here"
```

### 常用命令示例

#### JSON 处理

```bash
# 格式化 JSON
devkit json format data.json

# JSON 转 YAML
devkit json convert --to yaml input.json

# jq 路径查询
devkit json path data.json "users[0].name"

# 对比两个 JSON
devkit json diff a.json b.json
```

#### 加密与安全

```bash
# AES 加密
devkit crypto aes encrypt -f secret.txt -k mykey

# JWT 签发
devkit crypto jwt sign --payload '{"user":"alice"}' --secret mysecret

# JWT 验证
devkit crypto jwt decode eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9... --secret mysecret
```

#### 网络诊断

```bash
# 端口检测
devkit net portcheck 8080 --host localhost

# HTTP GET 请求
devkit net http GET https://api.example.com/users

# DNS 解析
devkit net dns example.com
```

#### 代码生成

```bash
# JSON 生成 TypeScript interface
devkit codegen json-types --lang typescript data.json

# SQL 生成 SQLAlchemy model
devkit codegen sql-orm --orm sqlalchemy schema.sql

# OpenAPI 生成 TypeScript 客户端
devkit codegen openapi-client --lang typescript openapi.yaml
```

#### 数据库工具

```bash
# 执行查询
devkit db query "SELECT * FROM users LIMIT 10" --type sqlite --path data.db

# 交互模式
devkit db shell --type mysql --host localhost --user root

# 导出数据
devkit db export "SELECT * FROM users" --format csv --output users.csv
```

#### API 测试

```bash
# 发送请求
devkit api test GET https://api.example.com/users

# 运行 collection 中的请求
devkit api run login --project myapp

# 性能测试（发 100 次请求）
devkit api perf GET https://api.example.com/health -n 100
```

#### 系统监控

```bash
# 实时 TUI 仪表盘
devkit sysmon tui

# 后台日志模式（每秒采样一次，持续 60 秒）
devkit sysmon log --interval 1 --duration 60 --output ./metrics/
```

## 📁 配置文件

配置文件位于 `~/.config/devkit/config.yml`

```yaml
general:
  default_editor: vim
  theme: default
  timezone: Asia/Shanghai

json:
  indent: 2
  sort_keys: false

db:
  default_type: mysql
  timeout: 30

api:
  timeout: 30
  verify_ssl: true

db_connections:
  prod:
    type: mysql
    host: db.example.com
    port: 3306
    database: app
    user: readonly
    # 密码使用主密钥加密存储
    password: "!encrypted:gAAAAABk..."

api_collections:
  myapp:
    login:
      method: POST
      url: https://api.example.com/auth/login
      json:
        username: "{{username}}"
        password: "{{password}}"
```

## 🔐 安全特性

- **敏感配置加密**: 数据库密码、API Token 等敏感信息使用 AES-256 加密
- **系统 Keyring 集成**: 主密钥可安全存储在系统密钥链
- **环境变量支持**: 通过 `DEVKIT_MASTER_KEY` 环境变量提供主密钥

## 🛠️ 开发

```bash
# 克隆项目
git clone https://github.com/devkit-cli/devkit.git
cd devkit

# 安装开发依赖
pip install -e ".[dev,all]"

# 运行测试
pytest tests/ -v

# 代码检查
ruff check devkit/
mypy devkit/

# 构建发布
python -m build
twine check dist/*
```

## 📋 命令列表

| 命令 | 说明 |
|------|------|
| `devkit json` | JSON/YAML/TOML 处理 |
| `devkit codec` | 编解码与哈希 |
| `devkit crypto` | 加密与证书工具 |
| `devkit net` | 网络诊断工具 |
| `devkit time` | 时间与日期工具 |
| `devkit regex` | 正则表达式调试 |
| `devkit file` | 文件批量处理 |
| `devkit git` | Git 辅助工具 |
| `devkit codegen` | 代码生成器 |
| `devkit db` | 数据库工具 |
| `devkit api` | API 测试工具 |
| `devkit sysmon` | 系统监控 |
| `devkit config` | 配置管理 |

## 📄 License

MIT License - see [LICENSE](LICENSE) for details.
