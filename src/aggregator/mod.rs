pub mod tdigest;

use crate::{AggregationKey, LogLevel, LogRecord, WindowStats};
use crate::aggregator::tdigest::TDigest;
use crate::config::ConfigHandle;
use chrono::{DateTime, Duration, Utc};
use parking_lot::RwLock;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use tokio::sync::broadcast;
use tracing::{debug, info};

const FINE_SLOTS: usize = 32;
const COARSE_SLOTS: usize = 32;

struct WindowBucket {
    start: Option<DateTime<Utc>>,
    counts: HashMap<AggregationKey, u64>,
    sum_spend: HashMap<AggregationKey, f64>,
    digests: HashMap<AggregationKey, TDigest>,
    tdigest_compression: f64,
}

impl WindowBucket {
    fn empty(compression: f64) -> Self {
        Self {
            start: None,
            counts: HashMap::new(),
            sum_spend: HashMap::new(),
            digests: HashMap::new(),
            tdigest_compression: compression,
        }
    }

    fn reset(&mut self, start: DateTime<Utc>) {
        self.start = Some(start);
        self.counts.clear();
        self.sum_spend.clear();
        self.digests.clear();
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

    fn build_stats(&self, start: DateTime<Utc>, window_secs: u64) -> Vec<WindowStats> {
        let end = start + Duration::seconds(window_secs as i64);
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
                window_start: start,
                window_end: end,
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

struct WindowRing {
    window_secs: u64,
    slots: Vec<WindowBucket>,
    last_emitted_index: Option<u64>,
}

impl WindowRing {
    fn new(window_secs: u64, num_slots: usize, compression: f64) -> Self {
        let mut slots = Vec::with_capacity(num_slots);
        for _ in 0..num_slots {
            slots.push(WindowBucket::empty(compression));
        }
        Self {
            window_secs,
            slots,
            last_emitted_index: None,
        }
    }

    #[inline]
    fn slot_index_for(&self, start: DateTime<Utc>) -> usize {
        let window_idx = start.timestamp() as u64 / self.window_secs;
        (window_idx % self.slots.len() as u64) as usize
    }

    #[inline]
    fn window_index(&self, start: DateTime<Utc>) -> u64 {
        start.timestamp() as u64 / self.window_secs
    }

    fn bucket_for(&mut self, start: DateTime<Utc>) -> &mut WindowBucket {
        let idx = self.slot_index_for(start);
        let bucket = &mut self.slots[idx];
        match bucket.start {
            Some(existing) if existing == start => {}
            _ => {
                bucket.reset(start);
            }
        }
        bucket
    }

    fn emit_expired(&mut self, now: DateTime<Utc>, cutoff_windows: u64) -> Vec<WindowStats> {
        let cutoff_index = (now.timestamp() as u64 / self.window_secs).saturating_sub(cutoff_windows);
        let mut out = Vec::new();

        let start_idx = match self.last_emitted_index {
            Some(li) => li + 1,
            None => cutoff_index.saturating_sub(self.slots.len() as u64),
        };

        if start_idx > cutoff_index {
            return out;
        }

        for wi in start_idx..=cutoff_index {
            let slot_idx = (wi % self.slots.len() as u64) as usize;
            let bucket = &mut self.slots[slot_idx];
            if let Some(bucket_start) = bucket.start {
                let bucket_wi = self.window_index(bucket_start);
                if bucket_wi == wi && !bucket.counts.is_empty() {
                    let stats = bucket.build_stats(bucket_start, self.window_secs);
                    out.extend(stats);
                    bucket.start = None;
                }
            }
        }

        self.last_emitted_index = Some(cutoff_index);
        out
    }

    fn snapshot_recent(&self, limit: usize) -> Vec<&WindowBucket> {
        let mut out: Vec<&WindowBucket> = self.slots.iter().filter(|b| b.start.is_some()).collect();
        out.sort_by_key(|b| std::cmp::Reverse(b.start));
        out.truncate(limit);
        out
    }

    fn snapshot_all(&self) -> Vec<WindowStats> {
        let mut out = Vec::new();
        for bucket in &self.slots {
            if let Some(start) = bucket.start {
                if !bucket.counts.is_empty() {
                    out.extend(bucket.build_stats(start, self.window_secs));
                }
            }
        }
        out.sort_by_key(|s| s.window_start);
        out
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
    fine_ring: WindowRing,
    coarse_ring: WindowRing,
    last_fine_emitted: Option<DateTime<Utc>>,
    last_coarse_emitted: Option<DateTime<Utc>>,
    pending_fine_results: VecDeque<WindowStats>,
    pending_coarse_results: VecDeque<WindowStats>,
    initialized: bool,
}

impl AggregationEngine {
    pub fn new(config: ConfigHandle) -> Self {
        let (event_sender, _) = broadcast::channel(1024);
        let (fine_secs, coarse_secs, compression) = {
            let _ = std::panic::catch_unwind(|| {
                tokio::runtime::Handle::try_current().ok();
            });
            (10u64, 300u64, 100.0f64)
        };

        let fine_ring = WindowRing::new(fine_secs, FINE_SLOTS, compression);
        let coarse_ring = WindowRing::new(coarse_secs, COARSE_SLOTS, compression);

        let inner = AggregationInner {
            fine_window_secs: fine_secs,
            coarse_window_secs: coarse_secs,
            tdigest_compression: compression,
            fine_ring,
            coarse_ring,
            last_fine_emitted: None,
            last_coarse_emitted: None,
            pending_fine_results: VecDeque::new(),
            pending_coarse_results: VecDeque::new(),
            initialized: false,
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
        let new_fine = cfg.pipeline.fine_grained_window_secs;
        let new_coarse = cfg.pipeline.coarse_grained_window_secs;
        let new_compression = cfg.pipeline.tdigest_compression;

        if !inner.initialized {
            inner.fine_window_secs = new_fine;
            inner.coarse_window_secs = new_coarse;
            inner.tdigest_compression = new_compression;
            inner.fine_ring = WindowRing::new(new_fine, FINE_SLOTS, new_compression);
            inner.coarse_ring = WindowRing::new(new_coarse, COARSE_SLOTS, new_compression);
            inner.initialized = true;
        } else if inner.fine_window_secs != new_fine
            || inner.coarse_window_secs != new_coarse
        {
            warn!(
                "Window sizes changed ({}/{} -> {}/{}), but ring array requires fixed size at init; keeping old.",
                inner.fine_window_secs, inner.coarse_window_secs,
                new_fine, new_coarse
            );
            inner.tdigest_compression = new_compression;
        } else {
            inner.tdigest_compression = new_compression;
        }
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
        let fine_bucket = inner.fine_ring.bucket_for(fine_start);
        fine_bucket.ingest(&key, spend);

        let coarse_start = floor_to_window(ts, inner.coarse_window_secs);
        let coarse_bucket = inner.coarse_ring.bucket_for(coarse_start);
        coarse_bucket.ingest(&key, spend);
    }

    pub fn tick(&self, now: DateTime<Utc>) {
        let mut inner = self.inner.write();

        let fine_stats = inner.fine_ring.emit_expired(now, 2);
        if !fine_stats.is_empty() {
            if let Some(last) = fine_stats.last() {
                inner.last_fine_emitted = Some(last.window_start);
            }
            debug!(
                "Emitting fine window stats ({} keys)",
                fine_stats.len()
            );
            inner.pending_fine_results.extend(fine_stats.clone());
            let to_broadcast = fine_stats.clone();
            drop(inner);
            let _ = self.event_sender.send(to_broadcast);
            return;
        }

        let coarse_stats = inner.coarse_ring.emit_expired(now, 2);
        if !coarse_stats.is_empty() {
            if let Some(last) = coarse_stats.last() {
                inner.last_coarse_emitted = Some(last.window_start);
            }
            let total: u64 = coarse_stats.iter().map(|s| s.count).sum();
            info!(
                "Emitting coarse window stats ({} keys, total count={})",
                coarse_stats.len(),
                total
            );
            inner.pending_coarse_results.extend(coarse_stats);
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
        let buckets = inner.fine_ring.snapshot_recent(6);
        for bucket in buckets {
            let start = bucket.start.unwrap();
            let end = start + Duration::seconds(inner.fine_window_secs as i64);
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
                    window_start: start,
                    window_end: end,
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
        let buckets = inner.fine_ring.snapshot_recent(6);
        for bucket in buckets {
            for (key, count) in &bucket.counts {
                let entry = svc_agg
                    .entry(key.service.clone())
                    .or_insert_with(|| (0, TDigest::new(inner.tdigest_compression)));
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
        let buckets = inner.fine_ring.snapshot_recent(6);
        for bucket in buckets {
            for (key, count) in &bucket.counts {
                if key.level == LogLevel::Error || key.level == LogLevel::Fatal {
                    let entry = svc_errors
                        .entry(key.service.clone())
                        .or_insert_with(|| (0, TDigest::new(inner.tdigest_compression)));
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
        inner.fine_ring.snapshot_all()
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
    use tracing::Level;

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

    #[test]
    fn test_window_ring_basic() {
        let mut ring = WindowRing::new(10, 8, 100.0);
        let ts = DateTime::parse_from_rfc3339("2024-01-15T10:30:45Z")
            .unwrap()
            .with_timezone(&Utc);
        let start = floor_to_window(ts, 10);

        let bucket = ring.bucket_for(start);
        assert_eq!(bucket.start, Some(start));

        let key = AggregationKey {
            service: "svc".into(),
            level: crate::LogLevel::Info,
        };
        bucket.ingest(&key, Some(10.0));
        bucket.ingest(&key, Some(20.0));

        let now = start + Duration::seconds(30);
        let emitted = ring.emit_expired(now, 2);
        assert!(emitted.len() >= 1);
        assert_eq!(emitted[0].count, 2);
        assert_eq!(emitted[0].sum_spend, 30.0);
    }
}
