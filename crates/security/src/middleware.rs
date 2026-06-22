use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Instant;

use axum::body::Body;
use axum::http::{HeaderValue, Request, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use common::error::ErrorResponse;
use db::{DatabasePool, RedisClient, Tenant};
use pin_project_lite::pin_project;
use tower::{Layer, Service};
use tracing::{debug, error, info, warn};

use crate::auth::{ApiKeyAuthenticator, X_API_KEY_HEADER};
use crate::rate_limit::{RateLimitConfig, RateLimitExceededType, RateLimiter};

const X_TENANT_ID: &str = "X-Tenant-Id";
const X_RATELIMIT_QPS_REMAINING: &str = "X-RateLimit-Qps-Remaining";
const X_RATELIMIT_QPS_LIMIT: &str = "X-RateLimit-Qps-Limit";
const X_RATELIMIT_PERMIN_REMAINING: &str = "X-RateLimit-PerMin-Remaining";
const X_RATELIMIT_PERMIN_LIMIT: &str = "X-RateLimit-PerMin-Limit";
const X_REQUEST_ID: &str = "X-Request-Id";
const X_PROCESS_TIME_MS: &str = "X-Process-Time-Ms";

#[derive(Clone)]
pub struct AuthLayer {
    db_pool: DatabasePool,
    redis_client: RedisClient,
}

impl std::fmt::Debug for AuthLayer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthLayer").finish()
    }
}

impl AuthLayer {
    pub fn new(db_pool: DatabasePool, redis_client: RedisClient) -> Self {
        Self {
            db_pool,
            redis_client,
        }
    }
}

impl<S> Layer<S> for AuthLayer {
    type Service = AuthService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        AuthService {
            inner,
            db_pool: self.db_pool.clone(),
            redis_client: self.redis_client.clone(),
        }
    }
}

#[derive(Clone)]
pub struct AuthService<S> {
    inner: S,
    db_pool: DatabasePool,
    redis_client: RedisClient,
}

impl<S> std::fmt::Debug for AuthService<S> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthService").finish()
    }
}

impl<S, B> Service<Request<B>> for AuthService<S>
where
    S: Service<Request<B>, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
    B: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<B>) -> Self::Future {
        let db_pool = self.db_pool.clone();
        let redis_client = self.redis_client.clone();
        let mut inner = self.inner.clone();

        Box::pin(async move {
            let api_key = req
                .headers()
                .get(X_API_KEY_HEADER)
                .and_then(|v| v.to_str().ok());

            let api_key = match api_key {
                Some(k) => k.to_string(),
                None => {
                    warn!("Missing X-API-Key header, path: {}", req.uri().path());
                    let err = ErrorResponse {
                        code: StatusCode::UNAUTHORIZED.as_u16(),
                        message: "Missing X-API-Key header".to_string(),
                        details: None,
                    };
                    return Ok((StatusCode::UNAUTHORIZED, Json(err)).into_response());
                }
            };

            let authenticator = ApiKeyAuthenticator::new(db_pool.clone(), redis_client.clone());
            let tenant = match authenticator.validate_api_key(&api_key).await {
                Ok(t) => t,
                Err(e) => {
                    warn!(
                        "API key validation failed, path: {}, error: {}",
                        req.uri().path(),
                        e
                    );
                    let err = ErrorResponse {
                        code: StatusCode::UNAUTHORIZED.as_u16(),
                        message: "Invalid API key".to_string(),
                        details: None,
                    };
                    return Ok((StatusCode::UNAUTHORIZED, Json(err)).into_response());
                }
            };

            debug!(
                "Authentication successful, tenant: {}, path: {}",
                tenant.id,
                req.uri().path()
            );

            let (mut parts, body) = req.into_parts();
            parts.extensions.insert(db_pool);
            parts.extensions.insert(redis_client);
            parts.extensions.insert(tenant.clone());

            if let Ok(tenant_id_str) = HeaderValue::from_str(&tenant.id.to_string()) {
                parts.headers.insert(X_TENANT_ID, tenant_id_str);
            }

            let req = Request::from_parts(parts, body);
            inner.call(req).await
        })
    }
}

#[derive(Debug, Clone)]
pub struct RateLimitLayer {
    rate_limiter: Arc<RateLimiter>,
}

impl RateLimitLayer {
    pub fn new(redis_client: RedisClient, config: RateLimitConfig) -> Self {
        let rate_limiter = RateLimiter::new(redis_client, config);
        Self {
            rate_limiter: Arc::new(rate_limiter),
        }
    }

    pub fn from_rate_limiter(rate_limiter: RateLimiter) -> Self {
        Self {
            rate_limiter: Arc::new(rate_limiter),
        }
    }
}

impl<S> Layer<S> for RateLimitLayer {
    type Service = RateLimitService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        RateLimitService {
            inner,
            rate_limiter: self.rate_limiter.clone(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct RateLimitService<S> {
    inner: S,
    rate_limiter: Arc<RateLimiter>,
}

impl<S, B> Service<Request<B>> for RateLimitService<S>
where
    S: Service<Request<B>, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
    B: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<B>) -> Self::Future {
        let rate_limiter = self.rate_limiter.clone();
        let mut inner = self.inner.clone();

        Box::pin(async move {
            let tenant = req.extensions().get::<Tenant>().cloned();

            if let Some(tenant) = tenant {
                let qps_limit = tenant.qps_limit as u32;
                let per_min_limit = tenant.rate_limit_per_minute as u32;

                let qps_limit = if qps_limit == 0 {
                    rate_limiter.default_config().qps_limit
                } else {
                    qps_limit
                };
                let per_min_limit = if per_min_limit == 0 {
                    rate_limiter.default_config().per_min_limit
                } else {
                    per_min_limit
                };

                let rate_result = rate_limiter
                    .check_rate_limit(&tenant.id, qps_limit, per_min_limit)
                    .await
                    .unwrap_or_else(|e| {
                        error!("Rate limit check error: {}", e);
                        crate::rate_limit::RateLimitResult {
                            allowed: true,
                            qps_remaining: qps_limit,
                            per_min_remaining: per_min_limit,
                            qps_limit,
                            per_min_limit,
                            exceeded_type: None,
                        }
                    });

                if !rate_result.allowed {
                    let exceeded_msg = match rate_result.exceeded_type {
                        Some(RateLimitExceededType::Qps) => "QPS rate limit exceeded",
                        Some(RateLimitExceededType::PerMinute) => {
                            "Per-minute rate limit exceeded"
                        }
                        None => "Rate limit exceeded",
                    };
                    warn!(
                        "{} for tenant {}, path: {}",
                        exceeded_msg,
                        tenant.id,
                        req.uri().path()
                    );
                    let response = Response::builder()
                        .status(StatusCode::TOO_MANY_REQUESTS)
                        .header(X_RATELIMIT_QPS_LIMIT, qps_limit.to_string())
                        .header(
                            X_RATELIMIT_QPS_REMAINING,
                            rate_result.qps_remaining.to_string(),
                        )
                        .header(X_RATELIMIT_PERMIN_LIMIT, per_min_limit.to_string())
                        .header(
                            X_RATELIMIT_PERMIN_REMAINING,
                            rate_result.per_min_remaining.to_string(),
                        )
                        .body(Body::from(
                            serde_json::to_string(&ErrorResponse {
                                code: StatusCode::TOO_MANY_REQUESTS.as_u16(),
                                message: exceeded_msg.to_string(),
                                details: None,
                            })
                            .unwrap_or_default(),
                        ))
                        .unwrap_or_else(|_| {
                            (StatusCode::TOO_MANY_REQUESTS, "Rate limit exceeded").into_response()
                        });
                    return Ok(response);
                }

                let (mut parts, body) = req.into_parts();
                if let Ok(v) = HeaderValue::from_str(&qps_limit.to_string()) {
                    parts.headers.insert(X_RATELIMIT_QPS_LIMIT, v);
                }
                if let Ok(v) = HeaderValue::from_str(&rate_result.qps_remaining.to_string()) {
                    parts.headers.insert(X_RATELIMIT_QPS_REMAINING, v);
                }
                if let Ok(v) = HeaderValue::from_str(&per_min_limit.to_string()) {
                    parts.headers.insert(X_RATELIMIT_PERMIN_LIMIT, v);
                }
                if let Ok(v) = HeaderValue::from_str(&rate_result.per_min_remaining.to_string()) {
                    parts.headers.insert(X_RATELIMIT_PERMIN_REMAINING, v);
                }
                let req = Request::from_parts(parts, body);

                let mut response = inner.call(req).await?;
                if let Ok(v) = HeaderValue::from_str(&qps_limit.to_string()) {
                    response.headers_mut().insert(X_RATELIMIT_QPS_LIMIT, v);
                }
                if let Ok(v) = HeaderValue::from_str(&rate_result.qps_remaining.to_string()) {
                    response
                        .headers_mut()
                        .insert(X_RATELIMIT_QPS_REMAINING, v);
                }
                if let Ok(v) = HeaderValue::from_str(&per_min_limit.to_string()) {
                    response.headers_mut().insert(X_RATELIMIT_PERMIN_LIMIT, v);
                }
                if let Ok(v) = HeaderValue::from_str(&rate_result.per_min_remaining.to_string()) {
                    response
                        .headers_mut()
                        .insert(X_RATELIMIT_PERMIN_REMAINING, v);
                }
                Ok(response)
            } else {
                inner.call(req).await
            }
        })
    }
}

#[derive(Debug, Clone)]
pub struct RequestLogLayer;

impl RequestLogLayer {
    pub fn new() -> Self {
        Self
    }
}

impl Default for RequestLogLayer {
    fn default() -> Self {
        Self::new()
    }
}

impl<S> Layer<S> for RequestLogLayer {
    type Service = RequestLogService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        RequestLogService { inner }
    }
}

#[derive(Debug, Clone)]
pub struct RequestLogService<S> {
    inner: S,
}

pin_project! {
    pub struct RequestLogFuture<F> {
        #[pin]
        inner: F,
        method: String,
        path: String,
        tenant_id: Option<String>,
        request_id: Option<String>,
        start: Instant,
    }
}

impl<F, E> Future for RequestLogFuture<F>
where
    F: Future<Output = Result<Response, E>>,
{
    type Output = Result<Response, E>;

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        let this = self.project();
        let result = match this.inner.poll(cx) {
            Poll::Pending => return Poll::Pending,
            Poll::Ready(r) => r,
        };

        let elapsed = this.start.elapsed();
        let elapsed_ms = elapsed.as_secs_f64() * 1000.0;

        match result {
            Ok(mut response) => {
                let status = response.status();
                if let Ok(v) = HeaderValue::from_str(&format!("{:.3}", elapsed_ms)) {
                    response.headers_mut().insert(X_PROCESS_TIME_MS, v);
                }

                if status.is_server_error() {
                    error!(
                        method = %this.method,
                        path = %this.path,
                        status = %status.as_u16(),
                        duration_ms = %format!("{:.3}", elapsed_ms),
                        tenant_id = ?this.tenant_id,
                        request_id = ?this.request_id,
                        "Request failed with server error"
                    );
                } else if status.is_client_error() {
                    warn!(
                        method = %this.method,
                        path = %this.path,
                        status = %status.as_u16(),
                        duration_ms = %format!("{:.3}", elapsed_ms),
                        tenant_id = ?this.tenant_id,
                        request_id = ?this.request_id,
                        "Request failed with client error"
                    );
                } else {
                    info!(
                        method = %this.method,
                        path = %this.path,
                        status = %status.as_u16(),
                        duration_ms = %format!("{:.3}", elapsed_ms),
                        tenant_id = ?this.tenant_id,
                        request_id = ?this.request_id,
                        "Request completed successfully"
                    );
                }

                Poll::Ready(Ok(response))
            }
            Err(e) => {
                error!(
                    method = %this.method,
                    path = %this.path,
                    duration_ms = %format!("{:.3}", elapsed_ms),
                    tenant_id = ?this.tenant_id,
                    request_id = ?this.request_id,
                    "Request failed with internal error"
                );
                Poll::Ready(Err(e))
            }
        }
    }
}

impl<S, B> Service<Request<B>> for RequestLogService<S>
where
    S: Service<Request<B>, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
    B: Send + 'static,
{
    type Response = Response;
    type Error = S::Error;
    type Future = RequestLogFuture<S::Future>;

    fn poll_ready(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, req: Request<B>) -> Self::Future {
        let method = req.method().to_string();
        let path = req.uri().path().to_string();
        let tenant_id = req
            .headers()
            .get(X_TENANT_ID)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        let request_id = req
            .headers()
            .get(X_REQUEST_ID)
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        let start = Instant::now();

        let inner_future = self.inner.call(req);
        RequestLogFuture {
            inner: inner_future,
            method,
            path,
            tenant_id,
            request_id,
            start,
        }
    }
}

#[derive(Clone)]
pub struct SecurityConfig {
    pub enable_auth: bool,
    pub enable_rate_limit: bool,
    pub enable_logging: bool,
    pub db_pool: DatabasePool,
    pub redis_client: RedisClient,
    pub rate_limit_config: RateLimitConfig,
}

impl std::fmt::Debug for SecurityConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SecurityConfig").finish()
    }
}

impl SecurityConfig {
    pub fn new(db_pool: DatabasePool, redis_client: RedisClient) -> Self {
        Self {
            enable_auth: true,
            enable_rate_limit: true,
            enable_logging: true,
            db_pool,
            redis_client,
            rate_limit_config: RateLimitConfig::default(),
        }
    }

    pub fn with_rate_limit_config(mut self, config: RateLimitConfig) -> Self {
        self.rate_limit_config = config;
        self
    }

    pub fn with_auth(mut self, enable: bool) -> Self {
        self.enable_auth = enable;
        self
    }

    pub fn with_rate_limit(mut self, enable: bool) -> Self {
        self.enable_rate_limit = enable;
        self
    }

    pub fn with_logging(mut self, enable: bool) -> Self {
        self.enable_logging = enable;
        self
    }
}

#[derive(Clone)]
pub struct SecurityLayer {
    config: SecurityConfig,
}

impl std::fmt::Debug for SecurityLayer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SecurityLayer").finish()
    }
}

impl SecurityLayer {
    pub fn new(config: SecurityConfig) -> Self {
        Self { config }
    }

    pub fn simple(db_pool: DatabasePool, redis_client: RedisClient) -> Self {
        Self {
            config: SecurityConfig::new(db_pool, redis_client),
        }
    }
}

impl<S> Layer<S> for SecurityLayer
where
    S: Service<Request<Body>, Response = Response> + Clone + Send + 'static,
    S::Future: Send + 'static,
{
    type Service = AuthService<RateLimitService<RequestLogService<S>>>;

    fn layer(&self, inner: S) -> Self::Service {
        let service = RequestLogLayer::new().layer(inner);
        let service = RateLimitLayer::new(
            self.config.redis_client.clone(),
            self.config.rate_limit_config.clone(),
        ).layer(service);
        let service = AuthLayer::new(
            self.config.db_pool.clone(),
            self.config.redis_client.clone(),
        ).layer(service);
        service
    }
}
