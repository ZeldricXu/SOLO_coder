use dashmap::DashMap;
use parking_lot::RwLock;
use std::sync::atomic::{AtomicU64, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use chrono::{DateTime, Utc};
use serde::{Serialize, Deserialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricPoint {
    pub timestamp: DateTime<Utc>,
    pub value: f64,
    pub tags: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricSummary {
    pub name: String,
    pub count: u64,
    pub sum: f64,
    pub min: f64,
    pub max: f64,
    pub avg: f64,
    pub p50: Option<f64>,
    pub p95: Option<f64>,
    pub p99: Option<f64>,
    pub last_updated: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineMonitorEvent {
    pub event_id: String,
    pub pipeline_id: String,
    pub stage: String,
    pub event_type: String,
    pub timestamp: DateTime<Utc>,
    pub duration_ms: Option<u64>,
    pub tags: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineMonitorSnapshot {
    pub total_pipelines: u64,
    pub active_pipelines: u64,
    pub success_count: u64,
    pub failure_count: u64,
    pub stage_metrics: std::collections::HashMap<String, StageMetric>,
    pub recent_events: Vec<PipelineMonitorEvent>,
    pub overall_throughput: f64,
    pub avg_latency_ms: Option<f64>,
    pub error_rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageMetric {
    pub total_calls: u64,
    pub success_calls: u64,
    pub failure_calls: u64,
    pub avg_duration_ms: f64,
    pub p95_duration_ms: f64,
    pub active_invocations: u64,
}

#[derive(Debug, Clone)]
pub struct TimerGuard {
    name: String,
    start: Instant,
    metrics: Arc<MetricsCollector>,
    tags: std::collections::HashMap<String, String>,
}

impl Drop for TimerGuard {
    fn drop(&mut self) {
        let duration = self.start.elapsed().as_millis() as f64;
        self.metrics.record_timing_with_tags(&self.name, duration, self.tags.clone());
    }
}

#[derive(Debug, Default)]
pub struct MetricsCollector {
    counters: Arc<DashMap<String, AtomicU64>>,
    gauges: Arc<DashMap<String, AtomicI64>>,
    histograms: Arc<DashMap<String, RwLock<Vec<f64>>>>,
    timings: Arc<DashMap<String, RwLock<Vec<(DateTime<Utc>, f64, std::collections::HashMap<String, String>)>>>>,
    pipeline_events: Arc<RwLock<Vec<PipelineMonitorEvent>>>,
    active_pipelines: Arc<AtomicU64>,
    stage_active_counts: Arc<DashMap<String, AtomicU64>>,
    max_events: usize,
}

impl Clone for MetricsCollector {
    fn clone(&self) -> Self {
        Self {
            counters: Arc::clone(&self.counters),
            gauges: Arc::clone(&self.gauges),
            histograms: Arc::clone(&self.histograms),
            timings: Arc::clone(&self.timings),
            pipeline_events: Arc::clone(&self.pipeline_events),
            active_pipelines: Arc::clone(&self.active_pipelines),
            stage_active_counts: Arc::clone(&self.stage_active_counts),
            max_events: self.max_events,
        }
    }
}

impl MetricsCollector {
    pub fn new() -> Self {
        Self {
            counters: Arc::new(DashMap::new()),
            gauges: Arc::new(DashMap::new()),
            histograms: Arc::new(DashMap::new()),
            timings: Arc::new(DashMap::new()),
            pipeline_events: Arc::new(RwLock::new(Vec::new())),
            active_pipelines: Arc::new(AtomicU64::new(0)),
            stage_active_counts: Arc::new(DashMap::new()),
            max_events: 1000,
        }
    }

    pub fn with_max_events(mut self, max_events: usize) -> Self {
        self.max_events = max_events;
        self
    }

    pub fn increment_counter(&self, name: &str) {
        self.counters
            .entry(name.to_string())
            .or_insert_with(|| AtomicU64::new(0))
            .fetch_add(1, Ordering::Relaxed);
    }

    pub fn increment_counter_with_tags(&self, name: &str, tags: std::collections::HashMap<String, String>) {
        let tagged_name = self.build_tagged_name(name, &tags);
        self.increment_counter(&tagged_name);
        self.increment_counter(name);
    }

    pub fn add_to_counter(&self, name: &str, value: u64) {
        self.counters
            .entry(name.to_string())
            .or_insert_with(|| AtomicU64::new(0))
            .fetch_add(value, Ordering::Relaxed);
    }

    pub fn get_counter(&self, name: &str) -> u64 {
        self.counters
            .get(name)
            .map(|v| v.load(Ordering::Relaxed))
            .unwrap_or(0)
    }

    pub fn set_gauge(&self, name: &str, value: i64) {
        self.gauges
            .entry(name.to_string())
            .or_insert_with(|| AtomicI64::new(0))
            .store(value, Ordering::Relaxed);
    }

    pub fn increment_gauge(&self, name: &str, value: i64) {
        self.gauges
            .entry(name.to_string())
            .or_insert_with(|| AtomicI64::new(0))
            .fetch_add(value, Ordering::Relaxed);
    }

    pub fn get_gauge(&self, name: &str) -> i64 {
        self.gauges
            .get(name)
            .map(|v| v.load(Ordering::Relaxed))
            .unwrap_or(0)
    }

    pub fn record_histogram(&self, name: &str, value: f64) {
        self.histograms
            .entry(name.to_string())
            .or_insert_with(|| RwLock::new(Vec::new()))
            .write()
            .push(value);
    }

    pub fn record_histogram_with_tags(&self, name: &str, value: f64, tags: std::collections::HashMap<String, String>) {
        let tagged_name = self.build_tagged_name(name, &tags);
        self.record_histogram(&tagged_name, value);
        self.record_histogram(name, value);
    }

    pub fn record_timing_with_tags(&self, name: &str, duration_ms: f64, tags: std::collections::HashMap<String, String>) {
        self.timings
            .entry(name.to_string())
            .or_insert_with(|| RwLock::new(Vec::new()))
            .write()
            .push((Utc::now(), duration_ms, tags.clone()));
        self.record_histogram_with_tags(name, duration_ms, tags);
    }

    pub fn start_timer(&self, name: &str) -> TimerGuard {
        TimerGuard {
            name: name.to_string(),
            start: Instant::now(),
            metrics: Arc::new(self.clone()),
            tags: std::collections::HashMap::new(),
        }
    }

    pub fn start_timer_with_tags(&self, name: &str, tags: std::collections::HashMap<String, String>) -> TimerGuard {
        TimerGuard {
            name: name.to_string(),
            start: Instant::now(),
            metrics: Arc::new(self.clone()),
            tags,
        }
    }

    pub fn pipeline_start(&self, pipeline_id: &str) {
        self.active_pipelines.fetch_add(1, Ordering::Relaxed);
        self.increment_counter("pipeline_total");
        self.record_pipeline_event(PipelineMonitorEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            pipeline_id: pipeline_id.to_string(),
            stage: "pipeline".to_string(),
            event_type: "start".to_string(),
            timestamp: Utc::now(),
            duration_ms: None,
            tags: std::collections::HashMap::new(),
        });
    }

    pub fn pipeline_complete(&self, pipeline_id: &str, success: bool, duration_ms: u64) {
        self.active_pipelines.fetch_sub(1, Ordering::Relaxed);
        if success {
            self.increment_counter("pipeline_success");
        } else {
            self.increment_counter("pipeline_failure");
        }
        self.record_histogram("pipeline_latency", duration_ms as f64);
        self.record_pipeline_event(PipelineMonitorEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            pipeline_id: pipeline_id.to_string(),
            stage: "pipeline".to_string(),
            event_type: if success { "success" } else { "failure" }.to_string(),
            timestamp: Utc::now(),
            duration_ms: Some(duration_ms),
            tags: std::collections::HashMap::new(),
        });
    }

    pub fn stage_start(&self, pipeline_id: &str, stage: &str) {
        let stage_key = format!("stage_{}", stage);
        self.stage_active_counts
            .entry(stage_key.clone())
            .or_insert_with(|| AtomicU64::new(0))
            .fetch_add(1, Ordering::Relaxed);
        self.increment_counter(&format!("stage_{}_total", stage));
        self.record_pipeline_event(PipelineMonitorEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            pipeline_id: pipeline_id.to_string(),
            stage: stage.to_string(),
            event_type: "start".to_string(),
            timestamp: Utc::now(),
            duration_ms: None,
            tags: std::collections::HashMap::new(),
        });
    }

    pub fn stage_complete(&self, pipeline_id: &str, stage: &str, success: bool, duration_ms: u64) {
        let stage_key = format!("stage_{}", stage);
        self.stage_active_counts
            .entry(stage_key.clone())
            .or_insert_with(|| AtomicU64::new(0))
            .fetch_sub(1, Ordering::Relaxed);
        if success {
            self.increment_counter(&format!("stage_{}_success", stage));
        } else {
            self.increment_counter(&format!("stage_{}_failure", stage));
        }
        self.record_histogram(&format!("stage_{}_latency", stage), duration_ms as f64);
        self.record_pipeline_event(PipelineMonitorEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            pipeline_id: pipeline_id.to_string(),
            stage: stage.to_string(),
            event_type: if success { "success" } else { "failure" }.to_string(),
            timestamp: Utc::now(),
            duration_ms: Some(duration_ms),
            tags: std::collections::HashMap::new(),
        });
    }

    pub fn record_pipeline_event(&self, event: PipelineMonitorEvent) {
        let mut events = self.pipeline_events.write();
        events.push(event);
        if events.len() > self.max_events {
            let to_remove = events.len() - self.max_events;
            events.drain(0..to_remove);
        }
    }

    pub fn get_pipeline_monitor_snapshot(&self) -> PipelineMonitorSnapshot {
        let total = self.get_counter("pipeline_total");
        let success = self.get_counter("pipeline_success");
        let failure = self.get_counter("pipeline_failure");
        
        let stages = vec!["parse", "split", "vectorize"];
        let mut stage_metrics = std::collections::HashMap::new();
        
        for stage in &stages {
            let total_calls = self.get_counter(&format!("stage_{}_total", stage));
            let success_calls = self.get_counter(&format!("stage_{}_success", stage));
            let failure_calls = self.get_counter(&format!("stage_{}_failure", stage));
            let active = self.stage_active_counts
                .get(&format!("stage_{}", stage))
                .map(|v| v.load(Ordering::Relaxed))
                .unwrap_or(0);
            
            let avg_duration = self.get_mean(&format!("stage_{}_latency", stage)).unwrap_or(0.0);
            let p95_duration = self.get_percentile(&format!("stage_{}_latency", stage), 0.95).unwrap_or(0.0);
            
            stage_metrics.insert(stage.to_string(), StageMetric {
                total_calls,
                success_calls,
                failure_calls,
                avg_duration_ms: avg_duration,
                p95_duration_ms: p95_duration,
                active_invocations: active,
            });
        }

        let recent_events = self.pipeline_events.read().iter().rev().take(100).cloned().collect();
        let avg_latency = self.get_mean("pipeline_latency");
        let error_rate = if total > 0 { failure as f64 / total as f64 } else { 0.0 };

        PipelineMonitorSnapshot {
            total_pipelines: total,
            active_pipelines: self.active_pipelines.load(Ordering::Relaxed),
            success_count: success,
            failure_count: failure,
            stage_metrics,
            recent_events,
            overall_throughput: total as f64,
            avg_latency_ms: avg_latency,
            error_rate,
        }
    }

    pub fn get_percentile(&self, name: &str, p: f64) -> Option<f64> {
        self.histograms.get(name).and_then(|values| {
            let guard = values.read();
            if guard.is_empty() {
                None
            } else {
                let mut sorted: Vec<f64> = guard.clone();
                sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
                let idx = (sorted.len() as f64 * p).ceil() as usize - 1;
                Some(sorted[idx.max(0).min(sorted.len() - 1)])
            }
        })
    }

    pub fn get_mean(&self, name: &str) -> Option<f64> {
        self.histograms.get(name).and_then(|values| {
            let guard = values.read();
            if guard.is_empty() {
                None
            } else {
                let sum: f64 = guard.iter().sum();
                Some(sum / guard.len() as f64)
            }
        })
    }

    pub fn get_metric_summary(&self, name: &str) -> Option<MetricSummary> {
        self.histograms.get(name).map(|values| {
            let guard = values.read();
            let count = guard.len() as u64;
            let sum: f64 = guard.iter().sum();
            let min = guard.iter().cloned().fold(f64::INFINITY, f64::min);
            let max = guard.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            let avg = if count > 0 { sum / count as f64 } else { 0.0 };
            
            let mut sorted: Vec<f64> = guard.clone();
            sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
            
            MetricSummary {
                name: name.to_string(),
                count,
                sum,
                min,
                max,
                avg,
                p50: if !sorted.is_empty() { Some(sorted[(sorted.len() as f64 * 0.50) as usize]) } else { None },
                p95: if !sorted.is_empty() { Some(sorted[(sorted.len() as f64 * 0.95) as usize.min(sorted.len() - 1)]) } else { None },
                p99: if !sorted.is_empty() { Some(sorted[(sorted.len() as f64 * 0.99) as usize.min(sorted.len() - 1)]) } else { None },
                last_updated: Utc::now(),
            }
        })
    }

    pub fn snapshot(&self) -> crate::models::Metrics {
        crate::models::Metrics {
            throughput: Some(self.get_counter("requests") as f64),
            latency_p50: self.get_percentile("latency", 0.50),
            latency_p99: self.get_percentile("latency", 0.99),
            error_rate: {
                let total = self.get_counter("requests") as f64;
                let errors = self.get_counter("errors") as f64;
                if total > 0.0 { Some(errors / total) } else { Some(0.0) }
            },
            success_count: Some(self.get_counter("success")),
            failure_count: Some(self.get_counter("errors")),
        }
    }

    pub fn reset(&self) {
        self.counters.clear();
        self.gauges.clear();
        self.histograms.clear();
        self.timings.clear();
        self.pipeline_events.write().clear();
        self.active_pipelines.store(0, Ordering::Relaxed);
        self.stage_active_counts.clear();
    }

    fn build_tagged_name(&self, name: &str, tags: &std::collections::HashMap<String, String>) -> String {
        let mut parts: Vec<String> = tags.iter()
            .map(|(k, v)| format!("{}={}", k, v))
            .collect();
        parts.sort();
        format!("{}[{}]", name, parts.join(","))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_counter_operations() {
        let metrics = MetricsCollector::new();
        metrics.increment_counter("requests");
        metrics.increment_counter("requests");
        metrics.add_to_counter("requests", 3);
        assert_eq!(metrics.get_counter("requests"), 5);
    }

    #[test]
    fn test_gauge_operations() {
        let metrics = MetricsCollector::new();
        metrics.set_gauge("active_connections", 100);
        assert_eq!(metrics.get_gauge("active_connections"), 100);
        metrics.increment_gauge("active_connections", -20);
        assert_eq!(metrics.get_gauge("active_connections"), 80);
    }

    #[test]
    fn test_histogram_operations() {
        let metrics = MetricsCollector::new();
        metrics.record_histogram("latency", 10.0);
        metrics.record_histogram("latency", 20.0);
        metrics.record_histogram("latency", 30.0);
        metrics.record_histogram("latency", 40.0);
        metrics.record_histogram("latency", 50.0);

        assert_eq!(metrics.get_percentile("latency", 0.50), Some(30.0));
        assert_eq!(metrics.get_percentile("latency", 0.99), Some(50.0));
        assert_eq!(metrics.get_mean("latency"), Some(30.0));
    }

    #[test]
    fn test_pipeline_monitoring() {
        let metrics = MetricsCollector::new();
        let pipeline_id = "pipe_001";
        
        metrics.pipeline_start(pipeline_id);
        assert_eq!(metrics.get_counter("pipeline_total"), 1);
        assert_eq!(metrics.active_pipelines.load(Ordering::Relaxed), 1);
        
        metrics.stage_start(pipeline_id, "parse");
        metrics.stage_complete(pipeline_id, "parse", true, 50);
        
        metrics.pipeline_complete(pipeline_id, true, 100);
        
        assert_eq!(metrics.get_counter("pipeline_success"), 1);
        assert_eq!(metrics.active_pipelines.load(Ordering::Relaxed), 0);
        
        let snapshot = metrics.get_pipeline_monitor_snapshot();
        assert_eq!(snapshot.total_pipelines, 1);
        assert_eq!(snapshot.success_count, 1);
        assert!(snapshot.stage_metrics.contains_key("parse"));
    }

    #[test]
    fn test_timer_guard() {
        let metrics = MetricsCollector::new();
        {
            let _timer = metrics.start_timer("test_operation");
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(metrics.get_mean("test_operation").unwrap_or(0.0) >= 10.0);
    }

    #[test]
    fn test_metric_summary() {
        let metrics = MetricsCollector::new();
        for i in 1..=100 {
            metrics.record_histogram("test_metric", i as f64);
        }
        
        let summary = metrics.get_metric_summary("test_metric").unwrap();
        assert_eq!(summary.count, 100);
        assert_eq!(summary.min, 1.0);
        assert_eq!(summary.max, 100.0);
        assert_eq!(summary.avg, 50.5);
        assert!(summary.p50.is_some());
        assert!(summary.p95.is_some());
        assert!(summary.p99.is_some());
    }
}
