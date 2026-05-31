use std::sync::Arc;
use dashmap::DashMap;
use serde_json::json;
use tokio::sync::Mutex;
use tokio::time::{self, Duration};
use tracing::{info, warn, debug, error};
use uuid::Uuid;

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::ports::mod::{EventPublisherPort, CloudSyncPort};
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use super::model::{
    CachedData, CacheWriteRequest, CacheWriteResponse, CacheStatus,
    NetworkStatus, SyncStatus, SyncPolicy, SyncProgress, SyncResult,
    CacheQuery,
};

pub struct OfflineCacheService {
    cache: Arc<DashMap<String, CachedData>>,
    network_status: Arc<Mutex<NetworkStatus>>,
    sync_progress: Arc<Mutex<SyncProgress>>,
    sync_policy: SyncPolicy,
    max_cache_size_bytes: u64,
    current_size_bytes: Arc<Mutex<u64>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    cloud_sync: Arc<dyn CloudSyncPort>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
    is_syncing: Arc<Mutex<bool>>,
    persistence_path: Option<String>,
}

impl OfflineCacheService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        cloud_sync: Arc<dyn CloudSyncPort>,
        audit_logger: Arc<AuditLogger>,
        sync_policy: Option<SyncPolicy>,
        max_cache_size_mb: Option<u64>,
        persistence_path: Option<String>,
    ) -> Arc<Self> {
        let sync_policy = sync_policy.unwrap_or_default();
        let max_cache_size_bytes = max_cache_size_mb.unwrap_or(100) * 1024 * 1024;

        let service = Arc::new(Self {
            cache: Arc::new(DashMap::new()),
            network_status: Arc::new(Mutex::new(NetworkStatus::Unknown)),
            sync_progress: Arc::new(Mutex::new(SyncProgress::default())),
            sync_policy,
            max_cache_size_bytes,
            current_size_bytes: Arc::new(Mutex::new(0)),
            event_publisher,
            cloud_sync,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "offline_cache"),
            is_syncing: Arc::new(Mutex::new(false)),
            persistence_path,
        });

        service.clone().start_background_tasks();

        service
    }

    pub async fn write_data(&self, ctx: &RequestContext, req: CacheWriteRequest) -> AppResult<CacheWriteResponse> {
        let start = std::time::Instant::now();
        debug!(entity_type = %req.entity_type, entity_id = %req.entity_id, "Writing data to offline cache");

        self.validate_write_request(&req)?;

        let cached_data = CachedData::new(
            req.entity_type.clone(),
            req.entity_id.clone(),
            req.operation.clone(),
            req.payload.clone(),
            req.idempotency_key,
            req.ttl_seconds,
            req.priority,
        );

        self.check_and_evict_if_needed(cached_data.size_bytes).await?;

        let will_sync = matches!(*self.network_status.lock().await, NetworkStatus::Online);

        let cache_id = cached_data.id.clone();
        let created_at = cached_data.created_at;
        let network_status = self.network_status.lock().await.clone();

        self.add_to_cache(cached_data).await?;

        let event = DomainEvent::new(
            "cache.data.written",
            &cache_id,
            json!({
                "cache_id": cache_id,
                "entity_type": req.entity_type,
                "entity_id": req.entity_id,
                "operation": req.operation,
                "size_bytes": req.payload.to_string().len(),
                "will_sync": will_sync,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "cache.write",
            "offline_cache",
            &cache_id,
            true,
            json!({
                "entity_type": req.entity_type,
                "entity_id": req.entity_id,
                "operation": req.operation,
                "will_sync": will_sync,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        if will_sync {
            let service_clone = self.clone_arc();
            tokio::spawn(async move {
                let _ = service_clone.trigger_sync().await;
            });
        }

        Ok(CacheWriteResponse {
            cache_id,
            status: "cached".into(),
            network_status,
            will_sync,
            created_at,
        })
    }

    pub async fn get_cache_status(&self) -> AppResult<CacheStatus> {
        let network_status = self.network_status.lock().await.clone();
        let sync_progress = self.sync_progress.lock().await.clone();
        let current_size = *self.current_size_bytes.lock().await;

        let mut pending_items = 0u64;
        let mut synced_items = 0u64;
        let mut failed_items = 0u64;
        let mut oldest_pending_at: Option<chrono::DateTime<chrono::Utc>> = None;

        for item in self.cache.iter() {
            match item.sync_status {
                SyncStatus::Pending | SyncStatus::Syncing => {
                    pending_items += 1;
                    if oldest_pending_at.map_or(true, |t| item.created_at < t) {
                        oldest_pending_at = Some(item.created_at);
                    }
                }
                SyncStatus::Synced => synced_items += 1,
                SyncStatus::Failed => failed_items += 1,
                SyncStatus::Expired => {}
            }
        }

        let usage_percent = if self.max_cache_size_bytes > 0 {
            (current_size as f64 / self.max_cache_size_bytes as f64) * 100.0
        } else {
            0.0
        };

        Ok(CacheStatus {
            network_status,
            total_items: self.cache.len() as u64,
            pending_items,
            synced_items,
            failed_items,
            total_size_bytes: current_size,
            max_size_bytes: self.max_cache_size_bytes,
            usage_percent,
            sync_progress,
            sync_policy: self.sync_policy.clone(),
            oldest_pending_at,
        })
    }

    pub async fn query_cache(&self, query: CacheQuery, page: u32, page_size: u32) -> AppResult<(Vec<CachedData>, u64)> {
        let mut items: Vec<CachedData> = self.cache.iter()
            .filter(|item| {
                query.sync_status.as_ref().map_or(true, |s| &item.sync_status == s)
                    && query.entity_type.as_ref().map_or(true, |t| &item.entity_type == t)
                    && query.entity_id.as_ref().map_or(true, |id| &item.entity_id == id)
                    && query.operation.as_ref().map_or(true, |op| &item.operation == op)
            })
            .map(|item| item.clone())
            .collect();

        items.sort_by(|a, b| b.priority.cmp(&a.priority).then(a.created_at.cmp(&b.created_at)));

        let total = items.len() as u64;
        let start = ((page.saturating_sub(1)) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();

        Ok((paginated, total))
    }

    pub async fn trigger_sync(&self) -> AppResult<Vec<SyncResult>> {
        let is_online = self.check_network_and_update_status().await?;
        if !is_online {
            return Err(AppError::ServiceUnavailable("网络不可用，无法同步".into()));
        }

        let mut is_syncing = self.is_syncing.lock().await;
        if *is_syncing {
            debug!("Sync already in progress, skipping trigger");
            return Ok(Vec::new());
        }
        *is_syncing = true;
        drop(is_syncing);

        let results = self.perform_sync().await;

        let mut is_syncing = self.is_syncing.lock().await;
        *is_syncing = false;

        results
    }

    pub async fn force_sync(&self, ctx: &RequestContext) -> AppResult<Vec<SyncResult>> {
        info!("Force sync triggered by operator");

        let event = DomainEvent::new(
            "sync.started",
            "manual_sync",
            json!({ "type": "manual", "operator": ctx.auth.as_ref().map(|a| &a.device_id) }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        let results = self.trigger_sync().await?;

        self.audit_logger.log_operation(
            ctx,
            "cache.force_sync",
            "offline_cache",
            "manual",
            true,
            json!({
                "synced_count": results.iter().filter(|r| r.success).count(),
                "failed_count": results.iter().filter(|r| !r.success).count(),
            }),
        );

        Ok(results)
    }

    pub async fn clear_synced(&self, ctx: &RequestContext, older_than_hours: Option<u64>) -> AppResult<u64> {
        let cutoff = older_than_hours.unwrap_or(self.sync_policy.auto_clean_synced_hours);
        let cutoff_time = chrono::Utc::now() - chrono::Duration::hours(cutoff as i64);

        let mut removed_ids = Vec::new();
        let mut freed_bytes = 0u64;

        self.cache.retain(|id, item| {
            if matches!(item.sync_status, SyncStatus::Synced)
                && item.synced_at.map_or(false, |t| t <= cutoff_time)
            {
                removed_ids.push(id.clone());
                freed_bytes += item.size_bytes;
                false
            } else {
                true
            }
        });

        let removed_count = removed_ids.len() as u64;

        if removed_count > 0 {
            *self.current_size_bytes.lock().await -= freed_bytes;
        }

        self.audit_logger.log_operation(
            ctx,
            "cache.clear_synced",
            "offline_cache",
            "cleanup",
            true,
            json!({
                "removed_count": removed_count,
                "freed_bytes": freed_bytes,
                "cutoff_hours": cutoff,
            }),
        );

        Ok(removed_count)
    }

    pub async fn get_network_status(&self) -> NetworkStatus {
        self.network_status.lock().await.clone()
    }

    pub async fn check_network_and_update_status(&self) -> AppResult<bool> {
        let previous = self.network_status.lock().await.clone();
        let is_online = self.cloud_sync.is_online().await;

        let current = if is_online {
            NetworkStatus::Online
        } else {
            NetworkStatus::Offline
        };

        if previous != current {
            *self.network_status.lock().await = current.clone();

            let event_type = match &current {
                NetworkStatus::Online => "network.online",
                NetworkStatus::Offline => "network.offline",
                NetworkStatus::Unknown => "network.unknown",
            };

            let event = DomainEvent::new(
                event_type,
                "network",
                json!({
                    "previous_status": previous.as_str(),
                    "current_status": current.as_str(),
                }),
                Uuid::new_v4().to_string(),
            );
            self.event_publisher.publish(event).await?;

            info!(previous = %previous.as_str(), current = %current.as_str(), "Network status changed");

            if matches!(current, NetworkStatus::Online) {
                let service_clone = self.clone_arc();
                tokio::spawn(async move {
                    let _ = service_clone.trigger_sync().await;
                });
            }
        }

        Ok(is_online)
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    fn start_background_tasks(self: Arc<Self>) {
        let service_clone = self.clone();
        tokio::spawn(async move {
            let mut interval = time::interval(Duration::from_secs(30));
            loop {
                interval.tick().await;
                if let Err(e) = service_clone.check_network_and_update_status().await {
                    warn!(error = %e, "Failed to check network status");
                }
            }
        });

        let service_clone = self.clone();
        tokio::spawn(async move {
            let mut interval = time::interval(Duration::from_secs(service_clone.sync_policy.sync_interval_seconds));
            loop {
                interval.tick().await;
                let is_online = service_clone.network_status.lock().await.clone() == NetworkStatus::Online;
                if is_online {
                    let has_pending = service_clone.cache.iter().any(|item| {
                        matches!(item.sync_status, SyncStatus::Pending | SyncStatus::Failed)
                    });
                    if has_pending {
                        let _ = service_clone.trigger_sync().await;
                    }
                }
            }
        });

        let service_clone = self.clone();
        tokio::spawn(async move {
            let mut interval = time::interval(Duration::from_secs(3600));
            loop {
                interval.tick().await;
                if let Err(e) = service_clone.clean_expired_data().await {
                    warn!(error = %e, "Failed to clean expired data");
                }
            }
        });
    }

    async fn perform_sync(&self) -> AppResult<Vec<SyncResult>> {
        let start = std::time::Instant::now();
        let trace_id = Uuid::new_v4().to_string();

        info!("Starting sync process");

        let pending_items: Vec<CachedData> = self.cache.iter()
            .filter(|item| {
                matches!(item.sync_status, SyncStatus::Pending | SyncStatus::Failed)
                    && item.can_retry(self.sync_policy.max_retries)
                    && !item.is_expired()
            })
            .map(|item| item.clone())
            .collect();

        if pending_items.is_empty() {
            debug!("No pending items to sync");
            return Ok(Vec::new());
        }

        let sync_progress = self.sync_progress.clone();
        {
            let mut progress = sync_progress.lock().await;
            progress.sync_started_at = Some(chrono::Utc::now());
            progress.total_pending = pending_items.len() as u64;
        }

        let event = DomainEvent::new(
            "sync.started",
            "auto_sync",
            json!({
                "type": "auto",
                "pending_count": pending_items.len(),
            }),
            &trace_id,
        );
        self.event_publisher.publish(event).await?;

        let mut results = Vec::new();
        let batch_size = self.sync_policy.batch_size as usize;

        for batch in pending_items.chunks(batch_size) {
            let batch_results = self.sync_batch(batch, &trace_id).await?;
            results.extend(batch_results);
        }

        let successful_count = results.iter().filter(|r| r.success).count();
        let failed_count = results.iter().filter(|r| !r.success).count();

        {
            let mut progress = sync_progress.lock().await;
            progress.total_synced += successful_count as u64;
            progress.total_failed += failed_count as u64;
            progress.last_completed_at = Some(chrono::Utc::now());
            progress.current_syncing = None;
        }

        let complete_event = DomainEvent::new(
            "sync.completed",
            "auto_sync",
            json!({
                "total": results.len(),
                "successful": successful_count,
                "failed": failed_count,
                "duration_ms": start.elapsed().as_millis(),
            }),
            &trace_id,
        );
        self.event_publisher.publish(complete_event).await?;

        info!(
            total = results.len(),
            successful = successful_count,
            failed = failed_count,
            "Sync completed"
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(results)
    }

    async fn sync_batch(&self, batch: &[CachedData], trace_id: &str) -> AppResult<Vec<SyncResult>> {
        let mut results = Vec::new();
        let semaphore = Arc::new(tokio::sync::Semaphore::new(self.sync_policy.max_parallel_syncs as usize));
        let mut handles = Vec::new();

        for item in batch {
            let permit = semaphore.clone().acquire_owned().await
                .map_err(|e| AppError::Internal(format!("Semaphore error: {}", e)))?;

            let service_clone = self.clone_arc();
            let item = item.clone();
            let trace_id = trace_id.to_string();

            let handle = tokio::spawn(async move {
                let _permit = permit;
                service_clone.sync_single(item, &trace_id).await
            });
            handles.push(handle);
        }

        for handle in handles {
            match handle.await {
                Ok(Ok(result)) => results.push(result),
                Ok(Err(e)) => {
                    error!(error = %e, "Sync task failed");
                }
                Err(e) => {
                    error!(error = %e, "Sync task panicked");
                }
            }
        }

        Ok(results)
    }

    async fn sync_single(&self, mut item: CachedData, trace_id: &str) -> AppResult<SyncResult> {
        debug!(cache_id = %item.id, "Syncing cached data");

        {
            let mut progress = self.sync_progress.lock().await;
            progress.current_syncing = Some(item.id.clone());
        }

        if let Some(cache_item) = self.cache.get_mut(&item.id) {
            cache_item.mark_sync_attempt();
            item.sync_attempts = cache_item.sync_attempts;
            item.last_sync_attempt = cache_item.last_sync_attempt;
        }

        let sync_payload = if self.sync_policy.enable_idempotency {
            json!({
                "data": item.payload,
                "idempotency_key": item.idempotency_key,
                "entity_type": item.entity_type,
                "entity_id": item.entity_id,
                "operation": item.operation,
                "timestamp": item.created_at.to_rfc3339(),
            })
        } else {
            item.payload.clone()
        };

        let result = match self.cloud_sync.upload_data(sync_payload).await {
            Ok(_) => {
                if let Some(mut cache_item) = self.cache.get_mut(&item.id) {
                    cache_item.mark_synced();
                }

                SyncResult {
                    cache_id: item.id.clone(),
                    success: true,
                    synced_at: Some(chrono::Utc::now()),
                    error: None,
                }
            }
            Err(e) => {
                let error_msg = e.to_string();

                if let Some(mut cache_item) = self.cache.get_mut(&item.id) {
                    cache_item.mark_failed(error_msg.clone());
                }

                let event = DomainEvent::new(
                    "sync.failed",
                    &item.id,
                    json!({
                        "cache_id": item.id,
                        "error": error_msg,
                        "attempts": item.sync_attempts,
                        "max_retries": self.sync_policy.max_retries,
                    }),
                    trace_id,
                );
                let _ = self.event_publisher.publish(event).await;

                SyncResult {
                    cache_id: item.id.clone(),
                    success: false,
                    synced_at: None,
                    error: Some(error_msg),
                }
            }
        };

        Ok(result)
    }

    async fn add_to_cache(&self, data: CachedData) -> AppResult<()> {
        let size = data.size_bytes;
        self.cache.insert(data.id.clone(), data);
        *self.current_size_bytes.lock().await += size;

        if let Some(path) = &self.persistence_path {
            if let Err(e) = self.persist_to_disk(path).await {
                warn!(error = %e, "Failed to persist cache to disk");
            }
        }

        Ok(())
    }

    async fn check_and_evict_if_needed(&self, incoming_size: u64) -> AppResult<()> {
        let current_size = *self.current_size_bytes.lock().await;

        if current_size + incoming_size <= self.max_cache_size_bytes {
            return Ok(());
        }

        let need_to_free = (current_size + incoming_size).saturating_sub(self.max_cache_size_bytes);
        let mut freed = 0u64;

        let mut items_to_evict: Vec<_> = self.cache.iter()
            .filter(|item| matches!(item.sync_status, SyncStatus::Synced | SyncStatus::Expired))
            .map(|item| (item.id.clone(), item.size_bytes, item.synced_at, item.created_at))
            .collect();

        items_to_evict.sort_by(|a, b| {
            let a_synced = a.2.is_some();
            let b_synced = b.2.is_some();
            if a_synced && !b_synced {
                std::cmp::Ordering::Less
            } else if !a_synced && b_synced {
                std::cmp::Ordering::Greater
            } else {
                a.3.cmp(&b.3)
            }
        });

        for (id, size, _, _) in items_to_evict {
            if freed >= need_to_free {
                break;
            }
            if self.cache.remove(&id).is_some() {
                freed += size;
            }
        }

        if freed < need_to_free {
            return Err(AppError::Internal(format!(
                "缓存已满，无法写入。需要释放 {} 字节，仅释放了 {} 字节",
                need_to_free, freed
            )));
        }

        *self.current_size_bytes.lock().await -= freed;

        info!(freed_bytes = freed, "Evicted old cache entries to make room");

        Ok(())
    }

    async fn clean_expired_data(&self) -> AppResult<u64> {
        let mut removed = 0u64;
        let mut freed_bytes = 0u64;

        self.cache.retain(|_, item| {
            if item.is_expired() {
                item.sync_status = SyncStatus::Expired;
                freed_bytes += item.size_bytes;
                removed += 1;
                false
            } else {
                true
            }
        });

        if removed > 0 {
            *self.current_size_bytes.lock().await -= freed_bytes;
            debug!(count = removed, "Cleaned expired cache entries");
        }

        Ok(removed)
    }

    async fn persist_to_disk(&self, path: &str) -> AppResult<()> {
        let items: Vec<CachedData> = self.cache.iter().map(|item| item.clone()).collect();
        let data = serde_json::to_vec(&items)?;
        tokio::fs::write(path, data).await?;
        Ok(())
    }

    pub async fn load_from_disk(&self, path: &str) -> AppResult<u64> {
        match tokio::fs::read(path).await {
            Ok(data) => {
                let items: Vec<CachedData> = serde_json::from_slice(&data)?;
                let mut loaded = 0u64;
                let mut total_size = 0u64;

                for item in items {
                    if !item.is_expired() {
                        total_size += item.size_bytes;
                        self.cache.insert(item.id.clone(), item);
                        loaded += 1;
                    }
                }

                *self.current_size_bytes.lock().await += total_size;
                info!(count = loaded, "Loaded cache from disk");
                Ok(loaded)
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                debug!("No cache file found, starting with empty cache");
                Ok(0)
            }
            Err(e) => Err(e.into()),
        }
    }

    fn validate_write_request(&self, req: &CacheWriteRequest) -> AppResult<()> {
        if req.entity_type.is_empty() {
            return Err(AppError::Validation("实体类型不能为空".into()));
        }
        if req.entity_id.is_empty() {
            return Err(AppError::Validation("实体ID不能为空".into()));
        }
        if req.operation.is_empty() {
            return Err(AppError::Validation("操作类型不能为空".into()));
        }
        Ok(())
    }

    fn clone_arc(&self) -> Arc<Self> {
        Arc::new(Self {
            cache: self.cache.clone(),
            network_status: self.network_status.clone(),
            sync_progress: self.sync_progress.clone(),
            sync_policy: self.sync_policy.clone(),
            max_cache_size_bytes: self.max_cache_size_bytes,
            current_size_bytes: self.current_size_bytes.clone(),
            event_publisher: self.event_publisher.clone(),
            cloud_sync: self.cloud_sync.clone(),
            audit_logger: self.audit_logger.clone(),
            metrics: self.metrics.clone(),
            is_syncing: self.is_syncing.clone(),
            persistence_path: self.persistence_path.clone(),
        })
    }
}
