use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::time::{interval, Duration as TokioDuration};

use anyhow::Result;
use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use std::collections::VecDeque;
use tracing::{debug, info, warn};

use common::alert::{AlertSeverity, DetectionMethod};
use common::metrics::TimeSeries;

#[derive(Debug, Clone)]
pub struct DetectionResult {
    pub is_anomaly: bool,
    pub score: f64,
    pub details: String,
    pub suggested_severity: AlertSeverity,
    pub affected_metrics: Vec<String>,
}

impl DetectionResult {
    pub fn normal() -> Self {
        Self {
            is_anomaly: false,
            score: 0.0,
            details: "Normal".to_string(),
            suggested_severity: AlertSeverity::Info,
            affected_metrics: Vec::new(),
        }
    }

    pub fn anomaly(score: f64, details: String, severity: AlertSeverity) -> Self {
        Self {
            is_anomaly: true,
            score,
            details,
            suggested_severity: severity,
            affected_metrics: Vec::new(),
        }
    }

    pub fn with_metrics(mut self, metrics: Vec<String>) -> Self {
        self.affected_metrics = metrics;
        self
    }
}

#[async_trait]
pub trait Detector: Send + Sync {
    fn name(&self) -> &str;

    async fn detect(&mut self, series: &[TimeSeries]) -> Result<DetectionResult>;

    fn detection_method(&self) -> DetectionMethod;
}

#[async_trait]
pub trait SingleSeriesDetector: Detector {
    async fn detect_single(&mut self, series: &TimeSeries) -> Result<DetectionResult>;
}

pub trait MultiSeriesDetector: Detector {}

pub struct StaticThresholdDetector {
    name: String,
    threshold: f64,
    direction: ThresholdDirection,
    severity: AlertSeverity,
}

#[derive(Debug, Clone, Copy)]
pub enum ThresholdDirection {
    Above,
    Below,
    Either,
}

impl StaticThresholdDetector {
    pub fn new(
        name: String,
        threshold: f64,
        direction: ThresholdDirection,
        severity: AlertSeverity,
    ) -> Self {
        Self {
            name,
            threshold,
            direction,
            severity,
        }
    }
}

#[async_trait]
impl Detector for StaticThresholdDetector {
    fn name(&self) -> &str {
        &self.name
    }

    async fn detect(&mut self, series_list: &[TimeSeries]) -> Result<DetectionResult> {
        if series_list.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let mut all_normal = true;
        let mut max_score: f64 = 0.0;
        let mut all_details = Vec::new();
        let mut affected_metrics = Vec::new();

        for series in series_list {
            let result = self.detect_single(series).await?;
            if result.is_anomaly {
                all_normal = false;
                max_score = max_score.max(result.score);
                all_details.push(result.details);
                affected_metrics.push(series.metric_name.clone());
            }
        }

        if all_normal {
            Ok(DetectionResult::normal())
        } else {
            Ok(DetectionResult::anomaly(
                max_score,
                all_details.join("; "),
                self.severity.clone(),
            )
            .with_metrics(affected_metrics))
        }
    }

    fn detection_method(&self) -> DetectionMethod {
        DetectionMethod::StaticThreshold
    }
}

#[async_trait]
impl SingleSeriesDetector for StaticThresholdDetector {
    async fn detect_single(&mut self, series: &TimeSeries) -> Result<DetectionResult> {
        if series.points.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let latest_value = series.points.last().unwrap().value;
        let is_anomaly = match self.direction {
            ThresholdDirection::Above => latest_value > self.threshold,
            ThresholdDirection::Below => latest_value < self.threshold,
            ThresholdDirection::Either => (latest_value - self.threshold).abs() > self.threshold * 0.5,
        };

        if is_anomaly {
            Ok(DetectionResult::anomaly(
                1.0,
                format!(
                    "Value {} exceeded threshold {}",
                    latest_value, self.threshold
                ),
                self.severity.clone(),
            )
            .with_metrics(vec![series.metric_name.clone()]))
        } else {
            Ok(DetectionResult::normal())
        }
    }
}

pub struct MovingAverageDetector {
    name: String,
    window_size: usize,
    std_dev_multiplier: f64,
    history: VecDeque<f64>,
    severity: AlertSeverity,
}

impl MovingAverageDetector {
    pub fn new(name: String, window_size: usize, std_dev_multiplier: f64, severity: AlertSeverity) -> Self {
        Self {
            name,
            window_size,
            std_dev_multiplier,
            history: VecDeque::with_capacity(window_size),
            severity,
        }
    }

    fn mean(&self) -> f64 {
        if self.history.is_empty() {
            return 0.0;
        }
        self.history.iter().sum::<f64>() / self.history.len() as f64
    }

    fn std_dev(&self) -> f64 {
        if self.history.len() < 2 {
            return 0.0;
        }
        let mean = self.mean();
        let variance = self
            .history
            .iter()
            .map(|x| (x - mean).powi(2))
            .sum::<f64>()
            / (self.history.len() - 1) as f64;
        variance.sqrt()
    }
}

#[async_trait]
impl Detector for MovingAverageDetector {
    fn name(&self) -> &str {
        &self.name
    }

    async fn detect(&mut self, series_list: &[TimeSeries]) -> Result<DetectionResult> {
        if series_list.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let mut all_normal = true;
        let mut max_score: f64 = 0.0;
        let mut all_details = Vec::new();
        let mut affected_metrics = Vec::new();

        for series in series_list {
            let result = self.detect_single(series).await?;
            if result.is_anomaly {
                all_normal = false;
                max_score = max_score.max(result.score);
                all_details.push(result.details);
                affected_metrics.push(series.metric_name.clone());
            }
        }

        if all_normal {
            Ok(DetectionResult::normal())
        } else {
            Ok(DetectionResult::anomaly(
                max_score,
                all_details.join("; "),
                self.severity.clone(),
            )
            .with_metrics(affected_metrics))
        }
    }

    fn detection_method(&self) -> DetectionMethod {
        DetectionMethod::MovingAverage
    }
}

#[async_trait]
impl SingleSeriesDetector for MovingAverageDetector {
    async fn detect_single(&mut self, series: &TimeSeries) -> Result<DetectionResult> {
        if series.points.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let latest_value = series.points.last().unwrap().value;

        if self.history.len() >= self.window_size {
            let mean = self.mean();
            let std_dev = self.std_dev();
            let threshold = mean + self.std_dev_multiplier * std_dev;

            debug!(
                "MA Detector: value={}, mean={}, std_dev={}, threshold={}",
                latest_value, mean, std_dev, threshold
            );

            if latest_value > threshold && std_dev > 0.0 {
                let z_score = (latest_value - mean) / std_dev;
                let score = (z_score / self.std_dev_multiplier).min(1.0);

                self.history.push_back(latest_value);
                self.history.pop_front();

                return Ok(DetectionResult::anomaly(
                    score,
                    format!(
                        "Spike detected: value={}, mean={}, z-score={:.2}",
                        latest_value, mean, z_score
                    ),
                    self.severity.clone(),
                )
                .with_metrics(vec![series.metric_name.clone()]));
            }
        }

        self.history.push_back(latest_value);
        if self.history.len() > self.window_size {
            self.history.pop_front();
        }

        Ok(DetectionResult::normal())
    }
}

#[derive(Debug, Clone)]
struct DbscanParams {
    eps: f64,
    min_points: usize,
}

pub struct DbscanDetector {
    name: String,
    params: Arc<RwLock<DbscanParams>>,
    severity: AlertSeverity,
    history: VecDeque<(DateTime<Utc>, f64)>,
    auto_tuning_enabled: bool,
    last_tuning_time: Option<DateTime<Utc>>,
}

impl DbscanDetector {
    pub fn new(name: String, eps: f64, min_points: usize, severity: AlertSeverity) -> Self {
        Self {
            name,
            params: Arc::new(RwLock::new(DbscanParams { eps, min_points })),
            severity,
            history: VecDeque::with_capacity(100000),
            auto_tuning_enabled: true,
            last_tuning_time: None,
        }
    }

    pub fn with_auto_tuning(mut self, enabled: bool) -> Self {
        self.auto_tuning_enabled = enabled;
        self
    }

    fn find_neighbors(points: &[f64], idx: usize, eps: f64) -> Vec<usize> {
        let mut neighbors = Vec::new();
        for (i, &p) in points.iter().enumerate() {
            if i != idx && (p - points[idx]).abs() <= eps {
                neighbors.push(i);
            }
        }
        neighbors
    }

    fn add_to_history(&mut self, series: &TimeSeries) {
        let cutoff = Utc::now() - Duration::hours(24);
        while self.history.front().map_or(false, |&(t, _)| t < cutoff) {
            self.history.pop_front();
        }

        for point in &series.points {
            self.history.push_back((point.timestamp, point.value));
        }

        if self.history.len() > 100000 {
            let excess = self.history.len() - 100000;
            for _ in 0..excess {
                self.history.pop_front();
            }
        }
    }

    pub async fn maybe_tune_params(&mut self) -> Result<()> {
        if !self.auto_tuning_enabled {
            return Ok(());
        }

        let now = Utc::now();
        let need_tuning = match self.last_tuning_time {
            None => true,
            Some(last) => now - last > Duration::hours(6),
        };

        if !need_tuning {
            return Ok(());
        }

        if self.history.len() < 100 {
            debug!(
                "Not enough history for DBSCAN tuning: {} points, need at least 100",
                self.history.len()
            );
            return Ok(());
        }

        let sample_size = std::cmp::min(self.history.len(), 10000);
        let step = self.history.len() / sample_size;
        let mut sample: Vec<f64> = self
            .history
            .iter()
            .step_by(step)
            .take(sample_size)
            .map(|&(_, v)| v)
            .collect();

        if sample.len() < 100 {
            debug!("Not enough samples after sampling: {}", sample.len());
            return Ok(());
        }

        let new_eps = Self::estimate_eps(&sample, self.params.read().await.min_points);

        if new_eps <= 0.0 || !new_eps.is_finite() {
            warn!("Invalid estimated eps: {}, skipping tuning", new_eps);
            return Ok(());
        }

        let current_eps = self.params.read().await.eps;
        let change_ratio = (new_eps - current_eps).abs() / current_eps.max(1e-9);

        if change_ratio < 0.1 {
            debug!(
                "DBSCAN eps change too small: {:.4} -> {:.4} (change {:.2}%), skipping",
                current_eps, new_eps, change_ratio * 100.0
            );
            return Ok(());
        }

        info!(
            "Tuning DBSCAN params: eps={:.4} -> {:.4}, min_points={}",
            current_eps, new_eps, self.params.read().await.min_points
        );

        {
            let mut params = self.params.write().await;
            params.eps = new_eps;
        }

        self.last_tuning_time = Some(now);
        metrics::gauge!("dbscan_eps", "detector" => self.name.clone()).set(new_eps);

        Ok(())
    }

    fn estimate_eps(sample: &[f64], min_points: usize) -> f64 {
        use rand::seq::SliceRandom;
        use rand::thread_rng;

        let mut rng = thread_rng();
        let mut distances: Vec<f64> = sample
            .choose_multiple(&mut rng, sample.len().min(2000))
            .map(|&v| v)
            .collect();
        distances.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        let k = min_points.min(distances.len().saturating_sub(1));
        if k == 0 || distances.len() < 2 {
            return 0.0;
        }

        let mut k_distances = Vec::with_capacity(distances.len());
        for i in 0..distances.len() {
            let mut d: Vec<f64> = distances
                .iter()
                .map(|&v| (v - distances[i]).abs())
                .collect();
            d.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

            if k < d.len() {
                k_distances.push(d[k]);
            }
        }

        k_distances.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        let mut max_diff = 0.0;
        let mut elbow_idx = k_distances.len() * 90 / 100;

        for i in 1..k_distances.len() {
            let diff = k_distances[i] - k_distances[i - 1];
            if diff > max_diff {
                max_diff = diff;
                elbow_idx = i;
            }
        }

        let eps = if elbow_idx < k_distances.len() {
            k_distances[elbow_idx]
        } else {
            k_distances.last().copied().unwrap_or(0.0)
        };

        eps.max(1e-6)
    }

    pub fn spawn_auto_tuning(&mut self) {
        if !self.auto_tuning_enabled {
            return;
        }

        let params_clone = self.params.clone();
        let name_clone = self.name.clone();
        let history_clone = Arc::new(RwLock::new(self.history.clone()));

        tokio::spawn(async move {
            let mut interval = interval(TokioDuration::from_secs(6 * 3600));
            loop {
                interval.tick().await;

                let history = history_clone.read().await;
                if history.len() < 100 {
                    continue;
                }

                let sample_size = std::cmp::min(history.len(), 10000);
                let step = history.len() / sample_size;
                let sample: Vec<f64> = history
                    .iter()
                    .step_by(step)
                    .take(sample_size)
                    .map(|&(_, v)| v)
                    .collect();

                let current_min_points = params_clone.read().await.min_points;
                let new_eps = Self::estimate_eps(&sample, current_min_points);

                if new_eps <= 0.0 || !new_eps.is_finite() {
                    warn!("Invalid estimated eps for {}: {}", name_clone, new_eps);
                    continue;
                }

                let current_eps = params_clone.read().await.eps;
                let change_ratio = (new_eps - current_eps).abs() / current_eps.max(1e-9);

                if change_ratio >= 0.1 {
                    info!(
                        "Auto-tuning DBSCAN[{}]: eps={:.4} -> {:.4}",
                        name_clone, current_eps, new_eps
                    );

                    let mut params = params_clone.write().await;
                    params.eps = new_eps;

                    metrics::gauge!("dbscan_eps", "detector" => name_clone.clone())
                        .set(new_eps);
                }
            }
        });
    }
}

#[async_trait]
impl Detector for DbscanDetector {
    fn name(&self) -> &str {
        &self.name
    }

    async fn detect(&mut self, series_list: &[TimeSeries]) -> Result<DetectionResult> {
        if series_list.is_empty() {
            return Ok(DetectionResult::normal());
        }

        for series in series_list {
            self.add_to_history(series);
        }

        let _ = self.maybe_tune_params().await;

        let mut all_normal = true;
        let mut max_score: f64 = 0.0;
        let mut all_details = Vec::new();
        let mut affected_metrics = Vec::new();
        let params_snapshot = self.params.read().await.clone();

        for series in series_list {
            let result = self.detect_single_with_params(series, &params_snapshot).await?;
            if result.is_anomaly {
                all_normal = false;
                max_score = max_score.max(result.score);
                all_details.push(result.details);
                affected_metrics.push(series.metric_name.clone());
            }
        }

        if all_normal {
            Ok(DetectionResult::normal())
        } else {
            Ok(DetectionResult::anomaly(
                max_score,
                all_details.join("; "),
                self.severity.clone(),
            )
            .with_metrics(affected_metrics))
        }
    }

    fn detection_method(&self) -> DetectionMethod {
        DetectionMethod::Dbscan
    }
}

#[async_trait]
impl SingleSeriesDetector for DbscanDetector {
    async fn detect_single(&mut self, series: &TimeSeries) -> Result<DetectionResult> {
        let params = self.params.read().await.clone();
        self.detect_single_with_params(series, &params).await
    }
}

impl DbscanDetector {
    async fn detect_single_with_params(
        &self,
        series: &TimeSeries,
        params: &DbscanParams,
    ) -> Result<DetectionResult> {
        if series.points.len() < params.min_points {
            return Ok(DetectionResult::normal());
        }

        let values: Vec<f64> = series.points.iter().map(|p| p.value).collect();
        let latest_idx = values.len() - 1;
        let neighbors = Self::find_neighbors(&values, latest_idx, params.eps);

        if neighbors.len() < params.min_points {
            let avg_neighbors = if neighbors.is_empty() {
                0.0
            } else {
                neighbors.iter().map(|&i| values[i]).sum::<f64>() / neighbors.len() as f64
            };

            Ok(DetectionResult::anomaly(
                0.8,
                format!(
                    "Outlier detected: value={}, avg_neighbors={:.2}",
                    values[latest_idx], avg_neighbors
                ),
                self.severity.clone(),
            )
            .with_metrics(vec![series.metric_name.clone()]))
        } else {
            Ok(DetectionResult::normal())
        }
    }
}

pub struct SeasonalDetector {
    name: String,
    period_hours: i64,
    history: Vec<(DateTime<Utc>, f64)>,
    threshold_multiplier: f64,
    severity: AlertSeverity,
}

impl SeasonalDetector {
    pub fn new(name: String, period_hours: i64, threshold_multiplier: f64, severity: AlertSeverity) -> Self {
        Self {
            name,
            period_hours,
            history: Vec::new(),
            threshold_multiplier,
            severity,
        }
    }

    fn get_historical_comparison(&self, timestamp: DateTime<Utc>, _value: f64) -> Option<(f64, f64)> {
        let period = Duration::hours(self.period_hours);
        let target_time = timestamp - period;

        let mut comparisons = Vec::new();
        for &(ts, val) in &self.history {
            let diff = (ts - target_time).num_minutes().abs();
            if diff < 30 {
                comparisons.push((val, diff as f64));
            }
        }

        if comparisons.is_empty() {
            return None;
        }

        let total_weight: f64 = comparisons.iter().map(|(_, w)| 1.0 / (w + 1.0)).sum();
        let weighted_avg: f64 = comparisons
            .iter()
            .map(|(v, w)| v * (1.0 / (w + 1.0)))
            .sum::<f64>()
            / total_weight;

        let variance: f64 = comparisons
            .iter()
            .map(|(v, _)| (v - weighted_avg).powi(2))
            .sum::<f64>()
            / comparisons.len() as f64;

        Some((weighted_avg, variance.sqrt()))
    }
}

#[async_trait]
impl Detector for SeasonalDetector {
    fn name(&self) -> &str {
        &self.name
    }

    async fn detect(&mut self, series_list: &[TimeSeries]) -> Result<DetectionResult> {
        if series_list.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let mut all_normal = true;
        let mut max_score: f64 = 0.0;
        let mut all_details = Vec::new();
        let mut affected_metrics = Vec::new();

        for series in series_list {
            let result = self.detect_single(series).await?;
            if result.is_anomaly {
                all_normal = false;
                max_score = max_score.max(result.score);
                all_details.push(result.details);
                affected_metrics.push(series.metric_name.clone());
            }
        }

        if all_normal {
            Ok(DetectionResult::normal())
        } else {
            Ok(DetectionResult::anomaly(
                max_score,
                all_details.join("; "),
                self.severity.clone(),
            )
            .with_metrics(affected_metrics))
        }
    }

    fn detection_method(&self) -> DetectionMethod {
        DetectionMethod::SeasonalComparison
    }
}

#[async_trait]
impl SingleSeriesDetector for SeasonalDetector {
    async fn detect_single(&mut self, series: &TimeSeries) -> Result<DetectionResult> {
        if series.points.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let latest = series.points.last().unwrap();

        if let Some((historical_avg, historical_std)) =
            self.get_historical_comparison(latest.timestamp, latest.value)
        {
            let deviation = if historical_std > 0.0 {
                (latest.value - historical_avg).abs() / historical_std
            } else {
                0.0
            };

            if deviation > self.threshold_multiplier && historical_std > 0.0 {
                self.history.push((latest.timestamp, latest.value));

                return Ok(DetectionResult::anomaly(
                    (deviation / self.threshold_multiplier).min(1.0),
                    format!(
                        "Seasonal anomaly: current={:.2}, historical_avg={:.2}, deviation={:.2}σ",
                        latest.value, historical_avg, deviation
                    ),
                    self.severity.clone(),
                )
                .with_metrics(vec![series.metric_name.clone()]));
            }
        }

        self.history.push((latest.timestamp, latest.value));

        if self.history.len() > 1000 {
            self.history.drain(0..self.history.len() - 1000);
        }

        Ok(DetectionResult::normal())
    }
}

fn pearson_correlation(x: &[f64], y: &[f64]) -> f64 {
    let n = x.len().min(y.len()) as f64;
    if n < 2.0 {
        return 0.0;
    }

    let mean_x: f64 = x.iter().sum::<f64>() / n;
    let mean_y: f64 = y.iter().sum::<f64>() / n;

    let mut cov = 0.0;
    let mut var_x = 0.0;
    let mut var_y = 0.0;

    for i in 0..n as usize {
        let dx = x[i] - mean_x;
        let dy = y[i] - mean_y;
        cov += dx * dy;
        var_x += dx * dx;
        var_y += dy * dy;
    }

    let denom = var_x.sqrt() * var_y.sqrt();
    if denom == 0.0 {
        return 0.0;
    }

    cov / denom
}

pub struct CorrelationDetector {
    name: String,
    correlation_threshold: f64,
    window_size: usize,
    severity: AlertSeverity,
    anomaly_threshold: f64,
}

impl CorrelationDetector {
    pub fn new(
        name: String,
        correlation_threshold: f64,
        window_size: usize,
        severity: AlertSeverity,
    ) -> Self {
        Self {
            name,
            correlation_threshold,
            window_size,
            severity,
            anomaly_threshold: 2.0,
        }
    }

    pub fn with_anomaly_threshold(mut self, threshold: f64) -> Self {
        self.anomaly_threshold = threshold;
        self
    }

    fn is_series_anomalous(&self, series: &TimeSeries) -> bool {
        if series.points.len() < 3 {
            return false;
        }

        let window: Vec<f64> = series
            .points
            .iter()
            .rev()
            .take(self.window_size)
            .map(|p| p.value)
            .collect();

        if window.len() < 3 {
            return false;
        }

        let mean: f64 = window.iter().sum::<f64>() / window.len() as f64;
        let variance: f64 =
            window.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / (window.len() - 1) as f64;
        let std_dev = variance.sqrt();

        if std_dev == 0.0 {
            return false;
        }

        let latest = series.points.last().unwrap().value;
        let z_score = (latest - mean) / std_dev;

        z_score.abs() > self.anomaly_threshold
    }
}

#[async_trait]
impl Detector for CorrelationDetector {
    fn name(&self) -> &str {
        &self.name
    }

    async fn detect(&mut self, series_list: &[TimeSeries]) -> Result<DetectionResult> {
        if series_list.len() < 2 {
            return Ok(DetectionResult::normal());
        }

        let mut correlation_pairs = Vec::new();

        for i in 0..series_list.len() {
            for j in (i + 1)..series_list.len() {
                let a = &series_list[i];
                let b = &series_list[j];

                let a_values: Vec<f64> = a
                    .points
                    .iter()
                    .rev()
                    .take(self.window_size)
                    .map(|p| p.value)
                    .collect();
                let b_values: Vec<f64> = b
                    .points
                    .iter()
                    .rev()
                    .take(self.window_size)
                    .map(|p| p.value)
                    .collect();

                let min_len = a_values.len().min(b_values.len());
                if min_len < 3 {
                    continue;
                }

                let a_window = &a_values[..min_len];
                let b_window = &b_values[..min_len];

                let r = pearson_correlation(a_window, b_window);

                debug!(
                    "Correlation between {} and {}: r={:.3}",
                    a.metric_name, b.metric_name, r
                );

                if r.abs() > self.correlation_threshold {
                    let a_anomalous = self.is_series_anomalous(a);
                    let b_anomalous = self.is_series_anomalous(b);

                    if a_anomalous && b_anomalous {
                        correlation_pairs.push((
                            a.metric_name.clone(),
                            b.metric_name.clone(),
                            r,
                        ));
                    }
                }
            }
        }

        if correlation_pairs.is_empty() {
            return Ok(DetectionResult::normal());
        }

        let max_r = correlation_pairs
            .iter()
            .map(|(_, _, r)| r.abs())
            .fold(0.0_f64, f64::max);

        let details = correlation_pairs
            .iter()
            .map(|(a, b, r)| format!("{} <-> {} (r={:.3})", a, b, r))
            .collect::<Vec<_>>()
            .join("; ");

        let mut affected_metrics = Vec::new();
        for (a, b, _) in &correlation_pairs {
            affected_metrics.push(a.clone());
            affected_metrics.push(b.clone());
        }
        affected_metrics.dedup();

        Ok(DetectionResult::anomaly(
            max_r,
            format!("Correlated anomalies detected: {}", details),
            self.severity.clone(),
        )
        .with_metrics(affected_metrics))
    }

    fn detection_method(&self) -> DetectionMethod {
        DetectionMethod::Correlation
    }
}

impl MultiSeriesDetector for CorrelationDetector {}

#[cfg(test)]
mod tests {
    use super::*;
    use common::metrics::{Label, Labels, MetricPoint};

    #[tokio::test]
    async fn test_static_threshold() {
        let mut detector = StaticThresholdDetector::new(
            "test".to_string(),
            100.0,
            ThresholdDirection::Above,
            AlertSeverity::Warning,
        );

        let mut series = TimeSeries::new("test".to_string(), Labels::new());
        series.add_point(Utc::now(), 50.0);

        let result = detector.detect(&[series.clone()]).await.unwrap();
        assert!(!result.is_anomaly);

        series.add_point(Utc::now(), 150.0);
        let result = detector.detect(&[series]).await.unwrap();
        assert!(result.is_anomaly);
    }

    #[tokio::test]
    async fn test_moving_average() {
        let mut detector = MovingAverageDetector::new("test".to_string(), 5, 2.0, AlertSeverity::Warning);

        let base_time = Utc::now();

        for i in 0..10 {
            let mut series = TimeSeries::new("test".to_string(), Labels::new());
            series.add_point(base_time + Duration::seconds(i), 10.0 + i as f64);
            let result = detector.detect(&[series]).await.unwrap();
            if i < 5 {
                assert!(!result.is_anomaly, "Should not detect anomaly for first {} points", i);
            }
        }

        let mut series = TimeSeries::new("test".to_string(), Labels::new());
        series.add_point(base_time + Duration::seconds(10), 100.0);
        let result = detector.detect(&[series]).await.unwrap();
        assert!(result.is_anomaly, "Should detect anomaly for spike value");
    }

    #[test]
    fn test_pearson_correlation() {
        let x = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let y = vec![2.0, 4.0, 6.0, 8.0, 10.0];
        let r = pearson_correlation(&x, &y);
        assert!((r - 1.0).abs() < 0.001);

        let x = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let y = vec![5.0, 4.0, 3.0, 2.0, 1.0];
        let r = pearson_correlation(&x, &y);
        assert!((r - (-1.0)).abs() < 0.001);
    }

    #[tokio::test]
    async fn test_correlation_detector() {
        let mut detector = CorrelationDetector::new(
            "error-cpu-correlation".to_string(),
            0.7,
            10,
            AlertSeverity::Error,
        )
        .with_anomaly_threshold(0.5);

        let base_time = Utc::now();
        let mut series_a = TimeSeries::new("error_rate".to_string(), Labels::new());
        let mut series_b = TimeSeries::new("cpu_usage".to_string(), Labels::new());

        for i in 0..10 {
            let val = i as f64;
            series_a.add_point(base_time + Duration::seconds(i), val);
            series_b.add_point(base_time + Duration::seconds(i), val * 2.0);
        }

        let result = detector.detect(&[series_a, series_b]).await.unwrap();
        assert!(result.is_anomaly, "Should detect correlation anomaly");
    }
}
