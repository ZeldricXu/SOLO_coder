use chrono::{Duration, Utc};
use colored::Colorize;
use dialoguer::{theme::ColorfulTheme, Confirm, Select};
use indicatif::{ProgressBar, ProgressStyle};
use std::collections::HashSet;
use std::sync::Arc;
use tracing::{debug, warn};

use crate::command::{CommandHandler, ModuleCommand};
use crate::config::{BranchConfig, Config, JiraConfig};
use crate::context::AppContext;
use crate::errors::{GitFlowError, Result};
use crate::git::{get_jira_issue_from_branch, BranchInfo, GitContext};
use crate::jira::{slugify, JiraClient};

pub mod cli {
    use clap::Subcommand;

    use super::*;

    #[derive(Subcommand, Debug, Clone)]
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
            #[arg(short = 'n', long, help = "模拟执行，不实际删除")]
            dry_run: bool,

            #[arg(short, long, help = "跳过确认，直接删除")]
            yes: bool,

            #[arg(long, help = "保留的分支列表，逗号分隔", value_delimiter = ',')]
            keep: Vec<String>,

            #[arg(short = 't', long, help = "分支年龄阈值（天），默认30天")]
            age_threshold: Option<i64>,

            #[arg(long, help = "启用自动化清理策略")]
            auto: bool,

            #[arg(long, help = "按分支年龄自动清理已合并分支")]
            by_age: bool,

            #[arg(long, help = "按分支类型规则自动清理（如feature/*合并后即删，hotfix/*保留30天）")]
            by_type: bool,

            #[arg(long, help = "清理上游已被删除的本地tracking分支")]
            by_remote: bool,

            #[arg(long, help = "分支类型清理规则，格式：type:days（如feature:0,hotfix:30,bugfix:7）", value_delimiter = ',')]
            type_rules: Vec<String>,
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

    pub struct BranchHandler {
        ctx: AppContext,
        cmd: BranchCommands,
    }

    impl BranchHandler {
        pub fn new(ctx: AppContext, cmd: BranchCommands) -> Self {
            Self { ctx, cmd }
        }
    }

    #[async_trait::async_trait]
    impl CommandHandler for BranchHandler {
        async fn handle(&self) -> Result<()> {
            let config = self.ctx.config.get().await;
            let manager = BranchManager::new(self.ctx.git.clone(), config);
            manager.handle(&self.cmd).await
        }
    }

    pub struct BranchModule;

    impl ModuleCommand for BranchModule {
        type Command = BranchCommands;
        type Handler = BranchHandler;

        fn name() -> &'static str {
            "branch"
        }

        fn about() -> &'static str {
            "分支管家 - 管理Git分支"
        }

        fn create_handler(ctx: crate::context::AppContext, cmd: &Self::Command) -> Result<Self::Handler> {
            Ok(BranchHandler::new(ctx, cmd.clone()))
        }
    }
}

pub use cli::{BranchCommands, BranchHandler, BranchModule};

pub struct BranchManager {
    git: Arc<GitContext>,
    config: Config,
}

impl BranchManager {
    pub fn new(git: Arc<GitContext>, config: Config) -> Self {
        Self { git, config }
    }

    pub async fn handle(&self, command: &BranchCommands) -> Result<()> {
        match command {
            BranchCommands::Create {
                name,
                base,
                r#type,
                issue,
                description,
                force,
            } => {
                self.create(name.as_deref(), base.as_deref(), r#type.as_deref(), issue.as_deref(), description.as_deref(), *force).await
            }
            BranchCommands::List {
                local,
                remote,
                sort_by_date,
                merged,
                pattern,
            } => {
                self.list(*local, *remote, *sort_by_date, *merged, pattern.as_deref())
            }
            BranchCommands::Clean {
                dry_run,
                yes,
                keep,
                age_threshold,
                auto,
                by_age,
                by_type,
                by_remote,
                type_rules,
            } => {
                self.clean(*dry_run, *yes, keep, *age_threshold, *auto, *by_age, *by_type, *by_remote, type_rules)
            }
            BranchCommands::Sync {
                push,
                all,
                pull,
                rebase,
            } => {
                self.sync(*push, *all, *pull, *rebase)
            }
        }
    }

    async fn create(
        &self,
        name: Option<&str>,
        base: Option<&str>,
        branch_type: Option<&str>,
        issue: Option<&str>,
        description: Option<&str>,
        force: bool,
    ) -> Result<()> {
        let branch_config = &self.config.branch;
        let jira_config = &self.config.jira;

        let (branch_name, issue_info) = match name {
            Some(input_name) => {
                if branch_config.jira_integration && looks_like_jira_issue(input_name, &branch_config.jira_issue_pattern) {
                    self.create_from_jira(input_name, base, branch_type, jira_config, force).await?
                } else {
                    (self.build_branch_name(branch_type, Some(input_name), description, branch_config)?, None)
                }
            }
            None => {
                if let Some(issue_id) = issue {
                    self.create_from_jira(issue_id, base, branch_type, jira_config, force).await?
                } else {
                    let branch_type = self.select_branch_type(branch_type, branch_config)?;
                    let description = match description {
                        Some(d) => d.to_string(),
                        None => {
                            let input: String = dialoguer::Input::with_theme(&ColorfulTheme::default())
                                .with_prompt("请输入分支描述")
                                .interact_text()?;
                            input
                        }
                    };
                    (self.build_branch_name(Some(&branch_type), None, Some(&description), branch_config)?, None)
                }
            }
        };

        let _branch = self.git.create_branch(&branch_name, base, force)?;
        self.git.checkout_branch(&branch_name)?;

        println!();
        println!("{} 分支创建成功!", "✓".green().bold());
        println!("  分支名: {}", branch_name.cyan().bold());
        println!("  基础: {}", base.unwrap_or("HEAD").yellow());
        if let Some((issue_key, issue_url)) = issue_info {
            println!("  JIRA: {} ({})", issue_key.blue(), issue_url.underline());
        }
        println!();
        println!("使用以下命令切换到新分支:");
        println!("  {}", format!("git checkout {}", branch_name).dimmed());

        Ok(())
    }

    async fn create_from_jira(
        &self,
        issue_key: &str,
        _base: Option<&str>,
        branch_type: Option<&str>,
        jira_config: &JiraConfig,
        force: bool,
    ) -> Result<(String, Option<(String, String)>)> {
        if !jira_config.enabled {
            return Err(GitFlowError::JiraError(
                "JIRA集成未启用，请在配置中启用并设置API凭据".into(),
            ));
        }

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message(format!("正在获取JIRA issue {} 信息...", issue_key));

        let jira = JiraClient::new(jira_config.clone())?;
        let issue = jira.get_issue(issue_key).await?;

        pb.finish_and_clear();

        let determined_type = match branch_type {
            Some(t) => t.to_string(),
            None => jira.map_issue_type(&issue.fields.issuetype.name),
        };

        let branch_name = jira.generate_branch_name(&issue, &determined_type, &self.config.branch.name_pattern);
        let issue_url = jira.get_issue_link_url(issue_key).await;

        Ok((branch_name, Some((issue_key.to_string(), issue_url))))
    }

    fn select_branch_type(&self, preselected: Option<&str>, config: &BranchConfig) -> Result<String> {
        if let Some(t) = preselected {
            if config.types.iter().any(|x| x == t) {
                return Ok(t.to_string());
            }
        }

        let items: Vec<String> = config.types.clone();
        let selection = Select::with_theme(&ColorfulTheme::default())
            .with_prompt("请选择分支类型")
            .items(&items)
            .default(0)
            .interact()?;

        Ok(items[selection].clone())
    }

    fn build_branch_name(
        &self,
        branch_type: Option<&str>,
        name: Option<&str>,
        description: Option<&str>,
        config: &BranchConfig,
    ) -> Result<String> {
        let branch_type = branch_type
            .ok_or_else(|| GitFlowError::InvalidBranchName("需要指定分支类型".into()))?;

        if !config.types.iter().any(|t| t == branch_type) {
            return Err(GitFlowError::InvalidBranchName(format!(
                "无效的分支类型 '{}'，允许的类型: {}",
                branch_type,
                config.types.join(", ")
            )));
        }

        let name_part = match (name, description) {
            (Some(n), _) => slugify(n),
            (None, Some(d)) => slugify(d),
            (None, None) => {
                return Err(GitFlowError::InvalidBranchName(
                    "需要指定分支名或描述".into(),
                ))
            }
        };

        if name_part.is_empty() {
            return Err(GitFlowError::InvalidBranchName(
                "分支描述无效".into(),
            ));
        }

        let branch_name = format!("{}/{}", branch_type, name_part);
        self.validate_branch_name(&branch_name, config)?;

        Ok(branch_name)
    }

    fn validate_branch_name(&self, name: &str, config: &BranchConfig) -> Result<()> {
        if name.is_empty() {
            return Err(GitFlowError::InvalidBranchName("分支名不能为空".into()));
        }

        if name.contains(' ') {
            return Err(GitFlowError::InvalidBranchName("分支名不能包含空格".into()));
        }

        if config.protected_branches.iter().any(|b| name == *b) {
            return Err(GitFlowError::InvalidBranchName(format!(
                "分支名 '{}' 是受保护的分支名",
                name
            )));
        }

        if name.len() > 100 {
            warn!("分支名较长 ({} 字符)，建议使用更短的名称", name.len());
        }

        Ok(())
    }

    fn list(
        &self,
        local: bool,
        remote: bool,
        sort_by_date: bool,
        merged_only: bool,
        pattern: Option<&str>,
    ) -> Result<()> {
        let branches = self.git.list_branches(local, remote, pattern, merged_only)?;

        let mut branches = branches;

        if sort_by_date {
            branches.sort_by(|a, b| b.last_commit_time.cmp(&a.last_commit_time));
        } else {
            branches.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        }

        if branches.is_empty() {
            println!("{} 没有找到匹配的分支", "ℹ".blue());
            return Ok(());
        }

        println!();
        println!("{} 共找到 {} 个分支:", "📋".cyan(), branches.len());
        println!();

        println!(
            "{:<3} {:<50} {:<25} {:<20} {:<10}",
            "", "分支名", "最后提交", "作者", "状态"
        );
        println!("{}", "─".repeat(120));

        let current_branch = self.git.current_branch().ok();

        for branch in &branches {
            let prefix = if branch.is_current {
                "* ".green().bold().to_string()
            } else {
                "  ".to_string()
            };

            let display_name = if branch.is_remote {
                format!("{}/{}", "origin".dimmed(), branch.short_name)
            } else {
                branch.name.clone()
            };

            let display_name = if branch.is_current {
                display_name.green().bold().to_string()
            } else {
                display_name.white().to_string()
            };

            let time_str = format_relative_time(&branch.last_commit_time);
            let status = self.get_branch_status(branch, &current_branch);

            println!(
                "{:<3} {:<50} {:<25} {:<20} {:<10}",
                prefix, display_name, time_str, branch.last_commit_author, status
            );
        }

        println!();
        println!("{} 当前分支标记为 *", "提示:".bold());

        Ok(())
    }

    fn get_branch_status(&self, branch: &BranchInfo, current: &Option<String>) -> String {
        let mut statuses = Vec::new();

        if branch.is_merged {
            statuses.push("已合并".green().to_string());
        }

        if branch.is_remote {
            statuses.push("远程".blue().to_string());
        }

        if let Some(curr) = current {
            if branch.short_name == *curr {
                statuses.push("当前".yellow().bold().to_string());
            }
        }

        if statuses.is_empty() {
            "-".to_string()
        } else {
            statuses.join(", ")
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn clean(
        &self,
        dry_run: bool,
        yes: bool,
        keep: &[String],
        age_threshold: Option<i64>,
        auto: bool,
        by_age: bool,
        by_type: bool,
        by_remote: bool,
        type_rules_cli: &[String],
    ) -> Result<()> {
        let config = &self.config.branch;
        let global_age_threshold = age_threshold.unwrap_or(config.clean_age_threshold_days);
        let now = Utc::now();

        let use_auto = auto || by_age || by_type || by_remote;

        let mut type_rules = config.clean_type_rules.clone();
        for rule in type_rules_cli {
            let parts: Vec<&str> = rule.splitn(2, ':').collect();
            if parts.len() == 2 {
                if let Ok(days) = parts[1].parse::<i64>() {
                    type_rules.insert(parts[0].to_string(), days);
                }
            }
        }

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在扫描分支...");

        let local_branches = self.git.list_branches(true, false, None, false)?;
        let remote_branches = if by_remote {
            self.git.list_branches(false, true, None, false)?
        } else {
            Vec::new()
        };
        let _current_branch = self.git.current_branch().ok();

        let mut branches_to_delete: Vec<(BranchInfo, Vec<String>)> = Vec::new();
        let mut keep_set: HashSet<String> = keep.iter().cloned().collect();
        for protected in &config.protected_branches {
            keep_set.insert(protected.clone());
        }

        let remote_short_names: HashSet<String> = remote_branches
            .iter()
            .map(|b| b.short_name.clone())
            .collect();

        for branch in local_branches {
            if branch.is_current {
                continue;
            }

            if keep_set.contains(&branch.short_name) {
                debug!("跳过受保护的分支: {}", branch.name);
                continue;
            }

            let mut reasons = Vec::new();

            if use_auto {
                if by_age || auto {
                    let cutoff = now - Duration::days(global_age_threshold);
                    let is_merged = self
                        .git
                        .is_merged(&branch.short_name, Some(&config.protected_branches[0]))
                        .unwrap_or(false);
                    let is_old = branch.last_commit_time < cutoff;

                    if is_merged && is_old {
                        reasons.push(format!("已合并且超过{}天", global_age_threshold));
                    }
                }

                if by_type || auto {
                    let branch_type = branch.short_name.split('/').next().unwrap_or("");
                    if let Some(&retention_days) = type_rules.get(branch_type) {
                        let is_merged = self
                            .git
                            .is_merged(&branch.short_name, Some(&config.protected_branches[0]))
                            .unwrap_or(false);
                        let cutoff = now - Duration::days(retention_days);
                        let is_old = branch.last_commit_time < cutoff;

                        if is_merged && (retention_days == 0 || is_old) {
                            if retention_days == 0 {
                                reasons.push(format!("{}类型合并后即删", branch_type));
                            } else {
                                reasons.push(format!("{}类型合并且超过{}天", branch_type, retention_days));
                            }
                        }
                    }
                }

                if by_remote || auto {
                    if let Some(upstream) = &branch.upstream {
                        let upstream_short = upstream.split('/').nth(1).unwrap_or(upstream);
                        if !remote_short_names.contains(upstream_short) {
                            reasons.push("上游分支已删除".to_string());
                        }
                    }
                }
            } else {
                let cutoff = now - Duration::days(global_age_threshold);
                let is_merged = self
                    .git
                    .is_merged(&branch.short_name, Some(&config.protected_branches[0]))
                    .unwrap_or(false);
                let is_old = branch.last_commit_time < cutoff;

                if is_merged || is_old {
                    if is_merged {
                        reasons.push("已合并".to_string());
                    }
                    if is_old {
                        reasons.push(format!("超过{}天", global_age_threshold));
                    }
                }
            }

            if !reasons.is_empty() {
                branches_to_delete.push((branch, reasons));
            }
        }

        pb.finish_and_clear();

        if branches_to_delete.is_empty() {
            println!();
            println!("{} 没有找到需要清理的分支", "✓".green());
            println!("  所有分支都是活跃的或受保护的");
            return Ok(());
        }

        println!();
        println!("{} 找到 {} 个可清理的分支:", "⚠".yellow(), branches_to_delete.len());
        if use_auto {
            let mut strategies = Vec::new();
            if by_age || auto { strategies.push(format!("按年龄(>{}天)", global_age_threshold)); }
            if by_type || auto { strategies.push("按类型规则".to_string()); }
            if by_remote || auto { strategies.push("按remote状态".to_string()); }
            println!("  清理策略: {}", strategies.join(", "));
        } else {
            println!("  年龄阈值: {} 天", global_age_threshold);
        }
        if !type_rules.is_empty() && (by_type || auto) {
            println!("  类型规则:");
            let mut rule_pairs: Vec<(&String, &i64)> = type_rules.iter().collect();
            rule_pairs.sort_by(|a, b| a.0.cmp(b.0));
            for (t, days) in rule_pairs {
                println!("    {}/{}: {} 天", t, "*", days);
            }
        }
        println!();

        for (branch, reasons) in &branches_to_delete {
            let reason_strs: Vec<String> = reasons.iter()
                .map(|r| r.yellow().to_string())
                .collect();

            println!(
                "  {} - {} (最后提交: {})",
                branch.short_name.cyan(),
                reason_strs.join(", "),
                format_relative_time(&branch.last_commit_time)
            );
        }

        println!();

        let total_bytes_saved: u64 = 0;

        if dry_run {
            println!("{} 模拟模式，不会实际删除任何分支", "ℹ".blue());
            println!("  将删除 {} 个分支", branches_to_delete.len());
            if total_bytes_saved > 0 {
                println!("  预计释放空间: {}", humansize::format_size(total_bytes_saved, humansize::DECIMAL));
            }
            return Ok(());
        }

        let confirmed = if yes {
            true
        } else {
            Confirm::with_theme(&ColorfulTheme::default())
                .with_prompt(format!(
                    "确定要删除这 {} 个分支吗?",
                    branches_to_delete.len()
                ))
                .default(false)
                .interact()?
        };

        if !confirmed {
            println!("{} 操作已取消", "✗".red());
            return Ok(());
        }

        let delete_pb = ProgressBar::new(branches_to_delete.len() as u64);
        delete_pb.set_style(
            ProgressStyle::default_bar()
                .template("{bar:40.green/white} {pos}/{len} {msg}")
                .unwrap()
                .progress_chars("█▉▊▋▌▍▎▏  "),
        );

        let mut deleted = 0;
        let mut failed = 0;

        for (branch, _) in &branches_to_delete {
            delete_pb.set_message(format!("正在删除: {}", branch.short_name));

            match self.git.delete_branch(&branch.short_name, false) {
                Ok(_) => {
                    deleted += 1;
                    debug!("已删除分支: {}", branch.name);
                }
                Err(e) => {
                    failed += 1;
                    warn!("删除分支 {} 失败: {}", branch.name, e);
                }
            }

            delete_pb.inc(1);
        }

        delete_pb.finish_and_clear();

        println!();
        println!("{} 清理完成!", "✓".green().bold());
        println!("  已删除: {}", deleted.to_string().green());
        if failed > 0 {
            println!("  失败: {}", failed.to_string().red());
        }
        println!();
        println!("{} 提示: 每周一早上运行 'gitflow branch clean --auto --yes' 可自动化清理", "💡".cyan());

        Ok(())
    }

    fn sync(&self, push: bool, sync_all: bool, pull: bool, use_rebase: bool) -> Result<()> {
        let remote = &self.config.general.default_remote;

        if !self.git.has_remote(remote) {
            return Err(GitFlowError::Other(format!(
                "远程仓库 '{}' 不存在",
                remote
            )));
        }

        let current_branch = self.git.current_branch()?;
        let branches: Vec<String> = if sync_all {
            self.git
                .list_branches(true, false, None, false)?
                .into_iter()
                .filter(|b| !self.config.branch.protected_branches.contains(&b.short_name))
                .map(|b| b.short_name)
                .collect()
        } else {
            vec![current_branch.clone()]
        };

        if branches.is_empty() {
            println!("{} 没有需要同步的分支", "ℹ".blue());
            return Ok(());
        }

        if pull {
            let pb = ProgressBar::new_spinner();
            pb.set_style(
                ProgressStyle::default_spinner()
                    .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                    .template("{spinner:.green} {msg}")
                    .unwrap(),
            );
            pb.set_message(format!("正在从 {} 拉取更新...", remote));

            self.git.fetch(remote)?;

            pb.finish_and_clear();
            println!("{} 已拉取远程更新", "✓".green());
        }

        println!();
        println!("{} 同步 {} 个分支:", "🔄".cyan(), branches.len());
        println!();

        let pb = ProgressBar::new(branches.len() as u64);
        pb.set_style(
            ProgressStyle::default_bar()
                .template("{bar:40.green/white} {pos}/{len} {msg}")
                .unwrap()
                .progress_chars("█▉▊▋▌▍▎▏  "),
        );

        let mut synced = 0;
        let mut failed = Vec::new();

        for branch_name in &branches {
            pb.set_message(format!("同步: {}", branch_name));

            if branch_name != &current_branch {
                if let Err(e) = self.git.checkout_branch(branch_name) {
                    failed.push((branch_name.clone(), e.to_string()));
                    pb.inc(1);
                    continue;
                }
            }

            match self.git.sync_branch(branch_name, remote, use_rebase) {
                Ok(_) => {
                    if push {
                        if let Err(e) = self.git.push(branch_name, remote, false) {
                            failed.push((branch_name.clone(), format!("推送失败: {}", e)));
                        } else {
                            synced += 1;
                        }
                    } else {
                        synced += 1;
                    }
                }
                Err(e) => {
                    failed.push((branch_name.clone(), e.to_string()));
                }
            }

            pb.inc(1);
        }

        if current_branch != branches[branches.len() - 1] {
            let _ = self.git.checkout_branch(&current_branch);
        }

        pb.finish_and_clear();

        println!();
        println!("{} 同步完成!", "✓".green().bold());
        println!("  成功: {}", synced.to_string().green());

        if !failed.is_empty() {
            println!("  失败: {}", failed.len().to_string().red());
            println!();
            for (branch, error) in &failed {
                println!("  {}: {}", branch.red(), error.dimmed());
            }
        }

        Ok(())
    }
}

fn looks_like_jira_issue(input: &str, pattern: &str) -> bool {
    get_jira_issue_from_branch(input, pattern).is_some()
}

fn format_relative_time(time: &chrono::DateTime<Utc>) -> String {
    let now = Utc::now();
    let duration = now - *time;

    if duration.num_days() > 365 {
        let years = duration.num_days() / 365;
        format!("{} 年前", years)
    } else if duration.num_days() > 30 {
        let months = duration.num_days() / 30;
        format!("{} 个月前", months)
    } else if duration.num_days() > 0 {
        format!("{} 天前", duration.num_days())
    } else if duration.num_hours() > 0 {
        format!("{} 小时前", duration.num_hours())
    } else if duration.num_minutes() > 0 {
        format!("{} 分钟前", duration.num_minutes())
    } else {
        "刚刚".to_string()
    }
}
