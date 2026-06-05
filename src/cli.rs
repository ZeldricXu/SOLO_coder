use clap::{Parser, Subcommand};

#[derive(Parser, Debug)]
#[command(
    name = "gitflow",
    version,
    about = "Git工作流自动化CLI工具 - 让团队开发更高效",
    long_about = None,
)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Commands,

    #[arg(short, long, global = true, help = "启用调试日志")]
    pub debug: bool,

    #[arg(short, long, global = true, help = "静默模式，只输出错误")]
    pub quiet: bool,

    #[arg(
        long,
        global = true,
        help = "配置文件路径",
        value_name = "FILE"
    )]
    pub config: Option<String>,
}

#[derive(Subcommand, Debug)]
pub enum Commands {
    #[command(about = "分支管家 - 管理Git分支")]
    Branch {
        #[command(subcommand)]
        command: BranchCommands,
    },

    #[command(about = "提交规范检查 - 验证和规范提交消息")]
    Commit {
        #[command(subcommand)]
        command: CommitCommands,
    },

    #[command(about = "PR工作流 - 一键创建Pull Request")]
    Pr {
        #[command(subcommand)]
        command: PrCommands,
    },

    #[command(about = "变更日志 - 自动生成CHANGELOG.md")]
    Changelog {
        #[command(subcommand)]
        command: ChangelogCommands,
    },

    #[command(about = "仓库健康扫描 - 检测仓库健康状态")]
    Health {
        #[command(subcommand)]
        command: HealthCommands,
    },

    #[command(about = "配置管理 - 管理全局和项目配置")]
    Config {
        #[command(subcommand)]
        command: ConfigCommands,
    },
}

#[derive(Subcommand, Debug)]
pub enum BranchCommands {
    #[command(about = "创建新分支，支持从JIRA issue自动生成规范分支名")]
    Create {
        #[arg(help = "分支名或JIRA issue号")]
        name: Option<String>,

        #[arg(short, long, help = "基础分支，默认为当前分支")]
        base: Option<String>,

        #[arg(short = 't', long, help = "分支类型: feature/bugfix/hotfix/release/chore", value_parser = ["feature", "bugfix", "hotfix", "release", "chore"])]
        r#type: Option<String>,

        #[arg(short = 'i', long, help = "JIRA issue ID")]
        issue: Option<String>,

        #[arg(short, long, help = "分支描述")]
        description: Option<String>,

        #[arg(short = 'f', long, help = "强制创建，即使分支已存在")]
        force: bool,
    },

    #[command(about = "列出所有分支，显示详细信息")]
    List {
        #[arg(short, long, help = "只显示本地分支")]
        local: bool,

        #[arg(short, long, help = "只显示远程分支")]
        remote: bool,

        #[arg(short, long, help = "按最后提交时间排序")]
        sort_by_date: bool,

        #[arg(short = 'm', long, help = "显示已合并的分支")]
        merged: bool,

        #[arg(short = 'p', long = "pattern", help = "按模式过滤分支名")]
        pattern: Option<String>,
    },

    #[command(about = "清理已合并的本地分支，安全删除")]
    Clean {
        #[arg(short, long, help = "模拟执行，不实际删除")]
        dry_run: bool,

        #[arg(short, long, help = "跳过确认，直接删除")]
        yes: bool,

        #[arg(long, help = "保留的分支列表，逗号分隔", value_delimiter = ',')]
        keep: Vec<String>,

        #[arg(short = 't', long, help = "分支年龄阈值（天），默认30天")]
        age_threshold: Option<i64>,
    },

    #[command(about = "同步分支，与远程保持一致")]
    Sync {
        #[arg(short, long, help = "同步后推送到远程")]
        push: bool,

        #[arg(short, long, help = "同步所有本地分支")]
        all: bool,

        #[arg(short, long, help = "同步前先拉取最新代码")]
        pull: bool,

        #[arg(short = 'r', long, help = "使用rebase而非merge")]
        rebase: bool,
    },
}

#[derive(Subcommand, Debug)]
pub enum CommitCommands {
    #[command(about = "检查提交消息格式")]
    Check {
        #[arg(help = "提交消息或commit hash", default_value = "HEAD")]
        message: Option<String>,

        #[arg(short, long, help = "检查文件中的提交消息")]
        file: Option<String>,

        #[arg(short, long, help = "严格模式，所有规则都必须通过")]
        strict: bool,
    },

    #[command(about = "交互式创建符合规范的提交")]
    Create {
        #[arg(short, long, help = "跳过lint检查")]
        no_lint: bool,

        #[arg(short, long, help = "跳过单元测试")]
        no_test: bool,

        #[arg(short, long, help = "提交类型", value_parser = ["feat", "fix", "docs", "style", "refactor", "perf", "test", "chore", "build", "ci", "revert"])]
        r#type: Option<String>,

        #[arg(short, long, help = "影响范围")]
        scope: Option<String>,

        #[arg(short, long, help = "提交摘要")]
        subject: Option<String>,

        #[arg(short, long, help = "提交详细描述")]
        body: Option<String>,

        #[arg(short = 'B', long, help = "不兼容变更说明")]
        breaking: Option<String>,

        #[arg(short, long, help = "关联的issue列表，逗号分隔", value_delimiter = ',')]
        issues: Vec<String>,
    },

    #[command(about = "安装pre-commit hook")]
    InstallHook {
        #[arg(short, long, help = "强制覆盖现有hook")]
        force: bool,

        #[arg(long, help = "hook类型", default_value = "pre-commit", value_parser = ["pre-commit", "commit-msg"])]
        hook_type: String,
    },
}

#[derive(Subcommand, Debug)]
pub enum PrCommands {
    #[command(about = "从当前分支创建PR")]
    Create {
        #[arg(short, long, help = "目标分支", default_value = "main")]
        base: String,

        #[arg(short, long, help = "PR标题")]
        title: Option<String>,

        #[arg(short, long, help = "PR描述")]
        body: Option<String>,

        #[arg(short, long, help = "指定reviewer列表，逗号分隔", value_delimiter = ',')]
        reviewers: Vec<String>,

        #[arg(short = 'l', long, help = "标签列表，逗号分隔", value_delimiter = ',')]
        labels: Vec<String>,

        #[arg(short = 'D', long, help = "创建草稿PR")]
        draft: bool,

        #[arg(short, long, help = "关联的JIRA issue")]
        issue: Option<String>,

        #[arg(short = 'o', long, help = "创建后在浏览器打开")]
        open: bool,

        #[arg(long, help = "平台: github/gitlab", default_value = "github", value_parser = ["github", "gitlab"])]
        platform: String,
    },

    #[command(about = "查看当前分支的PR状态")]
    Status {
        #[arg(short, long, help = "显示详细信息")]
        verbose: bool,
    },
}

#[derive(Subcommand, Debug)]
pub enum ChangelogCommands {
    #[command(about = "生成变更日志")]
    Generate {
        #[arg(short, long, help = "版本号，如v1.0.0")]
        version: Option<String>,

        #[arg(short, long, help = "从指定tag开始")]
        from: Option<String>,

        #[arg(short, long, help = "到指定tag结束")]
        to: Option<String>,

        #[arg(short, long, help = "输出文件路径", default_value = "CHANGELOG.md")]
        output: String,

        #[arg(short, long, help = "追加到现有文件")]
        append: bool,

        #[arg(long, help = "不包含未发布的变更")]
        no_unreleased: bool,

        #[arg(short, long, help = "只显示，不写入文件")]
        dry_run: bool,
    },

    #[command(about = "初始化CHANGELOG.md")]
    Init {
        #[arg(short, long, help = "输出文件路径", default_value = "CHANGELOG.md")]
        output: String,

        #[arg(short, long, help = "强制覆盖现有文件")]
        force: bool,
    },
}

#[derive(Subcommand, Debug)]
pub enum HealthCommands {
    #[command(about = "运行仓库健康扫描")]
    Scan {
        #[arg(short, long, help = "输出格式: text/json", default_value = "text", value_parser = ["text", "json"])]
        format: String,

        #[arg(short, long, help = "输出文件路径")]
        output: Option<String>,

        #[arg(long, help = "跳过大文件检测")]
        no_large_files: bool,

        #[arg(long, help = "跳过过期分支检测")]
        no_stale_branches: bool,

        #[arg(long, help = "跳过依赖检测")]
        no_dependencies: bool,

        #[arg(long, help = "跳过CI状态检测")]
        no_ci: bool,

        #[arg(long, help = "大文件阈值（MB）", default_value = "5")]
        large_file_threshold: u64,

        #[arg(long, help = "分支过期阈值（天）", default_value = "90")]
        stale_branch_threshold: i64,
    },

    #[command(about = "显示历史健康评分趋势")]
    History {
        #[arg(short, long, help = "显示的记录数", default_value = "10")]
        limit: usize,
    },
}

#[derive(Subcommand, Debug)]
pub enum ConfigCommands {
    #[command(about = "初始化配置文件")]
    Init {
        #[arg(short, long, help = "全局配置")]
        global: bool,

        #[arg(short, long, help = "强制覆盖现有配置")]
        force: bool,
    },

    #[command(about = "查看配置")]
    Show {
        #[arg(short, long, help = "查看全局配置")]
        global: bool,

        #[arg(short, long, help = "查看所有合并后的配置")]
        merged: bool,
    },

    #[command(about = "设置配置项")]
    Set {
        #[arg(help = "配置键，如 commit.types")]
        key: String,

        #[arg(help = "配置值")]
        value: String,

        #[arg(short, long, help = "设置全局配置")]
        global: bool,
    },

    #[command(about = "获取配置项")]
    Get {
        #[arg(help = "配置键")]
        key: String,

        #[arg(short, long, help = "从全局配置获取")]
        global: bool,
    },
}
