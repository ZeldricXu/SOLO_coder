use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use chrono::{DateTime, Utc};
use common::error::AppError;
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use db::RedisClient;
use redis::AsyncCommands;

const MAX_LATENCIES: usize = 10_000;
const REDIS_KEY_PREFIX: &str = "rollout:";

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum RolloutPhase {
    Pending,
    RollingOut { current_percent: u8 },
    Completed,
    RolledBack { reason: String },
    Paused,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RolloutConfig {
    #[serde(default = "default_initial_percent")]
    pub initial_percent: u8,
    #[serde(default = "default_step_percent")]
    pub step_percent: u8,
    #[serde(default = "default_window_secs")]
    pub window_secs: u64,
    #[serde(default = "default_max_error_rate_ratio")]
    pub max_error_rate_ratio: f64,
    #[serde(default = "default_max_p99_latency_ratio")]
    pub max_p99_latency_ratio: f64,
    #[serde(default = "default_min_samples")]
    pub min_samples: u64,
    #[serde(default = "default_protection_secs")]
    pub protection_secs: u64,
}

fn default_initial_percent() -> u8 {
    5
}

fn default_step_percent() -> u8 {
    10
}

fn default_window_secs() -> u64 {
    3600
}

fn default_max_error_rate_ratio() -> f64 {
    1.2
}

fn default_max_p99_latency_ratio() -> f64 {
    1.5
}

fn default_min_samples() -> u64 {
    100
}

fn default_protection_secs() -> u64 {
    300
}

impl Default for RolloutConfig {
    fn default() -> Self {
        Self {
            initial_percent: default_initial_percent(),
            step_percent: default_step_percent(),
            window_secs: default_window_secs(),
            max_error_rate_ratio: default_max_error_rate_ratio(),
            max_p99_latency_ratio: default_max_p99_latency_ratio(),
            min_samples: default_min_samples(),
            protection_secs: default_protection_secs(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct VersionMetrics {
    pub total_requests: u64,
    pub error_count: u64,
    pub latencies_ms: Vec<u64>,
    pub started_at: DateTime<Utc>,
}

impl VersionMetrics {
    pub fn new() -> Self {
        Self {
            total_requests: 0,
            error_count: 0,
            latencies_ms: Vec::with_capacity(MAX_LATENCIES),
            started_at: Utc::now(),
        }
    }

    pub fn record(&mut self, latency_ms: u64, is_error: bool) {
        self.total_requests += 1;
        if is_error {
            self.error_count += 1;
        }
        if self.latencies_ms.len() >= MAX_LATENCIES {
            self.latencies_ms.remove(0);
        }
        self.latencies_ms.push(latency_ms);
    }
}

impl Default for VersionMetrics {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Rollout {
    pub id: Uuid,
    pub model_name: String,
    pub old_version_id: Uuid,
    pub new_version_id: Uuid,
    pub config: RolloutConfig,
    pub phase: RolloutPhase,
    pub current_percent: u8,
    pub started_at: DateTime<Utc>,
    #[serde(skip, default = "Instant::now")]
    pub last_window_start: Instant,
    pub baseline_metrics: VersionMetrics,
    pub candidate_metrics: VersionMetrics,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum RolloutEvent {
    Started {
        rollout_id: Uuid,
        model_name: String,
    },
    Progress {
        rollout_id: Uuid,
        model_name: String,
        percent: u8,
    },
    RollbackTriggered {
        rollout_id: Uuid,
        model_name: String,
        reason: String,
    },
    Completed {
        rollout_id: Uuid,
        model_name: String,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RolloutSnapshot {
    pub id: Uuid,
    pub model_name: String,
    pub old_version_id: Uuid,
    pub new_version_id: Uuid,
    pub config: RolloutConfig,
    pub phase: RolloutPhase,
    pub current_percent: u8,
    pub started_at: DateTime<Utc>,
    pub baseline_total_requests: u64,
    pub baseline_error_count: u64,
    pub baseline_error_rate: f64,
    pub baseline_p99_latency_ms: u64,
    pub candidate_total_requests: u64,
    pub candidate_error_count: u64,
    pub candidate_error_rate: f64,
    pub candidate_p99_latency_ms: u64,
}

#[derive(Clone)]
pub struct RolloutManager {
    redis: RedisClient,
    rollouts: Arc<DashMap<String, Rollout>>,
    event_tx: broadcast::Sender<RolloutEvent>,
}

impl RolloutManager {
    pub fn new(redis: RedisClient) -> Self {
        let (event_tx, _) = broadcast::channel(1024);
        Self {
            redis,
            rollouts: Arc::new(DashMap::new()),
            event_tx,
        }
    }

    pub fn start_rollout(
        &self,
        model_name: &str,
        old_version_id: Uuid,
        new_version_id: Uuid,
        config: Option<RolloutConfig>,
    ) -> Result<Uuid, AppError> {
        if self.rollouts.contains_key(model_name) {
            return Err(AppError::Validation(format!(
                "Rollout already exists for model: {}",
                model_name
            )));
        }

        let config = config.unwrap_or_default();
        let rollout_id = Uuid::new_v4();
        let now = Utc::now();

        let rollout = Rollout {
            id: rollout_id,
            model_name: model_name.to_string(),
            old_version_id,
            new_version_id,
            config: config.clone(),
            phase: RolloutPhase::RollingOut {
                current_percent: config.initial_percent,
            },
            current_percent: config.initial_percent,
            started_at: now,
            last_window_start: Instant::now(),
            baseline_metrics: VersionMetrics::new(),
            candidate_metrics: VersionMetrics::new(),
        };

        self.rollouts.insert(model_name.to_string(), rollout);
        self.persist_rollout(model_name)?;

        let _ = self.event_tx.send(RolloutEvent::Started {
            rollout_id,
            model_name: model_name.to_string(),
        });

        info!(
            "Rollout started for model: {}, rollout_id: {}, initial_percent: {}%",
            model_name, rollout_id, config.initial_percent
        );

        Ok(rollout_id)
    }

    pub fn cancel_rollout(&self, model_name: &str) -> Result<(), AppError> {
        self.rollouts.remove(model_name);
        self.delete_rollout_from_redis(model_name)?;
        info!("Rollout cancelled for model: {}", model_name);
        Ok(())
    }

    pub fn pause_rollout(&self, model_name: &str) -> Result<(), AppError> {
        let mut entry = self
            .rollouts
            .get_mut(model_name)
            .ok_or_else(|| AppError::Validation(format!("Rollout not found for model: {}", model_name)))?;

        if matches!(entry.phase, RolloutPhase::RolledBack { .. } | RolloutPhase::Completed) {
            return Err(AppError::Validation(format!(
                "Cannot pause rollout in terminal phase for model: {}",
                model_name
            )));
        }

        entry.phase = RolloutPhase::Paused;
        self.persist_rollout(model_name)?;
        info!("Rollout paused for model: {}", model_name);
        Ok(())
    }

    pub fn resume_rollout(&self, model_name: &str) -> Result<(), AppError> {
        let mut entry = self
            .rollouts
            .get_mut(model_name)
            .ok_or_else(|| AppError::Validation(format!("Rollout not found for model: {}", model_name)))?;

        let current_percent = entry.current_percent;
        entry.phase = RolloutPhase::RollingOut { current_percent };
        entry.last_window_start = Instant::now();
        self.persist_rollout(model_name)?;
        info!("Rollout resumed for model: {}", model_name);
        Ok(())
    }

    pub fn record_metrics(&self, model_name: &str, version_id: Uuid, latency_ms: u64, is_error: bool) {
        let Some(mut entry) = self.rollouts.get_mut(model_name) else {
            return;
        };

        if version_id == entry.old_version_id {
            entry.baseline_metrics.record(latency_ms, is_error);
        } else if version_id == entry.new_version_id {
            entry.candidate_metrics.record(latency_ms, is_error);
        }
    }

    pub fn route_percent(&self, model_name: &str) -> Option<u8> {
        let entry = self.rollouts.get(model_name)?;
        match entry.phase {
            RolloutPhase::RollingOut { current_percent } => Some(current_percent),
            RolloutPhase::Completed => Some(100),
            RolloutPhase::RolledBack { .. } => Some(0),
            RolloutPhase::Paused => Some(entry.current_percent),
            RolloutPhase::Pending => Some(0),
        }
    }

    pub fn tick(&self) {
        let model_names: Vec<String> = self.rollouts.iter().map(|e| e.key().clone()).collect();

        for model_name in model_names {
            self.tick_single(&model_name);
        }
    }

    fn tick_single(&self, model_name: &str) {
        let mut rollout = match self.rollouts.get_mut(model_name) {
            Some(r) => r,
            None => return,
        };

        let RolloutPhase::RollingOut { .. } = rollout.phase else {
            return;
        };

        let elapsed = rollout.last_window_start.elapsed();
        let window_duration = Duration::from_secs(rollout.config.window_secs);

        let protection_elapsed = chrono::Utc::now()
            .signed_duration_since(rollout.started_at)
            .num_seconds()
            .max(0) as u64;
        let in_protection = protection_elapsed < rollout.config.protection_secs;

        if !in_protection {
            if let Some(reason) = self.check_rollback_conditions(&rollout) {
                warn!(
                    "Rollback triggered for model: {}, rollout_id: {}, reason: {}",
                    model_name, rollout.id, reason
                );
                rollout.phase = RolloutPhase::RolledBack {
                    reason: reason.clone(),
                };
                rollout.current_percent = 0;
                let rollout_id = rollout.id;
                let model_name_owned = model_name.to_string();
                drop(rollout);
                let _ = self.event_tx.send(RolloutEvent::RollbackTriggered {
                    rollout_id,
                    model_name: model_name_owned.clone(),
                    reason,
                });
                let _ = self.persist_rollout(&model_name_owned);
                return;
            }
        }

        if elapsed >= window_duration {
            let new_percent = (rollout.current_percent + rollout.config.step_percent).min(100);
            let rollout_id = rollout.id;
            let model_name_owned = model_name.to_string();

            if new_percent >= 100 {
                rollout.phase = RolloutPhase::Completed;
                rollout.current_percent = 100;
                rollout.last_window_start = Instant::now();
                drop(rollout);
                info!(
                    "Rollout completed for model: {}, rollout_id: {}",
                    model_name_owned, rollout_id
                );
                let _ = self.event_tx.send(RolloutEvent::Completed {
                    rollout_id,
                    model_name: model_name_owned.clone(),
                });
                let _ = self.persist_rollout(&model_name_owned);
            } else {
                rollout.phase = RolloutPhase::RollingOut {
                    current_percent: new_percent,
                };
                rollout.current_percent = new_percent;
                rollout.last_window_start = Instant::now();
                drop(rollout);
                debug!(
                    "Rollout progress for model: {}, rollout_id: {}, percent: {}%",
                    model_name_owned, rollout_id, new_percent
                );
                let _ = self.event_tx.send(RolloutEvent::Progress {
                    rollout_id,
                    model_name: model_name_owned.clone(),
                    percent: new_percent,
                });
                let _ = self.persist_rollout(&model_name_owned);
            }
        }
    }

    fn check_rollback_conditions(&self, rollout: &Rollout) -> Option<String> {
        let baseline = &rollout.baseline_metrics;
        let candidate = &rollout.candidate_metrics;

        if baseline.total_requests < rollout.config.min_samples
            || candidate.total_requests < rollout.config.min_samples
        {
            return None;
        }

        let baseline_error_rate = error_rate(baseline);
        let candidate_error_rate = error_rate(candidate);

        if baseline_error_rate > 0.0 {
            let error_ratio = candidate_error_rate / baseline_error_rate;
            if error_ratio > rollout.config.max_error_rate_ratio {
                return Some(format!(
                    "Error rate exceeded threshold: candidate={:.4}, baseline={:.4}, ratio={:.2}, max_ratio={:.2}",
                    candidate_error_rate, baseline_error_rate, error_ratio, rollout.config.max_error_rate_ratio
                ));
            }
        }

        let baseline_p99 = compute_p99(&baseline.latencies_ms);
        let candidate_p99 = compute_p99(&candidate.latencies_ms);

        if baseline_p99 > 0 {
            let latency_ratio = candidate_p99 as f64 / baseline_p99 as f64;
            if latency_ratio > rollout.config.max_p99_latency_ratio {
                return Some(format!(
                    "P99 latency exceeded threshold: candidate={}ms, baseline={}ms, ratio={:.2}, max_ratio={:.2}",
                    candidate_p99, baseline_p99, latency_ratio, rollout.config.max_p99_latency_ratio
                ));
            }
        }

        None
    }

    pub fn get_rollout(&self, model_name: &str) -> Option<RolloutSnapshot> {
        let rollout = self.rollouts.get(model_name)?;
        Some(RolloutSnapshot {
            id: rollout.id,
            model_name: rollout.model_name.clone(),
            old_version_id: rollout.old_version_id,
            new_version_id: rollout.new_version_id,
            config: rollout.config.clone(),
            phase: rollout.phase.clone(),
            current_percent: rollout.current_percent,
            started_at: rollout.started_at,
            baseline_total_requests: rollout.baseline_metrics.total_requests,
            baseline_error_count: rollout.baseline_metrics.error_count,
            baseline_error_rate: error_rate(&rollout.baseline_metrics),
            baseline_p99_latency_ms: compute_p99(&rollout.baseline_metrics.latencies_ms),
            candidate_total_requests: rollout.candidate_metrics.total_requests,
            candidate_error_count: rollout.candidate_metrics.error_count,
            candidate_error_rate: error_rate(&rollout.candidate_metrics),
            candidate_p99_latency_ms: compute_p99(&rollout.candidate_metrics.latencies_ms),
        })
    }

    pub fn subscribe(&self) -> broadcast::Receiver<RolloutEvent> {
        self.event_tx.subscribe()
    }

    fn persist_rollout(&self, model_name: &str) -> Result<(), AppError> {
        let rollout = self
            .rollouts
            .get(model_name)
            .ok_or_else(|| AppError::Validation(format!("Rollout not found for model: {}", model_name)))?;

        let key = format!("{}{}", REDIS_KEY_PREFIX, model_name);
        let serialized = serde_json::to_string(&*rollout)?;

        let mut conn = self.redis.manager.clone();
        let key_clone = key.clone();
        tokio::spawn(async move {
            let result: Result<String, redis::RedisError> = redis::cmd("SET")
                .arg(key_clone)
                .arg(serialized)
                .query_async(&mut conn)
                .await;
            if let Err(e) = result {
                error!("Failed to persist rollout to Redis: {}", e);
            }
        });

        Ok(())
    }

    fn delete_rollout_from_redis(&self, model_name: &str) -> Result<(), AppError> {
        let key = format!("{}{}", REDIS_KEY_PREFIX, model_name);
        let mut conn = self.redis.manager.clone();
        tokio::spawn(async move {
            let result: Result<usize, redis::RedisError> = redis::cmd("DEL")
                .arg(key)
                .query_async(&mut conn)
                .await;
            if let Err(e) = result {
                error!("Failed to delete rollout from Redis: {}", e);
            }
        });
        Ok(())
    }
}

pub fn compute_percentile(latencies: &[u64], p: f64) -> u64 {
    if latencies.is_empty() {
        return 0;
    }

    let mut sorted: Vec<u64> = latencies.to_vec();
    sorted.sort_unstable();

    let n = sorted.len();
    let index = (p * (n - 1) as f64).round() as usize;
    sorted[index.min(n - 1)]
}

pub fn compute_p99(latencies: &[u64]) -> u64 {
    compute_percentile(latencies, 0.99)
}

pub fn error_rate(metrics: &VersionMetrics) -> f64 {
    if metrics.total_requests == 0 {
        return 0.0;
    }
    metrics.error_count as f64 / metrics.total_requests as f64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compute_percentile_empty() {
        assert_eq!(compute_percentile(&[], 0.99), 0);
    }

    #[test]
    fn test_compute_percentile_single() {
        assert_eq!(compute_percentile(&[42], 0.99), 42);
    }

    #[test]
    fn test_compute_p99() {
        let latencies: Vec<u64> = (1..=100).collect();
        assert_eq!(compute_p99(&latencies), 99);
    }

    #[test]
    fn test_error_rate_zero_requests() {
        let metrics = VersionMetrics::new();
        assert_eq!(error_rate(&metrics), 0.0);
    }

    #[test]
    fn test_error_rate() {
        let mut metrics = VersionMetrics::new();
        for i in 0..100 {
            metrics.record(10, i < 5);
        }
        assert!((error_rate(&metrics) - 0.05).abs() < 1e-10);
    }

    #[test]
    fn test_version_metrics_ring_buffer() {
        let mut metrics = VersionMetrics::new();
        for i in 0..20_000 {
            metrics.record(i, false);
        }
        assert_eq!(metrics.total_requests, 20_000);
        assert_eq!(metrics.latencies_ms.len(), MAX_LATENCIES);
        assert_eq!(metrics.latencies_ms[0], 10_000);
    }

    #[test]
    fn test_rollout_config_defaults() {
        let config = RolloutConfig::default();
        assert_eq!(config.initial_percent, 5);
        assert_eq!(config.step_percent, 10);
        assert_eq!(config.window_secs, 3600);
        assert_eq!(config.max_error_rate_ratio, 1.2);
        assert_eq!(config.max_p99_latency_ratio, 1.5);
        assert_eq!(config.min_samples, 100);
        assert_eq!(config.protection_secs, 300);
    }
}
