use crate::utils::error::{AppError, AppResult};
use chrono::DateTime;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::json;

#[derive(Debug, Clone)]
pub struct SlackClient {
    webhook_url: String,
    client: Client,
}

#[derive(Debug, Serialize, Deserialize)]
struct SlackMessage {
    text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    mrkdwn: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    attachments: Option<Vec<SlackAttachment>>,
}

#[derive(Debug, Serialize, Deserialize)]
struct SlackAttachment {
    color: String,
    title: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    title_link: Option<String>,
    text: String,
    mrkdwn_in: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    fields: Option<Vec<SlackField>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    footer: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ts: Option<i64>,
}

#[derive(Debug, Serialize, Deserialize)]
struct SlackField {
    title: String,
    value: String,
    short: bool,
}

impl SlackClient {
    pub fn new(webhook_url: String) -> Self {
        Self {
            webhook_url,
            client: Client::new(),
        }
    }

    pub async fn send_message(&self, text: &str) -> AppResult<()> {
        let message = SlackMessage {
            text: text.to_string(),
            mrkdwn: None,
            attachments: None,
        };

        self.send(&message).await
    }

    pub async fn send_markdown(&self, text: &str) -> AppResult<()> {
        let message = SlackMessage {
            text: text.to_string(),
            mrkdwn: Some(true),
            attachments: None,
        };

        self.send(&message).await
    }

    pub async fn send_mention(&self, user_ids: &[String], text: &str) -> AppResult<()> {
        let mentions = user_ids
            .iter()
            .map(|id| format!("<@{}>", id))
            .collect::<Vec<_>>()
            .join(" ");

        let full_text = format!("{} {}", mentions, text);

        let message = SlackMessage {
            text: full_text,
            mrkdwn: Some(true),
            attachments: None,
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
        let color = if critical_count > 0 {
            "#ff0000"
        } else if high_count > 0 {
            "#ff7700"
        } else if medium_count > 0 {
            "#ffcc00"
        } else if low_count > 0 {
            "#36a64f"
        } else {
            "#36a64f"
        };

        let status_text = if suggestion_count == 0 {
            "✅ 未发现问题"
        } else {
            format!("⚠️ 发现 {} 个建议", suggestion_count)
        };

        let attachment = SlackAttachment {
            color: color.to_string(),
            title: format!("[{}] {}", repo_name, mr_title),
            title_link: Some(mr_url.to_string()),
            text: format!("*代码评审完成*\n\n{}", status_text),
            mrkdwn_in: vec!["text".to_string(), "fields".to_string()],
            fields: Some(vec![
                SlackField {
                    title: "作者".to_string(),
                    value: author.to_string(),
                    short: true,
                },
                SlackField {
                    title: "建议总数".to_string(),
                    value: suggestion_count.to_string(),
                    short: true,
                },
                SlackField {
                    title: "严重(Critical)".to_string(),
                    value: critical_count.to_string(),
                    short: true,
                },
                SlackField {
                    title: "高(High)".to_string(),
                    value: high_count.to_string(),
                    short: true,
                },
                SlackField {
                    title: "中(Medium)".to_string(),
                    value: medium_count.to_string(),
                    short: true,
                },
                SlackField {
                    title: "低(Low)".to_string(),
                    value: low_count.to_string(),
                    short: true,
                },
            ]),
            footer: Some("Code Review Platform".to_string()),
            ts: Some(chrono::Utc::now().timestamp()),
        };

        let message = SlackMessage {
            text: format!("代码评审完成: {}", mr_title),
            mrkdwn: Some(true),
            attachments: Some(vec![attachment]),
        };

        self.send(&message).await
    }

    pub async fn send_daily_digest(
        &self,
        date: DateTime<chrono::Utc>,
        total_mrs: i64,
        total_reviews: i64,
        total_suggestions: i64,
        top_repositories: Vec<(String, i64)>,
    ) -> AppResult<()> {
        let date_str = date.format("%Y-%m-%d").to_string();

        let mut fields = vec![
            SlackField {
                title: "合并请求数".to_string(),
                value: total_mrs.to_string(),
                short: true,
            },
            SlackField {
                title: "AI评审次数".to_string(),
                value: total_reviews.to_string(),
                short: true,
            },
            SlackField {
                title: "建议总数".to_string(),
                value: total_suggestions.to_string(),
                short: true,
            },
        ];

        for (repo, count) in top_repositories.iter().take(5) {
            fields.push(SlackField {
                title: repo.clone(),
                value: format!("{} 个MR", count),
                short: true,
            });
        }

        let attachment = SlackAttachment {
            color: "#439FE0".to_string(),
            title: format!("📊 每日摘要 - {}", date_str),
            title_link: None,
            text: "以下是今日的代码评审统计摘要：".to_string(),
            mrkdwn_in: vec!["text".to_string(), "fields".to_string()],
            fields: Some(fields),
            footer: Some("Code Review Platform".to_string()),
            ts: Some(chrono::Utc::now().timestamp()),
        };

        let message = SlackMessage {
            text: format!("📊 每日代码评审摘要 - {}", date_str),
            mrkdwn: Some(true),
            attachments: Some(vec![attachment]),
        };

        self.send(&message).await
    }

    async fn send<T: Serialize>(&self, payload: &T) -> AppResult<()> {
        let response = self
            .client
            .post(&self.webhook_url)
            .header("Content-Type", "application/json")
            .json(payload)
            .send()
            .await
            .map_err(|e| AppError::ExternalService(format!("Slack request failed: {}", e)))?;

        if !response.status().is_success() {
            let status = response.status();
            let error_msg = response
                .text()
                .await
                .unwrap_or_else(|_| "Unknown error".to_string());
            return Err(AppError::ExternalService(format!(
                "Slack API error: {} - {}",
                status, error_msg
            )));
        }

        Ok(())
    }
}
