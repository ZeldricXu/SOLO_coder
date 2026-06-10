use std::fs;
use std::path::Path;

use crate::scene::SceneConfig;

#[derive(Debug)]
pub enum SceneLoadError {
    IoError(std::io::Error),
    ParseError(String),
}

impl From<std::io::Error> for SceneLoadError {
    fn from(err: std::io::Error) -> Self {
        SceneLoadError::IoError(err)
    }
}

impl std::fmt::Display for SceneLoadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SceneLoadError::IoError(err) => write!(f, "IO error: {}", err),
            SceneLoadError::ParseError(err) => write!(f, "Parse error: {}", err),
        }
    }
}

impl std::error::Error for SceneLoadError {}

pub fn load_json(path: impl AsRef<Path>) -> Result<SceneConfig, SceneLoadError> {
    let content = fs::read_to_string(path)?;
    serde_json::from_str(&content).map_err(|e| SceneLoadError::ParseError(e.to_string()))
}

pub fn save_json(path: impl AsRef<Path>, config: &SceneConfig) -> Result<(), SceneLoadError> {
    let content = serde_json::to_string_pretty(config)
        .map_err(|e| SceneLoadError::ParseError(e.to_string()))?;
    fs::write(path, content)?;
    Ok(())
}

pub fn load_yaml(path: impl AsRef<Path>) -> Result<SceneConfig, SceneLoadError> {
    let content = fs::read_to_string(path)?;
    serde_yaml::from_str(&content).map_err(|e| SceneLoadError::ParseError(e.to_string()))
}

pub fn save_yaml(path: impl AsRef<Path>, config: &SceneConfig) -> Result<(), SceneLoadError> {
    let content = serde_yaml::to_string(config)
        .map_err(|e| SceneLoadError::ParseError(e.to_string()))?;
    fs::write(path, content)?;
    Ok(())
}

pub fn from_json_str(content: &str) -> Result<SceneConfig, SceneLoadError> {
    serde_json::from_str(content).map_err(|e| SceneLoadError::ParseError(e.to_string()))
}

pub fn to_json_str(config: &SceneConfig) -> Result<String, SceneLoadError> {
    serde_json::to_string_pretty(config).map_err(|e| SceneLoadError::ParseError(e.to_string()))
}
