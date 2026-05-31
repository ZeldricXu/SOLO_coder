# 贡献指南

感谢您有兴趣为 StreamSQL 项目做贡献！我们欢迎所有形式的贡献，包括但不限于：

- 🐛 报告 Bug
- 💡 提出新特性建议
- 📝 改进文档
- 🛠️ 提交代码修复或新功能
- ⚡ 性能优化
- ✅ 编写测试用例

## 📋 行为准则

参与本项目的所有人都应遵守我们的行为准则：

- 尊重他人，使用友善和包容的语言
- 接受不同的观点和经验
- 优雅地接受建设性批评
- 关注对社区最有利的事情
- 对其他社区成员保持同理心

任何不可接受的行为都可以向项目维护者报告。

## 🚀 开始之前

### 环境要求

- Go 1.21+
- Git
- Docker & Docker Compose（推荐用于本地开发）
- Make 工具

### 开发环境搭建

1. **Fork 仓库**
   - 访问 https://github.com/streamsql/streamsql
   - 点击右上角的 "Fork" 按钮

2. **克隆 Fork 后的仓库**
   ```bash
   git clone https://github.com/your-username/streamsql.git
   cd streamsql
   ```

3. **添加上游仓库**
   ```bash
   git remote add upstream https://github.com/streamsql/streamsql.git
   ```

4. **安装依赖和工具**
   ```bash
   make install-tools
   make dependencies
   ```

5. **启动依赖服务**
   ```bash
   docker-compose up -d postgres redis
   ```

6. **运行测试验证环境**
   ```bash
   make test
   ```

## 🎯 如何贡献

### 报告 Bug

如果您发现了 Bug，请按照以下步骤操作：

1. **搜索现有 Issues**，确认这不是一个已经报告过的问题
2. **创建新 Issue**，使用 Bug 报告模板
3. **提供详细信息**：
   - 清晰的标题和描述
   - 复现步骤
   - 预期行为
   - 实际行为
   - 环境信息（操作系统、Go版本、StreamSQL版本等）
   - 相关日志或截图

### 提出新特性

如果您有好的想法或功能建议：

1. **搜索现有 Issues 和 Discussions**，确认这不是一个已经被讨论过的话题
2. **创建新 Issue**，使用 Feature Request 模板
3. **详细描述**：
   - 功能的用例和场景
   - 期望的行为
   - 可能的实现方案
   - 为什么这个功能对大多数用户有用

### 提交代码

#### 1. 创建分支

```bash
# 从 main 分支创建新分支
git checkout main
git pull upstream main
git checkout -b feature/your-feature-name

# 或者对于 Bug 修复
git checkout -b fix/issue-number-description
```

分支命名规范：
- `feature/xxx` - 新功能
- `fix/xxx` - Bug 修复
- `docs/xxx` - 文档改进
- `perf/xxx` - 性能优化
- `refactor/xxx` - 代码重构
- `test/xxx` - 测试相关

#### 2. 开发

- 遵循现有的代码风格和架构模式
- 确保代码通过所有现有测试
- 为新功能添加必要的测试用例
- 更新相关文档

#### 3. 运行质量检查

```bash
# 格式化代码
make fmt

# 运行代码检查
make lint

# 运行测试
make test

# 或者运行完整的质量门禁
make quality-gate
```

#### 4. 提交更改

```bash
# 查看更改
git status

# 添加更改的文件
git add .

# 提交
git commit -m "feat: add amazing feature"

# 推送到您的 Fork
git push origin feature/your-feature-name
```

#### 5. 创建 Pull Request

1. 访问您的 Fork 仓库
2. 点击 "Compare & pull request" 按钮
3. 填写 PR 模板：
   - 清晰的标题
   - 详细的描述（解决了什么问题、如何解决的）
   - 相关 Issue 引用（例如：`Fixes #123`）
   - 测试结果截图（如果适用）
4. 等待代码审查

### 提交信息规范

我们使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

**类型（Type）：**
- `feat` - 新功能
- `fix` - Bug 修复
- `docs` - 文档更新
- `style` - 代码格式（不影响代码运行的变动）
- `refactor` - 重构（既不是新增功能，也不是修改bug的代码变动）
- `perf` - 性能优化
- `test` - 增加测试
- `build` - 构建系统或外部依赖的变动
- `ci` - CI 配置文件和脚本的变动
- `chore` - 其他不修改源文件或测试文件的变动

**示例：**
```
feat(compression): add LZ4 compression algorithm

- Implement LZ4 compression algorithm for better performance
- Add configuration options for LZ4 specific parameters
- Update documentation for the new compression method

Fixes #456
```

## 🔍 代码审查流程

1. **自动检查**：PR 创建后会自动运行 CI 流水线
2. **维护者审查**：至少需要一名核心维护者的批准
3. **修改建议**：审查者可能会要求一些修改
4. **合并**：审查通过后，维护者会将 PR 合并到主分支

### 审查要点

- ✅ 代码是否符合项目的编码规范
- ✅ 是否有适当的测试覆盖
- ✅ 是否有必要的文档
- ✅ 实现是否高效且可维护
- ✅ 是否有安全隐患
- ✅ 是否与现有架构一致

## 📁 项目架构

在贡献代码之前，请理解项目的架构：

```
streamsql/
├── cmd/                    # 应用入口
├── internal/               # 内部代码（不对外导出）
│   ├── common/            # 公共基础模块
│   │   ├── config/        # 配置管理
│   │   ├── errors/        # 错误处理
│   │   ├── logger/        # 日志模块
│   │   └── models/        # 数据模型
│   ├── compression/       # 时序数据压缩模块
│   ├── quality/           # 数据质量校验模块
│   ├── lifecycle/         # 数据生命周期管理模块
│   ├── cdc/               # CDC增量捕获模块
│   ├── vectorindex/       # 向量索引构建模块
│   ├── lineage/           # 数据血缘解析模块
│   ├── streamparser/      # 流式查询解析模块
│   ├── metacrawler/       # 元数据采集爬虫模块
│   ├── gateway/           # 网关层
│   ├── engine/            # 核心引擎
│   └── api/               # API接口层
├── config/                # 配置文件
├── deploy/                # 部署配置
└── docs/                  # 文档
```

## 🧪 测试指南

### 编写测试

- 单元测试：`*_test.go` 文件，放在同一目录
- 集成测试：放在 `test/integration/` 目录
- 性能测试：放在 `test/benchmark/` 目录

### 测试要求

- 新功能必须包含单元测试
- 测试覆盖率应保持在 70% 以上
- 测试应该是确定性的（不依赖外部状态）
- 使用表驱动测试模式

### 运行测试

```bash
# 运行所有测试
make test

# 运行特定包的测试
go test -v ./internal/compression/...

# 生成覆盖率报告
make test-coverage

# 运行性能测试
go test -bench=. -benchmem ./...
```

## 📝 文档

- 代码注释：所有导出的类型和函数都应有文档注释
- README.md：重大更新需要更新主 README
- API 文档：新的 API 端点需要在 `docs/API.md` 中记录
- 部署文档：部署相关的更改需要更新 `docs/DEPLOYMENT.md`

## 🤔 需要帮助？

如果您在贡献过程中有任何问题：

1. 查看现有文档
2. 搜索已有的 Issues 和 Discussions
3. 创建新的 Discussion 提问
4. 在 PR 中 @ 维护者

## 🎉 成为贡献者

我们会在 [CONTRIBUTORS.md](CONTRIBUTORS.md) 中列出所有贡献者。首次贡献并被合并后，您的名字将被添加进去！

---

再次感谢您的贡献！🎉

**StreamSQL Team**
