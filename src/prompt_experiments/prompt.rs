use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::id::generate_id;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PromptType {
    System,
    User,
    Assistant,
    ChatTemplate,
    FewShot,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptContent {
    pub text: String,
    pub variables: Vec<String>,
    pub placeholders: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Prompt {
    pub prompt_id: String,
    pub name: String,
    pub description: String,
    pub prompt_type: PromptType,
    pub content: PromptContent,
    pub tags: Vec<String>,
    pub metadata: HashMap<String, String>,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptRegistrationRequest {
    pub name: String,
    pub description: String,
    pub prompt_type: PromptType,
    pub content: PromptContent,
    pub tags: Vec<String>,
    pub metadata: HashMap<String, String>,
    pub created_by: String,
}

impl Prompt {
    pub fn new(request: PromptRegistrationRequest) -> Result<Self, AppError> {
        if request.name.is_empty() {
            return Err(AppError::Validation("Prompt name cannot be empty".to_string()));
        }
        if request.content.text.is_empty() {
            return Err(AppError::Validation("Prompt content cannot be empty".to_string()));
        }
        
        let now = Utc::now();
        Ok(Self {
            prompt_id: generate_id("prompt"),
            name: request.name,
            description: request.description,
            prompt_type: request.prompt_type,
            content: request.content,
            tags: request.tags,
            metadata: request.metadata,
            created_by: request.created_by,
            created_at: now,
            updated_at: now,
        })
    }

    pub fn render(&self, variables: &HashMap<String, String>) -> Result<String, AppError> {
        let mut rendered = self.content.text.clone();
        
        for var in &self.content.variables {
            let placeholder = format!("{{{}}}", var);
            let value = variables.get(var)
                .ok_or_else(|| AppError::Validation(format!("Missing variable: {}", var)))?;
            rendered = rendered.replace(&placeholder, value);
        }
        
        for (key, default) in &self.content.placeholders {
            let placeholder = format!("{{{}}}", key);
            if !rendered.contains(&placeholder) {
                continue;
            }
            let value = variables.get(key).unwrap_or(default);
            rendered = rendered.replace(&placeholder, value);
        }
        
        Ok(rendered)
    }

    pub fn update_content(&mut self, new_content: PromptContent) {
        self.content = new_content;
        self.updated_at = Utc::now();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_prompt_creation() {
        let content = PromptContent {
            text: "Hello, {name}! Welcome to {platform}.".to_string(),
            variables: vec!["name".to_string()],
            placeholders: vec![("platform".to_string(), "our platform".to_string())]
                .into_iter().collect(),
        };

        let request = PromptRegistrationRequest {
            name: "Greeting Prompt".to_string(),
            description: "A friendly greeting prompt".to_string(),
            prompt_type: PromptType::User,
            content,
            tags: vec!["greeting".to_string()],
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
        };

        let prompt = Prompt::new(request).unwrap();
        assert!(prompt.prompt_id.starts_with("prompt_"));
        assert_eq!(prompt.name, "Greeting Prompt");
    }

    #[test]
    fn test_prompt_rendering() {
        let content = PromptContent {
            text: "Hello, {name}! Welcome to {platform}.".to_string(),
            variables: vec!["name".to_string()],
            placeholders: vec![("platform".to_string(), "our platform".to_string())]
                .into_iter().collect(),
        };

        let request = PromptRegistrationRequest {
            name: "Test".to_string(),
            description: "Test".to_string(),
            prompt_type: PromptType::User,
            content,
            tags: vec![],
            metadata: HashMap::new(),
            created_by: "test".to_string(),
        };

        let prompt = Prompt::new(request).unwrap();
        
        let mut vars = HashMap::new();
        vars.insert("name".to_string(), "Alice".to_string());
        
        let rendered = prompt.render(&vars).unwrap();
        assert_eq!(rendered, "Hello, Alice! Welcome to our platform.");
        
        vars.insert("platform".to_string(), "Acme Corp".to_string());
        let rendered = prompt.render(&vars).unwrap();
        assert_eq!(rendered, "Hello, Alice! Welcome to Acme Corp.");
    }

    #[test]
    fn test_prompt_rendering_missing_variable() {
        let content = PromptContent {
            text: "Hello, {name}!".to_string(),
            variables: vec!["name".to_string()],
            placeholders: HashMap::new(),
        };

        let request = PromptRegistrationRequest {
            name: "Test".to_string(),
            description: "Test".to_string(),
            prompt_type: PromptType::User,
            content,
            tags: vec![],
            metadata: HashMap::new(),
            created_by: "test".to_string(),
        };

        let prompt = Prompt::new(request).unwrap();
        let vars = HashMap::new();
        
        let result = prompt.render(&vars);
        assert!(result.is_err());
    }
}
