use clap::Parser;
use colored::Colorize;
use std::process;
use tracing::{debug, error, info};
use tracing_subscriber::{fmt, EnvFilter};

mod branch;
mod changelog;
mod cli;
mod commit;
mod config;
mod errors;
mod git;
mod health;
mod jira;
mod pr;

use cli::{Cli, Commands};
use config::ConfigManager;
use errors::{GitFlowError, Result};
use git::GitRepository;

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

    let config_manager = if let Some(config_path) = &cli.config {
        ConfigManager::with_custom_path(config_path)?
    } else {
        ConfigManager::new()?
    };

    let config = config_manager.load()?;
    debug!("配置加载完成");

    match &cli.command {
        Commands::Config { command } => {
            handle_config_command(&config_manager, command).await?;
        }
        _ => {
            let repo = GitRepository::open(None)?;
            info!("已打开Git仓库: {:?}", repo.path());

            match &cli.command {
                Commands::Branch { command } => {
                    let manager = branch::BranchManager::new(&repo, &config);
                    manager.handle(command).await?;
                }
                Commands::Commit { command } => {
                    let manager = commit::CommitManager::new(&repo, &config);
                    manager.handle(command)?;
                }
                Commands::Pr { command } => {
                    let manager = pr::PrManager::new(&repo, &config)?;
                    manager.handle(command).await?;
                }
                Commands::Changelog { command } => {
                    let generator = changelog::ChangelogGenerator::new(&repo, &config);
                    generator.handle(command)?;
                }
                Commands::Health { command } => {
                    let scanner = health::HealthScanner::new(&repo, &config);
                    scanner.handle(command)?;
                }
                Commands::Config { .. } => unreachable!(),
            }
        }
    }

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

async fn handle_config_command(
    config_manager: &ConfigManager,
    command: &cli::ConfigCommands,
) -> Result<()> {
    use cli::ConfigCommands::*;

    match command {
        Init { global, force } => {
            config_manager.init(*global, *force)?;
            println!(
                "{} 配置文件已初始化: {:?}",
                "✓".green().bold(),
                if *global {
                    config_manager.global_path()
                } else {
                    config_manager.project_path()
                }
            );
        }
        Show { global, merged } => {
            let config = if *merged {
                config_manager.load()?
            } else if *global {
                config_manager.load_global()?
            } else {
                config_manager.load_project()?
            };

            let output = toml::to_string_pretty(&config)?;
            println!();
            println!("{}", "=".repeat(60).dimmed());
            if *global {
                println!("{} 全局配置", "📋".cyan());
            } else if *merged {
                println!("{} 合并后的配置", "📋".cyan());
            } else {
                println!("{} 项目配置", "📋".cyan());
            }
            println!("{}", "=".repeat(60).dimmed());
            println!();
            println!("{}", output);
        }
        Set { key, value, global } => {
            config_manager.set_value(key, value, *global)?;
            println!(
                "{} 配置已设置: {} = {}",
                "✓".green().bold(),
                key.bold(),
                value
            );
        }
        Get { key, global } => {
            let value = config_manager.get_value(key, *global)?;
            println!("{} = {}", key.bold(), value);
        }
    }

    Ok(())
}

fn handle_error(e: GitFlowError) {
    eprintln!();
    match e {
        GitFlowError::UserCancelled => {
            eprintln!("{} 操作已取消", "⚠️".yellow());
        }
        GitFlowError::ValidationError(msg) => {
            eprintln!("{} 验证失败: {}", "❌".red(), msg);
        }
        GitFlowError::ConfigError(msg) => {
            eprintln!("{} 配置错误: {}", "⚙️".red(), msg);
        }
        GitFlowError::RepositoryNotFound => {
            eprintln!(
                "{} 未找到Git仓库，请在Git仓库目录下运行此命令",
                "❌".red()
            );
        }
        GitFlowError::InvalidBranchName(msg) => {
            eprintln!("{} 无效的分支名: {}", "❌".red(), msg);
        }
        GitFlowError::InvalidCommitMessage(msg) => {
            eprintln!("{} 无效的提交消息: {}", "❌".red(), msg);
        }
        GitFlowError::JiraError(msg) => {
            eprintln!("{} JIRA API错误: {}", "🔗".red(), msg);
        }
        GitFlowError::GitPlatformError(msg) => {
            eprintln!("{} Git平台API错误: {}", "🔗".red(), msg);
        }
        _ => {
            eprintln!("{} 错误: {:?}", "❌".red(), e);
        }
    }
    eprintln!();

    if std::env::var("GITFLOW_DEBUG").is_ok() {
        error!("详细错误信息: {:?}", e);
    }
}
