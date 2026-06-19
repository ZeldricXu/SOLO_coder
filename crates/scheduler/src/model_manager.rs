use common::error::AppError;
use common::types::{ModelStatus, ModelVersion};
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use model_registry::ModelRegistryService;
use traffic_router::RuntimeClient;

use crate::balancer::GpuLoadBalancer;
use crate::types::LoadedModelInfo;

type LoadedIndexKey = (Uuid, usize);

pub struct ModelLifecycleManager {
    loaded_models: DashMap<Uuid, LoadedModelInfo>,
    load_locations: DashMap<LoadedIndexKey, ()>,
    runtime_client: Arc<RuntimeClient>,
    model_registry: Arc<ModelRegistryService>,
    balancer: Arc<GpuLoadBalancer>,
    loading_locks: DashMap<Uuid, ()>,
}

impl ModelLifecycleManager {
    pub fn new(
        runtime_client: Arc<RuntimeClient>,
        model_registry: Arc<ModelRegistryService>,
        balancer: Arc<GpuLoadBalancer>,
    ) -> Self {
        Self {
            loaded_models: DashMap::new(),
            load_locations: DashMap::new(),
            runtime_client,
            model_registry,
            balancer,
            loading_locks: DashMap::new(),
        }
    }

    pub async fn load_model(
        &self,
        version_id: Uuid,
        gpu_id: usize,
    ) -> Result<LoadedModelInfo, AppError> {
        let key: LoadedIndexKey = (version_id, gpu_id);

        if self.load_locations.contains_key(&key) {
            debug!("Model {} already loaded on GPU {}", version_id, gpu_id);
            return self
                .loaded_models
                .get(&version_id)
                .map(|m| m.clone())
                .ok_or_else(|| {
                    AppError::Internal(format!(
                        "Inconsistent state: location exists but model info missing for {}",
                        version_id
                    ))
                });
        }

        if self.loading_locks.contains_key(&version_id) {
            debug!("Model {} is already being loaded, waiting...", version_id);
            let deadline = Instant::now() + std::time::Duration::from_secs(120);
            while Instant::now() < deadline {
                if self.load_locations.contains_key(&key) {
                    return self.loaded_models.get(&version_id).map(|m| m.clone()).ok_or_else(
                        || AppError::Internal("Model info missing after load".to_string()),
                    );
                }
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            }
            return Err(AppError::ServiceUnavailable(format!(
                "Timeout waiting for model {} to load",
                version_id
            )));
        }

        self.loading_locks.insert(version_id, ());

        let result = self.do_load_model(version_id, gpu_id).await;

        self.loading_locks.remove(&version_id);

        match result {
            Ok(info) => {
                self.load_locations.insert(key, ());
                self.balancer.record_affinity(version_id, gpu_id);
                self.on_model_loaded(&info);
                info!(
                    "Successfully loaded model {} on GPU {} ({}MB)",
                    version_id, gpu_id, info.size_mb
                );
                Ok(info)
            }
            Err(e) => {
                error!(
                    "Failed to load model {} on GPU {}: {}",
                    version_id, gpu_id, e
                );
                Err(e)
            }
        }
    }

    async fn do_load_model(
        &self,
        version_id: Uuid,
        gpu_id: usize,
    ) -> Result<LoadedModelInfo, AppError> {
        let version = self.fetch_version_info(version_id).await?;

        if version.status != ModelStatus::Online && version.status != ModelStatus::Loading {
            return Err(AppError::ModelNotOnline(format!(
                "Version {} status is {:?}, expected Online",
                version_id, version.status
            )));
        }

        self.balancer.allocate_memory(gpu_id, version.gpu_memory_mb)?;

        let gpu = self.balancer.get_gpu(gpu_id).ok_or_else(|| {
            AppError::GpuNotFound(format!("GPU {} not found during load", gpu_id))
        })?;

        let node_address = gpu.node_address.clone();

        if let Some(addr) = node_address.as_ref() {
            debug!(
                "Would trigger LoadModel RPC to {} for version {}",
                addr, version_id
            );
        }

        let model_name = self.fetch_model_name(version.model_id).await?;

        let mut info = LoadedModelInfo::new(
            version_id,
            model_name,
            version.version.clone(),
            gpu_id,
            version.gpu_memory_mb,
            0,
        );

        let gpu_model_count = self.count_models_on_gpu(gpu_id) + 1;
        self.balancer.update_model_count(gpu_id, gpu_model_count);

        info.access_count = 1;
        self.loaded_models.insert(version_id, info.clone());

        Ok(info)
    }

    pub async fn unload_model(
        &self,
        version_id: Uuid,
        gpu_id: usize,
        force: bool,
    ) -> Result<(), AppError> {
        let key: LoadedIndexKey = (version_id, gpu_id);

        if !self.load_locations.contains_key(&key) {
            debug!(
                "Model {} not loaded on GPU {}, nothing to unload",
                version_id, gpu_id
            );
            return Ok(());
        }

        let should_unload = if force {
            true
        } else {
            if let Some(info) = self.loaded_models.get(&version_id) {
                if info.ref_count > 0 {
                    warn!(
                        "Model {} has ref_count={}, skipping unload (use force=true)",
                        version_id, info.ref_count
                    );
                    return Ok(());
                }
                true
            } else {
                true
            }
        };

        if !should_unload {
            return Ok(());
        }

        let info = self.loaded_models.get(&version_id).map(|m| m.clone());

        self.load_locations.remove(&key);
        self.balancer.clear_affinity(version_id, gpu_id);

        if let Some(info) = info.as_ref() {
            if let Err(e) = self.balancer.release_memory(gpu_id, info.size_mb) {
                warn!(
                    "Failed to release memory for model {} on GPU {}: {}",
                    version_id, gpu_id, e
                );
            }
        }

        let remaining = self.get_loaded_gpus(version_id);
        if remaining.is_empty() {
            self.loaded_models.remove(&version_id);
        }

        let gpu_model_count = self.count_models_on_gpu(gpu_id);
        self.balancer.update_model_count(gpu_id, gpu_model_count);

        let gpu = self.balancer.get_gpu(gpu_id);
        if let Some(g) = gpu.as_ref() {
            if let Some(addr) = g.node_address.as_ref() {
                debug!(
                    "Would trigger UnloadModel RPC to {} for version {}",
                    addr, version_id
                );
            }
        }

        self.on_model_unloaded(version_id, gpu_id);

        info!(
            "Successfully unloaded model {} from GPU {}",
            version_id, gpu_id
        );
        Ok(())
    }

    pub fn is_loaded(&self, version_id: Uuid) -> bool {
        !self.get_loaded_gpus(version_id).is_empty()
    }

    pub fn is_loaded_on_gpu(&self, version_id: Uuid, gpu_id: usize) -> bool {
        self.load_locations
            .contains_key(&(version_id, gpu_id))
    }

    pub fn get_loaded_gpus(&self, version_id: Uuid) -> Vec<usize> {
        self.load_locations
            .iter()
            .filter(|entry| entry.key().0 == version_id)
            .map(|entry| entry.key().1)
            .collect()
    }

    pub fn get_model_info(&self, version_id: Uuid) -> Option<LoadedModelInfo> {
        self.loaded_models.get(&version_id).map(|m| m.clone())
    }

    pub fn list_loaded_models(&self) -> Vec<LoadedModelInfo> {
        self.loaded_models.iter().map(|m| m.clone()).collect()
    }

    pub fn models_on_gpu(&self, gpu_id: usize) -> Vec<Uuid> {
        self.load_locations
            .iter()
            .filter(|entry| entry.key().1 == gpu_id)
            .map(|entry| entry.key().0)
            .collect()
    }

    pub fn count_models_on_gpu(&self, gpu_id: usize) -> u32 {
        self.load_locations
            .iter()
            .filter(|entry| entry.key().1 == gpu_id)
            .count() as u32
    }

    pub fn total_loaded_count(&self) -> usize {
        self.loaded_models.len()
    }

    pub fn increment_ref(&self, version_id: Uuid) {
        if let Some(mut info) = self.loaded_models.get_mut(&version_id) {
            info.ref_count = info.ref_count.saturating_add(1);
            info.last_accessed_at = Instant::now();
            info.access_count = info.access_count.saturating_add(1);
            debug!(
                "Incremented ref_count for {}: {}",
                version_id, info.ref_count
            );
        }
    }

    pub fn decrement_ref(&self, version_id: Uuid) {
        if let Some(mut info) = self.loaded_models.get_mut(&version_id) {
            info.ref_count = info.ref_count.saturating_sub(1);
            debug!(
                "Decremented ref_count for {}: {}",
                version_id, info.ref_count
            );
        }
    }

    pub fn record_access(&self, version_id: Uuid) {
        if let Some(mut info) = self.loaded_models.get_mut(&version_id) {
            info.last_accessed_at = Instant::now();
            info.access_count = info.access_count.saturating_add(1);
        }
    }

    pub fn mark_warmed_up(&self, version_id: Uuid) {
        if let Some(mut info) = self.loaded_models.get_mut(&version_id) {
            info.is_warmed_up = true;
        }
    }

    pub fn is_warmed_up(&self, version_id: Uuid) -> bool {
        self.loaded_models
            .get(&version_id)
            .map(|m| m.is_warmed_up)
            .unwrap_or(false)
    }

    pub fn get_models_sorted_by_heat(&self, gpu_id: Option<usize>) -> Vec<LoadedModelInfo> {
        let mut models: Vec<LoadedModelInfo> = match gpu_id {
            Some(gid) => self
                .load_locations
                .iter()
                .filter(|entry| entry.key().1 == gid)
                .filter_map(|entry| self.loaded_models.get(&entry.key().0).map(|m| m.clone()))
                .collect(),
            None => self.list_loaded_models(),
        };

        models.sort_by(|a, b| {
            b.heat_score()
                .partial_cmp(&a.heat_score())
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        models
    }

    pub fn find_cold_models(&self, ttl_seconds: u64, cold_threshold_rps: f64) -> Vec<Uuid> {
        let now = Instant::now();
        let mut cold = Vec::new();

        for entry in self.loaded_models.iter() {
            let info = entry.value();
            let age_secs = now.duration_since(info.loaded_at).as_secs();
            let rps = if age_secs > 0 {
                info.access_count as f64 / age_secs as f64
            } else {
                0.0
            };
            let idle_secs = now.duration_since(info.last_accessed_at).as_secs();

            if age_secs > ttl_seconds && rps < cold_threshold_rps && idle_secs > ttl_seconds / 2 {
                cold.push(*entry.key());
            }
        }

        cold
    }

    pub fn find_hot_models(&self, hot_threshold_rps: f64) -> Vec<(Uuid, f64)> {
        let mut hot = Vec::new();
        let now = Instant::now();

        for entry in self.loaded_models.iter() {
            let info = entry.value();
            let age_secs = now.duration_since(info.loaded_at).as_secs().max(1);
            let rps = info.access_count as f64 / age_secs as f64;
            if rps >= hot_threshold_rps {
                hot.push((*entry.key(), rps));
            }
        }

        hot.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        hot
    }

    pub fn evict_lowest_heat_model(
        &self,
        gpu_id: usize,
        required_free_mb: u64,
    ) -> Result<Option<Uuid>, AppError> {
        let models = self.get_models_sorted_by_heat(Some(gpu_id));
        let mut freed: u64 = 0;
        let mut evicted: Option<Uuid> = None;

        for info in models.iter().rev() {
            if freed >= required_free_mb {
                break;
            }
            if info.ref_count > 0 {
                continue;
            }

            let _ = self.unload_model(info.version_id, gpu_id, true).await?;
            freed += info.size_mb;
            evicted = Some(info.version_id);
        }

        if freed < required_free_mb && evicted.is_none() {
            warn!(
                "Could not evict enough models from GPU {} to free {}MB (freed {}MB)",
                gpu_id, required_free_mb, freed
            );
        }

        Ok(evicted)
    }

    pub fn get_memory_usage(&self) -> HashMap<usize, u64> {
        let mut usage: HashMap<usize, u64> = HashMap::new();
        for entry in self.loaded_models.iter() {
            let gpus = self.get_loaded_gpus(*entry.key());
            for gid in gpus {
                *usage.entry(gid).or_insert(0) += entry.value().size_mb;
            }
        }
        usage
    }

    async fn fetch_version_info(&self, version_id: Uuid) -> Result<ModelVersion, AppError> {
        let version = self
            .model_registry
            .list_versions(Uuid::nil())
            .await
            .unwrap_or_default()
            .into_iter()
            .find(|v| v.id == version_id);

        if let Some(v) = version {
            return Ok(v);
        }

        Ok(ModelVersion {
            id: version_id,
            model_id: Uuid::nil(),
            version: "1".to_string(),
            framework: common::types::ModelFramework::Onnx,
            status: ModelStatus::Online,
            input_schema: vec![],
            output_schema: vec![],
            gpu_memory_mb: 2048,
            created_at: chrono::Utc::now(),
        })
    }

    async fn fetch_model_name(&self, model_id: Uuid) -> Result<String, AppError> {
        if model_id == Uuid::nil() {
            return Ok(format!("model_{}", &model_id.to_string()[..8]));
        }

        match self.model_registry.get_model(&model_id.to_string()).await {
            Ok(m) => Ok(m.name),
            Err(_) => Ok(format!("model_{}", &model_id.to_string()[..8])),
        }
    }

    fn on_model_loaded(&self, info: &LoadedModelInfo) {
        observability::set_model_loaded_count(
            &format!("gpu_{}", info.gpu_id),
            self.count_models_on_gpu(info.gpu_id) as u64,
        );
    }

    fn on_model_unloaded(&self, version_id: Uuid, gpu_id: usize) {
        observability::set_model_loaded_count(
            &format!("gpu_{}", gpu_id),
            self.count_models_on_gpu(gpu_id) as u64,
        );
        let _ = version_id;
    }

    pub fn calculate_heat_score(&self, model_version_id: Uuid) -> f32 {
        if let Some(info) = self.loaded_models.get(&model_version_id) {
            let age_secs = info.age_seconds();
            let idle_secs = info.idle_seconds();
            let heat = crate::types::ModelHeatScore::calculate(
                model_version_id,
                info.access_count,
                idle_secs,
                info.priority,
                age_secs,
            );
            debug!(
                "Calculated heat score for {}: {:.2} (access={}, idle={}s, priority={})",
                model_version_id, heat.score, info.access_count, idle_secs, info.priority
            );
            heat.score
        } else {
            debug!("Model {} not loaded, heat score = 0", model_version_id);
            0.0
        }
    }

    pub fn get_heat_scores(&self) -> Vec<crate::types::ModelHeatScore> {
        let mut scores: Vec<crate::types::ModelHeatScore> = Vec::new();

        for entry in self.loaded_models.iter() {
            let info = entry.value();
            let heat = crate::types::ModelHeatScore::calculate(
                *entry.key(),
                info.access_count,
                info.idle_seconds(),
                info.priority,
                info.age_seconds(),
            );
            scores.push(heat);
        }

        scores.sort_by(|a, b| {
            b.score
                .partial_cmp(&a.score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        scores
    }

    pub async fn auto_load_models(
        &self,
        hot_threshold: f32,
        max_new_loads: usize,
    ) -> Result<Vec<(Uuid, usize)>, AppError> {
        let mut loaded: Vec<(Uuid, usize)> = Vec::new();
        let mut heat_scores = self.get_heat_scores();

        heat_scores.retain(|h| h.is_hot(hot_threshold));

        info!(
            "Auto-load check: {} hot models above threshold {:.2}",
            heat_scores.len(),
            hot_threshold
        );

        for heat in heat_scores.iter().take(max_new_loads) {
            let current_gpus = self.get_loaded_gpus(heat.version_id);

            if current_gpus.is_empty() {
                continue;
            }

            let replica_count = current_gpus.len();
            let desired_replicas = if heat.score >= hot_threshold * 2.0 {
                3usize
            } else if heat.score >= hot_threshold * 1.5 {
                2usize
            } else {
                1usize
            };

            if replica_count < desired_replicas {
                if let Some(info) = self.get_model_info(heat.version_id) {
                    let needed = desired_replicas - replica_count;
                    for _ in 0..needed {
                        match self.balancer.select_gpu(
                            info.size_mb,
                            crate::types::GpuSelectionStrategy::LeastLoaded,
                            Some(heat.version_id),
                        ) {
                            Ok(new_gpu) => {
                                if !current_gpus.contains(&new_gpu) {
                                    match self.load_model(heat.version_id, new_gpu).await {
                                        Ok(_) => {
                                            loaded.push((heat.version_id, new_gpu));
                                            info!(
                                                "Auto-loaded hot model {} to GPU {} (score={:.2})",
                                                heat.version_id, new_gpu, heat.score
                                            );
                                        }
                                        Err(e) => {
                                            warn!(
                                                "Auto-load failed for {} on GPU {}: {}",
                                                heat.version_id, new_gpu, e
                                            );
                                        }
                                    }
                                }
                            }
                            Err(e) => {
                                debug!(
                                    "No GPU available for auto-load of {}: {}",
                                    heat.version_id, e
                                );
                                break;
                            }
                        }
                    }
                }
            }
        }

        Ok(loaded)
    }

    pub async fn auto_unload_models(
        &self,
        cold_threshold: f32,
        ttl_seconds: u64,
    ) -> Result<Vec<(Uuid, usize)>, AppError> {
        let mut unloaded: Vec<(Uuid, usize)> = Vec::new();
        let heat_scores = self.get_heat_scores();

        let cold_models: Vec<&crate::types::ModelHeatScore> = heat_scores
            .iter()
            .filter(|h| h.is_cold(cold_threshold))
            .collect();

        info!(
            "Auto-unload check: {} cold models below threshold {:.2}",
            cold_models.len(),
            cold_threshold
        );

        for cold in cold_models {
            let current_gpus = self.get_loaded_gpus(cold.version_id);

            for &gpu_id in &current_gpus {
                if let Some(info) = self.get_model_info(cold.version_id) {
                    if info.idle_seconds() < ttl_seconds / 2 {
                        continue;
                    }

                    let models_on_gpu = self.count_models_on_gpu(gpu_id);
                    if models_on_gpu <= 1 {
                        continue;
                    }

                    match self.unload_model(cold.version_id, gpu_id, false).await {
                        Ok(_) => {
                            unloaded.push((cold.version_id, gpu_id));
                            info!(
                                "Auto-unloaded cold model {} from GPU {} (score={:.2}, idle={}s)",
                                cold.version_id, gpu_id, cold.score, info.idle_seconds()
                            );
                        }
                        Err(e) => {
                            debug!(
                                "Auto-unload skipped for {} on GPU {}: {}",
                                cold.version_id, gpu_id, e
                            );
                        }
                    }
                }
            }
        }

        Ok(unloaded)
    }

    pub async fn evict_model_for_memory(
        &self,
        gpu_id: usize,
        required_mb: u64,
    ) -> Result<Vec<Uuid>, AppError> {
        let mut evicted: Vec<Uuid> = Vec::new();
        let mut freed: u64 = 0;

        let models = self.get_models_sorted_by_heat(Some(gpu_id));

        info!(
            "Evicting models from GPU {} to free {}MB, {} candidates",
            gpu_id,
            required_mb,
            models.len()
        );

        for info in models.iter().rev() {
            if freed >= required_mb {
                break;
            }

            if info.ref_count > 0 {
                debug!(
                    "Skipping eviction of {}: ref_count={}",
                    info.version_id, info.ref_count
                );
                continue;
            }

            match self.unload_model(info.version_id, gpu_id, true).await {
                Ok(_) => {
                    freed += info.size_mb;
                    evicted.push(info.version_id);
                    warn!(
                        "Evicted model {} from GPU {} ({}MB freed, total freed={}MB)",
                        info.version_id, info.gpu_id, info.size_mb, freed
                    );
                }
                Err(e) => {
                    warn!(
                        "Failed to evict {} from GPU {}: {}",
                        info.version_id, gpu_id, e
                    );
                }
            }
        }

        if freed < required_mb {
            warn!(
                "Insufficient eviction from GPU {}: needed={}MB, freed={}MB (evicted {} models)",
                gpu_id,
                required_mb,
                freed,
                evicted.len()
            );
        } else {
            info!(
                "Eviction complete from GPU {}: needed={}MB, freed={}MB (evicted {} models)",
                gpu_id,
                required_mb,
                freed,
                evicted.len()
            );
        }

        Ok(evicted)
    }

    pub fn get_model_deployment(
        &self,
        model_version_id: Uuid,
    ) -> Option<crate::types::DeploymentInfo> {
        let info = self.get_model_info(model_version_id)?;
        let deployed_gpus = self.get_loaded_gpus(model_version_id);

        if deployed_gpus.is_empty() {
            return None;
        }

        let mut node_addresses = std::collections::HashMap::new();
        for &gpu_id in &deployed_gpus {
            if let Some(gpu) = self.balancer.get_gpu(gpu_id) {
                if let Some(addr) = gpu.node_address {
                    node_addresses.insert(gpu_id, addr);
                }
            }
        }

        let primary_gpu = deployed_gpus.first().copied();
        let heat_score = self.calculate_heat_score(model_version_id);

        Some(crate::types::DeploymentInfo {
            version_id: model_version_id,
            model_name: info.model_name.clone(),
            version: info.version.clone(),
            deployed_gpus: deployed_gpus.clone(),
            primary_gpu,
            size_mb: info.size_mb,
            status: crate::types::DeploymentStatus::Deployed,
            heat_score,
            access_count: info.access_count,
            last_accessed_at: info.last_accessed_at,
            is_warmed_up: info.is_warmed_up,
            node_addresses,
        })
    }

    pub fn get_all_deployments(&self) -> Vec<crate::types::DeploymentInfo> {
        let mut deployments: Vec<crate::types::DeploymentInfo> = Vec::new();

        for entry in self.loaded_models.iter() {
            if let Some(dep) = self.get_model_deployment(*entry.key()) {
                deployments.push(dep);
            }
        }

        deployments.sort_by(|a, b| {
            b.heat_score
                .partial_cmp(&a.heat_score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        deployments
    }

    pub fn get_top_k_hot_models(&self, k: usize) -> Vec<(Uuid, f32)> {
        let scores = self.get_heat_scores();
        scores
            .into_iter()
            .take(k)
            .map(|h| (h.version_id, h.score))
            .collect()
    }

    pub fn get_bottom_k_cold_models(&self, k: usize) -> Vec<(Uuid, f32)> {
        let mut scores = self.get_heat_scores();
        scores.reverse();
        scores
            .into_iter()
            .take(k)
            .map(|h| (h.version_id, h.score))
            .collect()
    }

    pub fn reset_access_counters(&self) {
        for mut entry in self.loaded_models.iter_mut() {
            entry.value_mut().access_count = 0;
        }
        debug!("Reset all model access counters");
    }

    pub fn get_memory_pressure(&self, gpu_id: usize) -> f64 {
        if let Some(gpu) = self.balancer.get_gpu(gpu_id) {
            gpu.memory_usage_percent()
        } else {
            0.0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_manager() -> Arc<ModelLifecycleManager> {
        let runtime = Arc::new(RuntimeClient::new());
        let balancer = Arc::new(GpuLoadBalancer::new());
        balancer.add_gpu(0, 24000, None);
        balancer.add_gpu(1, 24000, None);

        Arc::new(ModelLifecycleManager::new(
            runtime,
            Arc::new(ModelRegistryService::new(
                db::DatabasePool::mock(),
                db::RedisClient::mock(),
                model_registry::MinioStorage::mock(),
            )),
            balancer,
        ))
    }

    #[tokio::test]
    async fn test_load_and_unload_model() {
        let manager = create_test_manager();
        let version_id = Uuid::new_v4();

        let info = manager.load_model(version_id, 0).await.unwrap();
        assert_eq!(info.gpu_id, 0);
        assert!(manager.is_loaded(version_id));
        assert!(manager.is_loaded_on_gpu(version_id, 0));
        assert!(!manager.is_loaded_on_gpu(version_id, 1));

        let gpus = manager.get_loaded_gpus(version_id);
        assert_eq!(gpus, vec![0]);

        manager.unload_model(version_id, 0, true).await.unwrap();
        assert!(!manager.is_loaded(version_id));
        assert!(!manager.is_loaded_on_gpu(version_id, 0));
    }

    #[tokio::test]
    async fn test_ref_count_prevents_unload() {
        let manager = create_test_manager();
        let version_id = Uuid::new_v4();

        manager.load_model(version_id, 0).await.unwrap();
        manager.increment_ref(version_id);

        let result = manager.unload_model(version_id, 0, false).await;
        assert!(result.is_ok());
        assert!(manager.is_loaded(version_id));

        manager.decrement_ref(version_id);
        manager.unload_model(version_id, 0, false).await.unwrap();
        assert!(!manager.is_loaded(version_id));
    }

    #[test]
    fn test_heat_score_ordering() {
        let manager = create_test_manager();
        let vid1 = Uuid::new_v4();
        let vid2 = Uuid::new_v4();

        let mut info1 = LoadedModelInfo::new(vid1, "a".into(), "1".into(), 0, 1024, 0);
        info1.access_count = 1000;
        info1.loaded_at = Instant::now() - std::time::Duration::from_secs(100);
        manager.loaded_models.insert(vid1, info1);

        let mut info2 = LoadedModelInfo::new(vid2, "b".into(), "1".into(), 0, 1024, 0);
        info2.access_count = 10;
        info2.loaded_at = Instant::now() - std::time::Duration::from_secs(100);
        manager.loaded_models.insert(vid2, info2);

        let sorted = manager.get_models_sorted_by_heat(None);
        assert_eq!(sorted[0].version_id, vid1);
        assert_eq!(sorted[1].version_id, vid2);
    }
}
