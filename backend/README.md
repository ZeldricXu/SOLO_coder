# InkSoul AI 后端

基于 FastAPI 的智能小说创作管理平台后端服务。

## 技术栈

- **框架**: FastAPI
- **数据库**: PostgreSQL + pgvector
- **ORM**: SQLAlchemy
- **AI**: LangChain + OpenAI/DeepSeek
- **向量存储**: Pinecone 或 PostgreSQL pgvector

## 快速开始

### 环境要求

- Python 3.11+
- PostgreSQL 15+ (可选，开发阶段可使用内存存储)

### 安装依赖

```bash
cd backend

# 使用 pip
pip install -r requirements.txt

# 或使用 uv (推荐)
uv pip install -e .
```

### 配置环境变量

创建 `.env` 文件：

```env
# 数据库
DATABASE_URL=postgresql+asyncpg://user:password@localhost:5432/inksoul

# Supabase (可选)
SUPABASE_URL=your_supabase_url
SUPABASE_KEY=your_supabase_key

# AI 配置
OPENAI_API_KEY=your_openai_api_key
OPENAI_BASE_URL=https://api.deepseek.com/v1  # 或其他 OpenAI 兼容接口
OPENAI_MODEL=gpt-3.5-turbo

# 安全
SECRET_KEY=your-secret-key-change-in-production
```

### 启动服务

```bash
# 开发模式
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# 或使用 python
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### API 文档

启动后访问：
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## 项目结构

```
backend/
├── app/
│   ├── api/
│   │   └── v1/
│   │       ├── endpoints/      # API 路由
│   │       │   ├── novels.py   # 小说管理
│   │       │   ├── chapters.py # 章节管理
│   │       │   ├── assets.py   # 设定管理
│   │       │   └── write.py    # AI 写作接口
│   │       └── __init__.py
│   ├── core/                    # 核心配置
│   │   ├── config.py
│   │   └── __init__.py
│   ├── models/                  # 数据库模型 (待实现)
│   ├── schemas/                 # Pydantic 模型
│   │   ├── novel.py
│   │   ├── volume.py
│   │   ├── chapter.py
│   │   ├── asset.py
│   │   ├── write.py
│   │   └── __init__.py
│   ├── services/                # 业务逻辑
│   │   ├── ai_service.py       # AI 服务
│   │   ├── novel_service.py    # 小说服务
│   │   └── __init__.py
│   └── main.py                  # 应用入口
├── pyproject.toml
└── requirements.txt
```

## API 接口

### 小说管理
- `GET /api/v1/novels/` - 获取小说列表
- `POST /api/v1/novels/` - 创建小说
- `GET /api/v1/novels/{id}` - 获取小说详情
- `PUT /api/v1/novels/{id}` - 更新小说
- `DELETE /api/v1/novels/{id}` - 删除小说

### 章节管理
- `GET /api/v1/chapters/` - 获取章节列表
- `POST /api/v1/chapters/` - 创建章节
- `GET /api/v1/chapters/{id}` - 获取章节详情
- `PATCH /api/v1/chapters/{id}` - 增量更新章节
- `DELETE /api/v1/chapters/{id}` - 删除章节

### 设定管理
- `GET /api/v1/assets/search` - 语义搜索设定
- `GET /api/v1/assets/` - 获取设定列表
- `POST /api/v1/assets/` - 创建设定
- `GET /api/v1/assets/{id}` - 获取设定详情
- `PUT /api/v1/assets/{id}` - 更新设定
- `DELETE /api/v1/assets/{id}` - 删除设定

### AI 写作
- `POST /api/v1/write/autocomplete` - AI 续写（流式）
- `POST /api/v1/write/polish` - AI 润色
- `POST /api/v1/write/inspire` - AI 灵感

## 开发计划

- [ ] 实现数据库模型和迁移
- [ ] 实现用户认证
- [ ] 集成向量数据库
- [ ] 完善 AI 服务
- [ ] 添加测试
- [ ] 优化性能
