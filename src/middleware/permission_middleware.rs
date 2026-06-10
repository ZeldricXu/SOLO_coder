use std::future::{ready, Ready};
use std::marker::PhantomData;

use actix_web::body::EitherBody;
use actix_web::dev::{forward_ready, Service, ServiceRequest, ServiceResponse, Transform};
use actix_web::http::{header, Method};
use actix_web::{Error, HttpResponse};
use futures_util::future::LocalBoxFuture;
use tracing::{debug, warn};

use crate::models::AuthUser;
use crate::services::Permission;
use crate::utils::AppError;

pub trait RoleConst {
    const ROLE: &'static str;
}

pub struct Owner;
impl RoleConst for Owner {
    const ROLE: &'static str = "owner";
}

pub struct Maintainer;
impl RoleConst for Maintainer {
    const ROLE: &'static str = "maintainer";
}

pub struct Reviewer;
impl RoleConst for Reviewer {
    const ROLE: &'static str = "reviewer";
}

pub struct Developer;
impl RoleConst for Developer {
    const ROLE: &'static str = "developer";
}

pub struct RequireRole<R> {
    _marker: PhantomData<R>,
}

impl<R> RequireRole<R> {
    pub fn new() -> Self {
        Self {
            _marker: PhantomData,
        }
    }
}

impl<R> Default for RequireRole<R> {
    fn default() -> Self {
        Self::new()
    }
}

impl<S, B, R> Transform<S, ServiceRequest> for RequireRole<R>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
    R: RoleConst + 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Transform = RequireRoleService<S, R>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(RequireRoleService {
            service,
            _marker: PhantomData,
        }))
    }
}

pub struct RequireRoleService<S, R> {
    service: S,
    _marker: PhantomData<R>,
}

impl<S, B, R> Service<ServiceRequest> for RequireRoleService<S, R>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
    R: RoleConst + 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let service = self.service.clone();
        let required_role = R::ROLE;

        Box::pin(async move {
            let user = req.extensions().get::<AuthUser>().cloned();

            match user {
                Some(auth_user) => {
                    if role_has_permission(&auth_user.role, required_role) {
                        debug!(
                            user_id = %auth_user.id,
                            role = %auth_user.role,
                            required_role = %required_role,
                            "Role check passed"
                        );
                        let res = service.call(req).await?;
                        Ok(res.map_into_left_body())
                    } else {
                        warn!(
                            user_id = %auth_user.id,
                            role = %auth_user.role,
                            required_role = %required_role,
                            "Role check failed - insufficient permissions"
                        );
                        let response = HttpResponse::Forbidden().json(serde_json::json!({
                            "code": 403,
                            "message": "Forbidden",
                            "error": format!("User requires '{}' role to perform this action", required_role)
                        }));
                        Ok(req.into_response(response).map_into_right_body())
                    }
                }
                None => {
                    warn!("Role check failed - user not authenticated");
                    let response = HttpResponse::Unauthorized().json(serde_json::json!({
                        "code": 401,
                        "message": "Unauthorized",
                        "error": "User not authenticated"
                    }));
                    Ok(req.into_response(response).map_into_right_body())
                }
            }
        })
    }
}

pub fn role_has_permission(user_role: &str, required_role: &str) -> bool {
    let role_priority = ["owner", "maintainer", "reviewer", "developer"];

    let user_idx = role_priority.iter().position(|r| r == user_role);
    let required_idx = role_priority.iter().position(|r| r == required_role);

    match (user_idx, required_idx) {
        (Some(u), Some(r)) => u <= r,
        _ => false,
    }
}

pub struct RequirePermission {
    permission: Permission,
}

impl RequirePermission {
    pub fn new(permission: Permission) -> Self {
        Self { permission }
    }
}

impl<S, B> Transform<S, ServiceRequest> for RequirePermission
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Transform = RequirePermissionService<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(RequirePermissionService {
            service,
            permission: self.permission.clone(),
        }))
    }
}

pub struct RequirePermissionService<S> {
    service: S,
    permission: Permission,
}

impl<S, B> Service<ServiceRequest> for RequirePermissionService<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let service = self.service.clone();
        let permission = self.permission.clone();

        Box::pin(async move {
            let user = req.extensions().get::<AuthUser>().cloned();

            match user {
                Some(auth_user) => {
                    if user_has_permission(&auth_user.role, &permission) {
                        debug!(
                            user_id = %auth_user.id,
                            permission = %permission.as_str(),
                            "Permission check passed"
                        );
                        let res = service.call(req).await?;
                        Ok(res.map_into_left_body())
                    } else {
                        warn!(
                            user_id = %auth_user.id,
                            role = %auth_user.role,
                            permission = %permission.as_str(),
                            "Permission check failed"
                        );
                        let response = HttpResponse::Forbidden().json(serde_json::json!({
                            "code": 403,
                            "message": "Forbidden",
                            "error": format!("User requires '{}' permission to perform this action", permission.as_str())
                        }));
                        Ok(req.into_response(response).map_into_right_body())
                    }
                }
                None => {
                    warn!("Permission check failed - user not authenticated");
                    let response = HttpResponse::Unauthorized().json(serde_json::json!({
                        "code": 401,
                        "message": "Unauthorized",
                        "error": "User not authenticated"
                    }));
                    Ok(req.into_response(response).map_into_right_body())
                }
            }
        })
    }
}

fn user_has_permission(role: &str, permission: &Permission) -> bool {
    let permissions = get_permissions_for_role(role);
    permissions.contains(permission)
}

fn get_permissions_for_role(role: &str) -> Vec<Permission> {
    let mut permissions = Vec::new();

    match role {
        "owner" => {
            permissions.push(Permission::ManageOrganization);
            permissions.push(Permission::ManageTeam);
            permissions.push(Permission::ManageRepository);
            permissions.push(Permission::ReviewMergeRequest);
            permissions.push(Permission::CreateIssue);
            permissions.push(Permission::EditIssue);
            permissions.push(Permission::EditAnyIssue);
            permissions.push(Permission::ViewStatistics);
            permissions.push(Permission::AssignReviewer);
            permissions.push(Permission::MergeRequest);
        }
        "maintainer" => {
            permissions.push(Permission::ManageTeam);
            permissions.push(Permission::ManageRepository);
            permissions.push(Permission::ReviewMergeRequest);
            permissions.push(Permission::CreateIssue);
            permissions.push(Permission::EditIssue);
            permissions.push(Permission::EditAnyIssue);
            permissions.push(Permission::ViewStatistics);
            permissions.push(Permission::AssignReviewer);
            permissions.push(Permission::MergeRequest);
        }
        "reviewer" => {
            permissions.push(Permission::ReviewMergeRequest);
            permissions.push(Permission::CreateIssue);
            permissions.push(Permission::EditIssue);
            permissions.push(Permission::ViewStatistics);
        }
        "developer" => {
            permissions.push(Permission::CreateIssue);
            permissions.push(Permission::EditIssue);
        }
        _ => {
            permissions.push(Permission::CreateIssue);
        }
    }

    permissions
}

pub struct CsrfMiddleware;

impl CsrfMiddleware {
    pub fn new() -> Self {
        Self
    }
}

impl Default for CsrfMiddleware {
    fn default() -> Self {
        Self::new()
    }
}

impl<S, B> Transform<S, ServiceRequest> for CsrfMiddleware
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Transform = CsrfMiddlewareService<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(CsrfMiddlewareService { service }))
    }
}

pub struct CsrfMiddlewareService<S> {
    service: S,
}

impl<S, B> Service<ServiceRequest> for CsrfMiddlewareService<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
{
    type Response = ServiceResponse<EitherBody<B>>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let service = self.service.clone();
        let method = req.method().clone();

        let requires_csrf = matches!(
            method,
            Method::POST | Method::PUT | Method::DELETE | Method::PATCH
        );

        let has_api_auth = req
            .headers()
            .get(header::AUTHORIZATION)
            .and_then(|h| h.to_str().ok())
            .map(|h| h.starts_with("Bearer "))
            .unwrap_or(false);

        let path = req.path().to_string();
        let is_webhook = path.starts_with("/webhook/");

        if !requires_csrf || has_api_auth || is_webhook {
            let fut = service.call(req);
            return Box::pin(async move {
                let res = fut.await?;
                Ok(res.map_into_left_body())
            });
        }

        Box::pin(async move {
            let session_csrf = req
                .cookie("csrf_token")
                .map(|c| c.value().to_string());

            let header_csrf = req
                .headers()
                .get("X-CSRF-Token")
                .and_then(|h| h.to_str().ok())
                .map(|s| s.to_string());

            let form_csrf = None;

            let provided_csrf = header_csrf.or(form_csrf);

            match (session_csrf, provided_csrf) {
                (Some(session), Some(provided)) if session == provided => {
                    debug!("CSRF token validation passed");
                    let res = service.call(req).await?;
                    Ok(res.map_into_left_body())
                }
                _ => {
                    warn!("CSRF token validation failed");
                    let response = HttpResponse::Forbidden().json(serde_json::json!({
                        "code": 403,
                        "message": "Forbidden",
                        "error": "Invalid or missing CSRF token"
                    }));
                    Ok(req.into_response(response).map_into_right_body())
                }
            }
        })
    }
}

pub fn generate_csrf_token() -> String {
    crate::utils::crypto::generate_csrf_token()
}

pub fn require_role(role: &str) -> Result<(), AppError> {
    Ok(())
}
