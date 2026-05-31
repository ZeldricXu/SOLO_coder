use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use crate::models::StreamSQLError;
use super::engine::{QualityEngine, ValidationReport};
use super::rules::RuleSet;

pub struct QualityScheduler {
    engine: Arc<QualityEngine>,
    scheduled_tasks: Arc<RwLock<std::collections::HashMap<String, ScheduledTask>>,
    running: Arc<Mutex<bool>>,
    handles: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>,
}

#[derive(Debug, Clone)]
pub struct ScheduledTask {
    pub id: String,
    pub rule_set_id: String,
    pub cron_expression: String,
    pub interval_ms: u64,
    pub next_run: chrono::DateTime<chrono::Utc>,
    pub enabled: bool,
    pub last_run: Option<chrono::DateTime<chrono::Utc>>,
}

impl QualityScheduler {
    pub fn new(engine: Arc<QualityEngine>) -> Self {
        Self {
            engine,
            scheduled_tasks: Arc::new(RwLock::new(std::collections::HashMap::new())),
            running: Arc::new(Mutex::new(false)),
            handles: Arc::new(Mutex::new(Vec::new())),
        }
    }

    pub async fn schedule_rule_set(
        &self,
        rule_set: &RuleSet,
        interval_ms: u64,
    ) -> ScheduledTask {
        let task = ScheduledTask {
            id: crate::models::IdGenerator::generate("task"),
            rule_set_id: rule_set.id.clone(),
            cron_expression: rule_set
                .schedule
                .as_ref()
                .map(|s| s.cron_expression.clone())
                .unwrap_or_else(|| "0 */15 * * * *".to_string()),
            interval_ms,
            next_run: chrono::Utc::now()
                + chrono::Duration::milliseconds(interval_ms as i64),
            enabled: rule_set.enabled,
            last_run: None,
        };

        self.scheduled_tasks
            .write()
            .await
            .insert(task.id.clone(), task.clone());

        task
    }

    pub async fn start(&self) {
        *self.running.lock().await = true;

        let tasks = self.scheduled_tasks.clone();
        let engine = self.engine.clone();
        let running = self.running.clone();

        let handle = tokio::spawn(async move {
            while *running.lock().await {
                let now = chrono::Utc::now();
                let mut to_run: Vec<ScheduledTask> = {
                    let tasks = tasks.read().await;
                    tasks
                        .values()
                        .filter(|t| t.enabled && t.next_run <= now)
                        .cloned()
                        .collect()
                };

                for task in to_run {
                    let engine_clone = engine.clone();
                    let task_id = task.id.clone();
                    let rule_set_id = task.rule_set_id.clone();
                    
                    tokio::spawn(async move {
                        let data = vec![serde_json::json!({})];
                        if let Ok(report) = engine_clone.run_rule_set(&rule_set_id, &data).await {
                            tracing::info!(
                                "Scheduled quality check completed: task={}, report={}, failed={}",
                                task_id,
                                report.report_id,
                                report.failed_rules
                            );
                        }
                    });

                    let mut tasks_write = tasks.write().await;
                    if let Some(t) = tasks_write.get_mut(&task_id) {
                        t.next_run = chrono::Utc::now()
                            + chrono::Duration::milliseconds(task.interval_ms as i64);
                        t.last_run = Some(now);
                    }
                }

                tokio::time::sleep(tokio::time::Duration::from_millis(1000)).await;
            }
        });

        self.handles.lock().await.push(handle);
    }

    pub async fn stop(&self) {
        *self.running.lock().await = false;
        
        let mut handles = self.handles.lock().await;
        for handle in handles.drain(..) {
            handle.abort();
        }
    }

    pub async fn list_tasks(&self) -> Vec<ScheduledTask> {
        self.scheduled_tasks
            .read()
            .await
            .values()
            .cloned()
            .collect()
    }

    pub async fn get_task(&self, task_id: &str) -> Option<ScheduledTask> {
        self.scheduled_tasks
            .read()
            .await
            .get(task_id)
            .cloned()
    }

    pub async fn enable_task(&self, task_id: &str) -> Result<(), StreamSQLError> {
        let mut tasks = self.scheduled_tasks.write().await;
        if let Some(task) = tasks.get_mut(task_id) {
            task.enabled = true;
            Ok(())
        } else {
            Err(StreamSQLError::Quality(format!("Task {} not found", task_id)))
        }
    }

    pub async fn disable_task(&self, task_id: &str) -> Result<(), StreamSQLError> {
        let mut tasks = self.scheduled_tasks.write().await;
        if let Some(task) = tasks.get_mut(task_id) {
            task.enabled = false;
            Ok(())
        } else {
            Err(StreamSQLError::Quality(format!("Task {} not found", task_id)))
        }
    }

    pub async fn remove_task(&self, task_id: &str) {
        self.scheduled_tasks
            .write()
            .await
            .remove(task_id);
    }

    pub async fn trigger_now(&self, task_id: &str) -> Result<ValidationReport, StreamSQLError> {
        let task = self
            .get_task(task_id)
            .await
            .ok_or_else(|| StreamSQLError::Quality(format!("Task {} not found", task_id)))?;

        let data = vec![serde_json::json!({})];
        self.engine
            .run_rule_set(&task.rule_set_id, &data)
            .await
    }
}
