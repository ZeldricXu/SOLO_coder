use std::collections::HashMap;

pub trait FilePositioned {
    fn file_path(&self) -> Option<&str>;
    fn line_no(&self) -> Option<i32>;
}

pub struct CommentGrouper;

impl CommentGrouper {
    pub fn group_by_file<'a, T: FilePositioned>(items: &'a [T]) -> HashMap<String, Vec<&'a T>> {
        let mut grouped: HashMap<String, Vec<&'a T>> = HashMap::new();
        for item in items {
            if let Some(fp) = item.file_path() {
                grouped.entry(fp.to_string()).or_default().push(item);
            }
        }
        grouped
    }

    pub fn group_by_file_and_line<'a, T: FilePositioned>(
        items: &'a [T],
    ) -> HashMap<(String, Option<i32>), Vec<&'a T>> {
        let mut grouped: HashMap<(String, Option<i32>), Vec<&'a T>> = HashMap::new();
        for item in items {
            if let Some(fp) = item.file_path() {
                let key = (fp.to_string(), item.line_no());
                grouped.entry(key).or_default().push(item);
            }
        }
        grouped
    }
}
