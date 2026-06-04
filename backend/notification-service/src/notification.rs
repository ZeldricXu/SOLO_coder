use chrono::Utc;
use common::error::AppResult;
use models::{CreateNotificationRequest, Notification};
use shared::NotificationType;
use sqlx::PgPool;
use tracing::{debug, info};
use uuid::Uuid;

pub struct NotificationService {
    pg_pool: PgPool,
}

impl NotificationService {
    pub fn new(pg_pool: PgPool) -> Self {
        Self { pg_pool }
    }

    pub async fn create(&self, req: CreateNotificationRequest) -> AppResult<Uuid> {
        let id = Uuid::new_v4();

        sqlx::query(
            "
            INSERT INTO notifications (id, user_id, type, title, content, data)
            VALUES ($1, $2, $3, $4, $5, $6)
            "
        )
        .bind(id)
        .bind(req.user_id)
        .bind(req.type_)
        .bind(req.title)
        .bind(req.content)
        .bind(req.data)
        .execute(&self.pg_pool)
        .await?;

        debug!(notification_id = %id, user_id = %req.user_id, "Notification created");
        Ok(id)
    }

    pub async fn create_many(&self, requests: Vec<CreateNotificationRequest>) -> AppResult<Vec<Uuid>> {
        let mut ids = Vec::with_capacity(requests.len());
        let mut tx = self.pg_pool.begin().await?;

        for req in requests {
            let id = Uuid::new_v4();
            sqlx::query(
                "
                INSERT INTO notifications (id, user_id, type, title, content, data)
                VALUES ($1, $2, $3, $4, $5, $6)
                "
            )
            .bind(id)
            .bind(req.user_id)
            .bind(req.type_)
            .bind(req.title)
            .bind(req.content)
            .bind(req.data)
            .execute(&mut *tx)
            .await?;
            ids.push(id);
        }

        tx.commit().await?;
        Ok(ids)
    }

    pub async fn mark_as_read(&self, user_id: Uuid, notification_id: Uuid) -> AppResult<()> {
        sqlx::query(
            "
            UPDATE notifications
            SET is_read = true, read_at = $1
            WHERE id = $2 AND user_id = $3
            "
        )
        .bind(Utc::now())
        .bind(notification_id)
        .bind(user_id)
        .execute(&self.pg_pool)
        .await?;

        Ok(())
    }

    pub async fn mark_all_as_read(&self, user_id: Uuid) -> AppResult<u64> {
        let result = sqlx::query(
            "
            UPDATE notifications
            SET is_read = true, read_at = $1
            WHERE user_id = $2 AND NOT is_read
            "
        )
        .bind(Utc::now())
        .bind(user_id)
        .execute(&self.pg_pool)
        .await?;

        Ok(result.rows_affected())
    }

    pub async fn get_user_notifications(
        &self,
        user_id: Uuid,
        unread_only: bool,
        limit: i64,
        offset: i64,
    ) -> AppResult<Vec<Notification>> {
        let notifications = if unread_only {
            sqlx::query_as::<_, Notification>(
                "
                SELECT id, user_id, type as type_, title, content,
                       data, is_read, read_at, created_at
                FROM notifications
                WHERE user_id = $1 AND NOT is_read
                ORDER BY created_at DESC
                LIMIT $2 OFFSET $3
                "
            )
            .bind(user_id)
            .bind(limit)
            .bind(offset)
            .fetch_all(&self.pg_pool)
            .await?
        } else {
            sqlx::query_as::<_, Notification>(
                "
                SELECT id, user_id, type as type_, title, content,
                       data, is_read, read_at, created_at
                FROM notifications
                WHERE user_id = $1
                ORDER BY created_at DESC
                LIMIT $2 OFFSET $3
                "
            )
            .bind(user_id)
            .bind(limit)
            .bind(offset)
            .fetch_all(&self.pg_pool)
            .await?
        };

        Ok(notifications)
    }

    pub async fn get_unread_count(&self, user_id: Uuid) -> AppResult<i64> {
        let count: Option<i64> = sqlx::query_scalar(
            "SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND NOT is_read"
        )
        .bind(user_id)
        .fetch_one(&self.pg_pool)
        .await?;

        Ok(count.unwrap_or(0))
    }

    pub async fn notify_auction_started(&self, auction_id: Uuid, title: &str, user_ids: Vec<Uuid>) -> AppResult<Vec<Uuid>> {
        let requests: Vec<CreateNotificationRequest> = user_ids
            .into_iter()
            .map(|user_id| CreateNotificationRequest {
                user_id,
                type_: NotificationType::AuctionStarted,
                title: "拍卖开始了".into(),
                content: format!("您关注的商品「{}」已开始拍卖，快来参与吧！", title),
                data: Some(serde_json::json!({ "auction_id": auction_id })),
            })
            .collect();

        self.create_many(requests).await
    }

    pub async fn notify_won_auction(&self, user_id: Uuid, auction_id: Uuid, title: &str, price: rust_decimal::Decimal) -> AppResult<Uuid> {
        self.create(CreateNotificationRequest {
            user_id,
            type_: NotificationType::WonAuction,
            title: "恭喜！您拍得商品".into(),
            content: format!("您以 ¥{:.2} 成功拍得「{}」，请尽快完成支付。", price, title),
            data: Some(serde_json::json!({ "auction_id": auction_id, "price": price })),
        })
        .await
    }

    pub async fn notify_lost_auction(&self, user_id: Uuid, auction_id: Uuid, title: &str) -> AppResult<Uuid> {
        self.create(CreateNotificationRequest {
            user_id,
            type_: NotificationType::LostAuction,
            title: "拍卖结束".into(),
            content: format!("很遗憾，「{}」已被他人拍走。", title),
            data: Some(serde_json::json!({ "auction_id": auction_id })),
        })
        .await
    }

    pub async fn notify_payment_received(&self, user_id: Uuid, order_id: Uuid, amount: rust_decimal::Decimal) -> AppResult<Uuid> {
        self.create(CreateNotificationRequest {
            user_id,
            type_: NotificationType::PaymentReceived,
            title: "支付成功".into(),
            content: format!("您已成功支付 ¥{:.2}，等待卖家发货。", amount),
            data: Some(serde_json::json!({ "order_id": order_id, "amount": amount })),
        })
        .await
    }

    pub async fn notify_order_shipped(
        &self,
        user_id: Uuid,
        order_id: Uuid,
        tracking_number: &str,
        tracking_company: &str,
    ) -> AppResult<Uuid> {
        self.create(CreateNotificationRequest {
            user_id,
            type_: NotificationType::OrderShipped,
            title: "商品已发货".into(),
            content: format!(
                "您的订单已发货，{}单号：{}",
                tracking_company, tracking_number
            ),
            data: Some(serde_json::json!({
                "order_id": order_id,
                "tracking_number": tracking_number,
                "tracking_company": tracking_company
            })),
        })
        .await
    }

    pub async fn notify_refund_processed(&self, user_id: Uuid, amount: rust_decimal::Decimal, reason: &str) -> AppResult<Uuid> {
        self.create(CreateNotificationRequest {
            user_id,
            type_: NotificationType::RefundProcessed,
            title: "退款已到账".into(),
            content: format!("¥{:.2} 已退回您的账户，原因：{}", amount, reason),
            data: Some(serde_json::json!({ "amount": amount })),
        })
        .await
    }

    pub async fn notify_risk_alert(&self, admin_ids: Vec<Uuid>, description: &str, severity: &str) -> AppResult<Vec<Uuid>> {
        let requests: Vec<CreateNotificationRequest> = admin_ids
            .into_iter()
            .map(|user_id| CreateNotificationRequest {
                user_id,
                type_: NotificationType::RiskAlert,
                title: format!("[{}] 风控警报", severity),
                content: description.into(),
                data: Some(serde_json::json!({ "severity": severity })),
            })
            .collect();

        self.create_many(requests).await
    }
}

impl Clone for NotificationService {
    fn clone(&self) -> Self {
        Self {
            pg_pool: self.pg_pool.clone(),
        }
    }
}
