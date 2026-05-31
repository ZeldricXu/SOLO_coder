use crate::config::{AggregationFunction, EdgeAggregatorConfig};
use crate::error::SystemError;
use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::marker::PhantomData;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tracing::{debug, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPoint {
    pub id: Uuid,
    pub device_id: String,
    pub metric: String,
    pub value: f64,
    pub timestamp: DateTime<Utc>,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregatedData {
    pub window_start: DateTime<Utc>,
    pub window_end: DateTime<Utc>,
    pub device_id: String,
    pub metric: String,
    pub results: HashMap<String, f64>,
    pub sample_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationWindow<T> {
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub data_points: Vec<T>,
    pub aggregated: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregatorStats {
    pub total_points_received: u64,
    pub total_points_aggregated: u64,
    pub active_windows: usize,
    pub pending_uploads: usize,
    pub compression_ratio: f64,
}

#[derive(Debug, Clone, Default)]
struct AggregatorStatsInternal {
    total_points_received: u64,
    total_points_aggregated: u64,
    total_raw_bytes: u64,
    total_aggregated_bytes: u64,
}

pub trait AggregateFn: Send + Sync {
    fn name(&self) -> &str;
    fn aggregate(&self, values: &[f64]) -> f64;
}

pub struct SumAggregation;

impl AggregateFn for SumAggregation {
    fn name(&self) -> &str {
        "sum"
    }

    fn aggregate(&self, values: &[f64]) -> f64 {
        values.iter().sum()
    }
}

pub struct AvgAggregation;

impl AggregateFn for AvgAggregation {
    fn name(&self) -> &str {
        "avg"
    }

    fn aggregate(&self, values: &[f64]) -> f64 {
        if values.is_empty() {
            0.0
        } else {
            values.iter().sum::<f64>() / values.len() as f64
        }
    }
}

pub struct MinAggregation;

impl AggregateFn for MinAggregation {
    fn name(&self) -> &str {
        "min"
    }

    fn aggregate(&self, values: &[f64]) -> f64 {
        values.iter().cloned().fold(f64::INFINITY, f64::min)
    }
}

pub struct MaxAggregation;

impl AggregateFn for MaxAggregation {
    fn name(&self) -> &str {
        "max"
    }

    fn aggregate(&self, values: &[f64]) -> f64 {
        values.iter().cloned().fold(f64::NEG_INFINITY, f64::max)
    }
}

pub struct CountAggregation;

impl AggregateFn for CountAggregation {
    fn name(&self) -> &str {
        "count"
    }

    fn aggregate(&self, values: &[f64]) -> f64 {
        values.len() as f64
    }
}

pub struct StdDevAggregation;

impl AggregateFn for StdDevAggregation {
    fn name(&self) -> &str {
        "stddev"
    }

    fn aggregate(&self, values: &[f64]) -> f64 {
        if values.is_empty() {
            return 0.0;
        }
        let avg = values.iter().sum::<f64>() / values.len() as f64;
        let variance: f64 = values.iter().map(|v| (v - avg).powi(2)).sum::<f64>() / values.len() as f64;
        variance.sqrt()
    }
}

pub struct AggregationRegistry {
    functions: HashMap<String, Arc<dyn AggregateFn>>,
}

impl AggregationRegistry {
    pub fn new() -> Self {
        let mut registry = Self {
            functions: HashMap::new(),
        };
        registry.register_defaults();
        registry
    }

    fn register_defaults(&mut self) {
        self.register(Arc::new(SumAggregation));
        self.register(Arc::new(AvgAggregation));
        self.register(Arc::new(MinAggregation));
        self.register(Arc::new(MaxAggregation));
        self.register(Arc::new(CountAggregation));
        self.register(Arc::new(StdDevAggregation));
    }

    pub fn register(&mut self, function: Arc<dyn AggregateFn>) {
        self.functions.insert(function.name().to_string(), function);
    }

    pub fn get(&self, name: &str) -> Option<Arc<dyn AggregateFn>> {
        self.functions.get(name).cloned()
    }

    pub fn from_config_function(function: &AggregationFunction) -> &'static str {
        match function {
            AggregationFunction::Sum => "sum",
            AggregationFunction::Avg => "avg",
            AggregationFunction::Min => "min",
            AggregationFunction::Max => "max",
            AggregationFunction::Count => "count",
            AggregationFunction::StdDev => "stddev",
        }
    }
}

impl Default for AggregationRegistry {
    fn default() -> Self {
        Self::new()
    }
}

pub trait WindowPolicy<T>: Send + Sync {
    fn window_key(&self, point: &T) -> String;
    fn window_duration(&self) -> Duration;
    fn should_rotate(&self, window: &AggregationWindow<T>, now: DateTime<Utc>) -> bool;
    fn create_window(&self, point: &T, now: DateTime<Utc>) -> AggregationWindow<T>;
}

pub struct TimeBasedWindowPolicy {
    window_size: Duration,
}

impl TimeBasedWindowPolicy {
    pub fn new(window_size: Duration) -> Self {
        Self { window_size }
    }

    fn window_start(&self, timestamp: DateTime<Utc>) -> DateTime<Utc> {
        let timestamp_secs = timestamp.timestamp();
        let window_secs = self.window_size.num_seconds();
        let window_start_secs = (timestamp_secs / window_secs) * window_secs;
        DateTime::from_timestamp(window_start_secs, 0).unwrap_or(timestamp)
    }
}

impl WindowPolicy<DataPoint> for TimeBasedWindowPolicy {
    fn window_key(&self, point: &DataPoint) -> String {
        let start = self.window_start(point.timestamp);
        format!("{}:{}:{}", point.device_id, point.metric, start.timestamp())
    }

    fn window_duration(&self) -> Duration {
        self.window_size
    }

    fn should_rotate(&self, window: &AggregationWindow<DataPoint>, now: DateTime<Utc>) -> bool {
        window.end_time <= now && !window.data_points.is_empty()
    }

    fn create_window(&self, _point: &DataPoint, now: DateTime<Utc>) -> AggregationWindow<DataPoint> {
        let start = self.window_start(now);
        AggregationWindow {
            start_time: start,
            end_time: start + self.window_size,
            data_points: Vec::new(),
            aggregated: false,
        }
    }
}

pub trait Extractor<T>: Send + Sync {
    fn extract_value(&self, point: &T) -> f64;
    fn extract_key(&self, point: &T) -> String;
}

#[derive(Clone, Copy)]
pub struct DataPointExtractor;

impl Extractor<DataPoint> for DataPointExtractor {
    fn extract_value(&self, point: &DataPoint) -> f64 {
        point.value
    }

    fn extract_key(&self, point: &DataPoint) -> String {
        point.metric.clone()
    }
}

#[derive(Clone)]
pub struct AggregationRule {
    pub metric: String,
    pub function_name: String,
    pub output_field: String,
}

impl From<crate::config::AggregationRule> for AggregationRule {
    fn from(config: crate::config::AggregationRule) -> Self {
        Self {
            metric: config.metric,
            function_name: AggregationRegistry::from_config_function(&config.function).to_string(),
            output_field: config.output_field,
        }
    }
}

#[async_trait]
pub trait AggregationEngine<T>: Send + Sync {
    async fn ingest(&self, point: T) -> Result<(), SystemError>;
    async fn force_aggregate(&self) -> Result<Vec<AggregatedData>, SystemError>;
    async fn get_results(&self) -> Vec<AggregatedData>;
    async fn get_and_clear_results(&self) -> Vec<AggregatedData>;
    async fn get_stats(&self) -> Result<AggregatorStats, SystemError>;
}

pub struct GenericAggregator<T, E> {
    windows: Arc<DashMap<String, AggregationWindow<T>>>,
    aggregated_results: Arc<RwLock<Vec<AggregatedData>>>,
    window_policy: Arc<dyn WindowPolicy<T>>,
    extractor: E,
    aggregation_registry: Arc<AggregationRegistry>,
    rules: Vec<AggregationRule>,
    stats: Arc<RwLock<AggregatorStatsInternal>>,
    callbacks: Arc<RwLock<Vec<Arc<dyn Fn(AggregatedData) + Send + Sync>>>>,
    data_tx: mpsc::Sender<T>,
    phantom: PhantomData<fn() -> T>,
}

impl<T, E> GenericAggregator<T, E>
where
    T: Clone + Send + Sync + 'static,
    E: Extractor<T> + Clone + Send + Sync + 'static,
{
    pub fn new(
        window_policy: Arc<dyn WindowPolicy<T>>,
        extractor: E,
        aggregation_registry: Arc<AggregationRegistry>,
        rules: Vec<AggregationRule>,
        channel_size: usize,
    ) -> (Self, mpsc::Receiver<T>) {
        let (data_tx, data_rx) = mpsc::channel(channel_size);

        let aggregator = Self {
            windows: Arc::new(DashMap::new()),
            aggregated_results: Arc::new(RwLock::new(Vec::new())),
            window_policy,
            extractor,
            aggregation_registry,
            rules,
            stats: Arc::new(RwLock::new(AggregatorStatsInternal::default())),
            callbacks: Arc::new(RwLock::new(Vec::new())),
            data_tx,
            phantom: PhantomData,
        };

        (aggregator, data_rx)
    }

    pub fn start_data_ingestion(&self, mut rx: mpsc::Receiver<T>)
    where
        T: Clone + Send + 'static,
    {
        let windows = self.windows.clone();
        let window_policy = self.window_policy.clone();
        let stats = self.stats.clone();

        tokio::spawn(async move {
            while let Some(point) = rx.recv().await {
                {
                    let mut stats = stats.write().await;
                    stats.total_points_received += 1;
                    stats.total_raw_bytes += std::mem::size_of::<T>() as u64;
                }

                let key = window_policy.window_key(&point);
                let now = Utc::now();

                windows.entry(key).and_modify(|window| {
                    window.data_points.push(point.clone());
                }).or_insert_with(|| {
                    let mut window = window_policy.create_window(&point, now);
                    window.data_points.push(point);
                    window
                });
            }
        });
    }

    pub fn start_window_rotation(&self)
    where
        T: Clone + Send + Sync + 'static,
    {
        let windows = self.windows.clone();
        let aggregated_results = self.aggregated_results.clone();
        let window_policy = self.window_policy.clone();
        let rules = self.rules.clone();
        let callbacks = self.callbacks.clone();
        let stats = self.stats.clone();
        let registry = self.aggregation_registry.clone();
        let extractor = self.extractor.clone();

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(window_policy.window_duration().to_std().unwrap()).await;

                let now = Utc::now();
                let mut to_aggregate = Vec::new();

                for entry in windows.iter() {
                    if window_policy.should_rotate(entry.value(), now) {
                        to_aggregate.push(entry.key().clone());
                    }
                }

                for key in to_aggregate {
                    if let Some((_, window)) = windows.remove(&key) {
                        let rules = rules.clone();
                        let aggregated_results = aggregated_results.clone();
                        let callbacks = callbacks.clone();
                        let stats = stats.clone();
                        let registry = registry.clone();
                        let extractor = extractor.clone();

                        tokio::spawn(async move {
                            match Self::aggregate_window(&window, &rules, &registry, &extractor) {
                                Ok(aggregated) => {
                                    let mut stats = stats.write().await;
                                    stats.total_points_aggregated += aggregated.sample_count as u64;
                                    stats.total_aggregated_bytes += std::mem::size_of::<AggregatedData>() as u64;
                                    drop(stats);

                                    {
                                        let mut results = aggregated_results.write().await;
                                        results.push(aggregated.clone());
                                    }

                                    let cbs = callbacks.read().await;
                                    for cb in cbs.iter() {
                                        cb(aggregated.clone());
                                    }
                                }
                                Err(e) => {
                                    warn!("聚合窗口失败: {}", e);
                                }
                            }
                        });
                    }
                }
            }
        });
    }

    fn aggregate_window(
        window: &AggregationWindow<T>,
        rules: &[AggregationRule],
        registry: &AggregationRegistry,
        extractor: &E,
    ) -> Result<AggregatedData, SystemError> {
        if window.data_points.is_empty() {
            return Err(SystemError::AggregationError("空窗口".to_string()));
        }

        let first_point = &window.data_points[0];
        let key = extractor.extract_key(first_point);
        let device_id = "".to_string();

        let values: Vec<f64> = window.data_points.iter().map(|p| extractor.extract_value(p)).collect();

        let mut results = HashMap::new();
        let mut has_matching_rule = false;

        for rule in rules {
            if rule.metric == key {
                has_matching_rule = true;
                if let Some(function) = registry.get(&rule.function_name) {
                    let value = function.aggregate(&values);
                    results.insert(rule.output_field.clone(), value);
                }
            }
        }

        if !has_matching_rule {
            if let Some(avg_fn) = registry.get("avg") {
                results.insert("avg".to_string(), avg_fn.aggregate(&values));
            }
            if let Some(min_fn) = registry.get("min") {
                results.insert("min".to_string(), min_fn.aggregate(&values));
            }
            if let Some(max_fn) = registry.get("max") {
                results.insert("max".to_string(), max_fn.aggregate(&values));
            }
            if let Some(count_fn) = registry.get("count") {
                results.insert("count".to_string(), count_fn.aggregate(&values));
            }
            if let Some(sum_fn) = registry.get("sum") {
                results.insert("sum".to_string(), sum_fn.aggregate(&values));
            }
        }

        Ok(AggregatedData {
            window_start: window.start_time,
            window_end: window.end_time,
            device_id,
            metric: key,
            results,
            sample_count: values.len(),
        })
    }

    pub async fn ingest(&self, point: T) -> Result<(), SystemError> {
        self.data_tx
            .send(point)
            .await
            .map_err(|e| SystemError::AggregationError(format!("数据接收失败: {}", e)))?;
        Ok(())
    }

    pub async fn force_aggregate_all(&self) -> Result<Vec<AggregatedData>, SystemError>
    where
        T: Clone,
    {
        let mut results = Vec::new();
        let windows: Vec<AggregationWindow<T>> = self.windows.iter().map(|w| w.clone()).collect();

        for window in windows {
            if !window.data_points.is_empty() {
                let aggregated = Self::aggregate_window(
                    &window,
                    &self.rules,
                    &self.aggregation_registry,
                    &self.extractor,
                )?;
                results.push(aggregated);
            }
        }

        Ok(results)
    }

    pub async fn get_results(&self) -> Vec<AggregatedData> {
        self.aggregated_results.read().await.clone()
    }

    pub async fn get_and_clear_results(&self) -> Vec<AggregatedData> {
        let mut results = self.aggregated_results.write().await;
        std::mem::take(&mut *results)
    }

    pub async fn get_stats_internal(&self) -> Result<AggregatorStats, SystemError> {
        let stats = self.stats.read().await;
        let compression_ratio = if stats.total_raw_bytes > 0 {
            stats.total_raw_bytes as f64 / stats.total_aggregated_bytes.max(1) as f64
        } else {
            1.0
        };

        Ok(AggregatorStats {
            total_points_received: stats.total_points_received,
            total_points_aggregated: stats.total_points_aggregated,
            active_windows: self.windows.len(),
            pending_uploads: self.aggregated_results.read().await.len(),
            compression_ratio,
        })
    }

    pub async fn register_callback<F>(&self, callback: F)
    where
        F: Fn(AggregatedData) + Send + Sync + 'static,
    {
        let mut callbacks = self.callbacks.write().await;
        callbacks.push(Arc::new(callback));
    }

    pub async fn clear_old_results(&self, max_age: Duration) -> Result<usize, SystemError> {
        let cutoff = Utc::now() - max_age;
        let mut results = self.aggregated_results.write().await;
        let original_len = results.len();
        results.retain(|r| r.window_end > cutoff);
        Ok(original_len - results.len())
    }
}

pub struct EdgeAggregator {
    engine: GenericAggregator<DataPoint, DataPointExtractor>,
}

impl EdgeAggregator {
    pub fn new(config: &EdgeAggregatorConfig) -> Result<Self, SystemError> {
        let window_policy = Arc::new(TimeBasedWindowPolicy::new(
            chrono::Duration::from_std(config.window_size()).unwrap(),
        ));

        let extractor = DataPointExtractor;
        let aggregation_registry = Arc::new(AggregationRegistry::new());

        let rules: Vec<AggregationRule> = config
            .aggregation_rules
            .iter()
            .cloned()
            .map(AggregationRule::from)
            .collect();

        let (engine, data_rx) = GenericAggregator::new(
            window_policy,
            extractor,
            aggregation_registry,
            rules,
            10000,
        );

        engine.start_data_ingestion(data_rx);
        engine.start_window_rotation();

        Ok(Self { engine })
    }

    pub async fn ingest(&self, data_point: DataPoint) -> Result<(), SystemError> {
        self.engine.ingest(data_point).await
    }

    pub async fn ingest_batch(&self, data_points: Vec<DataPoint>) -> Result<(), SystemError> {
        for point in data_points {
            self.ingest(point).await?;
        }
        Ok(())
    }

    pub async fn get_aggregated_results(&self) -> Vec<AggregatedData> {
        self.engine.get_results().await
    }

    pub async fn get_and_clear_results(&self) -> Vec<AggregatedData> {
        self.engine.get_and_clear_results().await
    }

    pub async fn get_stats(&self) -> Result<AggregatorStats, SystemError> {
        self.engine.get_stats_internal().await
    }

    pub async fn register_callback<F>(&self, callback: F)
    where
        F: Fn(AggregatedData) + Send + Sync + 'static,
    {
        self.engine.register_callback(callback).await
    }

    pub async fn force_aggregate_all(&self) -> Result<Vec<AggregatedData>, SystemError> {
        self.engine.force_aggregate_all().await
    }

    pub async fn clear_old_results(&self, max_age: Duration) -> Result<usize, SystemError> {
        self.engine.clear_old_results(max_age).await
    }
}

impl Clone for EdgeAggregator {
    fn clone(&self) -> Self {
        Self {
            engine: GenericAggregator {
                windows: self.engine.windows.clone(),
                aggregated_results: self.engine.aggregated_results.clone(),
                window_policy: self.engine.window_policy.clone(),
                extractor: DataPointExtractor,
                aggregation_registry: self.engine.aggregation_registry.clone(),
                rules: self.engine.rules.clone(),
                stats: self.engine.stats.clone(),
                callbacks: self.engine.callbacks.clone(),
                data_tx: self.engine.data_tx.clone(),
                phantom: PhantomData,
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{AggregationRule, AggregationFunction};

    #[test]
    fn test_aggregation_functions() {
        let values = vec![1.0, 2.0, 3.0, 4.0, 5.0];

        let sum = SumAggregation;
        assert_eq!(sum.aggregate(&values), 15.0);

        let avg = AvgAggregation;
        assert_eq!(avg.aggregate(&values), 3.0);

        let min = MinAggregation;
        assert_eq!(min.aggregate(&values), 1.0);

        let max = MaxAggregation;
        assert_eq!(max.aggregate(&values), 5.0);

        let count = CountAggregation;
        assert_eq!(count.aggregate(&values), 5.0);
    }

    #[tokio::test]
    async fn test_edge_aggregator() {
        let config = EdgeAggregatorConfig {
            window_size_secs: 1,
            max_batch_size: 100,
            aggregation_rules: vec![
                AggregationRule {
                    metric: "temperature".to_string(),
                    function: AggregationFunction::Avg,
                    output_field: "temperature_avg".to_string(),
                },
            ],
        };

        let aggregator = EdgeAggregator::new(&config).unwrap();

        for i in 0..10 {
            let point = DataPoint {
                id: Uuid::new_v4(),
                device_id: "sensor001".to_string(),
                metric: "temperature".to_string(),
                value: 20.0 + i as f64,
                timestamp: Utc::now(),
                tags: HashMap::new(),
            };
            aggregator.ingest(point).await.unwrap();
        }

        tokio::time::sleep(std::time::Duration::from_millis(1500)).await;

        let results = aggregator.get_aggregated_results().await;
        assert!(results.len() > 0);
    }
}
