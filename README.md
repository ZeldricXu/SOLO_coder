# 📝 周报自动汇总系统

> 告别周五晚上复制粘贴的噩梦！周一自动收、周五自动发。

## ✨ 功能特性

### 1. 📄 周报模板管理
- 管理员自定义模板，支持 **Markdown文本 / 单选 / 多选 / 普通文本** 四种字段类型
- 支持 **必填 / 选填** 配置
- **模板版本管理**：改模板不影响历史周报，每一次字段变更自动创建新版本
- 支持标记特殊字段：`本周完成` / `下周计划` / `风险阻塞`（自动用于汇总逻辑）

### 2. ⏰ 收报引擎（自动提醒）
| 时间 | 提醒类型 |
|------|----------|
| 每周一 09:00 | 首次填写提醒 |
| 每周三 10:00 | 追加提醒（未填人员） |
| 每周五 10:00 | 紧急提醒 |
| 截止前 2 小时 | `@` 级别最后通牒 |

- **多渠道推送**：企业微信 / 飞书 / 邮件（同时发送）
- **不同团队自定义截止时间**（支持周几、几点、几分）
- **代理填写**：TL 可帮休假组员代交周报
- 草稿自动保存，已提交需管理员/TL 撤回

### 3. 📊 汇总生成（每周五 18:00 自动）
- 按 **团队** 维度聚合，显示提交率、人均字数
- **风险/阻塞项单独标红展示**（自动识别风险字段）
- **智能偏离检测**：自动对比「上周的下周计划」和「本周的本周完成」，标记未兑现项
- 偏离级别：`一般 / 重大`

### 4. 📈 统计面板
- 近 N 周 **个人填写率趋势图**
- 本周 **团队提交速度排行榜**（🥇🥈🥉）
- **人均填写字数统计**
- **关键词词云**（jieba 分词 + 去停用词）— 一眼看整个部门这周在忙什么
- 提醒日志审计（谁、什么时间、通过什么渠道、发送结果）

### 5. 📤 导出与分发
- **自动生成 PDF**（ReportLab，样式专业）
- 支持 PDF / Markdown / JSON 三种格式下载
- 汇总结果发送到：
  - 指定邮箱（SMTP）
  - 企业微信群 Webhook
  - 飞书群 Webhook
  - Confluence / 语雀 / Notion（可扩展）

---

## 🛠 技术栈

| 模块 | 技术 |
|------|------|
| **后端** | Python 3.9+ · FastAPI · SQLAlchemy · Pydantic |
| **数据库** | SQLite（零配置，轻量）|
| **定时任务** | APScheduler (CronTrigger) |
| **通知推送** | requests（Webhook） · smtplib（邮件）|
| **PDF** | ReportLab |
| **词云/NLP** | jieba · wordcloud · matplotlib |
| **前端** | Vue 3 · Vite · Element Plus · Pinia · Vue Router · ECharts |
| **Markdown** | marked.js |

---

## 🚀 快速开始

### 方式一：启动脚本（推荐）

```bash
cd DF1-105
chmod +x start.sh
./start.sh
```

### 方式二：手动启动

#### 1️⃣ 后端

```bash
cd DF1-105/backend

# 虚拟环境（可选但推荐）
python3 -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# 安装依赖
pip install -r requirements.txt

# 配置环境变量（可选，有默认值）
cp .env.example .env
# 编辑 .env 填入 企业微信/飞书/邮件 等配置

# 启动（首次会自动建表 + 初始化默认数据）
python main.py
# 或
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

**后端地址**：http://localhost:8000  
**API 文档**：http://localhost:8000/docs （Swagger UI）

#### 2️⃣ 前端（新终端）

```bash
cd DF1-105/frontend

# 安装依赖
npm install   # 或 pnpm i / yarn

# 开发模式启动
npm run dev
```

**前端地址**：http://localhost:5173

---

## 🔑 默认账号

首次启动自动初始化：

| 用户名 | 密码 | 角色 | 说明 |
|--------|------|------|------|
| `admin` | `admin123` | 超级管理员 | 全部权限 |
| `zhangsan` ~ `zhoujiu` | `123456` | 普通成员 | 分属三个演示团队 |

> ⚠️ **生产环境请立刻修改 admin 默认密码！**

---

## 📁 目录结构

```
DF1-105/
├── backend/                         # FastAPI 后端
│   ├── main.py                      # 入口（含 lifespan、默认数据初始化）
│   ├── requirements.txt
│   ├── .env.example
│   ├── app/
│   │   ├── api/                     # API 路由层
│   │   │   ├── auth.py              # 登录/注册
│   │   │   ├── users.py             # 用户 CRUD
│   │   │   ├── teams.py             # 团队 + 通知配置
│   │   │   ├── templates.py         # 模板 + 版本管理
│   │   │   ├── reports.py           # 周报提交/撤回/代理
│   │   │   ├── summaries.py         # 汇总生成 + 偏离检测
│   │   │   ├── statistics.py        # 统计 + 词云
│   │   │   └── export.py            # 导出/分发/手动触发提醒
│   │   ├── core/                    # 核心模块
│   │   │   ├── config.py            # 配置（pydantic-settings）
│   │   │   ├── database.py          # SQLAlchemy 引擎 & 会话
│   │   │   ├── security.py          # JWT + 权限依赖
│   │   │   └── utils.py             # 周次计算工具
│   │   ├── models/models.py         # SQLAlchemy ORM 模型（11张表）
│   │   ├── schemas/schemas.py       # Pydantic 请求/响应模型
│   │   ├── services/notification.py # 通知推送服务
│   │   └── scheduler/tasks.py       # APScheduler 定时任务
│   └── exports/                     # 生成的 PDF 等文件存放
│
└── frontend/                        # Vue 3 前端
    ├── package.json
    ├── vite.config.js               # 已配置 /api 代理到 8000
    ├── index.html
    └── src/
        ├── main.js
        ├── App.vue
        ├── router/index.js          # 路由 + 路由守卫
        ├── store/user.js            # Pinia 用户状态
        ├── api/index.js             # 封装好的请求
        ├── utils/request.js         # axios 拦截器（自动带 token）
        ├── styles/global.scss
        ├── layouts/MainLayout.vue   # 主布局（侧边栏+顶栏）
        └── views/
            ├── Login.vue
            ├── Dashboard.vue            # 工作台
            ├── report/
            │   ├── ReportWrite.vue      # 填写周报（含Markdown实时预览）
            │   └── ReportList.vue       # 周报列表 + 待提交 + 代理
            ├── summary/SummaryView.vue  # 汇总查看（按团队/风险/偏离）
            ├── statistics/Statistics.vue
            └── admin/
                ├── TemplateManage.vue   # 模板+字段可视化管理
                ├── TeamManage.vue       # 团队+截止时间+通知配置
                ├── UserManage.vue       # 用户CRUD
                └── ExportDistribute.vue # 导出分发+定时任务面板
```

---

## 🔧 配置说明（.env）

只需把 `.env.example` 复制为 `.env`，按需填写：

### 必改项
```
SECRET_KEY=随便打一串长字符串（生产环境一定要改！）
```

### 可选推送渠道
| 配置项 | 说明 |
|--------|------|
| `WECOM_BOT_WEBHOOK` | 全局企业微信群机器人 Webhook |
| `FEISHU_BOT_WEBHOOK` | 全局飞书群机器人 Webhook |
| `SMTP_HOST / PORT / USER / PASSWORD` | 邮件服务器配置 |
| `CONFLUENCE_*` | Confluence Wiki 配置 |
| `YUQUE_*` | 语雀配置 |
| `NOTION_TOKEN / DATABASE_ID` | Notion 配置 |

> 团队级别的 webhook 在 **团队管理 → 通知配置** 中为每个团队单独设置。

### 定时任务时间（可选调）
```
REMINDER_MONDAY_HOUR=9     # 周一提醒几点？
REMINDER_WEDNESDAY_HOUR=10 # 周三
REMINDER_FRIDAY_HOUR=10    # 周五
SUMMARY_FRIDAY_HOUR=18     # 周五一几点生成汇总？
```

---

## 📚 主要 API 速查

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录（FormData） |
| GET | `/api/reports/my-current` | 获取/自动创建我本周的周报 |
| PUT | `/api/reports/:id` | 保存草稿 / 提交周报 |
| POST | `/api/reports/proxy-submit` | TL 代理填写 |
| GET | `/api/summaries/current` | 查看本周汇总（懒生成） |
| POST | `/api/summaries/generate` | 管理员强制重新生成 |
| GET | `/api/statistics/word-cloud` | 关键词词云 |
| POST | `/api/export/distribute` | 一键多渠道分发汇总 |
| POST | `/api/export/send-reminder` | 手动批量发送提醒 |
| POST | `/api/scheduler/trigger/:name` | 手动触发定时任务调试 |

完整文档：启动后访问 http://localhost:8000/docs

---

## 🧪 快速验证

1. 启动后端和前端
2. 访问 http://localhost:5173
3. 用 `admin / admin123` 登录
4. 用 `zhangsan / 123456`（在新浏览器/隐身模式）登录，填一份周报
5. 回到 admin → **导出分发** → 点击「立即执行 周五18点汇总」
6. 去 **汇总查看** / **统计面板** 看效果
7. （可选）把你自己的企业微信 webhook 贴进 **团队管理 → 通知配置**，然后点「📢 发送批量提醒」测试推送

---

## 🔐 权限模型

| 角色 | 权限 |
|------|------|
| `super_admin` | 全部（含删除用户、设默认模板）|
| `admin` (TL) | 本团队：代交周报、撤回成员周报、看全团队数据 |
| `user` | 自己的周报、查看汇总、查看统计 |

---

## 📌 注意事项

1. **SQLite 无服务端**：数据库就是 `backend/weekly_report.db` 一个文件，直接拷贝 = 备份。
2. **APScheduler** 单进程跑，后端重启不会重复执行（misfire_grace_time 已配）。
3. **词云** 首次会加载 jieba 词典（慢一次），之后正常。
4. 如果只想体验 **不想配推送渠道**，系统完全可用（提醒发送失败会记录日志，不阻塞其他流程）。

---

🎯 **用起来，让周五晚上不再属于周报！**
