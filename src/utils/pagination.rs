use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PageInfo {
    pub page: i32,
    pub per_page: i32,
    pub total: i64,
    pub total_pages: i32,
    pub has_next: bool,
    pub has_prev: bool,
}

impl PageInfo {
    pub fn new(page: i32, per_page: i32, total: i64) -> Self {
        let total_pages = (total as f64 / per_page as f64).ceil() as i32;
        Self {
            page,
            per_page,
            total,
            total_pages,
            has_next: page < total_pages,
            has_prev: page > 1,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PaginatedResult<T> {
    pub items: Vec<T>,
    pub page_info: PageInfo,
}

impl<T> PaginatedResult<T> {
    pub fn new(items: Vec<T>, page: i32, per_page: i32, total: i64) -> Self {
        Self {
            items,
            page_info: PageInfo::new(page, per_page, total),
        }
    }

    pub fn map<U, F: Fn(T) -> U>(self, f: F) -> PaginatedResult<U> {
        PaginatedResult {
            items: self.items.into_iter().map(f).collect(),
            page_info: self.page_info,
        }
    }
}
