# 会议室预约与会议协作系统

一套完整的会议室预约 + 会议协作系统，解决会议室预订冲突、会议纪要散落、待办跟进难等问题。

## 技术栈

- **后端**: Go + Gin + GORM
- **前端**: React 18 + TypeScript + Vite + Ant Design
- **数据库**: PostgreSQL
- **图表**: ECharts

## 功能模块

### 1. 会议室管理
- 会议室新增/编辑/下架
- 属性：楼层、容量、设备、位置
- 特殊会议室需审批预订
- 每个会议室独立日历页面，支持天/周/月三种视图切换

### 2. 预约引擎
- 拖拽选时间段预订
- 实时时间冲突检测
- 支持周期性会议（每天/每周/每两周/每月）
- 预订后自动发通知
- 会前提醒（可配置）
- 门口屏显示当前会议信息和下一场倒计时

### 3. 会议协作
- 会议创建时自动生成协作文档
- 内置议程模板
- 会前异步填写议题
- 会中实时编辑纪要，支持 Markdown
- 待办事项识别与分配
- 24小时后纪要归档只读并生成摘要

### 4. 签到与统计
- 动态二维码签到（防代签）
- 统计面板：
  - 会议室使用率
  - 人均会议时长
  - 出席率
  - 最忙时段热力图
  - 会议效率评估（预定vs实际时长）

### 5. 消息通知
- 统一通知中心
- 四种通道：企业微信/钉钉/飞书/邮件
- 通知类型：
  - 预订确认
  - 临近提醒
  - 纪要发布
  - 待办分配
- 用户自定义通知偏好

## 快速开始

### 环境要求

- Go 1.21+
- Node.js 18+
- PostgreSQL 12+

### 一键启动（推荐）

```bash
cd DF1-101
chmod +x start.sh
./start.sh
```

### 手动启动

#### 1. 数据库初始化

```bash
createdb meeting_system
psql -U postgres -d meeting_system -f backend/migrations/001_init_schema.sql
```

#### 2. 启动后端

```bash
cd backend
cp .env.example .env
go mod download
go run main.go
```

后端服务运行在 http://localhost:8080

#### 3. 启动前端

```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```

前端服务运行在 http://localhost:3000

### 测试账号

```
邮箱: admin@company.com
密码: 任意密码
```

## 项目结构

```
DF1-101/
├── backend/                 # Go 后端
│   ├── cmd/
│   │   └── api/            # API 服务器入口
│   ├── internal/
│   │   ├── handler/        # 处理器层
│   │   ├── middleware/     # 中间件
│   │   ├── model/          # 数据模型
│   │   └── config/         # 配置
│   ├── pkg/
│   │   ├── database/       # 数据库
│   │   ├── notification/   # 通知服务
│   │   └── utils/          # 工具函数
│   ├── migrations/         # 数据库迁移
│   ├── main.go
│   └── go.mod
├── frontend/               # React 前端
│   ├── src/
│   │   ├── pages/          # 页面组件
│   │   ├── components/     # 通用组件
│   │   ├── api/            # API 封装
│   │   ├── store/          # 状态管理
│   │   ├── types/          # TypeScript 类型
│   │   ├── styles/         # 全局样式
│   │   └── router/         # 路由
│   ├── index.html
│   └── package.json
└── start.sh                # 一键启动脚本
```

## API 接口

### 认证
- `POST /api/auth/login` - 登录
- `GET /api/auth/me` - 获取当前用户

### 会议室
- `GET /api/rooms` - 获取会议室列表
- `GET /api/rooms/:id` - 获取会议室详情
- `POST /api/rooms` - 创建会议室（管理员）
- `PUT /api/rooms/:id` - 更新会议室（管理员）
- `DELETE /api/rooms/:id` - 删除会议室（管理员）
- `GET /api/rooms/:id/bookings` - 获取会议室预约
- `GET /api/rooms/:id/calendar` - 日历数据
- `GET /api/rooms/:id/display` - 门口屏数据

### 预约
- `GET /api/bookings` - 获取预约列表
- `GET /api/bookings/my` - 我的预约
- `GET /api/bookings/:id` - 获取预约详情
- `POST /api/bookings` - 创建预约
- `PUT /api/bookings/:id` - 更新预约
- `DELETE /api/bookings/:id` - 取消预约
- `POST /api/bookings/check-conflict` - 检查时间冲突
- `POST /api/bookings/:id/approve` - 审批通过
- `POST /api/bookings/:id/reject` - 审批拒绝

### 会议文档
- `GET /api/meeting-docs/booking/:bookingId` - 获取会议文档
- `PUT /api/meeting-docs/:id` - 更新会议文档
- `POST /api/meeting-docs/:id/archive` - 归档纪要

### 待办事项
- `GET /api/meeting-docs/:id/todos` - 获取待办列表
- `POST /api/meeting-docs/:id/todos` - 创建待办
- `GET /api/todos/my` - 我的待办
- `PUT /api/todos/:id` - 更新待办
- `DELETE /api/todos/:id` - 删除待办

### 签到
- `GET /api/check-in/qr/:bookingId` - 获取签到二维码
- `POST /api/check-in` - 签到
- `GET /api/check-in/booking/:bookingId` - 获取签到列表

### 通知
- `GET /api/notifications` - 获取通知列表
- `POST /api/notifications/read/:id` - 标记已读
- `POST /api/notifications/read-all` - 全部已读
- `GET /api/notifications/preferences` - 获取通知偏好
- `PUT /api/notifications/preferences` - 更新通知偏好

### 统计
- `GET /api/stats/room-usage` - 会议室使用率
- `GET /api/stats/meeting-hours` - 会议时长统计
- `GET /api/stats/attendance` - 出席率
- `GET /api/stats/heatmap` - 热力图
- `GET /api/stats/efficiency` - 效率分析

### 用户
- `GET /api/users` - 获取用户列表
- `GET /api/users/:id` - 获取用户详情

## 特色功能说明

### 周期性会议
支持每天、每周、每两周、每月重复。创建时自动生成未来一段时间内的所有预约，遇到冲突自动跳过。

### 特殊会议室审批
标记为「需审批」的会议室，预订后状态为「待审批」，需要审批人通过后才正式生效。

### 会议纪要归档
归档后文档变为只读，同时自动识别待办事项并分配给相关人员。

### 动态二维码签到
签到二维码每5分钟刷新一次，防止截图代签。

### 门口屏
独立页面 `/display/:roomId`，可部署到平板设备放在会议室门口，实时显示会议信息。

## 开发说明

### 数据库表

- `users` - 用户表
- `rooms` - 会议室表
- `bookings` - 预约表
- `meeting_docs` - 会议文档表
- `todos` - 待办事项表
- `check_ins` - 签到记录表
- `notifications` - 通知表
- `notification_preferences` - 通知偏好表
- `qr_code_tokens` - 二维码令牌表

### 通知通道扩展

在 `backend/pkg/notification/notification.go` 中实现对应通道的发送函数即可。

## License

MIT
