use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

use crate::domain::run_instance::RunInstance;
use crate::infra::config::ClassificationConfig;
use crate::infra::crypto::CryptoService;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum DataSensitivityLevel {
    Public,
    Internal,
    Confidential,
    HighlyConfidential,
    TopSecret,
}

impl DataSensitivityLevel {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "public" => Some(DataSensitivityLevel::Public),
            "internal" => Some(DataSensitivityLevel::Internal),
            "confidential" => Some(DataSensitivityLevel::Confidential),
            "highly_confidential" => Some(DataSensitivityLevel::HighlyConfidential),
            "top_secret" => Some(DataSensitivityLevel::TopSecret),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            DataSensitivityLevel::Public => "public",
            DataSensitivityLevel::Internal => "internal",
            DataSensitivityLevel::Confidential => "confidential",
            DataSensitivityLevel::HighlyConfidential => "highly_confidential",
            DataSensitivityLevel::TopSecret => "top_secret",
        }
    }

    pub fn numeric_level(&self) -> u8 {
        match self {
            DataSensitivityLevel::Public => 0,
            DataSensitivityLevel::Internal => 1,
            DataSensitivityLevel::Confidential => 2,
            DataSensitivityLevel::HighlyConfidential => 3,
            DataSensitivityLevel::TopSecret => 4,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum DataCategory {
    PII,
    Financial,
    Health,
    Education,
    Employment,
    Government,
    IntellectualProperty,
    CustomerData,
    Operational,
    Other,
}

impl DataCategory {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "pii" => Some(DataCategory::PII),
            "financial" => Some(DataCategory::Financial),
            "health" => Some(DataCategory::Health),
            "education" => Some(DataCategory::Education),
            "employment" => Some(DataCategory::Employment),
            "government" => Some(DataCategory::Government),
            "intellectual_property" => Some(DataCategory::IntellectualProperty),
            "customer_data" => Some(DataCategory::CustomerData),
            "operational" => Some(DataCategory::Operational),
            "other" => Some(DataCategory::Other),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            DataCategory::PII => "pii",
            DataCategory::Financial => "financial",
            DataCategory::Health => "health",
            DataCategory::Education => "education",
            DataCategory::Employment => "employment",
            DataCategory::Government => "government",
            DataCategory::IntellectualProperty => "intellectual_property",
            DataCategory::CustomerData => "customer_data",
            DataCategory::Operational => "operational",
            DataCategory::Other => "other",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationPattern {
    pub name: String,
    pub category: DataCategory,
    pub level: DataSensitivityLevel,
    pub regex: String,
    pub description: String,
    pub enabled: bool,
}

impl ClassificationPattern {
    pub fn default_pii() -> Self {
        Self {
            name: "id_card".to_string(),
            category: DataCategory::PII,
            level: DataSensitivityLevel::Confidential,
            regex: r"\d{17}[\dXx]".to_string(),
            description: "Chinese ID card number".to_string(),
            enabled: true,
        }
    }

    pub fn default_phone() -> Self {
        Self {
            name: "phone".to_string(),
            category: DataCategory::PII,
            level: DataSensitivityLevel::Internal,
            regex: r"1[3-9]\d{9}".to_string(),
            description: "Chinese phone number".to_string(),
            enabled: true,
        }
    }

    pub fn default_email() -> Self {
        Self {
            name: "email".to_string(),
            category: DataCategory::PII,
            level: DataSensitivityLevel::Internal,
            regex: r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}".to_string(),
            description: "Email address".to_string(),
            enabled: true,
        }
    }

    pub fn default_bank_card() -> Self {
        Self {
            name: "bank_card".to_string(),
            category: DataCategory::Financial,
            level: DataSensitivityLevel::HighlyConfidential,
            regex: r"\d{16,19}".to_string(),
            description: "Bank card number".to_string(),
            enabled: true,
        }
    }

    pub fn default_medical_record() -> Self {
        Self {
            name: "medical_record".to_string(),
            category: DataCategory::Health,
            level: DataSensitivityLevel::HighlyConfidential,
            regex: r"病历|诊断|处方|MR[0-9]+".to_string(),
            description: "Medical record information".to_string(),
            enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationResult {
    pub field_name: String,
    pub category: DataCategory,
    pub level: DataSensitivityLevel,
    pub matched_pattern: String,
    pub confidence: f64,
    pub matches: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataClassificationReport {
    pub report_id: String,
    pub resource_id: String,
    pub resource_type: String,
    pub total_fields: u32,
    pub sensitive_fields: u32,
    pub results: HashMap<String, ClassificationResult>,
    pub overall_level: DataSensitivityLevel,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationPolicy {
    pub policy_id: String,
    pub name: String,
    pub rules: Vec<PolicyRule>,
    pub auto_apply: bool,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PolicyRule {
    pub category: DataCategory,
    pub min_level: DataSensitivityLevel,
    pub action: PolicyAction,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PolicyAction {
    Encrypt,
    Mask,
    BlockAccess,
    FlagForReview,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassifyRequest {
    pub data: serde_json::Value,
    pub resource_id: String,
    pub resource_type: String,
    pub apply_policy: bool,
}

pub struct ClassificationService {
    config: ClassificationConfig,
    patterns: Vec<ClassificationPattern>,
    policies: std::sync::Arc<parking_lot::Mutex<HashMap<String, ClassificationPolicy>>>,
    reports: std::sync::Arc<parking_lot::Mutex<HashMap<String, DataClassificationReport>>>,
}

impl ClassificationService {
    pub fn new(config: ClassificationConfig) -> Self {
        let mut patterns = Vec::new();

        if config.enabled_patterns.iter().any(|p| p == "pii") {
            patterns.push(ClassificationPattern::default_pii());
            patterns.push(ClassificationPattern::default_phone());
            patterns.push(ClassificationPattern::default_email());
        }
        if config.enabled_patterns.iter().any(|p| p == "financial") {
            patterns.push(ClassificationPattern::default_bank_card());
        }
        if config.enabled_patterns.iter().any(|p| p == "health") {
            patterns.push(ClassificationPattern::default_medical_record());
        }

        Self {
            config,
            patterns,
            policies: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            reports: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
        }
    }

    pub fn add_pattern(&mut self, pattern: ClassificationPattern) {
        self.patterns.push(pattern);
    }

    pub fn list_patterns(&self) -> &[ClassificationPattern] {
        &self.patterns
    }

    pub async fn classify_data(
        &self,
        request: ClassifyRequest,
    ) -> AppResult<DataClassificationReport> {
        let mut results = HashMap::new();
        let mut max_level = DataSensitivityLevel::Public;
        let mut total_fields = 0u32;
        let mut sensitive_fields = 0u32;

        self.classify_value(
            &request.data,
            "root".to_string(),
            &mut results,
            &mut max_level,
            &mut total_fields,
            &mut sensitive_fields,
            0,
        )?;

        let report = DataClassificationReport {
            report_id: format!("clsf_{}", Uuid::new_v4().simple()),
            resource_id: request.resource_id,
            resource_type: request.resource_type,
            total_fields,
            sensitive_fields,
            results,
            overall_level: max_level,
            created_at: chrono::Utc::now(),
        };

        self.reports
            .lock()
            .insert(report.report_id.clone(), report.clone());

        if request.apply_policy && self.config.auto_apply_policy {
            self.apply_policies(&report)?;
        }

        Ok(report)
    }

    fn classify_value(
        &self,
        value: &serde_json::Value,
        path: String,
        results: &mut HashMap<String, ClassificationResult>,
        max_level: &mut DataSensitivityLevel,
        total_fields: &mut u32,
        sensitive_fields: &mut u32,
        depth: u32,
    ) -> AppResult<()> {
        if depth > self.config.scan_depth {
            return Ok(());
        }

        *total_fields += 1;

        match value {
            serde_json::Value::String(s) => {
                if let Some(result) = self.classify_string(s, &path)? {
                    if result.level.numeric_level() > max_level.numeric_level() {
                        *max_level = result.level.clone();
                    }
                    *sensitive_fields += 1;
                    results.insert(path, result);
                }
            }
            serde_json::Value::Object(obj) => {
                for (key, val) in obj {
                    self.classify_value(
                        val,
                        format!("{}.{}", path, key),
                        results,
                        max_level,
                        total_fields,
                        sensitive_fields,
                        depth + 1,
                    )?;
                }
            }
            serde_json::Value::Array(arr) => {
                for (i, val) in arr.iter().enumerate() {
                    self.classify_value(
                        val,
                        format!("{}[{}]", path, i),
                        results,
                        max_level,
                        total_fields,
                        sensitive_fields,
                        depth + 1,
                    )?;
                }
            }
            serde_json::Value::Number(n) => {
                let s = n.to_string();
                if let Some(result) = self.classify_string(&s, &path)? {
                    if result.level.numeric_level() > max_level.numeric_level() {
                        *max_level = result.level.clone();
                    }
                    *sensitive_fields += 1;
                    results.insert(path, result);
                }
            }
            _ => {}
        }

        Ok(())
    }

    fn classify_string(
        &self,
        text: &str,
        field_name: &str,
    ) -> AppResult<Option<ClassificationResult>> {
        let mut best_result: Option<ClassificationResult> = None;
        let mut highest_level = DataSensitivityLevel::Public;

        for pattern in &self.patterns {
            if !pattern.enabled {
                continue;
            }

            let re = Regex::new(&pattern.regex)
                .map_err(|e| AppError::ConfigError(format!("Invalid regex: {}", e)))?;

            let matches: Vec<String> = re
                .find_iter(text)
                .map(|m| m.as_str().to_string())
                .collect();

            if !matches.is_empty() {
                let confidence = matches.len() as f64 / text.len().max(1) as f64;

                if pattern.level.numeric_level() > highest_level.numeric_level() {
                    highest_level = pattern.level.clone();
                    best_result = Some(ClassificationResult {
                        field_name: field_name.to_string(),
                        category: pattern.category.clone(),
                        level: pattern.level.clone(),
                        matched_pattern: pattern.name.clone(),
                        confidence,
                        matches,
                    });
                }
            }
        }

        Ok(best_result)
    }

    pub fn create_policy(&self, name: String, rules: Vec<PolicyRule>, auto_apply: bool) -> ClassificationPolicy {
        let policy = ClassificationPolicy {
            policy_id: format!("pol_{}", Uuid::new_v4().simple()),
            name,
            rules,
            auto_apply,
            created_at: chrono::Utc::now(),
        };

        self.policies
            .lock()
            .insert(policy.policy_id.clone(), policy.clone());

        policy
    }

    fn apply_policies(&self, report: &DataClassificationReport) -> AppResult<Vec<PolicyAction>> {
        let policies = self.policies.lock();
        let mut actions = Vec::new();

        for policy in policies.values() {
            if !policy.auto_apply {
                continue;
            }

            for result in report.results.values() {
                for rule in &policy.rules {
                    if result.category == rule.category
                        && result.level.numeric_level() >= rule.min_level.numeric_level()
                    {
                        actions.push(rule.action.clone());
                    }
                }
            }
        }

        Ok(actions)
    }

    pub async fn get_report(&self, report_id: &str) -> AppResult<DataClassificationReport> {
        let reports = self.reports.lock();
        reports
            .get(report_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Report {} not found", report_id)))
    }

    pub async fn list_reports(&self) -> AppResult<Vec<DataClassificationReport>> {
        let reports = self.reports.lock();
        Ok(reports.values().cloned().collect())
    }

    pub fn create_run_instance(&self, resource_id: &str) -> RunInstance {
        let mut instance = RunInstance::new(resource_id.to_string());
        instance.set_metadata("module", "classification");
        instance
    }

    pub fn hash_data(&self, data: &[u8]) -> String {
        CryptoService::sha256_hex(data)
    }
}
