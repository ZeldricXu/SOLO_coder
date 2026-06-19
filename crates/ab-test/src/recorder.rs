use chrono::{DateTime, Utc};
use common::error::AppError;
use db::RedisClient;
use parking_lot::Mutex;
use redis::AsyncCommands;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tracing::{debug, error, info};
use uuid::Uuid;

use crate::stats::Statistics;
use crate::types::{MetricValue, PendingObservation};

const FLUSH_THRESHOLD: usize = 100;
const FLUSH_INTERVAL_SECS: u64 = 5;

#[derive(Debug, Default, Clone)]
struct StatsAccumulator {
    sum: f64,
    sum_sq: f64,
    count: i64,
    min: f64,
    max: f64,
}

impl StatsAccumulator {
    fn new() -> Self {
        Self {
            sum: 0.0,
            sum_sq: 0.0,
            count: 0,
            min: f64::INFINITY,
            max: f64::NEG_INFINITY,
        }
    }

    fn update(&mut self, value: f64) {
        self.sum += value;
        self.sum_sq += value * value;
        self.count += 1;
        if value < self.min {
            self.min = value;
        }
        if value > self.max {
            self.max = value;
        }
    }

    fn merge(&mut self, other: &StatsAccumulator) {
        self.sum += other.sum;
        self.sum_sq += other.sum_sq;
        self.count += other.count;
        if other.min < self.min {
            self.min = other.min;
        }
        if other.max > self.max {
            self.max = other.max;
        }
    }

    fn mean(&self) -> f64 {
        if self.count == 0 {
            0.0
        } else {
            self.sum / self.count as f64
        }
    }

    fn variance(&self) -> f64 {
        if self.count < 2 {
            0.0
        } else {
            let mean = self.mean();
            (self.sum_sq / self.count as f64 - mean * mean).max(0.0)
        }
    }

    fn std(&self) -> f64 {
        self.variance().sqrt()
    }
}

pub struct ExperimentRecorder {
    redis: RedisClient,
    pending: Arc<Mutex<Vec<PendingObservation>>>,
    handle: Option<tokio::task::JoinHandle<()>>,
}

impl ExperimentRecorder {
    pub fn new(redis: RedisClient) -> Self {
        Self {
            redis,
            pending: Arc::new(Mutex::new(Vec::new())),
            handle: None,
        }
    }

    pub fn start_background_flush(&mut self) {
        let pending = Arc::clone(&self.pending);
        let interval = Duration::from_secs(FLUSH_INTERVAL_SECS);

        let handle = tokio::spawn(async move {
            let mut interval_timer = tokio::time::interval(interval);
            loop {
                interval_timer.tick().await;
                let _ = Self::flush_pending_observations(&pending);
            }
        });

        self.handle = Some(handle);
    }

    fn stats_key(experiment_id: Uuid, group_name: &str, metric_name: &str) -> String {
        format!(
            "experiment:{}:{}:{}:stats",
            experiment_id, group_name, metric_name
        )
    }

    fn counter_key(experiment_id: Uuid, group_name: &str) -> String {
        format!("experiment:{}:{}:count", experiment_id, group_name)
    }

    pub async fn record_metric(
        &self,
        experiment_id: Uuid,
        group_name: &str,
        metric_name: &str,
        value: f64,
    ) -> Result<(), AppError> {
        let timestamp = Utc::now();

        let stats_key = Self::stats_key(experiment_id, group_name, metric_name);
        let counter_key = Self::counter_key(experiment_id, group_name);

        let mut redis_conn = self.redis.clone();

        redis_conn.incr::<_, _, ()>(&counter_key, 1).await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        self.update_redis_stats(&mut redis_conn, &stats_key, value).await?;

        {
            let mut pending = self.pending.lock();
            pending.push(PendingObservation {
                experiment_id,
                group_name: group_name.to_string(),
                metric_name: metric_name.to_string(),
                value,
                timestamp,
            });

            if pending.len() >= FLUSH_THRESHOLD {
                let pending_clone = std::mem::take(&mut *pending);
                drop(pending);
                tokio::spawn(async move {
                    Self::flush_pending_observations_standalone(pending_clone);
                });
            }
        }

        debug!(
            "Recorded metric: exp={}, group={}, metric={}, value={}",
            experiment_id, group_name, metric_name, value
        );

        Ok(())
    }

    async fn update_redis_stats(
        &self,
        redis_conn: &mut RedisClient,
        stats_key: &str,
        value: f64,
    ) -> Result<(), AppError> {
        let current: HashMap<String, String> = redis_conn
            .hgetall(stats_key)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        let mut accum = StatsAccumulator::new();
        if let Some(count_str) = current.get("count") {
            if let Ok(count) = count_str.parse::<i64>() {
                accum.count = count;
            }
        }
        if let Some(sum_str) = current.get("sum") {
            if let Ok(sum) = sum_str.parse::<f64>() {
                accum.sum = sum;
            }
        }
        if let Some(sum_sq_str) = current.get("sum_sq") {
            if let Ok(sum_sq) = sum_sq_str.parse::<f64>() {
                accum.sum_sq = sum_sq;
            }
        }
        if let Some(min_str) = current.get("min") {
            if let Ok(min) = min_str.parse::<f64>() {
                accum.min = min;
            }
        }
        if let Some(max_str) = current.get("max") {
            if let Ok(max) = max_str.parse::<f64>() {
                accum.max = max;
            }
        }

        accum.update(value);

        redis_conn
            .hset_multiple::<_, _, _, ()>(
                stats_key,
                &[
                    ("sum", accum.sum.to_string()),
                    ("sum_sq", accum.sum_sq.to_string()),
                    ("count", accum.count.to_string()),
                    ("min", accum.min.to_string()),
                    ("max", accum.max.to_string()),
                ],
            )
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        Ok(())
    }

    pub async fn get_metric_stats(
        &self,
        experiment_id: Uuid,
        group_name: &str,
        metric_name: &str,
    ) -> Result<MetricValue, AppError> {
        let stats_key = Self::stats_key(experiment_id, group_name, metric_name);
        let mut redis_conn = self.redis.clone();

        let stats: HashMap<String, String> = redis_conn
            .hgetall(&stats_key)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        let count = stats
            .get("count")
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0) as u64;
        let sum = stats
            .get("sum")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(0.0);
        let sum_sq = stats
            .get("sum_sq")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(0.0);
        let min = stats
            .get("min")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(f64::INFINITY);
        let max = stats
            .get("max")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(f64::NEG_INFINITY);

        let mean = if count > 0 { sum / count as f64 } else { 0.0 };
        let variance = if count >= 2 {
            (sum_sq / count as f64 - mean * mean).max(0.0)
        } else {
            0.0
        };
        let std = variance.sqrt();

        Ok(MetricValue {
            name: metric_name.to_string(),
            sample_count: count,
            mean,
            std,
            min: if min.is_finite() { min } else { 0.0 },
            max: if max.is_finite() { max } else { 0.0 },
        })
    }

    pub async fn get_group_observation_count(
        &self,
        experiment_id: Uuid,
        group_name: &str,
    ) -> Result<u64, AppError> {
        let counter_key = Self::counter_key(experiment_id, group_name);
        let mut redis_conn = self.redis.clone();

        let count: Option<i64> = redis_conn
            .get(&counter_key)
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        Ok(count.unwrap_or(0) as u64)
    }

    fn flush_pending_observations(
        pending: &Arc<Mutex<Vec<PendingObservation>>>,
    ) -> Result<usize, AppError> {
        let items = {
            let mut p = pending.lock();
            if p.is_empty() {
                return Ok(0);
            }
            std::mem::take(&mut *p)
        };
        let count = items.len();
        Self::process_flushed_observations(&items);
        Ok(count)
    }

    fn flush_pending_observations_standalone(items: Vec<PendingObservation>) {
        if items.is_empty() {
            return;
        }
        Self::process_flushed_observations(&items);
    }

    fn process_flushed_observations(items: &[PendingObservation]) {
        let mut grouped: HashMap<(Uuid, String, String), StatsAccumulator> = HashMap::new();
        for item in items {
            let key = (
                item.experiment_id,
                item.group_name.clone(),
                item.metric_name.clone(),
            );
            grouped
                .entry(key)
                .or_insert_with(StatsAccumulator::new)
                .update(item.value);
        }

        for ((exp_id, group_name, metric_name), stats) in &grouped {
            debug!(
                "Flushed stats: exp={}, group={}, metric={}, n={}, mean={:.4}",
                exp_id,
                group_name,
                metric_name,
                stats.count,
                stats.mean()
            );
        }
    }

    pub async fn flush_all(&self) -> Result<usize, AppError> {
        let pending = {
            let mut p = self.pending.lock();
            std::mem::take(&mut *p)
        };
        let count = pending.len();
        Self::process_flushed_observations(&pending);
        info!("Flushed {} pending observations", count);
        Ok(count)
    }

    pub async fn clear_experiment_data(&self, experiment_id: Uuid) -> Result<(), AppError> {
        let mut redis_conn = self.redis.clone();
        let pattern = format!("experiment:{}:*", experiment_id);
        let keys: Vec<String> = redis_conn
            .keys(&pattern)
            .await
            .unwrap_or_default();
        if !keys.is_empty() {
            redis_conn
                .del::<_, ()>(keys)
                .await
                .map_err(|e| AppError::Cache(e.to_string()))?;
        }
        info!("Cleared Redis data for experiment: {}", experiment_id);
        Ok(())
    }
}

impl Drop for ExperimentRecorder {
    fn drop(&mut self) {
        if let Some(handle) = self.handle.take() {
            handle.abort();
        }
    }
}
