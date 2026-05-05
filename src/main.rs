use clap::Parser;
use colored::*;
use indicatif::{ProgressBar, ProgressStyle};
use std::sync::Arc;
use tokio::sync::Mutex;

use multigit::cli::*;
use multigit::config::*;
use multigit::errors::*;
use multigit::events::*;
use multigit::git::*;
use multigit::logging::*;
use multigit::scheduler::*;

#[tokio::main]
async fn main() {
    let cli = Cli::parse();

    let result = match &cli.command {
        Commands::Sync(sync_cmd) => handle_sync(&cli, sync_cmd).await,
        Commands::Log(log_cmd) => handle_log(&cli, log_cmd).await,
        Commands::Config(config_cmd) => handle_config(&cli, config_cmd).await,
    };

    match result {
        Ok(_) => std::process::exit(0),
        Err(e) => {
            eprintln!("{}: {}", "错误".red(), e);
            std::process::exit(1);
        }
    }
}

struct ApplicationContext {
    event_bus: EventBus,
    verbose: bool,
}

impl ApplicationContext {
    fn new(verbose: bool) -> Self {
        ApplicationContext {
            event_bus: EventBus::new(),
            verbose,
        }
    }

    fn setup_subscribers(&self) {
        let console_subscriber = Box::new(ConsoleSubscriber::new(self.verbose));
        self.event_bus.subscribe(console_subscriber);
    }

    fn create_progress_subscriber(&self, total: u64, operation: String) -> ProgressBarSubscriber {
        ProgressBarSubscriber::new(total, operation)
    }
}

async fn handle_sync(cli: &Cli, cmd: &SyncCommand) -> AppResult<()> {
    let ctx = ApplicationContext::new(cli.verbose);
    ctx.setup_subscribers();

    let config_manager = load_config(cli.config.as_deref())?;
    let repositories = config_manager.load_group(&cmd.group)?;

    println!("{} 仓库组: {}", "同步".cyan(), cmd.group.green());
    println!("{} 仓库数量: {}\n", "发现".cyan(), repositories.len());

    let operation = if cmd.fetch_only { "fetch" } else { "pull" };
    let total = repositories.len() as u64;

    let progress_subscriber = ctx.create_progress_subscriber(total, operation.to_string());
    let _progress_id = ctx.event_bus.subscribe(Box::new(progress_subscriber));

    let git_options = GitOptions {
        force: cmd.force,
        fetch_only: cmd.fetch_only,
        prune: cmd.prune,
        fetch_depth: None,
        retry_count: 1,
        timeout_seconds: 300,
        verbose: cli.verbose,
        priority: TaskPriority::Normal,
    };

    let git_engine = GitEngine::new(cli.concurrency).with_event_bus(ctx.event_bus.clone());

    println!("开始执行 {} 操作...", operation.green());

    let batch_result = git_engine.batch_pull(repositories, git_options).await?;

    println!();

    print_sync_results(&batch_result, cli.verbose);

    println!("\n{}:", "汇总".bold().cyan());
    println!("  {}: {}", "总仓库数".white(), batch_result.total.to_string().yellow());
    println!("  {}: {}", "成功".green(), batch_result.success_count.to_string().green());
    println!("  {}: {}", "失败".red(), batch_result.failed_count.to_string().red());
    println!("  {}: {}", "冲突".yellow(), batch_result.conflict_count.to_string().yellow());
    println!("  {}: {}", "跳过".blue(), batch_result.skipped_count.to_string().blue());
    println!(
        "  {}: {:.2}秒",
        "耗时".white(),
        batch_result.total_duration_ms as f64 / 1000.0
    );

    if batch_result.conflict_count > 0 {
        println!(
            "\n{}: 发现 {} 个仓库存在冲突，请手动解决。",
            "警告".yellow().bold(),
            batch_result.conflict_count
        );
    }

    if batch_result.failed_count > 0 {
        return Err(AppError::internal("部分仓库同步失败"));
    }

    Ok(())
}

async fn handle_log(cli: &Cli, cmd: &LogCommand) -> AppResult<()> {
    let ctx = ApplicationContext::new(cli.verbose);
    ctx.setup_subscribers();

    let config_manager = load_config(cli.config.as_deref())?;

    let repositories: Vec<RepositoryConfig> = if let Some(group_name) = &cmd.group {
        config_manager
            .load_group(group_name)?
            .iter()
            .cloned()
            .collect()
    } else {
        config_manager
            .get_config()
            .get_all_repositories()
            .into_iter()
            .map(|(_, repo)| repo.clone())
            .collect()
    };

    if repositories.is_empty() {
        return Err(AppError::not_found("没有找到任何仓库"));
    }

    let total = repositories.len();
    println!("{} 分析 {} 个仓库的提交日志...", "开始".cyan(), total);

    let progress_subscriber = ctx.create_progress_subscriber(total as u64, "log".to_string());
    let _progress_id = ctx.event_bus.subscribe(Box::new(progress_subscriber));

    let git_engine = GitEngine::new(cli.concurrency).with_event_bus(ctx.event_bus.clone());

    let logs = git_engine
        .batch_log(
            &repositories,
            cmd.author.as_deref(),
            cmd.since.as_deref(),
            cmd.number,
        )
        .await?;

    let aggregated = AggregatedLogs::from_git_outputs_event(&logs);

    println!();

    if cmd.stats {
        print_log_stats(&aggregated);
    } else {
        println!("{}:", "提交记录".bold().cyan());
        println!(
            "{}",
            format_commit_table(&aggregated.commits, Some(50))
        );

        println!("\n{}:", "统计摘要".bold().cyan());
        println!("  总提交数: {}", aggregated.total_commits().to_string().yellow());
        println!("  作者数量: {}", aggregated.unique_authors().to_string().green());
        println!("  涉及仓库: {}", aggregated.unique_repositories().to_string().blue());
    }

    Ok(())
}

async fn handle_config(cli: &Cli, cmd: &ConfigCommand) -> AppResult<()> {
    match cmd {
        ConfigCommand::List(_) => {
            let config_manager = load_config(cli.config.as_deref())?;
            let groups = config_manager.list_groups();

            if groups.is_empty() {
                println!("{}: 没有配置任何仓库组", "信息".blue());
            } else {
                println!("{}:", "已配置的仓库组".bold().cyan());
                for group in groups {
                    let group_config = config_manager.load_group(group)?;
                    println!(
                        "  • {} ({} 个仓库)",
                        group.green(),
                        group_config.len().to_string().yellow()
                    );
                }
            }
        }

        ConfigCommand::Show(show_cmd) => {
            let config_manager = load_config(cli.config.as_deref())?;
            let group = config_manager
                .get_config()
                .get_group(&show_cmd.group)
                .ok_or_else(|| {
                    AppError::not_found(format!("仓库组不存在: {}", show_cmd.group))
                })?;

            println!("{}: {}", "仓库组".bold().cyan(), group.group_name.green());
            println!("\n{}:", "同步设置".bold().cyan());
            println!("  自动修剪: {}", group.sync_settings.auto_prune);
            println!("  获取深度: {}", group.sync_settings.fetch_depth);

            println!("\n{}:", "仓库列表".bold().cyan());
            for (idx, repo) in group.repositories.iter().enumerate() {
                println!("\n  {}. {}", (idx + 1).to_string().yellow(), repo.name.bold());
                println!("     URL: {}", repo.url);
                println!("     本地路径: {}", repo.local_path);
                println!("     默认分支: {}", repo.default_branch);
            }
        }

        ConfigCommand::Validate(_) => {
            let spinner = create_spinner("加载并验证配置文件...");

            let config_manager = match load_config(cli.config.as_deref()) {
                Ok(cm) => cm,
                Err(e) => {
                    spinner.finish_with_message("验证失败".red().to_string());
                    return Err(e);
                }
            };

            spinner.set_message("验证本地仓库路径...");

            let result = config_manager.validate_repository_paths();

            match result {
                Ok(_) => {
                    spinner.finish_with_message("✓ 全部验证通过".green().to_string());
                    println!("\n{}: 配置文件格式正确", "成功".green().bold());
                    if let Some(path) = config_manager.get_config_path() {
                        println!("   配置文件: {}", path.display().to_string().cyan());
                    }
                }
                Err(e) => {
                    spinner.finish_with_message("✗ 验证失败".red().to_string());
                    println!();
                    eprintln!("{}: {}", "错误".red().bold(), e);
                    return Err(e);
                }
            }
        }

        ConfigCommand::Init(init_cmd) => {
            let output_path = init_cmd
                .output
                .as_ref()
                .map(std::path::PathBuf::from)
                .unwrap_or_else(|| std::path::PathBuf::from("multigit.yaml"));

            if output_path.exists() {
                return Err(AppError::validation(format!(
                    "配置文件已存在: {}",
                    output_path.display()
                )));
            }

            let example_config = create_example_config();
            example_config.save_to_file(&output_path)?;

            println!("{}: 配置文件模板已创建", "✓ 成功".green().bold());
            println!("   路径: {}", output_path.display().to_string().cyan());
            println!(
                "\n{}: 请编辑该文件添加您的仓库配置",
                "提示".yellow()
            );
        }
    }

    Ok(())
}

fn print_sync_results(result: &BatchResult, verbose: bool) {
    for repo_result in &result.results {
        let status_str = match repo_result.status {
            ExecutionStatus::Success => "✓ 成功".green(),
            ExecutionStatus::Failed => "✗ 失败".red(),
            ExecutionStatus::Conflict => "⚠ 冲突".yellow(),
            ExecutionStatus::Skipped => "→ 跳过".blue(),
            ExecutionStatus::Pending => "… 等待中".white(),
            ExecutionStatus::Running => "● 执行中".cyan(),
        };

        let duration_str = if repo_result.duration_ms > 0 {
            format!(" ({:.2}s)", repo_result.duration_ms as f64 / 1000.0)
        } else {
            String::new()
        };

        println!(
            "{} {}{}",
            status_str,
            repo_result.repository_name.bold(),
            duration_str
        );

        if verbose && (!repo_result.stdout.is_empty() || !repo_result.stderr.is_empty()) {
            if !repo_result.stdout.is_empty() {
                for line in repo_result.stdout.lines() {
                    if !line.trim().is_empty() {
                        println!("      stdout: {}", line);
                    }
                }
            }
            if !repo_result.stderr.is_empty() {
                for line in repo_result.stderr.lines() {
                    if !line.trim().is_empty() {
                        println!("      stderr: {}", line);
                    }
                }
            }
        }
    }
}

fn print_log_stats(aggregated: &AggregatedLogs) {
    println!("{}:", "按作者统计".bold().cyan());
    println!("{}", format_author_stats(&aggregated.author_stats, Some(10)));

    println!("\n{}:", "每日提交趋势".bold().cyan());
    println!("{}", format_daily_stats(&aggregated.daily_stats));

    println!("\n{}:", "总体统计".bold().cyan());
    println!("  总提交数: {}", aggregated.total_commits().to_string().yellow());
    println!("  作者数量: {}", aggregated.unique_authors().to_string().green());
    println!("  涉及仓库: {}", aggregated.unique_repositories().to_string().blue());
}

fn load_config(config_path: Option<&str>) -> AppResult<ConfigManager> {
    let config_manager = if let Some(path) = config_path {
        ConfigManager::load(path)
    } else {
        ConfigManager::load_default()
    };

    match config_manager {
        Ok(cm) => Ok(cm),
        Err(e) => Err(e),
    }
}

fn create_example_config() -> MultiGitConfig {
    MultiGitConfig {
        groups: vec![RepositoryGroup {
            group_name: "microservices".to_string(),
            repositories: vec![
                RepositoryConfig {
                    name: "user-service".to_string(),
                    url: "git@github.com:org/user-service.git".to_string(),
                    local_path: "/projects/user-service".to_string(),
                    default_branch: "main".to_string(),
                },
                RepositoryConfig {
                    name: "order-service".to_string(),
                    url: "git@github.com:org/order-service.git".to_string(),
                    local_path: "/projects/order-service".to_string(),
                    default_branch: "main".to_string(),
                },
            ],
            sync_settings: SyncSettings {
                auto_prune: true,
                fetch_depth: 0,
            },
        }],
    }
}

fn create_spinner(message: &str) -> ProgressBar {
    let pb = ProgressBar::new_spinner();
    pb.set_style(
        ProgressStyle::default_spinner()
            .template("{spinner:.green} {msg}")
            .unwrap(),
    );
    pb.set_message(message.to_string());
    pb
}

impl AggregatedLogs {
    pub fn from_git_outputs_event(
        outputs: &std::collections::HashMap<String, AppResult<String>>,
    ) -> Self {
        let mut aggregated = AggregatedLogs::new();

        for (repo_name, result) in outputs {
            match result {
                Ok(output) => {
                    for line in output.lines() {
                        if let Some(commit) = CommitRecord::from_git_log_line(line, repo_name) {
                            aggregated.commits.push(commit);
                        }
                    }
                }
                Err(e) => {
                    eprintln!("获取仓库 {} 的日志失败: {}", repo_name, e);
                }
            }
        }

        aggregated.commits.sort_by(|a, b| b.date.cmp(&a.date));
        aggregated.calculate_author_stats();
        aggregated.calculate_daily_stats();

        aggregated
    }
}
