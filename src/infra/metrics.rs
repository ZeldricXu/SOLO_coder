use std::sync::Arc;
use std::time::Instant;

use parking_lot::Mutex;
use prometheus::{
    register_counter_vec, register_gauge_vec, register_histogram_vec,
    CounterVec, GaugeVec, HistogramVec, Opts, Registry,
};
use serde::Serialize;
use tracing::debug;

#[derive(Clone)]
pub struct MetricsRegistry {
    registry: Arc<Registry>,
    counters: Arc<Mutex<std::collections::HashMap<String, CounterVec>>>,
    gauges: Arc<Mutex<std::collections::HashMap<String, GaugeVec>>>,
    histograms: Arc<Mutex<std::collections::HashMap<String, HistogramVec>>>,
}

impl MetricsRegistry {
    pub fn new() -> Self {
        let registry = Registry::new();
        
        Self {
            registry: Arc::new(registry),
            counters: Arc::new(Mutex::new(std::collections::HashMap::new())),
            gauges: Arc::new(Mutex::new(std::collections::HashMap::new())),
            histograms: Arc::new(Mutex::new(std::collections::HashMap::new())),
        }
    }

    pub fn register_counter(&self, name: &str, help: &str, labels: &[&str]) -> CounterVec {
        let mut counters = self.counters.lock();
        
        if let Some(counter) = counters.get(name) {
            return counter.clone();
        }

        let opts = Opts::new(name, help);
        let counter = register_counter_vec!(opts, labels)
            .unwrap_or_else(|e| panic!("Failed to register counter {}: {}", name, e));
        
        self.registry.register(Box::new(counter.clone())).ok();
        counters.insert(name.to_string(), counter.clone());
        
        debug!("[Metrics] Registered counter: {}", name);
        counter
    }

    pub fn register_gauge(&self, name: &str, help: &str, labels: &[&str]) -> GaugeVec {
        let mut gauges = self.gauges.lock();
        
        if let Some(gauge) = gauges.get(name) {
            return gauge.clone();
        }

        let opts = Opts::new(name, help);
        let gauge = register_gauge_vec!(opts, labels)
            .unwrap_or_else(|e| panic!("Failed to register gauge {}: {}", name, e));
        
        self.registry.register(Box::new(gauge.clone())).ok();
        gauges.insert(name.to_string(), gauge.clone());
        
        debug!("[Metrics] Registered gauge: {}", name);
        gauge
    }

    pub fn register_histogram(&self, name: &str, help: &str, labels: &[&str], buckets: Option<Vec<f64>>) -> HistogramVec {
        let mut histograms = self.histograms.lock();
        
        if let Some(histogram) = histograms.get(name) {
            return histogram.clone();
        }

        let opts = Opts::new(name, help);
        let histogram = if let Some(b) = buckets {
            register_histogram_vec!(opts, b, labels)
        } else {
            register_histogram_vec!(opts, labels)
        }.unwrap_or_else(|e| panic!("Failed to register histogram {}: {}", name, e));
        
        self.registry.register(Box::new(histogram.clone())).ok();
        histograms.insert(name.to_string(), histogram.clone());
        
        debug!("[Metrics] Registered histogram: {}", name);
        histogram
    }

    pub fn increment_counter(&self, name: &str, labels: &[&str]) {
        if let Some(counter) = self.counters.lock().get(name) {
            counter.with_label_values(labels).inc();
        }
    }

    pub fn increment_counter_by(&self, name: &str, labels: &[&str], value: f64) {
        if let Some(counter) = self.counters.lock().get(name) {
            counter.with_label_values(labels).inc_by(value);
        }
    }

    pub fn set_gauge(&self, name: &str, labels: &[&str], value: f64) {
        if let Some(gauge) = self.gauges.lock().get(name) {
            gauge.with_label_values(labels).set(value);
        }
    }

    pub fn observe_histogram(&self, name: &str, labels: &[&str], value: f64) {
        if let Some(histogram) = self.histograms.lock().get(name) {
            histogram.with_label_values(labels).observe(value);
        }
    }

    pub fn time_operation<F, R>(&self, name: &str, labels: &[&str], f: F) -> R
    where
        F: FnOnce() -> R,
    {
        let start = Instant::now();
        let result = f();
        let duration = start.elapsed().as_secs_f64();
        self.observe_histogram(name, labels, duration);
        result
    }

    pub async fn time_operation_async<F, Fut, R>(&self, name: &str, labels: &[&str], f: F) -> R
    where
        F: FnOnce() -> Fut,
        Fut: std::future::Future<Output = R>,
    {
        let start = Instant::now();
        let result = f().await;
        let duration = start.elapsed().as_secs_f64();
        self.observe_histogram(name, labels, duration);
        result
    }

    pub fn gather(&self) -> String {
        use prometheus::Encoder;
        let encoder = prometheus::TextEncoder::new();
        let metric_families = self.registry.gather();
        let mut buffer = Vec::new();
        encoder.encode(&metric_families, &mut buffer).unwrap_or_default();
        String::from_utf8(buffer).unwrap_or_default()
    }

    pub fn get_snapshot(&self) -> MetricsSnapshot {
        let mut snapshot = MetricsSnapshot::default();
        
        for (name, counter) in self.counters.lock().iter() {
            if let Ok(Some(v)) = counter.get_metric_with_label_values(&[]) {
                snapshot.counters.insert(name.clone(), v.get());
            }
        }
        
        for (name, gauge) in self.gauges.lock().iter() {
            if let Ok(Some(v)) = gauge.get_metric_with_label_values(&[]) {
                snapshot.gauges.insert(name.clone(), v.get());
            }
        }
        
        snapshot
    }
}

impl Default for MetricsRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct MetricsSnapshot {
    pub counters: std::collections::HashMap<String, f64>,
    pub gauges: std::collections::HashMap<String, f64>,
}

pub struct TimerGuard<'a> {
    registry: &'a MetricsRegistry,
    name: &'a str,
    labels: Vec<&'a str>,
    start: Instant,
}

impl<'a> TimerGuard<'a> {
    pub fn new(registry: &'a MetricsRegistry, name: &'a str, labels: Vec<&'a str>) -> Self {
        Self {
            registry,
            name,
            labels,
            start: Instant::now(),
        }
    }
}

impl<'a> Drop for TimerGuard<'a> {
    fn drop(&mut self) {
        let duration = self.start.elapsed().as_secs_f64();
        self.registry.observe_histogram(self.name, &self.labels, duration);
    }
}

#[macro_export]
macro_rules! measure_time {
    ($registry:expr, $name:expr, $labels:expr, $code:block) => {{
        let _guard = $crate::infra::metrics::TimerGuard::new($registry, $name, $labels);
        $code
    }};
}
