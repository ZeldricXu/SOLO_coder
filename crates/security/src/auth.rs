use async_trait::async_trait;
use axum::extract::FromRequestParts;
use axum::http::request::Parts;
use axum::http::{HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use common::error::{AppError, ErrorResponse};
use db::{DatabasePool, RedisClient, Tenant};
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

const API_KEY_CACHE_TTL: u64 = 300;
pub const X_API_KEY_HEADER: &str = "X-API-Key";

#[derive(Clone)]
pub struct ApiKeyAuthenticator {
    db_pool: DatabasePool,
    redis_client: RedisClient,
}

impl std::fmt::Debug for ApiKeyAuthenticator {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ApiKeyAuthenticator").finish()
    }
}

impl ApiKeyAuthenticator {
    pub fn new(db_pool: DatabasePool, redis_client: RedisClient) -> Self {
        Self {
            db_pool,
            redis_client,
        }
    }

    pub fn db_pool(&self) -> &DatabasePool {
        &self.db_pool
    }

    pub fn redis_client(&self) -> &RedisClient {
        &self.redis_client
    }

    pub fn hash_api_key(api_key: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(api_key.as_bytes());
        let result = hasher.finalize();
        format!("{:x}", result)
    }

    fn cache_key(api_key_hash: &str) -> String {
        format!("tenant:apikey:{}", api_key_hash)
    }

    pub async fn validate_api_key(&self, api_key: &str) -> Result<Tenant, AppError> {
        let api_key_hash = Self::hash_api_key(api_key);
        let cache_key = Self::cache_key(&api_key_hash);

        let mut redis = self.redis_client.manager.clone();

        if let Ok(Some(cached)) = redis.get::<_, Option<String>>(&cache_key).await {
            if let Ok(tenant) = serde_json::from_str::<Tenant>(&cached) {
                debug!("API key cache hit for tenant: {}", tenant.id);
                return Ok(tenant);
            }
        }

        let tenant = self
            .db_pool
            .get_tenant_by_api_key_hash(&api_key_hash)
            .await
            .map_err(|e| match e {
                db::error::DbError::NotFound(_) => AppError::InvalidApiKey,
                _ => {
                    error!("Database error while validating API key: {}", e);
                    AppError::Internal("database error".to_string())
                }
            })?
            .ok_or(AppError::InvalidApiKey)?;

        if let Ok(json_str) = serde_json::to_string(&tenant) {
            let _: Result<(), _> = redis
                .set_ex::<_, _, ()>(&cache_key, json_str, API_KEY_CACHE_TTL)
                .await;
        }

        info!("API key validated successfully for tenant: {}", tenant.id);
        Ok(tenant)
    }

    pub async fn rotate_api_key(
        &self,
        tenant_id: Uuid,
        new_api_key: &str,
    ) -> Result<(), AppError> {
        let new_hash = Self::hash_api_key(new_api_key);

        let old_tenant = self
            .db_pool
            .get_tenant_by_id(tenant_id)
            .await
            .map_err(|e| match e {
                db::error::DbError::NotFound(_) => AppError::TenantNotFound(tenant_id.to_string()),
                _ => AppError::Internal("database error".to_string()),
            })?
            .ok_or_else(|| AppError::TenantNotFound(tenant_id.to_string()))?;

        self.db_pool
            .rotate_api_key(tenant_id, new_api_key, &new_hash)
            .await
            .map_err(|e| match e {
                db::error::DbError::NotFound(_) => AppError::TenantNotFound(tenant_id.to_string()),
                _ => AppError::Internal("database error".to_string()),
            })?;

        let old_hash = old_tenant.api_key_hash;
        let old_cache_key = Self::cache_key(&old_hash);
        let new_cache_key = Self::cache_key(&new_hash);

        let mut redis = self.redis_client.manager.clone();
        let _: Result<(), _> = redis.del::<_, ()>(&old_cache_key).await;
        let _: Result<(), _> = redis.del::<_, ()>(&new_cache_key).await;

        info!("API key rotated successfully for tenant: {}", tenant_id);
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthenticatedTenant(pub Tenant);

#[async_trait]
impl<S> FromRequestParts<S> for AuthenticatedTenant
where
    S: Send + Sync,
{
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, _state: &S) -> Result<Self, Self::Rejection> {
        if let Some(tenant) = parts.extensions.get::<Tenant>().cloned() {
            return Ok(AuthenticatedTenant(tenant));
        }

        let api_key = parts
            .headers
            .get(X_API_KEY_HEADER)
            .and_then(|v| v.to_str().ok())
            .ok_or_else(|| {
                warn!("Missing X-API-Key header");
                let err = ErrorResponse {
                    code: StatusCode::UNAUTHORIZED.as_u16(),
                    message: "Missing X-API-Key header".to_string(),
                    details: None,
                };
                (StatusCode::UNAUTHORIZED, Json(err)).into_response()
            })?
            .to_string();

        let db_pool = parts
            .extensions
            .get::<DatabasePool>()
            .cloned()
            .ok_or_else(|| {
                error!("DatabasePool not found in request extensions");
                let err = ErrorResponse {
                    code: StatusCode::INTERNAL_SERVER_ERROR.as_u16(),
                    message: "Internal server error".to_string(),
                    details: None,
                };
                (StatusCode::INTERNAL_SERVER_ERROR, Json(err)).into_response()
            })?;

        let redis_client = parts
            .extensions
            .get::<RedisClient>()
            .cloned()
            .ok_or_else(|| {
                error!("RedisClient not found in request extensions");
                let err = ErrorResponse {
                    code: StatusCode::INTERNAL_SERVER_ERROR.as_u16(),
                    message: "Internal server error".to_string(),
                    details: None,
                };
                (StatusCode::INTERNAL_SERVER_ERROR, Json(err)).into_response()
            })?;

        let authenticator = ApiKeyAuthenticator::new(db_pool, redis_client);
        let tenant = authenticator
            .validate_api_key(&api_key)
            .await
            .map_err(|e| {
                warn!("API key validation failed: {}", e);
                let err = ErrorResponse {
                    code: StatusCode::UNAUTHORIZED.as_u16(),
                    message: "Invalid API key".to_string(),
                    details: None,
                };
                (StatusCode::UNAUTHORIZED, Json(err)).into_response()
            })?;

        parts.extensions.insert(tenant.clone());
        Ok(AuthenticatedTenant(tenant))
    }
}

#[async_trait]
impl<S> FromRequestParts<S> for ApiKeyAuth
where
    S: Send + Sync,
{
    type Rejection = Response;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let auth = AuthenticatedTenant::from_request_parts(parts, state).await?;
        Ok(ApiKeyAuth {
            tenant_id: auth.0.id,
            tenant: auth.0,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiKeyAuth {
    pub tenant_id: Uuid,
    pub tenant: Tenant,
}

impl ApiKeyAuth {
    pub fn tenant_id(&self) -> Uuid {
        self.tenant_id
    }

    pub fn tenant(&self) -> &Tenant {
        &self.tenant
    }
}

pub fn extract_api_key(parts: &Parts) -> Option<String> {
    parts
        .headers
        .get(X_API_KEY_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
}

pub fn set_tenant_header(parts: &mut Parts, tenant_id: Uuid) {
    if let Ok(value) = HeaderValue::from_str(&tenant_id.to_string()) {
        parts.headers.insert("X-Tenant-Id", value);
    }
}
