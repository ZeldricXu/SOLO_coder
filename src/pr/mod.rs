use colored::Colorize;
use indicatif::{ProgressBar, ProgressStyle};
use reqwest::header::{HeaderMap, AUTHORIZATION, CONTENT_TYPE};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use tracing::{debug, info};
use webbrowser;

use crate::cli::PrCommands;
use crate::config::Config;
use crate::errors::{GitFlowError, Result};
use crate::git::{extract_jira_issues, parse_commit_for_conventional, CommitInfo, GitRepository};
use crate::jira::JiraClient;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct GitHubPullRequest {
    title: String,
    body: String,
    head: String,
    base: String,
    draft: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct GitHubPullRequestResponse {
    html_url: String,
    number: u64,
    title: String,
    state: String,
    body: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct GitLabMergeRequest {
    title: String,
    description: String,
    source_branch: String,
    target_branch: String,
    draft: bool,
    remove_source_branch: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct GitLabMergeRequestResponse {
    web_url: String,
    iid: u64,
    title: String,
    state: String,
    description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct GitHubReviewerRequest {
    reviewers: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct GitHubLabelsRequest {
    labels: Vec<String>,
}

pub struct PrManager<'a> {
    git: &'a GitRepository,
    config: &'a Config,
    client: Client,
}

impl<'a> PrManager<'a> {
    pub fn new(git: &'a GitRepository, config: &'a Config) -> Result<Self> {
        let client = Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()?;

        Ok(Self {
            git,
            config,
            client,
        })
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
            PrCommands::Status { verbose } => self.status(*verbose).await,
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

        println!();
        println!("{} 创建 Pull Request", "📤".cyan());
        println!();
        println!("  源分支: {} -> 目标分支: {}", current_branch.cyan(), base.yellow());
        println!("  标题: {}", pr_title);
        if !jira_issues.is_empty() {
            println!("  关联JIRA: {}", jira_issues.join(", ").blue());
        }
        println!();

        let pr_url = match platform {
            "github" => {
                self.create_github_pr(&current_branch, base, &pr_title, &pr_body, draft, reviewers, labels)
                    .await?
            }
            "gitlab" => {
                self.create_gitlab_mr(&current_branch, base, &pr_title, &pr_body, draft, reviewers, labels)
                    .await?
            }
            _ => {
                return Err(GitFlowError::GitPlatformError(format!(
                    "不支持的平台: {}",
                    platform
                )))
            }
        };

        println!();
        println!("{} PR 创建成功!", "✓".green().bold());
        println!("  URL: {}", pr_url.underline().blue());
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
            if let Err(e) = webbrowser::open(&pr_url) {
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

    async fn create_github_pr(
        &self,
        head: &str,
        base: &str,
        title: &str,
        body: &str,
        draft: bool,
        reviewers: &[String],
        labels: &[String],
    ) -> Result<String> {
        let github_config = &self.config.git_platform.github;
        let token = github_config
            .api_token
            .as_ref()
            .ok_or_else(|| GitFlowError::GitPlatformError("GitHub API token未配置".into()))?;

        let base_url = github_config
            .base_url
            .clone()
            .unwrap_or_else(|| "https://api.github.com".to_string());

        let owner = github_config
            .owner
            .as_ref()
            .ok_or_else(|| GitFlowError::GitPlatformError("GitHub owner未配置".into()))?;
        let repo = github_config
            .repo
            .as_ref()
            .ok_or_else(|| GitFlowError::GitPlatformError("GitHub repo未配置".into()))?;

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在创建GitHub PR...");

        let url = format!("{}/repos/{}/{}/pulls", base_url, owner, repo);
        debug!("创建PR: {}", url);

        let pr_request = GitHubPullRequest {
            title: title.to_string(),
            body: body.to_string(),
            head: head.to_string(),
            base: base.to_string(),
            draft,
        };

        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());
        headers.insert(AUTHORIZATION, format!("token {}", token).parse().unwrap());
        headers.insert("Accept", "application/vnd.github.v3+json".parse().unwrap());

        let response = self
            .client
            .post(&url)
            .headers(headers.clone())
            .json(&pr_request)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            pb.finish_and_clear();
            return Err(GitFlowError::GitPlatformError(format!(
                "创建PR失败 ({}): {}",
                status, error
            )));
        }

        let pr: GitHubPullRequestResponse = response.json().await?;
        pb.finish_and_clear();

        info!("GitHub PR #{} 已创建", pr.number);

        if !reviewers.is_empty() {
            let reviewer_url = format!(
                "{}/repos/{}/{}/pulls/{}/requested_reviewers",
                base_url, owner, repo, pr.number
            );
            let reviewer_request = GitHubReviewerRequest {
                reviewers: reviewers.to_vec(),
            };
            let _ = self
                .client
                .post(&reviewer_url)
                .headers(headers.clone())
                .json(&reviewer_request)
                .send()
                .await;
        }

        if !labels.is_empty() {
            let labels_url = format!(
                "{}/repos/{}/{}/issues/{}/labels",
                base_url, owner, repo, pr.number
            );
            let labels_request = GitHubLabelsRequest {
                labels: labels.to_vec(),
            };
            let _ = self
                .client
                .post(&labels_url)
                .headers(headers.clone())
                .json(&labels_request)
                .send()
                .await;
        }

        Ok(pr.html_url)
    }

    async fn create_gitlab_mr(
        &self,
        source_branch: &str,
        target_branch: &str,
        title: &str,
        description: &str,
        draft: bool,
        reviewers: &[String],
        labels: &[String],
    ) -> Result<String> {
        let gitlab_config = &self.config.git_platform.gitlab;
        let token = gitlab_config
            .api_token
            .as_ref()
            .ok_or_else(|| GitFlowError::GitPlatformError("GitLab API token未配置".into()))?;

        let base_url = gitlab_config
            .base_url
            .clone()
            .unwrap_or_else(|| "https://gitlab.com".to_string());

        let project_id = gitlab_config
            .project_id
            .as_ref()
            .ok_or_else(|| GitFlowError::GitPlatformError("GitLab project ID未配置".into()))?;

        let pb = ProgressBar::new_spinner();
        pb.set_style(
            ProgressStyle::default_spinner()
                .tick_chars("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏")
                .template("{spinner:.green} {msg}")
                .unwrap(),
        );
        pb.set_message("正在创建GitLab MR...");

        let url = format!("{}/api/v4/projects/{}/merge_requests", base_url, project_id);
        debug!("创建MR: {}", url);

        let mr_request = GitLabMergeRequest {
            title: title.to_string(),
            description: description.to_string(),
            source_branch: source_branch.to_string(),
            target_branch: target_branch.to_string(),
            draft,
            remove_source_branch: true,
        };

        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());
        headers.insert("PRIVATE-TOKEN", token.parse().unwrap());

        let response = self
            .client
            .post(&url)
            .headers(headers.clone())
            .json(&mr_request)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let error = response.text().await.unwrap_or_default();
            pb.finish_and_clear();
            return Err(GitFlowError::GitPlatformError(format!(
                "创建MR失败 ({}): {}",
                status, error
            )));
        }

        let mr: GitLabMergeRequestResponse = response.json().await?;
        pb.finish_and_clear();

        info!("GitLab MR !{} 已创建", mr.iid);

        if !reviewers.is_empty() || !labels.is_empty() {
            let update_url = format!("{}/api/v4/projects/{}/merge_requests/{}", base_url, project_id, mr.iid);
            let mut update_body = serde_json::Map::new();
            if !reviewers.is_empty() {
                update_body.insert("reviewer_ids".to_string(), serde_json::json!([]));
            }
            if !labels.is_empty() {
                update_body.insert("labels".to_string(), serde_json::json!(labels));
            }
            let _ = self
                .client
                .put(&update_url)
                .headers(headers.clone())
                .json(&update_body)
                .send()
                .await;
        }

        Ok(mr.web_url)
    }

    async fn status(&self, verbose: bool) -> Result<()> {
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

        println!("使用以下命令创建PR:");
        println!("  {}", format!("gitflow pr create --base {}", base_branch).dimmed());

        Ok(())
    }
}
