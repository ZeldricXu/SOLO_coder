use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileLocation {
    pub file_path: String,
    pub line_no: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CodePosition {
    pub file_path: String,
    pub line_no: i32,
    pub line_content: Option<String>,
    pub hunk_index: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReviewItem {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub author_id: Uuid,
    pub content: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone)]
pub struct DiffFileSummary {
    pub old_path: String,
    pub new_path: String,
    pub status: String,
    pub additions: i64,
    pub deletions: i64,
    pub binary: bool,
}

#[derive(Debug, Clone)]
pub struct ReviewDataContext {
    pub diff_files: Vec<review_diff::DiffFile>,
    pub merge_request_id: Uuid,
}
