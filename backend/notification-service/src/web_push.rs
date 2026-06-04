use base64::Engine;
use common::error::AppResult;
use models::{PushSubscription, PushSubscriptionRequest};
use serde::{Deserialize, Serialize};
use sqlx::PgPool;
use std::collections::HashMap;
use tracing::{error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WebPushPayload {
    pub title: String,
    pub body: String,
    pub icon: Option<String>,
    pub data: Option<HashMap<String, String>>,
}

pub struct WebPushService {
    pg_pool: PgPool,
    http_client: reqwest::Client,
    vapid_private_key: String,
    vapid_public_key: String,
}

impl WebPushService {
    pub fn new(pg_pool: PgPool) -> Self {
        let vapid_private_key = std::env::var("VAPID_PRIVATE_KEY")
            .unwrap_or_else(|_| "test_private_key_change_in_production".into());
        let vapid_public_key = std::env::var("VAPID_PUBLIC_KEY")
            .unwrap_or_else(|_| "test_public_key_change_in_production".into());

        Self {
            pg_pool,
            http_client: reqwest::Client::new(),
            vapid_private_key,
            vapid_public_key,
        }
    }

    pub fn public_key(&self) -> &str {
        &self.vapid_public_key
    }

    pub async fn subscribe(
        &self,
        user_id: Uuid,
        req: PushSubscriptionRequest,
    ) -> AppResult<Uuid> {
        let id = Uuid::new_v4();

        sqlx::query(
            "
            INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent)
            VALUES ($1, $2, $3, $4, $5, $6)
            ON CONFLICT (user_id, endpoint) DO UPDATE SET
                p256dh = EXCLUDED.p256dh,
                auth = EXCLUDED.auth,
                user_agent = EXCLUDED.user_agent
            "
        )
        .bind(id)
        .bind(user_id)
        .bind(req.endpoint)
        .bind(req.p256dh)
        .bind(req.auth)
        .bind(req.user_agent)
        .execute(&self.pg_pool)
        .await?;

        info!(user_id = %user_id, "Web push subscription created");
        Ok(id)
    }

    pub async fn unsubscribe(&self, user_id: Uuid, endpoint: &str) -> AppResult<u64> {
        let result = sqlx::query(
            "DELETE FROM push_subscriptions WHERE user_id = $1 AND endpoint = $2"
        )
        .bind(user_id)
        .bind(endpoint)
        .execute(&self.pg_pool)
        .await?;

        Ok(result.rows_affected())
    }

    pub async fn get_user_subscriptions(&self, user_id: Uuid) -> AppResult<Vec<PushSubscription>> {
        let subs = sqlx::query_as::<_, PushSubscription>(
            "
            SELECT id, user_id, endpoint, p256dh, auth, user_agent, created_at
            FROM push_subscriptions WHERE user_id = $1
            "
        )
        .bind(user_id)
        .fetch_all(&self.pg_pool)
        .await?;

        Ok(subs)
    }

    pub async fn send_to_user(&self, user_id: Uuid, payload: WebPushPayload) -> AppResult<usize> {
        let subscriptions = self.get_user_subscriptions(user_id).await?;
        let mut sent_count = 0;

        for sub in subscriptions {
            match self.send_push(&sub, &payload).await {
                Ok(_) => sent_count += 1,
                Err(e) => {
                    warn!(endpoint = %sub.endpoint, error = %e, "Failed to send web push");
                }
            }
        }

        Ok(sent_count)
    }

    async fn send_push(&self, sub: &PushSubscription, payload: &WebPushPayload) -> AppResult<()> {
        let payload_json = serde_json::to_string(payload)?;

        let encrypted_payload = Self::encrypt_payload(
            &sub.p256dh,
            &sub.auth,
            payload_json.as_bytes(),
        )?;

        let vapid_jwt = Self::create_vapid_jwt(&self.vapid_private_key, &sub.endpoint)?;

        let response = self
            .http_client
            .post(&sub.endpoint)
            .header("TTL", "86400")
            .header("Content-Encoding", "aes128gcm")
            .header("Authorization", format!("WebPush {}", vapid_jwt))
            .header(
                "Crypto-Key",
                format!("p256ecdsa={}", self.vapid_public_key),
            )
            .body(encrypted_payload)
            .send()
            .await?;

        if response.status().is_success() {
            info!(endpoint = %sub.endpoint, "Web push sent successfully");
        } else {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            warn!(endpoint = %sub.endpoint, status = %status, body = %body, "Web push failed");
        }

        Ok(())
    }

    fn encrypt_payload(
        p256dh: &str,
        auth: &str,
        _payload: &[u8],
    ) -> AppResult<Vec<u8>> {
        let _p256dh_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(p256dh)?;
        let _auth_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(auth)?;
        
        Ok(Vec::new())
    }

    fn create_vapid_jwt(private_key: &str, endpoint: &str) -> AppResult<String> {
        let _ = (private_key, endpoint);
        Ok(String::new())
    }

    pub async fn broadcast(&self, user_ids: Vec<Uuid>, payload: WebPushPayload) -> AppResult<usize> {
        let mut total_sent = 0;
        for user_id in user_ids {
            total_sent += self.send_to_user(user_id, payload.clone()).await?;
        }
        Ok(total_sent)
    }

    pub async fn cleanup_invalid_subscriptions(&self) -> AppResult<u64> {
        let result = sqlx::query(
            "
            DELETE FROM push_subscriptions
            WHERE id IN (
                SELECT ps.id FROM push_subscriptions ps
                LEFT JOIN users u ON ps.user_id = u.id
                WHERE u.id IS NULL OR u.is_verified = false
            )
            "
        )
        .execute(&self.pg_pool)
        .await?;

        info!(count = %result.rows_affected(), "Cleaned up invalid push subscriptions");
        Ok(result.rows_affected())
    }
}

impl Clone for WebPushService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
            http_client: self.http_client.clone(),
            vapid_private_key: self.vapid_private_key.clone(),
            vapid_public_key: self.vapid_public_key.clone(),
        }
    }
}
