use std::sync::Arc;
use dashmap::DashMap;
use serde_json::json;
use tracing::{info, warn, debug, error};
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use rand::Rng;

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::ports::mod::EventPublisherPort;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use crate::ports::mod::CloudSyncPort;

use super::model::{
    AggregationTask, AggregationFunction, DataPoint, AggregationResult,
    CreateTaskRequest, UpdateTaskRequest, TaskResponse, IngestDataRequest,
    TaskStatus, TimeWindow, WindowType, FilterRule, DedupConfig, SamplingConfig,
};

struct WindowState {
    points: Vec<DataPoint>,
    window_start: Option<DateTime<Utc>>,
    last_point_time: Option<DateTime<Utc>>,
}

struct DedupState {
    seen_keys: HashMap<String, DateTime<Utc>>,
}

pub struct DataAggregationService {
    tasks: Arc<DashMap<String, AggregationTask>>,
    results: Arc<DashMap<String, Vec<AggregationResult>>>,
    window_states: Arc<DashMap<String, WindowState>>,
    dedup_states: Arc<DashMap<String, DedupState>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    cloud_sync: Option<Arc<dyn CloudSyncPort>>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
}

impl DataAggregationService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        cloud_sync: Option<Arc<dyn CloudSyncPort>>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            tasks: Arc::new(DashMap::new()),
            results: Arc::new(DashMap::new()),
            window_states: Arc::new(DashMap::new()),
            dedup_states: Arc::new(DashMap::new()),
            event_publisher,
            cloud_sync,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "data_aggregation"),
        })
    }

    pub async fn create_task(&self, ctx: &RequestContext, req: CreateTaskRequest) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        debug!(task_name = %req.name, "Creating aggregation task");

        self.validate_create_request(&req)?;

        let task = AggregationTask::new(req);
        let task_id = task.task_id.clone();
        let task_name = task.name.clone();

        self.tasks.insert(task_id.clone(), task.clone());

        let event = DomainEvent::new(
            "aggregation.created",
            &task_id,
            json!({
                "task_id": task_id,
                "name": task_name,
                "status": format!("{:?}", TaskStatus::Created),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "aggregation.task.create",
            "data_aggregation",
            &task_id,
            true,
            json!({ "name": task_name }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_task_response(&task))
    }

    pub async fn start_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        info!(task_id = %task_id, "Starting aggregation task");

        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("聚合任务不存在: {}", task_id)))?;

        if task.is_running() {
            return Err(AppError::Conflict(format!("任务已在运行中: {}", task_id)));
        }

        task.start();

        self.window_states.insert(task_id.to_string(), WindowState {
            points: Vec::new(),
            window_start: None,
            last_point_time: None,
        });

        self.dedup_states.insert(task_id.to_string(), DedupState {
            seen_keys: HashMap::new(),
        });

        let task_clone = task.clone();

        let event = DomainEvent::new(
            "aggregation.started",
            task_id,
            json!({
                "task_id": task_id,
                "name": task.name.clone(),
                "started_at": task.started_at.map(|t| t.to_rfc3339()),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "aggregation.task.start",
            "data_aggregation",
            task_id,
            true,
            json!({ "status": format!("{:?}", task.status) }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_task_response(&task_clone))
    }

    pub async fn stop_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        info!(task_id = %task_id, "Stopping aggregation task");

        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("聚合任务不存在: {}", task_id)))?;

        if !task.is_running() {
            return Err(AppError::Conflict(format!("任务未在运行中: {}", task_id)));
        }

        task.stop();
        let task_clone = task.clone();

        self.window_states.remove(task_id);
        self.dedup_states.remove(task_id);

        self.audit_logger.log_operation(
            ctx,
            "aggregation.task.stop",
            "data_aggregation",
            task_id,
            true,
            json!({ "status": format!("{:?}", task.status) }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_task_response(&task_clone))
    }

    pub async fn delete_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<()> {
        let start = std::time::Instant::now();
        info!(task_id = %task_id, "Deleting aggregation task");

        if self.tasks.remove(task_id).is_none() {
            return Err(AppError::NotFound(format!("聚合任务不存在: {}", task_id)));
        }

        self.results.remove(task_id);
        self.window_states.remove(task_id);
        self.dedup_states.remove(task_id);

        let event = DomainEvent::new(
            "aggregation.deleted",
            task_id,
            json!({ "task_id": task_id }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "aggregation.task.delete",
            "data_aggregation",
            task_id,
            true,
            json!({}),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(())
    }

    pub async fn get_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        debug!(task_id = %task_id, "Getting aggregation task");

        let task = self.tasks.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("聚合任务不存在: {}", task_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "aggregation.task.get",
            "data_aggregation",
            task_id,
            true,
            json!({ "status": format!("{:?}", task.status) }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_task_response(&task))
    }

    pub async fn list_tasks(&self, page: u32, page_size: u32) -> AppResult<(Vec<TaskResponse>, u64)> {
        let items: Vec<TaskResponse> = self.tasks.iter()
            .map(|t| self.to_task_response(&t))
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn update_task(&self, ctx: &RequestContext, task_id: &str, req: UpdateTaskRequest) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        debug!(task_id = %task_id, "Updating aggregation task");

        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("聚合任务不存在: {}", task_id)))?;

        if task.is_running() {
            return Err(AppError::Conflict(format!("运行中的任务无法修改，请先停止: {}", task_id)));
        }

        if let Some(name) = req.name {
            task.name = name;
        }
        if let Some(description) = req.description {
            task.description = Some(description);
        }
        if let Some(data_source) = req.data_source {
            task.data_source = data_source;
        }
        if let Some(functions) = req.functions {
            task.functions = functions;
        }
        if let Some(time_window) = req.time_window {
            task.time_window = time_window;
        }
        if let Some(cloud_upload) = req.cloud_upload {
            task.cloud_upload = cloud_upload;
        }
        if let Some(upload_endpoint) = req.upload_endpoint {
            task.upload_endpoint = Some(upload_endpoint);
        }
        if let Some(tags) = req.tags {
            task.tags = Some(tags);
        }

        let task_clone = task.clone();

        self.audit_logger.log_operation(
            ctx,
            "aggregation.task.update",
            "data_aggregation",
            task_id,
            true,
            json!({}),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_task_response(&task_clone))
    }

    pub async fn ingest_data(&self, ctx: &RequestContext, req: IngestDataRequest) -> AppResult<serde_json::Value> {
        let start = std::time::Instant::now();
        debug!(task_id = %req.task_id, value = %req.value, "Ingesting data point");

        let task = self.tasks.get(&req.task_id)
            .ok_or_else(|| AppError::NotFound(format!("聚合任务不存在: {}", req.task_id)))?
            .clone();

        if !task.is_running() {
            return Err(AppError::Conflict(format!("任务未运行，无法接收数据: {}", req.task_id)));
        }

        let point = DataPoint::new(
            req.task_id.clone(),
            req.value,
            req.timestamp,
            req.fields,
        );

        if !self.apply_filters(&task.data_source.filter_rule, &point.fields) {
            self.metrics.record_success(start.elapsed().as_millis() as u64);
            return Ok(json!({ "status": "filtered", "task_id": req.task_id }));
        }

        if let Some(dedup_config) = &task.data_source.dedup_config {
            if dedup_config.enabled && self.is_duplicate(&req.task_id, dedup_config, &point.fields) {
                self.metrics.record_success(start.elapsed().as_millis() as u64);
                return Ok(json!({ "status": "duplicate", "task_id": req.task_id }));
            }
        }

        if let Some(sampling_config) = &task.data_source.sampling_config {
            if sampling_config.enabled && !self.should_sample(sampling_config) {
                self.metrics.record_success(start.elapsed().as_millis() as u64);
                return Ok(json!({ "status": "sampled_out", "task_id": req.task_id }));
            }
        }

        self.add_to_window(&req.task_id, point.clone(), &task.time_window).await?;

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(json!({
            "status": "accepted",
            "task_id": req.task_id,
            "point_id": point.point_id,
        }))
    }

    pub async fn ingest_batch(&self, ctx: &RequestContext, task_id: String, points: Vec<IngestDataRequest>) -> AppResult<serde_json::Value> {
        let start = std::time::Instant::now();
        info!(task_id = %task_id, count = points.len(), "Ingesting data batch");

        let mut accepted = 0;
        let mut filtered = 0;
        let mut duplicate = 0;
        let mut sampled_out = 0;

        for req in points {
            let result = self.ingest_data(ctx, req).await;
            match result {
                Ok(v) => {
                    let status = v["status"].as_str().unwrap_or("unknown");
                    match status {
                        "accepted" => accepted += 1,
                        "filtered" => filtered += 1,
                        "duplicate" => duplicate += 1,
                        "sampled_out" => sampled_out += 1,
                        _ => {}
                    }
                }
                Err(e) => {
                    warn!(error = %e, "Failed to ingest data point");
                }
            }
        }

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(json!({
            "task_id": task_id,
            "total": accepted + filtered + duplicate + sampled_out,
            "accepted": accepted,
            "filtered": filtered,
            "duplicate": duplicate,
            "sampled_out": sampled_out,
        }))
    }

    pub async fn get_results(
        &self,
        ctx: &RequestContext,
        task_id: &str,
        start_time: Option<DateTime<Utc>>,
        end_time: Option<DateTime<Utc>>,
        page: u32,
        page_size: u32,
    ) -> AppResult<(Vec<AggregationResult>, u64)> {
        let start = std::time::Instant::now();
        debug!(task_id = %task_id, "Querying aggregation results");

        let _task = self.tasks.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("聚合任务不存在: {}", task_id)))?;

        let all_results = self.results.get(task_id)
            .map(|r| r.clone())
            .unwrap_or_default();

        let filtered: Vec<AggregationResult> = all_results.into_iter()
            .filter(|r| {
                if let Some(st) = start_time {
                    if r.window_start < st {
                        return false;
                    }
                }
                if let Some(et) = end_time {
                    if r.window_end > et {
                        return false;
                    }
                }
                true
            })
            .collect();

        let total = filtered.len() as u64;
        let start_idx = ((page - 1) * page_size) as usize;
        let end_idx = (start_idx + page_size as usize).min(filtered.len());
        let paginated = filtered.into_iter().skip(start_idx).take(end_idx - start_idx).collect();

        self.audit_logger.log_operation(
            ctx,
            "aggregation.results.query",
            "data_aggregation",
            task_id,
            true,
            json!({ "count": total }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok((paginated, total))
    }

    async fn add_to_window(&self, task_id: &str, point: DataPoint, time_window: &TimeWindow) -> AppResult<()> {
        let mut window_state = self.window_states.get_mut(task_id)
            .ok_or_else(|| AppError::Internal(format!("窗口状态不存在: {}", task_id)))?;

        window_state.last_point_time = Some(point.timestamp);

        if window_state.window_start.is_none() {
            window_state.window_start = Some(point.timestamp);
        }

        match time_window.window_type {
            WindowType::Tumbling => {
                self.process_tumbling_window(task_id, point, time_window, &mut window_state).await?;
            }
            WindowType::Sliding => {
                self.process_sliding_window(task_id, point, time_window, &mut window_state).await?;
            }
            WindowType::Session => {
                self.process_session_window(task_id, point, time_window, &mut window_state).await?;
            }
        }

        Ok(())
    }

    async fn process_tumbling_window(
        &self,
        task_id: &str,
        point: DataPoint,
        time_window: &TimeWindow,
        window_state: &mut WindowState,
    ) -> AppResult<()> {
        let window_start = window_state.window_start.unwrap();
        let window_end = window_start + chrono::Duration::milliseconds(time_window.duration_ms as i64);

        if point.timestamp >= window_end {
            self.generate_and_store_result(task_id, window_start, window_end, &window_state.points).await?;
            window_state.points.clear();
            window_state.window_start = Some(point.timestamp);
        }

        window_state.points.push(point);
        Ok(())
    }

    async fn process_sliding_window(
        &self,
        task_id: &str,
        point: DataPoint,
        time_window: &TimeWindow,
        window_state: &mut WindowState,
    ) -> AppResult<()> {
        let slide_ms = time_window.slide_ms.unwrap_or(time_window.duration_ms / 2);
        let mut current_start = window_state.window_start.unwrap();

        while point.timestamp >= current_start + chrono::Duration::milliseconds(slide_ms as i64) {
            let window_end = current_start + chrono::Duration::milliseconds(time_window.duration_ms as i64);
            let window_points: Vec<DataPoint> = window_state.points.iter()
                .filter(|p| p.timestamp >= current_start && p.timestamp < window_end)
                .cloned()
                .collect();

            if !window_points.is_empty() {
                self.generate_and_store_result(task_id, current_start, window_end, &window_points).await?;
            }

            current_start = current_start + chrono::Duration::milliseconds(slide_ms as i64);
            window_state.window_start = Some(current_start);
        }

        let cutoff = current_start - chrono::Duration::milliseconds(time_window.duration_ms as i64);
        window_state.points.retain(|p| p.timestamp >= cutoff);
        window_state.points.push(point);
        Ok(())
    }

    async fn process_session_window(
        &self,
        task_id: &str,
        point: DataPoint,
        time_window: &TimeWindow,
        window_state: &mut WindowState,
    ) -> AppResult<()> {
        let gap_ms = time_window.gap_ms.unwrap_or(30000);

        if let Some(last_time) = window_state.last_point_time {
            let gap = (point.timestamp - last_time).num_milliseconds();
            if gap > gap_ms as i64 {
                if let Some(window_start) = window_state.window_start {
                    self.generate_and_store_result(task_id, window_start, last_time, &window_state.points).await?;
                }
                window_state.points.clear();
                window_state.window_start = Some(point.timestamp);
            }
        }

        window_state.points.push(point);
        Ok(())
    }

    async fn generate_and_store_result(
        &self,
        task_id: &str,
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
        points: &[DataPoint],
    ) -> AppResult<()> {
        let task = self.tasks.get(task_id)
            .ok_or_else(|| AppError::Internal(format!("任务不存在: {}", task_id)))?;

        if points.is_empty() {
            return Ok(());
        }

        let group_keys = self.extract_group_keys(&task.data_source.group_by_fields, points);

        let function_results = self.compute_aggregations(points, &task.functions);

        let mut result = AggregationResult::new(
            task_id.to_string(),
            window_start,
            window_end,
            task.time_window.window_type.clone(),
            function_results,
            points.len() as u64,
            group_keys,
        );

        if task.cloud_upload {
            if let Some(cloud_sync) = &self.cloud_sync {
                match cloud_sync.upload_data(json!(&result)).await {
                    Ok(_) => {
                        result.mark_uploaded();
                        let upload_event = DomainEvent::new(
                            "data.uploaded",
                            task_id,
                            json!({
                                "result_id": result.result_id,
                                "window_start": window_start.to_rfc3339(),
                                "window_end": window_end.to_rfc3339(),
                                "count": result.count,
                            }),
                            "system",
                        );
                        if let Err(e) = self.event_publisher.publish(upload_event).await {
                            error!(error = %e, "Failed to publish upload event");
                        }
                    }
                    Err(e) => {
                        warn!(error = %e, result_id = %result.result_id, "Failed to upload aggregation result to cloud");
                    }
                }
            }
        }

        let result_event = DomainEvent::new(
            "aggregation.result.generated",
            task_id,
            json!({
                "result_id": result.result_id,
                "window_start": window_start.to_rfc3339(),
                "window_end": window_end.to_rfc3339(),
                "count": result.count,
                "functions": result.function_results,
                "uploaded": result.uploaded,
            }),
            "system",
        );
        if let Err(e) = self.event_publisher.publish(result_event).await {
            error!(error = %e, "Failed to publish result generated event");
        }

        self.results.entry(task_id.to_string())
            .or_default()
            .push(result);

        if let Some(mut task) = self.tasks.get_mut(task_id) {
            task.last_result_at = Some(Utc::now());
        }

        info!(
            task_id = %task_id,
            window_start = %window_start.to_rfc3339(),
            window_end = %window_end.to_rfc3339(),
            count = points.len(),
            "Generated aggregation result"
        );

        Ok(())
    }

    fn compute_aggregations(&self, points: &[DataPoint], functions: &[AggregationFunction]) -> HashMap<String, serde_json::Value> {
        let mut results = HashMap::new();
        let values: Vec<f64> = points.iter().map(|p| p.value).collect();

        for func in functions {
            let name = func.name();
            let value = match func {
                AggregationFunction::Count => json!(points.len() as u64),
                AggregationFunction::Sum => json!(values.iter().sum::<f64>()),
                AggregationFunction::Avg => {
                    if !values.is_empty() {
                        json!(values.iter().sum::<f64>() / values.len() as f64)
                    } else {
                        json!(0.0)
                    }
                }
                AggregationFunction::Min => json!(values.iter().cloned().fold(f64::INFINITY, f64::min)),
                AggregationFunction::Max => json!(values.iter().cloned().fold(f64::NEG_INFINITY, f64::max)),
                AggregationFunction::Stddev => {
                    if values.len() >= 2 {
                        let mean = values.iter().sum::<f64>() / values.len() as f64;
                        let variance: f64 = values.iter()
                            .map(|v| (v - mean).powi(2))
                            .sum::<f64>() / (values.len() - 1) as f64;
                        json!(variance.sqrt())
                    } else {
                        json!(0.0)
                    }
                }
                AggregationFunction::Percentile(p) => {
                    json!(self.calculate_percentile(&values, *p))
                }
                AggregationFunction::First => {
                    points.first().map(|p| json!(p.value)).unwrap_or(json!(null))
                }
                AggregationFunction::Last => {
                    points.last().map(|p| json!(p.value)).unwrap_or(json!(null))
                }
            };
            results.insert(name, value);
        }

        results
    }

    fn calculate_percentile(&self, values: &[f64], percentile: f64) -> f64 {
        if values.is_empty() {
            return 0.0;
        }
        let mut sorted = values.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        let index = (percentile / 100.0 * (sorted.len() - 1) as f64) as usize;
        sorted[index.min(sorted.len() - 1)]
    }

    fn apply_filters(&self, filter_rule: &Option<FilterRule>, fields: &HashMap<String, Value>) -> bool {
        let Some(rule) = filter_rule else { return true; };
        rule.matches(fields)
    }

    fn is_duplicate(&self, task_id: &str, dedup_config: &DedupConfig, fields: &HashMap<String, Value>) -> bool {
        let mut dedup_state = match self.dedup_states.get_mut(task_id) {
            Some(s) => s,
            None => return false,
        };

        let key: String = dedup_config.fields.iter()
            .filter_map(|f| fields.get(f).map(|v| v.to_string()))
            .collect::<Vec<_>>()
            .join("|");

        if key.is_empty() {
            return false;
        }

        let now = Utc::now();
        let cutoff = now - chrono::Duration::milliseconds(dedup_config.window_ms as i64);

        if let Some(&last_seen) = dedup_state.seen_keys.get(&key) {
            if last_seen >= cutoff {
                return true;
            }
        }

        dedup_state.seen_keys.insert(key, now);
        dedup_state.seen_keys.retain(|_, &mut t| t >= cutoff);
        false
    }

    fn should_sample(&self, sampling_config: &SamplingConfig) -> bool {
        if sampling_config.rate <= 0.0 {
            return false;
        }
        if sampling_config.rate >= 1.0 {
            return true;
        }
        let mut rng = rand::thread_rng();
        rng.gen::<f64>() < sampling_config.rate
    }

    fn extract_group_keys(
        &self,
        group_by_fields: &Option<Vec<String>>,
        points: &[DataPoint],
    ) -> Option<HashMap<String, serde_json::Value>> {
        let fields = group_by_fields.as_ref()?;
        if fields.is_empty() || points.is_empty() {
            return None;
        }

        let first_point = points.first()?;
        let mut keys = HashMap::new();
        for field in fields {
            if let Some(value) = first_point.fields.get(field) {
                keys.insert(field.clone(), value.clone());
            }
        }

        if keys.is_empty() {
            None
        } else {
            Some(keys)
        }
    }

    fn to_task_response(&self, task: &AggregationTask) -> TaskResponse {
        TaskResponse {
            task_id: task.task_id.clone(),
            name: task.name.clone(),
            description: task.description.clone(),
            status: task.status.clone(),
            data_source_name: task.data_source.name.clone(),
            function_count: task.functions.len(),
            window_type: task.time_window.window_type.clone(),
            window_duration_ms: task.time_window.duration_ms,
            cloud_upload: task.cloud_upload,
            created_at: task.created_at,
            started_at: task.started_at,
            last_result_at: task.last_result_at,
        }
    }

    fn validate_create_request(&self, req: &CreateTaskRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("任务名称不能为空".into()));
        }
        if req.functions.is_empty() {
            return Err(AppError::Validation("至少需要指定一个聚合函数".into()));
        }
        if req.data_source.name.is_empty() {
            return Err(AppError::Validation("数据源名称不能为空".into()));
        }
        if req.data_source.value_field.is_empty() {
            return Err(AppError::Validation("值字段不能为空".into()));
        }
        if req.data_source.timestamp_field.is_empty() {
            return Err(AppError::Validation("时间戳字段不能为空".into()));
        }
        match req.time_window.window_type {
            WindowType::Tumbling | WindowType::Sliding => {
                if req.time_window.duration_ms == 0 {
                    return Err(AppError::Validation("窗口持续时间必须大于0".into()));
                }
            }
            WindowType::Session => {
                if req.time_window.gap_ms.unwrap_or(0) == 0 {
                    return Err(AppError::Validation("会话窗口间隙时间必须大于0".into()));
                }
            }
        }
        if req.cloud_upload && req.upload_endpoint.is_none() {
            return Err(AppError::Validation("启用云端上传时必须指定上传端点".into()));
        }
        Ok(())
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }
}
