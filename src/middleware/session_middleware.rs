use std::collections::HashMap;
use std::future::{ready, Ready};
use std::time::Duration;

use actix_web::cookie::{Cookie, SameSite};
use actix_web::dev::{forward_ready, Service, ServiceRequest, ServiceResponse, Transform};
use actix_web::http::header;
use actix_web::{Error, HttpMessage};
use futures_util::future::LocalBoxFuture;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use tracing::{debug, warn};
use uuid::Uuid;

use crate::config::{Settings, SessionConfig};
use crate::providers::RedisClient;
use crate::utils::AppResult;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SessionData {
    pub id: String,
    pub user_id: Option<Uuid>,
    pub data: HashMap<String, serde_json::Value>,
    pub flash: Option<FlashMessage>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub expires_at: chrono::DateTime<chrono::Utc>,
}

impl SessionData {
    pub fn new(id: String, ttl: Duration) -> Self {
        let now = chrono::Utc::now();
        Self {
            id,
            user_id: None,
            data: HashMap::new(),
            flash: None,
            created_at: now,
            expires_at: now + chrono::Duration::seconds(ttl.as_secs() as i64),
        }
    }

    pub fn get<T: DeserializeOwned>(&self, key: &str) -> Option<T> {
        self.data
            .get(key)
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }

    pub fn set<T: Serialize>(&mut self, key: &str, value: &T) -> AppResult<()> {
        let json_value = serde_json::to_value(value)?;
        self.data.insert(key.to_string(), json_value);
        Ok(())
    }

    pub fn remove(&mut self, key: &str) {
        self.data.remove(key);
    }

    pub fn set_flash(&mut self, message: FlashMessage) {
        self.flash = Some(message);
    }

    pub fn take_flash(&mut self) -> Option<FlashMessage> {
        self.flash.take()
    }

    pub fn needs_renewal(&self, ttl: Duration) -> bool {
        let now = chrono::Utc::now();
        let remaining = self.expires_at - now;
        remaining.num_seconds() < (ttl.as_secs() as i64) / 2
    }

    pub fn renew(&mut self, ttl: Duration) {
        self.expires_at = chrono::Utc::now() + chrono::Duration::seconds(ttl.as_secs() as i64);
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FlashMessage {
    pub message_type: String,
    pub message: String,
}

impl FlashMessage {
    pub fn success(message: &str) -> Self {
        Self {
            message_type: "success".to_string(),
            message: message.to_string(),
        }
    }

    pub fn error(message: &str) -> Self {
        Self {
            message_type: "error".to_string(),
            message: message.to_string(),
        }
    }

    pub fn info(message: &str) -> Self {
        Self {
            message_type: "info".to_string(),
            message: message.to_string(),
        }
    }

    pub fn warning(message: &str) -> Self {
        Self {
            message_type: "warning".to_string(),
            message: message.to_string(),
        }
    }
}

#[derive(Clone)]
pub struct SessionMiddleware {
    redis_client: RedisClient,
    settings: Settings,
}

impl SessionMiddleware {
    pub fn new(redis_client: RedisClient, settings: Settings) -> Self {
        Self {
            redis_client,
            settings,
        }
    }

    fn session_key(&self, session_id: &str) -> String {
        format!("session:{}", session_id)
    }

    async fn load_session(&self, session_id: &str) -> AppResult<Option<SessionData>> {
        let key = self.session_key(session_id);
        self.redis_client.get::<SessionData>(&key).await
    }

    async fn save_session(&self, session: &SessionData) -> AppResult<()> {
        let key = self.session_key(&session.id);
        let ttl = self.settings.session.ttl_secs;
        self.redis_client.set_ex(&key, session, ttl).await
    }

}

impl<S, B> Transform<S, ServiceRequest> for SessionMiddleware
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type Transform = SessionMiddlewareService<S>;
    type InitError = ();
    type Future = Ready<Result<Self::Transform, Self::InitError>>;

    fn new_transform(&self, service: S) -> Self::Future {
        ready(Ok(SessionMiddlewareService {
            service,
            redis_client: self.redis_client.clone(),
            settings: self.settings.clone(),
        }))
    }
}

pub struct SessionMiddlewareService<S> {
    service: S,
    redis_client: RedisClient,
    settings: Settings,
}

impl<S> SessionMiddlewareService<S> {
    fn session_key(&self, session_id: &str) -> String {
        format!("session:{}", session_id)
    }

    async fn load_session(&self, session_id: &str) -> AppResult<Option<SessionData>> {
        let key = self.session_key(session_id);
        self.redis_client.get::<SessionData>(&key).await
    }

    async fn save_session(&self, session: &SessionData) -> AppResult<()> {
        let key = self.session_key(&session.id);
        let ttl = self.settings.session.ttl_secs;
        self.redis_client.set_ex(&key, session, ttl).await
    }

    async fn renew_session(&self, session: &mut SessionData) -> AppResult<()> {
        let ttl = Duration::from_secs(self.settings.session.ttl_secs);
        session.renew(ttl);
        self.save_session(session).await
    }

    fn create_session_cookie(&self, session_id: &str, session_config: &SessionConfig) -> Cookie<'_> {
        let mut cookie = Cookie::new(session_config.cookie_name.clone(), session_id.to_string());
        cookie.set_http_only(session_config.cookie_http_only);
        cookie.set_secure(session_config.cookie_secure);
        cookie.set_same_site(SameSite::Lax);
        cookie.set_path("/");
        cookie.set_max_age(actix_web::cookie::time::Duration::seconds(
            session_config.ttl_secs as i64,
        ));
        cookie
    }
}

impl<S, B> Service<ServiceRequest> for SessionMiddlewareService<S>
where
    S: Service<ServiceRequest, Response = ServiceResponse<B>, Error = Error> + Clone + 'static,
    B: 'static,
{
    type Response = ServiceResponse<B>;
    type Error = Error;
    type Future = LocalBoxFuture<'static, Result<Self::Response, Self::Error>>;

    forward_ready!(service);

    fn call(&self, req: ServiceRequest) -> Self::Future {
        let service = self.service.clone();
        let redis_client = self.redis_client.clone();
        let settings = self.settings.clone();

        Box::pin(async move {
            let session_config = &settings.session;
            let cookie_name = &session_config.cookie_name;

            let existing_session_id = req
                .cookie(cookie_name)
                .map(|c| c.value().to_string());

            let (mut session, is_new) = match existing_session_id {
                Some(session_id) => {
                    match load_session_from_redis(&redis_client, &session_id).await {
                        Ok(Some(mut session)) => {
                            let ttl = Duration::from_secs(session_config.ttl_secs);
                            if session.needs_renewal(ttl) {
                                debug!("Session needs renewal, extending TTL");
                                session.renew(ttl);
                                if let Err(e) = save_session_to_redis(&redis_client, &session, session_config.ttl_secs).await {
                                    warn!("Failed to renew session: {}", e);
                                }
                            }
                            (session, false)
                        }
                        Ok(None) => {
                            debug!("Session not found in Redis, creating new session");
                            let new_id = Uuid::new_v4().to_string();
                            (SessionData::new(new_id, Duration::from_secs(session_config.ttl_secs)), true)
                        }
                        Err(e) => {
                            warn!("Error loading session: {}", e);
                            let new_id = Uuid::new_v4().to_string();
                            (SessionData::new(new_id, Duration::from_secs(session_config.ttl_secs)), true)
                        }
                    }
                }
                None => {
                    debug!("No session cookie found, creating new session");
                    let new_id = Uuid::new_v4().to_string();
                    (SessionData::new(new_id, Duration::from_secs(session_config.ttl_secs)), true)
                }
            };

            req.extensions_mut().insert(session.clone());

            let flash_to_take = session.take_flash();
            if let Some(flash) = flash_to_take.clone() {
                req.extensions_mut().insert(flash);
            }

            let mut res = service.call(req).await?;

            if flash_to_take.is_some() {
                if let Some(s) = res.request().extensions().get::<SessionData>() {
                    session = s.clone();
                }
                if session.flash.is_none() {
                    if let Err(e) = save_session_to_redis(&redis_client, &session, session_config.ttl_secs).await {
                        warn!("Failed to save session after flash consumed: {}", e);
                    }
                }
            }

            if is_new {
                if let Err(e) = save_session_to_redis(&redis_client, &session, session_config.ttl_secs).await {
                    warn!("Failed to save new session: {}", e);
                }
            }

            let cookie = create_cookie(&session.id, session_config);
            let _ = res.response_mut().add_cookie(&cookie);

            if is_new {
                debug!(session_id = %session.id, "New session created and cookie set");
            }

            Ok(res)
        })
    }
}

async fn load_session_from_redis(
    redis_client: &RedisClient,
    session_id: &str,
) -> AppResult<Option<SessionData>> {
    let key = format!("session:{}", session_id);
    redis_client.get::<SessionData>(&key).await
}

async fn save_session_to_redis(
    redis_client: &RedisClient,
    session: &SessionData,
    ttl_secs: u64,
) -> AppResult<()> {
    let key = format!("session:{}", session.id);
    redis_client.set_ex(&key, session, ttl_secs).await
}

fn create_cookie<'a>(session_id: &str, session_config: &SessionConfig) -> Cookie<'a> {
    let mut cookie = Cookie::new(session_config.cookie_name.clone(), session_id.to_string());
    cookie.set_http_only(session_config.cookie_http_only);
    cookie.set_secure(session_config.cookie_secure);
    cookie.set_same_site(SameSite::Lax);
    cookie.set_path("/");
    cookie.set_max_age(actix_web::cookie::time::Duration::seconds(
        session_config.ttl_secs as i64,
    ));
    cookie
}

pub fn get_session(req: &actix_web::HttpRequest) -> Option<&SessionData> {
    req.extensions().get::<SessionData>()
}

pub fn get_session_mut(req: &actix_web::HttpRequest) -> Option<&mut SessionData> {
    req.extensions_mut().get_mut::<SessionData>()
}

pub fn set_session<T: Serialize>(req: &actix_web::HttpRequest, key: &str, value: &T) -> AppResult<()> {
    if let Some(session) = req.extensions_mut().get_mut::<SessionData>() {
        session.set(key, value)
    } else {
        Err(crate::utils::AppError::Internal("Session not found".to_string()))
    }
}

pub fn get_session_value<T: DeserializeOwned>(req: &actix_web::HttpRequest, key: &str) -> Option<T> {
    req.extensions()
        .get::<SessionData>()
        .and_then(|s| s.get(key))
}

pub fn remove_session_value(req: &actix_web::HttpRequest, key: &str) {
    if let Some(session) = req.extensions_mut().get_mut::<SessionData>() {
        session.remove(key);
    }
}

pub fn set_flash(req: &actix_web::HttpRequest, message: FlashMessage) {
    if let Some(session) = req.extensions_mut().get_mut::<SessionData>() {
        session.set_flash(message);
    }
}

pub fn get_flash(req: &actix_web::HttpRequest) -> Option<&FlashMessage> {
    req.extensions().get::<FlashMessage>()
}
