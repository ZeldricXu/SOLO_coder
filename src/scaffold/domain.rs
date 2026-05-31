use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Language {
    Rust,
    Python,
    Go,
    JavaScript,
    TypeScript,
}

impl Default for Language {
    fn default() -> Self {
        Language::Rust
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProjectType {
    Library,
    Service,
    Cli,
    Web,
}

impl Default for ProjectType {
    fn default() -> Self {
        ProjectType::Library
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScaffoldConfig {
    pub project_name: String,
    pub language: Language,
    pub project_type: ProjectType,
    pub author: String,
    pub description: String,
    pub version: String,
    #[serde(default)]
    pub use_git: bool,
    #[serde(default)]
    pub include_license: bool,
    pub extra_vars: HashMap<String, String>,
}

impl Default for ScaffoldConfig {
    fn default() -> Self {
        Self {
            project_name: "my_project".to_string(),
            language: Language::default(),
            project_type: ProjectType::default(),
            author: "".to_string(),
            description: "".to_string(),
            version: "0.1.0".to_string(),
            use_git: true,
            include_license: true,
            extra_vars: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct GeneratedFile {
    pub path: PathBuf,
    pub content: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ScaffoldResult {
    pub project_name: String,
    pub output_dir: PathBuf,
    pub files: Vec<GeneratedFile>,
}
