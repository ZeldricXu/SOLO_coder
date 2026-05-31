use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::DateTime;
use chrono::Utc;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum DocumentSource {
    Confluence,
    Notion,
    GitLabWiki,
    GitHubWiki,
    Markdown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Document {
    pub id: Uuid,
    pub title: String,
    pub content: String,
    pub source: DocumentSource,
    pub source_url: String,
    pub author: String,
    pub tags: Vec<String>,
    pub team_owner: String,
    pub permissions: PermissionConfig,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PermissionConfig {
    pub read_teams: Vec<String>,
    pub read_users: Vec<String>,
    pub is_public: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum IndexStatus {
    Pending,
    Indexed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchQuery {
    pub keyword: String,
    pub source_filter: Option<DocumentSource>,
    pub team_filter: Option<String>,
    pub tag_filter: Vec<String>,
    pub user_context: UserContext,
    pub page: usize,
    pub page_size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserContext {
    pub user_id: String,
    pub teams: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
    pub document: Document,
    pub score: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationJob {
    pub id: Uuid,
    pub source: DocumentSource,
    pub config_url: String,
    pub last_sync: DateTime<Utc>,
    pub status: IndexStatus,
}
