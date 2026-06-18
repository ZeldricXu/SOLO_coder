use review_diff::{DiffFile, DiffAdapter, DiffError};
use crate::locator::CodeLocator;
use crate::types::{CodePosition, FileLocation};

pub struct ReviewDataQuery {
    adapter: DiffAdapter,
}

impl ReviewDataQuery {
    pub fn new() -> Self {
        Self { adapter: DiffAdapter::new() }
    }

    pub fn parse_diff(&self, diff_text: &str) -> Result<Vec<DiffFile>, DiffError> {
        self.adapter.parse(diff_text)
    }

    pub fn locate_code<'a>(
        &self,
        diff_files: &'a [DiffFile],
        file_path: &str,
        line_no: Option<i32>,
    ) -> Option<CodePosition> {
        CodeLocator::locate(diff_files, &FileLocation {
            file_path: file_path.to_string(),
            line_no,
        })
    }

    pub fn get_code_context<'a>(
        &self,
        diff_files: &'a [DiffFile],
        file_path: &str,
        line_no: i32,
        context_lines: i32,
    ) -> Option<(Vec<&'a review_diff::DiffLine>, i32)> {
        CodeLocator::get_context(diff_files, file_path, line_no, context_lines)
    }

    pub fn validate_location(
        &self,
        diff_files: &[DiffFile],
        file_path: &str,
        line_no: Option<i32>,
    ) -> bool {
        if !CodeLocator::file_exists(diff_files, file_path) {
            return false;
        }
        if let Some(no) = line_no {
            CodeLocator::line_in_diff(diff_files, file_path, no)
        } else {
            true
        }
    }

    pub fn count_changes(&self, files: &[DiffFile]) -> (i64, i64) {
        self.adapter.count_changes(files)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_DIFF: &str = "\
diff --git a/main.rs b/main.rs
--- a/main.rs
+++ b/main.rs
@@ -1,3 +1,4 @@
 fn main() {
-    old_call();
+    new_call();
+    extra();
 }
";

    #[test]
    fn test_parse_and_locate() {
        let query = ReviewDataQuery::new();
        let files = query.parse_diff(SAMPLE_DIFF).unwrap();
        assert_eq!(files.len(), 1);

        let pos = query.locate_code(&files, "main.rs", Some(2));
        assert!(pos.is_some());
        let p = pos.unwrap();
        assert_eq!(p.file_path, "main.rs");
        assert!(p.line_content.is_some());
    }

    #[test]
    fn test_validate_location() {
        let query = ReviewDataQuery::new();
        let files = query.parse_diff(SAMPLE_DIFF).unwrap();

        assert!(query.validate_location(&files, "main.rs", None));
        assert!(query.validate_location(&files, "main.rs", Some(2)));
        assert!(!query.validate_location(&files, "nonexistent.rs", None));
    }

    #[test]
    fn test_count_changes() {
        let query = ReviewDataQuery::new();
        let files = query.parse_diff(SAMPLE_DIFF).unwrap();
        let (add, del) = query.count_changes(&files);
        assert_eq!(add, 2);
        assert_eq!(del, 1);
    }

    #[test]
    fn test_get_code_context() {
        let query = ReviewDataQuery::new();
        let files = query.parse_diff(SAMPLE_DIFF).unwrap();

        let result = query.get_code_context(&files, "main.rs", 2, 1);
        assert!(result.is_some());
        let (lines, hunk_idx) = result.unwrap();
        assert_eq!(hunk_idx, 0);
        assert!(lines.len() >= 1);
    }
}
