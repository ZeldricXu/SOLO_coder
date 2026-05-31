use crate::config::NotifierConfig;
use crate::error::SystemError;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum NotificationStatus {
    Pending,
    Sent,
    Delivered,
    Failed,
    Retrying,
    Expired,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum NotificationChannel {
    Webhook,
    Email,
    Sms,
    InApp,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Notification {
    pub id: Uuid,
    pub channel: NotificationChannel,
    pub recipient: String,
    pub subject: String,
    pub content: String,
    pub status: NotificationStatus,
    pub priority: NotificationPriority,
    pub created_at: DateTime<Utc>,
    pub sent_at: Option<DateTime<Utc>>,
    pub delivered_at: Option<DateTime<Utc>>,
    pub retry_count: u32,
    pub max_retries: u32,
    pub error_message: Option<String>,
    pub metadata: HashMap<String, String>,
    pub delivery_token: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NotificationPriority {
    Low,
    Normal,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeliveryStatus {
    pub notification_id: Uuid,
    pub status: NotificationStatus,
    pub timestamp: DateTime<Utc>,
    pub details: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotifierStats {
    pub total_notifications: usize,
    pub sent: usize,
    pub delivered: usize,
    pub failed: usize,
    pub pending: usize,
    pub retry_count: u64,
    pub success_rate: f64,
}

#[async_trait::async_trait]
pub trait NotificationSender: Send + Sync {
    async fn send(&self, notification: &Notification) -> Result<String, SystemError>;
    async fn check_delivery(&self, token: &str) -> Result<DeliveryStatus, SystemError>;
}

pub struct WebhookSender {
    client: reqwest::Client,
}

impl WebhookSender {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(10))
                .build()
                .unwrap(),
        }
    }
}

#[async_trait::async_trait]
impl NotificationSender for WebhookSender {
    async fn send(&self, notification: &Notification) -> Result<String, SystemError> {
        let payload = serde_json::json!({
            "notification_id": notification.id.to_string(),
            "subject": notification.subject,
            "content": notification.content,
            "metadata": notification.metadata,
            "timestamp": notification.created_at,
        });

        let response = self
            .client
            .post(&notification.recipient)
            .json(&payload)
            .send()
            .await
            .map_err(SystemError::NetworkError)?;

        if response.status().is_success() {
            Ok(Uuid::new_v4().to_string())
        } else {
            Err(SystemError::NotificationError(format!(
                "Webhook发送失败: HTTP {}",
                response.status()
            )))
        }
    }

    async fn check_delivery(&self, _token: &str) -> Result<DeliveryStatus, SystemError> {
        Ok(DeliveryStatus {
            notification_id: Uuid::new_v4(),
            status: NotificationStatus::Delivered,
            timestamp: Utc::now(),
            details: Some("Webhook已送达".to_string()),
        })
    }
}

pub struct EmailSender {
    smtp_host: String,
    smtp_port: u16,
}

impl EmailSender {
    pub fn new(smtp_host: String, smtp_port: u16) -> Self {
        Self { smtp_host, smtp_port }
    }
}

#[async_trait::async_trait]
impl NotificationSender for EmailSender {
    async fn send(&self, notification: &Notification) -> Result<String, SystemError> {
        debug!(
            "发送邮件到 {}: {} (SMTP: {}:{})",
            notification.recipient, notification.subject, self.smtp_host, self.smtp_port
        );
        Ok(Uuid::new_v4().to_string())
    }

    async fn check_delivery(&self, _token: &str) -> Result<DeliveryStatus, SystemError> {
        Ok(DeliveryStatus {
            notification_id: Uuid::new_v4(),
            status: NotificationStatus::Delivered,
            timestamp: Utc::now(),
            details: Some("邮件已送达".to_string()),
        })
    }
}

#[derive(Clone)]
pub struct Notifier {
    config: NotifierConfig,
    notifications: Arc<DashMap<Uuid, Notification>>,
    senders: Arc<DashMap<NotificationChannel, Arc<dyn NotificationSender>>>,
    notify_tx: mpsc::Sender<Uuid>,
    delivery_callbacks: Arc<RwLock<Vec<Arc<dyn Fn(DeliveryStatus) + Send + Sync>>>>,
}

impl Notifier {
    pub fn new(config: &NotifierConfig) -> Result<Self, SystemError> {
        let (notify_tx, notify_rx) = mpsc::channel(1000);

        let mut notifier = Self {
            config: config.clone(),
            notifications: Arc::new(DashMap::new()),
            senders: Arc::new(DashMap::new()),
            notify_tx,
            delivery_callbacks: Arc::new(RwLock::new(Vec::new())),
        };

        notifier.register_sender(
            NotificationChannel::Webhook,
            Arc::new(WebhookSender::new()),
        );

        if let (Some(host), Some(port)) = (config.email_smtp_host.clone(), config.email_smtp_port) {
            notifier.register_sender(
                NotificationChannel::Email,
                Arc::new(EmailSender::new(host, port)),
            );
        }

        notifier.start_worker(notify_rx);
        notifier.start_delivery_tracker();

        Ok(notifier)
    }

    pub fn register_sender(&mut self, channel: NotificationChannel, sender: Arc<dyn NotificationSender>) {
        self.senders.insert(channel, sender);
    }

    pub async fn send_notification(
        &self,
        channel: NotificationChannel,
        recipient: String,
        subject: String,
        content: String,
        priority: NotificationPriority,
        metadata: HashMap<String, String>,
    ) -> Result<Uuid, SystemError> {
        let notification = Notification {
            id: Uuid::new_v4(),
            channel,
            recipient,
            subject,
            content,
            status: NotificationStatus::Pending,
            priority,
            created_at: Utc::now(),
            sent_at: None,
            delivered_at: None,
            retry_count: 0,
            max_retries: self.config.retry_attempts,
            error_message: None,
            metadata,
            delivery_token: None,
        };

        let id = notification.id;
        self.notifications.insert(id, notification);

        self.notify_tx
            .send(id)
            .await
            .map_err(|e| SystemError::NotificationError(format!("通知入队失败: {}", e)))?;

        Ok(id)
    }

    pub async fn get_notification(&self, id: Uuid) -> Result<Notification, SystemError> {
        self.notifications
            .get(&id)
            .map(|r| r.clone())
            .ok_or_else(|| SystemError::NotFoundError(format!("通知不存在: {}", id)))
    }

    pub async fn list_notifications(
        &self,
        status: Option<NotificationStatus>,
        channel: Option<NotificationChannel>,
    ) -> Vec<Notification> {
        self.notifications
            .iter()
            .map(|n| n.clone())
            .filter(|n| status.as_ref().map_or(true, |s| n.status == *s))
            .filter(|n| channel.as_ref().map_or(true, |c| n.channel == *c))
            .collect()
    }

    fn start_worker(&self, mut rx: mpsc::Receiver<Uuid>) {
        let notifications = self.notifications.clone();
        let senders = self.senders.clone();
        let config = self.config.clone();
        let notify_tx = self.notify_tx.clone();

        tokio::spawn(async move {
            while let Some(notification_id) = rx.recv().await {
                let notifications_clone = notifications.clone();
                let senders_clone = senders.clone();
                let config_clone = config.clone();
                let notify_tx_clone = notify_tx.clone();

                tokio::spawn(async move {
                    if let Some(mut notification) = notifications_clone.get_mut(&notification_id) {
                        if notification.status != NotificationStatus::Pending
                            && notification.status != NotificationStatus::Retrying
                        {
                            return;
                        }

                        let sender = senders_clone.get(&notification.channel);
                        if let Some(sender) = sender {
                            match sender.send(&notification).await {
                                Ok(token) => {
                                    notification.status = NotificationStatus::Sent;
                                    notification.sent_at = Some(Utc::now());
                                    notification.delivery_token = Some(token);
                                    info!("通知 {} 已发送", notification_id);
                                }
                                Err(e) => {
                                    notification.retry_count += 1;
                                    notification.error_message = Some(e.to_string());

                                    if notification.retry_count < notification.max_retries {
                                        notification.status = NotificationStatus::Retrying;
                                        warn!(
                                            "通知 {} 发送失败，正在重试 ({}/{})",
                                            notification_id,
                                            notification.retry_count,
                                            notification.max_retries
                                        );

                                        let notify_tx = notify_tx_clone.clone();
                                        let delay = config_clone.retry_delay();
                                        tokio::spawn(async move {
                                            tokio::time::sleep(delay).await;
                                            let _ = notify_tx.send(notification_id).await;
                                        });
                                    } else {
                                        notification.status = NotificationStatus::Failed;
                                        error!(
                                            "通知 {} 发送失败，已达最大重试次数",
                                            notification_id
                                        );
                                    }
                                }
                            }
                        } else {
                            notification.status = NotificationStatus::Failed;
                            notification.error_message = Some(format!(
                                "未找到通道 {:?} 的发送器",
                                notification.channel
                            ));
                        }
                    }
                });
            }
        });
    }

    fn start_delivery_tracker(&self) {
        let notifications = self.notifications.clone();
        let senders = self.senders.clone();
        let callbacks = self.delivery_callbacks.clone();

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(std::time::Duration::from_secs(30)).await;

                let mut to_check: Vec<Uuid> = Vec::new();
                for notification in notifications.iter() {
                    if matches!(notification.status, NotificationStatus::Sent) {
                        if notification.delivery_token.is_some() {
                            to_check.push(notification.id);
                        }
                    }
                }

                for notification_id in to_check {
                    let notifications = notifications.clone();
                    let senders = senders.clone();
                    let callbacks = callbacks.clone();

                    tokio::spawn(async move {
                        if let Some(notification) = notifications.get(&notification_id) {
                            if let (Some(token), Some(sender)) = (
                                notification.delivery_token.clone(),
                                senders.get(&notification.channel),
                            ) {
                                match sender.check_delivery(&token).await {
                                    Ok(status) => {
                                        if status.status == NotificationStatus::Delivered {
                                            if let Some(mut n) = notifications.get_mut(&notification_id) {
                                                n.status = NotificationStatus::Delivered;
                                                n.delivered_at = Some(Utc::now());
                                            }

                                            let cbs = callbacks.read().await;
                                            for cb in cbs.iter() {
                                                cb(status.clone());
                                            }
                                        }
                                    }
                                    Err(e) => {
                                        warn!("检查投递状态失败: {}", e);
                                    }
                                }
                            }
                        }
                    });
                }
            }
        });
    }

    pub async fn register_delivery_callback<F>(&self, callback: F)
    where
        F: Fn(DeliveryStatus) + Send + Sync + 'static,
    {
        let mut callbacks = self.delivery_callbacks.write().await;
        callbacks.push(Arc::new(callback));
    }

    pub async fn get_stats(&self) -> Result<NotifierStats, SystemError> {
        let mut sent = 0;
        let mut delivered = 0;
        let mut failed = 0;
        let mut pending = 0;
        let mut retry_count = 0u64;

        for n in self.notifications.iter() {
            match n.status {
                NotificationStatus::Pending => pending += 1,
                NotificationStatus::Sent => sent += 1,
                NotificationStatus::Delivered => {
                    sent += 1;
                    delivered += 1;
                }
                NotificationStatus::Failed => failed += 1,
                NotificationStatus::Retrying => {
                    pending += 1;
                    retry_count += n.retry_count as u64;
                }
                NotificationStatus::Expired => failed += 1,
            }
        }

        let total = sent + delivered + failed;
        let success_rate = if total > 0 {
            (delivered as f64 / total as f64) * 100.0
        } else {
            100.0
        };

        Ok(NotifierStats {
            total_notifications: self.notifications.len(),
            sent,
            delivered,
            failed,
            pending,
            retry_count,
            success_rate,
        })
    }

    pub async fn resend_failed(&self) -> Result<usize, SystemError> {
        let mut count = 0;
        let mut to_resend = Vec::new();

        for n in self.notifications.iter() {
            if matches!(n.status, NotificationStatus::Failed) {
                to_resend.push(n.id);
            }
        }

        for id in to_resend {
            if let Some(mut n) = self.notifications.get_mut(&id) {
                n.status = NotificationStatus::Pending;
                n.retry_count = 0;
                n.error_message = None;
                drop(n);

                self.notify_tx.send(id).await.ok();
                count += 1;
            }
        }

        Ok(count)
    }

    pub async fn cleanup_old_notifications(&self, max_age_hours: i64) -> Result<usize, SystemError> {
        let cutoff = Utc::now() - chrono::Duration::hours(max_age_hours);
        let mut to_remove = Vec::new();

        for n in self.notifications.iter() {
            if matches!(n.status, NotificationStatus::Delivered | NotificationStatus::Failed | NotificationStatus::Expired) {
                let timestamp = n.delivered_at.or(n.sent_at).unwrap_or(n.created_at);
                if timestamp < cutoff {
                    to_remove.push(n.id);
                }
            }
        }

        let count = to_remove.len();
        for id in to_remove {
            self.notifications.remove(&id);
        }

        Ok(count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_notifier() {
        let config = NotifierConfig {
            webhook_endpoints: vec![],
            email_smtp_host: None,
            email_smtp_port: None,
            retry_attempts: 3,
            retry_delay_secs: 1,
        };

        let notifier = Notifier::new(&config).unwrap();

        let mut metadata = HashMap::new();
        metadata.insert("key".to_string(), "value".to_string());

        let id = notifier
            .send_notification(
                NotificationChannel::Webhook,
                "http://localhost:8080/webhook".to_string(),
                "测试通知".to_string(),
                "这是测试内容".to_string(),
                NotificationPriority::Normal,
                metadata,
            )
            .await
            .unwrap();

        let notification = notifier.get_notification(id).await.unwrap();
        assert_eq!(notification.subject, "测试通知");
    }
}
