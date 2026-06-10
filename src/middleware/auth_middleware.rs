use std::future::{ready, Ready};
use std::time::Instant;

use actix_web::body::EitherBody;
use actix_web::dev::{forward_ready, Service, ServiceRequest, ServiceResponse, Transform};
use actix_web::http::{header, Method, StatusCode};
use actix_web::{web, Error, HttpResponse};
use futures_util::future::LocalBoxFuture;
use tracing::{debug, info, warn};

use crate::models::AuthUser;
use crate::services::AuthService;
use crate::utils::AppError;

#[derive(Clone)]
pub struct AuthMiddleware {
    auth_service: AuthService,
}

impl AuthMiddleware {
    pub fn new(auth_service: AuthService) -> Self {
        Self { auth_service }
    }

    fn is_public_path(&self, path: &str) -> bool {
        path == "/login"
            || path.starts_with("/auth/")
            || path.starts_with("/webhook/")
            || path.starts_with("/static/")
    }

    fn is_api_request(&self, path: &str) -> bool {
        path.starts_with("/api/")
    }
}

impl<S, B> Transform<S, ServiceRequest> for AuthMiddleware
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Transform = AuthMiddlewareService<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(AuthMiddlewareService {
            service,
            auth_service: self.auth_service.clone(),
            public_paths: vec![
                "/login".to_string(),
                "/auth/".to_string(),
                "/webhook/".to_string(),
                "/static/".to_string(),
            ],
        }))
    }
}

pub struct AuthMiddlewareService<S> {
    service: S,
    auth_service: AuthService,
    public_paths: Vec<String>,
}

impl<S> AuthMiddlewareService<S> {
    fn is_public_path(&self, path: &str) -> bool {
        path == "/login"
            || self
                .public_paths
                .iter()
                .any(|prefix| path.starts_with(prefix))
    }

    fn is_api_request(&self, path: &str) -> bool {
        path.starts_with("/api/")
    }
}

impl<S, B> Service<ServiceRequest> for AuthMiddlewareService<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let path = req.path().to_string();
        let method = req.method().clone();

        if self.is_public_path(&path) {
            let fut = self.service.call(req);
            return Box::pin(async move {
                let res = fut.await?;
                Ok(res.map_into_left_body())
            });
        }

        let auth_service = self.auth_service.clone();
        let is_api = self.is_api_request(&path);
        let service = self.service.clone();
        let (http_req, payload) = req.into_parts();

        Box::pin(async move {
            let auth_user = extract_and_validate_auth(&auth_service, &http_req).await;

            match auth_user {
                Ok(user) => {
                    http_req.extensions_mut().insert(user);
                    let req = ServiceRequest::from_parts(http_req, payload);
                    let res = service.call(req).await?;
                    Ok(res.map_into_left_body())
                }
                Err(_) => {
                    if is_api {
                        let response = HttpResponse::Unauthorized().json(serde_json::json!({
                            "code": 401,
                            "message": "Unauthorized",
                            "error": "Authentication required"
                        }));
                        Ok(ServiceRequest::from_parts(http_req, payload)
                            .into_response(response)
                            .map_into_right_body())
                    } else {
                        let response = HttpResponse::Found()
                            .append_header((header::LOCATION, "/login"))
                            .finish();
                        Ok(ServiceRequest::from_parts(http_req, payload)
                            .into_response(response)
                            .map_into_right_body())
                    }
                }
            }
        })
    }
}

async fn extract_and_validate_auth(
    auth_service: &AuthService,
    req: &actix_web::HttpRequest,
) -> Result<AuthUser, AppError> {
    if let Some(auth_header) = req.headers().get(header::AUTHORIZATION) {
        if let Ok(auth_str) = auth_header.to_str() {
            if let Some(token) = auth_str.strip_prefix("Bearer ") {
                debug!("Attempting API token authentication");
                return auth_service
                    .get_current_user(token)
                    .await;
            }
        }
    }

    if let Some(cookie) = req.cookie(&auth_service.settings.session.cookie_name) {
        let session_id = cookie.value();
        debug!("Attempting session authentication with cookie");
        return auth_service.get_current_user(session_id).await;
    }

    Err(AppError::Authentication(
        "No authentication credentials provided".to_string(),
    ))
}

pub struct LoggingMiddleware;

impl<S, B> Transform<S, ServiceRequest> for LoggingMiddleware
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type Transform = LoggingMiddlewareService<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(LoggingMiddlewareService { service }))
    }
}

pub struct LoggingMiddlewareService<S> {
    service: S,
}

impl<S, B> Service<ServiceRequest> for LoggingMiddlewareService<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let method = req.method().clone();
        let path = req.path().to_string();
        let start = Instant::now();
        let user_agent = req
            .headers()
            .get(header::USER_AGENT)
            .and_then(|h| h.to_str().ok())
            .unwrap_or("-")
            .to_string();

        let fut = self.service.call(req);

        Box::pin(async move {
            let res = fut.await?;
            let duration = start.elapsed();
            let status = res.status();

            let log_level = if status.is_server_error() {
                tracing::Level::WARN
            } else if status.is_client_error() {
                tracing::Level::INFO
            } else {
                tracing::Level::DEBUG
            };

            match log_level {
                tracing::Level::WARN => {
                    warn!(
                        method = %method,
                        path = %path,
                        status = %status.as_u16(),
                        duration_ms = %duration.as_millis(),
                        user_agent = %user_agent,
                        "Request completed with error"
                    );
                }
                tracing::Level::INFO => {
                    info!(
                        method = %method,
                        path = %path,
                        status = %status.as_u16(),
                        duration_ms = %duration.as_millis(),
                        user_agent = %user_agent,
                        "Request completed"
                    );
                }
                _ => {
                    debug!(
                        method = %method,
                        path = %path,
                        status = %status.as_u16(),
                        duration_ms = %duration.as_millis(),
                        user_agent = %user_agent,
                        "Request completed"
                    );
                }
            }

            Ok(res)
        })
    }
}

pub fn get_current_user(req: &actix_web::HttpRequest) -> Option<&AuthUser> {
    req.extensions().get::<AuthUser>()
}

pub fn require_auth(req: &actix_web::HttpRequest) -> Result<&AuthUser, AppError> {
    get_current_user(req).ok_or_else(|| {
        AppError::Authentication("User not authenticated".to_string())
    })
}
