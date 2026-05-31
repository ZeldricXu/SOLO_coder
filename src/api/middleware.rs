use axum::{
    http::{Request, StatusCode},
    middleware::Next,
    response::Response,
};
use tracing::{info, warn, error};
use uuid::Uuid;

pub async fn request_tracing<B>(mut request: Request<B>, next: Next<B>) -> Result<Response, StatusCode> {
    let trace_id = request
        .headers()
        .get("x-trace-id")
        .and_then(|h| h.to_str().ok())
        .map(|s| s.to_string())
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    let method = request.method().clone();
    let path = request.uri().path().to_string();

    request.headers_mut().insert(
        "x-trace-id",
        trace_id.parse().expect("Invalid trace id"),
    );

    info!(
        trace_id = %trace_id,
        method = %method,
        path = %path,
        "Request started"
    );

    let start = std::time::Instant::now();
    let response = next.run(request).await;
    let duration = start.elapsed();
    let status = response.status();

    if status.is_success() {
        info!(
            trace_id = %trace_id,
            method = %method,
            path = %path,
            status = %status.as_u16(),
            duration_ms = %duration.as_millis(),
            "Request completed successfully"
        );
    } else if status.is_client_error() {
        warn!(
            trace_id = %trace_id,
            method = %method,
            path = %path,
            status = %status.as_u16(),
            duration_ms = %duration.as_millis(),
            "Request client error"
        );
    } else {
        error!(
            trace_id = %trace_id,
            method = %method,
            path = %path,
            status = %status.as_u16(),
            duration_ms = %duration.as_millis(),
            "Request server error"
        );
    }

    Ok(response)
}

pub async fn request_timeout<B>(request: Request<B>, next: Next<B>) -> Result<Response, StatusCode> {
    let timeout = std::time::Duration::from_secs(30);

    match tokio::time::timeout(timeout, next.run(request)).await {
        Ok(response) => Ok(response),
        Err(_) => Err(StatusCode::GATEWAY_TIMEOUT),
    }
}

pub async fn cors<B>(request: Request<B>, next: Next<B>) -> Response {
    let mut response = next.run(request).await;

    response.headers_mut().insert(
        axum::http::header::ACCESS_CONTROL_ALLOW_ORIGIN,
        "*".parse().unwrap(),
    );
    response.headers_mut().insert(
        axum::http::header::ACCESS_CONTROL_ALLOW_METHODS,
        "GET, POST, PUT, DELETE, OPTIONS".parse().unwrap(),
    );
    response.headers_mut().insert(
        axum::http::header::ACCESS_CONTROL_ALLOW_HEADERS,
        "Content-Type, Authorization, X-Trace-Id, X-Signature, X-Timestamp".parse().unwrap(),
    );

    response
}
