use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelVersion {
    pub version_id: String,
    pub model_id: String,
    pub version: String,
    pub semantic_version: SemanticVersion,
    pub description: String,
    pub artifact_uri: String,
    pub checksum: String,
    pub size_bytes: u64,
    pub tags: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
    pub training_metrics: HashMap<String, f64>,
    pub validation_metrics: HashMap<String, f64>,
    pub created_by: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub is_latest: bool,
    pub is_deprecated: bool,
    pub deprecated_at: Option<chrono::DateTime<chrono::Utc>>,
    pub deprecated_by: Option<String>,
    pub deprecation_reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub struct SemanticVersion {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
    pub pre_release: Option<String>,
    pub build: Option<String>,
}

impl SemanticVersion {
    pub fn new(major: u32, minor: u32, patch: u32) -> Self {
        Self {
            major,
            minor,
            patch,
            pre_release: None,
            build: None,
        }
    }

    pub fn parse(s: &str) -> Result<Self, String> {
        let parts: Vec<&str> = s.splitn(3, '.').collect();
        if parts.len() != 3 {
            return Err("Invalid semantic version format".to_string());
        }

        let major = parts[0].parse::<u32>().map_err(|_| "Invalid major version")?;
        let minor = parts[1].parse::<u32>().map_err(|_| "Invalid minor version")?;
        
        let mut patch_part = parts[2];
        let mut pre_release = None;
        let mut build = None;

        if let Some(idx) = patch_part.find('+') {
            build = Some(patch_part[idx + 1..].to_string());
            patch_part = &patch_part[..idx];
        }
        if let Some(idx) = patch_part.find('-') {
            pre_release = Some(patch_part[idx + 1..].to_string());
            patch_part = &patch_part[..idx];
        }

        let patch = patch_part.parse::<u32>().map_err(|_| "Invalid patch version")?;

        Ok(Self {
            major,
            minor,
            patch,
            pre_release,
            build,
        })
    }

    pub fn to_string(&self) -> String {
        let mut s = format!("{}.{}.{}", self.major, self.minor, self.patch);
        if let Some(pre) = &self.pre_release {
            s.push_str(&format!("-{}", pre));
        }
        if let Some(build) = &self.build {
            s.push_str(&format!("+{}", build));
        }
        s
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionRegistrationRequest {
    pub model_id: String,
    pub version: String,
    pub description: String,
    pub artifact_uri: String,
    pub checksum: String,
    pub size_bytes: u64,
    pub tags: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
    pub training_metrics: HashMap<String, f64>,
    pub validation_metrics: HashMap<String, f64>,
    pub created_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionBumpRequest {
    pub model_id: String,
    pub bump_type: VersionBumpType,
    pub description: String,
    pub artifact_uri: String,
    pub checksum: String,
    pub size_bytes: u64,
    pub created_by: String,
    pub tags: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
    pub training_metrics: HashMap<String, f64>,
    pub validation_metrics: HashMap<String, f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VersionBumpType {
    Major,
    Minor,
    Patch,
    PreRelease,
}

impl ModelVersion {
    pub fn new(request: VersionRegistrationRequest) -> Result<Self, String> {
        let semantic_version = SemanticVersion::parse(&request.version)?;
        let now = chrono::Utc::now();
        
        Ok(Self {
            version_id: format!("ver_{}", crate::utils::id::generate_id()),
            model_id: request.model_id,
            version: request.version,
            semantic_version,
            description: request.description,
            artifact_uri: request.artifact_uri,
            checksum: request.checksum,
            size_bytes: request.size_bytes,
            tags: request.tags,
            metadata: request.metadata,
            training_metrics: request.training_metrics,
            validation_metrics: request.validation_metrics,
            created_by: request.created_by,
            created_at: now,
            is_latest: true,
            is_deprecated: false,
            deprecated_at: None,
            deprecated_by: None,
            deprecation_reason: None,
        })
    }

    pub fn bump_version(
        current: &ModelVersion,
        request: VersionBumpRequest,
    ) -> Result<Self, String> {
        let mut new_semver = current.semantic_version.clone();
        
        match request.bump_type {
            VersionBumpType::Major => {
                new_semver.major += 1;
                new_semver.minor = 0;
                new_semver.patch = 0;
                new_semver.pre_release = None;
                new_semver.build = None;
            }
            VersionBumpType::Minor => {
                new_semver.minor += 1;
                new_semver.patch = 0;
                new_semver.pre_release = None;
                new_semver.build = None;
            }
            VersionBumpType::Patch => {
                new_semver.patch += 1;
                new_semver.pre_release = None;
                new_semver.build = None;
            }
            VersionBumpType::PreRelease => {
                new_semver.pre_release = Some(format!("alpha.{}", chrono::Utc::now().timestamp()));
            }
        }

        let version_str = new_semver.to_string();
        let now = chrono::Utc::now();

        Ok(Self {
            version_id: format!("ver_{}", crate::utils::id::generate_id()),
            model_id: request.model_id,
            version: version_str,
            semantic_version: new_semver,
            description: request.description,
            artifact_uri: request.artifact_uri,
            checksum: request.checksum,
            size_bytes: request.size_bytes,
            tags: request.tags,
            metadata: request.metadata,
            training_metrics: request.training_metrics,
            validation_metrics: request.validation_metrics,
            created_by: request.created_by,
            created_at: now,
            is_latest: true,
            is_deprecated: false,
            deprecated_at: None,
            deprecated_by: None,
            deprecation_reason: None,
        })
    }

    pub fn deprecate(&mut self, reason: String, deprecated_by: String) {
        self.is_deprecated = true;
        self.deprecated_at = Some(chrono::Utc::now());
        self.deprecated_by = Some(deprecated_by);
        self.deprecation_reason = Some(reason);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn test_semantic_version_parsing() {
        let v = SemanticVersion::parse("1.2.3").unwrap();
        assert_eq!(v.major, 1);
        assert_eq!(v.minor, 2);
        assert_eq!(v.patch, 3);

        let v = SemanticVersion::parse("2.0.0-alpha.1").unwrap();
        assert_eq!(v.major, 2);
        assert_eq!(v.pre_release, Some("alpha.1".to_string()));

        let v = SemanticVersion::parse("1.0.0+build.123").unwrap();
        assert_eq!(v.build, Some("build.123".to_string()));

        let v = SemanticVersion::parse("1.0.0-beta+exp.sha.5114f85").unwrap();
        assert_eq!(v.pre_release, Some("beta".to_string()));
        assert_eq!(v.build, Some("exp.sha.5114f85".to_string()));
    }

    #[test]
    fn test_semantic_version_string() {
        let v = SemanticVersion::new(1, 2, 3);
        assert_eq!(v.to_string(), "1.2.3");

        let mut v = SemanticVersion::new(2, 0, 0);
        v.pre_release = Some("alpha.1".to_string());
        assert_eq!(v.to_string(), "2.0.0-alpha.1");
    }

    #[test]
    fn test_semantic_version_comparison() {
        let v1 = SemanticVersion::parse("1.0.0").unwrap();
        let v2 = SemanticVersion::parse("1.1.0").unwrap();
        let v3 = SemanticVersion::parse("2.0.0").unwrap();
        let v4 = SemanticVersion::parse("1.0.1").unwrap();

        assert!(v1 < v2);
        assert!(v2 < v3);
        assert!(v1 < v4);
        assert!(v4 < v2);
    }

    #[test]
    fn test_model_version_creation() {
        let request = VersionRegistrationRequest {
            model_id: "model_001".to_string(),
            version: "1.0.0".to_string(),
            description: "Initial version".to_string(),
            artifact_uri: "s3://models/model_001/1.0.0".to_string(),
            checksum: "abc123".to_string(),
            size_bytes: 1024 * 1024 * 100,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test_user".to_string(),
        };

        let version = ModelVersion::new(request).unwrap();
        assert!(version.version_id.starts_with("ver_"));
        assert_eq!(version.version, "1.0.0");
        assert!(version.is_latest);
        assert!(!version.is_deprecated);
    }

    #[test]
    fn test_version_bump() {
        let request = VersionRegistrationRequest {
            model_id: "model_001".to_string(),
            version: "1.2.3".to_string(),
            description: "".to_string(),
            artifact_uri: "".to_string(),
            checksum: "".to_string(),
            size_bytes: 0,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test".to_string(),
        };

        let current = ModelVersion::new(request).unwrap();

        let bump_request = VersionBumpRequest {
            model_id: "model_001".to_string(),
            bump_type: VersionBumpType::Minor,
            description: "Minor update".to_string(),
            artifact_uri: "s3://models/model_001/1.3.0".to_string(),
            checksum: "def456".to_string(),
            size_bytes: 1024 * 1024 * 150,
            created_by: "test".to_string(),
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
        };

        let new_version = ModelVersion::bump_version(&current, bump_request).unwrap();
        assert_eq!(new_version.version, "1.3.0");
        assert!(new_version.is_latest);
    }

    #[test]
    fn test_version_deprecation() {
        let request = VersionRegistrationRequest {
            model_id: "model_001".to_string(),
            version: "1.0.0".to_string(),
            description: "".to_string(),
            artifact_uri: "".to_string(),
            checksum: "".to_string(),
            size_bytes: 0,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test".to_string(),
        };

        let mut version = ModelVersion::new(request).unwrap();
        version.deprecate("Superseded by newer version".to_string(), "admin".to_string());

        assert!(version.is_deprecated);
        assert!(version.deprecated_at.is_some());
        assert_eq!(version.deprecated_by, Some("admin".to_string()));
        assert_eq!(version.deprecation_reason, Some("Superseded by newer version".to_string()));
    }

    #[test]
    fn test_invalid_version_parse() {
        assert!(SemanticVersion::parse("invalid").is_err());
        assert!(SemanticVersion::parse("1").is_err());
        assert!(SemanticVersion::parse("1.0").is_err());
        assert!(SemanticVersion::parse("a.b.c").is_err());
    }
}
