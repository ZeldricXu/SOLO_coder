use review_diff::{DiffFile, DiffLine, DiffContextProvider};
use crate::types::{CodePosition, FileLocation};

pub struct CodeLocator;

impl CodeLocator {
    pub fn locate<'a>(
        diff_files: &'a [DiffFile],
        location: &FileLocation,
    ) -> Option<CodePosition> {
        let file_path = &location.file_path;
        for file in diff_files {
            if file.new_path != *file_path && file.old_path != *file_path {
                continue;
            }
            if let Some(line_no) = location.line_no {
                for (hunk_idx, hunk) in file.hunks.iter().enumerate() {
                    for line in &hunk.lines {
                        if line.new_line_no == Some(line_no) || line.old_line_no == Some(line_no) {
                            return Some(CodePosition {
                                file_path: file_path.clone(),
                                line_no,
                                line_content: Some(line.content.clone()),
                                hunk_index: Some(hunk_idx as i32),
                            });
                        }
                    }
                }
            } else {
                return Some(CodePosition {
                    file_path: file_path.clone(),
                    line_no: 0,
                    line_content: None,
                    hunk_index: None,
                });
            }
        }
        None
    }

    pub fn get_context<'a>(
        diff_files: &'a [DiffFile],
        file_path: &str,
        line_no: i32,
        context_lines: i32,
    ) -> Option<(Vec<&'a DiffLine>, i32)> {
        DiffContextProvider::find_line_context(diff_files, file_path, line_no, context_lines)
    }

    pub fn get_file_hunks<'a>(
        diff_files: &'a [DiffFile],
        file_path: &str,
    ) -> Vec<&'a review_diff::DiffHunk> {
        DiffContextProvider::find_hunks_for_file(diff_files, file_path)
    }

    pub fn file_exists(diff_files: &[DiffFile], file_path: &str) -> bool {
        diff_files.iter().any(|f| f.new_path == file_path || f.old_path == file_path)
    }

    pub fn line_in_diff(diff_files: &[DiffFile], file_path: &str, line_no: i32) -> bool {
        Self::locate(diff_files, &FileLocation {
            file_path: file_path.to_string(),
            line_no: Some(line_no),
        }).is_some()
    }

    pub fn get_file_stats(diff_files: &[DiffFile], file_path: &str) -> Option<crate::types::DiffFileSummary> {
        let file = diff_files.iter().find(|f| f.new_path == file_path || f.old_path == file_path)?;
        let additions = file.hunks.iter()
            .flat_map(|h| h.lines.iter())
            .filter(|l| l.line_type == "new")
            .count() as i64;
        let deletions = file.hunks.iter()
            .flat_map(|h| h.lines.iter())
            .filter(|l| l.line_type == "old")
            .count() as i64;
        Some(crate::types::DiffFileSummary {
            old_path: file.old_path.clone(),
            new_path: file.new_path.clone(),
            status: file.status.clone(),
            additions,
            deletions,
            binary: file.binary,
        })
    }
}
