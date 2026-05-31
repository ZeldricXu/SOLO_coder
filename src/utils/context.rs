use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RequestContext {
    pub trace_id: String,
    pub user_id: Option<String>,
    pub namespace: String,
    pub created_at: DateTime<Utc>,
    pub timeout_at: Option<DateTime<Utc>>,
    pub data: Arc<DashMap<String, serde_json::Value>>,
}

impl RequestContext {
    pub fn new(trace_id: Option<String>, namespace: String) -> Self {
        let now = Utc::now();
        Self {
            trace_id: trace_id.unwrap_or_else(|| Uuid::new_v4().to_string()),
            user_id: None,
            namespace,
            created_at: now,
            timeout_at: None,
            data: Arc::new(DashMap::new()),
        }
    }

    pub fn with_timeout(mut self, seconds: u64) -> Self {
        self.timeout_at = Some(self.created_at + chrono::Duration::seconds(seconds as i64));
        self
    }

    pub fn with_user_id(mut self, user_id: String) -> Self {
        self.user_id = Some(user_id);
        self
    }

    pub fn is_timed_out(&self) -> bool {
        self.timeout_at
            .map(|t| Utc::now() > t)
            .unwrap_or(false)
    }

    pub fn set<K: Into<String>, V: Into<serde_json::Value>>(&self, key: K, value: V) {
        self.data.insert(key.into(), value.into());
    }

    pub fn get<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Option<T> {
        self.data.get(key)
            .and_then(|v| serde_json::from_value(v.value().clone()).ok())
    }

    pub fn elapsed_ms(&self) -> i64 {
        (Utc::now() - self.created_at).num_milliseconds()
    }

    pub fn cleanup(&self) {
        self.data.clear();
    }
}

#[derive(Debug, Clone, Default)]
pub struct TransactionContext {
    rollback_actions: Vec<Box<dyn FnOnce() + Send + Sync>>,
}

impl TransactionContext {
    pub fn new() -> Self {
        Self {
            rollback_actions: Vec::new(),
        }
    }

    pub fn add_rollback<F>(&mut self, action: F)
    where
        F: FnOnce() + Send + Sync + 'static,
    {
        self.rollback_actions.push(Box::new(action));
    }

    pub fn rollback(self) {
        for action in self.rollback_actions.into_iter().rev() {
            action();
        }
    }

    pub fn commit(self) {
        std::mem::drop(self.rollback_actions);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_request_context() {
        let ctx = RequestContext::new(Some("trace_123".to_string()), "production".to_string());
        assert_eq!(ctx.trace_id, "trace_123");
        assert_eq!(ctx.namespace, "production");
        assert!(!ctx.is_timed_out());

        ctx.set("key", "value");
        assert_eq!(ctx.get::<String>("key"), Some("value".to_string()));
    }

    #[test]
    fn test_transaction_rollback() {
        let mut rollback_called = false;
        {
            let mut tx = TransactionContext::new();
            tx.add_rollback(|| {
                rollback_called = true;
            });
            tx.rollback();
        }
        assert!(rollback_called);
    }

    #[test]
    fn test_transaction_commit() {
        let mut rollback_called = false;
        {
            let mut tx = TransactionContext::new();
            tx.add_rollback(|| {
                rollback_called = true;
            });
            tx.commit();
        }
        assert!(!rollback_called);
    }
}
