use dashmap::DashMap;
use histogram::{Histogram as InnerHistogram, HistogramConfig};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

const BUCKETS: &[f64] = &[
    1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0,
];

pub struct LatencyHistogram {
    inner: InnerHistogram,
    count: u64,
    sum: f64,
    min_value: Option<f64>,
    max_value: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LatencySnapshot {
    pub count: u64,
    pub min: Option<f64>,
    pub max: Option<f64>,
    pub mean: Option<f64>,
    pub p50: Option<f64>,
    pub p95: Option<f64>,
    pub p99: Option<f64>,
    pub p999: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Percentiles {
    pub p50: Option<f64>,
    pub p99: Option<f64>,
    pub p999: Option<f64>,
}

impl Default for LatencySnapshot {
    fn default() -> Self {
        Self {
            count: 0,
            min: None,
            max: None,
            mean: None,
            p50: None,
            p95: None,
            p99: None,
            p999: None,
        }
    }
}

impl LatencyHistogram {
    pub fn new() -> Self {
        let max_bucket = BUCKETS[BUCKETS.len() - 1];
        let config = HistogramConfig {
            precision: 2,
            max_memory: 0,
            max_value: (max_bucket * 100.0) as u64,
        };

        let inner = InnerHistogram::new(config).expect("Failed to create histogram");

        Self {
            inner,
            count: 0,
            sum: 0.0,
            min_value: None,
            max_value: None,
        }
    }

    pub fn record(&mut self, latency_ms: u64) {
        let value_ms = latency_ms as f64;
        self.record_f64(value_ms);
    }

    pub fn record_f64(&mut self, value_ms: f64) {
        let clamped = value_ms.clamp(0.0, BUCKETS[BUCKETS.len() - 1]);
        let scaled = (clamped * 100.0).round() as u64;
        self.inner.increment(scaled);
        self.count += 1;
        self.sum += clamped;
        self.min_value = Some(self.min_value.map_or(clamped, |v| v.min(clamped)));
        self.max_value = Some(self.max_value.map_or(clamped, |v| v.max(clamped)));
    }

    pub fn percentile(&mut self, p: f64) -> Option<f64> {
        if self.count == 0 {
            return None;
        }
        self.inner
            .percentile(p)
            .map(|v| v as f64 / 100.0)
    }

    pub fn percentiles(&mut self) -> Percentiles {
        Percentiles {
            p50: self.percentile(50.0),
            p99: self.percentile(99.0),
            p999: self.percentile(99.9),
        }
    }

    pub fn mean(&self) -> Option<f64> {
        if self.count == 0 {
            return None;
        }
        Some(self.sum / self.count as f64)
    }

    pub fn min(&self) -> Option<f64> {
        if self.count == 0 {
            return None;
        }
        self.min_value
    }

    pub fn max(&self) -> Option<f64> {
        if self.count == 0 {
            return None;
        }
        self.max_value
    }

    fn count_immutable(&self) -> u64 {
        self.count
    }

    pub fn count(&mut self) -> u64 {
        self.count
    }

    pub fn snapshot(&mut self) -> LatencySnapshot {
        let count = self.count;
        LatencySnapshot {
            count,
            min: self.min(),
            max: self.max(),
            mean: self.mean(),
            p50: self.percentile(50.0),
            p95: self.percentile(95.0),
            p99: self.percentile(99.0),
            p999: self.percentile(99.9),
        }
    }

    pub fn buckets() -> &'static [f64] {
        BUCKETS
    }
}

#[derive(Clone)]
pub struct ModelLatencyStats {
    inner: Arc<DashMap<String, LatencyHistogram>>,
}

impl ModelLatencyStats {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(DashMap::new()),
        }
    }

    pub fn record(&self, model_name: &str, latency_ms: u64) {
        self.inner
            .entry(model_name.to_string())
            .or_insert_with(LatencyHistogram::new)
            .record(latency_ms);
    }

    pub fn record_f64(&self, model_name: &str, latency_ms: f64) {
        self.inner
            .entry(model_name.to_string())
            .or_insert_with(LatencyHistogram::new)
            .record_f64(latency_ms);
    }

    pub fn percentiles(&self, model_name: &str) -> Option<Percentiles> {
        self.inner.get_mut(model_name).map(|mut h| h.percentiles())
    }

    pub fn snapshot(&self, model_name: &str) -> Option<LatencySnapshot> {
        self.inner.get_mut(model_name).map(|mut h| h.snapshot())
    }

    pub fn all_snapshots(&self) -> Vec<(String, LatencySnapshot)> {
        let mut results = Vec::new();
        let mut refs: Vec<_> = self.inner.iter_mut().collect();
        for mut item in refs.drain(..) {
            let model = item.key().clone();
            let snap = item.value_mut().snapshot();
            results.push((model, snap));
        }
        results
    }

    pub fn model_names(&self) -> Vec<String> {
        self.inner.iter().map(|item| item.key().clone()).collect()
    }

    pub fn clear(&self) {
        self.inner.clear();
    }

    pub fn len(&self) -> usize {
        self.inner.len()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }
}

impl Default for ModelLatencyStats {
    fn default() -> Self {
        Self::new()
    }
}

impl Default for LatencyHistogram {
    fn default() -> Self {
        Self::new()
    }
}

impl Clone for LatencyHistogram {
    fn clone(&self) -> Self {
        let snap = {
            let mut tmp = Self::new();
            tmp.count = self.count;
            tmp.sum = self.sum;
            tmp.min_value = self.min_value;
            tmp.max_value = self.max_value;
            let count = tmp.count_immutable();
            let mut snap = LatencySnapshot {
                count,
                min: tmp.min(),
                max: tmp.max(),
                mean: tmp.mean(),
                p50: None,
                p95: None,
                p99: None,
                p999: None,
            };
            snap.count = self.count;
            snap
        };

        let mut new = Self::new();
        new.count = self.count;
        new.sum = self.sum;
        new.min_value = self.min_value;
        new.max_value = self.max_value;

        if let Some(min) = snap.min {
            new.record(min as u64);
        }
        if let Some(max) = snap.max {
            new.record(max as u64);
        }
        if snap.count > 2 {
            let remaining = snap.count - 2;
            for _ in 0..remaining {
                if let Some(mean) = snap.mean {
                    new.record(mean as u64);
                }
            }
        }
        new
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_histogram_basic() {
        let mut h = LatencyHistogram::new();
        assert_eq!(h.count(), 0);
        assert!(h.mean().is_none());
        assert!(h.min().is_none());
        assert!(h.max().is_none());
        assert!(h.percentile(50.0).is_none());
    }

    #[test]
    fn test_histogram_record_u64() {
        let mut h = LatencyHistogram::new();
        h.record(10);
        h.record(20);
        h.record(30);
        h.record(40);
        h.record(50);

        assert_eq!(h.count(), 5);
        assert!(h.min().unwrap() <= 10.0);
        assert!(h.max().unwrap() >= 50.0);
    }

    #[test]
    fn test_histogram_record_f64() {
        let mut h = LatencyHistogram::new();
        h.record_f64(10.5);
        h.record_f64(20.5);
        h.record_f64(30.5);

        assert_eq!(h.count(), 3);
        assert!(h.mean().unwrap() > 20.0);
    }

    #[test]
    fn test_percentiles() {
        let mut h = LatencyHistogram::new();
        for i in 0..1000 {
            h.record((i + 1) as u64);
        }

        let p = h.percentiles();
        assert!(p.p50.is_some());
        assert!(p.p99.is_some());
        assert!(p.p999.is_some());

        if let Some(p50) = p.p50 {
            assert!(p50 >= 450.0 && p50 <= 550.0);
        }
    }

    #[test]
    fn test_percentiles_empty() {
        let mut h = LatencyHistogram::new();
        let p = h.percentiles();
        assert!(p.p50.is_none());
        assert!(p.p99.is_none());
        assert!(p.p999.is_none());
    }

    #[test]
    fn test_snapshot() {
        let mut h = LatencyHistogram::new();
        for i in 0..100 {
            h.record((i + 1) as u64);
        }

        let snap = h.snapshot();
        assert_eq!(snap.count, 100);
        assert!(snap.p50.is_some());
        assert!(snap.p95.is_some());
        assert!(snap.p99.is_some());
        assert!(snap.p999.is_some());
    }

    #[test]
    fn test_empty_snapshot() {
        let mut h = LatencyHistogram::new();
        let snap = h.snapshot();
        assert_eq!(snap.count, 0);
        assert!(snap.min.is_none());
        assert!(snap.max.is_none());
        assert!(snap.mean.is_none());
        assert!(snap.p50.is_none());
    }

    #[test]
    fn test_mean() {
        let mut h = LatencyHistogram::new();
        h.record(10);
        h.record(20);
        h.record(30);
        let mean = h.mean().unwrap();
        assert!((mean - 20.0).abs() < 0.01);
    }

    #[test]
    fn test_model_latency_stats_basic() {
        let stats = ModelLatencyStats::new();
        assert!(stats.is_empty());
        assert_eq!(stats.len(), 0);

        stats.record("model_a", 50);
        stats.record("model_a", 100);
        stats.record("model_b", 200);

        assert_eq!(stats.len(), 2);
        assert!(!stats.is_empty());

        let models = stats.model_names();
        assert_eq!(models.len(), 2);
        assert!(models.contains(&"model_a".to_string()));
        assert!(models.contains(&"model_b".to_string()));
    }

    #[test]
    fn test_model_latency_stats_percentiles() {
        let stats = ModelLatencyStats::new();
        for i in 0..100 {
            stats.record("test_model", (i + 1) as u64);
        }

        let p = stats.percentiles("test_model").unwrap();
        assert!(p.p50.is_some());
        assert!(p.p99.is_some());
        assert!(p.p999.is_some());

        assert!(stats.percentiles("nonexistent").is_none());
    }

    #[test]
    fn test_model_latency_stats_snapshot() {
        let stats = ModelLatencyStats::new();
        stats.record("model_x", 10);
        stats.record("model_x", 20);
        stats.record("model_y", 30);

        let snap_x = stats.snapshot("model_x").unwrap();
        assert_eq!(snap_x.count, 2);

        let snap_y = stats.snapshot("model_y").unwrap();
        assert_eq!(snap_y.count, 1);

        assert!(stats.snapshot("nonexistent").is_none());
    }

    #[test]
    fn test_model_latency_stats_all_snapshots() {
        let stats = ModelLatencyStats::new();
        stats.record("m1", 10);
        stats.record("m2", 20);

        let all = stats.all_snapshots();
        assert_eq!(all.len(), 2);
    }

    #[test]
    fn test_model_latency_stats_clear() {
        let stats = ModelLatencyStats::new();
        stats.record("m1", 10);
        stats.record("m2", 20);
        assert_eq!(stats.len(), 2);

        stats.clear();
        assert!(stats.is_empty());
        assert_eq!(stats.len(), 0);
    }
}
