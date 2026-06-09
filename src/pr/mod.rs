use colored::Colorize;
use indicatif::{ProgressBar, ProgressStyle};
use reqwest::Client;
use std::collections::HashSet;
use std::sync::Arc;
use tracing::warn;
use webbrowser;

use crate::command::{CommandHandler, ModuleCommand};
use crate::config::Config;
use crate::context::AppContext;
use crate::errors::{GitFlowError, Result};
use crate::git::{extract_jira_issues, parse_commit_for_conventional, CommitInfo, GitContext};
use crate::git_hosting::{create_hosting_client, PullRequest as GitHostingPullRequest};
use crate::jira::JiraClient;

pub mod cli {
    use clap::Subcommand;

    use super::*;

    #[derive(Subcommand, Debug, Clone)]
    pub enum PrCommands {
        #[command(about = "从当前分支创建PR")]
        Create {
            #[arg(short, long, help = "目标分支", default_value = "main")]
            base: String,

            #[arg(short, long, help = "PR标题")]
            title: Option<String>,

            #[arg(short = 'm', long, help = "PR描述")]
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

            #[arg(long, help = "平台: auto/github/gitlab/bitbucket", default_value = "auto", value_parser = ["auto", "github", "gitlab", "bitbucket"])]
            platform: String,
        },

        #[command(about = "查看当前分支的PR状态")]
        Status {
            #[arg(short, long, help = "显示详细信息")]
            verbose: bool,
        },
    }

    pub struct PrHandler {
        ctx: AppContext,
        cmd: PrCommands,
    }

    impl PrHandler {
        pub fn new(ctx: AppContext, cmd: PrCommands) -> Self {
            Self { ctx, cmd }
        }
    }

    #[async_trait::async_trait]
    impl CommandHandler for PrHandler {
        async fn handle(&self) -> Result<()> {
            let config = self.ctx.config.get().await;
            let manager = PrManager::new(self.ctx.git.clone(), config, self.ctx.http_client.clone());
            manager.handle(&self.cmd).await
        }
    }

    pub struct PrModule;

    impl ModuleCommand for PrModule {
        type Command = PrCommands;
        type Handler = PrHandler;

        fn name() -> &'static str {
            "pr"
        }

        fn about() -> &'static str {
            "PR工作流 - 一键创建Pull Request"
        }

        fn create_handler(ctx: crate::context::AppContext, cmd: &Self::Command) -> Result<Self::Handler> {
            Ok(PrHandler::new(ctx, cmd.clone()))
        }
    }
}

pub use cli::{PrCommands, PrHandler, PrModule};

pub struct PrManager {
    git: Arc<GitContext>,
    config: Config,
    client: Client,
}

impl PrManager {
    pub fn new(git: Arc<GitContext>, config: Config, client: Client) -> Self {
        Self { git, config, client }
    }

    pub async fn handle(&self, command: &PrCommands) -> Result<()> {
        match command {
            PrCommands::Create {
                base,
                title,
                body,
                reviewers,
                labels,
                draft,
                issue,
                open,
                platform,
            } => {
                self.create(
                    base,
                    title.as_deref(),
                    body.as_deref(),
                    reviewers,
                    labels,
                    *draft,
                    issue.as_deref(),
                    *open,
                    platform,
                )
                .await
            }
            PrCommands::Status { verbose } => self.status(*verbose),
        }
    }

    async fn create(
        &self,
        base: &str,
        title: Option<&str>,
        body: Option<&str>,
        reviewers: &[String],
        labels: &[String],
        draft: bool,
        issue: Option<&str>,
        open_in_browser: bool,
        platform: &str,
    ) -> Result<()> {
        let current_branch = self.git.current_branch()?;

        if current_branch == base {
            return Err(GitFlowError::Other(format!(
                "不能从 '{}' 创建PR到 '{}'，请切换到特性分支",
                current_branch, base
            )));
        }

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在准备PR内容...");

        let commits = self
            .git
            .get_commit_range(Some(base), Some(&current_branch))?;

        let jira_issues = self.extract_jira_issues(&current_branch, issue, &commits);
        let pr_title = self.generate_pr_title(title, &current_branch, &commits)?;
        let pr_body = self.generate_pr_body(body, &commits, &jira_issues)?;

        pb.finish_and_clear();

        let hosting = create_hosting_client(&self.config, platform, self.client.clone())?;

        println!();
        println!("{} 创建 Pull Request", "📤".cyan());
        println!();
        println!("  平台: {:?}", hosting.platform());
        println!("  源分支: {} -> 目标分支: {}", current_branch.cyan(), base.yellow());
        println!("  标题: {}", pr_title);
        if !jira_issues.is_empty() {
            println!("  关联JIRA: {}", jira_issues.join(", ").blue());
        }
        println!();

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message(format!("正在创建PR..."));
        pb.enable_steady_tick(std::time::Duration::from_millis(100));

        let pr = GitHostingPullRequest {
            title: pr_title,
            body: pr_body,
            head: current_branch.clone(),
            base: base.to_string(),
            draft,
            reviewers: reviewers.to_vec(),
            labels: labels.to_vec(),
        };

        let pr_resp = hosting.create_pull_request(&pr).await?;

        pb.finish_and_clear();

        println!();
        println!("{} PR 创建成功!", "✓".green().bold());
        println!("  URL: {}", pr_resp.url.underline().blue());
        println!();

        if self.config.jira.auto_transition && !jira_issues.is_empty() {
            if let Ok(jira) = JiraClient::new(self.config.jira.clone()) {
                for issue_key in &jira_issues {
                    let _ = jira
                        .transition_issue(issue_key, "21", Some("已创建PR，待代码审查"))
                        .await;
                }
            }
        }

        if open_in_browser {
            if let Err(e) = webbrowser::open(&pr_resp.url) {
                warn!("无法在浏览器中打开PR: {}", e);
            }
        }

        Ok(())
    }

    fn extract_jira_issues(
        &self,
        branch_name: &str,
        explicit_issue: Option<&str>,
        commits: &[CommitInfo],
    ) -> Vec<String> {
        let mut issues = HashSet::new();

        if let Some(issue) = explicit_issue {
            issues.insert(issue.to_string());
        }

        if self.config.pr.auto_link_jira {
            let pattern = &self.config.branch.jira_issue_pattern;

            if let Some(issue) =
                crate::git::get_jira_issue_from_branch(branch_name, pattern)
            {
                issues.insert(issue);
            }

            for commit in commits {
                let commit_issues = extract_jira_issues(&commit.message, pattern);
                for issue in commit_issues {
                    issues.insert(issue);
                }
            }
        }

        issues.into_iter().collect()
    }

    fn generate_pr_title(
        &self,
        explicit_title: Option<&str>,
        branch_name: &str,
        commits: &[CommitInfo],
    ) -> Result<String> {
        if let Some(title) = explicit_title {
            return Ok(title.to_string());
        }

        if let Some(first_commit) = commits.first() {
            if let Some(conv) = parse_commit_for_conventional(&first_commit.message) {
                let mut title = String::new();
                title.push_str(&conv.r#type);
                if let Some(scope) = &conv.scope {
                    title.push_str(&format!("({})", scope));
                }
                title.push_str(": ");
                title.push_str(&conv.subject);
                return Ok(title);
            }
            return Ok(first_commit.summary.clone());
        }

        Ok(branch_name.to_string())
    }

    fn generate_pr_body(
        &self,
        explicit_body: Option<&str>,
        commits: &[CommitInfo],
        jira_issues: &[String],
    ) -> Result<String> {
        if let Some(body) = explicit_body {
            return Ok(body.to_string());
        }

        let template = &self.config.pr.template;
        let mut description = String::new();

        if self.config.pr.include_commit_summary && !commits.is_empty() {
            description.push_str("## 提交摘要\n\n");
            for commit in commits {
                description.push_str(&format!("- `{}` {} - {}\n", &commit.short_sha[..7], commit.summary, commit.author));
            }
            description.push('\n');
        }

        let jira_section = if !jira_issues.is_empty() {
            let jira_links: Vec<String> = if self.config.jira.enabled && self.config.jira.base_url.is_some() {
                let base_url = self.config.jira.base_url.as_ref().unwrap();
                jira_issues
                    .iter()
                    .map(|i| format!("[{}]({}/browse/{})", i, base_url.trim_end_matches('/'), i))
                    .collect()
            } else {
                jira_issues.iter().map(|i| format!("`{}`", i)).collect()
            };
            jira_links.join(", ")
        } else {
            "_无关联Issue_".to_string()
        };

        let body = template
            .replace("{description}", &description)
            .replace("{jira_issue}", &jira_section)
            .replace("{notes}", "_请在此处添加额外说明_");

        Ok(body)
    }

    fn status(&self, verbose: bool) -> Result<()> {
        let current_branch = self.git.current_branch()?;
        let base_branch = &self.config.pr.default_base;

        let commits = self
            .git
            .get_commit_range(Some(base_branch), Some(&current_branch))?;

        println!();
        println!("{} PR状态", "📊".cyan());
        println!();
        println!("  当前分支: {}", current_branch.cyan());
        println!("  基准分支: {}", base_branch.yellow());
        println!("  待提交数: {}", commits.len().to_string().blue());
        println!();

        if verbose && !commits.is_empty() {
            println!("{}", "待提交列表:".bold());
            for commit in &commits {
                println!(
                    "  {} {} - {} ({})",
                    commit.short_sha[..7].dimmed(),
                    commit.summary,
                    commit.author,
                    commit.time.format("%Y-%m-%d")
                );
            }
            println!();
        }

        let jira_issues = self.extract_jira_issues(&current_branch, None, &commits);
        if !jira_issues.is_empty() {
            println!("{} 关联的JIRA issues:", "🔗".blue());
            for issue in &jira_issues {
                println!("  - {}", issue);
            }
            println!();
        }

        if let Ok(remote) = self.git.get_remote_url(&self.config.general.default_remote) {
            let platform = crate::git_hosting::detect_platform_from_remote(&remote);
            println!("  检测到Git平台: {:?}", platform);
            println!();
        }

        println!("使用以下命令创建PR:");
        println!("  {}", format!("gitflow pr create --base {} --platform auto", base_branch).dimmed());

        Ok(())
    }
}
