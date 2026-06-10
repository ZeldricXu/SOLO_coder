use crate::utils::error::{AppError, AppResult};
use chrono::DateTime;
use hmac::{Hmac, Mac};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone)]
pub struct DingtalkClient {
    webhook_url: String,
    secret: Option<String>,
    client: Client,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkTextMessage {
    msgtype: String,
    text: DingtalkTextContent,
    #[serde(skip_serializing_if = "Option::is_none")]
    at: Option<DingtalkAt>,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkTextContent {
    content: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkAt {
    #[serde(skip_serializing_if = "Option::is_none")]
    atMobiles: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    atUserIds: Option<Vec<String>>,
    isAtAll: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkMarkdownMessage {
    msgtype: String,
    markdown: DingtalkMarkdownContent,
    #[serde(skip_serializing_if = "Option::is_none")]
    at: Option<DingtalkAt>,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkMarkdownContent {
    title: String,
    text: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkActionCardMessage {
    msgtype: String,
    action_card: DingtalkActionCardContent,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkActionCardContent {
    title: String,
    text: String,
    btn_orientation: String,
    btns: Vec<DingtalkActionButton>,
}

#[derive(Debug, Serialize, Deserialize)]
struct DingtalkActionButton {
    title: String,
    action_url: String,
}

impl DingtalkClient {
    pub fn new(webhook_url: String, secret: Option<String>) -> Self {
        Self {
            webhook_url,
            secret,
            client: Client::new(),
        }
    }

    pub fn sign_request(&self, timestamp: i64) -> AppResult<String> {
        let secret = self
            .secret
            .as_ref()
            .ok_or_else(|| AppError::Configuration("Dingtalk secret not configured".to_string()))?;

        let string_to_sign = format!("{}\n{}", timestamp, secret);

        let mut mac = HmacSha256::new_from_slice(secret.as_bytes())
            .map_err(|e| AppError::Internal(format!("Failed to create HMAC: {}", e)))?;

        mac.update(string_to_sign.as_bytes());
        let result = mac.finalize();
        let code_bytes = result.into_bytes();

        let signature = base64::encode(&code_bytes);
        let encoded = urlencoding::encode(&signature).to_string();

        Ok(encoded)
    }

    fn get_signed_url(&self) -> AppResult<String> {
        if self.secret.is_some() {
            let timestamp = chrono::Utc::now().timestamp_millis();
            let sign = self.sign_request(timestamp)?;
            Ok(format!(
                "{}&timestamp={}&sign={}",
                self.webhook_url, timestamp, sign
            ))
        } else {
            Ok(self.webhook_url.clone())
        }
    }

    pub async fn send_text(&self, content: &str, at_mobiles: Option<&[String]>, is_at_all: bool) -> AppResult<()> {
        let at = if at_mobiles.is_some() || is_at_all {
            Some(DingtalkAt {
                atMobiles: at_mobiles.map(|m| m.to_vec()),
                atUserIds: None,
                isAtAll: is_at_all,
            })
        } else {
            None
        };

        let message = DingtalkTextMessage {
            msgtype: "text".to_string(),
            text: DingtalkTextContent {
                content: content.to_string(),
            },
            at,
        };

        self.send(&message).await
    }

    pub async fn send_markdown(
        &self,
        title: &str,
        text: &str,
        at_mobiles: Option<&[String]>,
        is_at_all: bool,
    ) -> AppResult<()> {
        let at = if at_mobiles.is_some() || is_at_all {
            Some(DingtalkAt {
                atMobiles: at_mobiles.map(|m| m.to_vec()),
                atUserIds: None,
                isAtAll: is_at_all,
            })
        } else {
            None
        };

        let message = DingtalkMarkdownMessage {
            msgtype: "markdown".to_string(),
            markdown: DingtalkMarkdownContent {
                title: title.to_string(),
                text: text.to_string(),
            },
            at,
        };

        self.send(&message).await
    }

    pub async fn send_action_card(
        &self,
        title: &str,
        text: &str,
        buttons: Vec<(String, String)>,
        btn_vertical: bool,
    ) -> AppResult<()> {
        let btns = buttons
            .into_iter()
            .map(|(btn_title, url)| DingtalkActionButton {
                title: btn_title,
                action_url: url,
            })
            .collect();

        let message = DingtalkActionCardMessage {
            msgtype: "action_card".to_string(),
            action_card: DingtalkActionCardContent {
                title: title.to_string(),
                text: text.to_string(),
                btn_orientation: if btn_vertical { "0".to_string() } else { "1".to_string() },
                btns,
            },
        };

        self.send(&message).await
    }

    pub async fn send_code_review_notification(
        &self,
        repo_name: &str,
        mr_title: &str,
        mr_url: &str,
        author: &str,
        suggestion_count: usize,
        critical_count: usize,
        high_count: usize,
        medium_count: usize,
        low_count: usize,
    ) -> AppResult<()> {
        let status_icon = if critical_count > 0 {
            "🔴"
        } else if high_count > 0 {
            "🟠"
        } else if medium_count > 0 {
            "🟡"
        } else {
            "🟢"
        };

        let status_text = if suggestion_count == 0 {
            "✅ 未发现问题"
        } else {
            format!("⚠️ 发现 {} 个建议", suggestion_count)
        };

        let text = format!(
            r#"### {status_icon} 代码评审完成

**仓库**: {repo_name}
**标题**: {mr_title}
**作者**: {author}

{status_text}

| 严重程度 | 数量 |
|---------|------|
| Critical | {critical_count} |
| High | {high_count} |
| Medium | {medium_count} |
| Low | {low_count} |

[点击查看详情]({mr_url})"#,
            status_icon = status_icon,
            repo_name = repo_name,
            mr_title = mr_title,
            author = author,
            status_text = status_text,
            critical_count = critical_count,
            high_count = high_count,
            medium_count = medium_count,
            low_count = low_count,
            mr_url = mr_url
        );

        let buttons = vec![
            ("查看详情".to_string(), mr_url.to_string()),
            ("查看所有建议".to_string(), mr_url.to_string()),
        ];

        self.send_action_card(
            &format!("[{}] 代码评审: {}", repo_name, mr_title),
            &text,
            buttons,
            false,
        )
        .await
    }

    async fn send<T: Serialize>(&self, payload: &T) -> AppResult<()> {
        let url = self.get_signed_url()?;

        let response = self
            .client
            .post(&url)
            .header("Content-Type", "application/json")
            .json(payload)
            .send()
            .await
            .map_err(|e| AppError::ExternalService(format!("Dingtalk request failed: {}", e)))?;

        if !response.status().is_success() {
            let status = response.status();
            let error_msg = response
                .text()
                .await
                .unwrap_or_else(|_| "Unknown error".to_string());
            return Err(AppError::ExternalService(format!(
                "Dingtalk API error: {} - {}",
                status, error_msg
            )));
        }

        let result: serde_json::Value = response
            .json()
            .await
            .map_err(|e| AppError::ExternalService(format!("Failed to parse Dingtalk response: {}", e)))?;

        let errcode = result
            .get("errcode")
            .and_then(|v| v.as_i64())
            .unwrap_or(-1);

        if errcode != 0 {
            let errmsg = result
                .get("errmsg")
                .and_then(|v| v.as_str())
                .unwrap_or("Unknown error")
                .to_string();
            return Err(AppError::ExternalService(format!(
                "Dingtalk API error: {} - {}",
                errcode, errmsg
            )));
        }

        Ok(())
    }
}
