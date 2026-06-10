use crate::models::ai_review::{
    AiScanCategory, AiSuggestion, LlmMessage, LlmRequest, LlmResponse,
};
use crate::utils::error::{AppError, AppResult};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::path::Path;
use std::time::Duration;
use uuid::Uuid;

#[derive(Debug, Clone)]
pub struct LlmClient {
    api_key: String,
    base_url: String,
    model: String,
    max_tokens: u32,
    temperature: f32,
    timeout: Duration,
    client: Client,
}

#[derive(Debug, Serialize, Deserialize)]
struct StreamChatResponse {
    choices: Vec<StreamChoice>,
}

#[derive(Debug, Serialize, Deserialize)]
struct StreamChoice {
    delta: StreamDelta,
}

#[derive(Debug, Serialize, Deserialize)]
struct StreamDelta {
    content: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ParsedAiSuggestion {
    file_path: String,
    line_no: i32,
    category: String,
    severity: String,
    title: String,
    description: String,
    suggestion: String,
}

impl LlmClient {
    pub fn new(
        api_key: String,
        base_url: String,
        model: String,
        max_tokens: u32,
        temperature: f32,
        timeout_secs: u64,
    ) -> Self {
        let client = Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .build()
            .expect("Failed to create HTTP client");

        Self {
            api_key,
            base_url,
            model,
            max_tokens,
            temperature,
            timeout: Duration::from_secs(timeout_secs),
            client,
        }
    }

    pub async fn chat(&self, messages: Vec<LlmMessage>) -> AppResult<String> {
        let request = LlmRequest {
            model: self.model.clone(),
            messages,
            max_tokens: self.max_tokens,
            temperature: self.temperature,
        };

        let url = format!("{}/chat/completions", self.base_url.trim_end_matches('/'));

        let response = self
            .client
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.api_key))
            .header("Content-Type", "application/json")
            .json(&request)
            .send()
            .await
            .map_err(|e| AppError::ExternalService(format!("LLM request failed: {}", e)))?;

        if !response.status().is_success() {
            let status = response.status();
            let error_msg = response
                .text()
                .await
                .unwrap_or_else(|_| "Unknown error".to_string());
            return Err(AppError::ExternalService(format!(
                "LLM API error: {} - {}",
                status, error_msg
            )));
        }

        let llm_response: LlmResponse = response
            .json()
            .await
            .map_err(|e| AppError::ExternalService(format!("Failed to parse LLM response: {}", e)))?;

        llm_response
            .choices
            .into_iter()
            .next()
            .map(|choice| choice.message.content)
            .ok_or_else(|| AppError::ExternalService("No response from LLM".to_string()))
    }

    pub async fn stream_chat(
        &self,
        messages: Vec<LlmMessage>,
    ) -> AppResult<tokio_stream::wrappers::ReceiverStream<String>> {
        let mut request_body = serde_json::json!({
            "model": self.model,
            "messages": messages,
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "stream": true
        });

        if self.model.to_lowercase().contains("claude") || self.model.to_lowercase().contains("anthropic") {
            request_body = serde_json::json!({
                "model": self.model,
                "messages": messages,
                "max_tokens": self.max_tokens,
                "temperature": self.temperature,
                "stream": true,
                "anthropic_version": "2023-06-01"
            });
        }

        let url = format!("{}/chat/completions", self.base_url.trim_end_matches('/'));

        let (tx, rx) = tokio::sync::mpsc::channel(100);

        let api_key = self.api_key.clone();
        let client = self.client.clone();

        tokio::spawn(async move {
            let result = client
                .post(&url)
                .header("Authorization", format!("Bearer {}", api_key))
                .header("Content-Type", "application/json")
                .json(&request_body)
                .send()
                .await;

            let mut stream = match result {
                Ok(response) => response.bytes_stream(),
                Err(e) => {
                    let _ = tx.send(format!("Error: {}", e)).await;
                    return;
                }
            };

            use futures::StreamExt;
            let mut buffer = String::new();

            while let Some(chunk) = stream.next().await {
                let chunk = match chunk {
                    Ok(c) => c,
                    Err(e) => {
                        let _ = tx.send(format!("Error: {}", e)).await;
                        return;
                    }
                };

                buffer.push_str(&String::from_utf8_lossy(&chunk));

                while let Some(pos) = buffer.find("\n\n") {
                    let line = buffer.drain(..=pos + 1).collect::<String>();
                    if let Some(data) = line.strip_prefix("data: ") {
                        let data = data.trim();
                        if data == "[DONE]" {
                            return;
                        }
                        if let Ok(parsed) = serde_json::from_str::<StreamChatResponse>(data) {
                            if let Some(choice) = parsed.choices.first() {
                                if let Some(content) = &choice.delta.content {
                                    let _ = tx.send(content.clone()).await;
                                }
                            }
                        }
                    }
                }
            }
        });

        Ok(tokio_stream::wrappers::ReceiverStream::new(rx))
    }

    pub fn generate_code_review_prompt(
        &self,
        file_path: &str,
        file_content: &str,
        scan_categories: &[AiScanCategory],
    ) -> String {
        let categories_str = scan_categories
            .iter()
            .map(|c| format!("- {}", c.as_str()))
            .collect::<Vec<_>>()
            .join("\n");

        format!(
            r#"你是一个专业的代码评审专家。请分析以下代码文件，并提供详细的改进建议。

文件路径: {file_path}

扫描类别:
{categories_str}

代码内容:
```
{file_content}
```

请按照以下JSON格式返回建议列表（不要包含其他文本，只返回JSON）:
{{
    "suggestions": [
        {{
            "file_path": "文件路径",
            "line_no": 行号,
            "category": "类别（code_style/bug_pattern/security/performance/best_practice/maintainability）",
            "severity": "严重程度（low/medium/high/critical）",
            "title": "问题标题",
            "description": "问题详细描述",
            "suggestion": "改进建议"
        }}
    ]
}}"#
        )
    }

    pub async fn scan_code(
        &self,
        ai_review_id: Uuid,
        file_path: &str,
        file_content: &str,
        scan_categories: &[AiScanCategory],
    ) -> AppResult<Vec<AiSuggestion>> {
        let prompt = self.generate_code_review_prompt(file_path, file_content, scan_categories);

        let messages = vec![LlmMessage {
            role: "user".to_string(),
            content: prompt,
        }];

        let response = self.chat(messages).await?;
        self.parse_ai_response(ai_review_id, &response)
    }

    pub fn parse_ai_response(
        &self,
        ai_review_id: Uuid,
        response: &str,
    ) -> AppResult<Vec<AiSuggestion>> {
        let cleaned_response = response
            .trim()
            .trim_start_matches("```json")
            .trim_end_matches("```")
            .trim();

        let parsed: Value = serde_json::from_str(cleaned_response).map_err(|e| {
            AppError::Parse(format!(
                "Failed to parse AI response as JSON: {}. Response: {}",
                e, response
            ))
        })?;

        let suggestions = parsed
            .get("suggestions")
            .and_then(|s| s.as_array())
            .ok_or_else(|| AppError::Parse("AI response missing 'suggestions' array".to_string()))?;

        let mut result = Vec::new();
        let now = chrono::Utc::now();

        for suggestion in suggestions {
            let parsed_suggestion: ParsedAiSuggestion =
                serde_json::from_value(suggestion.clone()).map_err(|e| {
                    AppError::Parse(format!(
                        "Failed to parse suggestion: {}. Suggestion: {}",
                        e, suggestion
                    ))
                })?;

            result.push(AiSuggestion {
                id: Uuid::new_v4(),
                ai_review_id,
                file_path: parsed_suggestion.file_path,
                line_no: parsed_suggestion.line_no,
                category: parsed_suggestion.category,
                severity: parsed_suggestion.severity,
                title: parsed_suggestion.title,
                description: parsed_suggestion.description,
                suggestion: parsed_suggestion.suggestion,
                status: "pending".to_string(),
                acted_by: None,
                acted_at: None,
                created_at: now,
            });
        }

        Ok(result)
    }

    pub async fn scan_code_file<P: AsRef<Path>>(
        &self,
        ai_review_id: Uuid,
        file_path: P,
        scan_categories: &[AiScanCategory],
    ) -> AppResult<Vec<AiSuggestion>> {
        let path = file_path.as_ref();
        let file_content = std::fs::read_to_string(path)
            .map_err(|e| AppError::Io(e))?;

        let path_str = path.to_string_lossy().to_string();

        self.scan_code(
            ai_review_id,
            &path_str,
            &file_content,
            scan_categories,
        )
        .await
    }
}
