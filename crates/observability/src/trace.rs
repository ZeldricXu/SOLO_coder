use axum::{
    body::Body,
    extract::Request,
    http::{HeaderMap, HeaderValue},
    middleware::Next,
    response::Response,
};
use futures::FutureExt;
use serde::{Deserialize, Serialize};
use std::future::Future;
use std::pin::Pin;
use tower::{Layer, Service};
use tracing::{debug_span, info_span, warn_span, Instrument, Span};
use uuid::Uuid;

pub const X_TRACE_ID: &str = "x-trace-id";
pub const X_SPAN_ID: &str = "x-span-id";
pub const X_REQUEST_ID: &str = "x-request-id";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TraceContext {
    pub trace_id: String,
    pub span_id: String,
    pub parent_span_id: Option<String>,
    pub request_id: Option<String>,
}

impl TraceContext {
    pub fn new() -> Self {
        Self {
            trace_id: generate_trace_id(),
            span_id: generate_span_id(),
            parent_span_id: None,
            request_id: None,
        }
    }

    pub fn with_request_id(mut self, request_id: String) -> Self {
        self.request_id = Some(request_id);
        self
    }

    pub fn from_headers(headers: &HeaderMap) -> Self {
        let trace_id = headers
            .get(X_TRACE_ID)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .unwrap_or_else(generate_trace_id);

        let parent_span_id = headers
            .get(X_SPAN_ID)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());

        let request_id = headers
            .get(X_REQUEST_ID)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());

        Self {
            trace_id,
            span_id: generate_span_id(),
            parent_span_id,
            request_id,
        }
    }

    pub fn inject_headers(&self, headers: &mut HeaderMap) {
        if let Ok(v) = HeaderValue::from_str(&self.trace_id) {
            headers.insert(X_TRACE_ID, v);
        }
        if let Ok(v) = HeaderValue::from_str(&self.span_id) {
            headers.insert(X_SPAN_ID, v);
        }
        if let Some(rid) = &self.request_id {
            if let Ok(v) = HeaderValue::from_str(rid) {
                headers.insert(X_REQUEST_ID, v);
            }
        }
    }

    pub fn inject_grpc_metadata(&self, metadata: &mut tonic::metadata::MetadataMap) {
        use tonic::metadata::AsciiMetadataValue;
        use std::str::FromStr;

        if let Ok(key) = tonic::metadata::MetadataKey::from_bytes(X_TRACE_ID.as_bytes()) {
            if let Ok(val) = AsciiMetadataValue::from_str(&self.trace_id) {
                metadata.insert(key, val);
            }
        }
        if let Ok(key) = tonic::metadata::MetadataKey::from_bytes(X_SPAN_ID.as_bytes()) {
            if let Ok(val) = AsciiMetadataValue::from_str(&self.span_id) {
                metadata.insert(key, val);
            }
        }
        if let Some(rid) = &self.request_id {
            if let Ok(key) = tonic::metadata::MetadataKey::from_bytes(X_REQUEST_ID.as_bytes()) {
                if let Ok(val) = AsciiMetadataValue::from_str(rid) {
                    metadata.insert(key, val);
                }
            }
        }
    }

    pub fn from_grpc_metadata(metadata: &tonic::metadata::MetadataMap) -> Self {
        let trace_id = metadata
            .get(X_TRACE_ID)
            .and_then(|v| v.to_bytes().ok())
            .and_then(|b| String::from_utf8(b.to_vec()).ok())
            .unwrap_or_else(generate_trace_id);

        let parent_span_id = metadata
            .get(X_SPAN_ID)
            .and_then(|v| v.to_bytes().ok())
            .and_then(|b| String::from_utf8(b.to_vec()).ok());

        let request_id = metadata
            .get(X_REQUEST_ID)
            .and_then(|v| v.to_bytes().ok())
            .and_then(|b| String::from_utf8(b.to_vec()).ok());

        Self {
            trace_id,
            span_id: generate_span_id(),
            parent_span_id,
            request_id,
        }
    }

    pub fn child(&self, _span_name: &str) -> Self {
        Self {
            trace_id: self.trace_id.clone(),
            span_id: generate_span_id(),
            parent_span_id: Some(self.span_id.clone()),
            request_id: self.request_id.clone(),
        }
    }
}

impl Default for TraceContext {
    fn default() -> Self {
        Self::new()
    }
}

fn generate_trace_id() -> String {
    Uuid::new_v4().to_string()
}

fn generate_span_id() -> String {
    let mut buf = [0u8; 8];
    getrandom::getrandom(&mut buf).unwrap_or_else(|_| {
        let t = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        buf.copy_from_slice(&t.to_le_bytes()[..8]);
    });
    hex::encode(buf)
}

#[derive(Debug, Clone)]
pub struct RequestTracingMiddleware {
    service_name: String,
}

impl RequestTracingMiddleware {
    pub fn new(service_name: impl Into<String>) -> Self {
        Self {
            service_name: service_name.into(),
        }
    }
}

impl<S> Layer<S> for RequestTracingMiddleware {
    type Service = RequestTracingService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        RequestTracingService {
            inner,
            service_name: self.service_name.clone(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct RequestTracingService<S> {
    inner: S,
    service_name: String,
}

impl<S> Service<Request<Body>> for RequestTracingService<S>
where
    S: Service<Request<Body>, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
{
    type Response = S::Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(
        &mut self,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<Body>) -> Self::Future {
        let clone = self.inner.clone();
        let mut inner = std::mem::replace(&mut self.inner, clone);
        let service_name = self.service_name.clone();

        async move {
            let (parts, body) = req.into_parts();

            let trace_context = TraceContext::from_headers(&parts.headers);
            let method = parts.method.clone();
            let uri = parts.uri.clone();
            let path = parts.uri.path().to_string();

            let span = info_span!(
                "http_request",
                service.name = %service_name,
                trace_id = %trace_context.trace_id,
                span_id = %trace_context.span_id,
                parent_span_id = %trace_context.parent_span_id.clone().unwrap_or_default(),
                http.method = %method,
                http.uri = %uri,
                http.path = %path,
                request_id = %trace_context.request_id.clone().unwrap_or_default(),
            );

            let start = std::time::Instant::now();
            let req = Request::from_parts(parts, body);

            let mut response = inner.call(req).instrument(span.clone()).await?;

            let duration_ms = start.elapsed().as_secs_f64() * 1000.0;
            let status = response.status().as_u16();

            trace_context.inject_headers(response.headers_mut());

            span.record("http.status_code", status);
            span.record("duration_ms", duration_ms);

            if status >= 500 {
                warn_span!(
                    parent: &span,
                    "http_error",
                    status_code = status,
                    duration_ms = duration_ms,
                );
            } else if status >= 400 {
                debug_span!(
                    parent: &span,
                    "http_client_error",
                    status_code = status,
                    duration_ms = duration_ms,
                );
            }

            Ok(response)
        }
        .boxed()
    }
}

pub fn gateway_span(trace_context: &TraceContext, method: &str, path: &str) -> Span {
    info_span!(
        "gateway_request",
        trace_id = %trace_context.trace_id,
        span_id = %trace_context.span_id,
        parent_span_id = %trace_context.parent_span_id.clone().unwrap_or_default(),
        http.method = %method,
        http.path = %path,
        component = "gateway",
    )
}

pub fn router_span(trace_context: &TraceContext, model_name: &str, strategy: &str) -> Span {
    info_span!(
        "routing_decision",
        trace_id = %trace_context.trace_id,
        span_id = %trace_context.span_id,
        parent_span_id = %trace_context.parent_span_id.clone().unwrap_or_default(),
        model.name = %model_name,
        routing.strategy = %strategy,
        component = "traffic_router",
    )
}

pub fn runtime_span(
    trace_context: &TraceContext,
    model_name: &str,
    version: &str,
    gpu_id: &str,
) -> Span {
    info_span!(
        "inference_runtime",
        trace_id = %trace_context.trace_id,
        span_id = %trace_context.span_id,
        parent_span_id = %trace_context.parent_span_id.clone().unwrap_or_default(),
        model.name = %model_name,
        model.version = %version,
        gpu.id = %gpu_id,
        component = "inference_runtime",
    )
}

pub fn model_forward_span(
    trace_context: &TraceContext,
    model_name: &str,
    version: &str,
    batch_size: u32,
) -> Span {
    info_span!(
        "model_forward",
        trace_id = %trace_context.trace_id,
        span_id = %trace_context.span_id,
        parent_span_id = %trace_context.parent_span_id.clone().unwrap_or_default(),
        model.name = %model_name,
        model.version = %version,
        batch.size = batch_size,
        component = "model_execution",
    )
}

pub async fn tracing_middleware(req: Request<Body>, next: Next) -> Response {
    let (parts, body) = req.into_parts();

    let trace_context = TraceContext::from_headers(&parts.headers);
    let method = parts.method.clone();
    let path = parts.uri.path().to_string();

    let span = info_span!(
        "request",
        trace_id = %trace_context.trace_id,
        span_id = %trace_context.span_id,
        parent_span_id = %trace_context.parent_span_id.clone().unwrap_or_default(),
        http.method = %method,
        http.path = %path,
    );

    let req = Request::from_parts(parts, body);
    let mut response = next.run(req).instrument(span).await;

    trace_context.inject_headers(response.headers_mut());

    response
}

pub mod otel {
    use super::*;
    use opentelemetry::trace::{SpanContext, SpanId as OtelSpanId, TraceId as OtelTraceId, TraceFlags, TraceState};
    use std::str::FromStr;

    pub fn trace_context_to_otel(tc: &TraceContext) -> SpanContext {
        let trace_id_bytes = hex_to_bytes_16(&tc.trace_id);
        let span_id_bytes = hex_to_bytes_8(&tc.span_id);

        let trace_id = match trace_id_bytes {
            Some(b) => OtelTraceId::from_bytes(b),
            None => OtelTraceId::INVALID,
        };

        let span_id = match span_id_bytes {
            Some(b) => OtelSpanId::from_bytes(b),
            None => OtelSpanId::INVALID,
        };

        let trace_flags = TraceFlags::SAMPLED;

        SpanContext::new(trace_id, span_id, trace_flags, true, TraceState::default())
    }

    pub fn otel_to_trace_context(sc: &SpanContext) -> Option<TraceContext> {
        if !sc.is_valid() {
            return None;
        }

        let trace_id = hex::encode(sc.trace_id().to_bytes());
        let span_id = hex::encode(sc.span_id().to_bytes());

        Some(TraceContext {
            trace_id,
            span_id,
            parent_span_id: None,
            request_id: None,
        })
    }

    pub fn extract_current() -> Option<TraceContext> {
        use opentelemetry::trace::TraceContextExt;
        let cx = opentelemetry::Context::current();
        let span = cx.span();
        let span_context = span.span_context();
        otel_to_trace_context(span_context)
    }

    fn hex_to_bytes_16(s: &str) -> Option<[u8; 16]> {
        if s.len() != 32 {
            return None;
        }
        let bytes = hex::decode(s).ok()?;
        if bytes.len() != 16 {
            return None;
        }
        let mut arr = [0u8; 16];
        arr.copy_from_slice(&bytes);
        Some(arr)
    }

    fn hex_to_bytes_8(s: &str) -> Option<[u8; 8]> {
        if s.len() != 16 {
            return None;
        }
        let bytes = hex::decode(s).ok()?;
        if bytes.len() != 8 {
            return None;
        }
        let mut arr = [0u8; 8];
        arr.copy_from_slice(&bytes);
        Some(arr)
    }

    pub fn set_otel_attributes_from_trace_context(tc: &TraceContext) -> Vec<opentelemetry::KeyValue> {
        let mut attrs = vec![
            opentelemetry::KeyValue::new("trace_id", tc.trace_id.clone()),
            opentelemetry::KeyValue::new("span_id", tc.span_id.clone()),
        ];
        if let Some(parent) = &tc.parent_span_id {
            attrs.push(opentelemetry::KeyValue::new("parent_span_id", parent.clone()));
        }
        if let Some(rid) = &tc.request_id {
            attrs.push(opentelemetry::KeyValue::new("request_id", rid.clone()));
        }
        attrs
    }
}

pub fn with_trace_context<F, R>(tc: &TraceContext, f: F) -> R
where
    F: FnOnce() -> R,
{
    use opentelemetry::trace::TraceContextExt;

    let otel_sc = otel::trace_context_to_otel(tc);
    let parent_cx = opentelemetry::Context::current();
    let cx = parent_cx.with_remote_span_context(otel_sc);

    let _guard = cx.attach();
    f()
}

pub async fn async_with_trace_context<F, R>(tc: &TraceContext, fut: F) -> R
where
    F: std::future::Future<Output = R>,
{
    use opentelemetry::trace::TraceContextExt;

    let otel_sc = otel::trace_context_to_otel(tc);
    let parent_cx = opentelemetry::Context::current();
    let cx = parent_cx.with_remote_span_context(otel_sc);

    let _guard = cx.attach();
    fut.await
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::{HeaderMap, HeaderName, Method};

    #[test]
    fn test_trace_context_new() {
        let ctx = TraceContext::new();
        assert!(!ctx.trace_id.is_empty());
        assert!(!ctx.span_id.is_empty());
        assert!(ctx.parent_span_id.is_none());
    }

    #[test]
    fn test_trace_context_from_headers_with_trace_id() {
        let mut headers = HeaderMap::new();
        headers.insert(
            HeaderName::from_static(X_TRACE_ID),
            HeaderValue::from_static("test-trace-id-123"),
        );
        headers.insert(
            HeaderName::from_static(X_SPAN_ID),
            HeaderValue::from_static("parent-span-456"),
        );

        let ctx = TraceContext::from_headers(&headers);
        assert_eq!(ctx.trace_id, "test-trace-id-123");
        assert_eq!(ctx.parent_span_id, Some("parent-span-456".to_string()));
        assert!(!ctx.span_id.is_empty());
    }

    #[test]
    fn test_trace_context_from_headers_empty() {
        let headers = HeaderMap::new();
        let ctx = TraceContext::from_headers(&headers);
        assert!(!ctx.trace_id.is_empty());
        assert!(ctx.parent_span_id.is_none());
    }

    #[test]
    fn test_inject_headers() {
        let ctx = TraceContext::new().with_request_id("req-789".to_string());
        let mut headers = HeaderMap::new();
        ctx.inject_headers(&mut headers);

        assert_eq!(
            headers.get(X_TRACE_ID).unwrap().to_str().unwrap(),
            ctx.trace_id
        );
        assert_eq!(
            headers.get(X_SPAN_ID).unwrap().to_str().unwrap(),
            ctx.span_id
        );
        assert_eq!(
            headers.get(X_REQUEST_ID).unwrap().to_str().unwrap(),
            "req-789"
        );
    }

    #[test]
    fn test_child_context() {
        let parent = TraceContext::new();
        let child = parent.child("test");

        assert_eq!(parent.trace_id, child.trace_id);
        assert_eq!(child.parent_span_id, Some(parent.span_id.clone()));
        assert_ne!(parent.span_id, child.span_id);
    }

    #[test]
    fn test_generate_span_id_format() {
        let id = generate_span_id();
        assert_eq!(id.len(), 16);
        assert!(id.chars().all(|c| c.is_ascii_hexdigit()));
    }
}
