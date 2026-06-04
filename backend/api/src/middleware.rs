use actix_web::{
    dev::{forward_ready, Service, ServiceRequest, ServiceResponse, Transform},
    Error, HttpMessage,
};
use common::{AuthService, AppError};
use futures_util::future::LocalBoxFuture;
use shared::UserRole;
use std::{
    future::{ready, Ready},
    sync::Arc,
};

#[derive(Clone)]
pub struct AuthMiddleware {
    auth_service: Arc<AuthService>,
    required_role: Option<UserRole>,
}

impl AuthMiddleware {
    pub fn new(auth_service: Arc<AuthService>, required_role: Option<UserRole>) -> Self {
        Self {
            auth_service,
            required_role,
        }
    }
}

impl<S, B> Transform<S, ServiceRequest> for AuthMiddleware
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type Transform = AuthMiddlewareService<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(AuthMiddlewareService {
            service: Arc::new(service),
            auth_service: self.auth_service.clone(),
            required_role: self.required_role,
        }))
    }
}

pub struct AuthMiddlewareService<S> {
    service: Arc<S>,
    auth_service: Arc<AuthService>,
    required_role: Option<UserRole>,
}

impl<S, B> Service<ServiceRequest> for AuthMiddlewareService<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let service = self.service.clone();
        let auth_service = self.auth_service.clone();
        let required_role = self.required_role;

        Box::pin(async move {
            let auth_header = req
                .headers()
                .get("Authorization")
                .and_then(|h| h.to_str().ok())
                .ok_or_else(|| AppError::Authentication("缺少认证令牌".into()))?;

            let token = auth_header
                .strip_prefix("Bearer ")
                .ok_or_else(|| AppError::Authentication("认证令牌格式错误".into()))?;

            let claims = auth_service.validate_token(token).map_err(AppError::from)?;

            if let Some(role) = required_role {
                if claims.role != role {
                    return Err(AppError::Authorization("权限不足".into()).into());
                }
            }

            req.extensions_mut().insert(claims.sub);
            req.extensions_mut().insert(claims.role);

            service.call(req).await
        })
    }
}
