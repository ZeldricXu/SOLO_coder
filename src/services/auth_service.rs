use std::time::Duration;

use chrono::{DateTime, Utc};
use reqwest::Client;
use serde::Deserialize;
use uuid::Uuid;

use crate::config::{OAuthProviderConfig, Settings};
use crate::models::{AuthUser, OAuthCredential, User, UserInfo};
use crate::repositories::{NotificationRepository, UserRepository};
use crate::utils::{AppError, AppResult};

#[derive(Clone)]
pub struct AuthService {
    user_repo: UserRepository,
    notification_repo: NotificationRepository,
    settings: Settings,
    redis_client: crate::providers::RedisClient,
    http_client: Client,
}

impl AuthService {
    pub fn new(
        user_repo: UserRepository,
        notification_repo: NotificationRepository,
        settings: Settings,
        redis_client: crate::providers::RedisClient,
    ) -> Self {
        Self {
            user_repo,
            notification_repo,
            settings,
            redis_client,
            http_client: Client::new(),
        }
    }

    pub fn get_auth_url(&self, provider: &str, state: &str) -> AppResult<String> {
        let oauth_config = self.get_oauth_config(provider)?;
        let auth_url = format!(
            "{}?client_id={}&redirect_uri={}&state={}&response_type=code&scope={}",
            oauth_config.auth_url,
            oauth_config.client_id,
            oauth_config.redirect_url,
            state,
            self.get_scope(provider)
        );
        Ok(auth_url)
    }

    pub async fn exchange_code(&self, provider: &str, code: &str) -> AppResult<TokenResponse> {
        let oauth_config = self.get_oauth_config(provider)?;

        let params = [
            ("client_id", oauth_config.client_id.as_str()),
            ("client_secret", oauth_config.client_secret.as_str()),
            ("code", code),
            ("redirect_uri", oauth_config.redirect_url.as_str()),
            ("grant_type", "authorization_code"),
        ];

        let response = self
            .http_client
            .post(&oauth_config.token_url)
            .form(&params)
            .send()
            .await
            .map_err(|e| {
                AppError::ExternalService(format!("OAuth token request failed: {}", e))
            })?;

        if !response.status().is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(AppError::Authentication(format!(
                "OAuth token exchange failed: {}",
                error_body
            )));
        }

        let token_response: TokenResponse = response.json().await.map_err(|e| {
            AppError::Serialization(e)
        })?;

        Ok(token_response)
    }

    pub async fn oauth_login(
        &self,
        provider: &str,
        code: &str,
    ) -> AppResult<(UserInfo, String)> {
        let token_response = self.exchange_code(provider, code).await?;

        let provider_user = self
            .get_provider_user_info(provider, &token_response.access_token)
            .await?;

        let user = self
            .user_repo
            .create(
                &provider_user.username,
                &provider_user.email.clone().unwrap_or_else(|| {
                    format!("{}@{}", provider_user.username, provider)
                }),
                provider_user.avatar_url.as_deref(),
            )
            .await?;

        let expires_at = token_response.expires_in.map(|secs| {
            Utc::now() + Duration::from_secs(secs as u64)
        });

        let _credential = self
            .user_repo
            .create_oauth_credential(
                user.id,
                provider,
                &provider_user.id,
                &token_response.access_token,
                token_response.refresh_token.as_deref(),
                expires_at,
            )
            .await?;

        let _ = self
            .notification_repo
            .create_default_settings(user.id)
            .await;

        let role = self
            .user_repo
            .get_user_highest_role(user.id, Uuid::nil())
            .await?
            .unwrap_or_else(|| "developer".to_string());

        let user_info = UserInfo {
            id: user.id,
            username: user.username,
            email: user.email,
            avatar_url: user.avatar_url,
            provider: provider.to_string(),
            provider_id: provider_user.id,
            role,
            created_at: user.created_at,
        };

        let session_id = self.create_session(&user_info).await?;

        Ok((user_info, session_id))
    }

    pub async fn create_session(&self, user: &UserInfo) -> AppResult<String> {
        let session_id = Uuid::new_v4().to_string();
        let key = format!("session:{}", session_id);

        let auth_user = AuthUser {
            id: user.id,
            username: user.username.clone(),
            email: user.email.clone(),
            avatar_url: user.avatar_url.clone(),
            role: user.role.clone(),
            organization_id: None,
        };

        self.redis_client
            .set_ex(&key, &auth_user, self.settings.session.ttl_secs)
            .await?;

        Ok(session_id)
    }

    pub async fn validate_session(&self, session_id: &str) -> AppResult<Option<AuthUser>> {
        let key = format!("session:{}", session_id);
        let user = self.redis_client.get::<AuthUser>(&key).await?;

        if let Some(ref user) = user {
            self.redis_client
                .set_ex(&key, user, self.settings.session.ttl_secs)
                .await?;
        }

        Ok(user)
    }

    pub async fn get_current_user(&self, session_id: &str) -> AppResult<AuthUser> {
        self.validate_session(session_id)
            .await?
            .ok_or_else(|| AppError::Authentication("Session not found or expired".to_string()))
    }

    pub async fn logout(&self, session_id: &str) -> AppResult<()> {
        let key = format!("session:{}", session_id);
        self.redis_client.del(&key).await?;
        Ok(())
    }

    fn get_oauth_config(&self, provider: &str) -> AppResult<&OAuthProviderConfig> {
        match provider {
            "github" => Ok(&self.settings.oauth.github),
            "gitlab" => Ok(&self.settings.oauth.gitlab),
            "gitee" => Ok(&self.settings.oauth.gitee),
            _ => Err(AppError::Validation(format!(
                "Unsupported OAuth provider: {}",
                provider
            ))),
        }
    }

    fn get_scope(&self, provider: &str) -> &str {
        match provider {
            "github" => "read:user user:email repo",
            "gitlab" => "read_user read_api read_repository",
            "gitee" => "user_info projects",
            _ => "",
        }
    }

    async fn get_provider_user_info(
        &self,
        provider: &str,
        access_token: &str,
    ) -> AppResult<ProviderUserInfo> {
        let oauth_config = self.get_oauth_config(provider)?;
        let api_base = &oauth_config.api_base_url;

        let user_path = match provider {
            "github" => "/user",
            "gitlab" => "/api/v4/user",
            "gitee" => "/api/v5/user",
            _ => {
                return Err(AppError::Validation(format!(
                    "Unsupported OAuth provider: {}",
                    provider
                )))
            }
        };

        let url = format!("{}{}", api_base, user_path);

        let response = self
            .http_client
            .get(&url)
            .bearer_auth(access_token)
            .send()
            .await
            .map_err(|e| {
                AppError::ExternalService(format!("Provider API request failed: {}", e))
            })?;

        if !response.status().is_success() {
            let error_body = response.text().await.unwrap_or_default();
            return Err(AppError::Authentication(format!(
                "Failed to get user info from {}: {}",
                provider, error_body
            )));
        }

        let user_info: ProviderUserInfo = match provider {
            "github" => {
                let gh_user: GitHubUser = response.json().await.map_err(|e| {
                    AppError::Serialization(e)
                })?;
                ProviderUserInfo {
                    id: gh_user.id.to_string(),
                    username: gh_user.login,
                    email: gh_user.email,
                    avatar_url: Some(gh_user.avatar_url),
                }
            }
            "gitlab" => {
                let gl_user: GitLabUser = response.json().await.map_err(|e| {
                    AppError::Serialization(e)
                })?;
                ProviderUserInfo {
                    id: gl_user.id.to_string(),
                    username: gl_user.username,
                    email: Some(gl_user.email),
                    avatar_url: gl_user.avatar_url,
                }
            }
            "gitee" => {
                let gitee_user: GiteeUser = response.json().await.map_err(|e| {
                    AppError::Serialization(e)
                })?;
                ProviderUserInfo {
                    id: gitee_user.id.to_string(),
                    username: gitee_user.login,
                    email: gitee_user.email,
                    avatar_url: Some(gitee_user.avatar_url),
                }
            }
            _ => unreachable!(),
        };

        Ok(user_info)
    }
}

#[derive(Debug, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    pub token_type: Option<String>,
    pub expires_in: Option<u32>,
    pub refresh_token: Option<String>,
    pub scope: Option<String>,
}

#[derive(Debug, Clone)]
struct ProviderUserInfo {
    id: String,
    username: String,
    email: Option<String>,
    avatar_url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GitHubUser {
    id: i64,
    login: String,
    email: Option<String>,
    avatar_url: String,
}

#[derive(Debug, Deserialize)]
struct GitLabUser {
    id: i64,
    username: String,
    email: String,
    avatar_url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GiteeUser {
    id: i64,
    login: String,
    email: Option<String>,
    avatar_url: String,
}
