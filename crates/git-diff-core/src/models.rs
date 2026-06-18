use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RawDiffFile {
    pub old_path: String,
    pub new_path: String,
    pub status: FileChangeStatus,
    pub old_mode: Option<u32>,
    pub new_mode: Option<u32>,
    pub similarity: Option<i32>,
    pub hunks: Vec<RawDiffHunk>,
    pub binary: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum FileChangeStatus {
    Added,
    Modified,
    Deleted,
    Renamed,
    Copied,
    TypeChange,
}

impl FileChangeStatus {
    pub fn as_str(&self) -> &str {
        match self {
            FileChangeStatus::Added => "added",
            FileChangeStatus::Modified => "modified",
            FileChangeStatus::Deleted => "deleted",
            FileChangeStatus::Renamed => "renamed",
            FileChangeStatus::Copied => "copied",
            FileChangeStatus::TypeChange => "typechange",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "added" | "new file" => Some(FileChangeStatus::Added),
            "modified" => Some(FileChangeStatus::Modified),
            "deleted" => Some(FileChangeStatus::Deleted),
            "renamed" => Some(FileChangeStatus::Renamed),
            "copied" => Some(FileChangeStatus::Copied),
            "typechange" => Some(FileChangeStatus::TypeChange),
            _ => None,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RawDiffHunk {
    pub old_start: i32,
    pub old_count: i32,
    pub new_start: i32,
    pub new_count: i32,
    pub header: String,
    pub lines: Vec<RawDiffLine>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq)]
pub enum LineType {
    Addition,
    Deletion,
    Context,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RawDiffLine {
    pub line_type: LineType,
    pub content: String,
    pub old_line_no: Option<i32>,
    pub new_line_no: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ChangedFile {
    pub old_path: Option<String>,
    pub new_path: Option<String>,
    pub status: FileChangeStatus,
}
