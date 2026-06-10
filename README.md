# DataFlow - 实时协作运营数据看板搭建器

一款面向运营团队的可视化看板搭建系统，支持拖拽式布局、多数据源接入、实时数据推送和定时报表导出。

## ✨ 功能特性

### 🎨 看板布局编辑器
- **拖拽式网格布局**：基于 GridStack 实现，图表组件自由排列、缩放
- **所见即所得**：实时预览布局效果，支持响应式布局
- **布局JSON持久化**：布局配置序列化为JSON存储，便于版本管理和分享
- **7种图表类型**：折线图、柱状图、饼图、热力图、漏斗图、散点图、仪表盘

### 🔌 多数据源连接器
- **MySQL**：关系型数据库查询
- **ClickHouse**：OLAP大数据分析引擎
- **Prometheus**：监控指标时序数据库
- **自定义HTTP API**：对接第三方REST API
- **查询参数模板化**：支持 `{{ param }}` 语法实现动态查询
- **SQL注入防护**：参数化查询确保数据安全

### ⚡ 实时数据推送
- **SSE 通道**：Server-Sent Events 增量推送数据变更
- **前端无刷新更新**：图表数据自动更新，无需刷新页面
- **按看板订阅**：支持按看板粒度订阅不同数据流
- **连接状态监控**：实时显示连接状态，断开自动重连

### 📦 看板模板市场
- **预置系统模板**：双十一销售大屏、用户增长漏斗、客服工单热力图、运营数据总览
- **一键应用模板**：快速复制模板，替换为自有数据源
- **自定义模板**：支持将已有看板保存为模板
- **评分与使用统计**：模板质量反馈机制

### 🔐 权限与分享
- **团队共享**：按团队/用户粒度共享，支持只读/编辑两种权限
- **外部分享链接**：生成带时效的外部分享链接
- **密码保护**：支持设置访问密码
- **访问次数限制**：可配置最大访问次数和过期时间

### 📊 定时快照与报表导出
- **Cron定时任务**：支持灵活的定时调度规则
- **自动截图**：Playwright 无头浏览器渲染看板截图
- **PDF报表生成**：WeasyPrint 生成精美PDF报表
- **邮件分发**：自动将报表发送给指定收件人

### 💾 数据缓存与查询优化
- **Redis缓存**：查询结果自动缓存，按数据源配置TTL
- **智能TTL**：MySQL 60s、ClickHouse 30s、Prometheus 15s、HTTP 60s
- **查询合并**：相同查询自动合并，减少数据库压力
- **缓存健康监控**：实时监控缓存命中率

## 🛠️ 技术栈

### 后端
- **Flask 3.0**：Python Web 框架
- **SQLAlchemy 2.0**：ORM 数据库操作
- **Redis 5.0**：缓存和消息队列
- **Celery 5.3**：异步任务队列
- **MySQL / ClickHouse / Prometheus**：多数据源驱动

### 前端
- **Jinja2**：模板引擎
- **HTMX 1.9**：轻量AJAX交互
- **Alpine.js 3.13**：轻量响应式框架
- **ECharts 5.4**：图表渲染
- **GridStack 10.1**：拖拽式网格布局
- **Bootstrap 5.3**：UI组件库

### 工具
- **Playwright**：无头浏览器截图
- **WeasyPrint**：PDF生成
- **Fernet**：连接配置加密
- **Flask-Login**：用户认证
- **Flask-WTF**：CSRF保护

## 📁 项目结构

```
DF1-68/
├── app/
│   ├── api/                    # API路由
│   │   ├── __init__.py
│   │   ├── auth.py             # 认证API
│   │   ├── dashboard.py        # 看板API
│   │   ├── datasource.py       # 数据源API
│   │   ├── chart.py            # 图表API
│   │   ├── template.py         # 模板API
│   │   ├── sse.py              # SSE实时推送
│   │   ├── share.py            # 分享API
│   │   └── report.py           # 报表API
│   ├── models/                 # 数据模型
│   │   ├── user.py             # 用户/团队/角色
│   │   ├── dashboard.py        # 看板
│   │   ├── datasource.py       # 数据源
│   │   ├── chart.py            # 图表
│   │   ├── template.py         # 模板
│   │   ├── share.py            # 分享
│   │   └── report.py           # 报表
│   ├── services/               # 业务逻辑层
│   │   ├── auth_service.py
│   │   ├── dashboard_service.py
│   │   ├── datasource_service.py
│   │   ├── chart_service.py
│   │   ├── template_service.py
│   │   ├── share_service.py
│   │   ├── report_service.py
│   │   └── init_service.py
│   ├── tasks/                  # Celery任务
│   │   ├── celery_app.py       # Celery应用配置
│   │   ├── report_tasks.py     # 报表生成任务
│   │   └── maintenance_tasks.py # 系统维护任务
│   ├── templates/              # Jinja2模板
│   │   ├── base.html
│   │   ├── index.html
│   │   ├── auth/
│   │   ├── dashboards/
│   │   ├── datasources/
│   │   ├── templates/
│   │   ├── share/
│   │   ├── modals/
│   │   ├── report/
│   │   └── errors/
│   ├── static/                 # 静态资源
│   │   ├── css/style.css
│   │   └── js/app.js
│   ├── utils/                  # 工具函数
│   │   ├── decorators.py       # 装饰器集合
│   │   └── filters.py          # 模板过滤器
│   └── __init__.py             # 应用工厂
├── config.py                   # 配置文件
├── run.py                      # 入口文件
├── requirements.txt            # Python依赖
├── .env.example                # 环境变量示例
└── README.md
```

## 🚀 快速开始

### 环境要求
- Python 3.10+
- Redis 6.0+
- MySQL 8.0+ (可选，可选SQLite)
- Node.js 16+ (用于Playwright)

### 1. 克隆项目
```bash
cd /Users/huangzitong/Desktop/SoloCoder6月/Code/66-70/DF1-68
```

### 2. 创建虚拟环境
```bash
python3 -m venv venv
source venv/bin/activate
```

### 3. 安装依赖
```bash
pip install -r requirements.txt
```

### 4. 配置环境变量
```bash
cp .env.example .env
```

编辑 `.env` 文件，根据需要修改配置：
```env
# 数据库配置
DATABASE_URL=sqlite:///dashboard.db

# Redis配置
REDIS_URL=redis://localhost:6379/0

# 加密密钥（使用自己的密钥）
SECRET_KEY=your-secret-key-here
FERNET_KEY=your-fernet-key-here

# 邮件配置（用于报表发送）
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USER=your-email@example.com
SMTP_PASSWORD=your-password

# 缓存TTL
CACHE_TTL_MYSQL=60
CACHE_TTL_CLICKHOUSE=30
CACHE_TTL_PROMETHEUS=15
CACHE_TTL_HTTP=60
```

### 5. 初始化数据库
```bash
# 初始化数据库表
flask init-db

# 创建管理员账户
flask create-admin --email admin@example.com --password admin123 --name 管理员

# 预置系统模板
flask seed-templates
```

### 6. 安装Playwright浏览器（可选，用于报表截图）
```bash
pip install playwright
playwright install chromium
```

### 7. 启动服务

#### 方式一：使用启动脚本
```bash
chmod +x start.sh
./start.sh
```

#### 方式二：手动启动各服务

**启动Flask应用：**
```bash
python run.py
```

**启动Celery Worker（报表任务）：**
```bash
celery -A app.tasks.celery_app worker --loglevel=info
```

**启动Celery Beat（定时任务调度）：**
```bash
celery -A app.tasks.celery_app beat --loglevel=info
```

### 8. 访问应用
- 首页: http://localhost:5000
- 登录: http://localhost:5000/login
- 使用 `admin@example.com` / `admin123` 登录

## 📖 API文档

### 认证相关
| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/auth/login` | 用户登录 |
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/logout` | 用户登出 |
| GET | `/api/auth/me` | 获取当前用户信息 |

### 看板相关
| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/dashboards` | 获取看板列表 |
| POST | `/api/dashboards` | 创建看板 |
| GET | `/api/dashboards/<id>` | 获取看板详情 |
| PUT | `/api/dashboards/<id>` | 更新看板 |
| DELETE | `/api/dashboards/<id>` | 删除看板 |
| PUT | `/api/dashboards/<id>/layout` | 保存布局配置 |
| GET | `/api/dashboards/<id>/charts` | 获取看板图表列表 |
| POST | `/api/dashboards/<id>/charts` | 添加图表到看板 |

### 数据源相关
| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/datasources` | 获取数据源列表 |
| POST | `/api/datasources` | 创建数据源 |
| GET | `/api/datasources/<id>` | 获取数据源详情 |
| PUT | `/api/datasources/<id>` | 更新数据源 |
| DELETE | `/api/datasources/<id>` | 删除数据源 |
| POST | `/api/datasources/<id>/test` | 测试连接 |
| POST | `/api/datasources/<id>/query` | 执行查询 |

### 图表相关
| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/charts/<id>` | 获取图表详情 |
| PUT | `/api/charts/<id>` | 更新图表配置 |
| DELETE | `/api/charts/<id>` | 删除图表 |
| GET | `/api/charts/<id>/data` | 获取图表数据 |
| PUT | `/api/charts/<id>/position` | 更新图表位置 |

### 实时推送
| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/sse/dashboard/<id>` | 看板级SSE通道 |
| GET | `/api/sse/chart/<id>` | 图表级SSE通道 |
| POST | `/api/sse/push/<dashboard_id>` | 推送数据更新 |

### 分享相关
| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/share/dashboard/<id>` | 创建分享链接 |
| GET | `/s/<token>` | 访问分享看板 |
| DELETE | `/api/share/<token>` | 撤销分享链接 |

### 报表相关
| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/reports` | 获取报表列表 |
| POST | `/api/reports` | 创建报表任务 |
| DELETE | `/api/reports/<id>` | 删除报表任务 |
| POST | `/api/reports/<id>/run` | 立即执行报表 |
| GET | `/api/reports/history` | 获取报表历史 |

## 🎯 使用指南

### 创建第一个看板

1. **登录系统**：使用管理员账户登录
2. **添加数据源**：进入「数据源」页面，添加MySQL/ClickHouse等数据源
3. **创建看板**：进入「我的看板」，点击「新建看板」
4. **编辑布局**：进入编辑模式，从左侧选择图表类型，添加到看板
5. **配置图表**：点击图表，在右侧属性面板配置数据源和查询语句
6. **保存布局**：点击「保存布局」按钮
7. **查看看板**：点击「预览」查看最终效果

### 配置定时报表

1. **确保Celery服务运行**：启动Celery Worker和Beat
2. **创建报表任务**：进入看板详情，设置报表标题、Cron表达式、收件人
3. **测试报表**：点击「立即执行」测试报表生成
4. **查看历史**：在报表中心查看历史报表记录

### 使用模板市场

1. **浏览模板**：进入「模板市场」浏览可用模板
2. **预览模板**：点击「预览详情」查看模板包含的图表
3. **应用模板**：点击「使用模板」，替换为自己的数据源
4. **保存为模板**：在看板编辑页面点击「存为模板」

## 🔧 配置说明

### 缓存TTL配置
在 `config.py` 中配置各数据源的缓存时间：
```python
CACHE_TTL = {
    'default': 300,
    'mysql': 60,
    'clickhouse': 30,
    'prometheus': 15,
    'http': 60
}
```

### 连接配置加密
数据源连接配置使用Fernet对称加密存储，需设置 `FERNET_KEY` 环境变量：
```bash
# 生成新的Fernet密钥
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

### Celery定时任务
在 `app/tasks/celery_app.py` 中配置：
```python
beat_schedule = {
    'process-scheduled-reports': {
        'task': 'app.tasks.report_tasks.process_scheduled_reports',
        'schedule': 60.0,  # 每分钟执行一次
    },
    'cleanup-expired-shares': {
        'task': 'app.tasks.maintenance_tasks.cleanup_expired_shares',
        'schedule': crontab(hour=2, minute=0),  # 每天凌晨2点
    },
}
```

## 🧪 测试

### 运行测试
```bash
# 安装测试依赖
pip install pytest pytest-flask pytest-cov

# 运行测试
pytest tests/

# 覆盖率报告
pytest tests/ --cov=app --cov-report=html
```

### 主要测试点
- 用户认证与权限系统
- 看板CRUD与布局保存
- 数据源连接与查询
- 图表数据转换与渲染
- 分享链接生成与验证
- 报表任务调度与生成
- SSE实时推送
- 缓存命中率测试

## 🔒 安全注意事项

1. **修改默认密钥**：生产环境务必修改 `SECRET_KEY` 和 `FERNET_KEY`
2. **使用HTTPS**：生产环境建议使用HTTPS协议
3. **数据库权限**：数据库账号遵循最小权限原则
4. **SQL注入防护**：所有查询使用参数化查询，避免拼接SQL
5. **CORS配置**：根据实际需要配置跨域访问
6. **登录限流**：建议添加登录失败次数限制
7. **连接配置加密**：敏感配置使用Fernet加密存储

## 🐛 常见问题

### Q: Redis连接失败
**A**: 确保Redis服务已启动，检查 `REDIS_URL` 配置是否正确：
```bash
redis-cli ping  # 应返回 PONG
```

### Q: Celery任务不执行
**A**: 检查以下几点：
1. Celery Worker 是否启动
2. Redis 连接是否正常
3. 任务是否已注册到 `celery_app`
4. 查看 Worker 日志排查错误

### Q: Playwright截图失败
**A**: 确保已正确安装Chromium浏览器：
```bash
playwright install chromium
playwright install-deps  # 安装系统依赖
```

### Q: 图表不显示数据
**A**: 检查：
1. 数据源连接测试是否成功
2. 查询语句是否正确，可在数据源测试页面验证
3. 查看浏览器控制台是否有JS错误
4. 检查后端API返回是否正确

### Q: SSE连接断开
**A**: SSE会自动重连，检查：
1. 服务器配置是否支持长连接
2. 反向代理（如Nginx）超时配置
3. 网络稳定性

## 📝 开发计划

- [ ] 支持更多图表类型（雷达图、桑基图、词云等）
- [ ] 图表钻取与联动筛选
- [ ] 看板版本管理与回滚
- [ ] 协同编辑与实时冲突解决
- [ ] 更多数据源支持（PostgreSQL、MongoDB、InfluxDB等）
- [ ] 移动端适配
- [ ] 插件化架构支持自定义图表
- [ ] 数据告警与通知

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目仅供学习和内部使用。

## 📞 联系方式

如有问题或建议，请通过以下方式联系：
- 项目 Issues
- 邮箱: support@dataflow.example

---

**DataFlow** - 让运营数据可视化更简单 🚀
