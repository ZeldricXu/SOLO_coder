use async_trait::async_trait;
use reqwest::header::{HeaderMap, AUTHORIZATION, CONTENT_TYPE};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{debug, info};

use crate::config::Config;
use crate::errors::{GitFlowError, Result};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PullRequest {
    pub title: String,
    pub body: String,
    pub head: String,
    pub base: String,
    pub draft: bool,
    pub reviewers: Vec<String>,
    pub labels: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PullRequestResponse {
    pub url: String,
    pub number: u64,
    pub title: String,
    pub state: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GitPlatform {
    GitHub,
    GitLab,
    Bitbucket,
    Unknown,
}

#[async_trait]
pub trait GitHosting {
    async fn create_pull_request(&self, pr: &PullRequest) -> Result<PullRequestResponse>;
    fn platform(&self) -> GitPlatform;
}

pub fn detect_platform_from_remote(remote_url: &str) -> GitPlatform {
    let url_lower = remote_url.to_lowercase();
    if url_lower.contains("github.com") || url_lower.contains("github.") {
        GitPlatform::GitHub
    } else if url_lower.contains("gitlab.com") || url_lower.contains("gitlab.") {
        GitPlatform::GitLab
    } else if url_lower.contains("bitbucket.org") || url_lower.contains("bitbucket.") {
        GitPlatform::Bitbucket
    } else {
        GitPlatform::Unknown
    }
}

pub fn create_hosting_client(
    config: &Config,
    platform: &str,
    client: Client,
) -> Result<Box<dyn GitHosting + Send + Sync>> {
    match platform {
        "github" | "auto" => {
            if platform == "auto" {
                if let Ok(repo) = crate::git::GitRepository::open(None) {
                    if let Ok(remote) = repo.get_remote_url(&config.general.default_remote) {
                        match detect_platform_from_remote(&remote) {
                            GitPlatform::GitHub => return Ok(Box::new(GitHubClient::new(config, client)?)),
                            GitPlatform::GitLab => return Ok(Box::new(GitLabClient::new(config, client)?)),
                            GitPlatform::Bitbucket => return Ok(Box::new(BitbucketClient::new(config, client)?)),
                            _ => {}
                        }
                    }
                }
            }
            Ok(Box::new(GitHubClient::new(config, client)?))
        }
        "gitlab" => Ok(Box::new(GitLabClient::new(config, client)?)),
        "bitbucket" => Ok(Box::new(BitbucketClient::new(config, client)?)),
        _ => Err(GitFlowError::GitPlatformError(format!(
            "不支持的平台: {}",
            platform
        ))),
    }
}

pub struct GitHubClient {
    client: Client,
    config: GitHubConfigInner,
}

struct GitHubConfigInner {
    token: String,
    base_url: String,
    owner: String,
    repo: String,
}

impl GitHubClient {
    pub fn new(config: &Config, client: Client) -> Result<Self> {
        let gh_config = &config.git_platform.github;
        Ok(Self {
            client,
            config: GitHubConfigInner {
                token: gh_config
                    .api_token
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("GitHub API token未配置".into()))?,
                base_url: gh_config
                    .base_url
                    .clone()
                    .unwrap_or_else(|| "https://api.github.com".to_string()),
                owner: gh_config
                    .owner
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("GitHub owner未配置".into()))?,
                repo: gh_config
                    .repo
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("GitHub repo未配置".into()))?,
            },
        })
    }
}

#[derive(Serialize)]
struct GitHubPullRequestRequest {
    title: String,
    body: String,
    head: String,
    base: String,
    draft: bool,
}

#[derive(Deserialize)]
struct GitHubPullRequestResponseInner {
    html_url: String,
    number: u64,
    title: String,
    state: String,
}

#[async_trait]
impl GitHosting for GitHubClient {
    async fn create_pull_request(&self, pr: &PullRequest) -> Result<PullRequestResponse> {
        let url = format!(
            "{}/repos/{}/{}/pulls",
            self.config.base_url, self.config.owner, self.config.repo
        );
        debug!("创建GitHub PR: {}", url);

        let pr_request = GitHubPullRequestRequest {
            title: pr.title.clone(),
            body: pr.body.clone(),
            head: pr.head.clone(),
            base: pr.base.clone(),
            draft: pr.draft,
        };

        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());
        headers.insert(
            AUTHORIZATION,
            format!("token {}", self.config.token).parse().unwrap(),
        );
        headers.insert(
            "Accept",
            "application/vnd.github.v3+json".parse().unwrap(),
        );

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
            return Err(GitFlowError::GitPlatformError(format!(
                "创建GitHub PR失败 ({}): {}",
                status, error
            )));
        }

        let pr_resp: GitHubPullRequestResponseInner = response.json().await?;
        info!("GitHub PR #{} 已创建", pr_resp.number);

        if !pr.reviewers.is_empty() {
            let reviewer_url = format!(
                "{}/repos/{}/{}/pulls/{}/requested_reviewers",
                self.config.base_url, self.config.owner, self.config.repo, pr_resp.number
            );
            let mut body = HashMap::new();
            body.insert("reviewers", &pr.reviewers);
            let _ = self
                .client
                .post(&reviewer_url)
                .headers(headers.clone())
                .json(&body)
                .send()
                .await;
        }

        if !pr.labels.is_empty() {
            let labels_url = format!(
                "{}/repos/{}/{}/issues/{}/labels",
                self.config.base_url, self.config.owner, self.config.repo, pr_resp.number
            );
            let mut body = HashMap::new();
            body.insert("labels", &pr.labels);
            let _ = self
                .client
                .post(&labels_url)
                .headers(headers.clone())
                .json(&body)
                .send()
                .await;
        }

        Ok(PullRequestResponse {
            url: pr_resp.html_url,
            number: pr_resp.number,
            title: pr_resp.title,
            state: pr_resp.state,
        })
    }

    fn platform(&self) -> GitPlatform {
        GitPlatform::GitHub
    }
}

pub struct GitLabClient {
    client: Client,
    config: GitLabConfigInner,
}

struct GitLabConfigInner {
    token: String,
    base_url: String,
    project_id: String,
}

impl GitLabClient {
    pub fn new(config: &Config, client: Client) -> Result<Self> {
        let gl_config = &config.git_platform.gitlab;
        Ok(Self {
            client,
            config: GitLabConfigInner {
                token: gl_config
                    .api_token
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("GitLab API token未配置".into()))?,
                base_url: gl_config
                    .base_url
                    .clone()
                    .unwrap_or_else(|| "https://gitlab.com".to_string()),
                project_id: gl_config
                    .project_id
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("GitLab project ID未配置".into()))?,
            },
        })
    }
}

#[derive(Serialize)]
struct GitLabMergeRequestRequest {
    title: String,
    description: String,
    source_branch: String,
    target_branch: String,
    draft: bool,
    remove_source_branch: bool,
}

#[derive(Deserialize)]
struct GitLabMergeRequestResponseInner {
    web_url: String,
    iid: u64,
    title: String,
    state: String,
}

#[async_trait]
impl GitHosting for GitLabClient {
    async fn create_pull_request(&self, pr: &PullRequest) -> Result<PullRequestResponse> {
        let url = format!(
            "{}/api/v4/projects/{}/merge_requests",
            self.config.base_url, self.config.project_id
        );
        debug!("创建GitLab MR: {}", url);

        let mr_request = GitLabMergeRequestRequest {
            title: pr.title.clone(),
            description: pr.body.clone(),
            source_branch: pr.head.clone(),
            target_branch: pr.base.clone(),
            draft: pr.draft,
            remove_source_branch: true,
        };

        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());
        headers.insert("PRIVATE-TOKEN", self.config.token.parse().unwrap());

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
            return Err(GitFlowError::GitPlatformError(format!(
                "创建GitLab MR失败 ({}): {}",
                status, error
            )));
        }

        let mr_resp: GitLabMergeRequestResponseInner = response.json().await?;
        info!("GitLab MR !{} 已创建", mr_resp.iid);

        if !pr.reviewers.is_empty() || !pr.labels.is_empty() {
            let update_url = format!(
                "{}/api/v4/projects/{}/merge_requests/{}",
                self.config.base_url, self.config.project_id, mr_resp.iid
            );
            let mut body = HashMap::new();
            if !pr.labels.is_empty() {
                body.insert("labels", pr.labels.join(","));
            }
            let _ = self
                .client
                .put(&update_url)
                .headers(headers.clone())
                .json(&body)
                .send()
                .await;
        }

        Ok(PullRequestResponse {
            url: mr_resp.web_url,
            number: mr_resp.iid,
            title: mr_resp.title,
            state: mr_resp.state,
        })
    }

    fn platform(&self) -> GitPlatform {
        GitPlatform::GitLab
    }
}

pub struct BitbucketClient {
    client: Client,
    config: BitbucketConfigInner,
}

struct BitbucketConfigInner {
    token: String,
    base_url: String,
    workspace: String,
    repo_slug: String,
}

impl BitbucketClient {
    pub fn new(config: &Config, client: Client) -> Result<Self> {
        let bb_config = &config.git_platform.bitbucket;
        Ok(Self {
            client,
            config: BitbucketConfigInner {
                token: bb_config
                    .api_token
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("Bitbucket API token未配置".into()))?,
                base_url: bb_config
                    .base_url
                    .clone()
                    .unwrap_or_else(|| "https://api.bitbucket.org/2.0".to_string()),
                workspace: bb_config
                    .workspace
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("Bitbucket workspace未配置".into()))?,
                repo_slug: bb_config
                    .repo_slug
                    .clone()
                    .ok_or_else(|| GitFlowError::GitPlatformError("Bitbucket repo slug未配置".into()))?,
            },
        })
    }
}

#[derive(Serialize)]
struct BitbucketPullRequestRequest {
    title: String,
    description: String,
    source: BitbucketBranchRef,
    destination: BitbucketBranchRef,
    draft: bool,
}

#[derive(Serialize)]
struct BitbucketBranchRef {
    branch: BitbucketBranchName,
}

#[derive(Serialize)]
struct BitbucketBranchName {
    name: String,
}

#[derive(Deserialize)]
struct BitbucketPullRequestResponseInner {
    links: BitbucketLinks,
    id: u64,
    title: String,
    state: String,
}

#[derive(Deserialize)]
struct BitbucketLinks {
    html: BitbucketLink,
}

#[derive(Deserialize)]
struct BitbucketLink {
    href: String,
}

#[async_trait]
impl GitHosting for BitbucketClient {
    async fn create_pull_request(&self, pr: &PullRequest) -> Result<PullRequestResponse> {
        let url = format!(
            "{}/repositories/{}/{}/pullrequests",
            self.config.base_url, self.config.workspace, self.config.repo_slug
        );
        debug!("创建Bitbucket PR: {}", url);

        let pr_request = BitbucketPullRequestRequest {
            title: pr.title.clone(),
            description: pr.body.clone(),
            source: BitbucketBranchRef {
                branch: BitbucketBranchName { name: pr.head.clone() },
            },
            destination: BitbucketBranchRef {
                branch: BitbucketBranchName { name: pr.base.clone() },
            },
            draft: pr.draft,
        };

        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_TYPE, "application/json".parse().unwrap());
        headers.insert(
            AUTHORIZATION,
            format!("Bearer {}", self.config.token).parse().unwrap(),
        );

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
            return Err(GitFlowError::GitPlatformError(format!(
                "创建Bitbucket PR失败 ({}): {}",
                status, error
            )));
        }

        let pr_resp: BitbucketPullRequestResponseInner = response.json().await?;
        info!("Bitbucket PR #{} 已创建", pr_resp.id);

        if !pr.reviewers.is_empty() {
            for reviewer in &pr.reviewers {
                let reviewer_url = format!(
                    "{}/repositories/{}/{}/pullrequests/{}/participants",
                    self.config.base_url, self.config.workspace, self.config.repo_slug, pr_resp.id
                );
                let mut body: HashMap<&str, &str> = HashMap::new();
                body.insert("username", reviewer.as_str());
                body.insert("role", "reviewer");
                let _ = self
                    .client
                    .post(&reviewer_url)
                    .headers(headers.clone())
                    .json(&body)
                    .send()
                    .await;
            }
        }

        Ok(PullRequestResponse {
            url: pr_resp.links.html.href,
            number: pr_resp.id,
            title: pr_resp.title,
            state: pr_resp.state,
        })
    }

    fn platform(&self) -> GitPlatform {
        GitPlatform::Bitbucket
    }
}
