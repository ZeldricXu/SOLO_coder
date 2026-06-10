# 配置文档

gitflow 支持全局和项目级两层 TOML 配置。

## 配置文件位置

优先级从高到低：

1. 命令行参数 `--config` 指定的文件
2. 项目级配置：`.gitflow.toml`（位于 Git 仓库根目录）
3. 全局配置：`~/.config/gitflow/config.toml`

## 完整配置示例

```toml
# ===================== 通用配置 =====================
[general]
# 默认目标分支
default_base_branch = "main"
# 静默模式
quiet = false
# 调试模式
debug = false

# ===================== JIRA 配置 =====================
[jira]
# JIRA 服务地址
base_url = "https://your-org.atlassian.net"
# JIRA API Token（在个人设置中生成）
api_token = ""
# JIRA 用户名/邮箱
username = "your.email@example.com"
# 分支名模板
branch_name_template = "{{type}}/{{issue_key}}-{{summary}}"

# ===================== GitHub 配置 =====================
[github]
# GitHub API Token
token = ""
# 组织名
org = "your-org"
# 默认 reviewer 列表
default_reviewers = ["user1", "user2"]
# 默认标签
default_labels = ["reviewed"]

# ===================== GitLab 配置 =====================
[gitlab]
# GitLab API Token
token = ""
# GitLab 地址（自托管需要配置）
base_url = "https://gitlab.com"
# 项目 ID
project_id = ""

# ===================== Bitbucket 配置 =====================
[bitbucket]
# Bitbucket 用户名/邮箱
username = ""
# Bitbucket 应用密码
app_password = ""
# 工作区
workspace = "your-workspace"

# ===================== 分支管家配置 =====================
[branch]
# 支持的分支类型
types = ["feature", "bugfix", "hotfix", "release", "chore"]
# 分支名最大长度
max_name_length = 100
# 清理保留分支
protect_branches = ["main", "master", "develop", "release/*"]

# 分支类型清理规则（按类型）
[branch.clean_type_rules]
# feature 分支合并后立即删除（0天）
feature = 0
# bugfix 分支合并后保留7天
bugfix = 7
# hotfix 分支合并后保留30天
hotfix = 30
# release 分支合并后保留90天
release = 90
# chore 分支合并后立即删除
chore = 0

# 分支清理年龄阈值（天）
age_threshold = 30

# ===================== 提交规范配置 =====================
[commit]
# 提交类型
types = ["feat", "fix", "docs", "style", "refactor", "perf", "test", "chore", "build", "ci", "revert"]
# 提交摘要最大长度
max_subject_length = 72
# 提交正文最大长度
max_body_line_length = 100
# 必须填写影响范围（scope）
require_scope = false
# 必须关联 JIRA issue
require_jira = false
# 预提交钩子
enable_pre_commit_hook = true

# 预提交时运行的命令
pre_commit_commands = [
    "cargo clippy --all-targets --all-features -- -D warnings",
    "cargo test"
]

# ===================== PR 配置 =====================
[pr]
# PR 标题模板
title_template = "{{type}}({{scope}}): {{summary}}"
# 自动关联 JIRA issue
auto_link_jira = true
# 自动添加 reviewer
auto_add_reviewers = true
# PR 模板路径
template_path = ".github/PULL_REQUEST_TEMPLATE.md"

# ===================== Changelog 配置 =====================
[changelog]
# 输出路径
output_path = "CHANGELOG.md"
# 包含的提交类型
include_types = ["feat", "fix", "perf", "refactor"]
# 包含未发布部分
include_unreleased = true
# 按类型分组
group_by_type = true

# ===================== 健康扫描配置 =====================
[health]
# 大文件阈值（MB）
large_file_threshold = 5
# 过期分支阈值（天）
stale_branch_threshold = 90
# 忽略的文件/目录
ignore_patterns = ["node_modules", "target", ".git"]
# CI 失败趋势分析周数
ci_trend_weeks = 2
```

## 初始化配置

运行以下命令在当前目录创建项目级配置：

```bash
gitflow config init
```

运行以下命令创建全局配置：

```bash
gitflow config init --global
```

## 查看配置

查看合并后的配置：

```bash
gitflow config show --merged
```

查看全局配置：

```bash
gitflow config show --global
```

查看项目配置：

```bash
gitflow config show
```

## 设置配置项

设置全局配置：

```bash
gitflow config set jira.base_url "https://your-org.atlassian.net" --global
```

设置项目配置：

```bash
gitflow config set branch.max_name_length 120
```

## 获取配置项

```bash
gitflow config get jira.base_url
```
