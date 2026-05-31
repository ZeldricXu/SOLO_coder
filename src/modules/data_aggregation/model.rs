use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use serde_json::Value;
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AggregationFunction {
    Count,
    Sum,
    Avg,
    Min,
    Max,
    Stddev,
    Percentile(f64),
    First,
    Last,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum WindowType {
    Tumbling,
    Sliding,
    Session,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeWindow {
    pub window_type: WindowType,
    pub duration_ms: u64,
    pub slide_ms: Option<u64>,
    pub gap_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum FilterOperator {
    Eq,
    Neq,
    Gt,
    Gte,
    Lt,
    Lte,
    Contains,
    StartsWith,
    EndsWith,
    In,
    NotIn,
    IsNull,
    IsNotNull,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterCondition {
    pub field: String,
    pub operator: FilterOperator,
    pub value: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterRule {
    pub conditions: Vec<FilterCondition>,
    pub combinator: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DedupConfig {
    pub enabled: bool,
    pub fields: Vec<String>,
    pub window_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SamplingConfig {
    pub enabled: bool,
    pub rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataSource {
    pub name: String,
    pub source_type: String,
    pub endpoint: Option<String>,
    pub frequency_ms: u64,
    pub filter_rule: Option<FilterRule>,
    pub dedup_config: Option<DedupConfig>,
    pub sampling_config: Option<SamplingConfig>,
    pub value_field: String,
    pub timestamp_field: String,
    pub group_by_fields: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskStatus {
    Created,
    Running,
    Stopped,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationTask {
    pub task_id: String,
    pub name: String,
    pub description: Option<String>,
    pub data_source: DataSource,
    pub functions: Vec<AggregationFunction>,
    pub time_window: TimeWindow,
    pub status: TaskStatus,
    pub output_fields: Option<Vec<String>>,
    pub cloud_upload: bool,
    pub upload_endpoint: Option<String>,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub last_result_at: Option<DateTime<Utc>>,
    pub error_message: Option<String>,
    pub tags: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPoint {
    pub point_id: String,
    pub task_id: String,
    pub timestamp: DateTime<Utc>,
    pub value: f64,
    pub fields: HashMap<String, Value>,
    pub received_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationResult {
    pub result_id: String,
    pub task_id: String,
    pub window_start: DateTime<Utc>,
    pub window_end: DateTime<Utc>,
    pub window_type: WindowType,
    pub function_results: HashMap<String, Value>,
    pub count: u64,
    pub group_keys: Option<HashMap<String, Value>>,
    pub generated_at: DateTime<Utc>,
    pub uploaded: bool,
    pub uploaded_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateTaskRequest {
    pub name: String,
    pub description: Option<String>,
    pub data_source: DataSource,
    pub functions: Vec<AggregationFunction>,
    pub time_window: TimeWindow,
    pub output_fields: Option<Vec<String>>,
    pub cloud_upload: bool,
    pub upload_endpoint: Option<String>,
    pub tags: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateTaskRequest {
    pub name: Option<String>,
    pub description: Option<String>,
    pub data_source: Option<DataSource>,
    pub functions: Option<Vec<AggregationFunction>>,
    pub time_window: Option<TimeWindow>,
    pub cloud_upload: Option<bool>,
    pub upload_endpoint: Option<String>,
    pub tags: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResponse {
    pub task_id: String,
    pub name: String,
    pub description: Option<String>,
    pub status: TaskStatus,
    pub data_source_name: String,
    pub function_count: usize,
    pub window_type: WindowType,
    pub window_duration_ms: u64,
    pub cloud_upload: bool,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub last_result_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IngestDataRequest {
    pub task_id: String,
    pub timestamp: Option<DateTime<Utc>>,
    pub value: f64,
    pub fields: Option<HashMap<String, Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IngestBatchRequest {
    pub task_id: String,
    pub points: Vec<IngestDataRequest>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResultQueryRequest {
    pub task_id: String,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub page: Option<u32>,
    pub page_size: Option<u32>,
}

impl AggregationTask {
    pub fn new(req: CreateTaskRequest) -> Self {
        let now = Utc::now();
        Self {
            task_id: Uuid::new_v4().to_string(),
            name: req.name,
            description: req.description,
            data_source: req.data_source,
            functions: req.functions,
            time_window: req.time_window,
            status: TaskStatus::Created,
            output_fields: req.output_fields,
            cloud_upload: req.cloud_upload,
            upload_endpoint: req.upload_endpoint,
            created_at: now,
            started_at: None,
            last_result_at: None,
            error_message: None,
            tags: req.tags,
        }
    }

    pub fn start(&mut self) {
        self.status = TaskStatus::Running;
        self.started_at = Some(Utc::now());
        self.error_message = None;
    }

    pub fn stop(&mut self) {
        self.status = TaskStatus::Stopped;
    }

    pub fn set_error(&mut self, error: String) {
        self.status = TaskStatus::Error;
        self.error_message = Some(error);
    }

    pub fn is_running(&self) -> bool {
        matches!(self.status, TaskStatus::Running)
    }
}

impl DataPoint {
    pub fn new(task_id: String, value: f64, timestamp: Option<DateTime<Utc>>, fields: Option<HashMap<String, Value>>) -> Self {
        Self {
            point_id: Uuid::new_v4().to_string(),
            task_id,
            timestamp: timestamp.unwrap_or_else(|| Utc::now()),
            value,
            fields: fields.unwrap_or_default(),
            received_at: Utc::now(),
        }
    }
}

impl AggregationResult {
    pub fn new(
        task_id: String,
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
        window_type: WindowType,
        function_results: HashMap<String, Value>,
        count: u64,
        group_keys: Option<HashMap<String, Value>>,
    ) -> Self {
        Self {
            result_id: Uuid::new_v4().to_string(),
            task_id,
            window_start,
            window_end,
            window_type,
            function_results,
            count,
            group_keys,
            generated_at: Utc::now(),
            uploaded: false,
            uploaded_at: None,
        }
    }

    pub fn mark_uploaded(&mut self) {
        self.uploaded = true;
        self.uploaded_at = Some(Utc::now());
    }
}

impl TimeWindow {
    pub fn tumbling(duration_ms: u64) -> Self {
        Self {
            window_type: WindowType::Tumbling,
            duration_ms,
            slide_ms: None,
            gap_ms: None,
        }
    }

    pub fn sliding(duration_ms: u64, slide_ms: u64) -> Self {
        Self {
            window_type: WindowType::Sliding,
            duration_ms,
            slide_ms: Some(slide_ms),
            gap_ms: None,
        }
    }

    pub fn session(gap_ms: u64) -> Self {
        Self {
            window_type: WindowType::Session,
            duration_ms: 0,
            slide_ms: None,
            gap_ms: Some(gap_ms),
        }
    }
}

impl FilterRule {
    pub fn matches(&self, fields: &HashMap<String, Value>) -> bool {
        let combinator = self.combinator.as_deref().unwrap_or("and");
        let results: Vec<bool> = self.conditions.iter()
            .map(|c| c.evaluate(fields))
            .collect();

        match combinator {
            "or" => results.iter().any(|&r| r),
            _ => results.iter().all(|&r| r),
        }
    }
}

impl FilterCondition {
    pub fn evaluate(&self, fields: &HashMap<String, Value>) -> bool {
        let field_value = fields.get(&self.field);

        match self.operator {
            FilterOperator::IsNull => field_value.is_none() || matches!(field_value, Some(Value::Null)),
            FilterOperator::IsNotNull => field_value.is_some() && !matches!(field_value, Some(Value::Null)),
            _ => {
                let Some(field_value) = field_value else { return false; };
                let Some(cond_value) = &self.value else { return false; };
                self.compare(field_value, cond_value)
            }
        }
    }

    fn compare(&self, a: &Value, b: &Value) -> bool {
        match self.operator {
            FilterOperator::Eq => a == b,
            FilterOperator::Neq => a != b,
            FilterOperator::Gt => compare_numbers(a, b, |x, y| x > y),
            FilterOperator::Gte => compare_numbers(a, b, |x, y| x >= y),
            FilterOperator::Lt => compare_numbers(a, b, |x, y| x < y),
            FilterOperator::Lte => compare_numbers(a, b, |x, y| x <= y),
            FilterOperator::Contains => {
                match (a.as_str(), b.as_str()) {
                    (Some(a_str), Some(b_str)) => a_str.contains(b_str),
                    _ => false,
                }
            }
            FilterOperator::StartsWith => {
                match (a.as_str(), b.as_str()) {
                    (Some(a_str), Some(b_str)) => a_str.starts_with(b_str),
                    _ => false,
                }
            }
            FilterOperator::EndsWith => {
                match (a.as_str(), b.as_str()) {
                    (Some(a_str), Some(b_str)) => a_str.ends_with(b_str),
                    _ => false,
                }
            }
            FilterOperator::In => {
                match b.as_array() {
                    Some(arr) => arr.iter().any(|x| x == a),
                    None => false,
                }
            }
            FilterOperator::NotIn => {
                match b.as_array() {
                    Some(arr) => !arr.iter().any(|x| x == a),
                    None => false,
                }
            }
            _ => false,
        }
    }
}

fn compare_numbers(a: &Value, b: &Value, cmp: fn(f64, f64) -> bool) -> bool {
    let a_num = a.as_f64().or_else(|| a.as_i64().map(|x| x as f64)).or_else(|| a.as_u64().map(|x| x as f64));
    let b_num = b.as_f64().or_else(|| b.as_i64().map(|x| x as f64)).or_else(|| b.as_u64().map(|x| x as f64));

    match (a_num, b_num) {
        (Some(a), Some(b)) => cmp(a, b),
        _ => false,
    }
}

impl AggregationFunction {
    pub fn name(&self) -> String {
        match self {
            AggregationFunction::Count => "count".to_string(),
            AggregationFunction::Sum => "sum".to_string(),
            AggregationFunction::Avg => "avg".to_string(),
            AggregationFunction::Min => "min".to_string(),
            AggregationFunction::Max => "max".to_string(),
            AggregationFunction::Stddev => "stddev".to_string(),
            AggregationFunction::Percentile(p) => format!("percentile_{}", p),
            AggregationFunction::First => "first".to_string(),
            AggregationFunction::Last => "last".to_string(),
        }
    }
}
