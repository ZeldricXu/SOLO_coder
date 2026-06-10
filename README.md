# DF1-69 协作式代码审查平台

## 项目简介

DF1-69 是一个面向技术团队的全流程代码评审管理系统，解决当前评审散落在 GitLab MR 评论、Slack 讨论和 Confluence 文档中的碎片化问题，提供统一的评审 checklist、问题追踪和统计复盘能力。

### 核心价值

- 将代码评审从分散的沟通渠道整合为结构化的流程管理
- 提升评审质量和效率，沉淀团队评审经验
- 支持 AI 辅助代码审查，自动发现潜在问题
- 提供完整的统计分析，帮助团队持续改进

### 目标用户

- 开发者、评审人
- Tech Lead、架构师
- 工程管理者

## 技术栈

- **后端框架**: Rust + Actix-web
- **模板引擎**: Maud (类型安全的 HTML 模板)
- **数据库**: PostgreSQL + SQLx
- **缓存/会话**: Redis
- **对象存储**: MinIO
- **认证方式**: OAuth2 (GitHub/GitLab/Gitee)
- **AI 集成**: OpenAI 兼容的 LLM API
- **通知渠道**: Email、Slack、DingTalk

## 功能模块

### 1. 用户认证与授权
- 支持 GitHub、GitLab、Gitee 三方 OAuth 登录
- 基于角色的权限控制（Owner、Maintainer、Reviewer、Developer）
- 安全的会话管理

### 2. 仓库管理
- 多平台仓库集成（GitHub、GitLab、Gitee）
- Webhook 自动同步 MR/PR 数据
- 仓库级配置管理

### 3. 合并请求管理
- MR/PR 列表展示与筛选
- Diff 代码对比查看
- 行级批注与讨论线程

### 4. 代码评审
- 结构化 Checklist 模板管理
- 支持仓库级、项目级 Checklist 继承
- 评审状态追踪

### 5. AI 智能评审
- 自动代码扫描与分析
- 代码风格建议
- Bug 模式识别
- 可采纳/忽略 AI 建议

### 6. 问题追踪
- 问题创建与关联代码上下文
- 严重级别划分（Critical、High、Medium、Low）
- 状态流转与责任人分配
- 完整的讨论历史记录

### 7. 通知中心
- 多渠道通知（Email、Slack、DingTalk）
- 通知配置管理
- 每日摘要推送

### 8. 统计看板
- 评审覆盖率统计
- 平均响应时间分析
- 问题密度热力图
- 个人缺陷发现率排名
- 团队评审效率趋势

## 快速开始

### 环境要求

- Rust 1.75+
- PostgreSQL 14+
- Redis 7+
- MinIO (或兼容 S3 的对象存储)
- Node.js 18+ (可选，用于前端构建)

### 安装依赖

```bash
# 克隆项目
git clone <repository-url>
cd DF1-69

# 安装 Rust 依赖
cargo build
```

### 配置

1. 复制环境变量示例文件：

```bash
cp .env.example .env
```

2. 编辑 `.env` 文件，配置以下内容：

```env
# 数据库连接
DATABASE_URL=postgres://username:password@localhost:5432/df1_69

# Redis 连接
REDIS_URL=redis://localhost:6379/0

# MinIO 配置
MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=df1-69

# 服务器配置
SERVER_HOST=127.0.0.1
SERVER_PORT=8080
BASE_URL=http://localhost:8080

# OAuth 配置（至少配置一个）
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret

# LLM 配置
LLM_PROVIDER=openai
LLM_API_KEY=your_llm_api_key
LLM_MODEL=gpt-4
LLM_BASE_URL=https://api.openai.com/v1

# Session 配置
SESSION_SECRET_KEY=your_session_secret_key_here_make_it_long_and_random
```

3. （可选）复制并编辑 YAML 配置文件：

```bash
cp config/default.yaml config/development.yaml
```

### 数据库迁移

项目使用 SQLx 进行数据库迁移：

```bash
# 运行数据库迁移
DATABASE_URL=postgres://username:password@localhost:5432/df1_69 cargo sqlx migrate run
```

迁移文件位于 `migrations/` 目录。

### 启动服务

```bash
# 开发模式启动
cargo run

# 或指定运行模式
RUN_MODE=development cargo run

# 生产模式构建并运行
cargo build --release
./target/release/code_review_platform
```

服务启动后，访问 `http://localhost:8080` 即可使用。

## 项目结构

```
DF1-69/
├── config/                    # 配置文件目录
│   ├── default.yaml          # 默认配置
│   ├── development.yaml      # 开发环境配置
│   └── production.yaml       # 生产环境配置
├── migrations/                # 数据库迁移文件
│   └── 001_initial_schema.sql
├── src/
│   ├── config/               # 配置加载
│   │   ├── mod.rs
│   │   └── settings.rs       # Settings 结构体定义
│   ├── handlers/             # HTTP 请求处理器
│   │   ├── auth_handler.rs
│   │   ├── merge_request_handler.rs
│   │   ├── ai_review_handler.rs
│   │   └── ...
│   ├── middleware/           # Actix 中间件
│   │   ├── auth_middleware.rs
│   │   ├── session_middleware.rs
│   │   └── permission_middleware.rs
│   ├── models/               # 数据模型
│   │   ├── user.rs
│   │   ├── merge_request.rs
│   │   ├── ai_review.rs
│   │   └── ...
│   ├── repositories/         # 数据访问层
│   │   ├── user_repo.rs
│   │   ├── merge_request_repo.rs
│   │   └── ...
│   ├── services/             # 业务逻辑层
│   │   ├── auth_service.rs
│   │   ├── ai_review_service.rs
│   │   ├── notification_service.rs
│   │   └── ...
│   ├── providers/            # 外部服务集成
│   │   ├── github.rs
│   │   ├── gitlab.rs
│   │   ├── gitee.rs
│   │   ├── llm.rs
│   │   ├── minio_client.rs
│   │   ├── redis_client.rs
│   │   ├── email.rs
│   │   ├── slack.rs
│   │   └── dingtalk.rs
│   ├── templates/            # Maud HTML 模板
│   │   ├── layout.rs
│   │   ├── login.rs
│   │   ├── dashboard.rs
│   │   ├── merge_requests.rs
│   │   └── ...
│   ├── utils/                # 工具函数
│   │   ├── error.rs
│   │   ├── crypto.rs
│   │   ├── diff_parser.rs
│   │   └── ...
│   ├── db.rs                 # 数据库连接
│   ├── lib.rs
│   └── main.rs               # 应用入口
├── static/                   # 静态资源
│   ├── css/
│   │   └── main.css
│   └── js/
│       └── main.js
├── Cargo.toml
├── .env.example
├── .gitignore
└── README.md
```

### 架构说明

- **Handlers**: 负责接收 HTTP 请求，参数校验，调用 Service 层
- **Services**: 核心业务逻辑实现，事务管理
- **Repositories**: 数据访问层，封装数据库操作
- **Models**: 数据结构定义，与数据库表对应
- **Providers**: 外部服务集成，统一封装第三方 API
- **Middleware**: 请求预处理（认证、权限、会话等）
- **Templates**: 服务端渲染的 HTML 模板

## API 文档

### 认证相关

- `GET /auth/login` - 登录页面
- `GET /auth/github` - GitHub OAuth 登录
- `GET /auth/github/callback` - GitHub OAuth 回调
- `GET /auth/gitlab` - GitLab OAuth 登录
- `GET /auth/gitlab/callback` - GitLab OAuth 回调
- `GET /auth/gitee` - Gitee OAuth 登录
- `GET /auth/gitee/callback` - Gitee OAuth 回调
- `POST /auth/logout` - 登出

### 合并请求相关

- `GET /merge_requests` - MR 列表
- `GET /merge_requests/{id}` - MR 详情
- `POST /merge_requests/{id}/review` - 提交评审
- `POST /merge_requests/{id}/comments` - 添加评论

### AI 评审相关

- `POST /api/ai-review/{mr_id}` - 触发 AI 评审
- `GET /api/ai-review/{mr_id}` - 获取 AI 评审结果
- `POST /api/ai-review/{review_id}/suggestion/{id}/adopt` - 采纳 AI 建议
- `POST /api/ai-review/{review_id}/suggestion/{id}/ignore` - 忽略 AI 建议

### Webhook

- `POST /webhook/github` - GitHub Webhook 端点
- `POST /webhook/gitlab` - GitLab Webhook 端点
- `POST /webhook/gitee` - Gitee Webhook 端点

### 管理接口

- `GET /admin/dashboard` - 管理仪表盘
- `GET /admin/stats` - 统计数据
- `GET /admin/repos` - 仓库管理
- `GET /admin/checklists` - Checklist 模板管理

## 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建你的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交你的更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启一个 Pull Request

### 代码规范

- 遵循 Rust 官方代码风格
- 使用 `cargo fmt` 格式化代码
- 使用 `cargo clippy` 检查代码质量
- 确保所有测试通过 (`cargo test`)
- 为新功能添加适当的文档注释

### 提交规范

请使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
feat: 添加新功能
fix: 修复bug
docs: 文档更新
style: 代码格式调整
refactor: 代码重构
test: 测试相关
chore: 构建/工具链相关
```

## License

本项目采用 MIT License - 详见 [LICENSE](LICENSE) 文件。
