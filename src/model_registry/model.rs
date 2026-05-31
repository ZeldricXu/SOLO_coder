use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Model {
    pub model_id: String,
    pub name: String,
    pub description: String,
    pub provider: String,
    pub model_type: ModelType,
    pub capabilities: Vec<String>,
    pub max_tokens: u64,
    pub supported_languages: Vec<String>,
    pub tags: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
    pub created_by: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
    pub is_active: bool,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ModelType {
    Llm,
    Embedding,
    Image,
    Audio,
    Multimodal,
    Custom(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelRegistrationRequest {
    pub name: String,
    pub description: String,
    pub provider: String,
    pub model_type: ModelType,
    pub capabilities: Vec<String>,
    pub max_tokens: u64,
    pub supported_languages: Vec<String>,
    pub tags: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
    pub created_by: String,
    pub labels: HashMap<String, String>,
}

impl Model {
    pub fn new(request: ModelRegistrationRequest) -> Self {
        let now = chrono::Utc::now();
        Self {
            model_id: format!("model_{}", crate::utils::id::generate_id()),
            name: request.name,
            description: request.description,
            provider: request.provider,
            model_type: request.model_type,
            capabilities: request.capabilities,
            max_tokens: request.max_tokens,
            supported_languages: request.supported_languages,
            tags: request.tags,
            metadata: request.metadata,
            created_by: request.created_by,
            created_at: now,
            updated_at: now,
            is_active: true,
            labels: request.labels,
        }
    }

    pub fn update(&mut self, updates: ModelUpdateRequest) {
        if let Some(description) = updates.description {
            self.description = description;
        }
        if let Some(capabilities) = updates.capabilities {
            self.capabilities = capabilities;
        }
        if let Some(tags) = updates.tags {
            self.tags = tags;
        }
        if let Some(metadata) = updates.metadata {
            self.metadata = metadata;
        }
        if let Some(labels) = updates.labels {
            self.labels = labels;
        }
        if let Some(is_active) = updates.is_active {
            self.is_active = is_active;
        }
        if let Some(max_tokens) = updates.max_tokens {
            self.max_tokens = max_tokens;
        }
        self.updated_at = chrono::Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelUpdateRequest {
    pub description: Option<String>,
    pub capabilities: Option<Vec<String>>,
    pub max_tokens: Option<u64>,
    pub tags: Option<HashMap<String, String>>,
    pub metadata: Option<HashMap<String, String>>,
    pub is_active: Option<bool>,
    pub labels: Option<HashMap<String, String>>,
}

impl Default for ModelUpdateRequest {
    fn default() -> Self {
        Self {
            description: None,
            capabilities: None,
            max_tokens: None,
            tags: None,
            metadata: None,
            is_active: None,
            labels: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn test_model_creation() {
        let request = ModelRegistrationRequest {
            name: "Test Model".to_string(),
            description: "A test model".to_string(),
            provider: "openai".to_string(),
            model_type: ModelType::Llm,
            capabilities: vec!["chat".to_string(), "completion".to_string()],
            max_tokens: 4096,
            supported_languages: vec!["en".to_string(), "zh".to_string()],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
            labels: HashMap::new(),
        };

        let model = Model::new(request);
        assert!(model.model_id.starts_with("model_"));
        assert_eq!(model.name, "Test Model");
        assert_eq!(model.provider, "openai");
        assert_eq!(model.model_type, ModelType::Llm);
        assert_eq!(model.capabilities.len(), 2);
        assert!(model.is_active);
    }

    #[test]
    fn test_model_update() {
        let request = ModelRegistrationRequest {
            name: "Test Model".to_string(),
            description: "A test model".to_string(),
            provider: "openai".to_string(),
            model_type: ModelType::Llm,
            capabilities: vec!["chat".to_string()],
            max_tokens: 4096,
            supported_languages: vec!["en".to_string()],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
            labels: HashMap::new(),
        };

        let mut model = Model::new(request);
        let original_updated_at = model.updated_at;

        std::thread::sleep(std::time::Duration::from_millis(10));

        let mut updates = ModelUpdateRequest::default();
        updates.description = Some("Updated description".to_string());
        updates.is_active = Some(false);
        updates.max_tokens = Some(8192);

        model.update(updates);

        assert_eq!(model.description, "Updated description");
        assert!(!model.is_active);
        assert_eq!(model.max_tokens, 8192);
        assert!(model.updated_at > original_updated_at);
    }

    #[test]
    fn test_model_type_serialization() {
        let types = vec![
            ModelType::Llm,
            ModelType::Embedding,
            ModelType::Image,
            ModelType::Audio,
            ModelType::Multimodal,
            ModelType::Custom("custom_type".to_string()),
        ];

        for model_type in types {
            let json = serde_json::to_string(&model_type).unwrap();
            let deserialized: ModelType = serde_json::from_str(&json).unwrap();
            assert_eq!(model_type, deserialized);
        }
    }
}
