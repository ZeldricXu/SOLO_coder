pub mod dingtalk;
pub mod email;
pub mod gitee;
pub mod github;
pub mod gitlab;
pub mod llm;
pub mod minio_client;
pub mod redis_client;
pub mod slack;

pub use dingtalk::DingtalkClient;
pub use email::EmailClient;
pub use gitee::GiteeProvider;
pub use github::GitHubProvider;
pub use gitlab::GitLabProvider;
pub use llm::LlmClient;
pub use minio_client::MinioClient;
pub use redis_client::RedisClient;
pub use slack::SlackClient;

use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::utils::AppResult;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderUser {
    pub id: String,
    pub username: String,
    pub email: Option<String>,
    pub avatar_url: Option<String>,
    pub name: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderRepository {
    pub id: String,
    pub name: String,
    pub full_name: String,
    pub description: Option<String>,
    pub html_url: String,
    pub clone_url: String,
    pub ssh_url: String,
    pub default_branch: String,
    pub is_private: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub owner: ProviderUser,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderMergeRequest {
    pub id: String,
    pub number: i64,
    pub title: String,
    pub description: Option<String>,
    pub state: String,
    pub source_branch: String,
    pub target_branch: String,
    pub html_url: String,
    pub author: ProviderUser,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub merged_at: Option<DateTime<Utc>>,
    pub merged_by: Option<ProviderUser>,
    pub labels: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderDiff {
    pub files: Vec<ProviderDiffFile>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderDiffFile {
    pub path: String,
    pub old_path: Option<String>,
    pub new_path: Option<String>,
    pub status: String,
    pub additions: i64,
    pub deletions: i64,
    pub changes: i64,
    pub patch: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderComment {
    pub id: String,
    pub body: String,
    pub author: ProviderUser,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProviderWebhook {
    pub id: String,
    pub url: String,
    pub events: Vec<String>,
    pub active: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone, Copy)]
pub enum MergeRequestState {
    Open,
    Closed,
    Merged,
    All,
}

impl MergeRequestState {
    pub fn as_str(&self) -> &str {
        match self {
            MergeRequestState::Open => "open",
            MergeRequestState::Closed => "closed",
            MergeRequestState::Merged => "merged",
            MergeRequestState::All => "all",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "open" => Some(MergeRequestState::Open),
            "closed" => Some(MergeRequestState::Closed),
            "merged" => Some(MergeRequestState::Merged),
            "all" => Some(MergeRequestState::All),
            _ => None,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum MergeRequestStatus {
    Pending,
    Running,
    Success,
    Failed,
    Skipped,
    Canceled,
}

impl MergeRequestStatus {
    pub fn as_str(&self) -> &str {
        match self {
            MergeRequestStatus::Pending => "pending",
            MergeRequestStatus::Running => "running",
            MergeRequestStatus::Success => "success",
            MergeRequestStatus::Failed => "failed",
            MergeRequestStatus::Skipped => "skipped",
            MergeRequestStatus::Canceled => "canceled",
        }
    }
}

#[derive(Debug, Clone)]
pub struct PaginationParams {
    pub page: i64,
    pub per_page: i64,
}

impl PaginationParams {
    pub fn new(page: i64, per_page: i64) -> Self {
        Self { page, per_page }
    }
}

impl Default for PaginationParams {
    fn default() -> Self {
        Self {
            page: 1,
            per_page: 20,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PaginatedResponse<T> {
    pub items: Vec<T>,
    pub total: i64,
    pub page: i64,
    pub per_page: i64,
    pub total_pages: i64,
}

#[async_trait]
pub trait GitProvider: Send + Sync + Clone {
    async fn get_user_info(&self) -> AppResult<ProviderUser>;
    async fn get_repositories(
        &self,
        pagination: PaginationParams,
    ) -> AppResult<crate::utils::PaginatedResponse<ProviderRepository>>;
    async fn get_repository(&self, repo_full_name: &str) -> AppResult<ProviderRepository>;
    async fn get_merge_request(
        &self,
        repo_full_name: &str,
        mr_number: i64,
    ) -> AppResult<ProviderMergeRequest>;
    async fn get_merge_requests(
        &self,
        repo_full_name: &str,
        state: Option<MergeRequestState>,
        pagination: PaginationParams,
    ) -> AppResult<crate::utils::PaginatedResponse<ProviderMergeRequest>>;
    async fn get_diff(
        &self,
        repo_full_name: &str,
        mr_number: i64,
    ) -> AppResult<ProviderDiff>;
    async fn create_webhook(
        &self,
        repo_full_name: &str,
        url: &str,
        events: &[String],
        secret: &str,
    ) -> AppResult<ProviderWebhook>;
    async fn delete_webhook(&self, repo_full_name: &str, webhook_id: &str) -> AppResult<()>;
    async fn add_comment(
        &self,
        repo_full_name: &str,
        mr_number: i64,
        body: &str,
    ) -> AppResult<ProviderComment>;
    async fn update_merge_request_status(
        &self,
        repo_full_name: &str,
        mr_number: i64,
        status: MergeRequestStatus,
        context: &str,
        description: Option<&str>,
        target_url: Option<&str>,
    ) -> AppResult<()>;
}
