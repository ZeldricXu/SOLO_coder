# DevKit CLI 贡献指南

首先感谢您花时间贡献代码！🎉

本文档是参与 DevKit CLI 贡献的指南。这些是指导原则，不是硬性规定。请发挥您的判断力，随时欢迎在 Pull Request 中提议修改本文档。

## 目录

- [行为准则](#行为准则)
- [项目结构](#项目结构)
- [架构概览](#架构概览)
- [如何贡献](#如何贡献)
  - [报告 Bug](#报告-bug)
  - [建议新功能](#建议新功能)
  - [新手友好任务](#新手友好任务-good-first-issue)
- [开发流程](#开发流程)
  - [前置条件](#前置条件)
  - [环境搭建](#环境搭建)
- [添加新的 Category](#添加新的-category)
- [代码规范](#代码规范)
  - [Ruff 规则集](#ruff-规则集)
  - [类型注解](#类型注解)
  - [文档字符串](#文档字符串)
  - [Git 提交信息](#git-提交信息)
- [Pull Request 流程](#pull-request-流程)
- [测试](#测试)

## 行为准则

本项目及其所有参与者均受我们的行为准则约束。参与即表示您同意遵守此准则。

## 项目结构

```
devkit/
├── __init__.py              # 包版本号
├── cli.py                   # 仅负责命令注册和参数解析
├── commands/                # 每个 category 一个文件
│   ├── __init__.py
│   ├── json_cmd.py          # JSON/YAML/TOML 处理
│   ├── crypto.py            # 加密与证书工具
│   ├── net.py               # 网络诊断
│   ├── regex_cmd.py         # 正则表达式调试器
│   ├── codec.py             # 编解码工具
│   ├── file_cmd.py          # 文件批量处理
│   ├── git_cmd.py           # Git 辅助工具
│   ├── time_cmd.py          # 时间/日期工具
│   ├── codegen.py           # 代码生成器
│   ├── db.py                # 数据库工具
│   ├── api.py               # API 测试工具
│   └── sysmon.py            # 系统监控
└── core/                    # 共享公共库
    ├── __init__.py
    ├── color.py             # 颜色常量和 cprint()
    ├── config.py            # 配置管理
    └── http_client.py       # HTTP 客户端封装
tests/
├── conftest.py
├── test_json_cmd.py
├── test_crypto.py
...
```

### 文件命名约定
- Category 命令文件：`<category>_cmd.py`（如 `json_cmd.py`, `file_cmd.py`）
- 单个单词的 category：`<category>.py`（如 `crypto.py`, `net.py`, `db.py`）

## 架构概览

### 模块依赖图
```
                    ┌──────────────┐
                    │   cli.py     │
                    │ (仅命令注册  │
                    │  和参数解析) │
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼──────┐   ┌───────▼──────┐   ┌───────▼──────┐
│ commands/    │   │ commands/    │   │ commands/    │
│ json_cmd.py  │   │ crypto.py    │   │ net.py       │
└──────────────┘   └──────────────┘   └──────────────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           │
                    ┌──────▼───────┐
                    │   core/      │
                    │ color.py     │
                    │ config.py    │
                    │ http_client.py│
                    └──────────────┘
```

### 核心原则
1. **core 被所有 category 依赖** - 每个 category 都依赖 core
2. **各 category 独立** - 任何 category 都不应导入其他 category
3. **cli.py 保持精简** - 仅做命令注册和参数解析，不包含任何业务逻辑
4. **业务逻辑放在 commands/** - 每个命令文件包含自己的逻辑
5. **core 中的函数必须被 ≥2 个 category 使用** - 杜绝"伪公共函数"

## 如何贡献

### 报告 Bug

- **使用 Issue 模板** 提交 Bug 报告
- **包含详细的复现步骤**
- **说明您的环境**（操作系统、Python 版本、devkit 版本）
- **如果可能，附上带 `--no-color` 参数的命令输出**

### 建议新功能

- **先检查现有 Issue**，避免重复提交
- **使用功能请求模板**
- **清晰说明使用场景** - 这个功能解决什么问题？
- **考虑范围边界** - 这个功能适合放在 devkit 还是应该作为独立工具？

### 新手友好任务 (Good First Issues)

标有 `good first issue` 标签的 Issue 非常适合新贡献者：

- 🔤 **添加新的哈希算法支持**（如 BLAKE2、SHA-3）
- 💡 **改进某个命令的错误提示**
- 📝 **为命令的 help 文本添加更多示例**
- 🎨 **添加新的颜色主题选项**
- 📊 **添加新的输出格式**（如 XML、Markdown 表格）
- 🔧 **为命令参数添加校验逻辑**
- 📚 **完善复杂函数的文档字符串**
- 🧪 **为边界情况添加测试用例**

## 开发流程

### 前置条件
- Python 3.8 或更高版本
- pip
- （可选）keyring 用于安全存储凭证

### 环境搭建

1. **Fork 仓库** 并克隆您的 Fork：
   ```bash
   git clone https://github.com/<您的用户名>/devkit.git
   cd devkit
   ```

2. **创建虚拟环境**：
   ```bash
   python -m venv .venv
   source .venv/bin/activate  # Windows 用: .venv\Scripts\activate
   ```

3. **开发模式安装**所有依赖：
   ```bash
   pip install -e ".[all,dev]"
   ```

4. **验证安装**：
   ```bash
   devkit --version
   ```

5. **运行测试套件**：
   ```bash
   pytest tests/ -v
   ```

## 添加新的 Category

想要添加新的 category（例如 `devkit docker`）？请遵循以下步骤：

### 步骤 1：创建命令文件

创建 `devkit/commands/<name>.py`：

```python
import click
from ..core import Color, cprint

# 先写纯业务逻辑函数
def do_something(param1, param2):
    """功能的核心逻辑。
    
    Args:
        param1: 参数 1 的说明
        param2: 参数 2 的说明
    
    Returns:
        返回值的说明
    
    Raises:
        ValueError: 当 param1 无效时抛出
    """
    # 具体实现
    return result

# 然后是 Click 命令包装
@click.group()
def mycategory():
    """这个 category 的功能描述"""
    pass

@mycategory.command('action')
@click.argument('input')
@click.option('--flag', '-f', is_flag=True, help='启用特殊模式')
def mycategory_action(input, flag):
    """这个 action 的功能描述
    
    示例:
      devkit mycategory action somefile.txt
      devkit mycategory action input.json --flag
    """
    try:
        result = do_something(input, flag)
        cprint(f'成功: {result}', Color.GREEN)
    except Exception as e:
        cprint(f'错误: {e}', Color.RED)
```

### 步骤 2：在 cli.py 中注册

编辑 `devkit/cli.py`：

```python
from .commands.mycategory import mycategory

# ... 在底部，其他注册命令之后：
cli.add_command(mycategory)
```

### 步骤 3：编写测试

创建 `tests/test_mycategory.py`：

```python
import pytest
from click.testing import CliRunner
from devkit.cli import cli

@pytest.fixture
def runner():
    return CliRunner()

class TestMyCategory:
    def test_basic_functionality(self, runner):
        result = runner.invoke(cli, ['mycategory', 'action', 'test'])
        assert result.exit_code == 0
        assert 'expected output' in result.output
    
    def test_error_handling(self, runner):
        result = runner.invoke(cli, ['mycategory', 'action', 'invalid'])
        assert result.exit_code == 1
        assert 'Error' in result.output
```

### 步骤 4：更新文档

1. 在 `README.md` 的命令列表中添加新 category
2. 在 `README.md` 中添加使用示例
3. 在 `cli.py` 的 CLI 帮助文本中添加新 category 说明

## 代码规范

### Ruff 规则集

我们使用 `ruff` 进行代码检查，配置在 `pyproject.toml` 中：

```toml
[tool.ruff]
line-length = 100
target-version = "py38"
select = ["E", "F", "W", "I", "N", "UP"]
ignore = ["E501", "W291", "W293"]
```

运行代码检查：
```bash
ruff check devkit/
ruff format devkit/  # 自动修复格式问题
```

### 类型注解

**所有公共函数必须有类型注解**：

```python
# ✅ 正确
def process_data(data: dict[str, Any], options: dict[str, bool]) -> list[str]:
    ...

# ❌ 错误
def process_data(data, options):
    ...
```

使用 `mypy` 进行类型检查：
```bash
mypy devkit/
```

### 文档字符串

所有公共函数使用 **Google 风格**的文档字符串：

```python
def parse_jq_path(path: str) -> list[tuple[str, str]]:
    """解析 jq 风格的路径表达式为 token 列表。
    
    Args:
        path: JQ 路径表达式（如 "data.users[0].name"）
    
    Returns:
        (token_type, token_value) 元组列表。
        token_type 可选值: "key", "index"
    
    Raises:
        ValueError: 路径包含无效语法时抛出
    
    Examples:
        >>> parse_jq_path("data.users[0].name")
        [("key", "data"), ("key", "users"), ("index", "0"), ("key", "name")]
    """
```

### Git 提交信息

遵循 [约定式提交](https://www.conventionalcommits.org/zh-hans/v1.0.0/) 规范：

```
feat: 添加新的哈希算法 BLAKE2
fix: 处理 jq 解析器中空 JSON 数组的问题
docs: 更新安装说明文档
test: 为 regex 模块添加边界情况测试
refactor: 简化 AES-GCM 认证逻辑
perf: 提升大文件 JSON 格式化速度
```

## Pull Request 流程

1. **先提 Issue** 讨论重大变更的设计
2. **Fork 仓库** 并从 `main` 创建特性分支：
   ```bash
   git checkout -b feature/my-new-feature
   ```
3. **编写代码**，遵循代码规范
4. **添加测试** - 没有测试的 PR 不会被合并
5. **确保 CI 通过**：
   ```bash
   pytest tests/ -v
   ruff check devkit/
   mypy devkit/
   ```
6. **提交 PR**，填写清晰的标题和描述
7. **等待 CI 全绿** - 所有检查必须通过
8. **回应评审意见** - 及时响应维护者的评论
9. **如果需要，压缩提交** 后再合并

### PR 检查清单
- [ ] （重大功能）我已先开 Issue 讨论设计
- [ ] 我已添加测试证明修复有效或功能可用
- [ ] 我已添加必要的文档（如适用）
- [ ] 我的代码遵循 ruff 代码规范
- [ ] 我的代码有完整的类型注解
- [ ] 所有新旧测试在本地通过

## 测试

### 运行测试

```bash
# 运行所有测试
pytest tests/ -v

# 运行特定模块的测试
pytest tests/test_json_cmd.py -v

# 运行并生成覆盖率报告
pytest tests/ -v --cov=devkit --cov-report=html

# 在多个 Python 版本上运行（需要 pyenv）
tox
```

### 测试覆盖率目标

所有模块的测试覆盖率目标为 **≥80%**。

### 测试最佳实践

1. **同时测试成功和失败场景**
2. **测试边界情况**（空输入、最大值、特殊字符）
3. **使用 fixtures** 处理通用测试准备
4. **Mock 外部依赖**（HTTP 请求、数据库连接）
5. **不测实现细节** - 只测行为

---

感谢您为 DevKit CLI 做贡献！🙌
