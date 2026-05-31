use crate::config::ClassificationConfig;
use crate::models::AppError;
use crate::utils::current_datetime;
use chrono::{DateTime, Utc};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum ClassificationLevel {
    Public = 0,
    Internal = 1,
    Confidential = 2,
    Restricted = 3,
    Secret = 4,
    TopSecret = 5,
}

impl ClassificationLevel {
    pub fn from_str(s: &str) -> Result<Self, AppError> {
        match s.to_lowercase().as_str() {
            "public" => Ok(ClassificationLevel::Public),
            "internal" => Ok(ClassificationLevel::Internal),
            "confidential" => Ok(ClassificationLevel::Confidential),
            "restricted" => Ok(ClassificationLevel::Restricted),
            "secret" => Ok(ClassificationLevel::Secret),
            "topsecret" | "top_secret" => Ok(ClassificationLevel::TopSecret),
            _ => Err(AppError::Validation(format!("Unknown classification level: {}", s))),
        }
    }

    pub fn to_str(&self) -> &'static str {
        match self {
            ClassificationLevel::Public => "public",
            ClassificationLevel::Internal => "internal",
            ClassificationLevel::Confidential => "confidential",
            ClassificationLevel::Restricted => "restricted",
            ClassificationLevel::Secret => "secret",
            ClassificationLevel::TopSecret => "topsecret",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationPattern {
    pub id: String,
    pub name: String,
    pub pattern: String,
    pub category: String,
    pub level: ClassificationLevel,
    pub enabled: bool,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataClassificationResult {
    pub field_name: String,
    pub value: String,
    pub detected_patterns: Vec<DetectedPattern>,
    pub max_level: ClassificationLevel,
    pub confidence: f64,
    pub is_sensitive: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DetectedPattern {
    pub pattern_id: String,
    pub pattern_name: String,
    pub category: String,
    pub level: ClassificationLevel,
    pub confidence: f64,
    pub matches: Vec<MatchLocation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatchLocation {
    pub start: usize,
    pub end: usize,
    pub matched_text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationPolicy {
    pub id: String,
    pub name: String,
    pub description: String,
    pub levels: HashMap<ClassificationLevel, PolicyAction>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PolicyAction {
    pub action: String,
    pub notify_users: Vec<String>,
    pub block_access: bool,
    pub log_access: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanResult {
    pub scan_id: String,
    pub scanned_at: DateTime<Utc>,
    pub total_fields: usize,
    pub sensitive_fields: usize,
    pub classifications: Vec<DataClassificationResult>,
    pub highest_level: Option<ClassificationLevel>,
}

pub struct DataClassificationEngine {
    config: ClassificationConfig,
    patterns: Arc<Vec<ClassificationPattern>>,
    compiled_patterns: Arc<HashMap<String, Regex>>,
    policies: Arc<HashMap<String, ClassificationPolicy>>,
}

impl DataClassificationEngine {
    pub fn new(config: ClassificationConfig) -> Self {
        let patterns = Self::default_patterns();
        let compiled = Self::compile_patterns(&patterns);
        let policies = Self::default_policies();

        Self {
            config,
            patterns: Arc::new(patterns),
            compiled_patterns: Arc::new(compiled),
            policies: Arc::new(policies),
        }
    }

    pub fn with_custom_patterns(
        config: ClassificationConfig,
        custom_patterns: Vec<ClassificationPattern>,
    ) -> Self {
        let mut patterns = Self::default_patterns();
        patterns.extend(custom_patterns);
        let compiled = Self::compile_patterns(&patterns);
        let policies = Self::default_policies();

        Self {
            config,
            patterns: Arc::new(patterns),
            compiled_patterns: Arc::new(compiled),
            policies: Arc::new(policies),
        }
    }

    fn default_patterns() -> Vec<ClassificationPattern> {
        vec![
            ClassificationPattern {
                id: "pii_email".to_string(),
                name: "Email Address".to_string(),
                pattern: r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}".to_string(),
                category: "PII".to_string(),
                level: ClassificationLevel::Confidential,
                enabled: true,
                description: "Email addresses identifying individuals".to_string(),
            },
            ClassificationPattern {
                id: "pii_phone".to_string(),
                name: "Phone Number".to_string(),
                pattern: r"1[3-9]\d{9}".to_string(),
                category: "PII".to_string(),
                level: ClassificationLevel::Confidential,
                enabled: true,
                description: "Chinese mobile phone numbers".to_string(),
            },
            ClassificationPattern {
                id: "pii_idcard".to_string(),
                name: "ID Card Number".to_string(),
                pattern: r"[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]".to_string(),
                category: "PII".to_string(),
                level: ClassificationLevel::Restricted,
                enabled: true,
                description: "Chinese Resident ID Card numbers".to_string(),
            },
            ClassificationPattern {
                id: "fin_creditcard".to_string(),
                name: "Credit Card Number".to_string(),
                pattern: r"\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}".to_string(),
                category: "Financial".to_string(),
                level: ClassificationLevel::Secret,
                enabled: true,
                description: "Credit and debit card numbers".to_string(),
            },
            ClassificationPattern {
                id: "fin_bankaccount".to_string(),
                name: "Bank Account".to_string(),
                pattern: r"\d{16,19}".to_string(),
                category: "Financial".to_string(),
                level: ClassificationLevel::Restricted,
                enabled: true,
                description: "Bank account numbers".to_string(),
            },
            ClassificationPattern {
                id: "sec_password".to_string(),
                name: "Password Hash".to_string(),
                pattern: r"(?:\$2[aby]?\$[0-9]{2}\$[./A-Za-z0-9]{53}|[0-9a-fA-F]{32,64})".to_string(),
                category: "Security".to_string(),
                level: ClassificationLevel::TopSecret,
                enabled: true,
                description: "Password hashes and cryptographic secrets".to_string(),
            },
            ClassificationPattern {
                id: "sec_apikey".to_string(),
                name: "API Key".to_string(),
                pattern: r"(?i)(api[_-]?key|secret[_-]?key|access[_-]?token)".to_string(),
                category: "Security".to_string(),
                level: ClassificationLevel::Secret,
                enabled: true,
                description: "API keys and access tokens".to_string(),
            },
            ClassificationPattern {
                id: "ip_address".to_string(),
                name: "IP Address".to_string(),
                pattern: r"\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b".to_string(),
                category: "Network".to_string(),
                level: ClassificationLevel::Internal,
                enabled: true,
                description: "IPv4 addresses".to_string(),
            },
            ClassificationPattern {
                id: "health_record".to_string(),
                name: "Health Record ID".to_string(),
                pattern: r"(?i)(medical|patient|health)[_\s-]?(id|record|no)".to_string(),
                category: "Healthcare".to_string(),
                level: ClassificationLevel::Restricted,
                enabled: true,
                description: "Healthcare and medical record identifiers".to_string(),
            },
            ClassificationPattern {
                id: "govt_id".to_string(),
                name: "Government ID".to_string(),
                pattern: r"(?i)(gov|government|official)[_\s-]?(id|number|no)".to_string(),
                category: "Government".to_string(),
                level: ClassificationLevel::Confidential,
                enabled: true,
                description: "Government issued identifiers".to_string(),
            },
        ]
    }

    fn compile_patterns(
        patterns: &[ClassificationPattern],
    ) -> HashMap<String, Regex> {
        let mut compiled = HashMap::new();
        for pattern in patterns {
            if pattern.enabled {
                if let Ok(re) = Regex::new(&pattern.pattern) {
                    compiled.insert(pattern.id.clone(), re);
                }
            }
        }
        compiled
    }

    fn default_policies() -> HashMap<String, ClassificationPolicy> {
        let mut policies = HashMap::new();

        policies.insert(
            "default".to_string(),
            ClassificationPolicy {
                id: "default".to_string(),
                name: "Default Policy".to_string(),
                description: "Default classification policy".to_string(),
                levels: {
                    let mut map = HashMap::new();
                    map.insert(
                        ClassificationLevel::Public,
                        PolicyAction {
                            action: "allow".to_string(),
                            notify_users: vec![],
                            block_access: false,
                            log_access: false,
                        },
                    );
                    map.insert(
                        ClassificationLevel::Internal,
                        PolicyAction {
                            action: "warn".to_string(),
                            notify_users: vec![],
                            block_access: false,
                            log_access: true,
                        },
                    );
                    map.insert(
                        ClassificationLevel::Confidential,
                        PolicyAction {
                            action: "mask".to_string(),
                            notify_users: vec!["security@company.com".to_string()],
                            block_access: false,
                            log_access: true,
                        },
                    );
                    map.insert(
                        ClassificationLevel::Restricted,
                        PolicyAction {
                            action: "restrict".to_string(),
                            notify_users: vec!["security@company.com".to_string(), "compliance@company.com".to_string()],
                            block_access: true,
                            log_access: true,
                        },
                    );
                    map.insert(
                        ClassificationLevel::Secret,
                        PolicyAction {
                            action: "block".to_string(),
                            notify_users: vec!["security@company.com".to_string(), "compliance@company.com".to_string(), "legal@company.com".to_string()],
                            block_access: true,
                            log_access: true,
                        },
                    );
                    map.insert(
                        ClassificationLevel::TopSecret,
                        PolicyAction {
                            action: "terminate".to_string(),
                            notify_users: vec!["security@company.com".to_string(), "compliance@company.com".to_string(), "legal@company.com".to_string(), "ciso@company.com".to_string()],
                            block_access: true,
                            log_access: true,
                        },
                    );
                    map
                },
                enabled: true,
            },
        );

        policies
    }

    pub fn add_pattern(&mut self, pattern: ClassificationPattern) {
        let patterns = Arc::make_mut(&mut self.patterns);
        patterns.push(pattern.clone());

        if pattern.enabled {
            if let Ok(re) = Regex::new(&pattern.pattern) {
                Arc::make_mut(&mut self.compiled_patterns)
                    .insert(pattern.id, re);
            }
        }
    }

    pub fn add_policy(&mut self, policy: ClassificationPolicy) {
        Arc::make_mut(&mut self.policies).insert(policy.id.clone(), policy);
    }

    pub fn classify_field(
        &self,
        field_name: &str,
        value: &str,
    ) -> DataClassificationResult {
        let mut detected_patterns = Vec::new();
        let mut max_level = ClassificationLevel::Public;
        let mut total_confidence = 0.0;
        let mut match_count = 0;

        for pattern in self.patterns.iter() {
            if !pattern.enabled {
                continue;
            }

            if let Some(re) = self.compiled_patterns.get(&pattern.id) {
                let matches: Vec<MatchLocation> = re
                    .find_iter(value)
                    .map(|m| MatchLocation {
                        start: m.start(),
                        end: m.end(),
                        matched_text: m.as_str().to_string(),
                    })
                    .collect();

                if !matches.is_empty() {
                    let confidence = self.calculate_confidence(&pattern, value, &matches);

                    if confidence >= self.config.min_confidence {
                        if pattern.level > max_level {
                            max_level = pattern.level;
                        }

                        detected_patterns.push(DetectedPattern {
                            pattern_id: pattern.id.clone(),
                            pattern_name: pattern.name.clone(),
                            category: pattern.category.clone(),
                            level: pattern.level,
                            confidence,
                            matches,
                        });

                        total_confidence += confidence;
                        match_count += 1;
                    }
                }
            }
        }

        let average_confidence = if match_count > 0 {
            total_confidence / match_count as f64
        } else {
            0.0
        };

        DataClassificationResult {
            field_name: field_name.to_string(),
            value: value.to_string(),
            detected_patterns,
            max_level,
            confidence: average_confidence,
            is_sensitive: max_level > ClassificationLevel::Public,
        }
    }

    fn calculate_confidence(
        &self,
        pattern: &ClassificationPattern,
        value: &str,
        matches: &[MatchLocation],
    ) -> f64 {
        if matches.is_empty() {
            return 0.0;
        }

        let match_ratio = matches.len() as f64 / value.len().max(1) as f64;
        let coverage: f64 = matches
            .iter()
            .map(|m| (m.end - m.start) as f64)
            .sum::<f64>()
            / value.len() as f64;

        let base_score = 0.5;
        let coverage_bonus = coverage * 0.3;
        let density_bonus = (match_ratio * 10.0).min(0.2);

        (base_score + coverage_bonus + density_bonus).min(1.0)
    }

    pub fn classify_json(
        &self,
        value: &serde_json::Value,
        prefix: &str,
    ) -> Vec<DataClassificationResult> {
        let mut results = Vec::new();

        match value {
            serde_json::Value::Object(obj) => {
                for (key, val) in obj {
                    let field_path = if prefix.is_empty() {
                        key.clone()
                    } else {
                        format!("{}.{}", prefix, key)
                    };

                    if let Some(s) = val.as_str() {
                        results.push(self.classify_field(&field_path, s));
                    } else {
                        results.extend(self.classify_json(val, &field_path));
                    }
                }
            }
            serde_json::Value::Array(arr) => {
                for (idx, val) in arr.iter().enumerate() {
                    let field_path = format!("{}[{}]", prefix, idx);
                    results.extend(self.classify_json(val, &field_path));
                }
            }
            _ => {}
        }

        results
    }

    pub fn scan_document(
        &self,
        document: &serde_json::Value,
    ) -> ScanResult {
        let classifications = self.classify_json(document, "");
        let sensitive_fields = classifications
            .iter()
            .filter(|c| c.is_sensitive)
            .count();

        let highest_level = classifications
            .iter()
            .filter(|c| c.is_sensitive)
            .map(|c| c.max_level)
            .max();

        ScanResult {
            scan_id: crate::utils::generate_id("scan"),
            scanned_at: current_datetime(),
            total_fields: classifications.len(),
            sensitive_fields,
            classifications,
            highest_level,
        }
    }

    pub fn apply_policy(
        &self,
        policy_id: &str,
        classification: &DataClassificationResult,
    ) -> Result<&PolicyAction, AppError> {
        let policy = self
            .policies
            .get(policy_id)
            .ok_or_else(|| AppError::NotFound(format!("Policy not found: {}", policy_id)))?;

        if !policy.enabled {
            return Err(AppError::Validation(format!(
                "Policy is not enabled: {}",
                policy_id
            )));
        }

        policy
            .levels
            .get(&classification.max_level)
            .ok_or_else(|| {
                AppError::NotFound(format!(
                    "No action defined for level: {:?}",
                    classification.max_level
                ))
            })
    }

    pub fn list_patterns(&self) -> Vec<ClassificationPattern> {
        self.patterns.to_vec()
    }

    pub fn list_policies(&self) -> Vec<ClassificationPolicy> {
        self.policies.values().cloned().collect()
    }

    pub fn get_pattern(&self, pattern_id: &str) -> Option<ClassificationPattern> {
        self.patterns
            .iter()
            .find(|p| p.id == pattern_id)
            .cloned()
    }

    pub fn get_policy(&self, policy_id: &str) -> Option<ClassificationPolicy> {
        self.policies.get(policy_id).cloned()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationEvent {
    pub event_type: String,
    pub scan_id: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub details: serde_json::Value,
}

impl ClassificationEvent {
    pub fn new(
        event_type: &str,
        scan_id: Option<String>,
        details: serde_json::Value,
    ) -> Self {
        Self {
            event_type: event_type.to_string(),
            scan_id,
            timestamp: current_datetime(),
            details,
        }
    }
}
