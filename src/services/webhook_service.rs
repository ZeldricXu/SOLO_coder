use std::time::Duration;

use hmac::{Hmac, Mac};
use sha2::{Sha256, Digest};
use uuid::Uuid;

use crate::models::{
    CreateMergeRequestRequest, WebhookLog, MergeRequest, User,
};
use crate::providers::RedisClient;
use crate::repositories::{MergeRequestRepository, RepoRepository, UserRepository};
use crate::utils::{AppError, AppResult, PaginatedResult, verify_hmac_signature};

use super::comment_service::NotificationService;

#[derive(Clone)]
pub struct WebhookService<N: NotificationService> {
    repo_repo: RepoRepository,
    mr_repo: MergeRequestRepository,
    user_repo: UserRepository,
    redis_client: RedisClient,
    notification_service: N,
}

impl<N: NotificationService> WebhookService<N> {
    pub fn new(
        repo_repo: RepoRepository,
        mr_repo: MergeRequestRepository,
        user_repo: UserRepository,
        redis_client: RedisClient,
        notification_service: N,
    ) -> Self {
        Self {
            repo_repo,
            mr_repo,
            user_repo,
            redis_client,
            notification_service,
        }
    }

    pub async fn handle_webhook(
        &self,
        provider: &str,
        repo_id: Option<Uuid>,
        event_type: &str,
        delivery_id: Option<&str>,
        signature_header: Option<&str>,
        payload: &[u8],
    ) -> AppResult<()> {
        let payload_json: serde_json::Value = serde_json::from_slice(payload)
            .map_err(|e| AppError::Parse(format!("Invalid webhook payload: {}", e)))?;

        if let Some(delivery_id) = delivery_id {
            if !self.deduplicate(delivery_id).await? {
                self.log_webhook(
                    provider,
                    repo_id,
                    event_type,
                    Some(delivery_id),
                    &payload_json,
                    200,
                    Some("Duplicate webhook delivery"),
                ).await?;
                return Ok(());
            }
        }

        if let (Some(signature), Some(repo_id)) = (signature_header, repo_id) {
            let repo = self.repo_repo.get_by_id(repo_id).await?
                .ok_or_else(|| AppError::NotFound("Repository not found".to_string()))?;
            
            if !self.verify_signature(provider, &repo.webhook_secret, payload, signature) {
                self.log_webhook(
                    provider,
                    Some(repo_id),
                    event_type,
                    delivery_id,
                    &payload_json,
                    401,
                    Some("Invalid webhook signature"),
                ).await?;
                return Err(AppError::WebhookSignature("Invalid signature".to_string()));
            }
        }

        let result = match event_type {
            "pull_request" | "merge_request" => {
                self.handle_merge_request_event(provider, repo_id, &payload_json).await
            }
            "push" => {
                self.handle_push_event(provider, repo_id, &payload_json).await
            }
            "issue_comment" | "pull_request_review" | "pull_request_review_comment" | "note" => {
                self.handle_comment_event(provider, repo_id, &payload_json).await
            }
            _ => {
                self.log_webhook(
                    provider,
                    repo_id,
                    event_type,
                    delivery_id,
                    &payload_json,
                    200,
                    Some("Unhandled event type"),
                ).await?;
                return Ok(());
            }
        };

        match &result {
            Ok(_) => {
                self.log_webhook(
                    provider,
                    repo_id,
                    event_type,
                    delivery_id,
                    &payload_json,
                    200,
                    None,
                ).await?;
            }
            Err(e) => {
                self.log_webhook(
                    provider,
                    repo_id,
                    event_type,
                    delivery_id,
                    &payload_json,
                    500,
                    Some(&e.to_string()),
                ).await?;
            }
        }

        result
    }

    pub fn verify_signature(
        &self,
        provider: &str,
        secret: &str,
        payload: &[u8],
        signature: &str,
    ) -> bool {
        match provider {
            "github" => {
                verify_hmac_signature(secret, payload, signature)
            }
            "gitlab" => {
                signature == secret
            }
            "gitee" => {
                let timestamp = signature.split(',').next().unwrap_or("");
                let sign = signature.split(',').nth(1).unwrap_or("");
                
                let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes())
                    .expect("HMAC can take key of any size");
                mac.update(format!("{}\n{}", timestamp, secret).as_bytes());
                let expected = hex::encode(mac.finalize().into_bytes());
                
                expected == sign
            }
            _ => false,
        }
    }

    pub async fn deduplicate(&self, delivery_id: &str) -> AppResult<bool> {
        let is_new = self.redis_client
            .check_and_set_delivery_id(delivery_id, Duration::from_secs(86400))
            .await?;
        Ok(is_new)
    }

    async fn handle_merge_request_event(
        &self,
        provider: &str,
        repo_id: Option<Uuid>,
        payload: &serde_json::Value,
    ) -> AppResult<()> {
        let repo_id = repo_id.ok_or_else(|| AppError::BadRequest("Missing repo_id".to_string()))?;

        let action = payload.get("action").and_then(|v| v.as_str()).unwrap_or("");
        let mr_data = payload.get("pull_request")
            .or_else(|| payload.get("merge_request"))
            .ok_or_else(|| AppError::Parse("Missing merge request data".to_string()))?;

        let provider_id = mr_data.get("id")
            .and_then(|v| v.as_i64())
            .map(|v| v.to_string())
            .ok_or_else(|| AppError::Parse("Missing merge request id".to_string()))?;

        let title = mr_data.get("title")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let description = mr_data.get("body")
            .or_else(|| mr_data.get("description"))
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());

        let source_branch = mr_data.get("head")
            .and_then(|v| v.get("ref"))
            .or_else(|| mr_data.get("source_branch"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let target_branch = mr_data.get("base")
            .and_then(|v| v.get("ref"))
            .or_else(|| mr_data.get("target_branch"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let author_provider_id = mr_data.get("user")
            .and_then(|v| v.get("id"))
            .and_then(|v| v.as_i64())
            .map(|v| v.to_string())
            .ok_or_else(|| AppError::Parse("Missing author id".to_string()))?;

        let author = self.get_or_create_user(provider, &author_provider_id, mr_data.get("user")).await?;

        let state = mr_data.get("state")
            .and_then(|v| v.as_str())
            .unwrap_or("open");

        let status = match (action, state) {
            ("closed", _) => if mr_data.get("merged").and_then(|v| v.as_bool()).unwrap_or(false) {
                "merged"
            } else {
                "closed"
            },
            ("merged", _) | (_, "merged") => "merged",
            (_, "closed") => "closed",
            _ => "open",
        };

        let req = CreateMergeRequestRequest {
            provider: provider.to_string(),
            provider_id: provider_id.clone(),
            title,
            description,
            source_branch,
            target_branch,
            author_provider_id: author_provider_id.clone(),
        };

        let _mr = self.mr_repo.create(
            repo_id,
            &req.provider,
            &req.provider_id,
            &req.title,
            req.description.as_deref(),
            &req.source_branch,
            &req.target_branch,
            author.id,
            status,
        ).await?;

        Ok(())
    }

    async fn handle_push_event(
        &self,
        _provider: &str,
        _repo_id: Option<Uuid>,
        _payload: &serde_json::Value,
    ) -> AppResult<()> {
        Ok(())
    }

    async fn handle_comment_event(
        &self,
        _provider: &str,
        _repo_id: Option<Uuid>,
        _payload: &serde_json::Value,
    ) -> AppResult<()> {
        Ok(())
    }

    async fn get_or_create_user(
        &self,
        provider: &str,
        provider_id: &str,
        user_data: Option<&serde_json::Value>,
    ) -> AppResult<User> {
        if let Some(user) = self.user_repo.get_by_provider_id(provider, provider_id).await? {
            return Ok(user);
        }

        let username = user_data
            .and_then(|u| u.get("login").or_else(|| u.get("username")))
            .and_then(|v| v.as_str())
            .unwrap_or(provider_id)
            .to_string();

        let email = user_data
            .and_then(|u| u.get("email"))
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("{}@{}", username, provider))
            .to_string();

        let avatar_url = user_data
            .and_then(|u| u.get("avatar_url"))
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());

        let user = self.user_repo.create(&username, &email, avatar_url.as_deref()).await?;

        Ok(user)
    }

    pub async fn log_webhook(
        &self,
        provider: &str,
        repo_id: Option<Uuid>,
        event_type: &str,
        delivery_id: Option<&str>,
        payload: &serde_json::Value,
        status: i32,
        error_message: Option<&str>,
    ) -> AppResult<WebhookLog> {
        let log = self.repo_repo.create_webhook_log(
            provider,
            repo_id,
            event_type,
            delivery_id,
            payload,
            status,
            error_message,
        ).await?;
        Ok(log)
    }

    pub async fn get_webhook_logs(
        &self,
        repo_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<PaginatedResult<WebhookLog>> {
        let page = if page < 1 { 1 } else { page };
        let per_page = if per_page < 1 || per_page > 100 { 20 } else { per_page };

        let (logs, total) = self.repo_repo.list_webhook_logs(repo_id, page, per_page).await?;

        Ok(PaginatedResult::new(logs, page, per_page, total))
    }
}
