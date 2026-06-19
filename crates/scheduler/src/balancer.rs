use common::error::AppError;
use dashmap::DashMap;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use tracing::{debug, info, warn};
use uuid::Uuid;

use crate::types::{GpuLoad, GpuSelectionStrategy};

pub struct GpuLoadBalancer {
    gpus: DashMap<usize, GpuLoad>,
    round_robin_counter: AtomicUsize,
    model_affinity: DashMap<Uuid, Vec<usize>>,
    last_update: RwLock<std::time::Instant>,
}

impl GpuLoadBalancer {
    pub fn new() -> Self {
        Self {
            gpus: DashMap::new(),
            round_robin_counter: AtomicUsize::new(0),
            model_affinity: DashMap::new(),
            last_update: RwLock::new(std::time::Instant::now()),
        }
    }

    pub fn add_gpu(&self, gpu_id: usize, total_mb: u64, node_address: Option<String>) {
        let mut gpu = GpuLoad::new(gpu_id, total_mb);
        gpu.node_address = node_address;
        gpu.score = Self::compute_load_score(&gpu);
        info!("Added GPU {} with {}MB total memory", gpu_id, total_mb);
        self.gpus.insert(gpu_id, gpu);
        *self.last_update.write() = std::time::Instant::now();
    }

    pub fn remove_gpu(&self, gpu_id: usize) {
        if self.gpus.remove(&gpu_id).is_some() {
            info!("Removed GPU {}", gpu_id);
            *self.last_update.write() = std::time::Instant::now();
        }
    }

    pub fn get_gpu(&self, gpu_id: usize) -> Option<GpuLoad> {
        self.gpus.get(&gpu_id).map(|g| g.clone())
    }

    pub fn list_gpus(&self) -> Vec<GpuLoad> {
        self.gpus.iter().map(|g| g.clone()).collect()
    }

    pub fn healthy_gpus(&self) -> Vec<GpuLoad> {
        self.gpus
            .iter()
            .filter(|g| g.is_healthy)
            .map(|g| g.clone())
            .collect()
    }

    pub fn gpu_count(&self) -> usize {
        self.gpus.len()
    }

    pub fn set_gpu_health(&self, gpu_id: usize, healthy: bool) {
        if let Some(mut gpu) = self.gpus.get_mut(&gpu_id) {
            gpu.is_healthy = healthy;
            gpu.score = Self::compute_load_score(&gpu);
            debug!("GPU {} health set to {}", gpu_id, healthy);
        }
    }

    pub fn compute_load_score(gpu: &GpuLoad) -> f64 {
        if !gpu.is_healthy {
            return f64::NEG_INFINITY;
        }

        let util_score = 1.0 - (gpu.util_percent / 100.0);
        let memory_ratio = if gpu.total_mb > 0 {
            gpu.free_mb as f64 / gpu.total_mb as f64
        } else {
            0.0
        };
        let memory_score = memory_ratio;
        let model_penalty = if gpu.model_count > 0 {
            (gpu.model_count as f64) * 0.02
        } else {
            0.0
        };

        (util_score * 0.4 + memory_score * 0.6 - model_penalty).max(0.0)
    }

    pub fn report_usage(&self, gpu_id: usize, util_percent: f64, memory_used_mb: u64) {
        if let Some(mut gpu) = self.gpus.get_mut(&gpu_id) {
            gpu.util_percent = util_percent.clamp(0.0, 100.0);
            gpu.free_mb = gpu.total_mb.saturating_sub(memory_used_mb);
            gpu.score = Self::compute_load_score(&gpu);
            *self.last_update.write() = std::time::Instant::now();
            debug!(
                "Updated GPU {}: util={:.1}%, free={}MB, score={:.3}",
                gpu_id, gpu.util_percent, gpu.free_mb, gpu.score
            );
        }
    }

    pub fn update_model_count(&self, gpu_id: usize, model_count: u32) {
        if let Some(mut gpu) = self.gpus.get_mut(&gpu_id) {
            gpu.model_count = model_count;
            gpu.score = Self::compute_load_score(&gpu);
        }
    }

    pub fn allocate_memory(&self, gpu_id: usize, size_mb: u64) -> Result<(), AppError> {
        let mut gpu = self
            .gpus
            .get_mut(&gpu_id)
            .ok_or_else(|| AppError::GpuNotFound(gpu_id.to_string()))?;

        if gpu.free_mb < size_mb {
            return Err(AppError::InsufficientGpuMemory(size_mb, gpu.free_mb));
        }

        gpu.free_mb -= size_mb;
        gpu.score = Self::compute_load_score(&gpu);
        Ok(())
    }

    pub fn release_memory(&self, gpu_id: usize, size_mb: u64) -> Result<(), AppError> {
        let mut gpu = self
            .gpus
            .get_mut(&gpu_id)
            .ok_or_else(|| AppError::GpuNotFound(gpu_id.to_string()))?;

        gpu.free_mb = (gpu.free_mb + size_mb).min(gpu.total_mb);
        gpu.score = Self::compute_load_score(&gpu);
        Ok(())
    }

    pub fn record_affinity(&self, version_id: Uuid, gpu_id: usize) {
        let mut affinities = self.model_affinity.entry(version_id).or_default();
        if !affinities.contains(&gpu_id) {
            affinities.push(gpu_id);
        }
    }

    pub fn clear_affinity(&self, version_id: Uuid, gpu_id: usize) {
        if let Some(mut affinities) = self.model_affinity.get_mut(&version_id) {
            affinities.retain(|g| *g != gpu_id);
            if affinities.is_empty() {
                drop(affinities);
                self.model_affinity.remove(&version_id);
            }
        }
    }

    pub fn get_affinity_gpus(&self, version_id: Uuid) -> Vec<usize> {
        self.model_affinity
            .get(&version_id)
            .map(|g| g.clone())
            .unwrap_or_default()
    }

    pub fn select_gpu(
        &self,
        required_memory_mb: u64,
        strategy: GpuSelectionStrategy,
        affinity_version_id: Option<Uuid>,
    ) -> Result<usize, AppError> {
        let candidates: Vec<GpuLoad> = self
            .gpus
            .iter()
            .filter(|g| g.can_fit(required_memory_mb))
            .map(|g| g.clone())
            .collect();

        if candidates.is_empty() {
            let available = self
                .gpus
                .iter()
                .map(|g| format!("GPU{}: {}MB free", g.gpu_id, g.free_mb))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(AppError::InsufficientGpuMemory(
                required_memory_mb,
                self.gpus.iter().map(|g| g.free_mb).max().unwrap_or(0),
            ));
        }

        let selected = match strategy {
            GpuSelectionStrategy::LeastLoaded => Self::select_least_loaded(&candidates),
            GpuSelectionStrategy::LeastMemory => Self::select_least_memory(&candidates),
            GpuSelectionStrategy::RoundRobin => {
                Self::select_round_robin(&candidates, &self.round_robin_counter)
            }
            GpuSelectionStrategy::Affinity => Self::select_affinity(
                &candidates,
                affinity_version_id,
                &self.model_affinity,
                &self.round_robin_counter,
            ),
        };

        debug!(
            "Selected GPU {} via {:?} strategy for {}MB request",
            selected, strategy, required_memory_mb
        );

        Ok(selected)
    }

    fn select_least_loaded(candidates: &[GpuLoad]) -> usize {
        candidates
            .iter()
            .min_by(|a, b| {
                a.util_percent
                    .partial_cmp(&b.util_percent)
                    .unwrap_or(std::cmp::Ordering::Equal)
                    .then_with(|| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal).reverse())
            })
            .map(|g| g.gpu_id)
            .unwrap_or(candidates[0].gpu_id)
    }

    fn select_least_memory(candidates: &[GpuLoad]) -> usize {
        candidates
            .iter()
            .max_by(|a, b| {
                a.free_mb
                    .cmp(&b.free_mb)
                    .then_with(|| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal))
            })
            .map(|g| g.gpu_id)
            .unwrap_or(candidates[0].gpu_id)
    }

    fn select_round_robin(candidates: &[GpuLoad], counter: &AtomicUsize) -> usize {
        let idx = counter.fetch_add(1, Ordering::SeqCst) % candidates.len();
        candidates[idx].gpu_id]
    }

    fn select_affinity(
        candidates: &[GpuLoad],
        version_id: Option<Uuid>,
        affinity_map: &DashMap<Uuid, Vec<usize>>,
        counter: &AtomicUsize,
    ) -> usize {
        if let Some(vid) = version_id {
            if let Some(affinities) = affinity_map.get(&vid) {
                let affinity_candidates: Vec<&GpuLoad> = candidates
                    .iter()
                    .filter(|g| affinities.contains(&g.gpu_id))
                    .collect();

                if !affinity_candidates.is_empty() {
                    return affinity_candidates
                        .iter()
                        .max_by(|a, b| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal))
                        .map(|g| g.gpu_id)
                        .unwrap_or(affinity_candidates[0].gpu_id);
                }
            }
        }

        Self::select_round_robin(candidates, counter)
    }

    pub fn find_gpus_for_eviction(&self, required_free_mb: u64) -> Vec<usize> {
        self.gpus
            .iter()
            .filter(|g| g.is_healthy && g.total_mb.saturating_sub(g.free_mb) >= required_free_mb)
            .map(|g| g.gpu_id)
            .collect()
    }

    pub fn total_memory_mb(&self) -> u64 {
        self.gpus.iter().map(|g| g.total_mb).sum()
    }

    pub fn total_free_memory_mb(&self) -> u64 {
        self.gpus.iter().map(|g| g.free_mb).sum()
    }

    pub fn avg_utilization(&self) -> f64 {
        let healthy: Vec<&GpuLoad> = self.gpus.iter().filter(|g| g.is_healthy).collect();
        if healthy.is_empty() {
            return 0.0;
        }
        healthy.iter().map(|g| g.util_percent).sum::<f64>() / healthy.len() as f64
    }

    pub fn get_load_summary(&self) -> HashMap<String, f64> {
        let mut summary = HashMap::new();
        summary.insert("gpu_count".to_string(), self.gpu_count() as f64);
        summary.insert("healthy_gpu_count".to_string(), self.healthy_gpus().len() as f64);
        summary.insert("total_memory_gb".to_string(), self.total_memory_mb() as f64 / 1024.0);
        summary.insert(
            "total_free_memory_gb".to_string(),
            self.total_free_memory_mb() as f64 / 1024.0,
        );
        summary.insert("avg_utilization".to_string(), self.avg_utilization());
        summary.insert(
            "memory_usage_percent".to_string(),
            if self.total_memory_mb() > 0 {
                (1.0 - self.total_free_memory_mb() as f64 / self.total_memory_mb() as f64) * 100.0
            } else {
                0.0
            },
        );
        summary
    }

    pub fn get_sorted_by_score(&self, required_memory_mb: u64) -> Vec<GpuLoad> {
        let mut eligible: Vec<GpuLoad> = self
            .gpus
            .iter()
            .filter(|g| g.can_fit(required_memory_mb))
            .map(|g| g.clone())
            .collect();

        eligible.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        eligible
    }

    pub fn get_oom_risk_gpus(&self, safety_margin_mb: u64) -> Vec<usize> {
        self.gpus
            .iter()
            .filter(|g| g.is_healthy && g.free_mb < safety_margin_mb)
            .map(|g| g.gpu_id)
            .collect()
    }
}

impl Default for GpuLoadBalancer {
    fn default() -> Self {
        Self::new()
    }
}

impl GpuLoadBalancer {
    pub fn least_loaded_gpu(&self, required_mb: u64) -> Option<common::types::GpuDevice> {
        let candidates: Vec<GpuLoad> = self
            .gpus
            .iter()
            .filter(|g| g.can_fit(required_mb))
            .map(|g| g.clone())
            .collect();

        if candidates.is_empty() {
            warn!("No GPU available for {}MB request", required_mb);
            return None;
        }

        let best = candidates
            .iter()
            .min_by(|a, b| {
                let score_a = a.util_percent * 0.5 + a.memory_usage_percent() * 0.5;
                let score_b = b.util_percent * 0.5 + b.memory_usage_percent() * 0.5;
                score_a
                    .partial_cmp(&score_b)
                    .unwrap_or(std::cmp::Ordering::Equal)
                    .then_with(|| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal).reverse())
            })
            .cloned()
            .unwrap();

        debug!(
            "Least loaded GPU selected: {} (util={:.1}%, mem={:.1}%, free={}MB)",
            best.gpu_id,
            best.util_percent,
            best.memory_usage_percent(),
            best.free_mb
        );

        Some(common::types::GpuDevice {
            id: best.gpu_id.to_string(),
            uuid: format!("gpu-{}", best.gpu_id),
            name: format!("GPU-{}", best.gpu_id),
            total_memory_mb: best.total_mb,
            used_memory_mb: best.used_mb(),
            utilization_percent: best.util_percent as f32,
            temperature: 0.0,
        })
    }

    pub fn weighted_round_robin(&self, required_mb: u64) -> Option<usize> {
        let eligible: Vec<GpuLoad> = self
            .gpus
            .iter()
            .filter(|g| g.can_fit(required_mb))
            .map(|g| g.clone())
            .collect();

        if eligible.is_empty() {
            return None;
        }

        let total_weight: f64 = eligible
            .iter()
            .map(|g| {
                let mem_weight = if g.total_mb > 0 {
                    g.free_mb as f64 / g.total_mb as f64
                } else {
                    0.0
                };
                let util_weight = 1.0 - (g.util_percent / 100.0).min(1.0);
                (mem_weight * 0.6 + util_weight * 0.4).max(0.01)
            })
            .sum();

        let mut r = if total_weight > 0.0 {
            let raw = self.round_robin_counter.fetch_add(1, Ordering::SeqCst) as f64;
            (raw % total_weight) + (raw * 0.618033988749895 % 1.0) * total_weight * 0.1
        } else {
            0.0
        };

        for gpu in &eligible {
            let mem_weight = if gpu.total_mb > 0 {
                gpu.free_mb as f64 / gpu.total_mb as f64
            } else {
                0.0
            };
            let util_weight = 1.0 - (gpu.util_percent / 100.0).min(1.0);
            let weight = (mem_weight * 0.6 + util_weight * 0.4).max(0.01);

            if r < weight || total_weight == 0.0 {
                debug!(
                    "Weighted round-robin selected GPU {} (weight={:.3})",
                    gpu.gpu_id, weight
                );
                return Some(gpu.gpu_id);
            }
            r -= weight;
        }

        Some(eligible[0].gpu_id)
    }

    pub fn bin_packing(
        &self,
        models_to_place: Vec<crate::types::BinPackingItem>,
        _gpus: Option<Vec<usize>>,
    ) -> crate::types::BinPackingResult {
        use crate::types::{BinPackingItem, BinPackingPlacement, BinPackingResult};
        use std::collections::HashMap;

        let mut items: Vec<BinPackingItem> = models_to_place;
        items.sort_by(|a, b| {
            b.size_mb
                .cmp(&a.size_mb)
                .then_with(|| b.priority.cmp(&a.priority))
        });

        let mut gpu_states: HashMap<usize, u64> = HashMap::new();
        for gpu in self.gpus.iter() {
            if gpu.is_healthy {
                gpu_states.insert(gpu.gpu_id, gpu.free_mb);
            }
        }

        let mut placements: Vec<BinPackingPlacement> = Vec::new();
        let mut unplaced: Vec<BinPackingItem> = Vec::new();

        for item in items {
            let mut best_gpu: Option<usize> = None;
            let mut best_remaining: u64 = u64::MAX;

            for (&gpu_id, &remaining) in &gpu_states {
                if remaining >= item.size_mb {
                    let leftover = remaining - item.size_mb;
                    if leftover < best_remaining {
                        best_remaining = leftover;
                        best_gpu = Some(gpu_id);
                    }
                }
            }

            if let Some(gpu_id) = best_gpu {
                if let Some(r) = gpu_states.get_mut(&gpu_id) {
                    *r -= item.size_mb;
                }
                placements.push(BinPackingPlacement {
                    version_id: item.version_id,
                    gpu_id,
                });
                debug!(
                    "Bin-packed model {} to GPU {} (size={}MB)",
                    item.version_id, gpu_id, item.size_mb
                );
            } else {
                warn!(
                    "Could not place model {} (size={}MB), no GPU fits",
                    item.version_id, item.size_mb
                );
                unplaced.push(item);
            }
        }

        let mut gpu_utilization: HashMap<usize, f64> = HashMap::new();
        for (&gpu_id, &remaining) in &gpu_states {
            if let Some(gpu) = self.gpus.get(&gpu_id) {
                let used = gpu.total_mb.saturating_sub(remaining);
                let util = if gpu.total_mb > 0 {
                    (used as f64 / gpu.total_mb as f64) * 100.0
                } else {
                    0.0
                };
                gpu_utilization.insert(gpu_id, util);
            }
        }

        info!(
            "Bin-packing complete: placed={}, unplaced={}",
            placements.len(),
            unplaced.len()
        );

        BinPackingResult {
            placements,
            unplaced,
            gpu_utilization,
        }
    }

    pub fn rebalance(&self, threshold: Option<f64>) -> Vec<crate::types::SchedulingDecision> {
        use crate::types::{SchedulingDecision, SchedulingDecisionDetail, SchedulingDecisionType};

        let imbalance_threshold = threshold.unwrap_or(30.0);
        let mut decisions = SchedulingDecision::new();

        let healthy: Vec<GpuLoad> = self.healthy_gpus();
        if healthy.len() < 2 {
            debug!("Not enough healthy GPUs for rebalancing");
            return vec![decisions];
        }

        let total_memory: u64 = healthy.iter().map(|g| g.total_mb).sum();
        let total_used: u64 = healthy.iter().map(|g| g.used_mb()).sum();
        let avg_util = if total_memory > 0 {
            (total_used as f64 / total_memory as f64) * 100.0
        } else {
            0.0
        };

        let mut overloaded: Vec<GpuLoad> = Vec::new();
        let mut underloaded: Vec<GpuLoad> = Vec::new();

        for gpu in &healthy {
            let mem_util = gpu.memory_usage_percent();
            let deviation = (mem_util - avg_util).abs();

            if deviation > imbalance_threshold {
                if mem_util > avg_util {
                    overloaded.push(gpu.clone());
                } else {
                    underloaded.push(gpu.clone());
                }
            }
        }

        overloaded.sort_by(|a, b| {
            b.memory_usage_percent()
                .partial_cmp(&a.memory_usage_percent())
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        underloaded.sort_by(|a, b| {
            a.memory_usage_percent()
                .partial_cmp(&b.memory_usage_percent())
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        info!(
            "Rebalance check: avg_util={:.1}%, overloaded={}, underloaded={}",
            avg_util,
            overloaded.len(),
            underloaded.len()
        );

        for ov in &overloaded {
            let target_util = avg_util;
            let excess_percent = (ov.memory_usage_percent() - target_util).max(0.0);
            let excess_mb = ((excess_percent / 100.0) * ov.total_mb as f64) as u64;

            if excess_mb < 512 {
                continue;
            }

            debug!(
                "GPU {} overloaded by {:.1}% ({}MB excess), need to offload",
                ov.gpu_id, excess_percent, excess_mb
            );

            let detail = SchedulingDecisionDetail {
                decision_type: SchedulingDecisionType::Migrate,
                version_id: Uuid::nil(),
                from_gpu: Some(ov.gpu_id),
                to_gpu: None,
                reason: format!("GPU {} overloaded by {:.1}%", ov.gpu_id, excess_percent),
                priority: 90,
                created_at: std::time::Instant::now(),
            };
            decisions.details.push(detail);
        }

        vec![decisions]
    }

    pub fn detect_overloaded_gpus(
        &self,
        config: &crate::types::SchedulerConfig,
    ) -> Vec<common::types::GpuDevice> {
        let mut result: Vec<common::types::GpuDevice> = Vec::new();

        for gpu in self.gpus.iter() {
            if !gpu.is_healthy {
                continue;
            }

            let mem_util = gpu.memory_usage_percent();
            let is_overloaded = gpu.util_percent >= config.overload_util_threshold
                || mem_util >= config.overload_memory_threshold;

            if is_overloaded {
                warn!(
                    "Detected overloaded GPU {}: util={:.1}%, mem={:.1}%",
                    gpu.gpu_id, gpu.util_percent, mem_util
                );
                result.push(common::types::GpuDevice {
                    id: gpu.gpu_id.to_string(),
                    uuid: format!("gpu-{}", gpu.gpu_id),
                    name: format!("GPU-{}", gpu.gpu_id),
                    total_memory_mb: gpu.total_mb,
                    used_memory_mb: gpu.used_mb(),
                    utilization_percent: gpu.util_percent as f32,
                    temperature: 0.0,
                });
            }
        }

        result
    }

    pub fn detect_underloaded_gpus(
        &self,
        config: &crate::types::SchedulerConfig,
    ) -> Vec<common::types::GpuDevice> {
        let mut result: Vec<common::types::GpuDevice> = Vec::new();

        for gpu in self.gpus.iter() {
            if !gpu.is_healthy {
                continue;
            }

            let mem_util = gpu.memory_usage_percent();
            let is_underloaded = gpu.util_percent <= config.underload_util_threshold
                && mem_util <= config.underload_memory_threshold
                && gpu.model_count < config.max_models_per_gpu / 2;

            if is_underloaded {
                debug!(
                    "Detected underloaded GPU {}: util={:.1}%, mem={:.1}%, models={}",
                    gpu.gpu_id, gpu.util_percent, mem_util, gpu.model_count
                );
                result.push(common::types::GpuDevice {
                    id: gpu.gpu_id.to_string(),
                    uuid: format!("gpu-{}", gpu.gpu_id),
                    name: format!("GPU-{}", gpu.gpu_id),
                    total_memory_mb: gpu.total_mb,
                    used_memory_mb: gpu.used_mb(),
                    utilization_percent: gpu.util_percent as f32,
                    temperature: 0.0,
                });
            }
        }

        result
    }

    pub fn compute_imbalance_score(&self) -> f64 {
        let healthy: Vec<GpuLoad> = self.healthy_gpus();
        if healthy.is_empty() {
            return 0.0;
        }

        let utils: Vec<f64> = healthy.iter().map(|g| g.memory_usage_percent()).collect();
        let avg: f64 = utils.iter().sum::<f64>() / utils.len() as f64;

        let variance: f64 = utils
            .iter()
            .map(|u| (u - avg).powi(2))
            .sum::<f64>()
            / utils.len() as f64;

        variance.sqrt()
    }

    pub fn get_gpu_node_state(&self, gpu_id: usize, config: &crate::types::SchedulerConfig) -> crate::types::GpuNodeState {
        use crate::types::GpuNodeState;

        match self.gpus.get(&gpu_id) {
            Some(gpu) if !gpu.is_healthy => GpuNodeState::Offline,
            Some(gpu) => {
                let mem_util = gpu.memory_usage_percent();
                if gpu.util_percent >= config.overload_util_threshold
                    || mem_util >= config.overload_memory_threshold
                {
                    GpuNodeState::Overloaded
                } else {
                    GpuNodeState::Healthy
                }
            }
            None => GpuNodeState::Offline,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gpu_load_score() {
        let gpu = GpuLoad {
            gpu_id: 0,
            total_mb: 24000,
            free_mb: 12000,
            util_percent: 50.0,
            model_count: 2,
            score: 0.0,
            node_address: None,
            is_healthy: true,
        };
        let score = GpuLoadBalancer::compute_load_score(&gpu);
        assert!(score > 0.0 && score <= 1.0);
    }

    #[test]
    fn test_unhealthy_gpu_score() {
        let gpu = GpuLoad {
            gpu_id: 0,
            total_mb: 24000,
            free_mb: 24000,
            util_percent: 0.0,
            model_count: 0,
            score: 0.0,
            node_address: None,
            is_healthy: false,
        };
        let score = GpuLoadBalancer::compute_load_score(&gpu);
        assert!(score.is_infinite() && score < 0.0);
    }

    #[test]
    fn test_add_and_select_gpu() {
        let balancer = GpuLoadBalancer::new();
        balancer.add_gpu(0, 24000, None);
        balancer.add_gpu(1, 24000, None);
        balancer.report_usage(0, 80.0, 20000);
        balancer.report_usage(1, 20.0, 8000);

        let result = balancer.select_gpu(4000, GpuSelectionStrategy::LeastLoaded, None);
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), 1);
    }

    #[test]
    fn test_insufficient_memory() {
        let balancer = GpuLoadBalancer::new();
        balancer.add_gpu(0, 8000, None);
        let result = balancer.select_gpu(16000, GpuSelectionStrategy::LeastLoaded, None);
        assert!(result.is_err());
    }

    #[test]
    fn test_round_robin() {
        let balancer = GpuLoadBalancer::new();
        balancer.add_gpu(0, 24000, None);
        balancer.add_gpu(1, 24000, None);

        let g1 = balancer
            .select_gpu(1000, GpuSelectionStrategy::RoundRobin, None)
            .unwrap();
        let g2 = balancer
            .select_gpu(1000, GpuSelectionStrategy::RoundRobin, None)
            .unwrap();
        let g3 = balancer
            .select_gpu(1000, GpuSelectionStrategy::RoundRobin, None)
            .unwrap();

        assert_ne!(g1, g2);
        assert_eq!(g1, g3);
    }

    #[test]
    fn test_memory_allocation() {
        let balancer = GpuLoadBalancer::new();
        balancer.add_gpu(0, 10000, None);

        balancer.allocate_memory(0, 5000).unwrap();
        assert_eq!(balancer.get_gpu(0).unwrap().free_mb, 5000);

        balancer.release_memory(0, 3000).unwrap();
        assert_eq!(balancer.get_gpu(0).unwrap().free_mb, 8000);

        assert!(balancer.allocate_memory(0, 9000).is_err());
    }
}
