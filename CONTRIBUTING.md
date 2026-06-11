# KB Note 贡献指南

> 本地优先的个人知识库桌面应用 —— Markdown 编辑器 · 双向链接图谱 · 全文检索 · 标签文件夹 · 文件监听 · 多格式导出 · 每日笔记 · 插件系统

---

## 0. 代码规范速览

| 项目 | 约定 |
|------|------|
| 语言 | Go 1.25 |
| GUI 框架 | Fyne v2 |
| 存储 | SQLite (mattn/go-sqlite3, CGO) |
| 配置格式 | TOML（用户级）+ struct tag（内部） |
| 包风格 | `internal/` 私有 / `pkg/` 可复用 / `cmd/` 入口 |
| 测试 | Go testing + 自定义 `testutil` 断言，禁用 testify |
| 命名 | 中文文件禁止，标识符英文驼峰 |
| 提交信息 | [Conventional Commits](https://www.conventionalcommits.org) |
| 错误处理 | `%w` 包装错误链，禁止 `_` 吞掉非显而易见的错误 |
| 并发 | `go func` 必须有 WaitGroup / Context 生命周期 |

---

## 1. 环境准备

### 1.1 必备工具

| 工具 | 最低版本 | 用途 | 安装命令 |
|------|---------|------|---------|
| **Go** | 1.25 | 编译器 | <https://go.dev/dl/> |
| **Task** | 3.x | 构建编排（替代 make） | `go install github.com/go-task/task/v3/cmd/task@latest` |
| **Git** | 2.3+ | 版本管理 | 系统包管理器 |
| **GCC / Clang** | 任意 | CGO（SQLite 需要） | macOS: Xcode Command Line Tools · Linux: build-essential · Windows: MinGW-w64 |

```bash
# macOS 一条搞定
xcode-select --install
brew install go go-task git

# Ubuntu/Debian
sudo apt update && sudo apt install -y golang-1.25 build-essential git
go install github.com/go-task/task/v3/cmd/task@latest

# Windows (chocolatey)
choco install golang git mingw
go install github.com/go-task/task/v3/cmd/task@latest
```

### 1.2 可选工具（按需）

| 工具 | 用途 |
|------|------|
| GoReleaser | 多平台打包发布 |
| golangci-lint | 聚合静态分析 |
| misspell | 拼写检查 |
| Xvfb | Linux 无头 GUI 测试 |
| Ollama | 本地语义向量嵌入服务 |
| Dive / Docker | 镜像体积分析 |

```bash
# 一条命令安装全部 Go 工具
go install github.com/goreleaser/goreleaser/v2@latest
go install github.com/client9/misspell/cmd/misspell@latest
go install golang.org/x/tools/go/analysis/passes/shadow/cmd/shadow@latest
curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/master/install.sh | sh -s -- -b $(go env GOPATH)/bin v1.61.0
```

### 1.3 克隆并验证

```bash
git clone git@github.com:solocoder/knowledgebase.git
cd knowledgebase

task info        # 打印版本信息
task deps        # go mod tidy / download / verify
task build       # 编译当前平台二进制

# 验证（首次运行会自动在 ~/.config/kbnote/ 生成默认配置）
./build/kbnote --version
# 期望输出类似：
#   KB Note v2.0.0 (commit a1b2c3d, built 2025-06-11T00:00:00Z)
```

---

## 2. 仓库结构

```
DF1-76/
├── cmd/
│   └── app/main.go               # 程序入口，参数解析
├── internal/                      # 私有包（不可被外部 import）
│   ├── config/                    #   TOML 配置管理 + 版本变量（ldflags 注入）
│   ├── db/                        #   SQLite 封装 + 迁移
│   ├── models/                    #   数据模型 Note / Link / Tag / Folder
│   ├── markdown/                  #   Markdown 解析 / 渲染 / Wiki 链接
│   ├── editor/                    #   编辑器核心（Wysiwyg / Source / Undo / Autocomplete）
│   ├── search/                    #   BM25 + 磁盘倒排索引 + 向量混合搜索
│   ├── graph/                     #   双向图谱（布局算法 / 社区发现 / 交互）
│   ├── tags/                      #   标签 & 文件夹管理
│   ├── fsnotify/                  #   文件系统监听（WatchSession + 双快照）
│   ├── export/                    #   导出管线（ExportContext + 多格式 Renderer）
│   ├── dailynote/                 #   每日笔记 + EJS 模板引擎 (goja)
│   ├── plugin/                    #   插件沙箱管理器
│   ├── server/                    #   本地 HTTP/WebSocket API 服务
│   └── testutil/                  #   测试夹具 & 断言工具（替代 testify）
├── pkg/                           # 可复用的公共库
│   ├── segment/                   #   纯 Go CJK 分词器
│   └── utils/                     #   杂项工具
├── tests/
│   └── integration/               #   端到端集成测试（真实 SQLite + 临时文件系统）
├── web/                           # 前端静态资源（Fyne WebView 用）
├── packaging/                     # 打包资源（.desktop / AppIcon / Wix 片段）
├── .github/workflows/ci.yml       # GitHub Actions（lint/test/release）
├── .goreleaser.yml                # GoReleaser 三平台打包配置
├── Taskfile.yml                   # Task 构建任务
├── config.toml.template           # 用户配置模板
├── CONTRIBUTING.md                # 👉 本文件
├── go.mod / go.sum
└── README.md
```

---

## 3. 日常开发流

### 3.1 新建功能分支

```bash
git checkout -b feat/<你的功能名>        # 新功能
# 或
git checkout -b fix/<问题简述>           # 修复 Bug
# 或
git checkout -b refactor/<模块>-<目标>   # 重构
```

### 3.2 常用 Task 命令

```bash
task                       # 列出所有可用任务
task info                 # 打印构建信息（版本/commit/日期）
task build                # 编译二进制到 ./build/kbnote（自动注入版本）
task run                  # 等价于 ./build/kbnote [参数...]
task run -- --help        # 传参给 kbnote
task debug                # 编译带 -gcflags="-N -l" + race 的 debug 版
task fmt                  # gofmt + goimports 格式化

task test                 # 跑所有测试 + 覆盖率（门槛 75%）
task test:unit            # 只跑 internal/ 单元测试
task test:integration     # 只跑 tests/integration 集成测试
task test:race            # 竞态检测器（Linux/macOS 强烈建议跑）
task bench                # 基准测试（生成 cpu.pprof / mem.pprof）

task lint                 # go vet + gofmt + misspell + shadow + golangci-lint
task lint:fmt             # 只跑 gofmt 检查

task release:snapshot     # goreleaser 本地快照（产物在 dist/，不上传）
task release:dry          # 校验 goreleaser 配置
task release              # 真实发布（需要 GITHUB_TOKEN + tag）

task ci                   # 等价于 clean → lint → test → test:race
```

### 3.3 本地知识库开发测试

首次运行会在默认目录自动生成：
- `~/.config/kbnote/config.toml` — 用户配置
- `~/.local/share/kbnote/templates/` — 4 个示例 EJS 模板
- `~/KnowledgeVault/` — 空的知识库根目录（含 Daily/ 子目录）

指定自定义知识库位置启动：

```bash
# 独立的临时知识库，别污染自己的真实数据
export KB_TEST_VAULT="/tmp/kb-dev-vault"
mkdir -p "$KB_TEST_VAULT"
task run -- --vault "$KB_TEST_VAULT"
```

或用自定义配置文件：

```bash
cp config.toml.template /tmp/kb-dev.toml
task run -- --config /tmp/kb-dev.toml
```

### 3.4 提交信息规范（Conventional Commits）

```
<type>(<scope>): <subject>

[body]
[footer]
```

**type 必填**：`feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `ci` / `chore` / `breaking`

示例：

```
feat(search): 磁盘倒排索引 LRU 热点缓存上限改为可调

新增 search.hot_cache_size 配置项（默认 10000），
在大仓库场景下显著降低 GC 压力。

Fixes: #127
```

---

## 4. 测试编写指南

### 4.1 单元测试

- 放在对应包内，命名 `<module>_test.go`
- 只测试本包**导出接口**（黑盒，包内加 `_test` 后缀）
- 优先使用 `internal/testutil` 的夹具与断言：

```go
func TestDiskIndex_AddPosting(t *testing.T) {
    tempDir, cleanup := testutil.TempDir(t, "diskindex-")
    defer cleanup()

    cfg := testutil.NewTestConfig(tempDir)
    idx, err := search.NewDiskInvertedIndex(cfg.Search.IndexPath)
    testutil.AssertNoError(t, err, "NewDiskInvertedIndex")

    err = idx.IndexNote(1, "标题", "这是一段 中文 测试内容")
    testutil.AssertNoError(t, err, "IndexNote")

    postings, err := idx.GetPostings("测试")
    testutil.AssertNoError(t, err, "GetPostings")
    testutil.AssertEqual(t, 1, len(postings), "测试这个词应有1篇命中")
}
```

**断言工具**：`testutil.AssertEqual` / `AssertNoError` / `AssertTrue` / `AssertNil` / `AssertNotNil` / `AssertContains` 等。

### 4.2 集成测试

- 放在 `tests/integration/` 下
- 允许创建真实 SQLite / 临时文件系统
- 必须有**步骤化**的输出（参考 `pipeline_test.go`）

### 4.3 覆盖率门槛

总覆盖率 **≥ 75%**（`task test` 会自动校验）。低于门槛 CI 会失败。

Tips：
- 构造 `error 路径` 用例：磁盘满、目录被删、配置非法值、并发竞争
- `search` / `export` / `fsnotify` / `graph` 这四个模块覆盖率最容易被拉高

---

## 5. 发布流程

### 5.1 打 tag 并推送

```bash
git checkout main
git pull origin main

# 本地完整过一遍 CI 等价流程
task ci

# 自动升级 patch 版本
task tag:patch            # 手动确认后会 push tag

# 或手动打 tag（推荐先 dry-run）
git tag -a v2.0.1 -m "Release v2.0.1"
git push origin v2.0.1
```

### 5.2 CI 自动执行

推送 tag `v*` 后 GitHub Actions 触发 `.github/workflows/ci.yml` 的 `release` job：

1. **Lint** — golangci-lint + go vet + misspell
2. **Test** — Windows / macOS (amd64+arm64) / Linux 四矩阵并行
   - Linux：覆盖率门槛 75%，Xvfb GUI 测试
3. **Race Detector** — 竞态检测
4. **Release** — macOS-14 runner（macOS 签名所需）执行 GoReleaser：
   - **二进制**：linux/darwin/windows × amd64/arm64，`-ldflags` 注入版本/commit/日期
   - **macOS**：`.app bundle` + Developer ID 签名 + Notarize
   - **Windows**：`.msi`（Wix Toolset，FeatureTree + 桌面快捷方式）
   - **Linux**：`.deb` / `.rpm` / `.apk` / `.pkg.tar.zst` + AppImage
   - **Docker**：`ghcr.io/solocoder/kbnote:VERSION-server`
   - **包管理器**：Homebrew Tap / Scoop Bucket
   - 自动上传到 GitHub Release + 生成 checksums

### 5.3 手动发版（CI 挂了时）

```bash
export GITHUB_TOKEN="ghp_xxx"
# 先 dry-run
task release:dry
# 确认没问题再发布
task release
```

---

## 6. 版本注入原理

编译时通过 `-ldflags -X` 把信息注入到 `internal/config` 包的三个未导出的字符串变量：

```bash
go build -ldflags "\
  -X github.com/solocoder/knowledgebase/internal/config.Version=v2.0.1 \
  -X github.com/solocoder/knowledgebase/internal/config.Commit=a1b2c3d \
  -X github.com/solocoder/knowledgebase/internal/config.Date=2025-06-11T00:00:00Z \
  -s -w" ./cmd/app
```

然后运行时：

```go
fmt.Println(config.BuildInfo())
// KB Note v2.0.1 (commit a1b2c3d, built 2025-06-11T00:00:00Z)
```

---

## 7. 常见问题

### Q1: `go build` 报错 `C source files not allowed when not using cgo`

`CGO_ENABLED=0`，`go-sqlite3` 需要 CGO。检查环境变量：

```bash
export CGO_ENABLED=1
# Windows 还要指定 CC：
export CC=x86_64-w64-mingw32-gcc
```

### Q2: Linux GUI 测试失败 `no display`

启动 Xvfb：

```bash
Xvfb :99 -screen 0 1920x1080x24 &
export DISPLAY=:99.0 QT_QPA_PLATFORM=offscreen
```

### Q3: 测试跑一半崩，报错 `disk full` 或 `too many open files`

- macOS: `ulimit -n 4096`
- Linux: `sysctl -w fs.file-max=65535` 或 `ulimit -n 65535`
- 或临时设置 `TMPDIR` 到空间大的分区：`export TMPDIR=/mnt/big/tmp`

### Q4: `task test` 覆盖率不够

生成 HTML 报告自己看哪里没覆盖：

```bash
task test
# 会生成 coverage/coverage.html
open coverage/coverage.html
```

### Q5: 怎么贡献代码？

1. Fork 本仓库
2. 新建分支（`feat/xxx` 或 `fix/xxx`）
3. 确保 `task ci` 全部通过
4. 提 PR，标题符合 Conventional Commits
5. 一个 PR 做一件事，避免上千行的巨型 PR

---

## 8. 关键模块阅读顺序（新贡献者建议）

1. `internal/config/config.go` — 配置中心，先搞懂数据结构
2. `internal/models/models.go` — 核心数据模型
3. `internal/db/database.go` — SQLite 封装
4. `internal/markdown/markdown.go` — Markdown 渲染管线
5. `internal/search/index.go` + `disk_index.go` — 全文检索
6. `internal/export/context.go` — 导出统一预处理
7. `internal/fsnotify/session.go` + `snapshot.go` — 文件监听抽象

---

有任何贡献相关问题，提 Issue 标 `question` / `good first issue`，维护者会在 48h 内回复。
