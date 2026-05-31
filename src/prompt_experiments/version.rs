use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::id::generate_id;
use crate::prompt_experiments::prompt::PromptContent;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VersionBumpType {
    Major,
    Minor,
    Patch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VersionStatus {
    Draft,
    Testing,
    Production,
    Deprecated,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SemanticVersion {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PromptVersion {
    pub version_id: String,
    pub prompt_id: String,
    pub version: SemanticVersion,
    pub version_string: String,
    pub content: PromptContent,
    pub status: VersionStatus,
    pub change_log: String,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub is_latest: bool,
    pub evaluation_scores: HashMap<String, f64>,
}

impl SemanticVersion {
    pub fn new(major: u32, minor: u32, patch: u32) -> Self {
        Self { major, minor, patch }
    }

    pub fn parse(version_str: &str) -> Result<Self, AppError> {
        let parts: Vec<&str> = version_str.split('.').collect();
        if parts.len() != 3 {
            return Err(AppError::Validation(format!(
                "Invalid version format: {}. Expected MAJOR.MINOR.PATCH",
                version_str
            )));
        }

        let major = parts[0].parse::<u32>()
            .map_err(|_| AppError::Validation(format!("Invalid major version: {}", parts[0])))?;
        let minor = parts[1].parse::<u32>()
            .map_err(|_| AppError::Validation(format!("Invalid minor version: {}", parts[1])))?;
        let patch = parts[2].parse::<u32>()
            .map_err(|_| AppError::Validation(format!("Invalid patch version: {}", parts[2])))?;

        Ok(Self { major, minor, patch })
    }

    pub fn bump(&self, bump_type: VersionBumpType) -> Self {
        match bump_type {
            VersionBumpType::Major => Self {
                major: self.major + 1,
                minor: 0,
                patch: 0,
            },
            VersionBumpType::Minor => Self {
                major: self.major,
                minor: self.minor + 1,
                patch: 0,
            },
            VersionBumpType::Patch => Self {
                major: self.major,
                minor: self.minor,
                patch: self.patch + 1,
            },
        }
    }

    pub fn to_string(&self) -> String {
        format!("{}.{}.{}", self.major, self.minor, self.patch)
    }
}

impl std::cmp::PartialOrd for SemanticVersion {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl std::cmp::Ord for SemanticVersion {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.major.cmp(&other.major)
            .then_with(|| self.minor.cmp(&other.minor))
            .then_with(|| self.patch.cmp(&other.patch))
    }
}

impl PromptVersion {
    pub fn new(
        prompt_id: String,
        version: SemanticVersion,
        content: PromptContent,
        change_log: String,
        created_by: String,
    ) -> Self {
        let version_string = version.to_string();
        Self {
            version_id: generate_id("ver"),
            prompt_id,
            version,
            version_string,
            content,
            status: VersionStatus::Draft,
            change_log,
            created_by,
            created_at: Utc::now(),
            is_latest: true,
            evaluation_scores: HashMap::new(),
        }
    }

    pub fn bump(
        &self,
        bump_type: VersionBumpType,
        new_content: PromptContent,
        change_log: String,
        created_by: String,
    ) -> Self {
        let new_version = self.version.bump(bump_type);
        Self::new(
            self.prompt_id.clone(),
            new_version,
            new_content,
            change_log,
            created_by,
        )
    }

    pub fn set_status(&mut self, status: VersionStatus) {
        self.status = status;
    }

    pub fn add_evaluation_score(&mut self, metric: String, score: f64) {
        self.evaluation_scores.insert(metric, score);
    }

    pub fn get_evaluation_score(&self, metric: &str) -> Option<f64> {
        self.evaluation_scores.get(metric).copied()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prompt_experiments::prompt::PromptContent;

    #[test]
    fn test_semantic_version_parsing() {
        let v = SemanticVersion::parse("1.2.3").unwrap();
        assert_eq!(v.major, 1);
        assert_eq!(v.minor, 2);
        assert_eq!(v.patch, 3);
        assert_eq!(v.to_string(), "1.2.3");
    }

    #[test]
    fn test_semantic_version_bump() {
        let v = SemanticVersion::new(1, 2, 3);
        
        let bumped = v.bump(VersionBumpType::Patch);
        assert_eq!(bumped.to_string(), "1.2.4");
        
        let bumped = v.bump(VersionBumpType::Minor);
        assert_eq!(bumped.to_string(), "1.3.0");
        
        let bumped = v.bump(VersionBumpType::Major);
        assert_eq!(bumped.to_string(), "2.0.0");
    }

    #[test]
    fn test_semantic_version_comparison() {
        let v1 = SemanticVersion::parse("1.0.0").unwrap();
        let v2 = SemanticVersion::parse("1.1.0").unwrap();
        let v3 = SemanticVersion::parse("2.0.0").unwrap();
        
        assert!(v1 < v2);
        assert!(v2 < v3);
        assert!(v1 < v3);
    }

    #[test]
    fn test_prompt_version_creation() {
        let content = PromptContent {
            text: "Test prompt".to_string(),
            variables: vec![],
            placeholders: HashMap::new(),
        };

        let version = SemanticVersion::new(1, 0, 0);
        let pv = PromptVersion::new(
            "prompt_123".to_string(),
            version,
            content,
            "Initial version".to_string(),
            "test_user".to_string(),
        );

        assert!(pv.version_id.starts_with("ver_"));
        assert_eq!(pv.version_string, "1.0.0");
        assert_eq!(pv.status, VersionStatus::Draft);
        assert!(pv.is_latest);
    }

    #[test]
    fn test_prompt_version_bump() {
        let content = PromptContent {
            text: "Test prompt v1".to_string(),
            variables: vec![],
            placeholders: HashMap::new(),
        };

        let version = SemanticVersion::new(1, 0, 0);
        let pv1 = PromptVersion::new(
            "prompt_123".to_string(),
            version,
            content,
            "Initial version".to_string(),
            "test_user".to_string(),
        );

        let new_content = PromptContent {
            text: "Test prompt v2".to_string(),
            variables: vec![],
            placeholders: HashMap::new(),
        };

        let pv2 = pv1.bump(
            VersionBumpType::Minor,
            new_content,
            "Added new features".to_string(),
            "test_user".to_string(),
        );

        assert_eq!(pv2.version_string, "1.1.0");
        assert_eq!(pv2.change_log, "Added new features");
    }
}
