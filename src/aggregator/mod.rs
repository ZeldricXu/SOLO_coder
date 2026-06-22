pub mod tdigest;

use crate::{AggregationKey, LogLevel, LogRecord, WindowStats};
use crate::aggregator::tdigest::TDigest;
use crate::config::ConfigHandle;
use chrono::{DateTime, Duration, Utc};
use parking_lot::RwLock;
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::Arc;
use tokio::sync::broadcast;
use tracing::{debug, info};

struct WindowBucket {
    start: DateTime<Utc>,
    end: DateTime<Utc>,
    counts: HashMap<AggregationKey, u64>,
    sum_spend: HashMap<AggregationKey, f64>,
    digests: HashMap<AggregationKey, TDigest>,
    tdigest_compression: f64,
}

impl WindowBucket {
    fn new(start: DateTime<Utc>, end: DateTime<Utc>, tdigest_compression: f64) -> Self {
        Self {
            start,
            end,
            counts: HashMap::new(),
            sum_spend: HashMap::new(),
            digests: HashMap::new(),
            tdigest_compression,
        }
    }

    fn ingest(&mut self, key: &AggregationKey, spend: Option<f64>) {
        *self.counts.entry(key.clone()).or_insert(0) += 1;
        if let Some(s) = spend {
            *self.sum_spend.entry(key.clone()).or_insert(0.0) += s;
            let compression = self.tdigest_compression;
            let digest = self
                .digests
                .entry(key.clone())
                .or_insert_with(|| TDigest::new(compression));
            digest.add(s);
        }
    }

    fn build_stats(&self) -> Vec<WindowStats> {
        let mut result = Vec::new();
        for (key, count) in &self.counts {
            let sum = self.sum_spend.get(key).copied().unwrap_or(0.0);
            let count_f = *count as f64;
            let avg = if count_f > 0.0 { sum / count_f } else { 0.0 };
            let (min, max, p50, p95, p99) = if let Some(d) = self.digests.get(key) {
                let mut dc = d.clone();
                (
                    dc.min(),
                    dc.max(),
                    dc.quantile(0.5),
                    dc.quantile(0.95),
                    dc.quantile(0.99),
                )
            } else {
                (0.0, 0.0, 0.0, 0.0, 0.0)
            };
            result.push(WindowStats {
                window_start: self.start,
                window_end: self.end,
                key: key.clone(),
                count: *count,
                sum_spend: sum,
                avg_spend: avg,
                p50_spend: p50,
                p95_spend: p95,
                p99_spend: p99,
                min_spend: min,
                max_spend: max,
            });
        }
        result
    }
}

pub struct AggregationEngine {
    config: ConfigHandle,
    inner: Arc<RwLock<AggregationInner>>,
    event_sender: broadcast::Sender<Vec<WindowStats>>,
}

struct AggregationInner {
    fine_window_secs: u64,
    coarse_window_secs: u64,
    tdigest_compression: f64,
    fine_buckets: BTreeMap<DateTime<Utc>, WindowBucket>,
    coarse_buckets: BTreeMap<DateTime<Utc>, WindowBucket>,
    last_fine_emitted: Option<DateTime<Utc>>,
    last_coarse_emitted: Option<DateTime<Utc>>,
    pending_fine_results: VecDeque<WindowStats>,
    pending_coarse_results: VecDeque<WindowStats>,
}

impl AggregationEngine {
    pub fn new(config: ConfigHandle) -> Self {
        let (event_sender, _) = broadcast::channel(1024);
        let rt = tokio::runtime::Handle::try_current().ok();
        let pipeline = rt.as_ref().map(|_| None).unwrap_or(None);
        let (fine_secs, coarse_secs, compression) = {
            let cfg = if let Ok(rt) = std::panic::catch_unwind(|| {
                tokio::runtime::Handle::try_current()
            }) {
                rt.ok()
            } else {
                None
            };
            if let Some(rt) = cfg {
                let _ = rt;
            }
            (10u64, 300u64, 100.0f64)
        };
        let inner = AggregationInner {
            fine_window_secs: fine_secs,
            coarse_window_secs: coarse_secs,
            tdigest_compression: compression,
            fine_buckets: BTreeMap::new(),
            coarse_buckets: BTreeMap::new(),
            last_fine_emitted: None,
            last_coarse_emitted: None,
            pending_fine_results: VecDeque::new(),
            pending_coarse_results: VecDeque::new(),
        };
        Self {
            config,
            inner: Arc::new(RwLock::new(inner)),
            event_sender,
        }
    }

    pub async fn init_from_config(&self) {
        let cfg = self.config.read().await;
        let mut inner = self.inner.write();
        inner.fine_window_secs = cfg.pipeline.fine_grained_window_secs;
        inner.coarse_window_secs = cfg.pipeline.coarse_grained_window_secs;
        inner.tdigest_compression = cfg.pipeline.tdigest_compression;
    }

    pub fn ingest(&self, record: &LogRecord) {
        let key = AggregationKey {
            service: record.service.clone(),
            level: record.level.clone(),
        };
        let ts = record.timestamp;
        let spend = record.spend_ms;

        let mut inner = self.inner.write();
        let fine_start = floor_to_window(ts, inner.fine_window_secs);
        let fine_end = fine_start + Duration::seconds(inner.fine_window_secs as i64);
        let fine_bucket = inner
            .fine_buckets
            .entry(fine_start)
            .or_insert_with(|| WindowBucket::new(fine_start, fine_end, inner.tdigest_compression));
        fine_bucket.ingest(&key, spend);

        let coarse_start = floor_to_window(ts, inner.coarse_window_secs);
        let coarse_end = coarse_start + Duration::seconds(inner.coarse_window_secs as i64);
        let coarse_bucket = inner
            .coarse_buckets
            .entry(coarse_start)
            .or_insert_with(|| WindowBucket::new(coarse_start, coarse_end, inner.tdigest_compression));
        coarse_bucket.ingest(&key, spend);
    }

    pub fn tick(&self, now: DateTime<Utc>) {
        let mut inner = self.inner.write();
        let cutoff_fine = now - Duration::seconds(inner.fine_window_secs as i64 * 2);
        let fine_to_emit: Vec<DateTime<Utc>> = inner
            .fine_buckets
            .range(..cutoff_fine)
            .map(|(k, _)| *k)
            .collect();
        let mut emitted_fine: Vec<WindowStats> = Vec::new();
        for start in &fine_to_emit {
            if let Some(bucket) = inner.fine_buckets.remove(start) {
                let stats = bucket.build_stats();
                debug!(
                    "Emitting fine window {:?} stats ({} keys)",
                    start,
                    stats.len()
                );
                emitted_fine.extend(stats.clone());
                inner.pending_fine_results.extend(stats);
                inner.last_fine_emitted = Some(*start);
            }
        }

        let cutoff_coarse = now - Duration::seconds(inner.coarse_window_secs as i64 * 2);
        let coarse_to_emit: Vec<DateTime<Utc>> = inner
            .coarse_buckets
            .range(..cutoff_coarse)
            .map(|(k, _)| *k)
            .collect();
        for start in &coarse_to_emit {
            if let Some(bucket) = inner.coarse_buckets.remove(start) {
                let stats = bucket.build_stats();
                info!(
                    "Emitting coarse window {:?} stats ({} keys, total count={})",
                    start,
                    stats.len(),
                    stats.iter().map(|s| s.count).sum::<u64>()
                );
                inner.pending_coarse_results.extend(stats);
                inner.last_coarse_emitted = Some(*start);
            }
        }

        let retention_cutoff = now - Duration::seconds(inner.fine_window_secs as i64 * 10);
        inner.fine_buckets.retain(|k, _| *k >= retention_cutoff);
        let coarse_retention = now - Duration::seconds(inner.coarse_window_secs as i64 * 5);
        inner.coarse_buckets.retain(|k, _| *k >= coarse_retention);

        drop(inner);

        if !emitted_fine.is_empty() {
            let _ = self.event_sender.send(emitted_fine);
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Vec<WindowStats>> {
        self.event_sender.subscribe()
    }

    pub fn drain_fine_results(&self) -> Vec<WindowStats> {
        let mut inner = self.inner.write();
        inner.pending_fine_results.drain(..).collect()
    }

    pub fn drain_coarse_results(&self) -> Vec<WindowStats> {
        let mut inner = self.inner.write();
        inner.pending_coarse_results.drain(..).collect()
    }

    pub fn recent_stats(&self, service: &str, level: Option<LogLevel>) -> Vec<WindowStats> {
        let inner = self.inner.read();
        let mut results = Vec::new();
        for bucket in inner.fine_buckets.values().rev().take(6) {
            for (key, count) in &bucket.counts {
                if key.service != service {
                    continue;
                }
                if let Some(ref lv) = level {
                    if &key.level != lv {
                        continue;
                    }
                }
                let sum = bucket.sum_spend.get(key).copied().unwrap_or(0.0);
                let count_f = *count as f64;
                let avg = if count_f > 0.0 { sum / count_f } else { 0.0 };
                let (min, max, p50, p95, p99) =
                    if let Some(d) = bucket.digests.get(key) {
                        let mut dc = d.clone();
                        (
                            dc.min(),
                            dc.max(),
                            dc.quantile(0.5),
                            dc.quantile(0.95),
                            dc.quantile(0.99),
                        )
                    } else {
                        (0.0, 0.0, 0.0, 0.0, 0.0)
                    };
                results.push(WindowStats {
                    window_start: bucket.start,
                    window_end: bucket.end,
                    key: key.clone(),
                    count: *count,
                    sum_spend: sum,
                    avg_spend: avg,
                    p50_spend: p50,
                    p95_spend: p95,
                    p99_spend: p99,
                    min_spend: min,
                    max_spend: max,
                });
            }
        }
        results
    }

    pub fn services_snapshot(&self) -> Vec<(String, u64, f64, f64)> {
        let inner = self.inner.read();
        let mut svc_agg: HashMap<String, (u64, TDigest)> = HashMap::new();
        for bucket in inner.fine_buckets.values().rev().take(6) {
            for (key, count) in &bucket.counts {
                let entry = svc_agg
                    .entry(key.service.clone())
                    .or_insert_with(|| (0, TDigest::new(100.0)));
                entry.0 += count;
                if let Some(d) = bucket.digests.get(key) {
                    entry.1.merge(d);
                }
            }
        }
        let mut result: Vec<_> = svc_agg
            .into_iter()
            .map(|(svc, (cnt, mut d))| {
                let p95 = d.quantile(0.95);
                let p99 = d.quantile(0.99);
                (svc, cnt, p95, p99)
            })
            .collect();
        result.sort_by(|a, b| b.1.cmp(&a.1));
        result
    }

    pub fn error_stats_snapshot(&self) -> Vec<(String, u64, f64)> {
        let inner = self.inner.read();
        let mut svc_errors: HashMap<String, (u64, TDigest)> = HashMap::new();
        for bucket in inner.fine_buckets.values().rev().take(6) {
            for (key, count) in &bucket.counts {
                if key.level == LogLevel::Error || key.level == LogLevel::Fatal {
                    let entry = svc_errors
                        .entry(key.service.clone())
                        .or_insert_with(|| (0, TDigest::new(100.0)));
                    entry.0 += count;
                    if let Some(d) = bucket.digests.get(key) {
                        entry.1.merge(d);
                    }
                }
            }
        }
        let mut result: Vec<_> = svc_errors
            .into_iter()
            .map(|(svc, (cnt, mut d))| {
                let p99 = d.quantile(0.99);
                (svc, cnt, p99)
            })
            .collect();
        result.sort_by(|a, b| b.1.cmp(&a.1));
        result
    }

    pub fn snapshot_all_fine(&self) -> Vec<WindowStats> {
        let inner = self.inner.read();
        let mut out = Vec::new();
        for bucket in inner.fine_buckets.values() {
            out.extend(bucket.build_stats());
        }
        out
    }
}

fn floor_to_window(ts: DateTime<Utc>, window_secs: u64) -> DateTime<Utc> {
    let secs = ts.timestamp();
    let w = window_secs as i64;
    let floored = (secs / w) * w;
    DateTime::from_timestamp(floored, 0).unwrap_or(ts)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_floor_to_window() {
        let ts = DateTime::parse_from_rfc3339("2024-01-15T10:30:45Z")
            .unwrap()
            .with_timezone(&Utc);
        let f = floor_to_window(ts, 10);
        assert_eq!(f.second(), 40);
        let c = floor_to_window(ts, 300);
        assert_eq!(c.minute(), 30);
        assert_eq!(c.second(), 0);
    }
}
