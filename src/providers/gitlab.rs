use async_trait::async_trait;
use chrono::{DateTime, NaiveDateTime, Utc};
use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
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

const DEFAULT_API_BASE: &str = "https://gitlab.com/api/v4";

#[derive(Debug, Clone)]
pub struct GitLabProvider {
    client: Client,
    api_base: String,
    access_token: String,
}

impl GitLabProvider {
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
            "PRIVATE-TOKEN",
            header::HeaderValue::from_str(&self.access_token).unwrap(),
        );
        headers.insert(
            header::ACCEPT,
            header::HeaderValue::from_static("application/json"),
        );
        headers
    }

    fn parse_timestamp(ts: &str) -> AppResult<DateTime<Utc>> {
        NaiveDateTime::parse_from_str(ts, "%Y-%m-%dT%H:%M:%S%.fZ")
            .or_else(|_| NaiveDateTime::parse_from_str(ts, "%Y-%m-%dT%H:%M:%SZ"))
            .map(|dt| dt.and_utc())
            .map_err(|e| AppError::Parse(format!("Failed to parse timestamp: {}", e)))
    }

    fn encode_path_segment(s: &str) -> String {
        utf8_percent_encode(s, NON_ALPHANUMERIC).to_string()
    }

    async fn get<T: for<'de> Deserialize<'de>>(
        &self,
        path: &str,
        query: Option<&[(&str, String)]>,
    ) -> AppResult<(T, Option<i64>, Option<i64>)> {
        let url = format!("{}{}", self.api_base, path);
        debug!("GET {}", url);

        let mut request = self.client.get(&url).headers(self.auth_headers());
        if let Some(query) = query {
            request = request.query(query);
        }

        let response = request.send().await.map_err(|e| {
            AppError::ExternalService(format!("GitLab API request failed: {}", e))
        })?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::NOT_FOUND => AppError::NotFound(format!("GitLab resource not found: {}", error_body)),
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("GitLab authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("GitLab authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("GitLab API error ({}): {}", status, error_body)),
            });
        }

        let total = response.headers()
            .get("X-Total")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i64>().ok());

        let total_pages = response.headers()
            .get("X-Total-Pages")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i64>().ok());

        let data: T = response.json().await.map_err(|e| AppError::Serialization(e))?;

        Ok((data, total, total_pages))
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
            .map_err(|e| AppError::ExternalService(format!("GitLab API request failed: {}", e)))?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("GitLab authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("GitLab authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("GitLab API error ({}): {}", status, error_body)),
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
            .map_err(|e| AppError::ExternalService(format!("GitLab API request failed: {}", e)))?;

        let status = response.status();
        if !status.is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(match status {
                StatusCode::NOT_FOUND => AppError::NotFound(format!("GitLab resource not found: {}", error_body)),
                StatusCode::UNAUTHORIZED => AppError::Authentication(format!("GitLab authentication failed: {}", error_body)),
                StatusCode::FORBIDDEN => AppError::Authorization(format!("GitLab authorization failed: {}", error_body)),
                _ => AppError::ExternalService(format!("GitLab API error ({}): {}", status, error_body)),
            });
        }

        Ok(())
    }
}

#[derive(Debug, Deserialize)]
struct GitLabUser {
    id: i64,
    username: String,
    email: Option<String>,
    avatar_url: Option<String>,
    name: String,
}

#[derive(Debug, Deserialize)]
struct GitLabNamespace {
    id: i64,
    name: String,
    path: String,
    kind: String,
}

#[derive(Debug, Deserialize)]
struct GitLabProject {
    id: i64,
    name: String,
    path_with_namespace: String,
    description: Option<String>,
    web_url: String,
    http_url_to_repo: String,
    ssh_url_to_repo: String,
    default_branch: Option<String>,
    visibility: String,
    created_at: String,
    last_activity_at: String,
    namespace: GitLabNamespace,
    owner: Option<GitLabUser>,
}

#[derive(Debug, Deserialize)]
struct GitLabMergeRequest {
    id: i64,
    iid: i64,
    title: String,
    description: Option<String>,
    state: String,
    web_url: String,
    source_branch: String,
    target_branch: String,
    author: GitLabUser,
    created_at: String,
    updated_at: String,
    merged_at: Option<String>,
    merged_by: Option<GitLabUser>,
    labels: Vec<String>,
    sha: String,
}

#[derive(Debug, Deserialize)]
struct GitLabDiffFile {
    new_path: String,
    old_path: String,
    new_file: bool,
    renamed_file: bool,
    deleted_file: bool,
    diff: String,
    additions: i64,
    deletions: i64,
}

#[derive(Debug, Deserialize)]
struct GitLabWebhook {
    id: i64,
    url: String,
    push_events: bool,
    tag_push_events: bool,
    merge_requests_events: bool,
    repository_update_events: bool,
    issues_events: bool,
    confidential_issues_events: bool,
    note_events: bool,
    confidential_note_events: bool,
    pipeline_events: bool,
    wiki_page_events: bool,
    job_events: bool,
    active: bool,
    created_at: String,
}

#[derive(Debug, Deserialize)]
struct GitLabNote {
    id: i64,
    body: String,
    author: GitLabUser,
    created_at: String,
    updated_at: String,
}

#[derive(Debug, Serialize)]
struct CreateWebhookRequest {
    url: String,
    token: String,
    push_events: bool,
    tag_push_events: bool,
    merge_requests_events: bool,
    repository_update_events: bool,
    enable_ssl_verification: bool,
}

#[derive(Debug, Serialize)]
struct CreateNoteRequest {
    body: String,
}

#[derive(Debug, Serialize)]
struct CreateStatusRequest {
    state: String,
    target_url: Option<String>,
    description: Option<String>,
    context: String,
}

impl From<GitLabUser> for ProviderUser {
    fn from(user: GitLabUser) -> Self {
        Self {
            id: user.id.to_string(),
            username: user.username,
            email: user.email,
            avatar_url: user.avatar_url,
            name: Some(user.name),
        }
    }
}

impl From<GitLabProject> for ProviderRepository {
    fn from(repo: GitLabProject) -> Self {
        let owner = repo.owner.clone().unwrap_or_else(|| GitLabUser {
            id: repo.namespace.id,
            username: repo.namespace.path.clone(),
            email: None,
            avatar_url: None,
            name: repo.namespace.name.clone(),
        });

        Self {
            id: repo.id.to_string(),
            name: repo.name,
            full_name: repo.path_with_namespace,
            description: repo.description,
            html_url: repo.web_url,
            clone_url: repo.http_url_to_repo,
            ssh_url: repo.ssh_url_to_repo,
            default_branch: repo.default_branch.unwrap_or_else(|| "main".to_string()),
            is_private: repo.visibility == "private",
            created_at: GitLabProvider::parse_timestamp(&repo.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GitLabProvider::parse_timestamp(&repo.last_activity_at).unwrap_or_else(|_| Utc::now()),
            owner: owner.into(),
        }
    }
}

impl From<GitLabMergeRequest> for ProviderMergeRequest {
    fn from(mr: GitLabMergeRequest) -> Self {
        Self {
            id: mr.id.to_string(),
            number: mr.iid,
            title: mr.title,
            description: mr.description,
            state: mr.state,
            source_branch: mr.source_branch,
            target_branch: mr.target_branch,
            html_url: mr.web_url,
            author: mr.author.into(),
            created_at: GitLabProvider::parse_timestamp(&mr.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GitLabProvider::parse_timestamp(&mr.updated_at).unwrap_or_else(|_| Utc::now()),
            merged_at: mr.merged_at.as_ref().and_then(|ts| GitLabProvider::parse_timestamp(ts).ok()),
            merged_by: mr.merged_by.map(|u| u.into()),
            labels: mr.labels,
        }
    }
}

impl From<GitLabWebhook> for ProviderWebhook {
    fn from(webhook: GitLabWebhook) -> Self {
        let mut events = Vec::new();
        if webhook.push_events {
            events.push("push".to_string());
        }
        if webhook.tag_push_events {
            events.push("tag_push".to_string());
        }
        if webhook.merge_requests_events {
            events.push("merge_requests".to_string());
        }
        if webhook.issues_events {
            events.push("issues".to_string());
        }
        if webhook.note_events {
            events.push("note".to_string());
        }
        if webhook.pipeline_events {
            events.push("pipeline".to_string());
        }

        Self {
            id: webhook.id.to_string(),
            url: webhook.url,
            events,
            active: webhook.active,
            created_at: GitLabProvider::parse_timestamp(&webhook.created_at).unwrap_or_else(|_| Utc::now()),
        }
    }
}

impl From<GitLabNote> for ProviderComment {
    fn from(note: GitLabNote) -> Self {
        Self {
            id: note.id.to_string(),
            body: note.body,
            author: note.author.into(),
            created_at: GitLabProvider::parse_timestamp(&note.created_at).unwrap_or_else(|_| Utc::now()),
            updated_at: GitLabProvider::parse_timestamp(&note.updated_at).unwrap_or_else(|_| Utc::now()),
        }
    }
}

#[async_trait]
impl GitProvider for GitLabProvider {
    async fn get_user_info(&self) -> AppResult<ProviderUser> {
        let (user, _, _): (GitLabUser, _, _) = self.get("/user", None).await?;
        Ok(user.into())
    }

    async fn get_repositories(
        &self,
        pagination: PaginationParams,
    ) -> AppResult<PaginatedResponse<ProviderRepository>> {
        let query = vec![
            ("per_page", pagination.per_page.to_string()),
            ("page", pagination.page.to_string()),
            ("order_by", "last_activity_at".to_string()),
            ("sort", "desc".to_string()),
            ("membership", "true".to_string()),
        ];

        let (projects, total, total_pages): (Vec<GitLabProject>, _, _) =
            self.get("/projects", Some(&query)).await?;

        let total = total.unwrap_or(projects.len() as i64);
        let total_pages = total_pages.unwrap_or(pagination.page as i64);

        let items: Vec<ProviderRepository> = projects.into_iter().map(|p| p.into()).collect();

        Ok(PaginatedResponse {
            items,
            total,
            page: pagination.page,
            per_page: pagination.per_page,
            total_pages,
        })
    }

    async fn get_repository(&self, repo_full_name: &str) -> AppResult<ProviderRepository> {
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!("/projects/{}", encoded_path);
        let (project, _, _): (GitLabProject, _, _) = self.get(&path, None).await?;
        Ok(project.into())
    }

    async fn get_merge_request(
        &self,
        repo_full_name: &str,
        mr_number: i64,
    ) -> AppResult<ProviderMergeRequest> {
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!("/projects/{}/merge_requests/{}", encoded_path, mr_number);
        let (mr, _, _): (GitLabMergeRequest, _, _) = self.get(&path, None).await?;
        Ok(mr.into())
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
            ("order_by", "updated_at".to_string()),
            ("sort", "desc".to_string()),
        ];

        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!("/projects/{}/merge_requests", encoded_path);
        let (mrs, total, total_pages): (Vec<GitLabMergeRequest>, _, _) =
            self.get(&path, Some(&query)).await?;

        let total = total.unwrap_or(mrs.len() as i64);
        let total_pages = total_pages.unwrap_or(pagination.page as i64);

        let items: Vec<ProviderMergeRequest> = mrs.into_iter().map(|mr| mr.into()).collect();

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
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!(
            "/projects/{}/merge_requests/{}/diffs",
            encoded_path, mr_number
        );
        let (files, _, _): (Vec<GitLabDiffFile>, _, _) = self.get(&path, None).await?;

        let diff_files: Vec<ProviderDiffFile> = files
            .into_iter()
            .map(|f| {
                let status = if f.deleted_file {
                    "removed".to_string()
                } else if f.renamed_file {
                    "renamed".to_string()
                } else if f.new_file {
                    "added".to_string()
                } else {
                    "modified".to_string()
                };

                ProviderDiffFile {
                    path: f.new_path.clone(),
                    old_path: Some(f.old_path),
                    new_path: Some(f.new_path),
                    status,
                    additions: f.additions,
                    deletions: f.deletions,
                    changes: f.additions + f.deletions,
                    patch: f.diff,
                }
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
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!("/projects/{}/hooks", encoded_path);

        let mut push_events = false;
        let mut tag_push_events = false;
        let mut merge_requests_events = false;
        let mut repository_update_events = false;

        for event in events {
            match event.as_str() {
                "push" => push_events = true,
                "tag_push" => tag_push_events = true,
                "merge_requests" => merge_requests_events = true,
                "repository" => repository_update_events = true,
                _ => {}
            }
        }

        let body = CreateWebhookRequest {
            url: url.to_string(),
            token: secret.to_string(),
            push_events,
            tag_push_events,
            merge_requests_events,
            repository_update_events,
            enable_ssl_verification: true,
        };

        let webhook: GitLabWebhook = self.post(&path, &body).await?;
        Ok(webhook.into())
    }

    async fn delete_webhook(&self, repo_full_name: &str, webhook_id: &str) -> AppResult<()> {
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!("/projects/{}/hooks/{}", encoded_path, webhook_id);
        self.delete(&path).await
    }

    async fn add_comment(
        &self,
        repo_full_name: &str,
        mr_number: i64,
        body: &str,
    ) -> AppResult<ProviderComment> {
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let path = format!(
            "/projects/{}/merge_requests/{}/notes",
            encoded_path, mr_number
        );
        let body = CreateNoteRequest {
            body: body.to_string(),
        };

        let note: GitLabNote = self.post(&path, &body).await?;
        Ok(note.into())
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
        let encoded_path = Self::encode_path_segment(repo_full_name);
        let mr_path = format!("/projects/{}/merge_requests/{}", encoded_path, mr_number);
        let (mr, _, _): (GitLabMergeRequest, _, _) = self.get(&mr_path, None).await?;

        let state = match status {
            MergeRequestStatus::Pending => "pending",
            MergeRequestStatus::Running => "running",
            MergeRequestStatus::Success => "success",
            MergeRequestStatus::Failed => "failed",
            MergeRequestStatus::Skipped => "success",
            MergeRequestStatus::Canceled => "canceled",
        };

        let path = format!("/projects/{}/statuses/{}", encoded_path, mr.sha);
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
