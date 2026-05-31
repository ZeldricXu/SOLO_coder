use crate::types::{AppError, generate_id};
use chrono::{Duration, Utc};
use dashmap::DashMap;
use governor::{
    clock::DefaultClock,
    state::{direct::NotKeyed, InMemoryState},
    Quota, RateLimiter,
};
use hmac::{Hmac, Mac};
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::sync::Arc;
use std::time::Duration as StdDuration;
use tracing;
use uuid::Uuid;

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub user_id: String,
    pub username: String,
    pub email: String,
    pub roles: Vec<String>,
    pub permissions: Vec<String>,
    pub is_active: bool,
    pub created_at: chrono::DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthCredentials {
    pub username: String,
    pub password: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    pub refresh_token: String,
    pub token_type: String,
    pub expires_in: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Claims {
    pub sub: String,
    pub username: String,
    pub roles: Vec<String>,
    pub permissions: Vec<String>,
    pub exp: usize,
    pub iat: usize,
    pub jti: String,
    pub token_type: TokenType,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TokenType {
    Access,
    Refresh,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiKey {
    pub key_id: String,
    pub name: String,
    pub api_key: String,
    pub secret_hash: String,
    pub user_id: String,
    pub scopes: Vec<String>,
    pub rate_limit: Option<u32>,
    pub expires_at: Option<chrono::DateTime<Utc>>,
    pub created_at: chrono::DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthContext {
    pub user_id: Option<String>,
    pub username: Option<String>,
    pub roles: Vec<String>,
    pub permissions: Vec<String>,
    pub api_key_id: Option<String>,
    pub scopes: Vec<String>,
    pub authenticated: bool,
    pub auth_method: Option<AuthMethod>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AuthMethod {
    Jwt,
    ApiKey,
    Basic,
    Bearer,
}

#[derive(Debug, Clone)]
pub struct RateLimitConfig {
    pub requests_per_minute: u32,
    pub requests_per_hour: u32,
    pub requests_per_day: u32,
    pub burst_size: u32,
}

impl Default for RateLimitConfig {
    fn default() -> Self {
        Self {
            requests_per_minute: 100,
            requests_per_hour: 1000,
            requests_per_day: 10000,
            burst_size: 50,
        }
    }
}

struct RateLimitState {
    minute_limiter: Arc<RateLimiter<NotKeyed, InMemoryState, DefaultClock>>,
    hour_limiter: Arc<RateLimiter<NotKeyed, InMemoryState, DefaultClock>>,
    day_limiter: Arc<RateLimiter<NotKeyed, InMemoryState, DefaultClock>>,
}

pub struct ApiGateway {
    users: Arc<DashMap<String, User>>,
    api_keys: Arc<DashMap<String, ApiKey>>,
    tokens: Arc<DashMap<String, TokenInfo>>,
    rate_limiters: Arc<DashMap<String, RateLimitState>>,
    roles: Arc<DashMap<String, Role>>,
    default_rate_limit: RateLimitConfig,
    jwt_secret: String,
    token_expiry_seconds: u64,
    refresh_token_expiry_seconds: u64,
}

#[derive(Debug, Clone)]
struct TokenInfo {
    pub token: String,
    pub user_id: String,
    pub token_type: TokenType,
    pub expires_at: chrono::DateTime<Utc>,
    pub created_at: chrono::DateTime<Utc>,
    pub revoked: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Role {
    pub role_id: String,
    pub name: String,
    pub description: String,
    pub permissions: Vec<String>,
}

impl ApiGateway {
    pub fn new(jwt_secret: String, default_rate_limit: RateLimitConfig) -> Self {
        let gateway = Self {
            users: Arc::new(DashMap::new()),
            api_keys: Arc::new(DashMap::new()),
            tokens: Arc::new(DashMap::new()),
            rate_limiters: Arc::new(DashMap::new()),
            roles: Arc::new(DashMap::new()),
            default_rate_limit,
            jwt_secret,
            token_expiry_seconds: 3600,
            refresh_token_expiry_seconds: 86400 * 7,
        };

        gateway.init_default_roles();
        gateway.start_token_cleanup();
        gateway
    }

    fn init_default_roles(&self) {
        let admin_role = Role {
            role_id: generate_id("role"),
            name: "admin".to_string(),
            description: "系统管理员".to_string(),
            permissions: vec![
                "resource:create".to_string(),
                "resource:read".to_string(),
                "resource:update".to_string(),
                "resource:delete".to_string(),
                "admin:manage".to_string(),
            ],
        };

        let user_role = Role {
            role_id: generate_id("role"),
            name: "user".to_string(),
            description: "普通用户".to_string(),
            permissions: vec![
                "resource:read".to_string(),
                "resource:create".to_string(),
            ],
        };

        let viewer_role = Role {
            role_id: generate_id("role"),
            name: "viewer".to_string(),
            description: "只读用户".to_string(),
            permissions: vec![
                "resource:read".to_string(),
            ],
        };

        self.roles.insert("admin".to_string(), admin_role);
        self.roles.insert("user".to_string(), user_role);
        self.roles.insert("viewer".to_string(), viewer_role);
    }

    pub fn register_user(&self, username: &str, password: &str, email: &str, roles: Vec<String>) -> Result<User, AppError> {
        if self.users.iter().any(|u| u.value().username == username) {
            return Err(AppError::Conflict(format!("用户名已存在: {}", username)));
        }

        let password_hash = self.hash_password(password)?;
        let user = User {
            user_id: generate_id("usr"),
            username: username.to_string(),
            email: email.to_string(),
            roles: roles.clone(),
            permissions: self.get_role_permissions(&roles),
            is_active: true,
            created_at: Utc::now(),
        };

        self.users.insert(user.user_id.clone(), user.clone());
        tracing::info!(user_id = %user.user_id, username = %username, "用户注册成功");
        Ok(user)
    }

    pub async fn login(&self, credentials: AuthCredentials) -> Result<TokenResponse, AppError> {
        let user = self.users
            .iter()
            .find(|u| u.value().username == credentials.username)
            .map(|u| u.value().clone())
            .ok_or_else(|| AppError::Unauthorized("用户名或密码错误".to_string()))?;

        if !user.is_active {
            return Err(AppError::Forbidden("用户已被禁用".to_string()));
        }

        if !self.verify_password(&credentials.password, &self.hash_password(&credentials.password)?) {
            return Err(AppError::Unauthorized("用户名或密码错误".to_string()));
        }

        let access_token = self.generate_token(&user, TokenType::Access)?;
        let refresh_token = self.generate_token(&user, TokenType::Refresh)?;

        self.store_token(&access_token, &user.user_id, TokenType::Access, self.token_expiry_seconds);
        self.store_token(&refresh_token, &user.user_id, TokenType::Refresh, self.refresh_token_expiry_seconds);

        tracing::info!(user_id = %user.user_id, "用户登录成功");

        Ok(TokenResponse {
            access_token,
            refresh_token,
            token_type: "Bearer".to_string(),
            expires_in: self.token_expiry_seconds,
        })
    }

    pub fn refresh_token(&self, refresh_token: &str) -> Result<TokenResponse, AppError> {
        let token_info = self.tokens
            .get(refresh_token)
            .ok_or_else(|| AppError::Unauthorized("刷新令牌无效".to_string()))?;

        if token_info.revoked {
            return Err(AppError::Unauthorized("刷新令牌已被撤销".to_string()));
        }

        if token_info.expires_at < Utc::now() {
            return Err(AppError::Unauthorized("刷新令牌已过期".to_string()));
        }

        if token_info.token_type != TokenType::Refresh {
            return Err(AppError::Unauthorized("令牌类型错误".to_string()));
        }

        let user = self.users
            .get(&token_info.user_id)
            .ok_or_else(|| AppError::Unauthorized("用户不存在".to_string()))?
            .clone();

        let access_token = self.generate_token(&user, TokenType::Access)?;
        let new_refresh_token = self.generate_token(&user, TokenType::Refresh)?;

        self.tokens.get_mut(refresh_token).unwrap().revoked = true;

        self.store_token(&access_token, &user.user_id, TokenType::Access, self.token_expiry_seconds);
        self.store_token(&new_refresh_token, &user.user_id, TokenType::Refresh, self.refresh_token_expiry_seconds);

        tracing::info!(user_id = %user.user_id, "令牌刷新成功");

        Ok(TokenResponse {
            access_token,
            refresh_token: new_refresh_token,
            token_type: "Bearer".to_string(),
            expires_in: self.token_expiry_seconds,
        })
    }

    pub fn logout(&self, access_token: &str, refresh_token: Option<&str>) -> Result<(), AppError> {
        if let Some(mut token) = self.tokens.get_mut(access_token) {
            token.revoked = true;
        }
        if let Some(rt) = refresh_token {
            if let Some(mut token) = self.tokens.get_mut(rt) {
                token.revoked = true;
            }
        }
        Ok(())
    }

    pub fn authenticate(&self, auth_header: &str, api_key_header: Option<&str>) -> Result<AuthContext, AppError> {
        if let Some(api_key) = api_key_header {
            return self.authenticate_api_key(api_key);
        }

        if auth_header.starts_with("Bearer ") {
            let token = &auth_header[7..];
            return self.authenticate_jwt(token);
        }

        if auth_header.starts_with("Basic ") {
            let credentials = &auth_header[6..];
            return self.authenticate_basic(credentials);
        }

        Err(AppError::Unauthorized("未提供有效的认证信息".to_string()))
    }

    fn authenticate_jwt(&self, token: &str) -> Result<AuthContext, AppError> {
        let token_info = self.tokens
            .get(token)
            .ok_or_else(|| AppError::Unauthorized("令牌无效".to_string()))?;

        if token_info.revoked {
            return Err(AppError::Unauthorized("令牌已被撤销".to_string()));
        }

        if token_info.expires_at < Utc::now() {
            return Err(AppError::Unauthorized("令牌已过期".to_string()));
        }

        if token_info.token_type != TokenType::Access {
            return Err(AppError::Unauthorized("令牌类型错误".to_string()));
        }

        let decoding_key = DecodingKey::from_secret(self.jwt_secret.as_bytes());
        let mut validation = Validation::default();
        validation.validate_exp = true;

        let token_data = decode::<Claims>(token, &decoding_key, &validation)
            .map_err(|e| AppError::Unauthorized(format!("令牌验证失败: {}", e)))?;

        let user = self.users
            .get(&token_data.claims.sub)
            .ok_or_else(|| AppError::Unauthorized("用户不存在".to_string()))?;

        Ok(AuthContext {
            user_id: Some(user.user_id.clone()),
            username: Some(user.username.clone()),
            roles: user.roles.clone(),
            permissions: user.permissions.clone(),
            api_key_id: None,
            scopes: Vec::new(),
            authenticated: true,
            auth_method: Some(AuthMethod::Jwt),
        })
    }

    fn authenticate_api_key(&self, api_key: &str) -> Result<AuthContext, AppError> {
        let key = self.api_keys
            .iter()
            .find(|k| k.value().api_key == api_key)
            .map(|k| k.value().clone())
            .ok_or_else(|| AppError::Unauthorized("API密钥无效".to_string()))?;

        if let Some(expires_at) = key.expires_at {
            if expires_at < Utc::now() {
                return Err(AppError::Unauthorized("API密钥已过期".to_string()));
            }
        }

        let user = self.users
            .get(&key.user_id)
            .ok_or_else(|| AppError::Unauthorized("用户不存在".to_string()))?;

        Ok(AuthContext {
            user_id: Some(user.user_id.clone()),
            username: Some(user.username.clone()),
            roles: user.roles.clone(),
            permissions: user.permissions.clone(),
            api_key_id: Some(key.key_id.clone()),
            scopes: key.scopes.clone(),
            authenticated: true,
            auth_method: Some(AuthMethod::ApiKey),
        })
    }

    fn authenticate_basic(&self, credentials: &str) -> Result<AuthContext, AppError> {
        let decoded = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, credentials)
            .map_err(|e| AppError::Unauthorized(format!("Basic认证解码失败: {}", e)))?;
        
        let decoded_str = String::from_utf8(decoded)
            .map_err(|e| AppError::Unauthorized(format!("Basic认证编码无效: {}", e)))?;

        let parts: Vec<&str> = decoded_str.splitn(2, ':').collect();
        if parts.len() != 2 {
            return Err(AppError::Unauthorized("Basic认证格式无效".to_string()));
        }

        let credentials = AuthCredentials {
            username: parts[0].to_string(),
            password: parts[1].to_string(),
        };

        let user = self.users
            .iter()
            .find(|u| u.value().username == credentials.username)
            .map(|u| u.value().clone())
            .ok_or_else(|| AppError::Unauthorized("用户名或密码错误".to_string()))?;

        if !user.is_active {
            return Err(AppError::Forbidden("用户已被禁用".to_string()));
        }

        Ok(AuthContext {
            user_id: Some(user.user_id.clone()),
            username: Some(user.username.clone()),
            roles: user.roles.clone(),
            permissions: user.permissions.clone(),
            api_key_id: None,
            scopes: Vec::new(),
            authenticated: true,
            auth_method: Some(AuthMethod::Basic),
        })
    }

    pub fn authorize(&self, ctx: &AuthContext, required_permission: &str) -> Result<(), AppError> {
        if !ctx.authenticated {
            return Err(AppError::Unauthorized("未认证".to_string()));
        }

        if ctx.permissions.contains(&required_permission.to_string()) {
            return Ok(());
        }

        if ctx.roles.iter().any(|role| {
            self.roles.get(role)
                .map(|r| r.permissions.contains(&required_permission.to_string()))
                .unwrap_or(false)
        }) {
            return Ok(());
        }

        Err(AppError::Forbidden(format!("权限不足，需要: {}", required_permission)))
    }

    pub fn authorize_role(&self, ctx: &AuthContext, required_role: &str) -> Result<(), AppError> {
        if !ctx.authenticated {
            return Err(AppError::Unauthorized("未认证".to_string()));
        }

        if ctx.roles.contains(&required_role.to_string()) {
            return Ok(());
        }

        Err(AppError::Forbidden(format!("角色不足，需要: {}", required_role)))
    }

    pub fn check_rate_limit(&self, identifier: &str) -> Result<(), AppError> {
        let limiter_state = self.rate_limiters
            .entry(identifier.to_string())
            .or_insert_with(|| self.create_rate_limiter_state(&self.default_rate_limit));

        if let Err(_) = limiter_state.minute_limiter.check() {
            return Err(AppError::RateLimited);
        }

        if let Err(_) = limiter_state.hour_limiter.check() {
            return Err(AppError::RateLimited);
        }

        if let Err(_) = limiter_state.day_limiter.check() {
            return Err(AppError::RateLimited);
        }

        Ok(())
    }

    fn create_rate_limiter_state(&self, config: &RateLimitConfig) -> RateLimitState {
        let minute_quota = Quota::per_minute(config.requests_per_minute.try_into().unwrap())
            .allow_burst(config.burst_size.try_into().unwrap());
        let hour_quota = Quota::per_hour(config.requests_per_hour.try_into().unwrap());
        let day_quota = Quota::per_day(config.requests_per_day.try_into().unwrap());

        RateLimitState {
            minute_limiter: Arc::new(RateLimiter::direct(minute_quota)),
            hour_limiter: Arc::new(RateLimiter::direct(hour_quota)),
            day_limiter: Arc::new(RateLimiter::direct(day_quota)),
        }
    }

    pub fn create_api_key(&self, user_id: &str, name: &str, scopes: Vec<String>, expires_in_days: Option<u32>) -> Result<ApiKey, AppError> {
        let api_key = format!("sk_{}", Uuid::new_v4().to_string().replace("-", ""));
        let secret = Uuid::new_v4().to_string();
        let secret_hash = self.hash_password(&secret)?;

        let expires_at = expires_in_days.map(|d| Utc::now() + Duration::days(d as i64));

        let key = ApiKey {
            key_id: generate_id("apikey"),
            name: name.to_string(),
            api_key: api_key.clone(),
            secret_hash,
            user_id: user_id.to_string(),
            scopes,
            rate_limit: None,
            expires_at,
            created_at: Utc::now(),
        };

        self.api_keys.insert(key.key_id.clone(), key.clone());
        tracing::info!(key_id = %key.key_id, user_id = %user_id, "API密钥创建成功");
        Ok(key)
    }

    pub fn revoke_api_key(&self, key_id: &str) -> Result<(), AppError> {
        if self.api_keys.remove(key_id).is_some() {
            tracing::info!(key_id = %key_id, "API密钥已撤销");
            Ok(())
        } else {
            Err(AppError::NotFound(format!("API密钥不存在: {}", key_id)))
        }
    }

    fn generate_token(&self, user: &User, token_type: TokenType) -> Result<String, AppError> {
        let now = Utc::now();
        let expiry = match token_type {
            TokenType::Access => now + Duration::seconds(self.token_expiry_seconds as i64),
            TokenType::Refresh => now + Duration::seconds(self.refresh_token_expiry_seconds as i64),
        };

        let claims = Claims {
            sub: user.user_id.clone(),
            username: user.username.clone(),
            roles: user.roles.clone(),
            permissions: user.permissions.clone(),
            exp: expiry.timestamp() as usize,
            iat: now.timestamp() as usize,
            jti: Uuid::new_v4().to_string(),
            token_type,
        };

        let token = encode(
            &Header::default(),
            &claims,
            &EncodingKey::from_secret(self.jwt_secret.as_bytes()),
        )
        .map_err(|e| AppError::InternalError(format!("生成令牌失败: {}", e)))?;

        Ok(token)
    }

    fn store_token(&self, token: &str, user_id: &str, token_type: TokenType, expiry_seconds: u64) {
        let token_info = TokenInfo {
            token: token.to_string(),
            user_id: user_id.to_string(),
            token_type,
            expires_at: Utc::now() + Duration::seconds(expiry_seconds as i64),
            created_at: Utc::now(),
            revoked: false,
        };

        self.tokens.insert(token.to_string(), token_info);
    }

    fn hash_password(&self, password: &str) -> Result<String, AppError> {
        let salt = b"static_salt_for_demo";
        let mut mac = HmacSha256::new_from_slice(salt)
            .map_err(|e| AppError::InternalError(format!("HMAC初始化失败: {}", e)))?;
        mac.update(password.as_bytes());
        let result = mac.finalize();
        let hash_bytes = result.into_bytes();
        Ok(base64::Engine::encode(&base64::engine::general_purpose::STANDARD, hash_bytes))
    }

    fn verify_password(&self, password: &str, hash: &str) -> bool {
        match self.hash_password(password) {
            Ok(computed) => computed == hash,
            Err(_) => false,
        }
    }

    fn get_role_permissions(&self, roles: &[String]) -> Vec<String> {
        let mut permissions = Vec::new();
        for role in roles {
            if let Some(role_def) = self.roles.get(role) {
                permissions.extend(role_def.permissions.clone());
            }
        }
        permissions.sort();
        permissions.dedup();
        permissions
    }

    fn start_token_cleanup(&self) {
        let tokens = self.tokens.clone();

        tokio::spawn(async move {
            let mut interval = tokio::time::interval(StdDuration::from_secs(3600));
            
            loop {
                interval.tick().await;
                
                let now = Utc::now();
                let mut removed = 0;

                tokens.retain(|_, info| {
                    if info.expires_at < now || info.revoked {
                        removed += 1;
                        false
                    } else {
                        true
                    }
                });

                if removed > 0 {
                    tracing::info!("清理过期令牌，数量: {}", removed);
                }
            }
        });
    }

    pub fn get_user(&self, user_id: &str) -> Option<User> {
        self.users.get(user_id).map(|u| u.clone())
    }

    pub fn list_users(&self) -> Vec<User> {
        self.users.iter().map(|u| u.value().clone()).collect()
    }

    pub fn list_api_keys(&self, user_id: Option<&str>) -> Vec<ApiKey> {
        self.api_keys
            .iter()
            .filter(|k| user_id.map_or(true, |uid| k.value().user_id == uid))
            .map(|k| k.value().clone())
            .collect()
    }
}
