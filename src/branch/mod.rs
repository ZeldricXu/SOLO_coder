use chrono::{Duration, Utc};
use colored::Colorize;
use dialoguer::{theme::ColorfulTheme, Confirm, Select};
use indicatif::{ProgressBar, ProgressStyle};
use std::collections::HashSet;
use tracing::{debug, warn};

use crate::cli::BranchCommands;
use crate::config::{BranchConfig, Config, JiraConfig};
use crate::errors::{GitFlowError, Result};
use crate::git::{get_jira_issue_from_branch, BranchInfo, GitRepository};
use crate::jira::{slugify, JiraClient};

pub struct BranchManager<'a> {
    git: &'a GitRepository,
    config: &'a Config,
}

impl<'a> BranchManager<'a> {
    pub fn new(git: &'a GitRepository, config: &'a Config) -> Self {
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
            } => {
                self.clean(*dry_run, *yes, keep, *age_threshold)
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

        let branch = self.git.create_branch(&branch_name, base, force)?;
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
        base: Option<&str>,
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

    fn clean(
        &self,
        dry_run: bool,
        yes: bool,
        keep: &[String],
        age_threshold: Option<i64>,
    ) -> Result<()> {
        let config = &self.config.branch;
        let age_threshold = age_threshold.unwrap_or(config.clean_age_threshold_days);
        let cutoff_date = Utc::now() - Duration::days(age_threshold);

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在扫描分支...");

        let local_branches = self.git.list_branches(true, false, None, false)?;
        let current_branch = self.git.current_branch().ok();

        let mut branches_to_delete = Vec::new();
        let mut keep_set: HashSet<String> = keep.iter().cloned().collect();
        for protected in &config.protected_branches {
            keep_set.insert(protected.clone());
        }

        for branch in local_branches {
            if branch.is_current {
                continue;
            }

            if keep_set.contains(&branch.short_name) {
                debug!("跳过受保护的分支: {}", branch.name);
                continue;
            }

            let is_merged = self
                .git
                .is_branch_name_merged(&branch.short_name, &config.protected_branches[0])
                .unwrap_or(false);

            let is_old = branch.last_commit_time < cutoff_date;

            if is_merged || is_old {
                branches_to_delete.push((branch, is_merged, is_old));
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
        println!("  年龄阈值: {} 天", age_threshold);
        println!();

        for (branch, is_merged, is_old) in &branches_to_delete {
            let mut reasons = Vec::new();
            if *is_merged {
                reasons.push("已合并".green().to_string());
            }
            if *is_old {
                reasons.push("过期".red().to_string());
            }

            println!(
                "  {} - {} (最后提交: {})",
                branch.short_name.cyan(),
                reasons.join(", "),
                format_relative_time(&branch.last_commit_time)
            );
        }

        println!();

        if dry_run {
            println!("{} 模拟模式，不会实际删除任何分支", "ℹ".blue());
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

        for (branch, _, _) in &branches_to_delete {
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
                        if let Err(e) = self.git.push(remote, branch_name, false) {
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
