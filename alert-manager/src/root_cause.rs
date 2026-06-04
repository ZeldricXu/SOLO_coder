use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{debug, info, warn};

use common::alert::Alert;

#[derive(Debug, Clone)]
pub struct RootCauseConfig {
    pub enabled: bool,
    pub openai_api_key: String,
    pub openai_model: String,
    pub elasticsearch_url: String,
    pub lookback_minutes: i64,
    pub max_log_lines: usize,
}

impl Default for RootCauseConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            openai_api_key: String::new(),
            openai_model: "gpt-4o-mini".to_string(),
            elasticsearch_url: "http://localhost:9200".to_string(),
            lookback_minutes: 5,
            max_log_lines: 100,
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct OpenAIRequest {
    model: String,
    messages: Vec<OpenAIMessage>,
    temperature: f64,
    max_tokens: u32,
}

#[derive(Debug, Serialize, Deserialize)]
struct OpenAIMessage {
    role: String,
    content: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct OpenAIResponse {
    choices: Vec<OpenAIChoice>,
}

#[derive(Debug, Serialize, Deserialize)]
struct OpenAIChoice {
    message: OpenAIMessage,
}

#[derive(Debug, Serialize)]
struct ESSearchRequest {
    query: ESQuery,
    size: usize,
    sort: Vec<serde_json::Value>,
}

#[derive(Debug, Serialize)]
struct ESQuery {
    bool: ESBoolQuery,
}

#[derive(Debug, Serialize)]
struct ESBoolQuery {
    must: Vec<serde_json::Value>,
    filter: Vec<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RootCauseAnalysis {
    pub alert_name: String,
    pub possible_causes: Vec<String>,
    pub recommended_actions: Vec<String>,
    pub confidence: f64,
    pub related_logs_summary: String,
    pub raw_analysis: String,
}

pub struct RootCauseAnalyzer {
    config: RootCauseConfig,
    http_client: reqwest::Client,
}

impl RootCauseAnalyzer {
    pub fn new(config: RootCauseConfig) -> Self {
        Self {
            config,
            http_client: reqwest::Client::new(),
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.config.enabled && !self.config.openai_api_key.is_empty()
    }

    pub async fn analyze(&self, alert: &Alert) -> Result<Option<RootCauseAnalysis>> {
        if !self.is_enabled() {
            return Ok(None);
        }

        info!("Starting root cause analysis for alert: {}", alert.name);

        let logs = self.fetch_related_logs(alert).await?;
        if logs.is_empty() {
            debug!("No related logs found for alert {}", alert.name);
            return Ok(None);
        }

        let analysis = self.call_llm(alert, &logs).await?;
        Ok(Some(analysis))
    }

    async fn fetch_related_logs(&self, alert: &Alert) -> Result<Vec<String>> {
        let now = chrono::Utc::now();
        let from_time = now - chrono::Duration::minutes(self.config.lookback_minutes);

        let mut must_clauses = Vec::new();
        must_clauses.push(serde_json::json!({
            "range": {
                "@timestamp": {
                    "gte": from_time.to_rfc3339(),
                    "lte": now.to_rfc3339()
                }
            }
        }));

        let service_name = alert.labels.get("service");
        if let Some(service) = service_name {
            must_clauses.push(serde_json::json!({
                "match": {
                    "service": service
                }
            }));
        }

        let mut filter_clauses = Vec::new();
        filter_clauses.push(serde_json::json!({
            "terms": {
                "level": ["ERROR", "WARN", "FATAL", "error", "warn", "fatal"]
            }
        }));

        let es_request = ESSearchRequest {
            query: ESQuery {
                bool: ESBoolQuery {
                    must: must_clauses,
                    filter: filter_clauses,
                },
            },
            size: self.config.max_log_lines,
            sort: vec![serde_json::json!({"@timestamp": {"order": "desc"}})],
        };

        let url = format!("{}/_search", self.config.elasticsearch_url);

        let response = self
            .http_client
            .post(&url)
            .header("Content-Type", "application/json")
            .json(&es_request)
            .send()
            .await;

        match response {
            Ok(resp) => {
                let body: serde_json::Value = resp.json().await?;
                let hits = body
                    .get("hits")
                    .and_then(|h| h.get("hits"))
                    .and_then(|h| h.as_array())
                    .cloned()
                    .unwrap_or_default();

                let logs: Vec<String> = hits
                    .iter()
                    .filter_map(|hit| {
                        hit.get("_source")
                            .and_then(|s| s.get("message"))
                            .and_then(|m| m.as_str())
                            .map(|s| s.to_string())
                    })
                    .collect();

                info!("Fetched {} related log lines from ES", logs.len());
                Ok(logs)
            }
            Err(e) => {
                warn!("Failed to fetch logs from ES: {}", e);
                Ok(Vec::new())
            }
        }
    }

    async fn call_llm(&self, alert: &Alert, logs: &[String]) -> Result<RootCauseAnalysis> {
        let logs_text = logs
            .iter()
            .take(50)
            .cloned()
            .collect::<Vec<_>>()
            .join("\n");

        let severity = alert.severity.as_str();
        let alert_details = alert
            .annotations
            .get("details")
            .cloned()
            .unwrap_or_default();

        let system_prompt = r#"You are an expert Site Reliability Engineer analyzing production incidents.
Given an alert and related log entries, provide a root cause analysis with:
1. Possible causes (list each as a separate item)
2. Recommended actions (list each as a separate item)
3. Confidence level (0.0 to 1.0)
4. Brief summary of the related logs

Respond in JSON format:
{
  "possible_causes": ["cause1", "cause2"],
  "recommended_actions": ["action1", "action2"],
  "confidence": 0.8,
  "related_logs_summary": "summary text"
}"#;

        let user_prompt = format!(
            "Alert: {}\nSeverity: {}\nValue: {}\nDetails: {}\n\nRelated logs:\n{}",
            alert.name, severity, alert.value, alert_details, logs_text
        );

        let request = OpenAIRequest {
            model: self.config.openai_model.clone(),
            messages: vec![
                OpenAIMessage {
                    role: "system".to_string(),
                    content: system_prompt.to_string(),
                },
                OpenAIMessage {
                    role: "user".to_string(),
                    content: user_prompt,
                },
            ],
            temperature: 0.3,
            max_tokens: 1024,
        };

        let url = "https://api.openai.com/v1/chat/completions";

        let response = self
            .http_client
            .post(url)
            .header("Authorization", format!("Bearer {}", self.config.openai_api_key))
            .header("Content-Type", "application/json")
            .json(&request)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            warn!("OpenAI API error: status={}, body={}", status, body);
            anyhow::bail!("OpenAI API returned status {}", status);
        }

        let openai_response: OpenAIResponse = response.json().await?;
        let content = openai_response
            .choices
            .first()
            .map(|c| c.message.content.clone())
            .unwrap_or_default();

        let analysis: serde_json::Value = serde_json::from_str(&content).unwrap_or_else(|_| {
            serde_json::json!({
                "possible_causes": ["Unable to parse LLM response"],
                "recommended_actions": ["Review logs manually"],
                "confidence": 0.0,
                "related_logs_summary": content.clone()
            })
        });

        Ok(RootCauseAnalysis {
            alert_name: alert.name.clone(),
            possible_causes: analysis
                .get("possible_causes")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            recommended_actions: analysis
                .get("recommended_actions")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            confidence: analysis
                .get("confidence")
                .and_then(|v| v.as_f64())
                .unwrap_or(0.0),
            related_logs_summary: analysis
                .get("related_logs_summary")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            raw_analysis: content,
        })
    }
}
