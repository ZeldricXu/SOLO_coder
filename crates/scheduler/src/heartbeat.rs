use common::error::AppError;
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use traffic_router::RuntimeClient;

use crate::balancer::GpuLoadBalancer;
use crate::model_manager::ModelLifecycleManager;
use crate::types::{
    DeadNode, GpuNodeState, HeartbeatInfo, HeartbeatStats, NodeInfo, SchedulerConfig,
    SchedulerEvent,
};

type HeartbeatKey = (String, usize);

struct RegisteredNode {
    node_id: String,
    address: String,
    gpu_ids: Vec<usize>,
    registered_at: Instant,
}

pub struct HeartbeatManager {
    heartbeats: DashMap<HeartbeatKey, HeartbeatInfo>,
    runtime_client: Arc<RuntimeClient>,
    balancer: Arc<GpuLoadBalancer>,
    model_manager: Arc<ModelLifecycleManager>,
    event_sender: broadcast::Sender<SchedulerEvent>,
    config: SchedulerConfig,
    registered_nodes: DashMap<String, Vec<usize>>,
    node_registry: DashMap<String, RegisteredNode>,
    address_to_node_id: DashMap<String, String>,
}

impl HeartbeatManager {
    pub fn new(
        runtime_client: Arc<RuntimeClient>,
        balancer: Arc<GpuLoadBalancer>,
        model_manager: Arc<ModelLifecycleManager>,
        event_sender: broadcast::Sender<SchedulerEvent>,
        config: SchedulerConfig,
    ) -> Self {
        Self {
            heartbeats: DashMap::new(),
            runtime_client,
            balancer,
            model_manager,
            event_sender,
            config,
            registered_nodes: DashMap::new(),
            node_registry: DashMap::new(),
            address_to_node_id: DashMap::new(),
        }
    }

    pub fn register_node(&self, node_address: String, gpu_ids: Vec<usize>) {
        let node_id = format!("node-{}", &node_address.replace(':', "-").replace('.', "-"));
        self.register_node_with_id(node_id, node_address, gpu_ids);
    }

    pub fn register_node_with_id(&self, node_id: String, node_address: String, gpu_ids: Vec<usize>) {
        info!(
            "Registering heartbeat node {} (id: {}) with GPUs: {:?}",
            node_address, node_id, gpu_ids
        );

        for &gpu_id in &gpu_ids {
            let key: HeartbeatKey = (node_address.clone(), gpu_id);
            if !self.heartbeats.contains_key(&key) {
                self.heartbeats
                    .insert(key, HeartbeatInfo::new(node_address.clone(), gpu_id));
            }
        }

        self.registered_nodes
            .insert(node_address.clone(), gpu_ids.clone());

        let node = RegisteredNode {
            node_id: node_id.clone(),
            address: node_address.clone(),
            gpu_ids: gpu_ids.clone(),
            registered_at: Instant::now(),
        };

        self.node_registry.insert(node_id.clone(), node);
        self.address_to_node_id.insert(node_address, node_id);
    }

    pub fn deregister_node(&self, node_id: &str) {
        info!("Deregistering heartbeat node id: {}", node_id);

        if let Some((_, node)) = self.node_registry.remove(node_id) {
            self.address_to_node_id.remove(&node.address);
            self.unregister_node(&node.address);
        }
    }

    pub fn unregister_node(&self, node_address: &str) {
        info!("Unregistering heartbeat node {}", node_address);

        if let Some((_, gpu_ids)) = self.registered_nodes.remove(node_address) {
            for gpu_id in gpu_ids {
                let key: HeartbeatKey = (node_address.to_string(), gpu_id);
                self.heartbeats.remove(&key);
            }
        }

        if let Some((_, node_id)) = self.address_to_node_id.remove(node_address) {
            self.node_registry.remove(&node_id);
        }
    }

    pub fn report_heartbeat(
        &self,
        node_id: &str,
        gpu_id: usize,
        stats: HeartbeatStats,
    ) -> Result<(), AppError> {
        let node = self
            .node_registry
            .get(node_id)
            .ok_or_else(|| AppError::GpuNotFound(format!("Node {} not registered", node_id)))?;

        let node_address = node.address.clone();
        let key: HeartbeatKey = (node_address.clone(), gpu_id);

        debug!(
            "Received heartbeat from node {} (addr: {}) GPU {}: qps={:.2}, util={:.1}%, mem_used={}MB",
            node_id, node_address, gpu_id, stats.qps, stats.gpu_util_percent, stats.gpu_memory_used_mb
        );

        let healthy = stats.error_rate < 0.5;

        self.record_heartbeat_result(
            &node_address,
            gpu_id,
            healthy,
            stats.qps,
            stats.avg_latency_ms,
            stats.error_rate,
        );

        self.balancer
            .report_usage(gpu_id, stats.gpu_util_percent, stats.gpu_memory_used_mb);

        if let Some(mut hb) = self.heartbeats.get_mut(&key) {
            hb.qps = stats.qps;
            hb.avg_latency_ms = stats.avg_latency_ms;
            hb.error_rate = stats.error_rate;
            hb.gpu_util_percent = stats.gpu_util_percent;
            hb.gpu_memory_used_mb = stats.gpu_memory_used_mb;
        }

        for vid in &stats.active_models {
            self.model_manager.record_access(*vid);
        }

        Ok(())
    }

    pub fn check_heartbeats(&self) -> Vec<DeadNode> {
        let now = Instant::now();
        let timeout_secs = self.config.heartbeat_timeout_secs;
        let mut dead_nodes: Vec<DeadNode> = Vec::new();
        let mut dead_node_ids: std::collections::HashSet<String> = std::collections::HashSet::new();

        for node_entry in self.node_registry.iter() {
            let node_id = node_entry.key().clone();
            let node = node_entry.value();
            let mut all_gpus_dead = true;
            let mut max_missed = 0u32;
            let mut last_hb = Instant::now();
            let mut has_any_heartbeat = false;

            for &gpu_id in &node.gpu_ids {
                let key: HeartbeatKey = (node.address.clone(), gpu_id);
                if let Some(hb) = self.heartbeats.get(&key) {
                    has_any_heartbeat = true;
                    let elapsed = now.duration_since(hb.last_heartbeat).as_secs();
                    let missed = elapsed / self.config.heartbeat_interval_secs.max(1);
                    max_missed = max_missed.max(missed as u32);
                    last_hb = last_hb.min(hb.last_heartbeat);

                    if elapsed < timeout_secs {
                        all_gpus_dead = false;
                    }
                }
            }

            let is_timeout = !has_any_heartbeat
                || now.duration_since(last_hb).as_secs() > timeout_secs;

            if is_timeout && all_gpus_dead && !dead_node_ids.contains(&node_id) {
                let models_hosted: Vec<Uuid> = node
                    .gpu_ids
                    .iter()
                    .flat_map(|&gid| self.model_manager.models_on_gpu(gid))
                    .collect();

                warn!(
                    "Detected dead node {} (addr: {}): missed {} heartbeats, {} models hosted",
                    node_id,
                    node.address,
                    max_missed,
                    models_hosted.len()
                );

                dead_nodes.push(DeadNode {
                    node_id: node_id.clone(),
                    address: node.address.clone(),
                    gpu_ids: node.gpu_ids.clone(),
                    last_heartbeat: last_hb,
                    missed_heartbeats: max_missed,
                    models_hosted,
                });

                dead_node_ids.insert(node_id);

                let _ = self.event_sender.send(SchedulerEvent::NodeDead {
                    node_id: node_id.clone(),
                    address: node.address.clone(),
                });
            }
        }

        if !dead_nodes.is_empty() {
            info!("Heartbeat check found {} dead nodes", dead_nodes.len());
        }

        dead_nodes
    }

    pub fn get_alive_nodes(&self) -> Vec<NodeInfo> {
        let now = Instant::now();
        let timeout_secs = self.config.heartbeat_timeout_secs;
        let mut nodes: Vec<NodeInfo> = Vec::new();

        for node_entry in self.node_registry.iter() {
            let node = node_entry.value();
            let mut is_alive = false;
            let mut total_qps = 0.0f64;
            let mut total_models = 0usize;
            let mut last_hb = now;

            for &gpu_id in &node.gpu_ids {
                let key: HeartbeatKey = (node.address.clone(), gpu_id);
                if let Some(hb) = self.heartbeats.get(&key) {
                    let elapsed = now.duration_since(hb.last_heartbeat).as_secs();
                    if elapsed < timeout_secs {
                        is_alive = true;
                    }
                    total_qps += hb.qps;
                    last_hb = last_hb.min(hb.last_heartbeat);
                }
                total_models += self.model_manager.models_on_gpu(gpu_id).len();
            }

            let state = if is_alive {
                let mut has_overload = false;
                for &gpu_id in &node.gpu_ids {
                    if let Some(gpu) = self.balancer.get_gpu(gpu_id) {
                        let mem_util = gpu.memory_usage_percent();
                        if gpu.util_percent >= self.config.overload_util_threshold
                            || mem_util >= self.config.overload_memory_threshold
                        {
                            has_overload = true;
                            break;
                        }
                    }
                }
                if has_overload {
                    GpuNodeState::Overloaded
                } else {
                    GpuNodeState::Healthy
                }
            } else {
                GpuNodeState::Offline
            };

            nodes.push(NodeInfo {
                node_id: node.node_id.clone(),
                address: node.address.clone(),
                gpu_ids: node.gpu_ids.clone(),
                state,
                registered_at: node.registered_at,
                last_heartbeat: last_hb,
                total_models,
                qps: total_qps,
            });
        }

        nodes.sort_by(|a, b| a.node_id.cmp(&b.node_id));
        nodes
    }

    pub fn get_node_by_address(&self, address: &str) -> Option<NodeInfo> {
        let node_id = self.address_to_node_id.get(address)?;
        let node = self.node_registry.get(node_id.value())?;

        let alive = self.get_alive_nodes();
        alive.into_iter().find(|n| n.node_id == *node_id)
    }

    pub fn get_node_stats(&self) -> HashMap<String, HashMap<String, f64>> {
        let mut stats: HashMap<String, HashMap<String, f64>> = HashMap::new();

        for node_entry in self.node_registry.iter() {
            let node = node_entry.value();
            let mut node_stats: HashMap<String, f64> = HashMap::new();
            let mut total_qps = 0.0f64;
            let mut total_latency = 0.0f64;
            let mut total_error_rate = 0.0f64;
            let mut total_util = 0.0f64;
            let mut total_mem_used = 0u64;
            let mut total_mem_total = 0u64;
            let mut count = 0u32;

            for &gpu_id in &node.gpu_ids {
                let key: HeartbeatKey = (node.address.clone(), gpu_id);
                if let Some(hb) = self.heartbeats.get(&key) {
                    total_qps += hb.qps;
                    total_latency += hb.avg_latency_ms;
                    total_error_rate += hb.error_rate;
                    total_util += hb.gpu_util_percent;
                    total_mem_used += hb.gpu_memory_used_mb;
                    count += 1;
                }
                if let Some(gpu) = self.balancer.get_gpu(gpu_id) {
                    total_mem_total += gpu.total_mb;
                }
            }

            node_stats.insert("qps".to_string(), total_qps);
            node_stats.insert(
                "avg_latency_ms".to_string(),
                if count > 0 {
                    total_latency / count as f64
                } else {
                    0.0
                },
            );
            node_stats.insert(
                "error_rate".to_string(),
                if count > 0 {
                    total_error_rate / count as f64
                } else {
                    0.0
                },
            );
            node_stats.insert(
                "avg_gpu_util".to_string(),
                if count > 0 {
                    total_util / count as f64
                } else {
                    0.0
                },
            );
            node_stats.insert("gpu_mem_used_mb".to_string(), total_mem_used as f64);
            node_stats.insert("gpu_mem_total_mb".to_string(), total_mem_total as f64);
            node_stats.insert(
                "gpu_count".to_string(),
                node.gpu_ids.len() as f64,
            );

            stats.insert(node.node_id.clone(), node_stats);
        }

        stats
    }

    pub fn get_node_id_for_address(&self, address: &str) -> Option<String> {
        self.address_to_node_id.get(address).map(|id| id.clone())
    }

    pub fn registered_node_count(&self) -> usize {
        self.node_registry.len()
    }

    pub async fn start(&self) {
        let interval = std::time::Duration::from_secs(self.config.heartbeat_interval_secs);
        info!(
            "Starting heartbeat manager with interval {:?}",
            interval
        );

        loop {
            self.check_all_nodes().await;
            tokio::time::sleep(interval).await;
        }
    }

    pub async fn check_all_nodes(&self) {
        debug!("Checking heartbeat for {} registered nodes", self.registered_nodes.len());

        let nodes: Vec<(String, Vec<usize>)> = self
            .registered_nodes
            .iter()
            .map(|entry| (entry.key().clone(), entry.value().clone()))
            .collect();

        for (node_address, gpu_ids) in nodes {
            for &gpu_id in &gpu_ids {
                self.ping_node(&node_address, gpu_id).await;
            }
        }

        self.handle_unhealthy_nodes().await;
    }

    async fn ping_node(&self, node_address: &str, gpu_id: usize) {
        let key: HeartbeatKey = (node_address.to_string(), gpu_id);
        let start = Instant::now();

        let version_ids: Vec<Uuid> = self
            .model_manager
            .models_on_gpu(gpu_id)
            .into_iter()
            .take(5)
            .collect();

        let mut any_healthy = version_ids.is_empty();
        let mut total_latency: f64 = 0.0;
        let mut checks: u32 = 0;
        let mut qps_total: f64 = 0.0;

        for vid in &version_ids {
            if !self.runtime_client.has_endpoint(*vid) {
                continue;
            }

            match self.runtime_client.health_check(*vid).await {
                Ok(healthy) => {
                    checks += 1;
                    total_latency += start.elapsed().as_secs_f64() * 1000.0;

                    if healthy {
                        any_healthy = true;
                        qps_total += 1.0;
                    }
                }
                Err(e) => {
                    debug!(
                        "Health check error for version {} on node {}: {}",
                        vid, node_address, e
                    );
                }
            }
        }

        let avg_latency = if checks > 0 {
            total_latency / checks as f64
        } else {
            0.0
        };

        let error_rate = if checks > 0 {
            1.0 - (if any_healthy { 1.0 } else { 0.0 })
        } else {
            0.0
        };

        let qps = qps_total / (self.config.heartbeat_interval_secs as f64).max(1.0);

        self.record_heartbeat_result(
            node_address,
            gpu_id,
            any_healthy,
            qps,
            avg_latency,
            error_rate,
        );

        if any_healthy {
            debug!(
                "Heartbeat OK for node {} GPU {}: qps={:.2}, latency={:.2}ms",
                node_address, gpu_id, qps, avg_latency
            );
        } else {
            warn!(
                "Heartbeat FAILED for node {} GPU {}: consecutive_failures={}",
                node_address,
                gpu_id,
                self.heartbeats
                    .get(&key)
                    .map(|h| h.consecutive_failures)
                    .unwrap_or(0)
            );
        }
    }

    fn record_heartbeat_result(
        &self,
        node_address: &str,
        gpu_id: usize,
        healthy: bool,
        qps: f64,
        avg_latency_ms: f64,
        error_rate: f64,
    ) {
        let key: HeartbeatKey = (node_address.to_string(), gpu_id);

        let event = SchedulerEvent::Heartbeat {
            node_address: node_address.to_string(),
            gpu_id,
            healthy,
            qps,
            avg_latency_ms,
            error_rate,
        };

        let _ = self.event_sender.send(event);

        if let Some(mut hb) = self.heartbeats.get_mut(&key) {
            hb.last_heartbeat = Instant::now();
            hb.qps = qps;
            hb.avg_latency_ms = avg_latency_ms;
            hb.error_rate = error_rate;

            if healthy {
                hb.consecutive_failures = 0;
                hb.is_healthy = true;
            } else {
                hb.consecutive_failures = hb.consecutive_failures.saturating_add(1);
                if hb.consecutive_failures >= self.config.max_unhealthy_heartbeats {
                    hb.is_healthy = false;
                }
            }
        }
    }

    async fn handle_unhealthy_nodes(&self) {
        let unhealthy: Vec<(String, usize)> = self
            .heartbeats
            .iter()
            .filter(|h| !h.value().is_healthy)
            .map(|h| (h.key().0.clone(), h.key().1))
            .collect();

        for (node_address, gpu_id) in unhealthy {
            warn!(
                "Node {} GPU {} is marked unhealthy, initiating model migration",
                node_address, gpu_id
            );

            self.balancer.set_gpu_health(gpu_id, false);

            let models_to_migrate = self.model_manager.models_on_gpu(gpu_id);

            if !models_to_migrate.is_empty() {
                info!(
                    "Migrating {} models from unhealthy GPU {} (node {})",
                    models_to_migrate.len(),
                    gpu_id,
                    node_address
                );
            }

            for version_id in models_to_migrate {
                match self.migrate_model(version_id, gpu_id).await {
                    Ok(new_gpu) => {
                        info!(
                            "Migrated model {} from GPU {} to GPU {}",
                            version_id, gpu_id, new_gpu
                        );
                    }
                    Err(e) => {
                        error!(
                            "Failed to migrate model {} from GPU {}: {}",
                            version_id, gpu_id, e
                        );
                    }
                }
            }
        }
    }

    async fn migrate_model(
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
                "Model {} already loaded on other GPUs, just unloading from {}",
                version_id, from_gpu
            );
            self.model_manager
                .unload_model(version_id, from_gpu, true)
                .await?;
            return Ok(other_loaded.into_iter().find(|&g| g != from_gpu).unwrap());
        }

        let new_gpu = self.balancer.select_gpu(
            info.size_mb,
            crate::types::GpuSelectionStrategy::LeastLoaded,
            Some(version_id),
        )?;

        info!(
            "Migrating model {} ({}MB): GPU {} -> GPU {}",
            version_id, info.size_mb, from_gpu, new_gpu
        );

        let _ = self
            .model_manager
            .load_model(version_id, new_gpu)
            .await?;

        self.model_manager
            .unload_model(version_id, from_gpu, true)
            .await?;

        Ok(new_gpu)
    }

    pub fn update_gpu_metrics(
        &self,
        node_address: &str,
        gpu_id: usize,
        util_percent: f64,
        memory_used_mb: u64,
    ) {
        let key: HeartbeatKey = (node_address.to_string(), gpu_id);

        if let Some(mut hb) = self.heartbeats.get_mut(&key) {
            hb.gpu_util_percent = util_percent;
            hb.gpu_memory_used_mb = memory_used_mb;
        }

        self.balancer.report_usage(gpu_id, util_percent, memory_used_mb);
    }

    pub fn get_heartbeat_info(&self, node_address: &str, gpu_id: usize) -> Option<HeartbeatInfo> {
        let key: HeartbeatKey = (node_address.to_string(), gpu_id);
        self.heartbeats.get(&key).map(|h| h.clone())
    }

    pub fn get_all_heartbeats(&self) -> Vec<HeartbeatInfo> {
        self.heartbeats.iter().map(|h| h.value().clone()).collect()
    }

    pub fn get_unhealthy_nodes(&self) -> Vec<(String, usize)> {
        self.heartbeats
            .iter()
            .filter(|h| !h.value().is_healthy)
            .map(|h| (h.key().0.clone(), h.key().1))
            .collect()
    }

    pub fn get_node_summary(&self) -> std::collections::HashMap<String, (usize, usize, f64, f64)> {
        let mut summary: std::collections::HashMap<String, (usize, usize, f64, f64)> =
            std::collections::HashMap::new();

        for hb in self.heartbeats.iter() {
            let node = hb.key().0.clone();
            let entry = summary.entry(node).or_insert((0, 0, 0.0, 0.0));
            entry.0 += 1;
            if hb.value().is_healthy {
                entry.1 += 1;
            }
            entry.2 += hb.value().qps;
            entry.3 += hb.value().avg_latency_ms;
        }

        for (_, v) in summary.iter_mut() {
            if v.0 > 0 {
                v.3 /= v.0 as f64;
            }
        }

        summary
    }

    pub fn mark_node_healthy(&self, node_address: &str, gpu_id: usize) {
        let key: HeartbeatKey = (node_address.to_string(), gpu_id);
        if let Some(mut hb) = self.heartbeats.get_mut(&key) {
            hb.is_healthy = true;
            hb.consecutive_failures = 0;
            hb.last_heartbeat = Instant::now();
        }
        self.balancer.set_gpu_health(gpu_id, true);
    }

    pub fn get_cluster_health_score(&self) -> f64 {
        let total = self.heartbeats.len();
        if total == 0 {
            return 1.0;
        }

        let healthy = self
            .heartbeats
            .iter()
            .filter(|h| h.value().is_healthy)
            .count();

        healthy as f64 / total as f64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_heartbeat_manager() -> Arc<HeartbeatManager> {
        let (tx, _rx) = broadcast::channel::<SchedulerEvent>(100);
        let runtime = Arc::new(RuntimeClient::new());
        let balancer = Arc::new(GpuLoadBalancer::new());
        balancer.add_gpu(0, 24000, Some("127.0.0.1:50051".to_string()));
        balancer.add_gpu(1, 24000, Some("127.0.0.1:50052".to_string()));

        let model_manager = Arc::new(ModelLifecycleManager::new(
            runtime.clone(),
            Arc::new(model_registry::ModelRegistryService::new(
                db::DatabasePool::mock(),
                db::RedisClient::mock(),
                model_registry::MinioStorage::mock(),
            )),
            balancer.clone(),
        ));

        let manager = HeartbeatManager::new(
            runtime,
            balancer,
            model_manager,
            tx,
            SchedulerConfig::default(),
        );

        Arc::new(manager)
    }

    #[test]
    fn test_register_node() {
        let mgr = create_test_heartbeat_manager();
        mgr.register_node("127.0.0.1:50051".to_string(), vec![0, 1]);

        let nodes = mgr.registered_nodes.clone();
        assert!(nodes.contains_key("127.0.0.1:50051"));
        assert_eq!(nodes.get("127.0.0.1:50051").unwrap(), &vec![0usize, 1]);

        let key0: HeartbeatKey = ("127.0.0.1:50051".to_string(), 0);
        let key1: HeartbeatKey = ("127.0.0.1:50051".to_string(), 1);
        assert!(mgr.heartbeats.contains_key(&key0));
        assert!(mgr.heartbeats.contains_key(&key1));
    }

    #[test]
    fn test_mark_node_healthy() {
        let mgr = create_test_heartbeat_manager();
        mgr.register_node("127.0.0.1:50051".to_string(), vec![0]);

        let key: HeartbeatKey = ("127.0.0.1:50051".to_string(), 0);
        {
            let mut hb = mgr.heartbeats.get_mut(&key).unwrap();
            hb.is_healthy = false;
            hb.consecutive_failures = 5;
        }

        mgr.mark_node_healthy("127.0.0.1:50051", 0);

        let hb = mgr.heartbeats.get(&key).unwrap();
        assert!(hb.is_healthy);
        assert_eq!(hb.consecutive_failures, 0);
    }

    #[test]
    fn test_cluster_health_score() {
        let mgr = create_test_heartbeat_manager();
        mgr.register_node("node1".to_string(), vec![0]);
        mgr.register_node("node2".to_string(), vec![1]);

        assert!((mgr.get_cluster_health_score() - 1.0).abs() < 0.001);

        let key: HeartbeatKey = ("node1".to_string(), 0);
        if let Some(mut hb) = mgr.heartbeats.get_mut(&key) {
            hb.is_healthy = false;
        }

        assert!((mgr.get_cluster_health_score() - 0.5).abs() < 0.001);
    }
}
