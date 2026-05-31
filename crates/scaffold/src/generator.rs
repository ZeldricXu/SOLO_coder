use std::collections::HashMap;
use serde_json::Value;
use anyhow::Result;
use crate::models::{ProjectTemplate, GenerationRequest, GenerationResult, GeneratedFile};

pub struct ProjectGenerator;

impl ProjectGenerator {
    pub fn generate(template: &ProjectTemplate, request: &GenerationRequest) -> Result<GenerationResult> {
        let mut files = Vec::new();

        for file_template in &template.file_templates {
            let rendered_path = Self::render_template(&file_template.path, &request.parameters);
            let rendered_content = Self::render_template(&file_template.content_template, &request.parameters);

            files.push(GeneratedFile {
                path: rendered_path,
                content: rendered_content,
            });
        }

        Ok(GenerationResult {
            project_name: request.project_name.clone(),
            files,
        })
    }

    pub fn render_template(content: &str, params: &HashMap<String, Value>) -> String {
        let mut result = content.to_string();

        for (key, value) in params {
            let value_str = match value {
                Value::String(s) => s.clone(),
                Value::Number(n) => n.to_string(),
                Value::Bool(b) => b.to_string(),
                Value::Null => String::new(),
                Value::Array(_) => value.to_string(),
                Value::Object(_) => value.to_string(),
            };

            let pattern = format!("{{{{{}}}}}", key);
            result = result.replace(&pattern, &value_str);
        }

        result
    }
}
