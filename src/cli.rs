use clap::Parser;
use crate::define_commands;
use crate::command::ModuleCommand;
use crate::branch::cli::BranchModule;
use crate::commit::cli::CommitModule;
use crate::pr::cli::PrModule;
use crate::changelog::cli::ChangelogModule;
use crate::health::cli::HealthModule;
use crate::config::cli::ConfigModule;

define_commands!(
    (branch, BranchModule),
    (commit, CommitModule),
    (pr, PrModule),
    (changelog, ChangelogModule),
    (health, HealthModule),
    (config, ConfigModule),
);

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
