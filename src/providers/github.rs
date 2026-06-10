use async_trait::async_trait;
use chrono::{DateTime, NaiveDateTime, Utc};
use reqwest::{header, Client, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tracing::debug;

use crate::utils::{AppError, AppResult};

use super::{
    GitProvider, MergeRequestState, MergeRequestStatus, PaginationParams, PaginatedResponse,
    ProviderComment, ProviderDiff, ProviderDiffFile, ProviderMergeRequest, ProviderRepository,
    ProviderUser, ProviderWebhook,
};

const DEFAULT_API_BASE: &str = "https://api.github.com";

#[derive(Debug, Clone)]
pub struct GitHubProvider {
    client: Client,
    api_base: String,
    access_token: String,
}

impl GitHubProvider {
    pub fn new(access_token: impl Into<String>) -> Self {
        Self::with_base_url(access_token, DEFAULT_API_BASE)
    }

    pub fn with_base_url(access_token: impl Into<String>, api_base: impl Into<String>) -> Self {
        let client = Client::new();
        Self {
            client,
            api_base: api_base.into(),
            access_token: access_token.into(),
        }
    }

    fn auth_headers(&self) -> header::HeaderMap {
        let mut headers = header::HeaderMap::new();
        headers.insert(
            header::AUTHORIZATION,
            header::HeaderValue::from_str(&format!("token {}", self.access_token)).unwrap(),
        );
        headers.insert(
            header::USER_AGENT,
            header::HeaderValue::from_static("code-review-platform"),
        );
        headers.insert(
            header::ACCEPT,
            header::HeaderValue::from_static("application/vnd.github.v3+json"),
        );
        headers
    }

    fn parse_timestamp(ts: &str) -> AppResult<DateTime<Utc>> {
        NaiveDateTime::parse_from_str(ts, "%Y-%m-%dT%H:%M:%SZ")
            .map(|dt| dt.and_utc())
            .map_err(|e| AppError::Parse(format!("Failed to parse timestamp: {}", e)))
    }

    fn parse_link_header(link_header: &str) -> Option<i64> {
        for part in link_header.split(',') {
            let part = part.trim();
            if part.ends_with("rel=\"last\"") {
                if let Some(start) = part.find("page=") {
                    if let Some(end) = part[start..].find('>') {
                        let page_str = &part[start + 5..start + end];
                        return page_str.parse::<i64>().ok();
                    }
                }
            }
        }
        None
    }

    async fn get<T: for<'de> Deserialize<'de>>(&self, path: &str, query: Option<&[(&str, String)]>) -> AppResult<(T, Option<i64>)> {
        let url = format!("{}{}", self.api_base, path);
        debug!("GET {}", url);

        let mut request = self.client.get(&url).headers(self.auth_headers());
        if let Some(query) = query {
            request = request.query(query);
        }

        let response = request.send().await.map_err(|e| {
            AppError::ExternalService(format!("GitHub API request failed: {}", e))
        })?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::NOT_FOUND => AppError::NotFound(format!("GitHub resource not found: {}", error_body)),
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("GitHub authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("GitHub authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("GitHub API error ({}): {}", status, error_body)),
            });
        }

        let link_header = response.headers()
            .get(header::LINK)
            .and_then(|v| v.to_str().ok())
            .and_then(Self::parse_link_header);

        let data: T = response.json().await.map_err(|e| {
            AppError::Serialization(e)
        })?;

        Ok((data, link_header))
    }

    async fn post<T: Serialize, R: for<'de> Deserialize<'de>>(&self, path: &str, body: &T) -> AppResult<R> {
        let url = format!("{}{}", self.api_base, path);
        debug!("POST {}", url);

        let response = self
            .client
            .post(&url)
            .headers(self.auth_headers())
            .json(body)
            .send()
            .await
            .map_err(|e| AppError::ExternalService(format!("GitHub API request failed: {}", e)))?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("GitHub authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("GitHub authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("GitHub API error ({}): {}", status, error_body)),
            });
        }

        let data: R = response.json().await.map_err(|e| AppError::Serialization(e))?;
        Ok(data)
    }

    async fn delete(&self, path: &str) -> AppResult<()> {
        let url = format!("{}{}", self.api_base, path);
        debug!("DELETE {}", url);

        let response = self
            .client
            .delete(&url)
            .headers(self.auth_headers())
            .send()
            .await
            .map_err(|e| AppError::ExternalService(format!("GitHub API request failed: {}", e)))?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::NOT_FOUND => AppError::NotFound(format!("GitHub resource not found: {}", error_body)),
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("GitHub authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("GitHub authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("GitHub API error ({}): {}", status, error_body)),
            });
        }

        Ok(())
    }
}

#[derive(Debug, Deserialize)]
struct GitHubUser {
    id: i64,
    login: String,
    email: Option<String>,
    avatar_url: String,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GitHubRepo {
    id: i64,
    name: String,
    full_name: String,
    description: Option<String>,
    html_url: String,
    clone_url: String,
    ssh_url: String,
    default_branch: String,
    private: bool,
    created_at: String,
    updated_at: String,
    owner: GitHubUser,
}

#[derive(Debug, Deserialize)]
struct GitHubPullRequest {
    id: i64,
    number: i64,
    title: String,
    body: Option<String>,
    state: String,
    html_url: String,
    head: GitHubBranch,
    base: GitHubBranch,
    user: GitHubUser,
    created_at: String,
    updated_at: String,
    merged_at: Option<String>,
    merged_by: Option<GitHubUser>,
    labels: Vec<GitHubLabel>,
}

#[derive(Debug, Deserialize)]
struct GitHubBranch {
    #[serde(rename = "ref")]
    ref_name: String,
    sha: String,
}

#[derive(Debug, Deserialize)]
struct GitHubLabel {
    name: String,
}

#[derive(Debug, Deserialize)]
struct GitHubDiffFile {
    filename: String,
    status: String,
    additions: i64,
    deletions: i64,
    changes: i64,
    patch: Option<String>,
    previous_filename: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GitHubWebhook {
    id: i64,
    url: Option<String>,
    events: Vec<String>,
    active: bool,
    created_at: String,
    config: Option<GitHubWebhookConfig>,
}

#[derive(Debug, Deserialize)]
struct GitHubWebhookConfig {
    url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GitHubComment {
    id: i64,
    body: String,
    user: GitHubUser,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, Serialize)]
struct CreateWebhookRequest {
    name: String,
    config: CreateWebhookConfig,
    events: Vec<String>,
    active: bool,
}

#[derive(Debug, Serialize)]
struct CreateWebhookConfig {
    url: String,
    content_type: String,
    secret: String,
}

#[derive(Debug, Serialize)]
struct CreateCommentRequest {
    body: String,
}

#[derive(Debug, Serialize)]
struct CreateStatusRequest {
    state: String,
    target_url: Option<String>,
    description: Option<String>,
    context: String,
}

impl From<GitHubUser> for ProviderUser {
    fn from(user: GitHubUser) -> Self {
        Self {
            id: user.id.to_string(),
            username: user.login,
            email: user.email,
            avatar_url: Some(user.avatar_url),
            name: user.name,
        }
    }
}

impl From<GitHubRepo> for ProviderRepository {
    fn from(repo: GitHubRepo) -> Self {
        Self {
            id: repo.id.to_string(),
            name: repo.name,
            full_name: repo.full_name,
            description: repo.description,
            html_url: repo.html_url,
            clone_url: repo.clone_url,
            ssh_url: repo.ssh_url,
            default_branch: repo.default_branch,
            is_private: repo.private,
            created_at: Self::parse_ts(&repo.created_at),
            updated_at: Self::parse_ts(&repo.updated_at),
            owner: repo.owner.into(),
        }
    }
}

impl ProviderRepository {
    fn parse_ts(ts: &str) -> DateTime<Utc> {
        GitHubProvider::parse_timestamp(ts).unwrap_or_else(|_| Utc::now())
    }
}

impl From<GitHubPullRequest> for ProviderMergeRequest {
    fn from(pr: GitHubPullRequest) -> Self {
        Self {
            id: pr.id.to_string(),
            number: pr.number,
            title: pr.title,
            description: pr.body,
            state: pr.state,
            source_branch: pr.head.ref_name,
            target_branch: pr.base.ref_name,
            html_url: pr.html_url,
            author: pr.user.into(),
            created_at: GitHubProvider::parse_timestamp(&pr.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GitHubProvider::parse_timestamp(&pr.updated_at).unwrap_or_else(|_| Utc::now()),
            merged_at: pr.merged_at.as_ref().and_then(|ts| GitHubProvider::parse_timestamp(ts).ok()),
            merged_by: pr.merged_by.map(|u| u.into()),
            labels: pr.labels.into_iter().map(|l| l.name).collect(),
        }
    }
}

impl From<GitHubWebhook> for ProviderWebhook {
    fn from(webhook: GitHubWebhook) -> Self {
        let url = webhook
            .config
            .and_then(|c| c.url)
            .or(webhook.url)
            .unwrap_or_default();
        Self {
            id: webhook.id.to_string(),
            url,
            events: webhook.events,
            active: webhook.active,
            created_at: GitHubProvider::parse_timestamp(&webhook.created_at).unwrap_or_else(|_| Utc::now()),
        }
    }
}

impl From<GitHubComment> for ProviderComment {
    fn from(comment: GitHubComment) -> Self {
        Self {
            id: comment.id.to_string(),
            body: comment.body,
            author: comment.user.into(),
            created_at: GitHubProvider::parse_timestamp(&comment.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GitHubProvider::parse_timestamp(&comment.updated_at).unwrap_or_else(|_| Utc::now()),
        }
    }
}

#[async_trait]
impl GitProvider for GitHubProvider {
    async fn get_user_info(&self) -> AppResult<ProviderUser> {
        let (user, _): (GitHubUser, _) = self.get("/user", None).await?;
        Ok(user.into())
    }

    async fn get_repositories(
        &self,
        pagination: PaginationParams,
    ) -> AppResult<PaginatedResponse<ProviderRepository>> {
        let query = vec![
            ("per_page", pagination.per_page.to_string()),
            ("page", pagination.page.to_string()),
            ("sort", "updated".to_string()),
            ("affiliation", "owner,collaborator,organization_member".to_string()),
        ];

        let (repos, last_page): (Vec<GitHubRepo>, _) = self.get("/user/repos", Some(&query)).await?;

        let total_pages = last_page.unwrap_or(pagination.page as i64);
        let total = total_pages * pagination.per_page as i64;

        let items: Vec<ProviderRepository> = repos.into_iter().map(|r| r.into()).collect();

        Ok(PaginatedResponse {
            items,
            total,
            page: pagination.page,
            per_page: pagination.per_page,
            total_pages,
        })
    }

    async fn get_repository(&self, repo_full_name: &str) -> AppResult<ProviderRepository> {
        let path = format!("/repos/{}", repo_full_name);
        let (repo, _): (GitHubRepo, _) = self.get(&path, None).await?;
        Ok(repo.into())
    }

    async fn get_merge_request(
        &self,
        repo_full_name: &str,
        mr_number: i64,
    ) -> AppResult<ProviderMergeRequest> {
        let path = format!("/repos/{}/pulls/{}", repo_full_name, mr_number);
        let (pr, _): (GitHubPullRequest, _) = self.get(&path, None).await?;
        Ok(pr.into())
    }

    async fn get_merge_requests(
        &self,
        repo_full_name: &str,
        state: Option<MergeRequestState>,
        pagination: PaginationParams,
    ) -> AppResult<PaginatedResponse<ProviderMergeRequest>> {
        let state_str = state.map(|s| s.as_str().to_string()).unwrap_or_else(|| "all".to_string());
        let query = vec![
            ("state", state_str),
            ("per_page", pagination.per_page.to_string()),
            ("page", pagination.page.to_string()),
            ("sort", "updated".to_string()),
            ("direction", "desc".to_string()),
        ];

        let path = format!("/repos/{}/pulls", repo_full_name);
        let (prs, last_page): (Vec<GitHubPullRequest>, _) = self.get(&path, Some(&query)).await?;

        let total_pages = last_page.unwrap_or(pagination.page as i64);
        let total = total_pages * pagination.per_page as i64;

        let items: Vec<ProviderMergeRequest> = prs.into_iter().map(|pr| pr.into()).collect();

        Ok(PaginatedResponse {
            items,
            total,
            page: pagination.page,
            per_page: pagination.per_page,
            total_pages,
        })
    }

    async fn get_diff(
        &self,
        repo_full_name: &str,
        mr_number: i64,
    ) -> AppResult<ProviderDiff> {
        let path = format!("/repos/{}/pulls/{}/files", repo_full_name, mr_number);
        let (files, _): (Vec<GitHubDiffFile>, _) = self.get(&path, None).await?;

        let diff_files: Vec<ProviderDiffFile> = files
            .into_iter()
            .map(|f| ProviderDiffFile {
                path: f.filename.clone(),
                old_path: f.previous_filename.clone(),
                new_path: Some(f.filename),
                status: f.status,
                additions: f.additions,
                deletions: f.deletions,
                changes: f.changes,
                patch: f.patch.unwrap_or_default(),
            })
            .collect();

        Ok(ProviderDiff { files: diff_files })
    }

    async fn create_webhook(
        &self,
        repo_full_name: &str,
        url: &str,
        events: &[String],
        secret: &str,
    ) -> AppResult<ProviderWebhook> {
        let path = format!("/repos/{}/hooks", repo_full_name);
        let body = CreateWebhookRequest {
            name: "web".to_string(),
            config: CreateWebhookConfig {
                url: url.to_string(),
                content_type: "json".to_string(),
                secret: secret.to_string(),
            },
            events: events.to_vec(),
            active: true,
        };

        let webhook: GitHubWebhook = self.post(&path, &body).await?;
        Ok(webhook.into())
    }

    async fn delete_webhook(&self, repo_full_name: &str, webhook_id: &str) -> AppResult<()> {
        let path = format!("/repos/{}/hooks/{}", repo_full_name, webhook_id);
        self.delete(&path).await
    }

    async fn add_comment(
        &self,
        repo_full_name: &str,
        mr_number: i64,
        body: &str,
    ) -> AppResult<ProviderComment> {
        let path = format!("/repos/{}/issues/{}/comments", repo_full_name, mr_number);
        let body = CreateCommentRequest {
            body: body.to_string(),
        };

        let comment: GitHubComment = self.post(&path, &body).await?;
        Ok(comment.into())
    }

    async fn update_merge_request_status(
        &self,
        repo_full_name: &str,
        mr_number: i64,
        status: MergeRequestStatus,
        context: &str,
        description: Option<&str>,
        target_url: Option<&str>,
    ) -> AppResult<()> {
        let pr_path = format!("/repos/{}/pulls/{}", repo_full_name, mr_number);
        let (pr, _): (GitHubPullRequest, _) = self.get(&pr_path, None).await?;
        
        let state = match status {
            MergeRequestStatus::Pending => "pending",
            MergeRequestStatus::Running => "pending",
            MergeRequestStatus::Success => "success",
            MergeRequestStatus::Failed => "failure",
            MergeRequestStatus::Skipped => "success",
            MergeRequestStatus::Canceled => "error",
        };

        let path = format!("/repos/{}/statuses/{}", repo_full_name, pr.head.sha);
        let body = CreateStatusRequest {
            state: state.to_string(),
            target_url: target_url.map(|s| s.to_string()),
            description: description.map(|s| s.to_string()),
            context: context.to_string(),
        };

        let _: Value = self.post(&path, &body).await?;
        Ok(())
    }
}
