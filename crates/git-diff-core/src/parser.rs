use crate::error::DiffResult;
use crate::models::*;

pub struct UnifiedDiffParser;

struct HunkInfo {
    old_start: i32,
    old_count: i32,
    new_start: i32,
    new_count: i32,
    header: String,
}

impl UnifiedDiffParser {
    pub fn new() -> Self {
        Self
    }

    pub fn parse(&self, diff_text: &str) -> DiffResult<Vec<RawDiffFile>> {
        let mut files = Vec::new();
        let mut current_file: Option<RawDiffFile> = None;
        let mut current_hunk: Option<RawDiffHunk> = None;
        let mut old_line_no: i32 = 0;
        let mut new_line_no: i32 = 0;

        for line in diff_text.lines() {
            if let Some((old_path, new_path)) = Self::parse_file_header(line) {
                if let Some(hunk) = current_hunk.take() {
                    if let Some(file) = current_file.as_mut() {
                        file.hunks.push(hunk);
                    }
                }
                if let Some(file) = current_file.take() {
                    files.push(file);
                }

                let status = if old_path == "/dev/null" {
                    FileChangeStatus::Added
                } else if new_path == "/dev/null" {
                    FileChangeStatus::Deleted
                } else {
                    FileChangeStatus::Modified
                };

                current_file = Some(RawDiffFile {
                    old_path,
                    new_path,
                    status,
                    old_mode: None,
                    new_mode: None,
                    similarity: None,
                    hunks: Vec::new(),
                    binary: false,
                });
                old_line_no = 0;
                new_line_no = 0;
                continue;
            }

            if let Some(mode) = line.strip_prefix("old mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.old_mode = Some(Self::parse_mode(mode));
                }
                continue;
            }

            if let Some(mode) = line.strip_prefix("new mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.new_mode = Some(Self::parse_mode(mode));
                }
                continue;
            }

            if let Some(mode) = line.strip_prefix("new file mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = FileChangeStatus::Added;
                    file.new_mode = Some(Self::parse_mode(mode));
                }
                continue;
            }

            if let Some(mode) = line.strip_prefix("deleted file mode ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = FileChangeStatus::Deleted;
                    file.old_mode = Some(Self::parse_mode(mode));
                }
                continue;
            }

            if line.starts_with("rename from ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = FileChangeStatus::Renamed;
                }
                continue;
            }

            if line.starts_with("copy from ") {
                if let Some(file) = current_file.as_mut() {
                    file.status = FileChangeStatus::Copied;
                }
                continue;
            }

            if let Some(pct) = Self::parse_similarity(line) {
                if let Some(file) = current_file.as_mut() {
                    file.similarity = Some(pct);
                }
                continue;
            }

            if line.starts_with("Binary files ") {
                if let Some(file) = current_file.as_mut() {
                    file.binary = true;
                }
                continue;
            }

            if let Some(hunk_info) = Self::parse_hunk_header(line) {
                if let Some(hunk) = current_hunk.take() {
                    if let Some(file) = current_file.as_mut() {
                        file.hunks.push(hunk);
                    }
                }

                old_line_no = hunk_info.old_start;
                new_line_no = hunk_info.new_start;

                current_hunk = Some(RawDiffHunk {
                    old_start: hunk_info.old_start,
                    old_count: hunk_info.old_count,
                    new_start: hunk_info.new_start,
                    new_count: hunk_info.new_count,
                    header: hunk_info.header,
                    lines: Vec::new(),
                });
                continue;
            }

            if let Some(hunk) = current_hunk.as_mut() {
                let first = line.chars().next();
                let (line_type, content, old_no, new_no) = match first {
                    Some('+') => {
                        let content = &line[1..];
                        let no = new_line_no;
                        new_line_no += 1;
                        (LineType::Addition, content, None, Some(no))
                    }
                    Some('-') => {
                        let content = &line[1..];
                        let no = old_line_no;
                        old_line_no += 1;
                        (LineType::Deletion, content, Some(no), None)
                    }
                    Some(' ') => {
                        let content = &line[1..];
                        let old = old_line_no;
                        let new_ = new_line_no;
                        old_line_no += 1;
                        new_line_no += 1;
                        (LineType::Context, content, Some(old), Some(new_))
                    }
                    Some('\\') => continue,
                    _ => continue,
                };

                hunk.lines.push(RawDiffLine {
                    line_type,
                    content: content.to_string(),
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
        &self,
        files: &'a [RawDiffFile],
        file_path: &str,
        line_no: i32,
        context_lines: i32,
    ) -> Option<(Vec<&'a RawDiffLine>, i32)> {
        for file in files {
            if file.new_path != file_path && file.old_path != file_path {
                continue;
            }

            for (hunk_idx, hunk) in file.hunks.iter().enumerate() {
                for (line_idx, line) in hunk.lines.iter().enumerate() {
                    if line.new_line_no == Some(line_no) || line.old_line_no == Some(line_no) {
                        let start = line_idx.saturating_sub(context_lines as usize);
                        let end = (line_idx + context_lines as usize + 1).min(hunk.lines.len());
                        let ctx: Vec<&RawDiffLine> = hunk.lines[start..end].iter().collect();
                        return Some((ctx, hunk_idx as i32));
                    }
                }
            }
        }

        None
    }

    pub fn count_changes(&self, files: &[RawDiffFile]) -> (i64, i64) {
        let mut additions = 0i64;
        let mut deletions = 0i64;

        for file in files {
            if file.binary {
                continue;
            }
            for hunk in &file.hunks {
                for line in &hunk.lines {
                    match line.line_type {
                        LineType::Addition => additions += 1,
                        LineType::Deletion => deletions += 1,
                        LineType::Context => {}
                    }
                }
            }
        }

        (additions, deletions)
    }

    fn parse_file_header(line: &str) -> Option<(String, String)> {
        let rest = line.strip_prefix("diff --git ")?;
        let sep = " b/";
        let sep_pos = rest.find(sep)?;
        let a_part = &rest[..sep_pos];
        let b_part = &rest[sep_pos + sep.len()..];

        let old_path = a_part.strip_prefix("a/").unwrap_or(a_part).to_string();
        let new_path = b_part.to_string();

        Some((old_path, new_path))
    }

    fn parse_hunk_header(line: &str) -> Option<HunkInfo> {
        let rest = line.strip_prefix("@@ -")?;
        let comma_pos = rest.find(',')?;
        let old_start: i32 = rest[..comma_pos].parse().ok()?;

        let after_comma = &rest[comma_pos + 1..];
        let space_pos = after_comma.find(" +")?;
        let old_count: i32 = if space_pos == 0 {
            1
        } else {
            after_comma[..space_pos].parse().unwrap_or(1)
        };

        let plus_part = &after_comma[space_pos + 2..];
        let space_or_end = plus_part.find(' ').unwrap_or(plus_part.len());
        let new_spec = &plus_part[..space_or_end];

        let (new_start, new_count) = if let Some(cpos) = new_spec.find(',') {
            let ns: i32 = new_spec[..cpos].parse().ok()?;
            let nc: i32 = new_spec[cpos + 1..].parse().unwrap_or(1);
            (ns, nc)
        } else {
            let ns: i32 = new_spec.parse().ok()?;
            (ns, 1)
        };

        let header = if space_or_end < plus_part.len() {
            plus_part[space_or_end..].to_string()
        } else {
            String::new()
        };

        Some(HunkInfo {
            old_start,
            old_count,
            new_start,
            new_count,
            header,
        })
    }

    fn parse_similarity(line: &str) -> Option<i32> {
        let rest = line.strip_prefix("similarity index ")?;
        let pct_str = rest.strip_suffix('%')?;
        pct_str.parse().ok()
    }

    fn parse_mode(mode_str: &str) -> u32 {
        u32::from_str_radix(mode_str.trim(), 8).unwrap_or(0)
    }
}

impl Default for UnifiedDiffParser {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_diff() {
        let diff = r#"diff --git a/src/main.rs b/src/main.rs
new file mode 100644
index 0000000..abc1234
--- /dev/null
+++ b/src/main.rs
@@ -0,0 +1,3 @@
+fn main() {
+    println!("hello");
++}
"#;
        let parser = UnifiedDiffParser::new();
        let files = parser.parse(diff).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].status, FileChangeStatus::Added);
        assert_eq!(files[0].new_path, "src/main.rs");
        assert_eq!(files[0].hunks.len(), 1);
        assert_eq!(files[0].hunks[0].lines.len(), 3);
        assert_eq!(files[0].hunks[0].lines[0].line_type, LineType::Addition);
    }

    #[test]
    fn test_count_changes() {
        let diff = r#"diff --git a/lib.rs b/lib.rs
index 1111111..2222222 100644
--- a/lib.rs
+++ b/lib.rs
@@ -1,3 +1,3 @@
 fn main() {
-    println!("old");
+    println!("new");
 }
"#;
        let parser = UnifiedDiffParser::new();
        let files = parser.parse(diff).unwrap();
        let (additions, deletions) = parser.count_changes(&files);
        assert_eq!(additions, 1);
        assert_eq!(deletions, 1);
    }
}
