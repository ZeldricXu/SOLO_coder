use crate::monitoring::domain::{
    MetricId, MetricType, MetricValue, MetricRecord, Labels, MetricsSnapshot,
    validate_histogram_value, MAX_HISTOGRAM_SAMPLES,
};
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use std::collections::HashMap;
use tokio::sync::Mutex;
use chrono::Utc;
use tracing::warn;

pub trait Metric: Send + Sync + 'static {
    fn id(&self) -> &MetricId;
    fn metric_type(&self) -> MetricType;
    fn record(&self, value: MetricValue);
    fn snapshot(&self) -> MetricRecord;
}

pub trait Counter: Metric {
    fn inc(&self);
    fn inc_by(&self, amount: u64);
    fn get(&self) -> u64;
}

pub trait Gauge: Metric {
    fn set(&self, value: f64);
    fn inc(&self);
    fn dec(&self);
    fn get(&self) -> f64;
}

pub trait Histogram: Metric {
    fn observe(&self, value: f64);
    fn samples(&self) -> Vec<f64>;
}

pub trait MetricRegistry: Send + Sync + 'static {
    fn register_counter(&self, id: MetricId, labels: Labels) -> Arc<dyn Counter>;
    fn register_gauge(&self, id: MetricId, labels: Labels) -> Arc<dyn Gauge>;
    fn register_histogram(&self, id: MetricId, labels: Labels) -> Arc<dyn Histogram>;
    fn get_counter(&self, id: &MetricId, labels: &Labels) -> Option<Arc<dyn Counter>>;
    fn snapshot(&self) -> MetricsSnapshot;
}

pub trait MetricsCollector: Send + Sync + 'static {
    fn counter(&self, id: impl Into<String>, labels: Labels) -> Arc<dyn Counter>;
    fn gauge(&self, id: impl Into<String>, labels: Labels) -> Arc<dyn Gauge>;
    fn histogram(&self, id: impl Into<String>, labels: Labels) -> Arc<dyn Histogram>;
    fn snapshot(&self) -> MetricsSnapshot;
}

pub struct SimpleCounter {
    id: MetricId,
    labels: Labels,
    value: AtomicU64,
}

impl SimpleCounter {
    pub fn new(id: MetricId, labels: Labels) -> Arc<Self> {
        Arc::new(Self {
            id,
            labels,
            value: AtomicU64::new(0),
        })
    }
}

impl Metric for SimpleCounter {
    fn id(&self) -> &MetricId {
        &self.id
    }

    fn metric_type(&self) -> MetricType {
        MetricType::Counter
    }

    fn record(&self, value: MetricValue) {
        if let MetricValue::Counter(v) = value {
            self.value.fetch_add(v, std::sync::atomic::Ordering::Relaxed);
        }
    }

    fn snapshot(&self) -> MetricRecord {
        MetricRecord {
            id: self.id.clone(),
            metric_type: MetricType::Counter,
            value: MetricValue::Counter(self.get()),
            labels: self.labels.clone(),
            timestamp: Utc::now(),
        }
    }
}

impl Counter for SimpleCounter {
    fn inc(&self) {
        self.value.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }

    fn inc_by(&self, amount: u64) {
        if amount == 0 {
            warn!("ignoring counter increment by 0");
            return;
        }
        self.value.fetch_add(amount, std::sync::atomic::Ordering::Relaxed);
    }

    fn get(&self) -> u64 {
        self.value.load(std::sync::atomic::Ordering::Relaxed)
    }
}

pub struct SimpleGauge {
    id: MetricId,
    labels: Labels,
    value: Mutex<f64>,
}

impl SimpleGauge {
    pub fn new(id: MetricId, labels: Labels) -> Arc<Self> {
        Arc::new(Self {
            id,
            labels,
            value: Mutex::new(0.0),
        })
    }
}

impl Metric for SimpleGauge {
    fn id(&self) -> &MetricId {
        &self.id
    }

    fn metric_type(&self) -> MetricType {
        MetricType::Gauge
    }

    fn record(&self, value: MetricValue) {
        if let MetricValue::Gauge(v) = value {
            self.set(v);
        }
    }

    fn snapshot(&self) -> MetricRecord {
        let value = self.get();
        MetricRecord {
            id: self.id.clone(),
            metric_type: MetricType::Gauge,
            value: MetricValue::Gauge(value),
            labels: self.labels.clone(),
            timestamp: Utc::now(),
        }
    }
}

impl Gauge for SimpleGauge {
    fn set(&self, value: f64) {
        if value.is_nan() || value.is_infinite() {
            warn!(%value, "ignoring invalid gauge value (NaN or Inf)");
            return;
        }
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => {
                tokio::task::block_in_place(|| {
                    let mut guard = futures::executor::block_on(self.value.lock());
                    *guard = value;
                });
            }
            Err(_) => {
                let mut guard = futures::executor::block_on(self.value.lock());
                *guard = value;
            }
        }
    }

    fn inc(&self) {
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => {
                tokio::task::block_in_place(|| {
                    let mut guard = futures::executor::block_on(self.value.lock());
                    *guard += 1.0;
                });
            }
            Err(_) => {
                let mut guard = futures::executor::block_on(self.value.lock());
                *guard += 1.0;
            }
        }
    }

    fn dec(&self) {
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => {
                tokio::task::block_in_place(|| {
                    let mut guard = futures::executor::block_on(self.value.lock());
                    *guard -= 1.0;
                });
            }
            Err(_) => {
                let mut guard = futures::executor::block_on(self.value.lock());
                *guard -= 1.0;
            }
        }
    }

    fn get(&self) -> f64 {
        let rt = tokio::runtime::Handle::try_current();
        match rt {
            Ok(_) => {
                tokio::task::block_in_place(|| {
                    *futures::executor::block_on(self.value.lock())
                })
            }
            Err(_) => {
                *futures::executor::block_on(self.value.lock())
            }
        }
    }
}

pub struct SimpleHistogram {
    id: MetricId,
    labels: Labels,
    samples: Mutex<Vec<f64>>,
}

impl SimpleHistogram {
    pub fn new(id: MetricId, labels: Labels) -> Arc<Self> {
        Arc::new(Self {
            id,
            labels,
            samples: Mutex::new(Vec::new()),
        })
    }
}

impl Metric for SimpleHistogram {
    fn id(&self) -> &MetricId {
        &self.id
    }

    fn metric_type(&self) -> MetricType {
        MetricType::Histogram
    }

    fn record(&self, value: MetricValue) {
        if let MetricValue::HistogramSample(v) = value {
            self.observe(v);
        }
    }

    fn snapshot(&self) -> MetricRecord {
        let samples = self.samples();
        let last = samples.last().copied().unwrap_or(0.0);
        MetricRecord {
            id: self.id.clone(),
            metric_type: MetricType::Histogram,
            value: MetricValue::HistogramSample(last),
            labels: self.labels.clone(),
            timestamp: Utc::now(),
        }
    }
}

impl Histogram for SimpleHistogram {
    fn observe(&self, value: f64) {
        if let Err(e) = validate_histogram_value(value) {
            warn!(error = %e, "ignoring invalid histogram value");
            return;
        }

        let rt = tokio::runtime::Handle::try_current();
        let add_sample = || {
            let mut samples = futures::executor::block_on(self.samples.lock());
            samples.push(value);
            if samples.len() > MAX_HISTOGRAM_SAMPLES {
                let excess = samples.len() - MAX_HISTOGRAM_SAMPLES;
                samples.drain(0..excess);
            }
        };

        match rt {
            Ok(_) => tokio::task::block_in_place(add_sample),
            Err(_) => add_sample(),
        }
    }

    fn samples(&self) -> Vec<f64> {
        let rt = tokio::runtime::Handle::try_current();
        let get_samples = || {
            futures::executor::block_on(self.samples.lock()).clone()
        };

        match rt {
            Ok(_) => tokio::task::block_in_place(get_samples),
            Err(_) => get_samples(),
        }
    }
}
