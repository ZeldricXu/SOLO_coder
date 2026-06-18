# 实时业务监控大盘 (Biz Monitor)

统一数据口径的实时业务监控平台，支持电商、广告、会员三条业务线的数据可视化和异常告警。

## 技术栈

### 后端
- **框架**: NestJS 10
- **ORM**: Prisma 5 + MySQL (元数据存储)
- **数据查询**: ClickHouse (大数据查询)
- **缓存/消息**: Redis + BullMQ
- **实时推送**: WebSocket + Redis Pub/Sub
- **认证**: JWT + Passport

### 前端
- **框架**: React 18 + TypeScript
- **构建**: Vite 5
- **UI**: Ant Design 5
- **图表**: ECharts 5
- **布局**: react-grid-layout (拖拽式看板)
- **状态**: Zustand
- **路由**: React Router 6

## 功能模块

### 1. 数据源连接器
- 支持 4 种数据源类型：MySQL、ClickHouse、PostgreSQL、HTTP API
- 连接池管理、查询超时熔断、字段类型自动推断

### 2. 指标定义引擎
- SQL 编辑器 + 预置指标模板
- 支持聚合函数（SUM/COUNT/AVG/MAX/MIN）
- 时间窗口、维度下钻
- 同比（YoY）、环比（MoM）自动计算

### 3. 看板编排器
- 拖拽式布局，支持 6 种组件：
  - 折线图、柱状图、饼图、表格、数字卡片、漏斗图
- 组件自由缩放和重排
- 全局筛选器 + 组件间联动筛选
- 看板配置 JSON 序列化，支持导入导出

### 4. 实时推送管道
- WebSocket + Redis Pub/Sub 数据变更通知
- 前端增量更新图表，不整页刷新
- 推送频率后端节流，防止高频抖动

### 5. 告警规则引擎
- 3 种告警类型：
  - 指标阈值告警
  - 同环比波动告警
  - 数据断流告警
- 3 种通知渠道：邮件、企业微信、钉钉
- 告警静默期 + 升级策略

### 6. 权限与多租户
- 按业务线做数据隔离
- 4 种角色：超级管理员、租户管理员、编辑者、查看者
- 看板编辑/查看权限分离
- 完整的操作审计日志

## 快速开始

### 一键启动

```bash
# 给启动脚本添加执行权限
chmod +x start.sh

# 启动所有服务（需要 Docker）
./start.sh
```

### 手动启动

#### 1. 启动基础设施

```bash
docker-compose up -d
```

#### 2. 启动后端

```bash
cd backend
npm install
npx prisma generate
npx prisma db push
npm run start:dev
```

#### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

### 访问地址

- 前端: http://localhost:5173
- 后端 API: http://localhost:3000
- API 文档: http://localhost:3000/api (如需 Swagger 可自行添加)

## 项目结构

```
DF1-82/
├── backend/
│   ├── src/
│   │   ├── alert/              # 告警模块
│   │   ├── audit/              # 审计日志模块
│   │   ├── auth/               # 认证模块
│   │   ├── common/             # 公共组件（守卫、拦截器、装饰器）
│   │   ├── dashboard/          # 看板模块
│   │   ├── data-source/        # 数据源连接器
│   │   ├── metric/             # 指标引擎
│   │   ├── prisma/             # Prisma 服务
│   │   ├── realtime/           # 实时推送
│   │   ├── tenant/             # 租户管理
│   │   ├── app.module.ts
│   │   └── main.ts
│   ├── prisma/
│   │   └── schema.prisma       # 数据模型
│   └── package.json
├── frontend/
│   ├── src/
│   │   ├── components/         # 组件
│   │   │   └── widgets/        # 6种图表组件
│   │   ├── pages/              # 页面
│   │   ├── services/           # API 服务
│   │   ├── store/              # 状态管理
│   │   ├── types/              # TypeScript 类型
│   │   ├── utils/              # 工具函数
│   │   ├── App.tsx
│   │   └── main.tsx
│   └── package.json
├── docker-compose.yml
└── start.sh
```

## API 接口

### 数据源
- `GET /api/data-sources` - 获取数据源列表
- `POST /api/data-sources` - 创建数据源
- `PUT /api/data-sources/:id` - 更新数据源
- `DELETE /api/data-sources/:id` - 删除数据源
- `POST /api/data-sources/:id/test` - 测试连接
- `POST /api/data-sources/:id/query` - 执行查询
- `GET /api/data-sources/:id/schema` - 推断字段类型

### 指标
- `GET /api/metrics` - 获取指标列表
- `POST /api/metrics` - 创建指标
- `PUT /api/metrics/:id` - 更新指标
- `DELETE /api/metrics/:id` - 删除指标
- `POST /api/metrics/:id/execute` - 执行指标查询
- `POST /api/metrics/:id/comparison` - 获取同环比对比
- `GET /api/metrics/templates` - 获取指标模板

### 看板
- `GET /api/dashboards` - 获取看板列表
- `POST /api/dashboards` - 创建看板
- `GET /api/dashboards/:id` - 获取看板详情
- `PUT /api/dashboards/:id` - 更新看板
- `DELETE /api/dashboards/:id` - 删除看板
- `POST /api/dashboards/:id/widgets` - 添加组件
- `PUT /api/dashboards/:id/layout` - 批量更新布局
- `GET /api/dashboards/:id/export` - 导出看板
- `POST /api/dashboards/import` - 导入看板

### 告警
- `GET /api/alerts/rules` - 获取告警规则
- `POST /api/alerts/rules` - 创建告警规则
- `PUT /api/alerts/rules/:id` - 更新告警规则
- `DELETE /api/alerts/rules/:id` - 删除告警规则
- `GET /api/alerts/records` - 获取告警记录
- `POST /api/alerts/records/:id/acknowledge` - 确认告警

## 预置业务线

系统预置三条业务线，可通过管理后台调整：

1. **电商** - 订单、GMV、转化率、复购率
2. **广告** - 曝光、点击、消耗、ROI
3. **会员** - 新增、活跃、留存、ARPU

## 预置指标模板

系统提供常用指标模板，开箱即用：

- 电商：日订单量、GMV趋势、用户转化率
- 广告：消耗趋势、点击率、ROI分析
- 会员：新增会员、活跃用户、留存率

## 核心特性

### 🔒 数据安全
- 多租户数据隔离
- 细粒度权限控制
- 密码加密存储
- 完整操作审计

### ⚡ 高性能
- 连接池复用
- 查询熔断机制
- Redis 缓存
- WebSocket 增量更新
- 推送节流防抖

### 📊 数据可视化
- 6种专业图表组件
- 拖拽式自由布局
- 响应式设计
- 多维度下钻分析

### 🔔 智能告警
- 多条件组合告警
- 静默期防骚扰
- 升级策略逐级上报
- 多渠道通知

## 开发说明

### 后端开发

```bash
cd backend

# 代码检查
npm run lint

# 构建
npm run build

# 生产运行
npm run start:prod
```

### 前端开发

```bash
cd frontend

# 类型检查
tsc --noEmit

# 构建
npm run build

# 预览构建
npm run preview
```

### 数据库迁移

```bash
cd backend

# 生成 Prisma Client
npx prisma generate

# 推送 Schema 到数据库
npx prisma db push

# 打开数据库管理界面
npx prisma studio
```

## 测试账号

首次启动后可通过注册接口创建用户，或使用以下测试账号：

```
邮箱: admin@example.com
密码: admin123
角色: SUPER_ADMIN
```

*注：测试账号需自行通过注册接口创建。*

## License

MIT
