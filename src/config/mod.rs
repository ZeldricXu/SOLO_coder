use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Language {
    Chinese,
    English,
}

impl Default for Language {
    fn default() -> Self {
        Language::Chinese
    }
}

impl Language {
    pub fn to_string(&self) -> &'static str {
        match self {
            Language::Chinese => "zh-CN",
            Language::English => "en-US",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub language: Language,
    pub theme_preset: String,
    pub notebook_path: Option<PathBuf>,
    pub auto_check_updates: bool,
    pub last_check_update: Option<u64>,
    pub skipped_version: Option<String>,
    pub window_size: Option<(f32, f32)>,
    pub window_position: Option<(f32, f32)>,
    pub sidebar_width: f32,
    pub right_panel_width: f32,
    pub show_backlinks: bool,
    pub show_graph: bool,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            language: Language::default(),
            theme_preset: "dark".to_string(),
            notebook_path: None,
            auto_check_updates: true,
            last_check_update: None,
            skipped_version: None,
            window_size: None,
            window_position: None,
            sidebar_width: 250.0,
            right_panel_width: 300.0,
            show_backlinks: true,
            show_graph: false,
        }
    }
}

impl AppConfig {
    pub fn load(config_path: &Path) -> Result<Self, Box<dyn std::error::Error>> {
        if config_path.exists() {
            let content = fs::read_to_string(config_path)?;
            let config: AppConfig = toml::from_str(&content)?;
            Ok(config)
        } else {
            Ok(Self::default())
        }
    }

    pub fn save(&self, config_path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(parent) = config_path.parent() {
            fs::create_dir_all(parent)?;
        }
        let content = toml::to_string_pretty(self)?;
        fs::write(config_path, content)?;
        Ok(())
    }

    pub fn default_notebook_path() -> PathBuf {
        dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("Notebook")
    }
}
