use uuid::Uuid;
use chrono::Utc;
use std::sync::Arc;
use parking_lot::Mutex;
use serde_json::Value;

#[derive(Debug, Clone)]
pub struct RequestContext {
    pub trace_id: String,
    pub request_id: String,
    pub start_time: chrono::DateTime<Utc>,
    pub auth: Option<crate::common::auth::AuthContext>,
    pub metadata: Arc<Mutex<std::collections::HashMap<String, Value>>>,
}

impl RequestContext {
    pub fn new(trace_id: impl Into<String>) -> Self {
        Self {
            trace_id: trace_id.into(),
            request_id: Uuid::new_v4().to_string(),
            start_time: Utc::now(),
            auth: None,
            metadata: Arc::new(Mutex::new(std::collections::HashMap::new())),
        }
    }

    pub fn new_with_random() -> Self {
        Self::new(Uuid::new_v4().to_string())
    }

    pub fn with_auth(mut self, auth: crate::common::auth::AuthContext) -> Self {
        self.auth = Some(auth);
        self
    }

    pub fn set_metadata(&self, key: impl Into<String>, value: Value) {
        self.metadata.lock().insert(key.into(), value);
    }

    pub fn get_metadata(&self, key: &str) -> Option<Value> {
        self.metadata.lock().get(key).cloned()
    }

    pub fn duration_ms(&self) -> i64 {
        (Utc::now() - self.start_time).num_milliseconds()
    }

    pub fn require_auth(&self) -> crate::common::error::AppResult<&crate::common::auth::AuthContext> {
        self.auth.as_ref()
            .ok_or_else(|| crate::common::error::AppError::Unauthorized("未认证".into()))
    }
}

impl Default for RequestContext {
    fn default() -> Self {
        Self::new_with_random()
    }
}

#[derive(Debug, Clone)]
pub struct AuditLogEntry {
    pub trace_id: String,
    pub operation: String,
    pub entity_type: String,
    pub entity_id: String,
    pub operator: String,
    pub timestamp: chrono::DateTime<Utc>,
    pub success: bool,
    pub details: Value,
}

impl AuditLogEntry {
    pub fn new(
        ctx: &RequestContext,
        operation: impl Into<String>,
        entity_type: impl Into<String>,
        entity_id: impl Into<String>,
        success: bool,
        details: Value,
    ) -> Self {
        Self {
            trace_id: ctx.trace_id.clone(),
            operation: operation.into(),
            entity_type: entity_type.into(),
            entity_id: entity_id.into(),
            operator: ctx.auth.as_ref().map(|a| a.device_id.clone()).unwrap_or_else(|| "system".into()),
            timestamp: Utc::now(),
            success,
            details,
        }
    }
}

pub struct AuditLogger {
    logs: Arc<Mutex<Vec<AuditLogEntry>>>,
}

impl AuditLogger {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            logs: Arc::new(Mutex::new(Vec::new())),
        })
    }

    pub fn log(&self, entry: AuditLogEntry) {
        tracing::info!(
            trace_id = %entry.trace_id,
            operation = %entry.operation,
            entity_type = %entry.entity_type,
            entity_id = %entry.entity_id,
            success = %entry.success,
            "Audit log recorded"
        );
        self.logs.lock().push(entry);
    }

    pub fn log_operation(
        &self,
        ctx: &RequestContext,
        operation: impl Into<String>,
        entity_type: impl Into<String>,
        entity_id: impl Into<String>,
        success: bool,
        details: Value,
    ) {
        self.log(AuditLogEntry::new(ctx, operation, entity_type, entity_id, success, details));
    }

    pub fn get_recent(&self, limit: usize) -> Vec<AuditLogEntry> {
        let logs = self.logs.lock();
        logs.iter().rev().take(limit).cloned().collect()
    }
}
