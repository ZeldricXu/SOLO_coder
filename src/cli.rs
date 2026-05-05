use clap::{Parser, Subcommand};

#[derive(Parser, Debug)]
#[command(name = "multigit")]
#[command(about = "MultiGit - 跨平台多Git仓库批量管理工具", long_about = None)]
#[command(version = "0.1.0")]
pub struct Cli {
    #[command(subcommand)]
    pub command: Commands,

    #[arg(short, long, global = true, help = "配置文件路径")]
    pub config: Option<String>,

    #[arg(short, long, global = true, help = "启用详细输出模式")]
    pub verbose: bool,

    #[arg(short, long, global = true, help = "最大并发数")]
    pub concurrency: Option<usize>,
}

#[derive(Subcommand, Debug)]
pub enum Commands {
    Sync(SyncCommand),
    Log(LogCommand),
    Config(ConfigCommand),
}

#[derive(clap::Args, Debug)]
#[command(about = "批量同步指定仓库组")]
pub struct SyncCommand {
    #[arg(help = "仓库组名称")]
    pub group: String,

    #[arg(short, long, help = "强制覆盖本地变更")]
    pub force: bool,

    #[arg(short, long, help = "仅执行fetch操作，不合并")]
    pub fetch_only: bool,

    #[arg(long, help = "自动修剪远程已删除的分支")]
    pub prune: bool,
}

#[derive(clap::Args, Debug)]
#[command(about = "跨仓库日志聚合查询")]
pub struct LogCommand {
    #[arg(help = "仓库组名称（可选，不指定则查询所有已配置仓库）")]
    pub group: Option<String>,

    #[arg(short, long, help = "过滤指定作者的提交")]
    pub author: Option<String>,

    #[arg(short, long, help = "指定时间范围，如：1.days, 2.weeks, 1.months")]
    pub since: Option<String>,

    #[arg(long, help = "按作者统计提交次数")]
    pub stats: bool,

    #[arg(short, long, help = "限制输出的提交数量")]
    pub number: Option<usize>,
}

#[derive(clap::Args, Debug)]
#[command(about = "配置文件管理")]
pub enum ConfigCommand {
    List(ConfigListCommand),
    Show(ConfigShowCommand),
    Validate(ConfigValidateCommand),
    Init(ConfigInitCommand),
}

#[derive(clap::Args, Debug)]
#[command(about = "列出所有已配置的仓库组")]
pub struct ConfigListCommand {}

#[derive(clap::Args, Debug)]
#[command(about = "显示指定仓库组的详细配置")]
pub struct ConfigShowCommand {
    #[arg(help = "仓库组名称")]
    pub group: String,
}

#[derive(clap::Args, Debug)]
#[command(about = "验证配置文件格式是否正确")]
pub struct ConfigValidateCommand {}

#[derive(clap::Args, Debug)]
#[command(about = "初始化配置文件模板")]
pub struct ConfigInitCommand {
    #[arg(short, long, help = "输出文件路径，默认为当前目录的multigit.yaml")]
    pub output: Option<String>,
}
