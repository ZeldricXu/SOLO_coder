use std::collections::HashSet;
use std::sync::Arc;
use std::time::{Duration, Instant};

use dashmap::DashMap;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;
use tokio::sync::broadcast;
use uuid::Uuid;

pub const CONNECTION_CACHE_TTL_SECS: u64 = 300;
pub const REDIS_CACHE_TTL_SECS: u64 = 900;
pub const INVALIDATION_CHANNEL: &str = "collab:auth:invalidation";

#[derive(Error, Debug)]
pub enum AuthError {
    #[error("Invalid token: {0}")]
    InvalidToken(String),

    #[error("Token expired")]
    Expired,

    #[error("Token not yet valid")]
    NotYetValid,

    #[error("Permission denied: {0}")]
    PermissionDenied(String),

    #[error("User not found: {0}")]
    UserNotFound(String),

    #[error("Invalid signature")]
    InvalidSignature,

    #[error("Share link expired or revoked")]
    ShareLinkInvalid,

    #[error("Invalid credentials")]
    InvalidCredentials,

    #[error("Cache error: {0}")]
    Cache(String),

    #[error("Redis error: {0}")]
    Redis(String),

    #[error("Database error: {0}")]
    Database(String),
}

pub type AuthResult<T> = Result<T, AuthError>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Role {
    Owner,
    Editor,
    Viewer,
    Commenter,
}

impl Role {
    pub fn can_read(&self) -> bool {
        true
    }

    pub fn can_write(&self) -> bool {
        matches!(self, Role::Owner | Role::Editor)
    }

    pub fn can_admin(&self) -> bool {
        matches!(self, Role::Owner)
    }

    pub fn can_comment(&self) -> bool {
        matches!(self, Role::Owner | Role::Editor | Role::Commenter)
    }

    pub fn to_str(&self) -> &'static str {
        match self {
            Role::Owner => "owner",
            Role::Editor => "editor",
            Role::Viewer => "viewer",
            Role::Commenter => "commenter",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "owner" => Some(Role::Owner),
            "editor" => Some(Role::Editor),
            "viewer" => Some(Role::Viewer),
            "commenter" => Some(Role::Commenter),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JwtClaims {
    pub sub: String,
    pub user_id: String,
    pub email: Option<String>,
    pub name: Option<String>,
    pub iss: String,
    pub aud: String,
    pub exp: u64,
    pub iat: u64,
    pub nbf: Option<u64>,
    pub jti: String,
    pub scope: Option<String>,
    pub document_permissions: Vec<DocumentPermission>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DocumentPermission {
    pub document_id: Uuid,
    pub role: Role,
    pub granted_by: Option<String>,
    pub granted_at: u64,
    pub expires_at: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShareLink {
    pub id: Uuid,
    pub document_id: Uuid,
    pub created_by: String,
    pub role: Role,
    pub token: String,
    pub expires_at: Option<chrono::DateTime<chrono::Utc>>,
    pub max_uses: Option<u32>,
    pub use_count: u32,
    pub is_revoked: bool,
    pub requires_email: Option<String>,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShareTokenClaims {
    pub sub: String,
    pub share_id: Uuid,
    pub document_id: Uuid,
    pub role: String,
    pub created_by: String,
    pub exp: u64,
    pub iat: u64,
    pub jti: String,
}

#[derive(Debug, Clone)]
struct PermissionCacheEntry {
    role: Role,
    fetched_at: Instant,
}

#[derive(Debug, Clone)]
pub struct ConnectionPermissionCache {
    entries: DashMap<Uuid, PermissionCacheEntry>,
    created_at: Instant,
}

impl ConnectionPermissionCache {
    pub fn new() -> Self {
        Self {
            entries: DashMap::new(),
            created_at: Instant::now(),
        }
    }

    fn get(&self, document_id: &Uuid) -> Option<Role> {
        if self.created_at.elapsed() > Duration::from_secs(CONNECTION_CACHE_TTL_SECS) {
            return None;
        }
        self.entries.get(document_id).map(|e| e.value().role.clone())
    }

    fn insert(&self, document_id: Uuid, role: Role) {
        self.entries.insert(
            document_id,
            PermissionCacheEntry {
                role,
                fetched_at: Instant::now(),
            },
        );
    }

    fn invalidate_document(&self, document_id: &Uuid) {
        self.entries.remove(document_id);
    }

    fn invalidate_all(&self) {
        self.entries.clear();
    }
}

#[derive(Debug, Clone)]
pub struct PermissionCache {
    redis_pool: Option<bb8::Pool<bb8_redis::RedisConnectionManager>>,
    redis_prefix: String,
    redis_cache_ttl: Duration,
    invalidation_tx: broadcast::Sender<InvalidationEvent>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum InvalidationEvent {
    Document { user_id: String, document_id: Uuid },
    User { user_id: String },
    All,
}

impl PermissionCache {
    pub fn new(
        redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
        redis_prefix: String,
    ) -> Self {
        let (tx, _rx) = broadcast::channel(1024);
        Self {
            redis_pool: Some(redis_pool),
            redis_prefix,
            redis_cache_ttl: Duration::from_secs(REDIS_CACHE_TTL_SECS),
            invalidation_tx: tx,
        }
    }

    pub fn invalidation_sender(&self) -> broadcast::Sender<InvalidationEvent> {
        self.invalidation_tx.clone()
    }

    pub fn subscribe_invalidation(&self) -> broadcast::Receiver<InvalidationEvent> {
        self.invalidation_tx.subscribe()
    }

    fn redis_key(&self, user_id: &str, document_id: &Uuid) -> String {
        format!("{}perm:{}:{}", self.redis_prefix, user_id, document_id)
    }

    pub async fn get(
        &self,
        user_id: &str,
        document_id: &Uuid,
    ) -> AuthResult<Option<Role>> {
        let key = self.redis_key(user_id, document_id);

        let mut conn = self
            .redis_pool
            .as_ref()
            .ok_or_else(|| AuthError::Cache("Redis pool not available".into()))?
            .get()
            .await
            .map_err(|e| AuthError::Cache(e.to_string()))?;

        let result: Option<String> = redis::cmd("GET")
            .arg(&key)
            .query_async(&mut *conn)
            .await
            .map_err(|e| AuthError::Redis(e.to_string()))?;

        Ok(result.and_then(|s| Role::from_str(&s)))
    }

    pub async fn set(
        &self,
        user_id: &str,
        document_id: &Uuid,
        role: &Role,
    ) -> AuthResult<()> {
        let key = self.redis_key(user_id, document_id);
        let ttl_secs = self.redis_cache_ttl.as_secs() as usize;

        let mut conn = self
            .redis_pool
            .as_ref()
            .ok_or_else(|| AuthError::Cache("Redis pool not available".into()))?
            .get()
            .await
            .map_err(|e| AuthError::Cache(e.to_string()))?;

        let _: () = redis::cmd("SETEX")
            .arg(&key)
            .arg(ttl_secs)
            .arg(role.to_str())
            .query_async(&mut *conn)
            .await
            .map_err(|e| AuthError::Redis(e.to_string()))?;

        Ok(())
    }

    pub async fn invalidate_document(
        &self,
        user_id: &str,
        document_id: &Uuid,
    ) -> AuthResult<()> {
        let key = self.redis_key(user_id, document_id);

        let mut conn = self
            .redis_pool
            .as_ref()
            .ok_or_else(|| AuthError::Cache("Redis pool not available".into()))?
            .get()
            .await
            .map_err(|e| AuthError::Cache(e.to_string()))?;

        let _: i64 = redis::cmd("DEL")
            .arg(&key)
            .query_async(&mut *conn)
            .await
            .map_err(|e| AuthError::Redis(e.to_string()))?;

        let _ = self.invalidation_tx.send(InvalidationEvent::Document {
            user_id: user_id.to_string(),
            document_id: *document_id,
        });

        Ok(())
    }

    pub async fn publish_invalidation_to_redis(
        &self,
        event: &InvalidationEvent,
    ) -> AuthResult<()> {
        let payload = serde_json::to_vec(event)
            .map_err(|e| AuthError::Cache(e.to_string()))?;

        let mut conn = self
            .redis_pool
            .as_ref()
            .ok_or_else(|| AuthError::Cache("Redis pool not available".into()))?
            .get()
            .await
            .map_err(|e| AuthError::Cache(e.to_string()))?;

        let _: i64 = redis::cmd("PUBLISH")
            .arg(format!("{}{}", self.redis_prefix, INVALIDATION_CHANNEL))
            .arg(&payload)
            .query_async(&mut *conn)
            .await
            .map_err(|e| AuthError::Redis(e.to_string()))?;

        Ok(())
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct AuthConfig {
    pub jwt_secret: String,
    pub jwt_issuer: String,
    pub jwt_audience: String,
    pub jwt_expiry_secs: u64,
    pub share_token_expiry_secs: u64,
}

#[async_trait::async_trait]
pub trait PermissionProvider: Send + Sync {
    async fn fetch_permission(
        &self,
        user_id: &str,
        document_id: &Uuid,
    ) -> AuthResult<Option<Role>>;
}

pub struct AuthService {
    pub config: AuthConfig,
    encoding_key: EncodingKey,
    decoding_key: DecodingKey,
    share_tokens: std::sync::Mutex<HashSet<String>>,
    permission_cache: PermissionCache,
    per_connection_caches: DashMap<Uuid, Arc<ConnectionPermissionCache>>,
    permission_provider: Option<Arc<dyn PermissionProvider>>,
}

impl AuthService {
    pub fn new(
        config: AuthConfig,
        redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
        redis_prefix: String,
    ) -> Self {
        Self::with_provider(config, redis_pool, redis_prefix, None)
    }

    pub fn with_provider(
        config: AuthConfig,
        redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
        redis_prefix: String,
        permission_provider: Option<Arc<dyn PermissionProvider>>,
    ) -> Self {
        let encoding_key = EncodingKey::from_secret(config.jwt_secret.as_bytes());
        let decoding_key = DecodingKey::from_secret(config.jwt_secret.as_bytes());

        Self {
            config,
            encoding_key,
            decoding_key,
            share_tokens: std::sync::Mutex::new(HashSet::new()),
            permission_cache: PermissionCache::new(redis_pool, redis_prefix),
            per_connection_caches: DashMap::new(),
            permission_provider,
        }
    }

    pub fn permission_cache(&self) -> &PermissionCache {
        &self.permission_cache
    }

    pub fn create_connection_cache(&self, connection_id: Uuid
    ) -> Arc<ConnectionPermissionCache> {
        let cache = Arc::new(ConnectionPermissionCache::new());
        self.per_connection_caches
            .insert(connection_id, cache.clone());
        cache
    }

    pub fn remove_connection_cache(&self, connection_id: &Uuid) {
        self.per_connection_caches.remove(connection_id);
    }

    pub fn issue_token(
        &self,
        user_id: String,
        email: Option<String>,
        name: Option<String>,
        document_permissions: Vec<DocumentPermission>,
    ) -> Result<String, AuthError> {
        let now = chrono::Utc::now().timestamp() as u64;
        let jti = Uuid::new_v4().to_string();

        let claims = JwtClaims {
            sub: user_id.clone(),
            user_id,
            email,
            name,
            iss: self.config.jwt_issuer.clone(),
            aud: self.config.jwt_audience.clone(),
            exp: now + self.config.jwt_expiry_secs,
            iat: now,
            nbf: Some(now),
            jti,
            scope: Some("collab:read collab:write".to_string()),
            document_permissions,
        };

        encode(&Header::default(), &claims, &self.encoding_key)
            .map_err(|e| AuthError::InvalidToken(e.to_string()))
    }

    pub fn verify_token(&self, token: &str) -> Result<JwtClaims, AuthError> {
        let mut validation = Validation::default();
        validation.set_audience(&[self.config.jwt_audience.as_str()]);
        validation.set_issuer(&[self.config.jwt_issuer.as_str()]);
        validation.leeway = 30;

        decode::<JwtClaims>(token, &self.decoding_key, &validation)
            .map(|d| d.claims)
            .map_err(|e| match e.kind() {
                jsonwebtoken::errors::ErrorKind::ExpiredSignature => AuthError::Expired,
                jsonwebtoken::errors::ErrorKind::ImmatureSignature => AuthError::NotYetValid,
                jsonwebtoken::errors::ErrorKind::InvalidSignature => AuthError::InvalidSignature,
                _ => AuthError::InvalidToken(e.to_string()),
            })
    }

    pub fn generate_share_token(
        &self,
        document_id: Uuid,
        created_by: String,
        role: Role,
        expires_at: Option<chrono::DateTime<chrono::Utc>>,
    ) -> Result<(String, ShareTokenClaims), AuthError> {
        let share_id = Uuid::new_v4();
        let now = chrono::Utc::now().timestamp() as u64;

        let exp = match expires_at {
            Some(dt) => dt.timestamp() as u64,
            None => now + self.config.share_token_expiry_secs,
        };

        let jti = Uuid::new_v4().to_string();

        let claims = ShareTokenClaims {
            sub: format!("share:{}", share_id),
            share_id,
            document_id,
            role: role.to_str().to_string(),
            created_by: created_by.clone(),
            exp,
            iat: now,
            jti: jti.clone(),
        };

        let token = encode(&Header::default(), &claims, &self.encoding_key)
            .map_err(|e| AuthError::InvalidToken(e.to_string()))?;

        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let hash = format!("{:x}", hasher.finalize());
        self.share_tokens.lock().unwrap().insert(hash);

        Ok((token, claims))
    }

    pub fn verify_share_token(&self, token: &str) -> Result<ShareTokenClaims, AuthError> {
        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let hash = format!("{:x}", hasher.finalize());

        if !self.share_tokens.lock().unwrap().contains(&hash) {
            return Err(AuthError::ShareLinkInvalid);
        }

        let mut validation = Validation::default();
        validation.set_issuer(&[self.config.jwt_issuer.as_str()]);
        validation.leeway = 30;
        validation.validate_aud = false;

        decode::<ShareTokenClaims>(token, &self.decoding_key, &validation)
            .map(|d| d.claims)
            .map_err(|e| match e.kind() {
                jsonwebtoken::errors::ErrorKind::ExpiredSignature => AuthError::Expired,
                _ => AuthError::ShareLinkInvalid,
            })
    }

    pub fn revoke_share_token(&self, token: &str) {
        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let hash = format!("{:x}", hasher.finalize());
        self.share_tokens.lock().unwrap().remove(&hash);
    }

    pub async fn check_document_permission(
        &self,
        claims: &JwtClaims,
        document_id: &Uuid,
        required: &Role,
    ) -> Result<(), AuthError> {
        if let Some(perm) = claims
            .document_permissions
            .iter()
            .find(|p| &p.document_id == document_id)
        {
            return Self::check_role(&perm.role, required);
        }
        Err(AuthError::PermissionDenied(format!(
            "No permissions for document {}",
            document_id
        )))
    }

    fn check_role(have: &Role, required: &Role) -> Result<(), AuthError> {
        match (have, required) {
            (Role::Owner, _) => Ok(()),
            (Role::Editor, Role::Editor)
            | (Role::Editor, Role::Viewer)
            | (Role::Editor, Role::Commenter) => Ok(()),
            (Role::Commenter, Role::Commenter) | (Role::Commenter, Role::Viewer) => Ok(()),
            (Role::Viewer, Role::Viewer) => Ok(()),
            _ => Err(AuthError::PermissionDenied(format!(
                "Required {:?} but has {:?}",
                required, have
            ))),
        }
    }

    pub async fn check_permission_cached(
        &self,
        connection_id: Option<Uuid>,
        user_id: &str,
        document_id: &Uuid,
        required: &Role,
    ) -> Result<(), AuthError> {
        if let Some(conn_cache) = connection_id.and_then(|cid| self.per_connection_caches.get(&cid)) {
            if let Some(role) = conn_cache.value().get(document_id) {
                return Self::check_role(&role, required);
            }
        }

        if let Some(role) = self.permission_cache.get(user_id, document_id).await? {
            if let Some(cid) = connection_id {
            if let Some(conn_cache) = self.per_connection_caches.get(&cid) {
                    conn_cache.value().insert(*document_id, role.clone());
                }
            }
            return Self::check_role(&role, required);
        }

        if let Some(ref provider) = &self.permission_provider {
            match provider.fetch_permission(user_id, document_id).await? {
                Some(role) => {
                    let _ = self
                        .permission_cache
                        .set(user_id, document_id, &role)
                        .await;
                    if let Some(cid) = connection_id {
                        if let Some(conn_cache) = self.per_connection_caches.get(&cid) {
                            conn_cache.value().insert(*document_id, role.clone());
                        }
                    }
                    return Self::check_role(&role, required);
                }
                None => {
                    return Err(AuthError::PermissionDenied(format!(
                        "No permissions for document {}",
                        document_id
                    )));
                }
            }
        } else {
            Err(AuthError::PermissionDenied(format!(
                "No permissions for document {}",
                document_id
            )))
        }
    }

    pub fn extract_bearer_token(auth_header: &str) -> Result<&str, AuthError> {
        if let Some(token) = auth_header.strip_prefix("Bearer ") {
            Ok(token.trim())
        } else {
            Err(AuthError::InvalidToken("Missing Bearer prefix".into()))
        }
    }

    pub fn invalidate_for_connection(&self, connection_id: &Uuid, event: &InvalidationEvent) {
        if let Some(cache) = self.per_connection_caches.get(connection_id) {
            match event {
                InvalidationEvent::Document { document_id, .. } => {
                    cache.value().invalidate_document(document_id);
                }
                InvalidationEvent::User { .. } | InvalidationEvent::All => {
                    cache.value().invalidate_all();
                }
            }
        }
    }

    pub async fn invalidate_document_permission(
        &self,
        user_id: &str,
        document_id: &Uuid,
    ) -> AuthResult<()> {
        self.permission_cache
            .invalidate_document(user_id, document_id)
            .await?;
        let event = InvalidationEvent::Document {
            user_id: user_id.to_string(),
            document_id: *document_id,
        };
        self.permission_cache
            .publish_invalidation_to_redis(&event)
            .await?;
        Ok(())
    }
}
