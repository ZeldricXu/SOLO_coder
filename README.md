# DF1-79 协作绘图引擎

基于 Rust + WebAssembly 构建的高性能实时协作绘图引擎，支持多人协同编辑、CRDT 数据同步、矢量图形渲染等核心功能。

## 项目结构

```
DF1-79/
├── crates/
│   ├── geometry/          # 几何计算库（路径、形状、变换、布尔运算）
│   ├── crdt/              # 无冲突复制数据类型（协作同步核心）
│   ├── renderer/          # 图形渲染引擎
│   ├── stroke-engine/     # 笔画引擎（手绘笔画处理）
│   ├── resource-manager/  # 资源管理器（图片、字体等资源加载）
│   └── permission-history/ # 权限与历史记录管理
├── pkg/                   # WebAssembly 构建输出目录
├── scripts/               # 构建与开发脚本
├── Cargo.toml             # Rust Workspace 配置
├── Makefile               # 常用命令封装
└── docker-compose.yml     # Docker 编排配置
```

## 技术栈

- **Rust 2021** - 核心语言
- **WebAssembly (wasm32-unknown-unknown)** - 浏览器端运行时
- **wasm-bindgen** - Rust 与 JavaScript 互操作
- **wasm-pack** - WebAssembly 构建工具
- **lyon** - 2D 矢量图形 tessellation
- **serde** - 序列化/反序列化
- **uuid** - 唯一标识生成

## 快速开始

### 环境要求

- Rust 1.75+ (建议使用 rustup 安装)
- wasm-pack 0.12+
- Node.js 18+ (前端开发)
- Docker & Docker Compose (可选，容器化部署)

### 安装依赖

```bash
# 安装 Rust 工具链
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# 添加 WebAssembly 目标
rustup target add wasm32-unknown-unknown

# 安装 wasm-pack
curl https://rustwasm.github.io/wasm-pack/installer/init.sh -sSf | sh
# 或使用 cargo 安装
cargo install wasm-pack
```

### 脚本执行权限

首次使用前，需为构建脚本添加执行权限：

```bash
chmod +x scripts/*.sh
```

### 构建

```bash
# 构建所有 WebAssembly 模块（输出到 pkg/ 目录）
make build-wasm

# 一键构建所有组件（WASM + 前端 + 后端）
make build-all

# 使用脚本直接构建
./scripts/build-wasm.sh
./scripts/build-all.sh
```

### 开发模式

```bash
# 启动开发模式（自动监听文件变化并重新构建）
make dev
# 或
./scripts/dev.sh
```

### 清理构建产物

```bash
make clean
```

## Docker 部署

```bash
# 构建并启动所有服务
docker-compose up -d --build

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Web 前端 | 8080 | 绘图应用前端界面 |
| API 服务 | 3000 | 后端协作服务 API |

## Crate 说明

### geometry
提供完整的 2D 几何运算能力：
- 基础形状（点、线、矩形、圆、贝塞尔曲线、路径）
- 仿射变换（平移、旋转、缩放、倾斜）
- 布尔运算（并集、交集、差集、异或）
- 边界框计算与碰撞检测
- 样式管理（填充、描边、透明度）
- 图形吸附（对齐辅助）

### crdt
实现基于 CRDT 的协作数据同步：
- 操作转换与冲突解决
- 增量同步与状态合并
- 离线编辑支持

### renderer
负责图形渲染：
- Canvas 渲染后端
- WebGL 渲染后端（可选）
- 图层管理
- 视口变换与缩放

### stroke-engine
处理手绘笔画：
- 笔画数据采集与压缩
- 笔画平滑与简化
- 笔压与速度感知

### resource-manager
资源加载与管理：
- 图片资源加载与缓存
- 字体资源管理
- 资源预加载策略

### permission-history
权限与历史：
- 细粒度权限控制
- 操作历史记录
- 撤销/重做支持

## 开发指南

### 添加新 Crate

1. 在 `crates/` 目录下创建新 crate
2. 在根 `Cargo.toml` 的 `workspace.members` 中添加路径
3. 如需要编译为 WASM，设置 `crate-type = ["cdylib", "rlib"]`

### 代码规范

```bash
# 格式化代码
cargo fmt --all

# 运行 Clippy 检查
cargo clippy --all-targets --all-features -- -D warnings

# 运行测试
cargo test --all
```

## 许可证

MIT License
