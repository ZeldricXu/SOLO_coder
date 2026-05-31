use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::Result;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metric {
    pub name: String,
    pub value: f64,
    pub metric_type: MetricType,
    pub dimensions: HashMap<String, String>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum MetricType {
    Counter,
    Gauge,
    Histogram,
    Timer,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: HashMap<String, MetricValue>,
    pub dimensions: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricValue {
    pub count: u64,
    pub sum: f64,
    pub min: f64,
    pub max: f64,
    pub avg: f64,
    pub p50: f64,
    pub p95: f64,
    pub p99: f64,
    pub last_value: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: HashMap<String, f64>,
    pub dimensions: HashMap<String, String>,
}

struct MetricState {
    values: Vec<f64>,
    last_value: f64,
    count: u64,
    sum: f64,
    min: f64,
    max: f64,
}

impl Default for MetricState {
    fn default() -> Self {
        Self {
            values: Vec::new(),
            last_value: 0.0,
            count: 0,
            sum: 0.0,
            min: f64::INFINITY,
            max: f64::NEG_INFINITY,
        }
    }
}

pub struct MetricsCollector {
    metrics: DashMap<String, MetricState>,
    dimensions: HashMap<String, String>,
    snapshot_interval: Duration,
    snapshot_tx: Option<mpsc::Sender<StatsSnapshot>>,
    shutdown_tx: Option<mpsc::Sender<()>>,
    listeners: RwLock<Vec<Arc<dyn Fn(StatsSnapshot) -> Result<()> + Send + Sync>>>,
}

impl MetricsCollector {
    pub fn new() -> Self {
        Self {
            metrics: DashMap::new(),
            dimensions: HashMap::new(),
            snapshot_interval: Duration::from_secs(60),
            snapshot_tx: None,
            shutdown_tx: None,
            listeners: RwLock::new(Vec::new()),
        }
    }

    pub fn with_dimension(mut self, key: String, value: String) -> Self {
        self.dimensions.insert(key, value);
        self
    }

    pub fn with_snapshot_interval(mut self, interval: Duration) -> Self {
        self.snapshot_interval = interval;
        self
    }

    pub fn register_listener<F>(&self, listener: F)
    where
        F: Fn(StatsSnapshot) -> Result<()> + Send + Sync + 'static,
    {
        self.listeners.write().push(Arc::new(listener));
    }

    pub fn record(&self, name: &str, value: f64, _metric_type: MetricType) {
        let mut state = self.metrics
            .entry(name.to_string())
            .or_insert_with(MetricState::default);
        
        state.values.push(value);
        state.last_value = value;
        state.count = state.count.saturating_add(1);
        state.sum += value;
        state.min = state.min.min(value);
        state.max = state.max.max(value);
        
        if state.values.len() > 10000 {
            state.values = state.values.split_off(state.values.len() - 5000);
        }
    }

    pub fn increment(&self, name: &str) {
        self.record(name, 1.0, MetricType::Counter);
    }

    pub fn increment_by(&self, name: &str, value: f64) {
        self.record(name, value, MetricType::Counter);
    }

    pub fn gauge(&self, name: &str, value: f64) {
        self.record(name, value, MetricType::Gauge);
    }

    pub fn timer<F, R>(&self, name: &str, f: F) -> R
    where
        F: FnOnce() -> R,
    {
        let start = Instant::now();
        let result = f();
        let duration = start.elapsed().as_secs_f64() * 1000.0;
        self.record(name, duration, MetricType::Timer);
        result
    }

    pub async fn time_async<F, R>(&self, name: &str, f: F) -> R
    where
        F: std::future::Future<Output = R>,
    {
        let start = Instant::now();
        let result = f.await;
        let duration = start.elapsed().as_secs_f64() * 1000.0;
        self.record(name, duration, MetricType::Timer);
        result
    }

    pub fn histogram(&self, name: &str, value: f64) {
        self.record(name, value, MetricType::Histogram);
    }

    pub fn get_metric(&self, name: &str) -> Option<MetricValue> {
        self.metrics.get(name).map(|state| Self::calculate_percentiles(&state))
    }

    fn calculate_percentiles(state: &MetricState) -> MetricValue {
        let mut sorted = state.values.clone();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        
        let count = state.count;
        let sum = state.sum;
        let avg = if count > 0 { sum / count as f64 } else { 0.0 };
        let min = if state.min.is_finite() { state.min } else { 0.0 };
        let max = if state.max.is_finite() { state.max } else { 0.0 };
        
        let p50 = Self::percentile(&sorted, 50.0);
        let p95 = Self::percentile(&sorted, 95.0);
        let p99 = Self::percentile(&sorted, 99.0);
        
        MetricValue {
            count,
            sum,
            min,
            max,
            avg,
            p50,
            p95,
            p99,
            last_value: state.last_value,
        }
    }

    fn percentile(sorted: &[f64], p: f64) -> f64 {
        if sorted.is_empty() {
            return 0.0;
        }
        
        let index = (p / 100.0 * (sorted.len() - 1) as f64).round() as usize;
        sorted[index.min(sorted.len() - 1)]
    }

    pub fn snapshot(&self) -> StatsSnapshot {
        let mut metrics = HashMap::new();
        
        for entry in self.metrics.iter() {
            let value = Self::calculate_percentiles(&entry.value());
            metrics.insert(entry.key().clone(), value.avg);
        }
        
        StatsSnapshot {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics,
            dimensions: self.dimensions.clone(),
        }
    }

    pub fn detailed_snapshot(&self) -> MetricSnapshot {
        let mut metrics = HashMap::new();
        
        for entry in self.metrics.iter() {
            let value = Self::calculate_percentiles(&entry.value());
            metrics.insert(entry.key().clone(), value);
        }
        
        MetricSnapshot {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics,
            dimensions: self.dimensions.clone(),
        }
    }

    pub async fn start_snapshot_collector(&mut self) -> Result<mpsc::Receiver<StatsSnapshot>> {
        let (tx, rx) = mpsc::channel::<StatsSnapshot>(100);
        let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);
        
        self.snapshot_tx = Some(tx.clone());
        self.shutdown_tx = Some(shutdown_tx);
        
        let interval = self.snapshot_interval;
        let metrics = self.metrics.clone();
        let dimensions = self.dimensions.clone();
        let listeners = self.listeners.clone();
        
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            
            loop {
                tokio::select! {
                    _ = ticker.tick() => {
                        let mut metric_values = HashMap::new();
                        
                        for entry in metrics.iter() {
                            let value = Self::calculate_percentiles(&entry.value());
                            metric_values.insert(entry.key().clone(), value.avg);
                        }
                        
                        let snapshot = StatsSnapshot {
                            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
                            timestamp: Utc::now(),
                            metrics: metric_values,
                            dimensions: dimensions.clone(),
                        };
                        
                        debug!("Generated metrics snapshot: {:?}", snapshot.snapshot_id);
                        
                        let listeners = listeners.read();
                        for listener in listeners.iter() {
                            let snapshot = snapshot.clone();
                            let listener = listener.clone();
                            tokio::spawn(async move {
                                if let Err(e) = listener(snapshot) {
                                    error!(error = %e, "Metrics snapshot listener failed");
                                }
                            });
                        }
                        
                        if tx.send(snapshot).await.is_err() {
                            warn!("Failed to send snapshot, receiver dropped");
                            break;
                        }
                    }
                    _ = shutdown_rx.recv() => {
                        info!("Metrics snapshot collector shutting down");
                        break;
                    }
                }
            }
        });
        
        Ok(rx)
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            drop(tx);
        }
    }

    pub fn reset(&self) {
        self.metrics.clear();
    }
}

impl Default for MetricsCollector {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for MetricsCollector {
    fn drop(&mut self) {
        self.stop();
    }
}

#[derive(Debug, Clone)]
pub struct TimerGuard<'a> {
    collector: &'a MetricsCollector,
    name: String,
    start: Instant,
}

impl<'a> TimerGuard<'a> {
    pub fn new(collector: &'a MetricsCollector, name: String) -> Self {
        Self {
            collector,
            name,
            start: Instant::now(),
        }
    }
}

impl<'a> Drop for TimerGuard<'a> {
    fn drop(&mut self) {
        let duration = self.start.elapsed().as_secs_f64() * 1000.0;
        self.collector.record(&self.name, duration, MetricType::Timer);
    }
}

#[macro_export]
macro_rules! measure_time {
    ($collector:expr, $name:expr) => {
        let _timer = $crate::monitoring::TimerGuard::new($collector, $name.to_string());
    };
}
