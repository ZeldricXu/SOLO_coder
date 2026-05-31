use crate::types::{
    AppError, AppResult, CoreEntity, EntityStatus, HandlerRequest, HandlerResponse, MetricsData,
    MetricsSnapshot, RunInstance, RunPhase, generate_id, now_utc,
};
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;
use tokio::time::timeout;

pub struct ProcessingContext {
    pub trace_id: String,
    pub namespace: String,
    pub started_at: Instant,
    pub attributes: HashMap<String, serde_json::Value>,
    pub rollback_actions: Vec<Box<dyn FnOnce() + Send + Sync>>,
}

impl ProcessingContext {
    pub fn new(trace_id: &str, namespace: &str) -> Self {
        Self {
            trace_id: trace_id.to_string(),
            namespace: namespace.to_string(),
            started_at: Instant::now(),
            attributes: HashMap::new(),
            rollback_actions: Vec::new(),
        }
    }

    pub fn add_rollback<F>(&mut self, action: F)
    where
        F: FnOnce() + Send + Sync + 'static,
    {
        self.rollback_actions.push(Box::new(action));
    }

    pub fn rollback(&mut self) {
        while let Some(action) = self.rollback_actions.pop() {
            action();
        }
    }

    pub fn elapsed(&self) -> Duration {
        self.started_at.elapsed()
    }
}

pub struct ResourcePool {
    semaphore: Semaphore,
    pool_size: usize,
}

impl ResourcePool {
    pub fn new(pool_size: usize) -> Self {
        Self {
            semaphore: Semaphore::new(pool_size),
            pool_size,
        }
    }

    pub async fn acquire(&self) -> AppResult<ResourceGuard> {
        let permit = self
            .semaphore
            .acquire()
            .await
            .map_err(|e| AppError::InternalError(format!("获取资源失败: {}", e)))?;

        Ok(ResourceGuard {
            permit,
            pool_size: self.pool_size,
        })
    }

    pub fn available_permits(&self) -> usize {
        self.semaphore.available_permits()
    }

    pub fn pool_size(&self) -> usize {
        self.pool_size
    }
}

pub struct ResourceGuard<'a> {
    permit: tokio::sync::SemaphorePermit<'a>,
    pool_size: usize,
}

impl<'a> Drop for ResourceGuard<'a> {
    fn drop(&mut self) {
        self.permit.forget();
    }
}

pub struct Event {
    pub event_id: String,
    pub event_type: String,
    pub aggregate_id: String,
    pub payload: serde_json::Value,
    pub metadata: HashMap<String, String>,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

pub trait EventEmitter: Send + Sync {
    fn emit(&self, event: Event);
}

pub struct InMemoryEventEmitter {
    events: DashMap<String, Vec<Event>>,
}

impl InMemoryEventEmitter {
    pub fn new() -> Self {
        Self {
            events: DashMap::new(),
        }
    }

    pub fn get_events(&self, aggregate_id: &str) -> Vec<Event> {
        self.events
            .get(aggregate_id)
            .map(|e| e.clone())
            .unwrap_or_default()
    }

    pub fn all_events(&self) -> Vec<Event> {
        let mut all = Vec::new();
        for entry in self.events.iter() {
            all.extend(entry.value().clone());
        }
        all
    }
}

impl EventEmitter for InMemoryEventEmitter {
    fn emit(&self, event: Event) {
        self.events
            .entry(event.aggregate_id.clone())
            .or_default()
            .push(event);
    }
}

impl Default for InMemoryEventEmitter {
    fn default() -> Self {
        Self::new()
    }
}

pub struct MetricsRecorder {
    snapshots: DashMap<String, MetricsSnapshot>,
    counters: DashMap<String, u64>,
    timers: DashMap<String, Vec<u64>>,
}

impl MetricsRecorder {
    pub fn new() -> Self {
        Self {
            snapshots: DashMap::new(),
            counters: DashMap::new(),
            timers: DashMap::new(),
        }
    }

    pub fn increment_counter(&self, key: &str, amount: u64) {
        *self.counters.entry(key.to_string()).or_insert(0) += amount;
    }

    pub fn record_timer(&self, key: &str, duration_ms: u64) {
        self.timers
            .entry(key.to_string())
            .or_default()
            .push(duration_ms);
    }

    pub fn record_metrics(
        &self,
        dimensions: HashMap<String, String>,
        throughput: u64,
        latency_p99: u64,
        error_rate: f64,
    ) -> MetricsSnapshot {
        let snapshot = MetricsSnapshot {
            snapshot_id: generate_id("snap"),
            timestamp: now_utc(),
            metrics: MetricsData {
                throughput,
                latency_p99,
                error_rate,
            },
            dimensions,
        };

        self.snapshots
            .insert(snapshot.snapshot_id.clone(), snapshot.clone());

        snapshot
    }

    pub fn get_counter(&self, key: &str) -> u64 {
        *self.counters.get(key).unwrap_or(&0)
    }

    pub fn get_timer_stats(&self, key: &str) -> Option<(u64, u64, f64)> {
        let timers = self.timers.get(key)?;
        if timers.is_empty() {
            return None;
        }

        let mut sorted = timers.clone();
        sorted.sort();

        let min = *sorted.first()?;
        let max = *sorted.last()?;
        let avg = sorted.iter().sum::<u64>() as f64 / sorted.len() as f64;

        Some((min, max, avg))
    }

    pub fn get_snapshots(&self) -> Vec<MetricsSnapshot> {
        self.snapshots.iter().map(|e| e.clone()).collect()
    }
}

impl Default for MetricsRecorder {
    fn default() -> Self {
        Self::new()
    }
}

pub struct RequestHandler {
    resource_pool: Arc<ResourcePool>,
    event_emitter: Arc<dyn EventEmitter>,
    metrics: Arc<MetricsRecorder>,
    configs: DashMap<String, HashMap<String, serde_json::Value>>,
    entities: DashMap<String, CoreEntity>,
    runs: DashMap<String, RunInstance>,
    default_timeout_ms: u64,
    max_retries: u32,
}

impl RequestHandler {
    pub fn new(
        pool_size: usize,
        event_emitter: Arc<dyn EventEmitter>,
        metrics: Arc<MetricsRecorder>,
    ) -> Self {
        Self {
            resource_pool: Arc::new(ResourcePool::new(pool_size)),
            event_emitter,
            metrics,
            configs: DashMap::new(),
            entities: DashMap::new(),
            runs: DashMap::new(),
            default_timeout_ms: 30000,
            max_retries: 3,
        }
    }

    pub fn with_timeout(mut self, timeout_ms: u64) -> Self {
        self.default_timeout_ms = timeout_ms;
        self
    }

    pub fn with_retries(mut self, retries: u32) -> Self {
        self.max_retries = retries;
        self
    }

    pub async fn load_config(&self, namespace: &str) -> AppResult<HashMap<String, serde_json::Value>> {
        if let Some(cfg) = self.configs.get(namespace) {
            return Ok(cfg.clone());
        }

        let defaults = vec![
            ("timeout".to_string(), serde_json::json!(30)),
            ("retries".to_string(), serde_json::json!(3)),
            ("pool_size".to_string(), serde_json::json!(10)),
        ]
        .into_iter()
        .collect();

        self.configs.insert(namespace.to_string(), defaults.clone());
        Ok(defaults)
    }

    pub fn set_config(&self, namespace: &str, config: HashMap<String, serde_json::Value>) {
        self.configs.insert(namespace.to_string(), config);
    }

    pub fn validate_params(&self, params: &serde_json::Value) -> AppResult<()> {
        if !params.is_object() {
            return Err(AppError::ValidationError(
                "参数必须是JSON对象".to_string(),
            ));
        }

        Ok(())
    }

    pub fn process_core(
        &self,
        payload: &serde_json::Value,
        rules: &HashMap<String, serde_json::Value>,
    ) -> AppResult<serde_json::Value> {
        let mut result = payload.clone();

        if let Some(obj) = result.as_object_mut() {
            obj.insert(
                "_processed_at".to_string(),
                serde_json::json!(now_utc().to_rfc3339()),
            );
            obj.insert(
                "_rules_applied".to_string(),
                serde_json::json!(rules.keys().cloned().collect::<Vec<_>>()),
            );
        }

        Ok(result)
    }

    pub fn persist_result(&self, result: &serde_json::Value) -> AppResult<CoreEntity> {
        let entity = CoreEntity {
            id: generate_id("ent"),
            r#type: "resource".to_string(),
            status: EntityStatus::Completed,
            attributes: {
                let mut attrs = HashMap::new();
                attrs.insert("result".to_string(), result.clone());
                attrs
            },
            created_at: now_utc(),
            updated_at: now_utc(),
        };

        self.entities.insert(entity.id.clone(), entity.clone());
        Ok(entity)
    }

    pub fn build_event(&self, result: &CoreEntity) -> Event {
        Event {
            event_id: generate_id("evt"),
            event_type: "task.completed".to_string(),
            aggregate_id: result.id.clone(),
            payload: serde_json::json!(result),
            metadata: HashMap::new(),
            timestamp: now_utc(),
        }
    }

    pub fn success_response(
        &self,
        trace_id: &str,
        data: serde_json::Value,
    ) -> HandlerResponse {
        HandlerResponse {
            code: 200,
            data: Some(data),
            message: None,
            trace_id: trace_id.to_string(),
        }
    }

    pub fn error_response(&self, trace_id: &str, code: u16, message: &str) -> HandlerResponse {
        HandlerResponse {
            code,
            data: None,
            message: Some(message.to_string()),
            trace_id: trace_id.to_string(),
        }
    }

    pub async fn create_entity(
        &self,
        entity_type: &str,
        config: serde_json::Value,
        labels: HashMap<String, String>,
    ) -> AppResult<CoreEntity> {
        let mut attributes = HashMap::new();
        attributes.insert("config".to_string(), config);
        attributes.insert(
            "labels".to_string(),
            serde_json::json!(labels),
        );

        let entity = CoreEntity {
            id: generate_id("rsc"),
            r#type: entity_type.to_string(),
            status: EntityStatus::Provisioning,
            attributes,
            created_at: now_utc(),
            updated_at: now_utc(),
        };

        self.entities.insert(entity.id.clone(), entity.clone());
        Ok(entity)
    }

    pub async fn get_entity_status(&self, id: &str) -> AppResult<(String, f64)> {
        let entity = self
            .entities
            .get(id)
            .ok_or_else(|| AppError::NotFound(format!("资源不存在: {}", id)))?;

        let run = self
            .runs
            .iter()
            .find(|r| r.entity_id == id)
            .map(|r| r.clone());

        let progress = run.as_ref().map(|r| r.progress).unwrap_or(0.0);
        let status = match entity.status {
            EntityStatus::Provisioning => "provisioning".to_string(),
            EntityStatus::Active => "active".to_string(),
            EntityStatus::Inactive => "inactive".to_string(),
            EntityStatus::Completed => "completed".to_string(),
            EntityStatus::Failed => "failed".to_string(),
            EntityStatus::Deprovisioning => "deprovisioning".to_string(),
            EntityStatus::Pending => "pending".to_string(),
            EntityStatus::Cancelled => "cancelled".to_string(),
        };

        Ok((status, progress))
    }

    pub async fn batch_operation(
        &self,
        operations: Vec<crate::types::BatchOperation>,
    ) -> AppResult<Vec<crate::types::BatchResult>> {
        let mut results = Vec::new();

        for op in operations {
            let result = match op.action.as_str() {
                "start" => self.start_entity(&op.id).await,
                "stop" => self.stop_entity(&op.id).await,
                "restart" => self.restart_entity(&op.id).await,
                "delete" => self.delete_entity(&op.id).await,
                _ => Err(AppError::ValidationError(format!(
                    "不支持的操作: {}",
                    op.action
                ))),
            };

            results.push(crate::types::BatchResult {
                id: op.id.clone(),
                success: result.is_ok(),
                message: result.err().map(|e| e.to_string()),
            });
        }

        Ok(results)
    }

    async fn start_entity(&self, id: &str) -> AppResult<()> {
        let mut entity = self
            .entities
            .get_mut(id)
            .ok_or_else(|| AppError::NotFound(format!("资源不存在: {}", id)))?;

        entity.status = EntityStatus::Active;
        entity.updated_at = now_utc();

        Ok(())
    }

    async fn stop_entity(&self, id: &str) -> AppResult<()> {
        let mut entity = self
            .entities
            .get_mut(id)
            .ok_or_else(|| AppError::NotFound(format!("资源不存在: {}", id)))?;

        entity.status = EntityStatus::Inactive;
        entity.updated_at = now_utc();

        Ok(())
    }

    async fn restart_entity(&self, id: &str) -> AppResult<()> {
        self.stop_entity(id).await?;
        self.start_entity(id).await
    }

    async fn delete_entity(&self, id: &str) -> AppResult<()> {
        self.entities
            .remove(id)
            .ok_or_else(|| AppError::NotFound(format!("资源不存在: {}", id)))?;

        Ok(())
    }

    pub async fn execute_handler(&self, request: HandlerRequest) -> HandlerResponse {
        let trace_id = request.trace_id.clone();
        let mut ctx = ProcessingContext::new(&request.trace_id, &request.namespace);

        self.metrics.increment_counter("requests.total", 1);

        let result = async {
            self.validate_params(&request.params)?;

            let config = self.load_config(&request.namespace).await?;

            let pool_size = config
                .get("pool_size")
                .and_then(|v| v.as_u64())
                .unwrap_or(10) as usize;

            let timeout_ms = config
                .get("timeout")
                .and_then(|v| v.as_u64())
                .unwrap_or(30)
                * 1000;

            let rules = config.clone();

            let resource = self.resource_pool.acquire().await?;

            let process_future = async {
                let result = self.process_core(&request.payload, &rules)?;
                let entity = self.persist_result(&result)?;
                self.event_emitter.emit(self.build_event(&entity));
                Ok(serde_json::json!(entity))
            };

            let result = match timeout(Duration::from_millis(timeout_ms), process_future).await {
                Ok(res) => res,
                Err(_) => {
                    return Err(AppError::TimeoutError);
                }
            };

            drop(resource);

            result
        }
        .await;

        let response = match result {
            Ok(data) => self.success_response(&trace_id, data),
            Err(AppError::ValidationError(details)) => {
                self.metrics.increment_counter("requests.validation_errors", 1);
                self.error_response(&trace_id, 422, &details)
            }
            Err(AppError::TimeoutError) => {
                self.metrics.increment_counter("requests.timeouts", 1);
                ctx.rollback();
                self.error_response(&trace_id, 504, "上游服务响应超时")
            }
            Err(e) => {
                self.metrics.increment_counter("requests.errors", 1);
                ctx.rollback();
                self.error_response(&trace_id, 500, "内部处理错误")
            }
        };

        let elapsed = ctx.elapsed();
        self.metrics
            .record_timer("request.latency", elapsed.as_millis() as u64);

        let (_, _, avg_latency) = self
            .metrics
            .get_timer_stats("request.latency")
            .unwrap_or((0, 0, 0.0));

        let error_count = self.metrics.get_counter("requests.errors");
        let total_count = self.metrics.get_counter("requests.total");
        let error_rate = if total_count > 0 {
            error_count as f64 / total_count as f64
        } else {
            0.0
        };

        let throughput = (total_count as f64 / elapsed.as_secs_f64().max(0.001)) as u64;

        self.metrics.record_metrics(
            vec![
                ("service".to_string(), "enterprise-middleware".to_string()),
                ("namespace".to_string(), request.namespace.clone()),
            ]
            .into_iter()
            .collect(),
            throughput,
            avg_latency as u64,
            error_rate,
        );

        response
    }

    pub fn start_run(&self, entity_id: &str) -> RunInstance {
        let run = RunInstance {
            run_id: generate_id("run"),
            entity_id: entity_id.to_string(),
            phase: RunPhase::Initializing,
            progress: 0.0,
            started_at: now_utc(),
            completed_at: None,
            error_detail: None,
        };

        self.runs.insert(run.run_id.clone(), run.clone());
        run
    }

    pub fn update_run_progress(&self, run_id: &str, progress: f64, phase: RunPhase) -> AppResult<()> {
        let mut run = self
            .runs
            .get_mut(run_id)
            .ok_or_else(|| AppError::NotFound(format!("运行实例不存在: {}", run_id)))?;

        run.progress = progress;
        run.phase = phase;

        if phase == RunPhase::Completed || phase == RunPhase::Failed {
            run.completed_at = Some(now_utc());
            if phase == RunPhase::Failed {
                run.error_detail = Some("执行失败".to_string());
            }
        }

        Ok(())
    }

    pub fn get_run(&self, run_id: &str) -> Option<RunInstance> {
        self.runs.get(run_id).map(|r| r.clone())
    }

    pub fn resource_pool(&self) -> &Arc<ResourcePool> {
        &self.resource_pool
    }

    pub fn metrics(&self) -> &Arc<MetricsRecorder> {
        &self.metrics
    }

    pub fn event_emitter(&self) -> &Arc<dyn EventEmitter> {
        &self.event_emitter
    }
}

pub fn create_handler(
    pool_size: usize,
) -> RequestHandler {
    let emitter = Arc::new(InMemoryEventEmitter::new());
    let metrics = Arc::new(MetricsRecorder::new());
    RequestHandler::new(pool_size, emitter, metrics)
}
