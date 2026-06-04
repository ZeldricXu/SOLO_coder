use anyhow::{Context, Result};
use std::collections::HashMap;
use tracing::{debug, info};

use common::alert::Alert;
use common::alert::NotificationChannelType;

pub struct NotificationChannel {
    pub id: String,
    pub name: String,
    pub channel_type: NotificationChannelType,
    pub config: ChannelConfig,
    pub enabled: bool,
}

pub struct ChannelConfig {
    pub webhook_url: Option<String>,
    pub email: EmailConfig,
    pub api_key: Option<String>,
}

pub struct EmailConfig {
    pub smtp_host: String,
    pub smtp_port: u16,
    pub from: String,
    pub to: Vec<String>,
}

impl NotificationChannel {
    pub fn new(
        id: String,
        name: String,
        channel_type: NotificationChannelType,
        config: ChannelConfig,
    ) -> Self {
        Self {
            id,
            name,
            channel_type,
            config,
            enabled: true,
        }
    }

    pub async fn send(&self, alert: &Alert) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }

        match self.channel_type {
            NotificationChannelType::Webhook => self.send_webhook(alert).await,
            NotificationChannelType::DingTalk => self.send_dingtalk(alert).await,
            NotificationChannelType::FeiShu => self.send_feishu(alert).await,
            NotificationChannelType::Email => self.send_email(alert).await,
            NotificationChannelType::PagerDuty => self.send_pagerduty(alert).await,
        }
    }

    async fn send_webhook(&self, alert: &Alert) -> Result<()> {
        let url = self.config.webhook_url.as_ref().context("webhook URL not configured")?;

        let payload = serde_json::json!({
            "alert": {
                "id": alert.id.to_string(),
                "name": alert.name,
                "severity": alert.severity.as_str(),
                "status": format!("{:?}", alert.status),
                "starts_at": alert.starts_at.to_rfc3339(),
                "value": alert.value,
                "annotations": alert.annotations,
            }
        });

        debug!("Sending webhook to {}", url);
        let client = reqwest::Client::new();
        client.post(url).json(&payload).send().await?;
        info!("Webhook sent successfully");
        Ok(())
    }

    async fn send_dingtalk(&self, alert: &Alert) -> Result<()> {
        let url = self.config.webhook_url.as_ref().context("DingTalk webhook not configured")?;

        let title = format!("[{}] {}", alert.severity.as_str().to_uppercase(), alert.name);
        let text = format!(
            "**Alert: {}\n**Severity**: {}\n**Value**: {}\n**Details**: {}",
            alert.name,
            alert.severity.as_str(),
            alert.value,
            alert.annotations.get("details").unwrap_or(&String::new())
        );

        let payload = serde_json::json!({
            "msgtype": "markdown",
            "markdown": {
                "title": title,
                "text": text
            }
        });

        let client = reqwest::Client::new();
        client.post(url).json(&payload).send().await?;
        info!("DingTalk alert sent");
        Ok(())
    }

    async fn send_feishu(&self, alert: &Alert) -> Result<()> {
        let url = self.config.webhook_url.as_ref().context("FeiShu webhook not configured")?;

        let title = format!("[{}] {}", alert.severity.as_str().to_uppercase(), alert.name);
        let text = format!(
            "Alert: {}\nSeverity: {}\nValue: {}\nDetails: {}",
            alert.name,
            alert.severity.as_str(),
            alert.value,
            alert.annotations.get("details").unwrap_or(&String::new())
        );

        let payload = serde_json::json!({
            "msg_type": "interactive",
            "card": {
                "config": {
                    "wide_screen_mode": true
                },
                "header": {
                    "title": {
                        "tag": "plain_text",
                        "content": title
                    }
                },
                "elements": [
                    {
                        "tag": "div",
                        "text": {
                            "tag": "lark_md",
                            "content": text
                        }
                    }
                ]
            }
        });

        let client = reqwest::Client::new();
        client.post(url).json(&payload).send().await?;
        info!("FeiShu alert sent");
        Ok(())
    }

    async fn send_email(&self, _alert: &Alert) -> Result<()> {
        info!("Email notification would be sent here (not implemented)");
        Ok(())
    }

    async fn send_pagerduty(&self, _alert: &Alert) -> Result<()> {
        info!("PagerDuty notification would be sent here (not implemented)");
        Ok(())
    }
}

impl ChannelConfig {
    pub fn webhook(url: String) -> Self {
        Self {
            webhook_url: Some(url),
            email: EmailConfig {
                smtp_host: String::new(),
                smtp_port: 587,
                from: String::new(),
                to: Vec::new(),
            },
            api_key: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::alert::{AlertSeverity, DetectionMethod};
    use common::metrics::Labels;

    #[tokio::test]
    async fn test_channel_send() {
        let channel = NotificationChannel::new(
            "test".to_string(),
            "Test Channel".to_string(),
            NotificationChannelType::Webhook,
            ChannelConfig::webhook("http://localhost:8080/test".to_string()),
        );

        let alert = Alert::new(
            "test_alert".to_string(),
            AlertSeverity::Warning,
            Labels::new(),
            DetectionMethod::StaticThreshold,
            1.0,
        );

        assert!(channel.send(&alert).await.is_ok());
    }
}
