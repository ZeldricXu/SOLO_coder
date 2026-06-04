use std::sync::Arc;
use tokio::sync::RwLock;
use std::collections::HashMap;

use common::error::{CdnResult};
use common::models::{PreheatTask, PreheatStatus};
use common::db::Database;
use common::utils::generate_id;

pub struct PreheatManager {
    db: Database,
    active_tasks: Arc<RwLock<HashMap<uuid::Uuid, PreheatTask>>>,
}

impl PreheatManager {
    pub fn new(db: Database) -> Self {
        PreheatManager {
            db,
            active_tasks: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn create_task(
        &self,
        content_url: String,
        target_regions: Vec<String>,
        target_nodes: Vec<uuid::Uuid>,
    ) -> CdnResult<PreheatTask> {
        let task = PreheatTask {
            id: generate_id(),
            content_url,
            target_regions,
            target_nodes,
            status: PreheatStatus::Pending,
            progress: 0.0,
            created_at: chrono::Utc::now(),
            completed_at: None,
        };

        let mut tasks = self.active_tasks.write().await;
        tasks.insert(task.id, task.clone());

        Ok(task)
    }

    pub async fn get_task(&self, task_id: uuid::Uuid) -> Option<PreheatTask> {
        let tasks = self.active_tasks.read().await;
        tasks.get(&task_id).cloned()
    }

    pub async fn update_progress(&self, task_id: uuid::Uuid, progress: f64) -> CdnResult<()> {
        let mut tasks = self.active_tasks.write().await;
        if let Some(task) = tasks.get_mut(&task_id) {
            task.progress = progress;
        }
        Ok(())
    }

    pub async fn complete_task(&self, task_id: uuid::Uuid, success: bool) -> CdnResult<()> {
        let mut tasks = self.active_tasks.write().await;
        if let Some(task) = tasks.get_mut(&task_id) {
            task.status = if success {
                PreheatStatus::Completed
            } else {
                PreheatStatus::Failed
            };
            task.completed_at = Some(chrono::Utc::now());
        }
        Ok(())
    }
}

impl Clone for PreheatManager {
    fn clone(&self) -> Self {
        PreheatManager {
            db: self.db.clone(),
            active_tasks: self.active_tasks.clone(),
        }
    }
}
