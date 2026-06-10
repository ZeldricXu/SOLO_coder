# gitflow-cli

> Git工作流自动化CLI工具 - 让团队开发更高效

一个用 Rust 编写的 Git 工作流自动化工具，提供分支管理、提交规范检查、PR 工作流、变更日志生成、仓库健康扫描等功能。支持与 JIRA、GitHub、GitLab、Bitbucket 集成。

## 功能特性

- **分支管家** (`gitflow branch`): 创建、列出、清理、同步分支，自动从 JIRA issue 创建规范分支名
- **提交规范检查** (`gitflow commit`): Conventional Commits 格式验证，自定义团队模板，pre-commit hook 集成
- **PR 工作流** (`gitflow pr`): 一键创建 PR，自动填充模板，关联 JIRA issue，指定 reviewer
- **变更日志** (`gitflow changelog`): 自动生成 Keep a Changelog 格式的 CHANGELOG.md
- **健康扫描** (`gitflow health`): 检测大文件、过期分支、未追踪依赖、CI 失败率趋势
- **配置管理** (`gitflow config`): 全局和项目级两层 TOML 配置

## 安装

### Homebrew

```bash
brew tap your-org/homebrew-tap
brew install gitflow
```

### Install Script

```bash
curl -fsSL https://raw.githubusercontent.com/your-org/gitflow-cli/main/install.sh | bash
```

### 指定版本安装

```bash
curl -fsSL https://raw.githubusercontent.com/your-org/gitflow-cli/main/install.sh | bash -s -- v0.1.0
```

### crates.io

```bash
cargo install gitflow-cli
```

### 手动下载

从 [Releases](https://github.com/your-org/gitflow-cli/releases) 页面下载对应平台的二进制包。

支持平台：
- Linux x86_64 / ARM64 (.tar.gz, .deb, .rpm)
- macOS x86_64 / ARM64 (.tar.gz)
- Windows x86_64 (.zip)

## 快速开始

```bash
# 查看帮助
gitflow --help

# 初始化配置
gitflow config init

# 从 JIRA issue 创建分支
gitflow branch create --issue PROJ-1234

# 交互式创建规范提交
gitflow commit create

# 一键创建 PR
gitflow pr create

# 生成变更日志
gitflow changelog generate

# 扫描仓库健康
gitflow health scan
```

## 配置

配置文件优先级（从高到低）：
1. 命令行参数 `--config` 指定的文件
2. 项目级配置：`.gitflow.toml`
3. 全局配置：`~/.config/gitflow/config.toml`

配置示例请参考 [docs/config.md](docs/config.md)。

## 开发

```bash
# 构建
cargo build --release

# 运行测试
cargo test

# 代码检查
cargo clippy --all-targets --all-features -- -D warnings
cargo fmt --all -- --check
```

## 发布流程

1. 更新 `Cargo.toml` 中的版本号
2. 创建 Git tag: `git tag v0.1.0`
3. 推送 tag: `git push origin v0.1.0`
4. GitHub Actions 自动触发发布流程，包括：
   - 跨平台编译
   - 生成 .deb 和 .rpm 包
   - 发布到 GitHub Releases
   - 自动更新 Homebrew tap
   - 发布到 crates.io

## License

MIT © Your Team
