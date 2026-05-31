use crate::core::{Event, EventEmitter};
use crate::types::{AppError, AppResult, HandlerRequest, HandlerResponse};
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub fn create_test_request(
    trace_id: &str,
    namespace: &str,
    params: serde_json::Value,
    payload: serde_json::Value,
) -> HandlerRequest {
    HandlerRequest {
        trace_id: trace_id.to_string(),
        namespace: namespace.to_string(),
        params,
        payload,
    }
}

pub fn create_simple_request(trace_id: &str) -> HandlerRequest {
    create_test_request(
        trace_id,
        "default",
        serde_json::json!({"validate": true}),
        serde_json::json!({"data": "test_value", "amount": 100}),
    )
}

pub struct MockEventEmitter {
    events: DashMap<String, Vec<Event>>,
    emit_count: AtomicU64,
    should_fail: bool,
    delay_ms: Option<u64>,
}

impl MockEventEmitter {
    pub fn new() -> Self {
        Self {
            events: DashMap::new(),
            emit_count: AtomicU64::new(0),
            should_fail: false,
            delay_ms: None,
        }
    }

    pub fn with_failures() -> Self {
        Self {
            should_fail: true,
            ..Self::new()
        }
    }

    pub fn with_delay(delay_ms: u64) -> Self {
        Self {
            delay_ms: Some(delay_ms),
            ..Self::new()
        }
    }

    pub fn emit_count(&self) -> u64 {
        self.emit_count.load(Ordering::SeqCst)
    }

    pub fn get_events(&self, aggregate_id: &str) -> Vec<Event> {
        self.events
            .get(aggregate_id)
            .map(|e| e.value().clone())
            .unwrap_or_default()
    }

    pub fn all_events(&self) -> Vec<Event> {
        let mut all = Vec::new();
        for entry in self.events.iter() {
            all.extend(entry.value().clone());
        }
        all
    }

    pub fn clear(&self) {
        self.events.clear();
        self.emit_count.store(0, Ordering::SeqCst);
    }
}

impl EventEmitter for MockEventEmitter {
    fn emit(&self, event: Event) {
        if let Some(delay) = self.delay_ms {
            std::thread::sleep(Duration::from_millis(delay));
        }

        if self.should_fail {
            panic!("MockEventEmitter 故意失败");
        }

        self.events
            .entry(event.aggregate_id.clone())
            .or_default()
            .push(event);
        self.emit_count.fetch_add(1, Ordering::SeqCst);
    }
}

impl Default for MockEventEmitter {
    fn default() -> Self {
        Self::new()
    }
}

pub struct CountingEventEmitter {
    count: AtomicU64,
    last_event: parking_lot::Mutex<Option<Event>>,
}

impl CountingEventEmitter {
    pub fn new() -> Self {
        Self {
            count: AtomicU64::new(0),
            last_event: parking_lot::Mutex::new(None),
        }
    }

    pub fn count(&self) -> u64 {
        self.count.load(Ordering::SeqCst)
    }

    pub fn last_event(&self) -> Option<Event> {
        self.last_event.lock().clone()
    }
}

impl EventEmitter for CountingEventEmitter {
    fn emit(&self, event: Event) {
        self.count.fetch_add(1, Ordering::SeqCst);
        *self.last_event.lock() = Some(event);
    }
}

pub struct DelayingEventEmitter {
    inner: Arc<dyn EventEmitter>,
    delay_ms: u64,
    fail_after: Option<u64>,
    call_count: AtomicU64,
}

impl DelayingEventEmitter {
    pub fn new(inner: Arc<dyn EventEmitter>, delay_ms: u64) -> Self {
        Self {
            inner,
            delay_ms,
            fail_after: None,
            call_count: AtomicU64::new(0),
        }
    }

    pub fn with_fail_after(inner: Arc<dyn EventEmitter>, delay_ms: u64, fail_after: u64) -> Self {
        Self {
            inner,
            delay_ms,
            fail_after: Some(fail_after),
            call_count: AtomicU64::new(0),
        }
    }
}

impl EventEmitter for DelayingEventEmitter {
    fn emit(&self, event: Event) {
        let count = self.call_count.fetch_add(1, Ordering::SeqCst);

        if let Some(fail_after) = self.fail_after {
            if count >= fail_after {
                panic!("DelayingEventEmitter 达到失败阈值");
            }
        }

        std::thread::sleep(Duration::from_millis(self.delay_ms));
        self.inner.emit(event);
    }
}

pub fn assert_response_success(response: &HandlerResponse) {
    assert_eq!(response.code, 200, "响应码应为200，实际为 {}: {:?}", response.code, response.message);
    assert!(response.data.is_some(), "成功响应应包含data字段");
    assert!(response.message.is_none(), "成功响应不应包含message字段");
}

pub fn assert_response_error(response: &HandlerResponse, expected_code: u16) {
    assert_eq!(response.code, expected_code, "错误响应码应为 {}，实际为 {}", expected_code, response.code);
    assert!(response.data.is_none(), "错误响应不应包含data字段");
    assert!(response.message.is_some(), "错误响应应包含message字段");
}

pub fn assert_event_emitted(emitter: &MockEventEmitter, event_type: &str, min_count: usize) {
    let events = emitter.all_events();
    let matching: Vec<_> = events.iter().filter(|e| e.event_type == event_type).collect();
    assert!(
        matching.len() >= min_count,
        "期望至少 {} 个 {} 事件，实际为 {} 个",
        min_count,
        event_type,
        matching.len()
    );
}

pub fn generate_trace_id() -> String {
    format!("trace_{}_{}", std::process::id(), chrono::Utc::now().timestamp_nanos())
}

pub async fn run_with_timeout<F, T>(future: F, timeout_ms: u64) -> AppResult<T>
where
    F: std::future::Future<Output = AppResult<T>>,
{
    tokio::time::timeout(Duration::from_millis(timeout_ms), future)
        .await
        .map_err(|_| AppError::TimeoutError)?
}

pub struct TestContext {
    pub emitter: Arc<MockEventEmitter>,
    pub metrics: Arc<crate::core::MetricsRecorder>,
    pub handler: crate::core::RequestHandler,
}

impl TestContext {
    pub fn new() -> Self {
        let emitter = Arc::new(MockEventEmitter::new());
        let metrics = Arc::new(crate::core::MetricsRecorder::new());
        let handler = crate::core::RequestHandler::new(10, emitter.clone(), metrics.clone())
            .with_timeout(5000)
            .with_retries(3);

        Self {
            emitter,
            metrics,
            handler,
        }
    }

    pub fn with_pool_size(pool_size: usize) -> Self {
        let emitter = Arc::new(MockEventEmitter::new());
        let metrics = Arc::new(crate::core::MetricsRecorder::new());
        let handler = crate::core::RequestHandler::new(pool_size, emitter.clone(), metrics.clone())
            .with_timeout(5000)
            .with_retries(3);

        Self {
            emitter,
            metrics,
            handler,
        }
    }

    pub fn with_timeout(timeout_ms: u64) -> Self {
        let emitter = Arc::new(MockEventEmitter::new());
        let metrics = Arc::new(crate::core::MetricsRecorder::new());
        let handler = crate::core::RequestHandler::new(10, emitter.clone(), metrics.clone())
            .with_timeout(timeout_ms)
            .with_retries(3);

        Self {
            emitter,
            metrics,
            handler,
        }
    }

    pub fn with_custom_emitter(emitter: Arc<dyn crate::core::EventEmitter>) -> Self {
        let metrics = Arc::new(crate::core::MetricsRecorder::new());
        let handler = crate::core::RequestHandler::new(10, emitter, metrics.clone())
            .with_timeout(5000)
            .with_retries(3);

        Self {
            emitter: Arc::new(MockEventEmitter::new()),
            metrics,
            handler,
        }
    }
}

impl Default for TestContext {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_simple_request() {
        let req = create_simple_request("test_trace_001");
        assert_eq!(req.trace_id, "test_trace_001");
        assert_eq!(req.namespace, "default");
        assert!(req.params.is_object());
        assert!(req.payload.is_object());
    }

    #[test]
    fn test_generate_trace_id_unique() {
        let id1 = generate_trace_id();
        let id2 = generate_trace_id();
        assert_ne!(id1, id2, "生成的trace_id应该唯一");
    }

    #[test]
    fn test_mock_emitter_basic() {
        let emitter = MockEventEmitter::new();
        assert_eq!(emitter.emit_count(), 0);
        assert!(emitter.all_events().is_empty());
    }

    #[tokio::test]
    async fn test_run_with_timeout_success() {
        let result = run_with_timeout(
            async { Ok::<_, AppError>("success") },
            1000,
        )
        .await;
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "success");
    }

    #[tokio::test]
    async fn test_run_with_timeout_timeout() {
        let result = run_with_timeout(
            async {
                tokio::time::sleep(Duration::from_millis(200)).await;
                Ok::<_, AppError>("too_slow")
            },
            100,
        )
        .await;
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), AppError::TimeoutError));
    }
}
