use crate::monitoring::domain::{MetricId, Labels, MetricsSnapshot};
use crate::monitoring::core::{
    MetricRegistry, Counter, Gauge, Histogram, SimpleCounter, SimpleGauge, SimpleHistogram
};
use std::sync::Arc;
use std::collections::HashMap;
use tokio::sync::Mutex;

#[derive(Default)]
pub struct InMemoryRegistry {
    counters: Mutex<HashMap<(MetricId, Labels), Arc<dyn Counter>>>,
    gauges: Mutex<HashMap<(MetricId, Labels), Arc<dyn Gauge>>>,
    histograms: Mutex<HashMap<(MetricId, Labels), Arc<dyn Histogram>>>,
}

impl InMemoryRegistry {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }
}

impl MetricRegistry for InMemoryRegistry {
    fn register_counter(&self, id: MetricId, labels: Labels) -> Arc<dyn Counter> {
        let key = (id.clone(), labels.clone());
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => tokio::task::block_in_place(|| {
                let mut guards = futures::executor::block_on(self.counters.lock());
                guards.entry(key).or_insert_with(|| SimpleCounter::new(id, labels)).clone()
            }),
            Err(_) => {
                let mut guards = futures::executor::block_on(self.counters.lock());
                guards.entry(key).or_insert_with(|| SimpleCounter::new(id, labels)).clone()
            }
        }
    }

    fn register_gauge(&self, id: MetricId, labels: Labels) -> Arc<dyn Gauge> {
        let key = (id.clone(), labels.clone());
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => tokio::task::block_in_place(|| {
                let mut guards = futures::executor::block_on(self.gauges.lock());
                guards.entry(key).or_insert_with(|| SimpleGauge::new(id, labels)).clone()
            }),
            Err(_) => {
                let mut guards = futures::executor::block_on(self.gauges.lock());
                guards.entry(key).or_insert_with(|| SimpleGauge::new(id, labels)).clone()
            }
        }
    }

    fn register_histogram(&self, id: MetricId, labels: Labels) -> Arc<dyn Histogram> {
        let key = (id.clone(), labels.clone());
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => tokio::task::block_in_place(|| {
                let mut guards = futures::executor::block_on(self.histograms.lock());
                guards.entry(key).or_insert_with(|| SimpleHistogram::new(id, labels)).clone()
            }),
            Err(_) => {
                let mut guards = futures::executor::block_on(self.histograms.lock());
                guards.entry(key).or_insert_with(|| SimpleHistogram::new(id, labels)).clone()
            }
        }
    }

    fn get_counter(&self, id: &MetricId, labels: &Labels) -> Option<Arc<dyn Counter>> {
        let rt = tokio::runtime::Handle::try_current();
        let key = (id.clone(), labels.clone());
        match rt {
            Ok(_) => tokio::task::block_in_place(|| {
                let guards = futures::executor::block_on(self.counters.lock());
                guards.get(&key).cloned()
            }),
            Err(_) => {
                let guards = futures::executor::block_on(self.counters.lock());
                guards.get(&key).cloned()
            }
        }
    }

    fn snapshot(&self) -> MetricsSnapshot {
        use chrono::Utc;
        
        let rt = tokio::runtime::Handle::try_current();
        let get_snapshots = || {
            let counter_guards = futures::executor::block_on(self.counters.lock());
            let gauge_guards = futures::executor::block_on(self.gauges.lock());
            let histogram_guards = futures::executor::block_on(self.histograms.lock());

            let mut records = Vec::new();
            for counter in counter_guards.values() {
                records.push(counter.snapshot());
            }
            for gauge in gauge_guards.values() {
                records.push(gauge.snapshot());
            }
            for hist in histogram_guards.values() {
                records.push(hist.snapshot());
            }
            records
        };

        let records = match rt {
            Ok(_) => tokio::task::block_in_place(get_snapshots),
            Err(_) => get_snapshots(),
        };

        MetricsSnapshot {
            timestamp: Utc::now(),
            records,
        }
    }
}

pub struct SimpleCollector<R: MetricRegistry> {
    registry: Arc<R>,
}

impl<R: MetricRegistry> SimpleCollector<R> {
    pub fn new(registry: Arc<R>) -> Self {
        Self { registry }
    }
}

impl<R: MetricRegistry> crate::monitoring::core::MetricsCollector for SimpleCollector<R> {
    fn counter(&self, id: impl Into<String>, labels: Labels) -> Arc<dyn Counter> {
        let id_str: String = id.into();
        match MetricId::new(id_str) {
            Ok(mid) => self.registry.register_counter(mid, labels),
            Err(e) => {
                tracing::warn!(error = %e, "using fallback metric id");
                self.registry.register_counter(
                    MetricId::new("invalid_metric").expect("hardcoded valid id"),
                    Labels::new(),
                )
            }
        }
    }

    fn gauge(&self, id: impl Into<String>, labels: Labels) -> Arc<dyn Gauge> {
        let id_str: String = id.into();
        match MetricId::new(id_str) {
            Ok(mid) => self.registry.register_gauge(mid, labels),
            Err(e) => {
                tracing::warn!(error = %e, "using fallback metric id");
                self.registry.register_gauge(
                    MetricId::new("invalid_metric").expect("hardcoded valid id"),
                    Labels::new(),
                )
            }
        }
    }

    fn histogram(&self, id: impl Into<String>, labels: Labels) -> Arc<dyn Histogram> {
        let id_str: String = id.into();
        match MetricId::new(id_str) {
            Ok(mid) => self.registry.register_histogram(mid, labels),
            Err(e) => {
                tracing::warn!(error = %e, "using fallback metric id");
                self.registry.register_histogram(
                    MetricId::new("invalid_metric").expect("hardcoded valid id"),
                    Labels::new(),
                )
            }
        }
    }

    fn snapshot(&self) -> MetricsSnapshot {
        self.registry.snapshot()
    }
}
