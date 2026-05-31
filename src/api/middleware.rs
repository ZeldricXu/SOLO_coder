use axum::{
    http::{Request, StatusCode},
    middleware::Next,
    response::Response,
    body::Body,
};
use std::time::Instant;
use tracing::info;

pub async fn request_logger(request: Request<Body>, next: Next) -> Result<Response, StatusCode> {
    let start = Instant::now();
    let method = request.method().clone();
    let uri = request.uri().clone();

    let response = next.run(request).await;

    let duration = start.elapsed();
    let status = response.status();

    info!(
        method = %method,
        uri = %uri,
        status = %status,
        duration = ?duration,
        "request processed"
    );

    Ok(response)
}

pub async fn request_id(request: Request<Body>, next: Next) -> Response {
    let request_id = uuid::Uuid::new_v4().to_string();

    let mut response = next.run(request).await;

    response.headers_mut().insert(
        "X-Request-Id",
        request_id.parse().unwrap(),
    );

    response
}

pub async fn cors(request: Request<Body>, next: Next) -> Response {
    let mut response = next.run(request).await;

    response.headers_mut().insert(
        "Access-Control-Allow-Origin",
        "*".parse().unwrap(),
    );
    response.headers_mut().insert(
        "Access-Control-Allow-Methods",
        "GET, POST, PUT, DELETE, OPTIONS".parse().unwrap(),
    );
    response.headers_mut().insert(
        "Access-Control-Allow-Headers",
        "Content-Type, Authorization".parse().unwrap(),
    );

    response
}
