use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use uuid::Uuid;

use crate::domain::user::{AuthContext, PermissionLevel};
use crate::infra::config::MaskingConfig;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MaskingRuleType {
    Email,
    Phone,
    IdCard,
    BankCard,
    Name,
    Address,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingRule {
    pub name: String,
    pub rule_type: MaskingRuleType,
    pub pattern: String,
    pub required_permission: PermissionLevel,
    pub keep_prefix: usize,
    pub keep_suffix: usize,
    pub masking_character: char,
    pub description: String,
}

impl MaskingRule {
    pub fn default_email(config: &MaskingConfig) -> Self {
        Self {
            name: "email".to_string(),
            rule_type: MaskingRuleType::Email,
            pattern: r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}".to_string(),
            required_permission: PermissionLevel::Confidential,
            keep_prefix: config.keep_prefix,
            keep_suffix: config.keep_suffix,
            masking_character: config.default_masking_character,
            description: "Email address masking".to_string(),
        }
    }

    pub fn default_phone(config: &MaskingConfig) -> Self {
        Self {
            name: "phone".to_string(),
            rule_type: MaskingRuleType::Phone,
            pattern: r"1[3-9]\d{9}".to_string(),
            required_permission: PermissionLevel::Confidential,
            keep_prefix: 3,
            keep_suffix: 4,
            masking_character: config.default_masking_character,
            description: "Phone number masking".to_string(),
        }
    }

    pub fn default_id_card(config: &MaskingConfig) -> Self {
        Self {
            name: "id_card".to_string(),
            rule_type: MaskingRuleType::IdCard,
            pattern: r"\d{17}[\dXx]".to_string(),
            required_permission: PermissionLevel::Restricted,
            keep_prefix: 6,
            keep_suffix: 4,
            masking_character: config.default_masking_character,
            description: "ID card number masking".to_string(),
        }
    }

    pub fn default_bank_card(config: &MaskingConfig) -> Self {
        Self {
            name: "bank_card".to_string(),
            rule_type: MaskingRuleType::BankCard,
            pattern: r"\d{16,19}".to_string(),
            required_permission: PermissionLevel::Restricted,
            keep_prefix: 4,
            keep_suffix: 4,
            masking_character: config.default_masking_character,
            description: "Bank card number masking".to_string(),
        }
    }

    pub fn default_name(config: &MaskingConfig) -> Self {
        Self {
            name: "name".to_string(),
            rule_type: MaskingRuleType::Name,
            pattern: r"[\u4e00-\u9fa5]{2,4}".to_string(),
            required_permission: PermissionLevel::Internal,
            keep_prefix: 1,
            keep_suffix: 0,
            masking_character: config.default_masking_character,
            description: "Chinese name masking".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingRequest {
    pub data: serde_json::Value,
    pub data_class: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingResponse {
    pub original: serde_json::Value,
    pub masked: serde_json::Value,
    pub applied_rules: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchMaskingRequest {
    pub records: Vec<MaskingRequest>,
    pub batch_id: Option<String>,
    pub parallel: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchMaskingResponse {
    pub results: Vec<MaskingResponse>,
    pub batch_id: String,
    pub total_count: usize,
    pub success_count: usize,
    pub failed_count: usize,
    pub processing_time_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchedOperation {
    pub operation_id: String,
    pub records: Vec<MaskingRequest>,
    pub auth: AuthContext,
    pub created_at: Instant,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchProcessorConfig {
    pub max_batch_size: usize,
    pub max_wait_time_ms: u64,
    pub enable_parallel: bool,
}

impl Default for BatchProcessorConfig {
    fn default() -> Self {
        Self {
            max_batch_size: 100,
            max_wait_time_ms: 50,
            enable_parallel: true,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct BatchProcessorMetrics {
    pub total_batches_processed: u64,
    pub total_records_processed: u64,
    pub average_batch_size: f64,
    pub average_processing_time_ms: f64,
    pub queued_batches: usize,
}

pub struct MaskingService {
    config: MaskingConfig,
    rules: HashMap<String, MaskingRule>,
    batch_config: BatchProcessorConfig,
    batch_metrics: std::sync::Arc<std::sync::Mutex<BatchProcessorMetrics>>,
}

impl MaskingService {
    pub fn new(config: MaskingConfig) -> Self {
        let mut rules = HashMap::new();

        if config.enabled_rules.iter().any(|r| r == "email") {
            rules.insert("email".to_string(), MaskingRule::default_email(&config));
        }
        if config.enabled_rules.iter().any(|r| r == "phone") {
            rules.insert("phone".to_string(), MaskingRule::default_phone(&config));
        }
        if config.enabled_rules.iter().any(|r| r == "id_card") {
            rules.insert("id_card".to_string(), MaskingRule::default_id_card(&config));
        }
        if config.enabled_rules.iter().any(|r| r == "bank_card") {
            rules.insert("bank_card".to_string(), MaskingRule::default_bank_card(&config));
        }
        if config.enabled_rules.iter().any(|r| r == "name") {
            rules.insert("name".to_string(), MaskingRule::default_name(&config));
        }

        Self {
            config,
            rules,
            batch_config: BatchProcessorConfig::default(),
            batch_metrics: std::sync::Arc::new(std::sync::Mutex::new(BatchProcessorMetrics {
                total_batches_processed: 0,
                total_records_processed: 0,
                average_batch_size: 0.0,
                average_processing_time_ms: 0.0,
                queued_batches: 0,
            })),
        }
    }

    pub fn with_batch_config(config: MaskingConfig, batch_config: BatchProcessorConfig) -> Self {
        let mut service = Self::new(config);
        service.batch_config = batch_config;
        service
    }

    pub fn add_rule(&mut self, rule: MaskingRule) {
        self.rules.insert(rule.name.clone(), rule);
    }

    pub fn remove_rule(&mut self, name: &str) {
        self.rules.remove(name);
    }

    pub fn list_rules(&self) -> Vec<&MaskingRule> {
        self.rules.values().collect()
    }

    pub async fn mask_data(
        &self,
        request: MaskingRequest,
        auth: &AuthContext,
    ) -> AppResult<MaskingResponse> {
        let applied_rules = self.get_applicable_rules(&request.data_class, auth);
        let masked = self.mask_value(&request.data, &applied_rules)?;

        Ok(MaskingResponse {
            original: request.data,
            masked,
            applied_rules: applied_rules.iter().map(|r| r.name.clone()).collect(),
        })
    }

    fn get_applicable_rules(
        &self,
        data_class: &Option<String>,
        auth: &AuthContext,
    ) -> Vec<&MaskingRule> {
        self.rules
            .values()
            .filter(|rule| {
                if let Some(class) = data_class {
                    if !auth.user.can_access_data_class(class) {
                        return true;
                    }
                }
                !auth.user.permission_level.can_access(&rule.required_permission)
            })
            .collect()
    }

    fn mask_value(
        &self,
        value: &serde_json::Value,
        rules: &[&MaskingRule],
    ) -> AppResult<serde_json::Value> {
        match value {
            serde_json::Value::String(s) => {
                let mut result = s.clone();
                for rule in rules {
                    result = self.apply_rule(&result, rule)?;
                }
                Ok(serde_json::Value::String(result))
            }
            serde_json::Value::Object(obj) => {
                let mut masked_obj = serde_json::Map::new();
                for (key, val) in obj {
                    masked_obj.insert(key.clone(), self.mask_value(val, rules)?);
                }
                Ok(serde_json::Value::Object(masked_obj))
            }
            serde_json::Value::Array(arr) => {
                let masked_arr: Result<Vec<_>, _> =
                    arr.iter().map(|v| self.mask_value(v, rules)).collect();
                Ok(serde_json::Value::Array(masked_arr?))
            }
            _ => Ok(value.clone()),
        }
    }

    fn apply_rule(&self, text: &str, rule: &MaskingRule) -> AppResult<String> {
        let re = Regex::new(&rule.pattern)
            .map_err(|e| AppError::ConfigError(format!("Invalid regex pattern: {}", e)))?;

        let result = re.replace_all(text, |caps: &regex::Captures| {
            if let Some(matched) = caps.get(0) {
                self.mask_string(matched.as_str(), rule)
            } else {
                "".to_string()
            }
        });

        Ok(result.into_owned())
    }

    fn mask_string(&self, s: &str, rule: &MaskingRule) -> String {
        let chars: Vec<char> = s.chars().collect();
        let len = chars.len();

        if len <= rule.keep_prefix + rule.keep_suffix {
            return s.to_string();
        }

        let mut result = String::with_capacity(len);

        for i in 0..len {
            if i < rule.keep_prefix || i >= len - rule.keep_suffix {
                result.push(chars[i]);
            } else {
                result.push(rule.masking_character);
            }
        }

        result
    }

    pub fn mask_field(
        &self,
        field_value: &str,
        field_name: &str,
        auth: &AuthContext,
    ) -> AppResult<String> {
        let applicable_rules: Vec<_> = self
            .rules
            .values()
            .filter(|r| {
                r.name.to_lowercase().contains(&field_name.to_lowercase())
                    && !auth.user.permission_level.can_access(&r.required_permission)
            })
            .collect();

        if applicable_rules.is_empty() {
            return Ok(field_value.to_string());
        }

        let mut result = field_value.to_string();
        for rule in applicable_rules {
            result = self.apply_rule(&result, rule)?;
        }

        Ok(result)
    }

    pub fn create_masking_policy(
        &self,
        fields: Vec<String>,
        min_permission: PermissionLevel,
    ) -> MaskingPolicy {
        MaskingPolicy {
            fields,
            min_permission,
            rules: self
                .rules
                .values()
                .map(|r| r.name.clone())
                .collect(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingPolicy {
    pub fields: Vec<String>,
    pub min_permission: PermissionLevel,
    pub rules: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DynamicMaskingConfig {
    pub policies: HashMap<String, MaskingPolicy>,
    pub default_policy: MaskingPolicy,
}

impl DynamicMaskingConfig {
    pub fn new(default_policy: MaskingPolicy) -> Self {
        Self {
            policies: HashMap::new(),
            default_policy,
        }
    }

    pub fn add_policy(&mut self, name: String, policy: MaskingPolicy) {
        self.policies.insert(name, policy);
    }

    pub fn get_policy(&self, name: &str) -> &MaskingPolicy {
        self.policies.get(name).unwrap_or(&self.default_policy)
    }
}

impl MaskingService {
    pub async fn batch_mask(
        &self,
        request: BatchMaskingRequest,
        auth: &AuthContext,
    ) -> AppResult<BatchMaskingResponse> {
        let start_time = Instant::now();
        let batch_id = request.batch_id.unwrap_or_else(|| format!("batch_{}", Uuid::new_v4().simple()));
        
        let mut results = Vec::with_capacity(request.records.len());
        let mut success_count = 0;
        let mut failed_count = 0;

        let parallel = request.parallel.unwrap_or(self.batch_config.enable_parallel);

        if parallel && request.records.len() > 1 {
            let futures: Vec<_> = request
                .records
                .iter()
                .map(|record| {
                    let rules = self.get_applicable_rules(&record.data_class, auth);
                    async move {
                        match self.mask_value(&record.data, &rules) {
                            Ok(masked) => Ok(MaskingResponse {
                                original: record.data.clone(),
                                masked,
                                applied_rules: rules.iter().map(|r| r.name.clone()).collect(),
                            }),
                            Err(e) => Err(e),
                        }
                    }
                })
                .collect();

            let batch_results = futures::future::join_all(futures).await;
            
            for result in batch_results {
                match result {
                    Ok(response) => {
                        results.push(response);
                        success_count += 1;
                    }
                    Err(_) => {
                        failed_count += 1;
                    }
                }
            }
        } else {
            for record in &request.records {
                match self.mask_data(record.clone(), auth).await {
                    Ok(response) => {
                        results.push(response);
                        success_count += 1;
                    }
                    Err(_) => {
                        failed_count += 1;
                    }
                }
            }
        }

        let processing_time = start_time.elapsed().as_millis() as u64;
        
        self.update_batch_metrics(request.records.len(), processing_time);

        Ok(BatchMaskingResponse {
            results,
            batch_id,
            total_count: request.records.len(),
            success_count,
            failed_count,
            processing_time_ms: processing_time,
        })
    }

    pub async fn mask_field_batch(
        &self,
        records: Vec<(String, String)>,
        auth: &AuthContext,
    ) -> AppResult<Vec<(String, String)>> {
        let mut results = Vec::with_capacity(records.len());
        
        for (field_name, field_value) in records {
            let masked = self.mask_field(&field_value, &field_name, auth)?;
            results.push((field_name, masked));
        }
        
        Ok(results)
    }

    pub async fn mask_json_batch(
        &self,
        records: Vec<serde_json::Value>,
        auth: &AuthContext,
    ) -> AppResult<Vec<serde_json::Value>> {
        let request = BatchMaskingRequest {
            records: records
                .into_iter()
                .map(|data| MaskingRequest {
                    data,
                    data_class: None,
                })
                .collect(),
            batch_id: None,
            parallel: Some(true),
        };
        
        let response = self.batch_mask(request, auth).await?;
        Ok(response.results.into_iter().map(|r| r.masked).collect())
    }

    fn update_batch_metrics(&self, batch_size: usize, processing_time_ms: u64) {
        let mut metrics = self.batch_metrics.lock();
        metrics.total_batches_processed += 1;
        metrics.total_records_processed += batch_size as u64;
        
        let total_batches = metrics.total_batches_processed;
        metrics.average_batch_size = (metrics.average_batch_size * (total_batches - 1) as f64 
            + batch_size as f64) / total_batches as f64;
        metrics.average_processing_time_ms = (metrics.average_processing_time_ms * (total_batches - 1) as f64 
            + processing_time_ms as f64) / total_batches as f64;
    }

    pub fn get_batch_metrics(&self) -> BatchProcessorMetrics {
        self.batch_metrics.lock().clone()
    }

    pub fn reset_batch_metrics(&self) {
        *self.batch_metrics.lock() = BatchProcessorMetrics {
            total_batches_processed: 0,
            total_records_processed: 0,
            average_batch_size: 0.0,
            average_processing_time_ms: 0.0,
            queued_batches: 0,
        };
    }

    pub fn get_batch_config(&self) -> &BatchProcessorConfig {
        &self.batch_config
    }

    pub fn update_batch_config(&mut self, config: BatchProcessorConfig) {
        self.batch_config = config;
    }
}
