<div align="center">

# MarkNote

**一个现代化、轻量级的跨平台 Markdown 笔记应用**

[![Rust](https://img.shields.io/badge/rust-1.75+-orange.svg)](https://www.rust-lang.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/marknote-app/marknote/actions/workflows/ci.yml/badge.svg)](https://github.com/marknote-app/marknote/actions)

[功能特性](#功能特性) • [快速开始](#快速开始) • [构建指南](#构建指南) • [贡献指南](#贡献指南) • [路线图](#路线图)

</div>

## ✨ 功能特性

### 📝 核心编辑
- **WYSIWYG 编辑器** - 独创的所见即所得 Markdown 编辑体验
- **语法高亮** - 基于 Tree-sitter 的多语言代码高亮
- **实时预览** - 分屏预览，编辑和渲染效果实时同步
- **数学公式** - 支持 LaTeX 数学公式渲染

### 🔗 知识网络
- **双向链接** - 使用 `[[维基链接]]` 语法创建笔记关联
- **反向链接** - 自动发现并展示引用当前笔记的其他笔记
- **知识图谱** - 力导向布局可视化展示笔记网络关系
- **全文搜索** - 基于 Tantivy 的毫秒级全文检索

### 🎨 用户体验
- **多主题支持** - 内置亮色、暗色、高对比三套主题
- **自定义主题** - 支持 TOML 格式自定义主题配置
- **响应式布局** - 自适应窗口大小，支持面板拖拽调整
- **国际化** - 支持中/英双语界面

### 📤 导出与分享
- **多格式导出** - 支持 HTML、PDF、DOCX 格式导出
- **富文本复制** - 一键复制带格式内容到其他应用
- **幻灯片模式** - 使用 `##` 分隔符创建演示文稿

### 🔄 版本控制
- **Git 集成** - 自动保存历史版本
- **差异对比** - 可视化展示版本间的内容差异
- **一键回滚** - 轻松恢复到任意历史版本

## 🚀 快速开始

### 下载安装

从 [Releases](https://github.com/marknote-app/marknote/releases) 页面下载对应平台的安装包：

| 平台 | 格式 | 下载 |
|------|------|------|
| macOS | `.dmg` (Universal) | [下载]() |
| Windows | `.zip` (Portable) | [下载]() |
| Linux | `.AppImage` / `.deb` / `.rpm` | [下载]() |

### 首次使用

1. 启动 MarkNote
2. 完成初始化向导：
   - 选择界面语言
   - 选择主题偏好
   - 设置笔记存储目录
3. 开始创建你的第一篇笔记！

## 🛠️ 构建指南

### 前置要求

- Rust 1.75+
- 系统依赖：

**macOS**
```bash
# 无需额外依赖
```

**Ubuntu/Debian**
```bash
sudo apt-get install -y libwebkit2gtk-4.0-dev libgtk-3-dev \
    libayatana-appindicator3-dev librsvg2-dev
```

**Windows**
```powershell
# 使用 Visual Studio Build Tools
# 无需额外依赖
```

### 构建

```bash
# 克隆仓库
git clone https://github.com/marknote-app/marknote.git
cd marknote

# 开发构建
cargo build

# 发布构建
cargo build --release

# 运行
cargo run
```

### 运行测试

```bash
# 单元测试
cargo test --lib

# 所有测试
cargo test --all

# 代码检查
cargo clippy --all -- -D warnings

# 格式检查
cargo fmt --all -- --check
```

## 📁 项目结构

```
DF1-54/
├── src/
│   ├── app.rs              # 主应用入口
│   ├── editor/             # 编辑器模块
│   │   ├── core.rs         # 编辑器核心
│   │   ├── wysiwyg.rs      # WYSIWYG 实现
│   │   ├── view_modes.rs   # 视图模式
│   │   └── slideshow.rs    # 幻灯片模式
│   ├── parser/             # Markdown 解析与渲染
│   │   ├── parse_stage.rs  # 解析阶段
│   │   ├── ir_stage.rs     # IR 转换阶段
│   │   ├── layout_stage.rs # 布局计算阶段
│   │   ├── render_stage.rs # 渲染阶段
│   │   └── treesitter.rs   # 语法高亮
│   ├── theme/              # 主题系统
│   │   ├── theme.rs        # Theme 结构体
│   │   ├── light.toml      # 亮色主题
│   │   ├── dark.toml       # 暗色主题
│   │   └── high_contrast.toml # 高对比主题
│   ├── export/             # 导出模块
│   │   ├── trait_.rs       # ExportFormat trait
│   │   ├── html.rs         # HTML 导出
│   │   ├── pdf.rs          # PDF 导出
│   │   └── docx.rs         # DOCX 导出
│   ├── file_manager/       # 文件管理
│   ├── links/              # 双向链接与知识图谱
│   ├── version/            # 版本控制
│   ├── updater/            # 自动更新
│   ├── setup/              # 初始化向导
│   ├── config/             # 配置管理
│   └── lib.rs
├── .github/
│   └── workflows/          # GitHub Actions
│       ├── ci.yml          # CI 流水线
│       ├── release.yml     # 发布构建
│       ├── nightly.yml     # 每日构建
│       └── deploy-docs.yml # 文档部署
├── docs/                   # 官网文档
└── Cargo.toml
```

## 🤝 贡献指南

我们欢迎任何形式的贡献！

### 开始贡献

1. Fork 本仓库
2. 创建你的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交你的更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启一个 Pull Request

### 代码规范

- 使用 `cargo fmt` 格式化代码
- 使用 `cargo clippy` 检查代码
- 遵循 Rust 社区最佳实践
- 添加必要的文档注释

### 报告问题

使用 [Issues](https://github.com/marknote-app/marknote/issues) 页面报告 Bug 或提出功能建议。

## 🗺️ 路线图

### ✅ v0.1.0 - 基础版本
- [x] 核心编辑功能
- [x] Markdown 渲染
- [x] 基础导出
- [x] Git 版本控制

### ✅ v0.2.0 - 知识网络
- [x] 双向链接
- [x] 知识图谱
- [x] 全文搜索
- [x] 幻灯片模式

### 🚧 v0.3.0 - 发布准备 (当前)
- [x] CI/CD 流水线
- [x] 跨平台打包
- [x] 自动更新
- [x] 初始化向导

### 📋 v0.4.0 - 协作增强
- [ ] 实时协作
- [ ] 评论功能
- [ ] 模板系统
- [ ] 标签管理

### 📋 v1.0.0 - 正式发布
- [ ] 插件系统
- [ ] 云同步
- [ ] 移动端支持
- [ ] API 接口

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 💝 支持

如果你觉得这个项目有帮助，请考虑：

- ⭐ 在 GitHub 上给个 Star
- 🐛 报告遇到的 Bug
- 📝 提交 Pull Request
- 💬 分享给你的朋友

---

<div align="center">

用 Rust + egui 构建 ❤️

</div>
