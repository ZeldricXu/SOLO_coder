use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum StorageTier {
    Hot,
    Warm,
    Cold,
    Archived,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TieringRuleType {
    AgeBased,
    SizeBased,
    AccessBased,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LifecyclePolicy {
    pub policy_id: String,
    pub name: String,
    pub namespace: String,
    pub rules: Vec<TieringRule>,
    pub archive_config: ArchiveConfig,
    pub cleanup_config: CleanupConfig,
    pub enabled: bool,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TieringRule {
    pub rule_id: String,
    pub rule_type: TieringRuleType,
    pub from_tier: StorageTier,
    pub to_tier: StorageTier,
    pub condition: TieringCondition,
    pub priority: u32,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TieringCondition {
    pub max_age_days: Option<u32>,
    pub max_size_bytes: Option<u64>,
    pub min_access_days: Option<u32>,
    pub custom_expression: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArchiveConfig {
    pub enabled: bool,
    pub archive_after_days: u32,
    pub storage_path: String,
    pub compression: ArchiveCompression,
    pub encryption_enabled: bool,
    pub retention_days: u32,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ArchiveCompression {
    None,
    Gzip,
    Lz4,
    Zstd,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanupConfig {
    pub enabled: bool,
    pub delete_after_days: u32,
    pub delete_archived_after_days: u32,
    pub max_versions: u32,
    pub min_free_space_percent: u32,
}

impl LifecyclePolicy {
    pub fn new(name: impl Into<String>, namespace: impl Into<String>) -> Self {
        Self {
            policy_id: format!("pol_{}", uuid::Uuid::new_v4()),
            name: name.into(),
            namespace: namespace.into(),
            rules: Vec::new(),
            archive_config: ArchiveConfig::default(),
            cleanup_config: CleanupConfig::default(),
            enabled: true,
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
        }
    }

    pub fn with_rule(mut self, rule: TieringRule) -> Self {
        self.rules.push(rule);
        self.updated_at = chrono::Utc::now();
        self
    }

    pub fn with_archive_config(mut self, config: ArchiveConfig) -> Self {
        self.archive_config = config;
        self.updated_at = chrono::Utc::now();
        self
    }

    pub fn with_cleanup_config(mut self, config: CleanupConfig) -> Self {
        self.cleanup_config = config;
        self.updated_at = chrono::Utc::now();
        self
    }

    pub fn get_applicable_rule(&self, metadata: &DataMetadata) -> Option<&TieringRule> {
        let mut applicable_rules: Vec<&TieringRule> = self
            .rules
            .iter()
            .filter(|r| r.enabled && self.rule_matches(r, metadata))
            .collect();

        applicable_rules.sort_by(|a, b| a.priority.cmp(&b.priority));
        applicable_rules.first().cloned()
    }

    fn rule_matches(&self, rule: &TieringRule, metadata: &DataMetadata) -> bool {
        if rule.from_tier != metadata.current_tier {
            return false;
        }

        self.condition_matches(&rule.condition, metadata)
    }

    fn condition_matches(&self, condition: &TieringCondition, metadata: &DataMetadata) -> bool {
        if let Some(max_age) = condition.max_age_days {
            if metadata.age_days() <= max_age {
                return false;
            }
        }

        if let Some(max_size) = condition.max_size_bytes {
            if metadata.size_bytes <= max_size {
                return false;
            }
        }

        if let Some(min_access) = condition.min_access_days {
            if metadata.days_since_last_access() < min_access {
                return false;
            }
        }

        true
    }

    pub fn should_archive(&self, metadata: &DataMetadata) -> bool {
        if !self.archive_config.enabled {
            return false;
        }

        metadata.age_days() >= self.archive_config.archive_after_days
    }

    pub fn should_delete(&self, metadata: &DataMetadata) -> bool {
        if !self.cleanup_config.enabled {
            return false;
        }

        if metadata.current_tier == StorageTier::Archived {
            return metadata.age_days() >= self.cleanup_config.delete_archived_after_days;
        }

        metadata.age_days() >= self.cleanup_config.delete_after_days
    }
}

impl Default for ArchiveConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            archive_after_days: 90,
            storage_path: "./archive".to_string(),
            compression: ArchiveCompression::Zstd,
            encryption_enabled: false,
            retention_days: 365,
        }
    }
}

impl Default for CleanupConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            delete_after_days: 365,
            delete_archived_after_days: 730,
            max_versions: 10,
            min_free_space_percent: 10,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataMetadata {
    pub data_id: String,
    pub table_name: String,
    pub partition_key: Option<String>,
    pub current_tier: StorageTier,
    pub size_bytes: u64,
    pub row_count: u64,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub last_accessed_at: Option<chrono::DateTime<chrono::Utc>>,
    pub version: u32,
    pub tags: HashMap<String, String>,
    pub tier_history: Vec<TierTransition>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TierTransition {
    pub from_tier: StorageTier,
    pub to_tier: StorageTier,
    pub reason: String,
    pub transitioned_at: chrono::DateTime<chrono::Utc>,
}

impl DataMetadata {
    pub fn new(data_id: impl Into<String>, table_name: impl Into<String>, size_bytes: u64) -> Self {
        Self {
            data_id: data_id.into(),
            table_name: table_name.into(),
            partition_key: None,
            current_tier: StorageTier::Hot,
            size_bytes,
            row_count: 0,
            created_at: chrono::Utc::now(),
            last_accessed_at: None,
            version: 1,
            tags: HashMap::new(),
            tier_history: Vec::new(),
        }
    }

    pub fn age_days(&self) -> u32 {
        let now = chrono::Utc::now();
        let duration = now.signed_duration_since(self.created_at);
        duration.num_days().max(0) as u32
    }

    pub fn days_since_last_access(&self) -> u32 {
        match self.last_accessed_at {
            Some(last) => {
                let now = chrono::Utc::now();
                let duration = now.signed_duration_since(last);
                duration.num_days().max(0) as u32
            }
            None => self.age_days(),
        }
    }

    pub fn transition_tier(&mut self, to_tier: StorageTier, reason: impl Into<String>) {
        let transition = TierTransition {
            from_tier: self.current_tier.clone(),
            to_tier: to_tier.clone(),
            reason: reason.into(),
            transitioned_at: chrono::Utc::now(),
        };

        self.tier_history.push(transition);
        self.current_tier = to_tier;
    }

    pub fn access(&mut self) {
        self.last_accessed_at = Some(chrono::Utc::now());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_policy_creation() {
        let policy = LifecyclePolicy::new("test_policy", "default");
        assert!(policy.enabled);
        assert_eq!(policy.rules.len(), 0);
    }

    #[test]
    fn test_data_metadata_age() {
        let mut metadata = DataMetadata::new("d1", "orders", 1024);
        assert_eq!(metadata.age_days(), 0);
        assert_eq!(metadata.current_tier, StorageTier::Hot);
    }

    #[test]
    fn test_tier_transition() {
        let mut metadata = DataMetadata::new("d1", "orders", 1024);
        metadata.transition_tier(StorageTier::Warm, "age based");

        assert_eq!(metadata.current_tier, StorageTier::Warm);
        assert_eq!(metadata.tier_history.len(), 1);
        assert_eq!(metadata.tier_history[0].from_tier, StorageTier::Hot);
        assert_eq!(metadata.tier_history[0].to_tier, StorageTier::Warm);
    }

    #[test]
    fn test_should_archive() {
        let policy = LifecyclePolicy::new("test", "default")
            .with_archive_config(ArchiveConfig {
                enabled: true,
                archive_after_days: 30,
                ..Default::default()
            });

        let mut metadata = DataMetadata::new("d1", "orders", 1024);
        assert!(!policy.should_archive(&metadata));

        metadata.created_at = chrono::Utc::now() - chrono::Duration::days(60);
        assert!(policy.should_archive(&metadata));
    }
}
