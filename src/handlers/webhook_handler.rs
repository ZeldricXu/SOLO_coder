use actix_web::{web, HttpResponse, Responder};
use uuid::Uuid;

use crate::services::{NotificationService, WebhookService};
use crate::utils::{AppError, AppResult};

#[derive(Debug, Clone)]
struct WebhookHeaders {
    event_type: String,
    delivery_id: Option<String>,
    signature: Option<String>,
}

fn extract_webhook_headers(
    provider: &str,
    headers: &actix_web::http::header::HeaderMap,
) -> AppResult<WebhookHeaders> {
    let (event_header, delivery_header, signature_header) = match provider {
        "github" => (
            "x-github-event",
            Some("x-github-delivery"),
            Some("x-hub-signature-256"),
        ),
        "gitlab" => (
            "x-gitlab-event",
            Some("x-gitlab-delivery"),
            Some("x-gitlab-token"),
        ),
        "gitee" => (
            "x-gitee-event",
            Some("x-gitee-delivery"),
            Some("x-gitee-token"),
        ),
        _ => {
            return Err(AppError::Validation(format!(
                "不支持的Webhook提供商: {}",
                provider
            )))
        }
    };

    let event_type = headers
        .get(event_header)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string())
        .ok_or_else(|| AppError::BadRequest(format!("缺少 {} header", event_header)))?;

    let delivery_id = delivery_header
        .and_then(|h| headers.get(h))
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());

    let signature = signature_header
        .and_then(|h| headers.get(h))
        .and_then(|v| v.to_str().ok())
        .map(|s| s.to_string());

    if provider == "gitee" {
        if let Some(timestamp) = headers.get("x-gitee-timestamp").and_then(|v| v.to_str().ok()) {
            if let Some(sign) = &signature {
                return Ok(WebhookHeaders {
                    event_type,
                    delivery_id,
                    signature: Some(format!("{},{}", timestamp, sign)),
                });
            }
        }
    }

    Ok(WebhookHeaders {
        event_type,
        delivery_id,
        signature,
    })
}

fn normalize_event_type(provider: &str, event_type: &str) -> String {
    match (provider, event_type) {
        ("github", "pull_request") => "pull_request".to_string(),
        ("github", "push") => "push".to_string(),
        ("github", "issue_comment") => "issue_comment".to_string(),
        ("github", "pull_request_review") => "pull_request_review".to_string(),
        ("github", "pull_request_review_comment") => "pull_request_review_comment".to_string(),
        ("gitlab", "Merge Request Hook") => "merge_request".to_string(),
        ("gitlab", "Push Hook") => "push".to_string(),
        ("gitlab", "Note Hook") => "note".to_string(),
        ("gitee", "merge_request") => "merge_request".to_string(),
        ("gitee", "push") => "push".to_string(),
        ("gitee", "note") => "note".to_string(),
        ("gitee", "Merge Request Hook") => "merge_request".to_string(),
        _ => event_type.to_string(),
    }
}

pub async fn handle_webhook<N: NotificationService + 'static>(
    path: web::Path<(String, Uuid)>,
    req: actix_web::HttpRequest,
    body: web::Bytes,
    webhook_service: web::Data<WebhookService<N>>,
) -> AppResult<impl Responder> {
    let (provider, repo_id) = path.into_inner();

    if !["github", "gitlab", "gitee"].contains(&provider.as_str()) {
        return Err(AppError::Validation(format!(
            "不支持的Webhook提供商: {}",
            provider
        )));
    }

    let headers = extract_webhook_headers(&provider, req.headers())?;
    let normalized_event = normalize_event_type(&provider, &headers.event_type);

    tracing::debug!(
        "Received webhook: provider={}, repo_id={}, event={}, delivery={:?}",
        provider,
        repo_id,
        normalized_event,
        headers.delivery_id
    );

    let payload = body.as_ref();

    let result = webhook_service
        .handle_webhook(
            &provider,
            Some(repo_id),
            &normalized_event,
            headers.delivery_id.as_deref(),
            headers.signature.as_deref(),
            payload,
        )
        .await;

    match result {
        Ok(_) => {
            tracing::info!(
                "Webhook processed successfully: provider={}, repo_id={}, event={}",
                provider,
                repo_id,
                normalized_event
            );
            Ok(HttpResponse::Ok().json(serde_json::json!({
                "code": 200,
                "message": "Webhook received and processed successfully",
                "data": {
                    "provider": provider,
                    "event": normalized_event,
                    "repo_id": repo_id
                }
            })))
        }
        Err(AppError::WebhookSignature(msg)) => {
            tracing::warn!(
                "Webhook signature verification failed: provider={}, repo_id={}, error={}",
                provider,
                repo_id,
                msg
            );
            Ok(HttpResponse::Unauthorized().json(serde_json::json!({
                "code": 401,
                "message": "Invalid webhook signature",
                "error": msg
            })))
        }
        Err(e) => {
            tracing::error!(
                "Webhook processing failed: provider={}, repo_id={}, event={}, error={}",
                provider,
                repo_id,
                normalized_event,
                e
            );
            Ok(HttpResponse::Accepted().json(serde_json::json!({
                "code": 202,
                "message": "Webhook received but processing failed, will retry",
                "data": {
                    "provider": provider,
                    "event": normalized_event,
                    "repo_id": repo_id
                }
            })))
        }
    }
}

pub async fn handle_webhook_public<N: NotificationService + 'static>(
    path: web::Path<String>,
    req: actix_web::HttpRequest,
    body: web::Bytes,
    webhook_service: web::Data<WebhookService<N>>,
) -> AppResult<impl Responder> {
    let provider = path.into_inner();

    if !["github", "gitlab", "gitee"].contains(&provider.as_str()) {
        return Err(AppError::Validation(format!(
            "不支持的Webhook提供商: {}",
            provider
        )));
    }

    let headers = extract_webhook_headers(&provider, req.headers())?;
    let normalized_event = normalize_event_type(&provider, &headers.event_type);

    tracing::debug!(
        "Received public webhook: provider={}, event={}, delivery={:?}",
        provider,
        normalized_event,
        headers.delivery_id
    );

    let payload = body.as_ref();

    let result = webhook_service
        .handle_webhook(
            &provider,
            None,
            &normalized_event,
            headers.delivery_id.as_deref(),
            headers.signature.as_deref(),
            payload,
        )
        .await;

    match result {
        Ok(_) => {
            tracing::info!(
                "Public webhook processed successfully: provider={}, event={}",
                provider,
                normalized_event
            );
            Ok(HttpResponse::Ok().json(serde_json::json!({
                "code": 200,
                "message": "Webhook received and processed successfully",
                "data": {
                    "provider": provider,
                    "event": normalized_event
                }
            })))
        }
        Err(AppError::WebhookSignature(msg)) => {
            tracing::warn!(
                "Public webhook signature verification failed: provider={}, error={}",
                provider,
                msg
            );
            Ok(HttpResponse::Unauthorized().json(serde_json::json!({
                "code": 401,
                "message": "Invalid webhook signature",
                "error": msg
            })))
        }
        Err(e) => {
            tracing::error!(
                "Public webhook processing failed: provider={}, event={}, error={}",
                provider,
                normalized_event,
                e
            );
            Ok(HttpResponse::Accepted().json(serde_json::json!({
                "code": 202,
                "message": "Webhook received but processing failed, will retry",
                "data": {
                    "provider": provider,
                    "event": normalized_event
                }
            })))
        }
    }
}

pub fn configure_webhook_routes<N: NotificationService + 'static>(cfg: &mut web::ServiceConfig) {
    cfg.service(
        web::resource("/webhook/{provider}/{repo_id}")
            .route(web::post().to(handle_webhook::<N>)),
    )
    .service(
        web::resource("/webhook/{provider}")
            .route(web::post().to(handle_webhook_public::<N>)),
    );
}
