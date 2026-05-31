pub mod domain;
pub mod engine;
pub mod writer;
pub mod service;

pub use domain::{ScaffoldConfig, GeneratedFile, ScaffoldResult, ProjectType, Language};
pub use service::ScaffoldService;

use crate::utils::error::Result;
use std::path::Path;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct CreateScaffoldRequest {
    pub config: ScaffoldConfig,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct TemplateInfo {
    pub name: String,
    pub description: String,
    pub language: String,
}

pub struct ScaffoldGenerator {
    inner: ScaffoldService,
}

impl ScaffoldGenerator {
    pub fn new() -> Result<Self> {
        Ok(Self { inner: ScaffoldService::new() })
    }

    pub fn list_templates(&self) -> Vec<TemplateInfo> {
        vec![
            TemplateInfo {
                name: "rust-lib".to_string(),
                description: "Rust library project".to_string(),
                language: "rust".to_string(),
            },
            TemplateInfo {
                name: "rust-service".to_string(),
                description: "Rust service project".to_string(),
                language: "rust".to_string(),
            },
            TemplateInfo {
                name: "rust-cli".to_string(),
                description: "Rust CLI project".to_string(),
                language: "rust".to_string(),
            },
        ]
    }

    pub async fn generate(
        &self,
        config: &ScaffoldConfig,
        _output_dir: Option<&Path>,
    ) -> Result<ScaffoldResult> {
        let dir = std::env::temp_dir().join(&config.project_name);
        self.inner.generate(config, &dir).await
    }

    pub async fn interactive_generate(
        &self,
        output_dir: &Path,
    ) -> Result<ScaffoldResult> {
        self.inner.interactive_generate(output_dir).await
    }
}

impl Clone for ScaffoldGenerator {
    fn clone(&self) -> Self {
        Self { inner: self.inner.clone() }
    }
}
