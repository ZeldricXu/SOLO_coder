use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::DateTime;
use chrono::Utc;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum TemplateKind {
    Rust,
    TypeScript,
    Python,
    Go,
    Java,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ParamType {
    String,
    Boolean,
    Number,
    Choice,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectTemplate {
    pub id: Uuid,
    pub name: String,
    pub description: String,
    pub kind: TemplateKind,
    pub file_templates: Vec<FileTemplate>,
    pub parameters: Vec<TemplateParameter>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileTemplate {
    pub path: String,
    pub content_template: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TemplateParameter {
    pub name: String,
    pub display_name: String,
    pub description: String,
    pub param_type: ParamType,
    pub default_value: Option<serde_json::Value>,
    pub required: bool,
    pub choices: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InteractiveQuestion {
    pub id: String,
    pub text: String,
    pub param_name: String,
    pub param_type: ParamType,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GenerationRequest {
    pub template_id: Uuid,
    pub project_name: String,
    pub parameters: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeneratedFile {
    pub path: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GenerationResult {
    pub project_name: String,
    pub files: Vec<GeneratedFile>,
}
