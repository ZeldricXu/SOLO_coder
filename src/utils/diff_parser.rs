use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::error::AppResult;

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

#[derive(Debug)]
pub struct DiffParser {
    file_header_re: Regex,
    hunk_header_re: Regex,
    similarity_re: Regex,
}

impl DiffParser {
    pub fn new() -> Self {
        Self {
            file_header_re: Regex::new(r"^diff --git a/(.*) b/(.*)$").unwrap(),
            hunk_header_re: Regex::new(r"^@@ -(\d+),?(\d*) \+(\d+),?(\d*) @@(.*)$").unwrap(),
            similarity_re: Regex::new(r"^similarity index (\d+)%$").unwrap(),
        }
    }

    pub fn parse(&self, diff_text: &str) -> AppResult<Vec<DiffFile>> {
        let mut files = Vec::new();
        let mut current_file: Option<DiffFile> = None;
        let mut current_hunk: Option<DiffHunk> = None;
        let mut old_line_no = 0;
        let mut new_line_no = 0;

        for line in diff_text.lines() {
            if let Some(captures) = self.file_header_re.captures(line) {
                if let Some(hunk) = current_hunk.take() {
                    if let Some(file) = current_file.as_mut() {
                        file.hunks.push(hunk);
                    }
                }
                if let Some(file) = current_file.take() {
                    files.push(file);
                }

                let old_path = captures.get(1).unwrap().as_str().to_string();
                let new_path = captures.get(2).unwrap().as_str().to_string();
                
                current_file = Some(DiffFile {
                    old_path: old_path.clone(),
                    new_path: new_path.clone(),
                    old_mode: None,
                    new_mode: None,
                    status: if old_path == "/dev/null" {
                        "added".to_string()
                    } else if new_path == "/dev/null" {
                        "deleted".to_string()
                    } else {
                        "modified".to_string()
                    },
                    similarity: None,
                    hunks: Vec::new(),
                    binary: false,
                });
                old_line_no = 0;
                new_line_no = 0;
                continue;
            }

            if line.starts_with("old mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.old_mode = Some(line.trim_start_matches("old mode ").to_string());
                }
                continue;
            }

            if line.starts_with("new mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.new_mode = Some(line.trim_start_matches("new mode ").to_string());
                }
                continue;
            }

            if line.starts_with("new file mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = "added".to_string();
                    file.new_mode = Some(line.trim_start_matches("new file mode ").to_string());
                }
                continue;
            }

            if line.starts_with("deleted file mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = "deleted".to_string();
                    file.old_mode = Some(line.trim_start_matches("deleted file mode ").to_string());
                }
                continue;
            }

            if line.starts_with("rename from ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = "renamed".to_string();
                }
                continue;
            }

            if let Some(captures) = self.similarity_re.captures(line) {
                if let Some(file) = current_file.as_mut() {
                    file.similarity = Some(captures.get(1).unwrap().as_str().parse().unwrap_or(0));
                }
                continue;
            }

            if line.starts_with("Binary files ") {
                if let Some(file) = current_file.as_mut() {
                    file.binary = true;
                }
                continue;
            }

            if let Some(captures) = self.hunk_header_re.captures(line) {
                if let Some(hunk) = current_hunk.take() {
                    if let Some(file) = current_file.as_mut() {
                        file.hunks.push(hunk);
                    }
                }

                let old_start: i32 = captures.get(1).unwrap().as_str().parse().unwrap();
                let old_lines: i32 = captures.get(2).map_or(1, |m| m.as_str().parse().unwrap_or(1));
                let new_start: i32 = captures.get(3).unwrap().as_str().parse().unwrap();
                let new_lines: i32 = captures.get(4).map_or(1, |m| m.as_str().parse().unwrap_or(1));
                let header = captures.get(5).map_or("", |m| m.as_str()).to_string();

                old_line_no = old_start;
                new_line_no = new_start;

                current_hunk = Some(DiffHunk {
                    old_start,
                    old_lines,
                    new_start,
                    new_lines,
                    header,
                    lines: Vec::new(),
                });
                continue;
            }

            if let Some(hunk) = current_hunk.as_mut() {
                let line_type = if line.starts_with('+') {
                    "new"
                } else if line.starts_with('-') {
                    "old"
                } else if line.starts_with(' ') {
                    "context"
                } else if line.starts_with('\\') {
                    continue;
                } else {
                    continue;
                };

                let content = if line.starts_with('+') || line.starts_with('-') || line.starts_with(' ') {
                    line[1..].to_string()
                } else {
                    line.to_string()
                };

                let (old_no, new_no) = match line_type {
                    "new" => {
                        let no = new_line_no;
                        new_line_no += 1;
                        (None, Some(no))
                    }
                    "old" => {
                        let no = old_line_no;
                        old_line_no += 1;
                        (Some(no), None)
                    }
                    "context" => {
                        let old = old_line_no;
                        let new = new_line_no;
                        old_line_no += 1;
                        new_line_no += 1;
                        (Some(old), Some(new))
                    }
                    _ => (None, None),
                };

                hunk.lines.push(DiffLine {
                    line_type: line_type.to_string(),
                    content,
                    old_line_no: old_no,
                    new_line_no: new_no,
                });
            }
        }

        if let Some(hunk) = current_hunk.take() {
            if let Some(file) = current_file.as_mut() {
                file.hunks.push(hunk);
            }
        }
        if let Some(file) = current_file.take() {
            files.push(file);
        }

        Ok(files)
    }

    pub fn get_line_context<'a>(
        &'a self,
        files: &'a [DiffFile],
        file_path: &str,
        line_no: i32,
        context_lines: i32,
    ) -> Option<(Vec<&'a DiffLine>, i32)> {
        for file in files {
            if file.new_path != file_path && file.old_path != file_path {
                continue;
            }

            for (hunk_idx, hunk) in file.hunks.iter().enumerate() {
                for (line_idx, line) in hunk.lines.iter().enumerate() {
                    let matches = if line.new_line_no == Some(line_no) || line.old_line_no == Some(line_no) {
                        true
                    } else {
                        false
                    };

                    if matches {
                        let start_idx = if line_idx as i32 - context_lines >= 0 {
                            line_idx - context_lines as usize
                        } else {
                            0
                        };
                        let end_idx = std::cmp::min(line_idx + context_lines as usize + 1, hunk.lines.len());
                        
                        let mut context_lines_vec = Vec::new();
                        for i in start_idx..end_idx {
                            context_lines_vec.push(&hunk.lines[i]);
                        }
                        
                        return Some((context_lines_vec, hunk_idx as i32));
                    }
                }
            }
        }

        None
    }

    pub fn get_changed_line_count(&self, files: &[DiffFile]) -> (i64, i64) {
        let mut additions = 0;
        let mut deletions = 0;

        for file in files {
            if file.binary {
                continue;
            }
            for hunk in &file.hunks {
                for line in &hunk.lines {
                    match line.line_type.as_str() {
                        "new" => additions += 1,
                        "old" => deletions += 1,
                        _ => {}
                    }
                }
            }
        }

        (additions, deletions)
    }

    pub fn group_comments_by_file<'a>(
        &self,
        comments: &'a [crate::models::comment::Comment],
    ) -> HashMap<String, Vec<&'a crate::models::comment::Comment>> {
        let mut grouped: HashMap<String, Vec<&crate::models::comment::Comment>> = HashMap::new();
        
        for comment in comments {
            if let Some(file_path) = &comment.file_path {
                grouped.entry(file_path.clone()).or_default().push(comment);
            }
        }
        
        grouped
    }

    pub fn group_comments_by_line<'a>(
        &self,
        comments: &'a [crate::models::comment::Comment],
    ) -> HashMap<(String, Option<i32>), Vec<&'a crate::models::comment::Comment>> {
        let mut grouped: HashMap<(String, Option<i32>), Vec<&crate::models::comment::Comment>> = HashMap::new();
        
        for comment in comments {
            if let Some(file_path) = &comment.file_path {
                let key = (file_path.clone(), comment.line_no);
                grouped.entry(key).or_default().push(comment);
            }
        }
        
        grouped
    }
}

impl Default for DiffParser {
    fn default() -> Self {
        Self::new()
    }
}
