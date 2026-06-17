use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use dashmap::{DashMap, DashSet};
use metrics::{counter, gauge};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::config::RateLimitConfig;

#[derive(Debug, Clone)]
struct TokenBucket {
    capacity: u64,
    tokens: f64,
    refill_rate_per_sec: f64,
    last_refill: Instant,
}

impl TokenBucket {
    fn new(capacity: u64, refill_rate_per_sec: f64) -> Self {
        Self {
            capacity,
            tokens: capacity as f64,
            refill_rate_per_sec,
            last_refill: Instant::now(),
        }
    }

    fn refill(&mut self) {
        let now = Instant::now();
        let elapsed = now.duration_since(self.last_refill).as_secs_f64();
        if elapsed > 0.0 {
            self.tokens = (self.tokens + elapsed * self.refill_rate_per_sec)
                .min(self.capacity as f64);
            self.last_refill = now;
        }
    }

    fn try_consume(&mut self, n: u64) -> bool {
        self.refill();
        if self.tokens >= n as f64 {
            self.tokens -= n as f64;
            true
        } else {
            false
        }
    }

    fn retry_after_secs(&self, n: u64) -> f64 {
        let needed = n as f64 - self.tokens;
        if needed <= 0.0 {
            0.0
        } else {
            needed / self.refill_rate_per_sec.max(0.0001)
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum RateLimitKey {
    Connection(Uuid),
    Document(Uuid),
    User(String),
    Ip(String),
}

#[derive(Debug, Clone)]
pub struct RateLimitHit {
    pub key: RateLimitKey,
    pub allowed: bool,
    pub retry_after: Option<f64>,
    pub tokens_remaining: f64,
}

#[derive(Debug, Clone)]
pub struct RateLimiter {
    config: RateLimitConfig,
    per_connection: DashMap<Uuid, TokenBucket>,
    per_document: DashMap<Uuid, TokenBucket>,
    hit_log: DashMap<RateLimitKey, u64>,
    last_cleanup: Arc<Mutex<Instant>>,
}

impl RateLimiter {
    pub fn new(config: RateLimitConfig) -> Self {
        Self {
            config,
            per_connection: DashMap::new(),
            per_document: DashMap::new(),
            hit_log: DashMap::new(),
            last_cleanup: Arc::new(Mutex::new(Instant::now())),
        }
    }

    pub fn check_connection(&self, session_id: Uuid, cost: u64) -> RateLimitHit {
        let capacity = self.config.per_connection_burst;
        let refill_rate = self.config.per_connection_ops_per_min as f64 / 60.0;

        let mut bucket = self.per_connection
            .entry(session_id)
            .or_insert_with(|| TokenBucket::new(capacity, refill_rate))
            .clone();

        let allowed = bucket.try_consume(cost);
        let retry = if allowed { None } else { Some(bucket.retry_after_secs(cost)) };

        self.per_connection.insert(session_id, bucket.clone());

        if !allowed {
            self.hit_log
                .entry(RateLimitKey::Connection(session_id))
                .and_modify(|c| *c += 1)
                .or_insert(1);
            counter!("collab_ratelimited_total", "scope" => "connection").increment(1);
        }

        RateLimitHit {
            key: RateLimitKey::Connection(session_id),
            allowed,
            retry_after: retry,
            tokens_remaining: bucket.tokens,
        }
    }

    pub fn check_document(&self, document_id: Uuid, cost: u64) -> RateLimitHit {
        let capacity = self.config.per_document_burst;
        let refill_rate = self.config.per_document_ops_per_sec as f64;

        let mut bucket = self.per_document
            .entry(document_id)
            .or_insert_with(|| TokenBucket::new(capacity, refill_rate))
            .clone();

        let allowed = bucket.try_consume(cost);
        let retry = if allowed { None } else { Some(bucket.retry_after_secs(cost)) };

        self.per_document.insert(document_id, bucket.clone());

        if !allowed {
            self.hit_log
                .entry(RateLimitKey::Document(document_id))
                .and_modify(|c| *c += 1)
                .or_insert(1);
            counter!("collab_ratelimited_total", "scope" => "document").increment(1);
        }

        RateLimitHit {
            key: RateLimitKey::Document(document_id),
            allowed,
            retry_after: retry,
            tokens_remaining: bucket.tokens,
        }
    }

    pub fn check_both(
        &self,
        session_id: Uuid,
        document_id: Uuid,
        cost: u64,
    ) -> Result<(), (RateLimitKey, f64)> {
        let conn_hit = self.check_connection(session_id, cost);
        if !conn_hit.allowed {
            return Err((conn_hit.key, conn_hit.retry_after.unwrap_or(1.0)));
        }

        let doc_hit = self.check_document(document_id, cost);
        if !doc_hit.allowed {
            return Err((doc_hit.key, doc_hit.retry_after.unwrap_or(1.0)));
        }

        Ok(())
    }

    pub fn remove_connection(&self, session_id: &Uuid) {
        self.per_connection.remove(session_id);
    }

    pub fn remove_document(&self, document_id: &Uuid) {
        self.per_document.remove(document_id);
    }

    pub fn cleanup_stale(&self, age: Duration) {
        let now = Instant::now();
        let last = *self.last_cleanup.lock().unwrap();
        if now.duration_since(last) < Duration::from_secs(self.config.cleanup_interval_secs) {
            return;
        }
        *self.last_cleanup.lock().unwrap() = now;

        let cutoff = now - age;
        self.per_connection.retain(|_, _b| {
            true
        });

        self.hit_log.retain(|_, _| true);

        gauge!("collab_ratelimit_tracker_count").set(
            (self.per_connection.len() + self.per_document.len()) as f64
        );
    }

    pub fn stats(&self) -> RateLimitStats {
        RateLimitStats {
            tracked_connections: self.per_connection.len(),
            tracked_documents: self.per_document.len(),
            total_hits: self.hit_log.iter().map(|h| *h.value()).sum(),
        }
    }

    pub fn capacity(&self) -> usize {
        self.config.max_ws_connections
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RateLimitStats {
    pub tracked_connections: usize,
    pub tracked_documents: usize,
    pub total_hits: u64,
}
