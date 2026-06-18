use crate::models::{DiffFile, DiffHunk, DiffLine};

pub struct DiffContextProvider;

impl DiffContextProvider {
    pub fn find_line_context<'a>(
        diff_files: &'a [DiffFile],
        file_path: &str,
        line_no: i32,
        context_lines: i32,
    ) -> Option<(Vec<&'a DiffLine>, i32)> {
        for file in diff_files {
            if file.new_path != file_path && file.old_path != file_path {
                continue;
            }

            for (hunk_idx, hunk) in file.hunks.iter().enumerate() {
                for (line_idx, line) in hunk.lines.iter().enumerate() {
                    if line.new_line_no == Some(line_no) || line.old_line_no == Some(line_no) {
                        let start_idx = if line_idx as i32 - context_lines >= 0 {
                            line_idx - context_lines as usize
                        } else {
                            0
                        };
                        let end_idx = std::cmp::min(line_idx + context_lines as usize + 1, hunk.lines.len());

                        let mut context_vec = Vec::new();
                        for i in start_idx..end_idx {
                            context_vec.push(&hunk.lines[i]);
                        }

                        return Some((context_vec, hunk_idx as i32));
                    }
                }
            }
        }

        None
    }

    pub fn find_hunks_for_file<'a>(
        diff_files: &'a [DiffFile],
        file_path: &str,
    ) -> Vec<&'a DiffHunk> {
        diff_files
            .iter()
            .filter(|f| f.new_path == file_path || f.old_path == file_path)
            .flat_map(|f| f.hunks.iter())
            .collect()
    }
}
