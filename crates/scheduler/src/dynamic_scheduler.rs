use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};

use common::error::AppError;
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use tracing::{debug, error, info, instrument, warn};
use uuid::Uuid;

const MAX_RPS_NORMALIZE: f64 = 1000.0;
const RPS_WINDOW_BUCKET_SECS: u64 = 10;
const RPS_WINDOW_TOTAL_SECS: u64 = 60;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DynamicSchedulerConfig {
    pub high_watermark_percent: f64,
    pub low_watermark_percent: f64,
    pub protection_period_secs: u64,
    pub warmup_iterations: u32,
    pub warmup_batch_size: u32,
    pub check_interval_secs: u64,
    pub min_rps_threshold: f64,
    pub lru_max_candidates: usize,
}

impl Default for DynamicSchedulerConfig {
    fn default() -> Self {
        Self {
            high_watermark_percent: 90.0,
            low_watermark_percent: 70.0,
            protection_period_secs: 600,
            warmup_iterations: 5,
            warmup_batch_size: 1,
            check_interval_secs: 15,
            min_rps_threshold: 0.1,
            lru_max_candidates: 10,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ModelHeatInfo {
    pub version_id: Uuid,
    pub model_name: String,
    pub version: String,
    pub gpu_memory_mb: u64,
    pub last_accessed: Instant,
    pub request_count: u64,
    pub loaded_at: Instant,
    pub gpu_id: usize,
    pub rps_window: VecDeque<(Instant, u64)>,
    pub in_protection: bool,
}

#[derive(Debug, Clone)]
pub enum LoadState {
    NotLoaded,
    Loading { started_at: Instant },
    Loaded { gpu_id: usize, loaded_at: Instant },
    Unloading,
}

#[derive(Debug, Clone)]
pub enum SchedulerDecision {
    Load { version_id: Uuid, gpu_id: usize },
    Unload {
        version_id: Uuid,
        gpu_id: usize,
        reason: String,
    },
    Warmup { version_id: Uuid, gpu_id: usize },
    Noop,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DynamicSchedulerEvent {
    ModelLoaded {
        version_id: Uuid,
        gpu_id: usize,
        duration_ms: u64,
    },
    ModelUnloaded {
        version_id: Uuid,
        gpu_id: usize,
        reason: String,
    },
    ModelWarmedUp {
        version_id: Uuid,
        gpu_id: usize,
        iterations: u32,
    },
    WatermarkBreached {
        gpu_id: usize,
        used_percent: f64,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelHeatSnapshot {
    pub version_id: Uuid,
    pub model_name: String,
    pub version: String,
    pub gpu_memory_mb: u64,
    pub gpu_id: usize,
    pub rps: f64,
    pub heat_score: f64,
    pub loaded_at_secs: u64,
    pub last_accessed_secs: u64,
    pub in_protection: bool,
    pub load_state: String,
}

pub struct DynamicModelScheduler {
    config: DynamicSchedulerConfig,
    heat_infos: Arc<DashMap<Uuid, ModelHeatInfo>>,
    load_states: Arc<DashMap<Uuid, LoadState>>,
    event_handlers:
        Arc<DashMap<String, Box<dyn Fn(DynamicSchedulerEvent) + Send + Sync>>>,
}

impl DynamicModelScheduler {
    pub fn new(config: DynamicSchedulerConfig) -> Self {
        Self {
            config,
            heat_infos: Arc::new(DashMap::new()),
            load_states: Arc::new(DashMap::new()),
            event_handlers: Arc::new(DashMap::new()),
        }
    }

    pub fn default_config() -> DynamicSchedulerConfig {
        DynamicSchedulerConfig::default()
    }

    pub fn record_access(&self, version_id: Uuid) {
        if let Some(mut info) = self.heat_infos.get_mut(&version_id) {
            let now = Instant::now();
            info.last_accessed = now;
            info.request_count = info.request_count.saturating_add(1);

            let bucket_start = now
                - Duration::from_secs(
                    now.elapsed().as_secs() % RPS_WINDOW_BUCKET_SECS,
                );

            if let Some(last) = info.rps_window.back_mut() {
                if last.0 == bucket_start {
                    last.1 = last.1.saturating_add(1);
                } else {
                    info.rps_window.push_back((bucket_start, 1));
                }
            } else {
                info.rps_window.push_back((bucket_start, 1));
            }

            while let Some(front) = info.rps_window.front() {
                if now.duration_since(front.0).as_secs() > RPS_WINDOW_TOTAL_SECS {
                    info.rps_window.pop_front();
                } else {
                    break;
                }
            }

            debug!(
                "Recorded access for model {}: total_requests={}, window_buckets={}",
                version_id,
                info.request_count,
                info.rps_window.len()
            );
        }
    }

    pub fn compute_heat_score(&self, version_id: Uuid) -> f64 {
        if let Some(info) = self.heat_infos.get(&version_id) {
            let rps = self.get_rps(version_id);
            let rps_normalized = (rps / MAX_RPS_NORMALIZE).min(1.0);

            let hours_since_access = info
                .last_accessed
                .elapsed()
                .as_secs_f64()
                / 3600.0;
            let recency_factor = 1.0 / (1.0 + hours_since_access);

            let heat = rps_normalized * 0.7 + recency_factor * 0.3;

            debug!(
                "Heat score for {}: rps={:.3}, rps_norm={:.3}, recency={:.3}, heat={:.3}",
                version_id, rps, rps_normalized, recency_factor, heat
            );

            heat
        } else {
            debug!("Model {} not found, heat_score=0.0", version_id);
            0.0
        }
    }

    pub fn is_in_protection(&self, version_id: Uuid) -> bool {
        if let Some(info) = self.heat_infos.get(&version_id) {
            let elapsed = info.loaded_at.elapsed().as_secs();
            let in_prot = elapsed < self.config.protection_period_secs;
            debug!(
                "Protection check for {}: elapsed={}s, period={}s, in_protection={}",
                version_id, elapsed, self.config.protection_period_secs, in_prot
            );
            in_prot
        } else {
            false
        }
    }

    pub fn should_unload_candidates(
        &self,
        gpu_memory_by_gpu: &HashMap<usize, (u64, u64)>,
    ) -> Vec<(Uuid, usize, String)> {
        let mut candidates: Vec<(Uuid, usize, String, f64, u64)> = Vec::new();

        for (&gpu_id, &(used_mb, total_mb)) in gpu_memory_by_gpu.iter() {
            let used_percent = if total_mb > 0 {
                (used_mb as f64 / total_mb as f64) * 100.0
            } else {
                0.0
            };

            debug!(
                "Checking GPU {}: used={}MB, total={}MB, percent={:.1}% (high_watermark={:.1}%)",
                gpu_id, used_mb, total_mb, used_percent, self.config.high_watermark_percent
            );

            if used_percent <= self.config.high_watermark_percent {
                continue;
            }

            warn!(
                "GPU {} exceeded high watermark: {:.1}% > {:.1}%",
                gpu_id, used_percent, self.config.high_watermark_percent
            );

            self.emit_event(DynamicSchedulerEvent::WatermarkBreached {
                gpu_id,
                used_percent,
            });

            let target_used_mb =
                (total_mb as f64 * self.config.low_watermark_percent / 100.0) as u64;
            let need_to_free_mb = used_mb.saturating_sub(target_used_mb);

            debug!(
                "GPU {} needs to free {}MB to reach low watermark {:.1}%",
                gpu_id, need_to_free_mb, self.config.low_watermark_percent
            );

            let mut gpu_candidates: Vec<(Uuid, usize, String, f64, u64)> = Vec::new();

            for entry in self.heat_infos.iter() {
                let info = entry.value();
                if info.gpu_id != gpu_id {
                    continue;
                }

                if let Some(state) = self.load_states.get(&info.version_id) {
                    if matches!(&*state, LoadState::Loading { .. }) {
                        debug!(
                            "Skipping model {} on GPU {}: currently loading",
                            info.version_id, gpu_id
                        );
                        continue;
                    }
                    if matches!(&*state, LoadState::Unloading) {
                        debug!(
                            "Skipping model {} on GPU {}: currently unloading",
                            info.version_id, gpu_id
                        );
                        continue;
                    }
                }

                let in_protection = info.loaded_at.elapsed().as_secs()
                    < self.config.protection_period_secs;
                if in_protection {
                    debug!(
                        "Skipping model {} on GPU {}: in protection period",
                        info.version_id, gpu_id
                    );
                    continue;
                }

                let heat_score = self.compute_heat_score(info.version_id);
                let reason = format!(
                    "LRU eviction: heat_score={:.3}, rps={:.3}",
                    heat_score,
                    self.get_rps(info.version_id)
                );

                gpu_candidates.push((
                    info.version_id,
                    gpu_id,
                    reason,
                    heat_score,
                    info.gpu_memory_mb,
                ));
            }

            gpu_candidates.sort_by(|a, b| {
                a.3.partial_cmp(&b.3).unwrap_or(std::cmp::Ordering::Equal)
            });

            let mut freed_mb: u64 = 0;
            for candidate in gpu_candidates.iter().take(self.config.lru_max_candidates) {
                if freed_mb >= need_to_free_mb {
                    break;
                }
                freed_mb = freed_mb.saturating_add(candidate.4);
                candidates.push(candidate.clone());

                debug!(
                    "Added unload candidate {} on GPU {}: frees {}MB, accumulated={}MB/{}MB needed",
                    candidate.0, gpu_id, candidate.4, freed_mb, need_to_free_mb
                );
            }
        }

        candidates
            .into_iter()
            .map(|(vid, gid, reason, _, _)| (vid, gid, reason))
            .collect()
    }

    pub fn can_accept_load(
        &self,
        gpu_id: usize,
        required_mb: u64,
        gpu_memory_by_gpu: &HashMap<usize, (u64, u64)>,
    ) -> bool {
        if let Some(&(used_mb, total_mb)) = gpu_memory_by_gpu.get(&gpu_id) {
            let free_mb = total_mb.saturating_sub(used_mb);
            let can_fit = free_mb >= required_mb;
            debug!(
                "can_accept_load GPU {}: required={}MB, free={}MB, total={}MB, result={}",
                gpu_id, required_mb, free_mb, total_mb, can_fit
            );
            can_fit
        } else {
            debug!("GPU {} not found in memory map", gpu_id);
            false
        }
    }

    pub fn get_rps(&self, version_id: Uuid) -> f64 {
        if let Some(info) = self.heat_infos.get(&version_id) {
            let now = Instant::now();
            let mut total_requests: u64 = 0;
            let mut window_start = now;

            for &(bucket_time, count) in info.rps_window.iter() {
                if now.duration_since(bucket_time).as_secs() <= RPS_WINDOW_TOTAL_SECS {
                    total_requests = total_requests.saturating_add(count);
                    if bucket_time < window_start {
                        window_start = bucket_time;
                    }
                }
            }

            let elapsed = now.duration_since(window_start).as_secs_f64();
            if elapsed > 0.0 {
                total_requests as f64 / elapsed
            } else {
                0.0
            }
        } else {
            0.0
        }
    }

    pub fn get_load_state(&self, version_id: Uuid) -> LoadState {
        self.load_states
            .get(&version_id)
            .map(|s| s.clone())
            .unwrap_or(LoadState::NotLoaded)
    }

    pub fn set_load_state(&self, version_id: Uuid, state: LoadState) {
        debug!("Setting load state for {}: {:?}", version_id, state);
        self.load_states.insert(version_id, state);
    }

    pub fn register_model(
        &self,
        version_id: Uuid,
        model_name: String,
        version: String,
        gpu_memory_mb: u64,
        gpu_id: usize,
    ) {
        let now = Instant::now();
        let info = ModelHeatInfo {
            version_id,
            model_name: model_name.clone(),
            version: version.clone(),
            gpu_memory_mb,
            last_accessed: now,
            request_count: 0,
            loaded_at: now,
            gpu_id,
            rps_window: VecDeque::new(),
            in_protection: true,
        };

        self.heat_infos.insert(version_id, info);
        self.load_states.insert(
            version_id,
            LoadState::Loaded {
                gpu_id,
                loaded_at: now,
            },
        );

        info!(
            "Registered model {} (v{}) on GPU {}: {}MB",
            version_id, version, gpu_id, gpu_memory_mb
        );
    }

    pub fn unregister_model(&self, version_id: Uuid) {
        self.heat_infos.remove(&version_id);
        self.load_states.remove(&version_id);
        debug!("Unregistered model {}", version_id);
    }

    pub fn tick(
        &self,
        gpu_memory_by_gpu: &HashMap<usize, (u64, u64)>,
    ) -> Vec<SchedulerDecision> {
        let mut decisions: Vec<SchedulerDecision> = Vec::new();

        debug!("Starting scheduler tick...");

        let unload_candidates = self.should_unload_candidates(gpu_memory_by_gpu);
        for (version_id, gpu_id, reason) in unload_candidates {
            info!(
                "Scheduling unload for {} on GPU {}: {}",
                version_id, gpu_id, reason
            );
            self.set_load_state(version_id, LoadState::Unloading);
            decisions.push(SchedulerDecision::Unload {
                version_id,
                gpu_id,
                reason,
            });

            self.emit_event(DynamicSchedulerEvent::ModelUnloaded {
                version_id,
                gpu_id,
                reason: "Watermark LRU eviction".to_string(),
            });
        }

        for mut entry in self.heat_infos.iter_mut() {
            let mut info = entry.value_mut();
            let elapsed = info.loaded_at.elapsed().as_secs();
            let was_in_protection = info.in_protection;
            info.in_protection = elapsed < self.config.protection_period_secs;

            if was_in_protection && !info.in_protection {
                debug!(
                    "Model {} protection period expired after {}s",
                    info.version_id, elapsed
                );
            }
        }

        if decisions.is_empty() {
            debug!("Scheduler tick completed: no decisions");
            decisions.push(SchedulerDecision::Noop);
        } else {
            info!(
                "Scheduler tick completed: {} decisions",
                decisions.len()
            );
        }

        decisions
    }

    pub fn add_event_handler<F>(&self, name: String, handler: F)
    where
        F: Fn(DynamicSchedulerEvent) + Send + Sync + 'static,
    {
        self.event_handlers.insert(name, Box::new(handler));
        debug!("Registered dynamic scheduler event handler");
    }

    pub fn get_snapshot(&self) -> Vec<ModelHeatSnapshot> {
        let mut snapshots: Vec<ModelHeatSnapshot> = Vec::new();
        let now = Instant::now();

        for entry in self.heat_infos.iter() {
            let info = entry.value();
            let rps = self.get_rps(info.version_id);
            let heat_score = self.compute_heat_score(info.version_id);
            let load_state_desc = match self.get_load_state(info.version_id) {
                LoadState::NotLoaded => "NotLoaded".to_string(),
                LoadState::Loading { .. } => "Loading".to_string(),
                LoadState::Loaded { .. } => "Loaded".to_string(),
                LoadState::Unloading => "Unloading".to_string(),
            };
            let in_protection =
                info.loaded_at.elapsed().as_secs() < self.config.protection_period_secs;

            snapshots.push(ModelHeatSnapshot {
                version_id: info.version_id,
                model_name: info.model_name.clone(),
                version: info.version.clone(),
                gpu_memory_mb: info.gpu_memory_mb,
                gpu_id: info.gpu_id,
                rps,
                heat_score,
                loaded_at_secs: info.loaded_at.elapsed().as_secs(),
                last_accessed_secs: info.last_accessed.elapsed().as_secs(),
                in_protection,
                load_state: load_state_desc,
            });
        }

        snapshots.sort_by(|a, b| {
            b.heat_score
                .partial_cmp(&a.heat_score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        snapshots
    }

    fn emit_event(&self, event: DynamicSchedulerEvent) {
        for handler in self.event_handlers.iter() {
            handler.value()(event.clone());
        }
    }
}

impl Clone for DynamicModelScheduler {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            heat_infos: Arc::clone(&self.heat_infos),
            load_states: Arc::clone(&self.load_states),
            event_handlers: Arc::clone(&self.event_handlers),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_scheduler() -> DynamicModelScheduler {
        let config = DynamicSchedulerConfig {
            protection_period_secs: 1,
            high_watermark_percent: 80.0,
            low_watermark_percent: 50.0,
            ..DynamicSchedulerConfig::default()
        };
        DynamicModelScheduler::new(config)
    }

    #[test]
    fn test_default_config() {
        let cfg = DynamicModelScheduler::default_config();
        assert_eq!(cfg.high_watermark_percent, 90.0);
        assert_eq!(cfg.low_watermark_percent, 70.0);
        assert_eq!(cfg.protection_period_secs, 600);
        assert_eq!(cfg.warmup_iterations, 5);
    }

    #[test]
    fn test_register_and_unregister_model() {
        let scheduler = create_test_scheduler();
        let vid = Uuid::new_v4();

        scheduler.register_model(vid, "test-model".into(), "v1".into(), 2048, 0);

        let snapshot = scheduler.get_snapshot();
        assert_eq!(snapshot.len(), 1);
        assert_eq!(snapshot[0].version_id, vid);
        assert_eq!(snapshot[0].gpu_memory_mb, 2048);

        scheduler.unregister_model(vid);
        assert_eq!(scheduler.get_snapshot().len(), 0);
    }

    #[test]
    fn test_record_access_and_rps() {
        let scheduler = create_test_scheduler();
        let vid = Uuid::new_v4();

        scheduler.register_model(vid, "test-model".into(), "v1".into(), 2048, 0);

        scheduler.record_access(vid);
        scheduler.record_access(vid);
        scheduler.record_access(vid);

        let rps = scheduler.get_rps(vid);
        assert!(rps >= 0.0);

        let info = scheduler.heat_infos.get(&vid).unwrap();
        assert_eq!(info.request_count, 3);
    }

    #[test]
    fn test_protection_period() {
        let scheduler = create_test_scheduler();
        let vid = Uuid::new_v4();

        scheduler.register_model(vid, "test-model".into(), "v1".into(), 2048, 0);
        assert!(scheduler.is_in_protection(vid));
    }

    #[test]
    fn test_compute_heat_score() {
        let scheduler = create_test_scheduler();
        let vid = Uuid::new_v4();

        scheduler.register_model(vid, "test-model".into(), "v1".into(), 2048, 0);

        let score_before = scheduler.compute_heat_score(vid);

        for _ in 0..100 {
            scheduler.record_access(vid);
        }

        let score_after = scheduler.compute_heat_score(vid);
        assert!(score_after >= score_before);
        assert!(score_after >= 0.0 && score_after <= 1.0);
    }

    #[test]
    fn test_can_accept_load() {
        let scheduler = create_test_scheduler();
        let mut gpu_mem = HashMap::new();
        gpu_mem.insert(0, (15000, 24000));

        assert!(scheduler.can_accept_load(0, 4000, &gpu_mem));
        assert!(!scheduler.can_accept_load(0, 10000, &gpu_mem));
        assert!(!scheduler.can_accept_load(1, 1000, &gpu_mem));
    }

    #[test]
    fn test_load_states() {
        let scheduler = create_test_scheduler();
        let vid = Uuid::new_v4();

        assert!(matches!(
            scheduler.get_load_state(vid),
            LoadState::NotLoaded
        ));

        scheduler.set_load_state(vid, LoadState::Loading {
            started_at: Instant::now(),
        });
        assert!(matches!(
            scheduler.get_load_state(vid),
            LoadState::Loading { .. }
        ));

        scheduler.set_load_state(
            vid,
            LoadState::Loaded {
                gpu_id: 0,
                loaded_at: Instant::now(),
            },
        );
        assert!(matches!(
            scheduler.get_load_state(vid),
            LoadState::Loaded { .. }
        ));

        scheduler.set_load_state(vid, LoadState::Unloading);
        assert!(matches!(
            scheduler.get_load_state(vid),
            LoadState::Unloading
        ));
    }

    #[test]
    fn test_tick_noop() {
        let scheduler = create_test_scheduler();
        let gpu_mem = HashMap::new();
        let decisions = scheduler.tick(&gpu_mem);
        assert_eq!(decisions.len(), 1);
        assert!(matches!(decisions[0], SchedulerDecision::Noop));
    }

    #[test]
    fn test_snapshot() {
        let scheduler = create_test_scheduler();
        let vid1 = Uuid::new_v4();
        let vid2 = Uuid::new_v4();

        scheduler.register_model(vid1, "model-a".into(), "v1".into(), 1024, 0);
        scheduler.register_model(vid2, "model-b".into(), "v1".into(), 2048, 0);

        for _ in 0..50 {
            scheduler.record_access(vid1);
        }

        let snapshots = scheduler.get_snapshot();
        assert_eq!(snapshots.len(), 2);
        assert_eq!(snapshots[0].version_id, vid1);
        assert!(snapshots[0].heat_score >= snapshots[1].heat_score);
    }
}
