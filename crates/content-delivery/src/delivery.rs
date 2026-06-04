use std::sync::Arc;
use tokio::sync::{RwLock, mpsc, broadcast, Mutex};
use std::collections::HashMap;
use std::time::Duration;
use uuid::Uuid;

use common::error::{CdnResult, CdnError};
use common::models::{DeliveryMode, PreheatTask, PreheatStatus};
use common::db::Database;
use common::utils::generate_id;

use cache_engine::CacheEngine;

#[derive(Debug, Clone)]
pub enum PushEventType {
    Started { total_nodes: usize },
    Progress { completed: usize, failed: usize, node_id: Uuid, success: bool },
    Completed { successful: usize, failed: usize },
}

#[derive(Debug, Clone)]
pub struct PushJob {
    task_id: Uuid,
    node_id: Uuid,
    cache_name: String,
    content_url: String,
    content: Vec<u8>,
    content_type: String,
    retry_count: u32,
}

#[derive(Clone)]
pub struct PushWorkerPool {
    sender: mpsc::Sender<PushJob>,
    progress_sender: broadcast::Sender<PushEventType>,
}

impl PushWorkerPool {
    pub fn new(num_workers: usize, cache_engine: CacheEngine) -> Self {
        let (job_sender, job_receiver) = mpsc::channel::<PushJob>(1000);
        let (progress_sender, _) = broadcast::channel::<PushEventType>(1000);

        let shared_receiver = Arc::new(Mutex::new(job_receiver));
        let progress_sender_clone = progress_sender.clone();

        for _ in 0..num_workers {
            let rx = shared_receiver.clone();
            let ce = cache_engine.clone();
            let ps = progress_sender_clone.clone();

            tokio::spawn(async move {
                loop {
                    let job = {
                        let mut guard = rx.lock().await;
                        guard.recv().await
                    };

                    match job {
                        Some(job) => {
                            Self::execute_job(job, ce.clone(), ps.clone()).await;
                        }
                        None => {
                            break;
                        }
                    }
                }
            });
        }

        PushWorkerPool {
            sender: job_sender,
            progress_sender,
        }
    }

    async fn execute_job(
        job: PushJob,
        cache_engine: CacheEngine,
        progress_sender: broadcast::Sender<PushEventType>,
    ) {
        let max_retries = 3;
        let mut success = false;

        for attempt in 0..=max_retries {
            if attempt > 0 {
                let backoff = Duration::from_secs(2u64.pow(attempt - 1));
                tokio::time::sleep(backoff).await;
            }

            let query_params = HashMap::new();
            match cache_engine.put(
                &job.cache_name,
                &job.content_url,
                "",
                &query_params,
                None,
                None,
                job.content.clone(),
                job.content_type.clone(),
                None,
            ).await {
                Ok(_) => {
                    success = true;
                    break;
                }
                Err(e) => {
                    tracing::warn!(
                        "Push to node {} (cache: {}) attempt {} failed: {}",
                        job.node_id,
                        job.cache_name,
                        attempt + 1,
                        e
                    );
                }
            }
        }

        let _ = progress_sender.send(PushEventType::Progress {
            completed: 1,
            failed: if success { 0 } else { 1 },
            node_id: job.node_id,
            success,
        });
    }

    pub async fn submit(&self, job: PushJob) -> CdnResult<()> {
        self.sender.send(job).await
            .map_err(|e| CdnError::CacheError(format!("Failed to submit push job: {}", e)))?;
        Ok(())
    }

    pub fn progress_sender(&self) -> &broadcast::Sender<PushEventType> {
        &self.progress_sender
    }
}

pub struct ContentDeliveryService {
    db: Database,
    cache_engine: CacheEngine,
    origin_fetcher: OriginFetcher,
    delivery_mode: DeliveryMode,
    push_targets: Arc<RwLock<Vec<String>>>,
    push_worker_pool: PushWorkerPool,
    progress_sender: broadcast::Sender<PushEventType>,
}

impl ContentDeliveryService {
    pub fn new(db: Database, cache_engine: CacheEngine, delivery_mode: DeliveryMode) -> Self {
        let push_worker_pool = PushWorkerPool::new(4, cache_engine.clone());
        let progress_sender = push_worker_pool.progress_sender().clone();

        ContentDeliveryService {
            db,
            cache_engine,
            origin_fetcher: OriginFetcher::new(),
            delivery_mode,
            push_targets: Arc::new(RwLock::new(Vec::new())),
            push_worker_pool,
            progress_sender,
        }
    }

    pub fn get_progress_receiver(&self) -> broadcast::Receiver<PushEventType> {
        self.progress_sender.subscribe()
    }

    pub async fn get_content(
        &self,
        cache_name: &str,
        domain: &str,
        path: &str,
        query_params: &HashMap<String, String>,
        user_agent: Option<&str>,
        referer: Option<&str>,
    ) -> CdnResult<(Vec<u8>, String, bool)> {
        if let Some(entry) = self.cache_engine.get(
            cache_name,
            domain,
            path,
            query_params,
            user_agent,
            referer,
        ).await? {
            return Ok((entry.content, entry.content_type, true));
        }

        let (content, content_type) = self.origin_fetcher.fetch(domain, path, query_params).await?;
        
        self.cache_engine.put(
            cache_name,
            domain,
            path,
            query_params,
            user_agent,
            referer,
            content.clone(),
            content_type.clone(),
            None,
        ).await?;

        Ok((content, content_type, false))
    }

    pub async fn push_content(
        &self,
        domain: &str,
        path: &str,
        content: Vec<u8>,
        content_type: String,
        target_regions: &[String],
    ) -> CdnResult<PreheatTask> {
        let task_id = generate_id();
        let now = chrono::Utc::now();

        let task = PreheatTask {
            id: task_id,
            content_url: format!("{}{}", domain, path),
            target_regions: target_regions.to_vec(),
            target_nodes: Vec::new(),
            status: PreheatStatus::InProgress,
            progress: 0.0,
            created_at: now,
            completed_at: None,
        };

        let this = self.clone();
        let task_clone = task.clone();
        tokio::spawn(async move {
            if let Err(e) = this.execute_push_task(&task_clone, content, content_type).await {
                tracing::error!("Push task failed: {}", e);
            }
        });

        Ok(task)
    }

    async fn execute_push_task(
        &self,
        task: &PreheatTask,
        content: Vec<u8>,
        content_type: String,
    ) -> CdnResult<()> {
        let caches = self.cache_engine.list_caches().await;
        let total_nodes = caches.len();

        let _ = self.progress_sender.send(PushEventType::Started { total_nodes });

        let mut target_node_ids = std::collections::HashSet::new();
        let mut jobs = Vec::with_capacity(total_nodes);

        for cache_name in caches {
            let node_id = generate_id();
            target_node_ids.insert(node_id);
            let job = PushJob {
                task_id: task.id,
                node_id,
                cache_name,
                content_url: task.content_url.clone(),
                content: content.clone(),
                content_type: content_type.clone(),
                retry_count: 0,
            };
            jobs.push(job);
        }

        for job in jobs {
            self.push_worker_pool.submit(job).await?;
        }

        let mut completed = 0usize;
        let mut failed = 0usize;
        let mut receiver = self.progress_sender.subscribe();
        let mut processed_nodes = std::collections::HashSet::new();

        while completed + failed < total_nodes {
            match receiver.recv().await {
                Ok(PushEventType::Progress { completed: c, failed: f, node_id, success: _ }) => {
                    if target_node_ids.contains(&node_id) && !processed_nodes.contains(&node_id) {
                        processed_nodes.insert(node_id);
                        completed += c;
                        failed += f;
                    }
                }
                Err(e) => {
                    tracing::warn!("Progress receiver error: {}", e);
                    break;
                }
                _ => {}
            }
        }

        let _ = self.progress_sender.send(PushEventType::Completed {
            successful: completed,
            failed,
        });

        Ok(())
    }

    pub async fn prefetch_to_regions(
        &self,
        url: &str,
        regions: &[String],
    ) -> CdnResult<PreheatTask> {
        let task_id = generate_id();
        let now = chrono::Utc::now();

        let task = PreheatTask {
            id: task_id,
            content_url: url.to_string(),
            target_regions: regions.to_vec(),
            target_nodes: Vec::new(),
            status: PreheatStatus::InProgress,
            progress: 0.0,
            created_at: now,
            completed_at: None,
        };

        let this = self.clone();
        let task_clone = task.clone();
        tokio::spawn(async move {
            if let Err(e) = this.execute_prefetch_task(&task_clone).await {
                tracing::error!("Prefetch task failed: {}", e);
            }
        });

        Ok(task)
    }

    async fn execute_prefetch_task(&self, task: &PreheatTask) -> CdnResult<()> {
        let (content, content_type) = self.origin_fetcher.fetch_url(&task.content_url).await?;
        
        let caches = self.cache_engine.list_caches().await;
        for cache_name in caches {
            let query_params = HashMap::new();
            let _ = self.cache_engine.put(
                &cache_name,
                &task.content_url,
                "",
                &query_params,
                None,
                None,
                content.clone(),
                content_type.clone(),
                None,
            ).await;
        }

        Ok(())
    }

    pub fn delivery_mode(&self) -> &DeliveryMode {
        &self.delivery_mode
    }
}

impl Clone for ContentDeliveryService {
    fn clone(&self) -> Self {
        ContentDeliveryService {
            db: self.db.clone(),
            cache_engine: self.cache_engine.clone(),
            origin_fetcher: self.origin_fetcher.clone(),
            delivery_mode: self.delivery_mode.clone(),
            push_targets: self.push_targets.clone(),
            push_worker_pool: self.push_worker_pool.clone(),
            progress_sender: self.progress_sender.clone(),
        }
    }
}

pub struct OriginFetcher {
    client: reqwest::Client,
}

impl OriginFetcher {
    pub fn new() -> Self {
        OriginFetcher {
            client: reqwest::Client::new(),
        }
    }

    pub async fn fetch(
        &self,
        domain: &str,
        path: &str,
        query_params: &HashMap<String, String>,
    ) -> CdnResult<(Vec<u8>, String)> {
        let url = if query_params.is_empty() {
            format!("http://{}{}", domain, path)
        } else {
            let query: Vec<String> = query_params
                .iter()
                .map(|(k, v)| format!("{}={}", k, v))
                .collect();
            format!("http://{}{}?{}", domain, path, query.join("&"))
        };

        self.fetch_url(&url).await
    }

    pub async fn fetch_url(&self, url: &str) -> CdnResult<(Vec<u8>, String)> {
        let response = self.client.get(url).send().await?;

        if !response.status().is_success() {
            return Err(CdnError::CacheError(format!(
                "Origin fetch failed with status: {}",
                response.status()
            )));
        }

        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_string();

        let content = response.bytes().await?.to_vec();

        Ok((content, content_type))
    }
}

impl Clone for OriginFetcher {
    fn clone(&self) -> Self {
        OriginFetcher {
            client: self.client.clone(),
        }
    }
}

impl Default for OriginFetcher {
    fn default() -> Self {
        Self::new()
    }
}
