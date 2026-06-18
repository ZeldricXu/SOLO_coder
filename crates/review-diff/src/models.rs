use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DiffFile {
    pub old_path: String,
    pub new_path: String,
    pub old_mode: Option<String>,
    pub new_mode: Option<String>,
    pub status: String,
    pub similarity: Option<i32>,
    pub hunks: Vec<DiffHunk>,
    pub binary: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DiffHunk {
    pub old_start: i32,
    pub old_lines: i32,
    pub new_start: i32,
    pub new_lines: i32,
    pub header: String,
    pub lines: Vec<DiffLine>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DiffLine {
    pub line_type: String,
    pub content: String,
    pub old_line_no: Option<i32>,
    pub new_line_no: Option<i32>,
}
