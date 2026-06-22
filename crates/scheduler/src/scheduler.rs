use common::error::AppError;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::broadcast;
use tokio::task::JoinHandle;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use traffic_router::RuntimeClient;
use model_registry::ModelRegistryService;

use crate::balancer::GpuLoadBalancer;
use crate::heartbeat::HeartbeatManager;
use crate::model_manager::ModelLifecycleManager;
use crate::types::*;
use crate::warmup::ModelWarmer;

type CycleId = u64;

pub struct ResourceScheduler {
    pub config: SchedulerConfig,
    pub balancer: Arc<GpuLoadBalancer>,
    pub model_manager: Arc<ModelLifecycleManager>,
    pub warmer: Arc<ModelWarmer>,
    pub heartbeat: Arc<HeartbeatManager>,
    pub runtime_client: Arc<RuntimeClient>,
    pub model_registry: Arc<ModelRegistryService>,
    pub event_sender: broadcast::Sender<SchedulerEvent>,
    event_receiver: parking_lot::RwLock<Option<broadcast::Receiver<SchedulerEvent>>>,
    scheduled_tasks: parking_lot::RwLock<Vec<JoinHandle<()>>>,
    cycle_counter: parking_lot::RwLock<CycleId>,
}

impl ResourceScheduler {
    pub fn new(
        config: SchedulerConfig,
        runtime_client: Arc<RuntimeClient>,
        model_registry: Arc<ModelRegistryService>,
    ) -> Self {
        let (tx, rx) = broadcast::channel::<SchedulerEvent>(1024);

        let balancer = Arc::new(GpuLoadBalancer::new());
        let model_manager = Arc::new(ModelLifecycleManager::new(
            runtime_client.clone(),
            model_registry.clone(),
            balancer.clone(),
        ));
        let warmer = Arc::new(ModelWarmer::new(
            runtime_client.clone(),
            model_registry.clone(),
            model_manager.clone(),
        ));
        let heartbeat = Arc::new(HeartbeatManager::new(
            runtime_client.clone(),
            balancer.clone(),
            model_manager.clone(),
            tx.clone(),
            config.clone(),
        ));

        Self {
            config,
            balancer,
            model_manager,
            warmer,
            heartbeat,
            runtime_client,
            model_registry,
            event_sender: tx,
            event_receiver: parking_lot::RwLock::new(Some(rx)),
            scheduled_tasks: parking_lot::RwLock::new(Vec::new()),
            cycle_counter: parking_lot::RwLock::new(0),
        }
    }

    pub fn add_gpu(&self, gpu_id: usize, total_mb: u64, node_address: Option<String>) {
        self.balancer.add_gpu(gpu_id, total_mb, node_address.clone());
        if let Some(ref addr) = node_address {
            self.heartbeat.register_node(addr.clone(), vec![gpu_id]);
        }
        let _ = self.event_sender.send(SchedulerEvent::GpuAdded {
            gpu_id,
            total_mb,
            node_address: node_address.unwrap_or_default(),
        });
    }

    pub fn remove_gpu(&self, gpu_id: usize) {
        self.balancer.remove_gpu(gpu_id);
        let _ = self.event_sender.send(SchedulerEvent::GpuRemoved { gpu_id });
    }

    pub fn start(&self) {
        info!("Starting resource scheduler...");

        let mut event_receiver = self
            .event_receiver
            .write()
            .take()
            .expect("Event receiver already taken");

        let scheduler_self = Arc::new(self.clone_without_receiver());

        let scheduler_self_clone = scheduler_self.clone();
        let scheduling_loop: JoinHandle<()> = tokio::spawn(async move {
            scheduler_self_clone.scheduling_loop().await;
        });

        let scheduler_self_clone = scheduler_self.clone();
        let event_loop: JoinHandle<()> = tokio::spawn(async move {
            while let Ok(event) = event_receiver.recv().await {
                scheduler_self_clone.handle_event(event);
            }
        });

        let heartbeat_self = scheduler_self.heartbeat.clone();
        let heartbeat_loop: JoinHandle<()> = tokio::spawn(async move {
            heartbeat_self.start().await;
        });

        let mut tasks = self.scheduled_tasks.write();
        tasks.push(scheduling_loop);
        tasks.push(event_loop);
        tasks.push(heartbeat_loop);

        info!("Resource scheduler started successfully");
    }

    fn clone_without_receiver(&self) -> Self {
        Self {
            config: self.config.clone(),
            balancer: self.balancer.clone(),
            model_manager: self.model_manager.clone(),
            warmer: self.warmer.clone(),
            heartbeat: self.heartbeat.clone(),
            runtime_client: self.runtime_client.clone(),
            model_registry: self.model_registry.clone(),
            event_sender: self.event_sender.clone(),
            event_receiver: parking_lot::RwLock::new(None),
            scheduled_tasks: parking_lot::RwLock::new(Vec::new()),
            cycle_counter: parking_lot::RwLock::new(*self.cycle_counter.read()),
        }
    }

    async fn scheduling_loop(&self) {
        let interval = std::time::Duration::from_secs(self.config.scheduling_interval_secs);
        info!(
            "Starting scheduling loop with interval {:?}",
            interval
        );

        loop {
            tokio::time::sleep(interval).await;

            if let Err(e) = self.run_scheduling_cycle().await {
                error!("Scheduling cycle failed: {}", e);
            }
        }
    }

    pub async fn run_scheduling_cycle(&self) -> Result<SchedulingDecision, AppError> {
        debug!("Running scheduling cycle...");

        let mut decision = SchedulingDecision::new();

        self.sync_gpu_model_counts();

        decision.unloaded_models.extend(self.evict_cold_models().await?);

        if self.config.auto_scale_enabled {
            decision
                .loaded_models
                .extend(self.scale_hot_models().await?);
        }

        let oom_decisions = self.prevent_oom().await?;
        decision.unloaded_models.extend(oom_decisions);

        decision
            .warmed_models
            .extend(self.warmup_cold_starts().await?);

        if !decision.is_empty() {
            info!(
                "Scheduling cycle complete: load={}, unload={}, warmup={}",
                decision.loaded_models.len(),
                decision.unloaded_models.len(),
                decision.warmed_models.len()
            );
        }

        self.log_scheduling_stats();

        Ok(decision)
    }

    fn sync_gpu_model_counts(&self) {
        for gpu in self.balancer.list_gpus() {
            let count = self.model_manager.count_models_on_gpu(gpu.gpu_id);
            self.balancer.update_model_count(gpu.gpu_id, count);
        }
    }

    async fn evict_cold_models(&self) -> Result<Vec<(Uuid, usize)>, AppError> {
        let mut unloaded = Vec::new();
        let cold_models = self.model_manager.find_cold_models(
            self.config.model_ttl_seconds,
            self.config.cold_threshold_rps,
        );

        for vid in cold_models {
            let gpus = self.model_manager.get_loaded_gpus(vid);
            for gid in gpus {
                let count_on_gpu = self.model_manager.count_models_on_gpu(gid);
                if count_on_gpu <= self.config.min_loaded_models_per_gpu {
                    debug!(
                        "Skipping eviction of {} from GPU {}: below min_models threshold",
                        vid, gid
                    );
                    continue;
                }

                match self.model_manager.unload_model(vid, gid, false).await {
                    Ok(_) => {
                        unloaded.push((vid, gid));
                        let _ = self.event_sender.send(SchedulerEvent::ModelUnloaded {
                            version_id: vid,
                            gpu_id: gid,
                        });
                    }
                    Err(e) => {
                        warn!("Failed to evict cold model {} from GPU {}: {}", vid, gid, e);
                    }
                }
            }
        }

        Ok(unloaded)
    }

    async fn scale_hot_models(&self) -> Result<Vec<(Uuid, usize)>, AppError> {
        let mut loaded = Vec::new();
        let hot_models = self.model_manager.find_hot_models(self.config.hot_threshold_rps);

        for (vid, rps) in hot_models {
            let current_gpus = self.model_manager.get_loaded_gpus(vid);
            let replica_count = current_gpus.len();

            let desired_replicas = if rps >= self.config.hot_threshold_rps * 5.0 {
                self.config.max_replicas_per_model.min(4)
            } else if rps >= self.config.hot_threshold_rps * 2.0 {
                self.config.max_replicas_per_model.min(3)
            } else {
                self.config.max_replicas_per_model.min(2)
            } as usize;

            if replica_count < desired_replicas {
                let needed = desired_replicas - replica_count;
                info!(
                    "Scaling hot model {}: {} replicas -> {} (rps={:.2})",
                    vid, replica_count, desired_replicas, rps
                );

                if let Some(info) = self.model_manager.get_model_info(vid) {
                    for _ in 0..needed {
                        match self.balancer.select_gpu(
                            info.size_mb,
                            GpuSelectionStrategy::LeastLoaded,
                            Some(vid),
                        ) {
                            Ok(new_gpu) => {
                                if !current_gpus.contains(&new_gpu) {
                                    match self.model_manager.load_model(vid, new_gpu).await {
                                        Ok(_) => {
                                            loaded.push((vid, new_gpu));
                                            let _ = self.event_sender.send(
                                                SchedulerEvent::ModelLoaded {
                                                    version_id: vid,
                                                    gpu_id: new_gpu,
                                                },
                                            );

                                            let warmer = self.warmer.clone();
                                            tokio::spawn(async move {
                                                let _ = warmer.warmup_model(vid, new_gpu, None, None).await;
                                            });
                                        }
                                        Err(e) => {
                                            warn!(
                                                "Failed to replicate model {} to GPU {}: {}",
                                                vid, new_gpu, e
                                            );
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                warn!("No GPU available for hot model {} replication: {}", vid, e);
                                break;
                            }
                        }
                    }
                }
            }
        }

        Ok(loaded)
    }

    async fn prevent_oom(&self) -> Result<Vec<(Uuid, usize)>, AppError> {
        let mut unloaded = Vec::new();
        let oom_gpus = self.balancer.get_oom_risk_gpus(self.config.safety_margin_mb);

        for gid in oom_gpus {
            warn!("GPU {} is at OOM risk, evicting lowest heat models", gid);

            let gpu = self.balancer.get_gpu(gid);
            if let Some(g) = gpu.as_ref() {
                let needed = self.config.safety_margin_mb.saturating_sub(g.free_mb) + 1024;

                match self
                    .model_manager
                    .evict_lowest_heat_model(gid, needed)
                    .await
                {
                    Ok(Some(vid)) => {
                        unloaded.push((vid, gid));
                        let _ = self.event_sender.send(SchedulerEvent::ModelUnloaded {
                            version_id: vid,
                            gpu_id: gid,
                        });
                    }
                    Ok(None) => {
                        warn!("Could not evict any models from GPU {} to prevent OOM", gid);
                    }
                    Err(e) => {
                        error!("OOM prevention failed for GPU {}: {}", gid, e);
                    }
                }
            }
        }

        Ok(unloaded)
    }

    async fn warmup_cold_starts(&self) -> Result<Vec<(Uuid, usize)>, AppError> {
        let mut warmed = Vec::new();
        let models = self.model_manager.list_loaded_models();

        for info in models {
            if !info.is_warmed_up && info.age_seconds() < 300 {
                let gpus = self.model_manager.get_loaded_gpus(info.version_id);
                for &gid in gpus.first() {
                    match self
                        .warmer
                        .warmup_model(info.version_id, gid, Some(self.config.warmup_batch_size), Some(self.config.warmup_iterations))
                        .await
                    {
                        Ok(report) if report.success => {
                            warmed.push((info.version_id, gid));
                        }
                        Ok(report) => {
                            warn!(
                                "Warmup failed for {} on GPU {}: {}",
                                info.version_id,
                                gid,
                                report.error_message.unwrap_or_default()
                            );
                        }
                        Err(e) => {
                            warn!(
                                "Warmup error for {} on GPU {}: {}",
                                info.version_id, gid, e
                            );
                        }
                    }
                }
            }
        }

        Ok(warmed)
    }

    pub async fn schedule_for_inference(
        &self,
        version_id: Uuid,
        required_mb: u64,
    ) -> Result<(usize, bool), AppError> {
        debug!(
            "Scheduling inference for model {} ({}MB required)",
            version_id, required_mb
        );

        let loaded_gpus = self.model_manager.get_loaded_gpus(version_id);

        if !loaded_gpus.is_empty() {
            let selected = loaded_gpus
                .iter()
                .filter_map(|&gid| self.balancer.get_gpu(gid))
                .filter(|g| g.is_healthy)
                .max_by(|a, b| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal))
                .map(|g| g.gpu_id);

            if let Some(gid) = selected {
                self.model_manager.record_access(version_id);
                self.model_manager.increment_ref(version_id);
                let needs_warmup = !self.model_manager.is_warmed_up(version_id);
                debug!(
                    "Using already loaded model {} on GPU {} (warmup needed: {})",
                    version_id, gid, needs_warmup
                );
                return Ok((gid, needs_warmup));
            }
        }

        info!(
            "Model {} not loaded, selecting GPU for new deployment",
            version_id
        );

        let strategy = if !loaded_gpus.is_empty() {
            GpuSelectionStrategy::Affinity
        } else {
            GpuSelectionStrategy::LeastLoaded
        };

        let gpu_id = self.balancer.select_gpu(
            required_mb,
            strategy,
            Some(version_id),
        )?;

        let mm = self.model_manager.clone();
        let warmer = self.warmer.clone();
        let batch_size = self.config.warmup_batch_size;
        let iterations = self.config.warmup_iterations;

        tokio::spawn(async move {
            match mm.load_model(version_id, gpu_id).await {
                Ok(_) => {
                    let _ = warmer
                        .warmup_model(version_id, gpu_id, Some(batch_size), Some(iterations))
                        .await;
                }
                Err(e) => {
                    error!("Async load failed for {} on GPU {}: {}", version_id, gpu_id, e);
                }
            }
        });

        Ok((gpu_id, true))
    }

    pub fn release_inference_slot(&self, version_id: Uuid) {
        self.model_manager.decrement_ref(version_id);
    }

    pub fn handle_event(&self, event: SchedulerEvent) {
        debug!("Handling scheduler event: {:?}", event);

        match event {
            SchedulerEvent::ModelLoaded { version_id, gpu_id } => {
                observability::increment_requests("model_loaded", &version_id.to_string(), "ok");
            }
            SchedulerEvent::ModelUnloaded { version_id, gpu_id } => {
                observability::increment_requests("model_unloaded", &version_id.to_string(), "ok");
            }
            SchedulerEvent::TrafficChanged { version_id, rps } => {
                debug!("Traffic changed for {}: {:.2} rps", version_id, rps);
            }
            SchedulerEvent::Heartbeat {
                node_address,
                gpu_id,
                healthy,
                qps,
                avg_latency_ms,
                error_rate,
            } => {
                let status = if healthy { "ok" } else { "error" };
                observability::record_inference_latency(
                    "heartbeat",
                    &gpu_id.to_string(),
                    status,
                    avg_latency_ms,
                );
                observability::record_gpu_metrics(
                    &gpu_id.to_string(),
                    &observability::GpuMetrics {
                        utilization_percent: 0.0,
                        memory_used_mb: 0,
                        memory_total_mb: self.balancer.get_gpu(gpu_id).map(|g| g.total_mb).unwrap_or(0),
                        temperature_c: 0.0,
                    },
                );
                let _ = (node_address, qps, error_rate);
            }
            SchedulerEvent::GpuAdded {
                gpu_id,
                total_mb,
                node_address,
            } => {
                info!("GPU {} added with {}MB (node: {})", gpu_id, total_mb, node_address);
            }
            SchedulerEvent::GpuRemoved { gpu_id } => {
                warn!("GPU {} removed from cluster", gpu_id);
            }
            SchedulerEvent::ModelStatusChanged {
                version_id,
                old_status,
                new_status,
            } => {
                info!(
                    "Model {} status changed: {:?} -> {:?}",
                    version_id, old_status, new_status
                );
                if new_status == common::types::ModelStatus::Online
                    && old_status != common::types::ModelStatus::Online
                {
                    let scheduler_self = unsafe {
                        std::mem::transmute::<&Self, &'static Self>(self)
                    };
                    let vid = version_id;
                    tokio::spawn(async move {
                        let gpus = scheduler_self.model_manager.get_loaded_gpus(vid);
                        if gpus.is_empty() {
                            if let Ok(info) = scheduler_self
                                .model_registry
                                .list_versions(Uuid::nil())
                                .await
                            {
                                if let Some(v) = info.into_iter().find(|v| v.id == vid) {
                                    if let Ok(gid) = scheduler_self.balancer.select_gpu(
                                        v.gpu_memory_mb,
                                        GpuSelectionStrategy::LeastLoaded,
                                        Some(vid),
                                    ) {
                                        let _ = scheduler_self.model_manager.load_model(vid, gid).await;
                                    }
                                }
                            }
                        }
                    });
                }
            }
            SchedulerEvent::InferenceRequested { version_id } => {
                self.model_manager.record_access(version_id);
            }
            SchedulerEvent::ModelMigrated { version_id, from_gpu, to_gpu } => {
                info!("Model {} migrated from GPU {} to GPU {}", version_id, from_gpu, to_gpu);
            }
            SchedulerEvent::GpuOverloaded { gpu_id, util_percent, memory_percent } => {
                warn!("GPU {} overloaded: util={:.1}%, mem={:.1}%", gpu_id, util_percent, memory_percent);
            }
            SchedulerEvent::GpuRecovered { gpu_id } => {
                info!("GPU {} recovered", gpu_id);
            }
            SchedulerEvent::NodeDead { node_id, address } => {
                warn!("Node {} ({}) is dead", node_id, address);
            }
            SchedulerEvent::SchedulingCycleCompleted { cycle_id, decisions, duration_ms } => {
                debug!("Scheduling cycle {} completed: {} decisions in {}ms", cycle_id, decisions, duration_ms);
            }
        }
    }

    fn log_scheduling_stats(&self) {
        let summary = self.balancer.get_load_summary();
        let loaded_count = self.model_manager.total_loaded_count();
        let health_score = self.heartbeat.get_cluster_health_score();

        info!(
            "Scheduler stats: models={}, gpus={:.0}, healthy_gpus={:.0}, mem_usage={:.1}%, avg_util={:.1}%, health={:.1}%",
            loaded_count,
            summary.get("gpu_count").copied().unwrap_or(0.0),
            summary.get("healthy_gpu_count").copied().unwrap_or(0.0),
            summary.get("memory_usage_percent").copied().unwrap_or(0.0),
            summary.get("avg_utilization").copied().unwrap_or(0.0),
            health_score * 100.0
        );
    }

    pub fn get_status(&self) -> HashMap<String, serde_json::Value> {
        let mut status = HashMap::new();

        let gpu_summary = self.balancer.get_load_summary();
        for (k, v) in gpu_summary {
            status.insert(k, serde_json::json!(v));
        }

        status.insert(
            "loaded_models".to_string(),
            serde_json::json!(self.model_manager.total_loaded_count()),
        );
        status.insert(
            "cluster_health".to_string(),
            serde_json::json!(self.heartbeat.get_cluster_health_score()),
        );
        status.insert(
            "unhealthy_nodes".to_string(),
            serde_json::json!(self.heartbeat.get_unhealthy_nodes().len()),
        );
        status.insert(
            "warmup_pending".to_string(),
            serde_json::json!(
                self.model_manager
                    .list_loaded_models()
                    .iter()
                    .filter(|m| !m.is_warmed_up)
                    .count()
            ),
        );

        status
    }

    pub fn register_runtime_endpoint(
        &self,
        version_id: Uuid,
        address: String,
    ) -> Result<(), AppError> {
        let config = traffic_router::EndpointConfig {
            address,
            model_version_id: version_id,
            max_retries: 2,
            timeout_ms: 30_000,
        };

        let rt = self.runtime_client.clone();
        tokio::spawn(async move {
            if let Err(e) = rt.register_endpoint(config).await {
                error!("Failed to register runtime endpoint for {}: {}", version_id, e);
            }
        });

        Ok(())
    }

    pub async fn schedule_tick(&self) -> Result<SchedulingDecision, AppError> {
        let cycle_start = std::time::Instant::now();
        let cycle_id = {
            let mut counter = self.cycle_counter.write();
            *counter += 1;
            *counter
        };

        info!(
            "=== Scheduling tick #{} started ===",
            cycle_id
        );

        let mut decision = SchedulingDecision::new();

        self.sync_gpu_model_counts();

        info!(
            "Tick #{} Step 1/7: Collecting heartbeats and GPU status...",
            cycle_id
        );
        let dead_nodes = self.heartbeat.check_heartbeats();
        for gpu in self.balancer.list_gpus() {
            let state = self.balancer.get_gpu_node_state(gpu.gpu_id, &self.config);
            match state {
                GpuNodeState::Overloaded => {
                    let _ = self.event_sender.send(SchedulerEvent::GpuOverloaded {
                        gpu_id: gpu.gpu_id,
                        util_percent: gpu.util_percent,
                        memory_percent: gpu.memory_usage_percent(),
                    });
                }
                GpuNodeState::Healthy if !gpu.is_healthy => {
                    let _ = self.event_sender.send(SchedulerEvent::GpuRecovered {
                        gpu_id: gpu.gpu_id,
                    });
                }
                _ => {}
            }
        }

        info!(
            "Tick #{} Step 2/7: Detecting dead nodes and migrating models...",
            cycle_id
        );
        for dead in &dead_nodes {
            warn!(
                "Processing dead node {} ({}), migrating {} models",
                dead.node_id, dead.address, dead.models_hosted.len()
            );

            for &version_id in &dead.models_hosted {
                for &gpu_id in &dead.gpu_ids {
                    if self.model_manager.is_loaded_on_gpu(version_id, gpu_id) {
                        match self.migrate_model_from_dead(version_id, gpu_id).await {
                            Ok(new_gpu) => {
                                decision.migrated_models.push((version_id, gpu_id, new_gpu));
                                decision.details.push(
                                    SchedulingDecisionDetail::migrate(
                                        version_id,
                                        gpu_id,
                                        new_gpu,
                                        format!("Source GPU {} on dead node {}", gpu_id, dead.node_id),
                                    )
                                );
                            }
                            Err(e) => {
                                error!(
                                    "Failed to migrate model {} from dead GPU {}: {}",
                                    version_id, gpu_id, e
                                );
                            }
                        }
                    }
                }
            }
        }

        info!(
            "Tick #{} Step 3/7: Calculating model heat scores...",
            cycle_id
        );
        let heat_scores = self.model_manager.get_heat_scores();
        info!(
            "Calculated heat scores for {} models, top 3: {:?}",
            heat_scores.len(),
            heat_scores
                .iter()
                .take(3)
                .map(|h| (h.version_id, format!("{:.1}", h.score)))
                .collect::<Vec<_>>()
        );

        info!(
            "Tick #{} Step 4/7: Auto-loading high-heat models...",
            cycle_id
        );
        match self
            .model_manager
            .auto_load_models(self.config.heat_score_hot_threshold, 10)
            .await
        {
            Ok(loaded) => {
                for (vid, gid) in &loaded {
                    decision.loaded_models.push((*vid, *gid));
                    decision.details.push(SchedulingDecisionDetail::load(
                        *vid,
                        *gid,
                        "Auto-load: high heat score".to_string(),
                    ));
                }
                info!("Auto-loaded {} models", loaded.len());
            }
            Err(e) => {
                warn!("Auto-load failed: {}", e);
            }
        }

        info!(
            "Tick #{} Step 5/7: Auto-unloading low-heat models...",
            cycle_id
        );
        match self
            .model_manager
            .auto_unload_models(
                self.config.heat_score_cold_threshold,
                self.config.model_ttl_seconds,
            )
            .await
        {
            Ok(unloaded) => {
                for (vid, gid) in &unloaded {
                    decision.unloaded_models.push((*vid, *gid));
                    decision.details.push(SchedulingDecisionDetail::unload(
                        *vid,
                        *gid,
                        "Auto-unload: low heat score / TTL expired".to_string(),
                    ));
                }
                info!("Auto-unloaded {} models", unloaded.len());
            }
            Err(e) => {
                warn!("Auto-unload failed: {}", e);
            }
        }

        let oom_decisions = self.prevent_oom().await?;
        decision.unloaded_models.extend(oom_decisions);

        info!(
            "Tick #{} Step 6/7: Executing GPU rebalancing...",
            cycle_id
        );
        let imbalance = self.balancer.compute_imbalance_score();
        if imbalance > self.config.rebalance_imbalance_threshold {
            warn!(
                "GPU memory imbalance detected: {:.1}% > threshold {:.1}%, triggering rebalance",
                imbalance, self.config.rebalance_imbalance_threshold
            );

            let rebalance_decisions = self
                .balancer
                .rebalance(Some(self.config.rebalance_imbalance_threshold));
            for rd in rebalance_decisions {
                decision.merge(rd);
            }

            let overloaded = self.balancer.detect_overloaded_gpus(&self.config);
            for og in &overloaded {
                let gid: usize = og.id.parse().unwrap_or(0);
                match self
                    .model_manager
                    .evict_model_for_memory(gid, self.config.safety_margin_mb + 1024)
                    .await
                {
                    Ok(evicted) => {
                        for vid in evicted {
                            decision.unloaded_models.push((vid, gid));
                            decision.details.push(SchedulingDecisionDetail::unload(
                                vid,
                                gid,
                                format!("Evicted from overloaded GPU {}", gid),
                            ));
                        }
                    }
                    Err(e) => {
                        warn!("Rebalance eviction failed for GPU {}: {}", gid, e);
                    }
                }
            }
        } else {
            debug!(
                "GPU imbalance {:.1}% within acceptable range",
                imbalance
            );
        }

        info!(
            "Tick #{} Step 7/7: Triggering warmup tasks...",
            cycle_id
        );
        match self.warmer.schedule_warmup_for_all().await {
            Ok(warmed) => {
                for (vid, gid) in &warmed {
                    decision.warmed_models.push((*vid, *gid));
                    decision.details.push(SchedulingDecisionDetail::warmup(
                        *vid,
                        *gid,
                        "Scheduled batch warmup".to_string(),
                    ));
                }
                if !warmed.is_empty() {
                    info!("Scheduled warmup for {} models", warmed.len());
                }
            }
            Err(e) => {
                warn!("Warmup scheduling failed: {}", e);
            }
        }

        let elapsed_ms = cycle_start.elapsed().as_millis() as u64;
        let total_actions = decision.total_actions();

        let _ = self.event_sender.send(SchedulerEvent::SchedulingCycleCompleted {
            cycle_id,
            decisions: total_actions as u32,
            duration_ms: elapsed_ms,
        });

        if total_actions > 0 {
            info!(
                "=== Tick #{} completed: {} actions in {}ms ===",
                cycle_id, total_actions, elapsed_ms
            );
        } else {
            debug!(
                "=== Tick #{} completed: no actions in {}ms ===",
                cycle_id, elapsed_ms
            );
        }

        self.log_scheduling_stats();

        Ok(decision)
    }

    async fn migrate_model_from_dead(
        &self,
        version_id: Uuid,
        from_gpu: usize,
    ) -> Result<usize, AppError> {
        let info = self
            .model_manager
            .get_model_info(version_id)
            .ok_or_else(|| AppError::ModelNotFound(version_id.to_string()))?;

        let other_loaded = self.model_manager.get_loaded_gpus(version_id);
        if other_loaded.iter().any(|&g| g != from_gpu) {
            debug!(
                "Model {} already loaded on other GPUs, removing from dead GPU {}",
                version_id, from_gpu
            );
            self.model_manager
                .unload_model(version_id, from_gpu, true)
                .await?;
            return Ok(other_loaded.into_iter().find(|&g| g != from_gpu).unwrap());
        }

        let new_gpu = self.balancer.select_gpu(
            info.size_mb,
            GpuSelectionStrategy::LeastLoaded,
            Some(version_id),
        )?;

        info!(
            "Migrating model {} ({}MB): dead GPU {} -> GPU {}",
            version_id, info.size_mb, from_gpu, new_gpu
        );

        let _ = self.model_manager.load_model(version_id, new_gpu).await?;
        self.model_manager
            .unload_model(version_id, from_gpu, true)
            .await?;

        let _ = self.event_sender.send(SchedulerEvent::ModelMigrated {
            version_id,
            from_gpu,
            to_gpu: new_gpu,
        });

        Ok(new_gpu)
    }

    pub async fn trigger_scheduling_event(&self) -> Result<SchedulingDecision, AppError> {
        info!("Manual scheduling trigger invoked");
        let result = self.schedule_tick().await;
        match &result {
            Ok(decision) if !decision.is_empty() => {
                info!(
                    "Manual scheduling completed with {} actions",
                    decision.total_actions()
                );
            }
            Ok(_) => {
                info!("Manual scheduling completed with no actions needed");
            }
            Err(e) => {
                error!("Manual scheduling failed: {}", e);
            }
        }
        result
    }

    pub async fn collect_cluster_snapshot(
        &self,
    ) -> HashMap<String, serde_json::Value> {
        let mut snapshot = HashMap::new();

        snapshot.insert("timestamp".to_string(), serde_json::json!(chrono::Utc::now().to_rfc3339()));

        let gpus: Vec<serde_json::Value> = self
            .balancer
            .list_gpus()
            .iter()
            .map(|g| {
                serde_json::json!({
                    "gpu_id": g.gpu_id,
                    "total_mb": g.total_mb,
                    "free_mb": g.free_mb,
                    "used_mb": g.used_mb(),
                    "util_percent": g.util_percent,
                    "memory_percent": g.memory_usage_percent(),
                    "model_count": g.model_count,
                    "node_address": g.node_address,
                    "healthy": g.is_healthy,
                    "state": self.balancer.get_gpu_node_state(g.gpu_id, &self.config).as_str(),
                })
            })
            .collect();
        snapshot.insert("gpus".to_string(), serde_json::json!(gpus));

        let nodes: Vec<serde_json::Value> = self
            .heartbeat
            .get_alive_nodes()
            .iter()
            .map(|n| {
                serde_json::json!({
                    "node_id": n.node_id,
                    "address": n.address,
                    "gpu_ids": n.gpu_ids,
                    "state": n.state.as_str(),
                    "registered_at_secs": n.registered_at.elapsed().as_secs(),
                    "last_heartbeat_secs": n.last_heartbeat.elapsed().as_secs(),
                    "total_models": n.total_models,
                    "qps": n.qps,
                })
            })
            .collect();
        snapshot.insert("nodes".to_string(), serde_json::json!(nodes));

        let deployments: Vec<serde_json::Value> = self
            .model_manager
            .get_all_deployments()
            .iter()
            .take(50)
            .map(|d| {
                serde_json::json!({
                    "version_id": d.version_id,
                    "model_name": d.model_name,
                    "version": d.version,
                    "deployed_gpus": d.deployed_gpus,
                    "primary_gpu": d.primary_gpu,
                    "size_mb": d.size_mb,
                    "heat_score": d.heat_score,
                    "access_count": d.access_count,
                    "idle_secs": d.last_accessed_at.elapsed().as_secs(),
                    "warmed_up": d.is_warmed_up,
                })
            })
            .collect();
        snapshot.insert("deployments".to_string(), serde_json::json!(deployments));

        snapshot.extend(self.get_status());
        snapshot
    }

    pub fn get_current_cycle_id(&self) -> CycleId {
        *self.cycle_counter.read()
    }

    pub async fn handle_overloaded_gpu(&self, gpu_id: usize) -> Result<Vec<Uuid>, AppError> {
        warn!("Handling overloaded GPU {}", gpu_id);
        let freed = self
            .model_manager
            .evict_model_for_memory(gpu_id, self.config.safety_margin_mb + 2048)
            .await?;
        Ok(freed)
    }

    pub async fn reload_models_from_registry(&self) -> Result<usize, AppError> {
        info!("Reloading models from registry...");
        let versions = self
            .model_registry
            .list_versions(Uuid::nil())
            .await
            .unwrap_or_default();

        let online_versions: Vec<_> = versions
            .into_iter()
            .filter(|v| v.status == common::types::ModelStatus::Online)
            .collect();

        info!(
            "Found {} online versions in registry",
            online_versions.len()
        );

        let mut loaded = 0usize;
        for v in online_versions.iter().take(100) {
            if !self.model_manager.is_loaded(v.id) {
                match self.balancer.select_gpu(
                    v.gpu_memory_mb,
                    GpuSelectionStrategy::BinPacking,
                    Some(v.id),
                ) {
                    Ok(gpu_id) => {
                        if let Ok(_) = self.model_manager.load_model(v.id, gpu_id).await {
                            loaded += 1;
                        }
                    }
                    Err(_) => {
                        debug!("No GPU available for model version {}", v.id);
                    }
                }
            }
        }

        info!("Auto-loaded {} models from registry", loaded);
        Ok(loaded)
    }
}

impl Clone for ResourceScheduler {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            balancer: self.balancer.clone(),
            model_manager: self.model_manager.clone(),
            warmer: self.warmer.clone(),
            heartbeat: self.heartbeat.clone(),
            runtime_client: self.runtime_client.clone(),
            model_registry: self.model_registry.clone(),
            event_sender: self.event_sender.clone(),
            event_receiver: parking_lot::RwLock::new(None),
            scheduled_tasks: parking_lot::RwLock::new(Vec::new()),
            cycle_counter: parking_lot::RwLock::new(*self.cycle_counter.read()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_scheduler() -> Arc<ResourceScheduler> {
        let runtime = Arc::new(RuntimeClient::new());
        let registry = Arc::new(ModelRegistryService::new(
            db::DatabasePool::mock(),
            db::RedisClient::mock(),
            model_registry::MinioStorage::mock(),
        ));

        let scheduler = ResourceScheduler::new(SchedulerConfig::default(), runtime, registry);

        scheduler.add_gpu(0, 24000, Some("127.0.0.1:50051".to_string()));
        scheduler.add_gpu(1, 24000, Some("127.0.0.1:50052".to_string()));

        Arc::new(scheduler)
    }

    #[tokio::test]
    async fn test_add_and_remove_gpu() {
        let scheduler = create_test_scheduler();
        assert_eq!(scheduler.balancer.gpu_count(), 2);

        scheduler.remove_gpu(0);
        assert_eq!(scheduler.balancer.gpu_count(), 1);
    }

    #[tokio::test]
    async fn test_schedule_inference_new_model() {
        let scheduler = create_test_scheduler();
        let vid = Uuid::new_v4();

        let result = scheduler.schedule_for_inference(vid, 2048).await;
        assert!(result.is_ok());
        let (gpu_id, needs_warmup) = result.unwrap();
        assert!((0..=1).contains(&gpu_id));
        assert!(needs_warmup);
    }

    #[test]
    fn test_get_status() {
        let scheduler = create_test_scheduler();
        let status = scheduler.get_status();
        assert!(status.contains_key("gpu_count"));
        assert!(status.contains_key("loaded_models"));
        assert!(status.contains_key("cluster_health"));
    }
}
