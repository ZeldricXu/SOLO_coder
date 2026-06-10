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

const DEFAULT_API_BASE: &str = "https://gitee.com/api/v5";

#[derive(Debug, Clone)]
pub struct GiteeProvider {
    client: Client,
    api_base: String,
    access_token: String,
}

impl GiteeProvider {
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
            header::ACCEPT,
            header::HeaderValue::from_static("application/json"),
        );
        headers.insert(
            header::USER_AGENT,
            header::HeaderValue::from_static("code-review-platform"),
        );
        headers
    }

    fn auth_query(&self) -> Vec<(&str, String)> {
        vec![("access_token", self.access_token.clone())]
    }

    fn parse_timestamp(ts: &str) -> AppResult<DateTime<Utc>> {
        NaiveDateTime::parse_from_str(ts, "%Y-%m-%dT%H:%M:%S%z")
            .or_else(|_| NaiveDateTime::parse_from_str(ts, "%Y-%m-%dT%H:%M:%S%.f%z"))
            .or_else(|_| NaiveDateTime::parse_from_str(ts, "%Y-%m-%d %H:%M:%S"))
            .map(|dt| dt.and_utc())
            .map_err(|e| AppError::Parse(format!("Failed to parse timestamp: {}", e)))
    }

    async fn get<T: for<'de> Deserialize<'de>>(
        &self,
        path: &str,
        query: Option<&[(&str, String)]>,
    ) -> AppResult<(T, Option<i64>, Option<i64>)> {
        let url = format!("{}{}", self.api_base, path);
        debug!("GET {}", url);

        let mut full_query = self.auth_query();
        if let Some(query) = query {
            full_query.extend_from_slice(query);
        }

        let request = self
            .client
            .get(&url)
            .headers(self.auth_headers())
            .query(&full_query);

        let response = request.send().await.map_err(|e| {
            AppError::ExternalService(format!("Gitee API request failed: {}", e))
        })?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::NOT_FOUND => AppError::NotFound(format!("Gitee resource not found: {}", error_body)),
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("Gitee authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("Gitee authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("Gitee API error ({}): {}", status, error_body)),
            });
        }

        let total = response.headers()
            .get("total_count")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i64>().ok());

        let total_pages = response.headers()
            .get("total_page")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i64>().ok());

        let data: T = response.json().await.map_err(|e| AppError::Serialization(e))?;

        Ok((data, total, total_pages))
    }

    async fn post<T: Serialize, R: for<'de> Deserialize<'de>>(&self, path: &str, body: &T) -> AppResult<R> {
        let url = format!("{}{}", self.api_base, path);
        debug!("POST {}", url);

        let request = self
            .client
            .post(&url)
            .headers(self.auth_headers())
            .query(&self.auth_query())
            .json(body);

        let response = request.send().await.map_err(|e| {
            AppError::ExternalService(format!("Gitee API request failed: {}", e))
        })?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("Gitee authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("Gitee authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("Gitee API error ({}): {}", status, error_body)),
            });
        }

        let data: R = response.json().await.map_err(|e| AppError::Serialization(e))?;
        Ok(data)
    }

    async fn delete(&self, path: &str) -> AppResult<()> {
        let url = format!("{}{}", self.api_base, path);
        debug!("DELETE {}", url);

        let request = self
            .client
            .delete(&url)
            .headers(self.auth_headers())
            .query(&self.auth_query());

        let response = request.send().await.map_err(|e| {
            AppError::ExternalService(format!("Gitee API request failed: {}", e))
        })?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::NOT_FOUND => AppError::NotFound(format!("Gitee resource not found: {}", error_body)),
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("Gitee authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("Gitee authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("Gitee API error ({}): {}", status, error_body)),
            });
        }

        Ok(())
    }
}

#[derive(Debug, Deserialize)]
struct GiteeUser {
    id: i64,
    login: String,
    email: Option<String>,
    avatar_url: String,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GiteeRepo {
    id: i64,
    name: String,
    full_name: String,
    description: Option<String>,
    html_url: String,
    clone_url: String,
    ssh_url: String,
    default_branch: Option<String>,
    private: bool,
    created_at: String,
    updated_at: String,
    owner: GiteeUser,
}

#[derive(Debug, Deserialize)]
struct GiteePullRequest {
    id: i64,
    number: i64,
    title: String,
    body: Option<String>,
    state: String,
    html_url: String,
    head: GiteeBranch,
    base: GiteeBranch,
    user: GiteeUser,
    created_at: String,
    updated_at: String,
    merged_at: Option<String>,
    merged_by: Option<GiteeUser>,
    labels: Vec<GiteeLabel>,
}

#[derive(Debug, Deserialize)]
struct GiteeBranch {
    #[serde(rename = "ref")]
    ref_name: String,
    sha: String,
}

#[derive(Debug, Deserialize)]
struct GiteeLabel {
    name: String,
}

#[derive(Debug, Deserialize)]
struct GiteeDiffFile {
    filename: String,
    status: String,
    additions: i64,
    deletions: i64,
    changes: i64,
    patch: Option<String>,
    previous_filename: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GiteeWebhook {
    id: i64,
    url: String,
    events: Vec<String>,
    active: bool,
    created_at: String,
}

#[derive(Debug, Deserialize)]
struct GiteeComment {
    id: i64,
    body: String,
    user: GiteeUser,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, Serialize)]
struct CreateWebhookRequest {
    url: String,
    events: Vec<String>,
    active: bool,
    password: String,
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
    context: Option<String>,
}

impl From<GiteeUser> for ProviderUser {
    fn from(user: GiteeUser) -> Self {
        Self {
            id: user.id.to_string(),
            username: user.login,
            email: user.email,
            avatar_url: Some(user.avatar_url),
            name: user.name,
        }
    }
}

impl From<GiteeRepo> for ProviderRepository {
    fn from(repo: GiteeRepo) -> Self {
        Self {
            id: repo.id.to_string(),
            name: repo.name,
            full_name: repo.full_name,
            description: repo.description,
            html_url: repo.html_url,
            clone_url: repo.clone_url,
            ssh_url: repo.ssh_url,
            default_branch: repo.default_branch.unwrap_or_else(|| "master".to_string()),
            is_private: repo.private,
            created_at: GiteeProvider::parse_timestamp(&repo.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GiteeProvider::parse_timestamp(&repo.updated_at).unwrap_or_else(|_| Utc::now()),
            owner: repo.owner.into(),
        }
    }
}

impl From<GiteePullRequest> for ProviderMergeRequest {
    fn from(pr: GiteePullRequest) -> Self {
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
            created_at: GiteeProvider::parse_timestamp(&pr.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GiteeProvider::parse_timestamp(&pr.updated_at).unwrap_or_else(|_| Utc::now()),
            merged_at: pr.merged_at.as_ref().and_then(|ts| GiteeProvider::parse_timestamp(ts).ok()),
            merged_by: pr.merged_by.map(|u| u.into()),
            labels: pr.labels.into_iter().map(|l| l.name).collect(),
        }
    }
}

impl From<GiteeWebhook> for ProviderWebhook {
    fn from(webhook: GiteeWebhook) -> Self {
        Self {
            id: webhook.id.to_string(),
            url: webhook.url,
            events: webhook.events,
            active: webhook.active,
            created_at: GiteeProvider::parse_timestamp(&webhook.created_at).unwrap_or_else(|_| Utc::now()),
        }
    }
}

impl From<GiteeComment> for ProviderComment {
    fn from(comment: GiteeComment) -> Self {
        Self {
            id: comment.id.to_string(),
            body: comment.body,
            author: comment.user.into(),
            created_at: GiteeProvider::parse_timestamp(&comment.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GiteeProvider::parse_timestamp(&comment.updated_at).unwrap_or_else(|_| Utc::now()),
        }
    }
}

#[async_trait]
impl GitProvider for GiteeProvider {
    async fn get_user_info(&self) -> AppResult<ProviderUser> {
        let (user, _, _): (GiteeUser, _, _) = self.get("/user", None).await?;
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
            ("type", "all".to_string()),
        ];

        let (repos, total, total_pages): (Vec<GiteeRepo>, _, _) =
            self.get("/user/repos", Some(&query)).await?;

        let total = total.unwrap_or(repos.len() as i64);
        let total_pages = total_pages.unwrap_or(pagination.page as i64);

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
        let (repo, _, _): (GiteeRepo, _, _) = self.get(&path, None).await?;
        Ok(repo.into())
    }

    async fn get_merge_request(
        &self,
        repo_full_name: &str,
        mr_number: i64,
    ) -> AppResult<ProviderMergeRequest> {
        let path = format!("/repos/{}/pulls/{}", repo_full_name, mr_number);
        let (pr, _, _): (GiteePullRequest, _, _) = self.get(&path, None).await?;
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
        let (prs, total, total_pages): (Vec<GiteePullRequest>, _, _) =
            self.get(&path, Some(&query)).await?;

        let total = total.unwrap_or(prs.len() as i64);
        let total_pages = total_pages.unwrap_or(pagination.page as i64);

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
        let (files, _, _): (Vec<GiteeDiffFile>, _, _) = self.get(&path, None).await?;

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
            url: url.to_string(),
            events: events.to_vec(),
            active: true,
            password: secret.to_string(),
        };

        let webhook: GiteeWebhook = self.post(&path, &body).await?;
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
        let path = format!("/repos/{}/pulls/{}/comments", repo_full_name, mr_number);
        let body = CreateCommentRequest {
            body: body.to_string(),
        };

        let comment: GiteeComment = self.post(&path, &body).await?;
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
        let (pr, _, _): (GiteePullRequest, _, _) = self.get(&pr_path, None).await?;

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
            context: Some(context.to_string()),
        };

        let _: Value = self.post(&path, &body).await?;
        Ok(())
    }
}
