use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize)]
pub struct QueryRequest {
    pub query: String,
    pub time: Option<i64>,
    pub timeout: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RangeQueryRequest {
    pub query: String,
    pub start: i64,
    pub end: i64,
    pub step: String,
    pub timeout: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ApiResponse<T> {
    pub status: String,
    pub data: Option<T>,
    pub error_type: Option<String>,
    pub error: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            status: "success".to_string(),
            data: Some(data),
            error_type: None,
            error: None,
        }
    }

    pub fn error(error_type: String, error: String) -> Self {
        Self {
            status: "error".to_string(),
            data: None,
            error_type: Some(error_type),
            error: Some(error),
        }
    }
}

#[derive(Debug, Serialize)]
pub struct SeriesData {
    pub result_type: String,
    pub result: Vec<SeriesResult>,
}

#[derive(Debug, Serialize)]
pub struct SeriesResult {
    pub metric: std::collections::BTreeMap<String, String>,
    pub values: Vec<(i64, String)>,
}

#[derive(Debug, Serialize)]
pub struct LabelNamesResponse {
    pub status: String,
    pub data: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct LabelValuesResponse {
    pub status: String,
    pub data: Vec<String>,
}
