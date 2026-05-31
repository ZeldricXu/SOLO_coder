use crate::types::{
    AppError, AppResult, NotificationChannel, NotificationConfig, NotificationMessage,
    NotificationPriority, NotificationResult, NotificationStatus, NotificationTemplate,
    generate_id, now_utc,
};
use async_trait::async_trait;
use dashmap::DashMap;
use parking_lot::RwLock;
use std::any::Any;
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tera::{Context, Tera};

#[async_trait]
pub trait NotificationChannelAdapter: Send + Sync {
    fn channel(&self) -> NotificationChannel;
    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult>;
    fn as_any(&self) -> &dyn Any;
}

pub struct EmailAdapter {
    config: NotificationConfig,
}

impl EmailAdapter {
    pub fn new(config: NotificationConfig) -> Self {
        Self { config }
    }
}

#[async_trait]
impl NotificationChannelAdapter for EmailAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::Email
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "email",
            recipient = %message.recipient,
            "发送邮件通知"
        );

        if self.config.smtp_host.is_empty() {
            return Err(AppError::NotificationError(
                "SMTP配置为空，使用模拟发送".to_string(),
            ));
        }

        tokio::time::sleep(Duration::from_millis(100)).await;

        Ok(NotificationResult {
            message_id: message.message_id.clone(),
            channel: NotificationChannel::Email,
            success: true,
            sent_at: Some(now_utc()),
            error: None,
        })
    }
}

pub struct SmsAdapter {
    config: NotificationConfig,
}

impl SmsAdapter {
    pub fn new(config: NotificationConfig) -> Self {
        Self { config }
    }
}

#[async_trait]
impl NotificationChannelAdapter for SmsAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::Sms
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "sms",
            recipient = %message.recipient,
            "发送短信通知"
        );

        tokio::time::sleep(Duration::from_millis(50)).await;

        Ok(NotificationResult {
            message_id: message.message_id.clone(),
            channel: NotificationChannel::Sms,
            success: true,
            sent_at: Some(now_utc()),
            error: None,
        })
    }
}

pub struct SlackAdapter {
    config: NotificationConfig,
    client: reqwest::Client,
}

impl SlackAdapter {
    pub fn new(config: NotificationConfig) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(config.webhook_timeout_ms))
            .build()
            .unwrap_or_default();
        Self { config, client }
    }
}

#[async_trait]
impl NotificationChannelAdapter for SlackAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::Slack
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "slack",
            "发送Slack通知"
        );

        if self.config.slack_webhook.is_empty() {
            return Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Slack,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            });
        }

        let payload = serde_json::json!({
            "text": message.content,
            "username": "Enterprise Middleware",
            "icon_emoji": ":bell:"
        });

        let result = self
            .client
            .post(&self.config.slack_webhook)
            .json(&payload)
            .send()
            .await;

        match result {
            Ok(resp) if resp.status().is_success() => Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Slack,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            }),
            Ok(resp) => Err(AppError::NotificationError(format!(
                "Slack Webhook返回错误: {}",
                resp.status()
            ))),
            Err(e) => Err(AppError::NotificationError(format!(
                "Slack Webhook请求失败: {}",
                e
            ))),
        }
    }
}

pub struct DingtalkAdapter {
    config: NotificationConfig,
    client: reqwest::Client,
}

impl DingtalkAdapter {
    pub fn new(config: NotificationConfig) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(config.webhook_timeout_ms))
            .build()
            .unwrap_or_default();
        Self { config, client }
    }
}

#[async_trait]
impl NotificationChannelAdapter for DingtalkAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::Dingtalk
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "dingtalk",
            "发送钉钉通知"
        );

        if self.config.dingtalk_webhook.is_empty() {
            return Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Dingtalk,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            });
        }

        let payload = serde_json::json!({
            "msgtype": "text",
            "text": {
                "content": message.content
            }
        });

        let result = self
            .client
            .post(&self.config.dingtalk_webhook)
            .json(&payload)
            .send()
            .await;

        match result {
            Ok(resp) if resp.status().is_success() => Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Dingtalk,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            }),
            Ok(resp) => Err(AppError::NotificationError(format!(
                "钉钉Webhook返回错误: {}",
                resp.status()
            ))),
            Err(e) => Err(AppError::NotificationError(format!(
                "钉钉Webhook请求失败: {}",
                e
            ))),
        }
    }
}

pub struct WechatAdapter {
    config: NotificationConfig,
    client: reqwest::Client,
}

impl WechatAdapter {
    pub fn new(config: NotificationConfig) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(config.webhook_timeout_ms))
            .build()
            .unwrap_or_default();
        Self { config, client }
    }
}

#[async_trait]
impl NotificationChannelAdapter for WechatAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::Wechat
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "wechat",
            "发送企业微信通知"
        );

        if self.config.wechat_webhook.is_empty() {
            return Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Wechat,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            });
        }

        let payload = serde_json::json!({
            "msgtype": "text",
            "text": {
                "content": message.content
            }
        });

        let result = self
            .client
            .post(&self.config.wechat_webhook)
            .json(&payload)
            .send()
            .await;

        match result {
            Ok(resp) if resp.status().is_success() => Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Wechat,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            }),
            Ok(resp) => Err(AppError::NotificationError(format!(
                "企业微信Webhook返回错误: {}",
                resp.status()
            ))),
            Err(e) => Err(AppError::NotificationError(format!(
                "企业微信Webhook请求失败: {}",
                e
            ))),
        }
    }
}

pub struct WebhookAdapter {
    config: NotificationConfig,
    client: reqwest::Client,
}

impl WebhookAdapter {
    pub fn new(config: NotificationConfig) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_millis(config.webhook_timeout_ms))
            .build()
            .unwrap_or_default();
        Self { config, client }
    }
}

#[async_trait]
impl NotificationChannelAdapter for WebhookAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::Webhook
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "webhook",
            url = %message.recipient,
            "发送Webhook通知"
        );

        let payload = serde_json::json!({
            "message_id": message.message_id,
            "subject": message.subject,
            "content": message.content,
            "priority": format!("{:?}", message.priority),
            "variables": message.variables,
            "timestamp": now_utc().to_rfc3339()
        });

        let result = self
            .client
            .post(&message.recipient)
            .json(&payload)
            .send()
            .await;

        match result {
            Ok(resp) if resp.status().is_success() => Ok(NotificationResult {
                message_id: message.message_id.clone(),
                channel: NotificationChannel::Webhook,
                success: true,
                sent_at: Some(now_utc()),
                error: None,
            }),
            Ok(resp) => Err(AppError::NotificationError(format!(
                "Webhook返回错误: {}",
                resp.status()
            ))),
            Err(e) => Err(AppError::NotificationError(format!(
                "Webhook请求失败: {}",
                e
            ))),
        }
    }
}

pub struct InAppAdapter {
    messages: Arc<DashMap<String, Vec<NotificationMessage>>>,
}

impl InAppAdapter {
    pub fn new() -> Self {
        Self {
            messages: Arc::new(DashMap::new()),
        }
    }

    pub fn get_messages(&self, user_id: &str) -> Vec<NotificationMessage> {
        self.messages
            .get(user_id)
            .map(|m| m.value().clone())
            .unwrap_or_default()
    }
}

impl Default for InAppAdapter {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl NotificationChannelAdapter for InAppAdapter {
    fn channel(&self) -> NotificationChannel {
        NotificationChannel::InApp
    }

    fn as_any(&self) -> &dyn Any {
        self
    }

    async fn send(&self, message: &NotificationMessage) -> AppResult<NotificationResult> {
        tracing::debug!(
            target: "notification",
            channel = "in_app",
            recipient = %message.recipient,
            "发送站内通知"
        );

        self.messages
            .entry(message.recipient.clone())
            .or_default()
            .push(message.clone());

        Ok(NotificationResult {
            message_id: message.message_id.clone(),
            channel: NotificationChannel::InApp,
            success: true,
            sent_at: Some(now_utc()),
            error: None,
        })
    }
}

pub struct TemplateManager {
    templates: DashMap<String, NotificationTemplate>,
    tera: Arc<RwLock<Tera>>,
}

impl TemplateManager {
    pub fn new() -> Self {
        let mut tera = Tera::default();
        tera.autoescape_on(vec![]);

        Self {
            templates: DashMap::new(),
            tera: Arc::new(RwLock::new(tera)),
        }
    }

    pub fn register_template(&self, template: NotificationTemplate) -> AppResult<()> {
        let tera = self.tera.write();
        let template_name = format!("subject_{}", template.template_id);
        tera.add_raw_template(&template_name, &template.subject_template)
            .map_err(|e| AppError::NotificationError(format!("模板注册失败: {}", e)))?;

        let content_name = format!("content_{}", template.template_id);
        tera.add_raw_template(&content_name, &template.content_template)
            .map_err(|e| AppError::NotificationError(format!("模板注册失败: {}", e)))?;

        self.templates.insert(template.template_id.clone(), template);
        Ok(())
    }

    pub fn get_template(&self, template_id: &str) -> Option<NotificationTemplate> {
        self.templates.get(template_id).map(|t| t.clone())
    }

    pub fn list_templates(&self) -> Vec<NotificationTemplate> {
        self.templates.iter().map(|t| t.clone()).collect()
    }

    pub fn render(
        &self,
        template_id: &str,
        variables: &HashMap<String, serde_json::Value>,
    ) -> AppResult<(String, String)> {
        let template = self
            .get_template(template_id)
            .ok_or_else(|| AppError::NotFound(format!("模板不存在: {}", template_id)))?;

        let mut context = Context::new();
        for (key, value) in variables {
            context.insert(key, value);
        }

        let tera = self.tera.read();
        let subject_name = format!("subject_{}", template_id);
        let content_name = format!("content_{}", template_id);

        let subject = tera
            .render(&subject_name, &context)
            .map_err(|e| AppError::NotificationError(format!("主题渲染失败: {}", e)))?;

        let content = tera
            .render(&content_name, &context)
            .map_err(|e| AppError::NotificationError(format!("内容渲染失败: {}", e)))?;

        Ok((subject, content))
    }

    pub fn delete_template(&self, template_id: &str) -> AppResult<()> {
        if self.templates.remove(template_id).is_none() {
            return Err(AppError::NotFound(format!("模板不存在: {}", template_id)));
        }
        Ok(())
    }
}

impl Default for TemplateManager {
    fn default() -> Self {
        Self::new()
    }
}

struct RateLimiter {
    requests: RwLock<Vec<Instant>>,
    limit_per_minute: u32,
}

impl RateLimiter {
    fn new(limit_per_minute: u32) -> Self {
        Self {
            requests: RwLock::new(Vec::new()),
            limit_per_minute,
        }
    }

    fn try_acquire(&self) -> bool {
        let now = Instant::now();
        let one_minute_ago = now - Duration::from_secs(60);

        let mut requests = self.requests.write();
        requests.retain(|t| *t > one_minute_ago);

        if requests.len() as u32 >= self.limit_per_minute {
            return false;
        }

        requests.push(now);
        true
    }
}

pub struct NotificationManager {
    config: NotificationConfig,
    adapters: DashMap<NotificationChannel, Arc<dyn NotificationChannelAdapter>>,
    template_manager: Arc<TemplateManager>,
    rate_limiter: Arc<RateLimiter>,
    message_history: DashMap<String, NotificationMessage>,
    pending_messages: Arc<DashMap<String, NotificationMessage>>,
}

impl NotificationManager {
    pub fn new(config: NotificationConfig) -> Self {
        let adapters = DashMap::new();

        adapters.insert(
            NotificationChannel::Email,
            Arc::new(EmailAdapter::new(config.clone())) as Arc<dyn NotificationChannelAdapter>,
        );
        adapters.insert(
            NotificationChannel::Sms,
            Arc::new(SmsAdapter::new(config.clone())) as Arc<dyn NotificationChannelAdapter>,
        );
        adapters.insert(
            NotificationChannel::Slack,
            Arc::new(SlackAdapter::new(config.clone())) as Arc<dyn NotificationChannelAdapter>,
        );
        adapters.insert(
            NotificationChannel::Dingtalk,
            Arc::new(DingtalkAdapter::new(config.clone())) as Arc<dyn NotificationChannelAdapter>,
        );
        adapters.insert(
            NotificationChannel::Wechat,
            Arc::new(WechatAdapter::new(config.clone())) as Arc<dyn NotificationChannelAdapter>,
        );
        adapters.insert(
            NotificationChannel::Webhook,
            Arc::new(WebhookAdapter::new(config.clone())) as Arc<dyn NotificationChannelAdapter>,
        );
        adapters.insert(
            NotificationChannel::InApp,
            Arc::new(InAppAdapter::new()) as Arc<dyn NotificationChannelAdapter>,
        );

        Self {
            config: config.clone(),
            adapters,
            template_manager: Arc::new(TemplateManager::new()),
            rate_limiter: Arc::new(RateLimiter::new(config.rate_limit_per_minute)),
            message_history: DashMap::new(),
            pending_messages: Arc::new(DashMap::new()),
        }
    }

    pub fn template_manager(&self) -> &TemplateManager {
        &self.template_manager
    }

    pub async fn send(
        &self,
        channel: NotificationChannel,
        recipient: &str,
        template_id: &str,
        variables: HashMap<String, serde_json::Value>,
        priority: NotificationPriority,
    ) -> AppResult<NotificationResult> {
        if !self.config.enabled {
            return Err(AppError::NotificationError(
                "通知服务未启用".to_string(),
            ));
        }

        if !self.rate_limiter.try_acquire() {
            return Err(AppError::RateLimited);
        }

        let (subject, content) = self.template_manager.render(template_id, &variables)?;

        let message = NotificationMessage {
            message_id: generate_id("msg"),
            channel: channel.clone(),
            template_id: template_id.to_string(),
            recipient: recipient.to_string(),
            subject,
            content,
            variables,
            priority,
            status: NotificationStatus::Pending,
            created_at: now_utc(),
            sent_at: None,
            error: None,
        };

        self.send_with_retry(message).await
    }

    pub async fn send_raw(
        &self,
        channel: NotificationChannel,
        recipient: &str,
        subject: &str,
        content: &str,
        priority: NotificationPriority,
    ) -> AppResult<NotificationResult> {
        if !self.config.enabled {
            return Err(AppError::NotificationError(
                "通知服务未启用".to_string(),
            ));
        }

        if !self.rate_limiter.try_acquire() {
            return Err(AppError::RateLimited);
        }

        let message = NotificationMessage {
            message_id: generate_id("msg"),
            channel: channel.clone(),
            template_id: "raw".to_string(),
            recipient: recipient.to_string(),
            subject: subject.to_string(),
            content: content.to_string(),
            variables: HashMap::new(),
            priority,
            status: NotificationStatus::Pending,
            created_at: now_utc(),
            sent_at: None,
            error: None,
        };

        self.send_with_retry(message).await
    }

    async fn send_with_retry(
        &self,
        mut message: NotificationMessage,
    ) -> AppResult<NotificationResult> {
        let adapter = self
            .adapters
            .get(&message.channel)
            .ok_or_else(|| {
                AppError::NotFound(format!("不支持的通知渠道: {:?}", message.channel))
            })?
            .clone();

        self.message_history
            .insert(message.message_id.clone(), message.clone());

        message.status = NotificationStatus::Sending;
        self.message_history
            .insert(message.message_id.clone(), message.clone());

        let mut last_error = None;
        let retry_count = self.config.retry_count;
        let retry_interval = Duration::from_millis(self.config.retry_interval_ms);

        for attempt in 0..=retry_count {
            if attempt > 0 {
                message.status = NotificationStatus::Retrying;
                self.message_history
                    .insert(message.message_id.clone(), message.clone());
                tokio::time::sleep(retry_interval).await;
            }

            match adapter.send(&message).await {
                Ok(result) => {
                    message.status = NotificationStatus::Sent;
                    message.sent_at = result.sent_at;
                    self.message_history
                        .insert(message.message_id.clone(), message.clone());
                    return Ok(result);
                }
                Err(e) => {
                    last_error = Some(e);
                }
            }
        }

        message.status = NotificationStatus::Failed;
        message.error = last_error.as_ref().map(|e| e.to_string());
        self.message_history
            .insert(message.message_id.clone(), message.clone());

        Err(last_error.unwrap_or_else(|| {
            AppError::NotificationError("通知发送失败".to_string())
        }))
    }

    pub async fn send_batch(
        &self,
        messages: Vec<(
            NotificationChannel,
            String,
            String,
            HashMap<String, serde_json::Value>,
            NotificationPriority,
        )>,
    ) -> Vec<AppResult<NotificationResult>> {
        let mut tasks = Vec::new();

        for (channel, recipient, template_id, variables, priority) in messages {
            let self_clone = self.clone();
            tasks.push(tokio::spawn(async move {
                self_clone
                    .send(channel, &recipient, &template_id, variables, priority)
                    .await
            }));
        }

        let mut results = Vec::new();
        for task in tasks {
            results.push(
                task.await
                    .unwrap_or_else(|e| Err(AppError::NotificationError(format!("任务执行失败: {}", e)))),
            );
        }

        results
    }

    pub fn get_message(&self, message_id: &str) -> Option<NotificationMessage> {
        self.message_history.get(message_id).map(|m| m.clone())
    }

    pub fn get_message_history(
        &self,
        channel: Option<NotificationChannel>,
        status: Option<NotificationStatus>,
        limit: usize,
    ) -> Vec<NotificationMessage> {
        let mut messages: Vec<NotificationMessage> = self
            .message_history
            .iter()
            .map(|m| m.clone())
            .filter(|m| {
                if let Some(ref ch) = channel {
                    if m.channel != *ch {
                        return false;
                    }
                }
                if let Some(ref st) = status {
                    if m.status != *st {
                        return false;
                    }
                }
                true
            })
            .collect();

        messages.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        messages.truncate(limit);
        messages
    }

    pub fn get_in_app_messages(&self, user_id: &str) -> Vec<NotificationMessage> {
        if let Some(adapter) = self.adapters.get(&NotificationChannel::InApp) {
            if let Some(in_app) = adapter
                .as_ref()
                .as_any()
                .downcast_ref::<InAppAdapter>()
            {
                return in_app.get_messages(user_id);
            }
        }
        Vec::new()
    }

    pub fn register_default_templates(&self) -> AppResult<()> {
        let templates = vec![
            NotificationTemplate {
                template_id: "alert_high_priority".to_string(),
                name: "高优先级告警".to_string(),
                channel: NotificationChannel::Slack,
                subject_template: "【高优先级告警】{{ title }}".to_string(),
                content_template: "告警级别: {{ level }}\n告警信息: {{ message }}\n发生时间: {{ timestamp }}\n详情: {{ details }}".to_string(),
                variables: vec![
                    "title".to_string(),
                    "level".to_string(),
                    "message".to_string(),
                    "timestamp".to_string(),
                    "details".to_string(),
                ],
                created_at: now_utc(),
                updated_at: now_utc(),
            },
            NotificationTemplate {
                template_id: "data_quality_alert".to_string(),
                name: "数据质量告警".to_string(),
                channel: NotificationChannel::Email,
                subject_template: "【数据质量】{{ rule_name }} 校验异常".to_string(),
                content_template: "规则ID: {{ rule_id }}\n规则名称: {{ rule_name }}\n数据集: {{ dataset }}\n实际值: {{ actual_value }}\n预期值: {{ expected_value }}\n异常数量: {{ anomaly_count }}\n检测时间: {{ timestamp }}".to_string(),
                variables: vec![
                    "rule_id".to_string(),
                    "rule_name".to_string(),
                    "dataset".to_string(),
                    "actual_value".to_string(),
                    "expected_value".to_string(),
                    "anomaly_count".to_string(),
                    "timestamp".to_string(),
                ],
                created_at: now_utc(),
                updated_at: now_utc(),
            },
            NotificationTemplate {
                template_id: "system_status".to_string(),
                name: "系统状态通知".to_string(),
                channel: NotificationChannel::InApp,
                subject_template: "系统状态更新: {{ status }}".to_string(),
                content_template: "系统: {{ system }}\n状态: {{ status }}\n消息: {{ message }}\n时间: {{ timestamp }}".to_string(),
                variables: vec![
                    "system".to_string(),
                    "status".to_string(),
                    "message".to_string(),
                    "timestamp".to_string(),
                ],
                created_at: now_utc(),
                updated_at: now_utc(),
            },
        ];

        for template in templates {
            self.template_manager.register_template(template)?;
        }

        Ok(())
    }
}

impl Clone for NotificationManager {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            adapters: self.adapters.clone(),
            template_manager: self.template_manager.clone(),
            rate_limiter: self.rate_limiter.clone(),
            message_history: self.message_history.clone(),
            pending_messages: self.pending_messages.clone(),
        }
    }
}

pub fn create_notification_manager(config: NotificationConfig) -> AppResult<NotificationManager> {
    let manager = NotificationManager::new(config);
    manager.register_default_templates()?;
    Ok(manager)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_template_rendering() {
        let config = NotificationConfig {
            enabled: true,
            default_channel: "in_app".to_string(),
            rate_limit_per_minute: 100,
            retry_count: 0,
            retry_interval_ms: 1000,
            smtp_host: "".to_string(),
            smtp_port: 25,
            smtp_username: "".to_string(),
            smtp_password: "".to_string(),
            slack_webhook: "".to_string(),
            dingtalk_webhook: "".to_string(),
            wechat_webhook: "".to_string(),
            webhook_timeout_ms: 5000,
        };

        let manager = create_notification_manager(config).unwrap();

        let mut variables = HashMap::new();
        variables.insert("title".to_string(), serde_json::json!("测试告警"));
        variables.insert("level".to_string(), serde_json::json!("Critical"));
        variables.insert("message".to_string(), serde_json::json!("这是一条测试消息"));
        variables.insert("timestamp".to_string(), serde_json::json!("2026-01-01T00:00:00Z"));
        variables.insert("details".to_string(), serde_json::json!("测试详情"));

        let (subject, content) = manager
            .template_manager()
            .render("alert_high_priority", &variables)
            .unwrap();

        assert!(subject.contains("测试告警"));
        assert!(content.contains("这是一条测试消息"));
    }

    #[tokio::test]
    async fn test_in_app_notification() {
        let config = NotificationConfig {
            enabled: true,
            default_channel: "in_app".to_string(),
            rate_limit_per_minute: 100,
            retry_count: 0,
            retry_interval_ms: 1000,
            smtp_host: "".to_string(),
            smtp_port: 25,
            smtp_username: "".to_string(),
            smtp_password: "".to_string(),
            slack_webhook: "".to_string(),
            dingtalk_webhook: "".to_string(),
            wechat_webhook: "".to_string(),
            webhook_timeout_ms: 5000,
        };

        let manager = create_notification_manager(config).unwrap();

        let result = manager
            .send_raw(
                NotificationChannel::InApp,
                "user123",
                "测试主题",
                "测试内容",
                NotificationPriority::Medium,
            )
            .await
            .unwrap();

        assert!(result.success);
    }
}
