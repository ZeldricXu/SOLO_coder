use serde::{de, Deserialize, Deserializer, Serialize};
use std::path::{Path, PathBuf};

use crate::errors::{AppError, AppResult, ErrorContext, ErrorKind};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RepositoryConfig {
    #[serde(deserialize_with = "validate_non_empty_string")]
    pub name: String,
    
    #[serde(deserialize_with = "validate_git_url")]
    pub url: String,
    
    #[serde(deserialize_with = "validate_non_empty_string")]
    pub local_path: String,
    
    #[serde(default = "default_branch")]
    pub default_branch: String,
}

fn validate_non_empty_string<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let s = String::deserialize(deserializer)?;
    if s.trim().is_empty() {
        return Err(de::Error::custom("字符串不能为空"));
    }
    Ok(s)
}

fn validate_git_url<'de, D>(deserializer: D) -> Result<String, D::Error>
where
    D: Deserializer<'de>,
{
    let s = String::deserialize(deserializer)?;
    if s.trim().is_empty() {
        return Err(de::Error::custom("Git URL不能为空"));
    }
    
    let is_valid = s.starts_with("git@")
        || s.starts_with("http://")
        || s.starts_with("https://")
        || s.ends_with(".git");
    
    if !is_valid {
        return Err(de::Error::custom(format!(
            "Git URL格式无效: '{}'. 应为 git@host:path.git, http://host/path.git 或 https://host/path.git 格式",
            s
        )));
    }
    
    Ok(s)
}

fn default_branch() -> String {
    "main".to_string()
}

impl RepositoryConfig {
    pub fn validate(&self) -> AppResult<()> {
        let mut errors = Vec::new();

        if self.name.trim().is_empty() {
            errors.push(
                AppError::validation("仓库名称不能为空")
                    .with_context(ErrorContext::new().with_operation("validate_repo")),
            );
        }

        if self.url.trim().is_empty() {
            errors.push(
                AppError::validation("Git URL不能为空")
                    .with_context(ErrorContext::new().with_operation("validate_repo")),
            );
        } else {
            let is_valid = self.url.starts_with("git@")
                || self.url.starts_with("http://")
                || self.url.starts_with("https://")
                || self.url.ends_with(".git");
            
            if !is_valid {
                errors.push(
                    AppError::validation(format!("Git URL格式无效: '{}'", self.url))
                        .with_context(ErrorContext::new().with_operation("validate_repo")),
                );
            }
        }

        if self.local_path.trim().is_empty() {
            errors.push(
                AppError::validation("本地路径不能为空")
                    .with_context(ErrorContext::new().with_operation("validate_repo")),
            );
        }

        if self.default_branch.trim().is_empty() {
            errors.push(
                AppError::validation("默认分支不能为空")
                    .with_context(ErrorContext::new().with_operation("validate_repo")),
            );
        }

        if errors.is_empty() {
            Ok(())
        } else if errors.len() == 1 {
            Err(errors.into_iter().next().unwrap())
        } else {
            let combined = errors
                .iter()
                .map(|e| e.to_string())
                .collect::<Vec<_>>()
                .join("; ");
            Err(AppError::validation(combined))
        }
    }

    pub fn validate_local_path_exists(&self) -> AppResult<()> {
        let path = Path::new(&self.local_path);
        
        if !path.exists() {
            return Err(AppError::not_found(format!("路径不存在: {}", self.local_path))
                .with_context(ErrorContext::new().with_repository(&self.name)));
        }

        let git_dir = path.join(".git");
        if !git_dir.exists() {
            return Err(AppError::validation(format!(
                "不是一个Git仓库 (缺少 .git 目录): {}",
                self.local_path
            ))
            .with_context(ErrorContext::new().with_repository(&self.name)));
        }

        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncSettings {
    #[serde(default = "default_auto_prune")]
    pub auto_prune: bool,
    
    #[serde(default = "default_fetch_depth")]
    pub fetch_depth: u32,
}

fn default_auto_prune() -> bool {
    false
}

fn default_fetch_depth() -> u32 {
    0
}

impl Default for SyncSettings {
    fn default() -> Self {
        SyncSettings {
            auto_prune: false,
            fetch_depth: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RepositoryGroup {
    #[serde(deserialize_with = "validate_non_empty_string")]
    pub group_name: String,
    
    #[serde(default)]
    pub repositories: Vec<RepositoryConfig>,
    
    #[serde(default)]
    pub sync_settings: SyncSettings,
}

impl RepositoryGroup {
    pub fn validate(&self) -> AppResult<()> {
        if self.group_name.trim().is_empty() {
            return Err(
                AppError::validation("仓库组名称不能为空")
                    .with_context(ErrorContext::new().with_operation("validate_group")),
            );
        }

        if self.repositories.is_empty() {
            return Err(AppError::validation(format!(
                "仓库组 '{}' 没有配置任何仓库",
                self.group_name
            ))
            .with_context(ErrorContext::new().with_operation("validate_group")));
        }

        for (idx, repo) in self.repositories.iter().enumerate() {
            if let Err(e) = repo.validate() {
                return Err(e.with_context(
                    ErrorContext::new()
                        .with_operation("validate_group")
                        .with_additional(&format!("repositories[{}]", idx))
                    ),
                );
            }
        }

        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MultiGitConfig {
    #[serde(default)]
    pub groups: Vec<RepositoryGroup>,
}

impl MultiGitConfig {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_file<P: AsRef<Path>>(path: P) -> AppResult<Self> {
        let path = path.as_ref();
        
        if !path.exists() {
            return Err(AppError::not_found(format!(
                "配置文件不存在: {}",
                path.display()
            )));
        }

        let content = std::fs::read_to_string(path)
            .with_context(|| {
                ErrorContext::new().with_operation("read_config_file")
            })?;
        
        let config: MultiGitConfig = serde_yaml::from_str(&content)
            .map_err(|e| {
                AppError::parse(format!("YAML解析失败: {}", e))
                    .with_context(ErrorContext::new().with_operation("parse_config"))
            })?;

        Ok(config)
    }

    pub fn save_to_file<P: AsRef<Path>>(&self, path: P) -> AppResult<()> {
        let content = serde_yaml::to_string(self)
            .map_err(|e| {
                AppError::internal(format!("YAML序列化失败: {}", e))
                    .with_context(ErrorContext::new().with_operation("serialize_config"))
            })?;
        
        std::fs::write(path, content)?;
        
        Ok(())
    }

    pub fn validate(&self) -> AppResult<()> {
        if self.groups.is_empty() {
            return Err(
                AppError::validation("没有配置任何仓库组")
                    .with_context(ErrorContext::new().with_operation("validate_config")),
            );
        }

        for (idx, group) in self.groups.iter().enumerate() {
            if let Err(e) = group.validate() {
                return Err(e.with_context(
                    ErrorContext::new()
                        .with_operation("validate_config")
                        .with_additional(&format!("groups[{}]", idx)
                    ),
                ));
            }
        }

        Ok(())
    }

    pub fn validate_with_path_check(&self) -> AppResult<()> {
        self.validate()?;

        for group in &self.groups {
            for repo in &group.repositories {
                repo.validate_local_path_exists()?;
            }
        }

        Ok(())
    }

    pub fn get_group(&self, group_name: &str) -> Option<&RepositoryGroup> {
        self.groups.iter().find(|g| g.group_name == group_name)
    }

    pub fn list_groups(&self) -> Vec<&str> {
        self.groups.iter().map(|g| g.group_name.as_str()).collect()
    }

    pub fn get_all_repositories(&self) -> Vec<(String, &RepositoryConfig)> {
        self.groups
            .iter()
            .flat_map(|group| {
                group
                    .repositories
                    .iter()
                    .map(move |repo| (group.group_name.clone(), repo))
            })
            .collect()
    }
}

pub struct ConfigManager {
    config: MultiGitConfig,
    config_path: Option<PathBuf>,
}

impl ConfigManager {
    pub fn new() -> Self {
        ConfigManager {
            config: MultiGitConfig::new(),
            config_path: None,
        }
    }

    pub fn load<P: AsRef<Path>>(config_path: P) -> AppResult<Self> {
        let path = config_path.as_ref();
        let config = MultiGitConfig::from_file(path)?;
        
        if let Err(e) = config.validate() {
            eprintln!("配置文件验证发现以下问题:");
            eprintln!("  - {}", e);
            return Err(e);
        }

        Ok(ConfigManager {
            config,
            config_path: Some(path.to_path_buf()),
        })
    }

    pub fn load_without_validation<P: AsRef<Path>>(config_path: P) -> AppResult<Self> {
        let path = config_path.as_ref();
        let config = MultiGitConfig::from_file(path)?;

        Ok(ConfigManager {
            config,
            config_path: Some(path.to_path_buf()),
        })
    }

    pub fn load_default() -> AppResult<Self> {
        let default_paths = vec![
            PathBuf::from("multigit.yaml"),
            PathBuf::from(".multigit.yaml"),
            PathBuf::from("config/multigit.yaml"),
            dirs::config_dir()
                .map(|mut p| p.join("multigit").join("config.yaml")),
        ]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>();

        let mut last_error: Option<AppError> = None;

        for path in default_paths {
            if path.exists() {
                match Self::load(&path) {
                    Ok(manager) => return Ok(manager),
                    Err(e) => {
                        last_error = Some(e);
                    }
                }
            }
        }

        if let Some(e) = last_error {
            return Err(e);
        }

        Err(AppError::not_found("未找到默认配置文件"))
    }

    pub fn load_group(&self, group_name: &str) -> AppResult<&[RepositoryConfig]> {
        self.config
            .get_group(group_name)
            .map(|group| group.repositories.as_slice())
            .ok_or_else(|| {
                AppError::not_found(format!("仓库组不存在: {}", group_name))
                    .with_context(ErrorContext::new().with_operation("load_group"))
            })
    }

    pub fn list_groups(&self) -> Vec<&str> {
        self.config.list_groups()
    }

    pub fn get_config(&self) -> &MultiGitConfig {
        &self.config
    }

    pub fn get_config_path(&self) -> Option<&Path> {
        self.config_path.as_deref()
    }

    pub fn save(&self) -> AppResult<()> {
        if let Some(path) = &self.config_path {
            self.config.save_to_file(path)
        } else {
            Err(AppError::not_found("没有配置文件路径")
                .with_context(ErrorContext::new().with_operation("save_config")))
        }
    }

    pub fn validate_repository_paths(&self) -> AppResult<()> {
        self.config.validate_with_path_check()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_repo_validation() {
        let valid_repo = RepositoryConfig {
            name: "test-repo".to_string(),
            url: "git@github.com:org/repo.git".to_string(),
            local_path: "/tmp/test".to_string(),
            default_branch: "main".to_string(),
        };
        assert!(valid_repo.validate().is_ok());

        let invalid_repo = RepositoryConfig {
            name: "".to_string(),
            url: "invalid-url".to_string(),
            local_path: "".to_string(),
            default_branch: "".to_string(),
        };
        assert!(invalid_repo.validate().is_err());
    }

    #[test]
    fn test_git_url_validation() {
        let valid_urls = vec![
            "git@github.com:org/repo.git",
            "https://github.com/org/repo.git",
            "http://gitlab.com/org/repo.git",
            "file:///path/to/repo.git",
            "/path/to/local/repo.git",
        ];

        for url in valid_urls {
            let repo = RepositoryConfig {
                name: "test".to_string(),
                url: url.to_string(),
                local_path: "/tmp/test".to_string(),
                default_branch: "main".to_string(),
            };
            assert!(repo.validate().is_ok(), "URL should be valid: {}", url);
        }
    }
}
