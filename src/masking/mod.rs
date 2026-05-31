use crate::config::{ConfigurationListener, DynamicConfigManager, MaskingConfig};
use crate::models::AppError;
use crate::utils::current_datetime;
use chrono::{DateTime, Utc};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;

pub mod async_engine;
pub use async_engine::{
    AsyncMaskingEngine, MaskingCallback, MaskingTask, MaskingTaskStatus,
    MaskingTaskType, AsyncMaskingResult, CallbackBox, SimpleCallback,
    MaskingEvent,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum UserRole {
    Admin,
    Manager,
    Operator,
    Viewer,
    Guest,
}

impl UserRole {
    pub fn from_str(s: &str) -> Result<Self, AppError> {
        match s.to_lowercase().as_str() {
            "admin" => Ok(UserRole::Admin),
            "manager" => Ok(UserRole::Manager),
            "operator" => Ok(UserRole::Operator),
            "viewer" => Ok(UserRole::Viewer),
            "guest" => Ok(UserRole::Guest),
            _ => Err(AppError::Validation(format!("Unknown user role: {}", s))),
        }
    }

    pub fn permission_level(&self) -> u8 {
        match self {
            UserRole::Admin => 0,
            UserRole::Manager => 1,
            UserRole::Operator => 2,
            UserRole::Viewer => 3,
            UserRole::Guest => 4,
        }
    }

    pub fn can_view(&self, required_level: u8) -> bool {
        self.permission_level() <= required_level
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MaskingStrategy {
    Full,
    Partial,
    Hash,
    Replace,
    Redact,
    None,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingRule {
    pub field_name: String,
    pub data_type: String,
    pub strategy: MaskingStrategy,
    pub visible_chars: usize,
    pub required_role: u8,
    pub pattern: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingContext {
    pub user_id: String,
    pub user_role: UserRole,
    pub request_id: String,
    pub timestamp: DateTime<Utc>,
    pub additional_claims: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingResult {
    pub original_value: String,
    pub masked_value: String,
    pub strategy_used: MaskingStrategy,
    pub is_masked: bool,
}

pub struct DynamicMaskingEngine {
    config: MaskingConfig,
    rules: Arc<HashMap<String, MaskingRule>>,
    patterns: Arc<HashMap<String, Regex>>,
}

impl DynamicMaskingEngine {
    pub fn new(config: MaskingConfig) -> Self {
        let rules = Self::default_rules(&config);
        let patterns = Self::compile_patterns();

        Self {
            config,
            rules: Arc::new(rules),
            patterns: Arc::new(patterns),
        }
    }

    pub fn with_custom_rules(
        config: MaskingConfig,
        custom_rules: Vec<MaskingRule>,
    ) -> Self {
        let mut rules = Self::default_rules(&config);
        for rule in custom_rules {
            rules.insert(rule.field_name.clone(), rule);
        }

        let patterns = Self::compile_patterns();

        Self {
            config,
            rules: Arc::new(rules),
            patterns: Arc::new(patterns),
        }
    }

    fn default_rules(config: &MaskingConfig) -> HashMap<String, MaskingRule> {
        let mut rules = HashMap::new();

        rules.insert(
            "email".to_string(),
            MaskingRule {
                field_name: "email".to_string(),
                data_type: "email".to_string(),
                strategy: if config.mask_email {
                    MaskingStrategy::Partial
                } else {
                    MaskingStrategy::None
                },
                visible_chars: 2,
                required_role: 1,
                pattern: Some(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}".to_string()),
            },
        );

        rules.insert(
            "phone".to_string(),
            MaskingRule {
                field_name: "phone".to_string(),
                data_type: "phone".to_string(),
                strategy: if config.mask_phone {
                    MaskingStrategy::Partial
                } else {
                    MaskingStrategy::None
                },
                visible_chars: 3,
                required_role: 1,
                pattern: Some(r"1[3-9]\d{9}".to_string()),
            },
        );

        rules.insert(
            "id_card".to_string(),
            MaskingRule {
                field_name: "id_card".to_string(),
                data_type: "id_card".to_string(),
                strategy: if config.mask_id_card {
                    MaskingStrategy::Partial
                } else {
                    MaskingStrategy::None
                },
                visible_chars: 4,
                required_role: 0,
                pattern: Some(
                    r"[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]"
                        .to_string(),
                ),
            },
        );

        rules.insert(
            "password".to_string(),
            MaskingRule {
                field_name: "password".to_string(),
                data_type: "password".to_string(),
                strategy: MaskingStrategy::Full,
                visible_chars: 0,
                required_role: 0,
                pattern: None,
            },
        );

        rules.insert(
            "credit_card".to_string(),
            MaskingRule {
                field_name: "credit_card".to_string(),
                data_type: "credit_card".to_string(),
                strategy: MaskingStrategy::Partial,
                visible_chars: 4,
                required_role: 0,
                pattern: Some(r"\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}".to_string()),
            },
        );

        rules.insert(
            "bank_account".to_string(),
            MaskingRule {
                field_name: "bank_account".to_string(),
                data_type: "bank_account".to_string(),
                strategy: MaskingStrategy::Partial,
                visible_chars: 4,
                required_role: 0,
                pattern: Some(r"\d{16,19}".to_string()),
            },
        );

        rules.insert(
            "address".to_string(),
            MaskingRule {
                field_name: "address".to_string(),
                data_type: "address".to_string(),
                strategy: MaskingStrategy::Partial,
                visible_chars: 6,
                required_role: 2,
                pattern: None,
            },
        );

        rules.insert(
            "name".to_string(),
            MaskingRule {
                field_name: "name".to_string(),
                data_type: "name".to_string(),
                strategy: MaskingStrategy::Partial,
                visible_chars: 1,
                required_role: 2,
                pattern: None,
            },
        );

        rules
    }

    fn compile_patterns() -> HashMap<String, Regex> {
        let mut patterns = HashMap::new();

        patterns.insert(
            "email".to_string(),
            Regex::new(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}").unwrap(),
        );
        patterns.insert(
            "phone".to_string(),
            Regex::new(r"1[3-9]\d{9}").unwrap(),
        );
        patterns.insert(
            "id_card".to_string(),
            Regex::new(r"[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]").unwrap(),
        );
        patterns.insert(
            "credit_card".to_string(),
            Regex::new(r"\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}").unwrap(),
        );
        patterns.insert(
            "bank_account".to_string(),
            Regex::new(r"\d{16,19}").unwrap(),
        );

        patterns
    }

    pub fn add_rule(&mut self, rule: MaskingRule) {
        Arc::make_mut(&mut self.rules).insert(rule.field_name.clone(), rule);
    }

    pub fn get_rule(&self, field_name: &str) -> Option<MaskingRule> {
        self.rules.get(field_name).cloned()
    }

    pub fn mask_field(
        &self,
        field_name: &str,
        value: &str,
        context: &MaskingContext,
    ) -> MaskingResult {
        let rule = match self.rules.get(field_name) {
            Some(r) => r,
            None => {
                return MaskingResult {
                    original_value: value.to_string(),
                    masked_value: value.to_string(),
                    strategy_used: MaskingStrategy::None,
                    is_masked: false,
                };
            }
        };

        if context.user_role.can_view(rule.required_role) {
            return MaskingResult {
                original_value: value.to_string(),
                masked_value: value.to_string(),
                strategy_used: MaskingStrategy::None,
                is_masked: false,
            };
        }

        let masked_value = self.apply_strategy(value, rule);

        MaskingResult {
            original_value: value.to_string(),
            masked_value,
            strategy_used: rule.strategy,
            is_masked: true,
        }
    }

    fn apply_strategy(&self, value: &str, rule: &MaskingRule) -> String {
        match rule.strategy {
            MaskingStrategy::None => value.to_string(),
            MaskingStrategy::Full => {
                std::iter::repeat(self.config.default_mask_char)
                    .take(value.len())
                    .collect()
            }
            MaskingStrategy::Partial => self.mask_partial(value, rule.visible_chars),
            MaskingStrategy::Hash => {
                use sha2::{Sha256, Digest};
                let mut hasher = Sha256::new();
                hasher.update(value.as_bytes());
                format!("hash_{}", hex::encode(hasher.finalize()))
            }
            MaskingStrategy::Replace => "[REDACTED]".to_string(),
            MaskingStrategy::Redact => "***".to_string(),
        }
    }

    fn mask_partial(&self, value: &str, visible_chars: usize) -> String {
        let chars: Vec<char> = value.chars().collect();
        let len = chars.len();

        if len <= visible_chars {
            return std::iter::repeat(self.config.default_mask_char)
                .take(len)
                .collect();
        }

        if let Some((local, domain)) = value.split_once('@') {
            let local_chars: Vec<char> = local.chars().collect();
            let local_len = local_chars.len();
            let visible = visible_chars.min(local_len.saturating_sub(1));
            
            let mut result = String::new();
            for (i, c) in local_chars.iter().enumerate() {
                if i < visible {
                    result.push(*c);
                } else {
                    result.push(self.config.default_mask_char);
                }
            }
            result.push('@');
            result.push_str(domain);
            return result;
        }

        if len >= 11 && value.chars().all(|c| c.is_ascii_digit()) {
            let mut result = String::new();
            for (i, c) in chars.iter().enumerate() {
                if i < 3 || i >= len.saturating_sub(visible_chars) {
                    result.push(*c);
                } else {
                    result.push(self.config.default_mask_char);
                }
            }
            return result;
        }

        let mut result = String::new();
        let half_visible = visible_chars / 2;
        let end_start = len.saturating_sub(half_visible);

        for (i, c) in chars.iter().enumerate() {
            if i < half_visible || i >= end_start {
                result.push(*c);
            } else {
                result.push(self.config.default_mask_char);
            }
        }

        result
    }

    pub fn mask_json_value(
        &self,
        value: &serde_json::Value,
        context: &MaskingContext,
    ) -> serde_json::Value {
        match value {
            serde_json::Value::Object(obj) => {
                let mut masked_obj = serde_json::Map::new();
                for (key, val) in obj {
                    let masked_val = if self.rules.contains_key(key) {
                        if let Some(s) = val.as_str() {
                            let result = self.mask_field(key, s, context);
                            serde_json::Value::String(result.masked_value)
                        } else {
                            self.mask_json_value(val, context)
                        }
                    } else {
                        self.mask_json_value(val, context)
                    };
                    masked_obj.insert(key.clone(), masked_val);
                }
                serde_json::Value::Object(masked_obj)
            }
            serde_json::Value::Array(arr) => {
                let masked_arr: Vec<serde_json::Value> = arr
                    .iter()
                    .map(|v| self.mask_json_value(v, context))
                    .collect();
                serde_json::Value::Array(masked_arr)
            }
            _ => value.clone(),
        }
    }

    pub fn mask_text(&self, text: &str, context: &MaskingContext) -> String {
        let mut result = text.to_string();

        for (name, pattern) in self.patterns.iter() {
            if let Some(rule) = self.rules.get(name) {
                if !context.user_role.can_view(rule.required_role) {
                    result = pattern
                        .replace_all(&result, |caps: &regex::Captures| {
                            let matched = &caps[0];
                            self.apply_strategy(matched, rule)
                        })
                        .to_string();
                }
            }
        }

        result
    }

    pub fn batch_mask(
        &self,
        fields: &HashMap<String, String>,
        context: &MaskingContext,
    ) -> HashMap<String, MaskingResult> {
        let mut results = HashMap::new();
        for (field, value) in fields {
            results.insert(field.clone(), self.mask_field(field, value, context));
        }
        results
    }

    pub fn can_view_field(&self, field_name: &str, role: &UserRole) -> bool {
        match self.rules.get(field_name) {
            Some(rule) => role.can_view(rule.required_role),
            None => true,
        }
    }

    pub fn list_rules(&self) -> Vec<MaskingRule> {
        self.rules.values().cloned().collect()
    }
}

impl MaskingContext {
    pub fn new(user_id: &str, user_role: UserRole) -> Self {
        Self {
            user_id: user_id.to_string(),
            user_role,
            request_id: crate::utils::generate_id("req"),
            timestamp: current_datetime(),
            additional_claims: HashMap::new(),
        }
    }

    pub fn with_claim(
        mut self,
        key: &str,
        value: serde_json::Value,
    ) -> Self {
        self.additional_claims.insert(key.to_string(), value);
        self
    }
}
