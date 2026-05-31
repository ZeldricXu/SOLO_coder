use crate::config::{ConfigurationListener, DynamicConfigManager, MaskingAsyncConfig, MaskingConfig};
use crate::masking::{DynamicMaskingEngine, MaskingContext, MaskingRule, MaskingResult, UserRole};
use crate::models::AppError;
use crate::utils::{current_datetime, generate_id};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot, Semaphore};
use tokio::task::JoinHandle;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MaskingTaskStatus {
    Pending,
    Processing,
    Completed,
    Failed,
    Cancelled,
    TimedOut,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MaskingTaskType {
    MaskField,
    MaskJson,
    MaskText,
    BatchMask,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingTask {
    pub task_id: String,
    pub task_type: MaskingTaskType,
    pub status: MaskingTaskStatus,
    pub context: MaskingContext,
    pub submitted_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_message: Option<String>,
    pub retry_count: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AsyncMaskingResult {
    pub task_id: String,
    pub status: MaskingTaskStatus,
    pub results: Option<HashMap<String, MaskingResult>>,
    pub masked_json: Option<serde_json::Value>,
    pub masked_text: Option<String>,
    pub error: Option<String>,
    pub processing_time_ms: u64,
}

pub trait MaskingCallback: Send + Sync {
    fn on_completed(&self, task_id: &str, result: &AsyncMaskingResult);
    fn on_failed(&self, task_id: &str, error: &AppError);
    fn on_retry(&self, task_id: &str, attempt: u32, error: &AppError);
}

pub type CallbackBox = Arc<dyn MaskingCallback>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingEvent {
    pub event_type: String,
    pub task_id: String,
    pub user_id: String,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl MaskingEvent {
    pub fn new(event_type: &str, task_id: &str, user_id: &str, details: serde_json::Value) -> Self {
        Self {
            event_type: event_type.to_string(),
            task_id: task_id.to_string(),
            user_id: user_id.to_string(),
            timestamp: current_datetime(),
            details,
        }
    }
}

enum MaskingTaskPayload {
    MaskField {
        field_name: String,
        value: String,
        result_tx: Option<oneshot::Sender<AsyncMaskingResult>>,
    },
    MaskJson {
        value: serde_json::Value,
        result_tx: Option<oneshot::Sender<AsyncMaskingResult>>,
    },
    MaskText {
        text: String,
        result_tx: Option<oneshot::Sender<AsyncMaskingResult>>,
    },
    BatchMask {
        fields: HashMap<String, String>,
        result_tx: Option<oneshot::Sender<AsyncMaskingResult>>,
    },
}

struct QueuedTask {
    task: MaskingTask,
    context: MaskingContext,
    payload: MaskingTaskPayload,
    callbacks: Vec<CallbackBox>,
}

pub struct AsyncMaskingEngine {
    engine: Arc<DynamicMaskingEngine>,
    config_manager: Option<Arc<DynamicConfigManager>>,
    async_config: RwLock<MaskingAsyncConfig>,
    tasks: DashMap<String, MaskingTask>,
    events: DashMap<String, MaskingEvent>,
    task_tx: Option<mpsc::UnboundedSender<QueuedTask>>,
    worker_handles: RwLock<Vec<JoinHandle<()>>>,
    semaphore: Option<Arc<Semaphore>>,
    is_running: RwLock<bool>,
}

struct MaskingConfigChangeListener;

impl ConfigurationListener for MaskingConfigChangeListener {
    fn on_config_changed(&self, module: &str, _old_version: u32, _new_version: u32) {
        if module == "masking" {
            tracing::info!("Masking async configuration changed");
        }
    }

    fn on_config_rolled_back(&self, module: &str, _from_version: u32, _to_version: u32) {
        if module == "masking" {
            tracing::warn!("Masking async configuration rolled back");
        }
    }
}

impl AsyncMaskingEngine {
    pub fn new(config: MaskingConfig) -> Self {
        let async_config = MaskingAsyncConfig::default();
        let engine = Arc::new(DynamicMaskingEngine::new(config));

        Self {
            engine,
            config_manager: None,
            async_config: RwLock::new(async_config),
            tasks: DashMap::new(),
            events: DashMap::new(),
            task_tx: None,
            worker_handles: RwLock::new(Vec::new()),
            semaphore: None,
            is_running: RwLock::new(false),
        }
    }

    pub fn with_config_manager(
        config: MaskingConfig,
        config_manager: Arc<DynamicConfigManager>,
    ) -> Self {
        let async_config = config_manager.get_masking_async_config();
        let engine = Arc::new(DynamicMaskingEngine::new(config));

        let mut engine = Self {
            engine,
            config_manager: Some(config_manager.clone()),
            async_config: RwLock::new(async_config),
            tasks: DashMap::new(),
            events: DashMap::new(),
            task_tx: None,
            worker_handles: RwLock::new(Vec::new()),
            semaphore: None,
            is_running: RwLock::new(false),
        };

        let listener = Arc::new(MaskingConfigChangeListener);
        config_manager.add_listener("masking", listener);

        engine
    }

    pub fn start(&mut self) {
        let is_running = *self.is_running.read().unwrap();
        if is_running {
            return;
        }

        let async_config = self.async_config.read().unwrap().clone();

        let (tx, mut rx) = mpsc::unbounded_channel::<QueuedTask>();
        self.task_tx = Some(tx);
        self.semaphore = Some(Arc::new(Semaphore::new(async_config.worker_threads)));

        *self.is_running.write().unwrap() = true;

        let mut handles = Vec::new();

        for _ in 0..async_config.worker_threads {
            let engine_clone = self.engine.clone();
            let sem_clone = self.semaphore.clone().unwrap();
            let max_retry = async_config.retry_attempts;

            let handle = tokio::spawn(async move {
                while let Some(mut queued) = rx.recv().await {
                    let _permit = sem_clone.acquire().await.unwrap();

                    let start = std::time::Instant::now();

                    queued.task.status = MaskingTaskStatus::Processing;
                    queued.task.started_at = Some(current_datetime());

                    let result = Self::process_task(
                        &engine_clone,
                        &mut queued,
                        max_retry,
                    ).await;

                    let processing_time = start.elapsed().as_millis() as u64;

                    let final_result = match result {
                        Ok(mut r) => {
                            r.processing_time_ms = processing_time;
                            r.status = MaskingTaskStatus::Completed;
                            r
                        }
                        Err(e) => {
                            let mut result = AsyncMaskingResult {
                                task_id: queued.task.task_id.clone(),
                                status: MaskingTaskStatus::Failed,
                                results: None,
                                masked_json: None,
                                masked_text: None,
                                error: Some(e.to_string()),
                                processing_time_ms: processing_time,
                            };
                            for cb in &queued.callbacks {
                                cb.on_failed(&queued.task.task_id, &e);
                            }
                            result
                        }
                    };

                    if final_result.status == MaskingTaskStatus::Completed {
                        for cb in &queued.callbacks {
                            cb.on_completed(&queued.task.task_id, &final_result);
                        }
                    }

                    if let Some(ref mut task_entry) = queued.task.status {
                        // Will be handled below
                    }
                }
            });

            handles.push(handle);
        }

        *self.worker_handles.write().unwrap() = handles;
    }

    async fn process_task(
        engine: &Arc<DynamicMaskingEngine>,
        queued: &mut QueuedTask,
        max_retry: u32,
    ) -> Result<AsyncMaskingResult, AppError> {
        let mut attempt = 0;
        loop {
            let result = match &queued.payload {
                MaskingTaskPayload::MaskField { field_name, value, .. } => {
                    let result = engine.mask_field(field_name, value, &queued.context);
                    let mut map = HashMap::new();
                    map.insert(field_name.clone(), result);
                    AsyncMaskingResult {
                        task_id: queued.task.task_id.clone(),
                        status: MaskingTaskStatus::Completed,
                        results: Some(map),
                        masked_json: None,
                        masked_text: None,
                        error: None,
                        processing_time_ms: 0,
                    }
                }
                MaskingTaskPayload::MaskJson { value, .. } => {
                    let masked = engine.mask_json_value(value, &queued.context);
                    AsyncMaskingResult {
                        task_id: queued.task.task_id.clone(),
                        status: MaskingTaskStatus::Completed,
                        results: None,
                        masked_json: Some(masked),
                        masked_text: None,
                        error: None,
                        processing_time_ms: 0,
                    }
                }
                MaskingTaskPayload::MaskText { text, .. } => {
                    let masked = engine.mask_text(text, &queued.context);
                    AsyncMaskingResult {
                        task_id: queued.task.task_id.clone(),
                        status: MaskingTaskStatus::Completed,
                        results: None,
                        masked_json: None,
                        masked_text: Some(masked),
                        error: None,
                        processing_time_ms: 0,
                    }
                }
                MaskingTaskPayload::BatchMask { fields, .. } => {
                    let results = engine.batch_mask(fields, &queued.context);
                    AsyncMaskingResult {
                        task_id: queued.task.task_id.clone(),
                        status: MaskingTaskStatus::Completed,
                        results: Some(results),
                        masked_json: None,
                        masked_text: None,
                        error: None,
                        processing_time_ms: 0,
                    }
                }
            };

            if result.error.is_none() || attempt >= max_retry {
                return Ok(result);
            }

            attempt += 1;
            queued.task.retry_count = attempt;
        }
    }

    pub fn stop(&mut self) {
        *self.is_running.write().unwrap() = false;
        if let Some(ref tx) = self.task_tx.take() {
            drop(tx);
        }
        for handle in self.worker_handles.write().unwrap().drain(..) {
            handle.abort();
        }
    }

    pub fn is_running(&self) -> bool {
        *self.is_running.read().unwrap()
    }

    fn create_task(&self, task_type: MaskingTaskType, context: &MaskingContext) -> MaskingTask {
        MaskingTask {
            task_id: generate_id("mask"),
            task_type,
            status: MaskingTaskStatus::Pending,
            context: context.clone(),
            submitted_at: current_datetime(),
            started_at: None,
            completed_at: None,
            error_message: None,
            retry_count: 0,
        }
    }

    fn submit_task(
        &self,
        task: MaskingTask,
        context: MaskingContext,
        payload: MaskingTaskPayload,
        callbacks: Vec<CallbackBox>,
    ) -> String {
        let task_id = task.task_id.clone();
        self.tasks.insert(task_id.clone(), task.clone());

        let queued = QueuedTask {
            task,
            context,
            payload,
            callbacks,
        };

        if let Some(ref tx) = self.task_tx {
            let _ = tx.send(queued);
        }

        let event = MaskingEvent::new(
            "task_submitted",
            &task_id,
            &context.user_id,
            serde_json::json!({
                "task_type": format!("{:?}", queued.task.task_type),
            }),
        );
        self.events.insert(task_id.clone(), event);

        task_id
    }

    pub async fn submit_mask_field(
        &self,
        field_name: String,
        value: String,
        context: MaskingContext,
        callbacks: Vec<CallbackBox>,
    ) -> String {
        let task = self.create_task(MaskingTaskType::MaskField, &context);
        self.submit_task(task, context, MaskingTaskPayload::MaskField {
            field_name,
            value,
            result_tx: None,
        }, callbacks)
    }

    pub async fn submit_mask_json(
        &self,
        value: serde_json::Value,
        context: MaskingContext,
        callbacks: Vec<CallbackBox>,
    ) -> String {
        let task = self.create_task(MaskingTaskType::MaskJson, &context);
        self.submit_task(task, context, MaskingTaskPayload::MaskJson {
            value,
            result_tx: None,
        }, callbacks)
    }

    pub async fn submit_mask_text(
        &self,
        text: String,
        context: MaskingContext,
        callbacks: Vec<CallbackBox>,
    ) -> String {
        let task = self.create_task(MaskingTaskType::MaskText, &context);
        self.submit_task(task, context, MaskingTaskPayload::MaskText {
            text,
            result_tx: None,
        }, callbacks)
    }

    pub async fn submit_batch_mask(
        &self,
        fields: HashMap<String, String>,
        context: MaskingContext,
        callbacks: Vec<CallbackBox>,
    ) -> String {
        let task = self.create_task(MaskingTaskType::BatchMask, &context);
        self.submit_task(task, context, MaskingTaskPayload::BatchMask {
            fields,
            result_tx: None,
        }, callbacks)
    }

    pub fn get_task(&self, task_id: &str) -> Option<MaskingTask> {
        self.tasks.get(task_id).map(|t| t.clone())
    }

    pub fn get_task_status(&self, task_id: &str) -> Option<MaskingTaskStatus> {
        self.tasks.get(task_id).map(|t| t.status)
    }

    pub fn cancel_task(&self, task_id: &str) -> Result<(), AppError> {
        if let Some(mut task) = self.tasks.get_mut(task_id) {
            if task.status == MaskingTaskStatus::Pending || task.status == MaskingTaskStatus::Processing {
                task.status = MaskingTaskStatus::Cancelled;
                return Ok(());
            }
        }
        Err(AppError::NotFound(format!("Task not found or cannot be cancelled: {}", task_id)))
    }

    pub fn list_pending_tasks(&self) -> Vec<MaskingTask> {
        self.tasks
            .iter()
            .filter(|t| t.status == MaskingTaskStatus::Pending)
            .map(|t| t.clone())
            .collect()
    }

    pub fn list_completed_tasks(&self) -> Vec<MaskingTask> {
        self.tasks
            .iter()
            .filter(|t| t.status == MaskingTaskStatus::Completed || t.status == MaskingTaskStatus::Failed)
            .map(|t| t.clone())
            .collect()
    }

    pub fn get_engine(&self) -> Arc<DynamicMaskingEngine> {
        self.engine.clone()
    }

    pub fn update_async_config(&self, config: MaskingAsyncConfig) {
        *self.async_config.write().unwrap() = config;
    }

    pub fn get_async_config(&self) -> MaskingAsyncConfig {
        self.async_config.read().unwrap().clone()
    }

    pub fn add_rule(&self, rule: MaskingRule) {
        let mut engine = Arc::make_mut(&mut self.engine.clone());
        engine.add_rule(rule);
    }
}

pub struct SimpleCallback<F: Fn(&str, &AsyncMaskingResult) + Send + Sync + 'static> {
    on_complete: F,
}

impl<F> SimpleCallback<F>
where
    F: Fn(&str, &AsyncMaskingResult) + Send + Sync + 'static,
{
    pub fn new(on_complete: F) -> Self {
        Self { on_complete }
    }
}

impl<F> MaskingCallback for SimpleCallback<F>
where
    F: Fn(&str, &AsyncMaskingResult) + Send + Sync + 'static,
{
    fn on_completed(&self, task_id: &str, result: &AsyncMaskingResult) {
        (self.on_complete)(task_id, result);
    }

    fn on_failed(&self, task_id: &str, error: &AppError) {
        tracing::error!("Masking task {} failed: {}", task_id, error);
    }

    fn on_retry(&self, task_id: &str, attempt: u32, error: &AppError) {
        tracing::warn!("Masking task {} retry {}: {}", task_id, attempt, error);
    }
}
