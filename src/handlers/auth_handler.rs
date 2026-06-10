use actix_session::Session;
use actix_web::{web, HttpResponse, Responder};
use serde::Deserialize;
use uuid::Uuid;

use crate::config::Settings;
use crate::models::AuthUser;
use crate::services::AuthService;
use crate::utils::{AppError, AppResult, ApiResponse};

const SESSION_ID_KEY: &str = "session_id";
const STATE_KEY: &str = "oauth_state";

#[derive(Debug, Deserialize)]
pub struct OAuthCallbackQuery {
    pub code: String,
    pub state: String,
}

pub async fn login_page() -> impl Responder {
    let html = maud::html! {
        (maud::DOCTYPE)
        html {
            head {
                meta charset="utf-8";
                meta name="viewport" content="width=device-width, initial-scale=1.0";
                title { "登录 - 代码审查平台" }
                style {
                    r#"
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    }
                    .login-container {
                        background: white;
                        border-radius: 16px;
                        padding: 48px;
                        box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
                        max-width: 400px;
                        width: 100%;
                    }
                    .logo {
                        text-align: center;
                        margin-bottom: 32px;
                    }
                    .logo h1 {
                        font-size: 28px;
                        color: #1a1a2e;
                        margin-bottom: 8px;
                    }
                    .logo p {
                        color: #666;
                        font-size: 14px;
                    }
                    .login-options {
                        display: flex;
                        flex-direction: column;
                        gap: 16px;
                    }
                    .login-btn {
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        gap: 12px;
                        padding: 14px 24px;
                        border: 2px solid #e5e7eb;
                        border-radius: 8px;
                        background: white;
                        font-size: 16px;
                        font-weight: 500;
                        color: #333;
                        cursor: pointer;
                        transition: all 0.2s;
                        text-decoration: none;
                    }
                    .login-btn:hover {
                        border-color: #667eea;
                        background: #f8f9ff;
                        transform: translateY(-2px);
                        box-shadow: 0 4px 12px rgba(102, 126, 234, 0.2);
                    }
                    .login-btn.github:hover { border-color: #333; }
                    .login-btn.gitlab:hover { border-color: #fc6d26; }
                    .login-btn.gitee:hover { border-color: #c71d23; }
                    .icon {
                        width: 24px;
                        height: 24px;
                    }
                    .divider {
                        text-align: center;
                        color: #999;
                        font-size: 12px;
                        margin: 16px 0;
                        position: relative;
                    }
                    .divider::before, .divider::after {
                        content: '';
                        position: absolute;
                        top: 50%;
                        width: 40%;
                        height: 1px;
                        background: #e5e7eb;
                    }
                    .divider::before { left: 0; }
                    .divider::after { right: 0; }
                    "#
                }
            }
            body {
                div class="login-container" {
                    div class="logo" {
                        h1 { "🔍 代码审查平台" }
                        p { "协作式代码质量保障工具" }
                    }
                    div class="login-options" {
                        a href="/auth/github" class="login-btn github" {
                            svg class="icon" viewBox="0 0 24 24" fill="currentColor" {
                                path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z";
                            }
                            span { "使用 GitHub 登录" }
                        }
                        a href="/auth/gitlab" class="login-btn gitlab" {
                            svg class="icon" viewBox="0 0 24 24" fill="currentColor" {
                                path d="M23.955 13.587l-1.342-4.135-2.664-8.189c-.135-.423-.73-.423-.867 0L16.888 9.45H7.112L4.919 1.263c-.138-.423-.732-.423-.867 0L1.388 9.45.045 13.587c-.129.391.098.801.491.801h3.068l1.539 4.728c.07.213.274.357.508.357h12.698c.233 0 .438-.144.508-.357l1.539-4.728h3.068c.392 0 .62-.41.491-.801z";
                            }
                            span { "使用 GitLab 登录" }
                        }
                        a href="/auth/gitee" class="login-btn gitee" {
                            svg class="icon" viewBox="0 0 24 24" fill="currentColor" {
                                path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm6.3 10.71l-1.8 4.77c-.11.29-.39.49-.71.49h-.02c-.17 0-.33-.05-.47-.14L12 14.77l-3.3 3.06c-.34.31-.87.28-1.18-.06-.31-.34-.28-.87.06-1.18l3.62-3.35-4.52-1.1c-.37-.09-.62-.43-.58-.81.04-.38.38-.66.76-.66h.02l5.12.66 1.66-4.75c.1-.29.37-.49.69-.49.53 0 1.01.48.89 1l-1.65 4.75 4.52 1.1c.37.09.62.43.58.81-.05.39-.39.67-.77.67h-.02l-1.27-.3z";
                            }
                            span { "使用 Gitee 登录" }
                        }
                    }
                }
            }
        }
    };
    HttpResponse::Ok().content_type("text/html; charset=utf-8").body(html.into_string())
}

pub async fn oauth_login(
    path: web::Path<String>,
    session: Session,
    auth_service: web::Data<AuthService>,
    settings: web::Data<Settings>,
) -> AppResult<impl Responder> {
    let provider = path.into_inner();
    
    if !["github", "gitlab", "gitee"].contains(&provider.as_str()) {
        return Err(AppError::Validation(format!(
            "不支持的OAuth提供商: {}",
            provider
        )));
    }

    let state = Uuid::new_v4().to_string();
    session.insert(STATE_KEY, &state)?;

    let auth_url = auth_service.get_auth_url(&provider, &state)?;

    Ok(HttpResponse::Found()
        .append_header(("Location", auth_url))
        .finish())
}

pub async fn oauth_callback(
    path: web::Path<String>,
    query: web::Query<OAuthCallbackQuery>,
    session: Session,
    auth_service: web::Data<AuthService>,
    settings: web::Data<Settings>,
) -> AppResult<impl Responder> {
    let provider = path.into_inner();

    let stored_state: Option<String> = session.get(STATE_KEY)?;
    session.remove(STATE_KEY);

    if stored_state.as_deref() != Some(&query.state) {
        return Err(AppError::Authentication("OAuth状态验证失败".to_string()));
    }

    let (user_info, session_id) = auth_service.oauth_login(&provider, &query.code).await?;

    session.insert(SESSION_ID_KEY, &session_id)?;

    let redirect_url = settings.base_url().to_string();
    
    Ok(HttpResponse::Found()
        .append_header(("Location", redirect_url))
        .finish())
}

pub async fn logout(session: Session, auth_service: web::Data<AuthService>) -> AppResult<impl Responder> {
    if let Some(session_id) = session.get::<String>(SESSION_ID_KEY)? {
        let _ = auth_service.logout(&session_id).await;
    }
    
    session.remove(SESSION_ID_KEY);
    session.purge();

    Ok(HttpResponse::Found()
        .append_header(("Location", "/login"))
        .finish())
}

pub async fn get_current_user(session: Session, auth_service: web::Data<AuthService>) -> AppResult<impl Responder> {
    let session_id = session
        .get::<String>(SESSION_ID_KEY)?
        .ok_or_else(|| AppError::Authentication("未登录".to_string()))?;

    let user = auth_service.get_current_user(&session_id).await?;

    Ok(HttpResponse::Ok().json(ApiResponse::success(user)))
}

pub async fn get_auth_user_from_session(session: &Session, auth_service: &AuthService) -> AppResult<Option<AuthUser>> {
    let session_id = match session.get::<String>(SESSION_ID_KEY)? {
        Some(id) => id,
        None => return Ok(None),
    };

    auth_service.validate_session(&session_id).await
}

pub async fn auth_middleware(session: &Session, auth_service: &AuthService) -> AppResult<AuthUser> {
    get_auth_user_from_session(session, auth_service).await?
        .ok_or_else(|| AppError::Authentication("请先登录".to_string()))
}
