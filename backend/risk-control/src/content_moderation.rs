use common::error::AppResult;
use models::ImageModerationResult;
use std::path::Path;
use tracing::{info, warn};

pub struct ContentModerationService {
    api_key: String,
    api_endpoint: String,
    enabled: bool,
}

impl ContentModerationService {
    pub fn new() -> Self {
        let api_key = std::env::var("CONTENT_MODERATION_API_KEY").unwrap_or_default();
        let api_endpoint = std::env::var("CONTENT_MODERATION_API_ENDPOINT")
            .unwrap_or_else(|_| "https://api.example.com/moderate".into());
        let enabled = std::env::var("CONTENT_MODERATION_ENABLED")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(false);

        Self {
            api_key,
            api_endpoint,
            enabled,
        }
    }

    pub async fn moderate_image(&self, image_path: &Path) -> AppResult<ImageModerationResult> {
        if !self.enabled {
            return Ok(ImageModerationResult {
                safe: true,
                confidence: 0.95,
                categories: vec!["general".into()],
                flagged_content: None,
            });
        }

        let _img_bytes = match std::fs::read(image_path) {
            Ok(b) => b,
            Err(e) => {
                warn!(path = %image_path.display(), error = %e, "Failed to read image for moderation");
                return Ok(ImageModerationResult {
                    safe: true,
                    confidence: 0.7,
                    categories: vec!["unknown".into()],
                    flagged_content: None,
                });
            }
        };

        info!(path = %image_path.display(), "[MOCK] Image moderation would call external API");
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

        Ok(ImageModerationResult {
            safe: true,
            confidence: 0.90,
            categories: vec!["general".into(), "product".into()],
            flagged_content: None,
        })
    }

    pub async fn moderate_text(&self, text: &str) -> AppResult<ImageModerationResult> {
        if !self.enabled {
            return Ok(ImageModerationResult {
                safe: true,
                confidence: 0.95,
                categories: vec!["text".into()],
                flagged_content: None,
            });
        }

        let lower_text = text.to_lowercase();
        let sensitive_words = ["违禁词1", "违禁词2", "敏感词"];
        let mut flagged = Vec::new();

        for word in sensitive_words {
            if lower_text.contains(word) {
                flagged.push(word.to_string());
            }
        }

        info!(text_len = text.len(), flagged_count = flagged.len(), "[MOCK] Text moderation completed");
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;

        Ok(ImageModerationResult {
            safe: flagged.is_empty(),
            confidence: if flagged.is_empty() { 0.95 } else { 0.8 },
            categories: vec!["text".into()],
            flagged_content: if flagged.is_empty() { None } else { Some(flagged) },
        })
    }

    pub fn moderate_keywords(&self, text: &str) -> Vec<String> {
        let lower_text = text.to_lowercase();
        let sensitive_patterns = [
            "枪支", "弹药", "毒品", "假币", "色情", "赌博",
            "炸药", "管制刀具", "窃听器", "迷药",
        ];

        sensitive_patterns
            .iter()
            .filter(|p| lower_text.contains(&p.to_lowercase()))
            .map(|s| s.to_string())
            .collect()
    }
}

impl Clone for ContentModerationService {
    fn clone(&self) -> Self {
        Self {
            api_key: self.api_key.clone(),
            api_endpoint: self.api_endpoint.clone(),
            enabled: self.enabled,
        }
    }
}
