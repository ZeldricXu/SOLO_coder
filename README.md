# Unified Notification Gateway

统一通知网关与Webhook分发服务 - 基于Node.js + TypeScript + Fastify的企业级通知服务。

## 功能特性

### 一、通知路由器（核心调度）
- 接收标准化的通知请求（JSON payload）
- 路由规则支持：用户偏好覆盖、渠道可用性检查、消息优先级、A/B测试分流和灰度发布
- 全渠道触达支持：紧急通知并行推送到所有可用渠道并等待ACK

### 二、渠道适配器
- **邮件**：SMTP relay + SendGrid API双通道，自动failover
- **短信**：阿里云短信 + Twilio双供应商，按区域自动路由
- **移动推送**：FCM（Android）+ APNs（iOS）双平台
- **即时通讯**：Slack Incoming Webhook、企业微信应用消息、飞书机器人
- **Webhook**：支持用户自定义URL回调，自定义HTTP头、签名密钥和重试策略

### 三、模板引擎
- Handlebars模板管理
- 支持按 notification_type + locale 查找对应模板
- 变量插值、条件渲染、默认值回退
- 提供/preview接口实时预览模板渲染效果

### 四、投递追踪器
- 每条通知生成全局唯一的delivery_id
- 记录各渠道发送状态（pending→sent→delivered→failed）
- 对接渠道回调更新投递状态
- 提供投递日志查询API

### 五、限流与节流器
- 基于令牌桶算法实现多层限流
- 渠道级别（SendGrid每日配额50000封）
- 用户级别（单个用户每分钟最多5条短信）
- 租户级别（每个业务线的并发发送数上限）
- 超限消息排队延迟发送，TTL过期后进DLQ

### 六、通知偏好中心API
- 用户可管理自己的通知偏好
- 按渠道和通知类型两个维度做opt-in/opt-out
- "免打扰时段"设置
- 偏好变更审计日志

### 七、Webhook管理控制台
- 租户注册自己的Webhook端点
- URL、签名密钥（HMAC-SHA256）、事件类型、重试配置
- 投递日志面板展示每次Webhook调用的请求/响应详情

### 八、多租户隔离
- tenant_id级别的数据隔离
- PostgreSQL Row-Level Security策略
- 每个租户独立的配额限制和速率限制配置
- 所有管理操作审计日志

## 技术栈

- **Runtime**: Node.js 18+
- **Language**: TypeScript
- **HTTP Framework**: Fastify
- **Queue**: BullMQ (Redis)
- **Database**: PostgreSQL
- **Cache/Limiter**: Redis
- **Template Engine**: Handlebars
- **Validation**: Zod

## 快速开始

### 环境要求
- Node.js 18+
- PostgreSQL 14+
- Redis 6+

### 安装依赖

```bash
cd DF1-12
npm install
```

### 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 文件配置数据库、Redis和各服务API密钥
```

### 数据库迁移

```bash
npm run db:migrate
npm run db:seed
```

### 启动开发服务器

```bash
npm run dev
```

### 构建生产版本

```bash
npm run build
npm start
```

## API 文档

### 通知发送

```bash
POST /api/v1/notifications/send
Content-Type: application/json

{
  "tenant_id": "tenant-uuid",
  "notification_type": "transactional",
  "recipient": {
    "email": "user@example.com",
    "phone": "+8613800138000"
  },
  "content": {
    "subject": "订单确认",
    "body": "您的订单已确认",
    "html": "<p>您的订单已确认</p>"
  },
  "channel_preference": ["email", "sms"],
  "priority": "high",
  "template_variables": {
    "order_id": "12345",
    "amount": 99.99
  }
}
```

### 查询投递状态

```bash
GET /api/v1/notifications/{delivery_id}/status
X-Tenant-Id: tenant-uuid
```

### 模板预览

```bash
POST /api/v1/templates/preview
Content-Type: application/json

{
  "body_template": "Hello {{name}}!",
  "variables": {
    "name": "World"
  }
}
```

### 更多API

- `GET /health` - 健康检查
- `GET /api/v1/admin/health` - 渠道健康状态
- `GET /api/v1/admin/queue/stats` - 队列统计
- `GET /api/v1/admin/queue/dlq` - 死信队列
- `POST /api/v1/webhooks` - 创建Webhook端点
- `GET /api/v1/preferences/{user_id}` - 获取用户偏好

## 项目结构

```
DF1-12/
├── src/
│   ├── adapters/          # 渠道适配器
│   │   ├── BaseAdapter.ts
│   │   ├── EmailAdapter.ts
│   │   ├── SMSAdapter.ts
│   │   ├── PushAdapter.ts
│   │   ├── SlackAdapter.ts
│   │   ├── WeChatAdapter.ts
│   │   ├── WebhookAdapter.ts
│   │   └── AdapterManager.ts
│   ├── router/            # 通知路由器
│   │   └── NotificationRouter.ts
│   ├── templates/         # 模板引擎
│   │   └── TemplateEngine.ts
│   ├── tracking/          # 投递追踪
│   │   └── DeliveryTracker.ts
│   ├── ratelimit/         # 限流
│   │   └── RateLimiter.ts
│   ├── preferences/       # 用户偏好
│   │   └── PreferenceManager.ts
│   ├── webhook/           # Webhook管理
│   │   └── WebhookManager.ts
│   ├── queue/             # 任务队列
│   │   └── NotificationQueue.ts
│   ├── db/                # 数据库
│   │   ├── index.ts
│   │   ├── migrate.ts
│   │   └── seed.ts
│   ├── controllers/       # API控制器
│   │   ├── notificationController.ts
│   │   ├── templateController.ts
│   │   ├── preferenceController.ts
│   │   ├── webhookController.ts
│   │   └── adminController.ts
│   ├── types/             # 类型定义
│   │   └── index.ts
│   ├── config/            # 配置
│   │   └── index.ts
│   ├── utils/             # 工具
│   │   └── logger.ts
│   └── index.ts           # 应用入口
├── package.json
├── tsconfig.json
└── .env.example
```

## 许可证

MIT
