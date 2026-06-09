use clap::Parser;
use colored::Colorize;
use std::process;
use tracing::{debug, error, info};
use tracing_subscriber::{fmt, EnvFilter};

mod branch;
mod changelog;
mod cli;
mod command;
mod commit;
mod config;
mod context;
mod errors;
mod git;
mod git_hosting;
mod health;
mod jira;
mod pr;

use cli::Cli;
use context::AppContext;
use errors::{GitFlowError, Result};

#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        handle_error(e);
        process::exit(1);
    }
}

async fn run() -> Result<()> {
    let cli = Cli::parse();
    init_logging(&cli);

    debug!("CLI参数解析完成: {:?}", cli);

    let ctx = AppContext::new(cli.config.as_deref()).await?;
    info!("已打开Git仓库: {:?}", ctx.git.path());

    cli.command.dispatch(ctx).await?;

    Ok(())
}

fn init_logging(cli: &Cli) {
    let filter = if cli.debug {
        EnvFilter::new("debug")
    } else if cli.quiet {
        EnvFilter::new("error")
    } else {
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"))
    };

    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .with_level(true)
        .with_thread_ids(false)
        .with_thread_names(false)
        .event_format(fmt::format().compact())
        .init();

    debug!("日志系统初始化完成");
}

fn handle_error(e: GitFlowError) {
    eprintln!();
    match e {
        GitFlowError::UserCancelled => {
            eprintln!("{} 操作已取消", "⚠️".yellow());
        }
        GitFlowError::ValidationError(ref msg) => {
            eprintln!("{} 验证失败: {}", "❌".red(), msg);
        }
        GitFlowError::ConfigError(ref msg) => {
            eprintln!("{} 配置错误: {}", "⚙️".red(), msg);
        }
        GitFlowError::RepositoryNotFound => {
            eprintln!(
                "{} 未找到Git仓库，请在Git仓库目录下运行此命令",
                "❌".red()
            );
        }
        GitFlowError::InvalidBranchName(ref msg) => {
            eprintln!("{} 无效的分支名: {}", "❌".red(), msg);
        }
        GitFlowError::InvalidCommitMessage(ref msg) => {
            eprintln!("{} 无效的提交消息: {}", "❌".red(), msg);
        }
        GitFlowError::JiraError(ref msg) => {
            eprintln!("{} JIRA API错误: {}", "🔗".red(), msg);
        }
        GitFlowError::GitPlatformError(ref msg) => {
            eprintln!("{} Git平台API错误: {}", "🔗".red(), msg);
        }
        ref e => {
            eprintln!("{} 错误: {:?}", "❌".red(), e);
        }
    }
    eprintln!();

    if std::env::var("GITFLOW_DEBUG").is_ok() {
        error!("详细错误信息: {:?}", e);
    }
}
