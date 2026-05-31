use crate::types::{SensitiveField, SensitiveType, UserPermission};
use parking_lot::RwLock;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use tracing::info;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingRule {
    pub field_name: String,
    pub sensitive_type: SensitiveType,
    pub mask_pattern: String,
    pub preserve_length: bool,
    pub preserve_format: bool,
    pub allowed_roles: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingConfiguration {
    pub rules: Vec<MaskingRule>,
    pub default_mask_pattern: String,
    pub case_sensitive: bool,
    pub enable_regex_matching: bool,
}

impl Default for MaskingConfiguration {
    fn default() -> Self {
        MaskingConfiguration {
            rules: Vec::new(),
            default_mask_pattern: "****".to_string(),
            case_sensitive: false,
            enable_regex_matching: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingResult {
    pub original_value: String,
    pub masked_value: String,
    pub field_name: String,
    pub sensitive_type: SensitiveType,
    pub was_masked: bool,
}

struct MaskingState {
    config: MaskingConfiguration,
    field_rules: HashMap<String, MaskingRule>,
    permissions: HashMap<String, UserPermission>,
    regex_patterns: HashMap<SensitiveType, Regex>,
    role_field_map: HashMap<String, HashSet<String>>,
}

pub struct DataMaskingService {
    state: Arc<RwLock<MaskingState>>,
}

impl DataMaskingService {
    pub fn new() -> Self {
        let regex_patterns = Self::build_regex_patterns();
        
        DataMaskingService {
            state: Arc::new(RwLock::new(MaskingState {
                config: MaskingConfiguration::default(),
                field_rules: HashMap::new(),
                permissions: HashMap::new(),
                regex_patterns,
                role_field_map: HashMap::new(),
            })),
        }
    }

    fn build_regex_patterns() -> HashMap<SensitiveType, Regex> {
        let mut patterns = HashMap::new();
        
        patterns.insert(
            SensitiveType::Email,
            Regex::new(r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}").unwrap(),
        );
        
        patterns.insert(
            SensitiveType::Phone,
            Regex::new(r"(\+?\d{1,3}[-.\s]?)?(\(?\d{2,4}\)?[-.\s]?)?\d{3,4}[-.\s]?\d{4}").unwrap(),
        );
        
        patterns.insert(
            SensitiveType::IdCard,
            Regex::new(r"\d{17}[\dXx]|\d{15}").unwrap(),
        );
        
        patterns.insert(
            SensitiveType::CreditCard,
            Regex::new(r"\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b").unwrap(),
        );
        
        patterns.insert(
            SensitiveType::Address,
            Regex::new(r"[\u4e00-\u9fa5]{2,}(省|市|区|县|街道|路|号|小区|栋|单元|室)[\u4e00-\u9fa50-9-]*").unwrap(),
        );
        
        patterns
    }

    pub fn configure(&self, config: MaskingConfiguration) {
        let mut state = self.state.write();
        state.config = config.clone();
        
        state.field_rules.clear();
        for rule in &config.rules {
            state.field_rules.insert(rule.field_name.clone(), rule.clone());
            
            for role in &rule.allowed_roles {
                state.role_field_map
                    .entry(role.clone())
                    .or_insert_with(HashSet::new)
                    .insert(rule.field_name.clone());
            }
        }
        
        info!(
            rule_count = config.rules.len(),
            "Data masking service configured"
        );
    }

    pub fn add_rule(&self, rule: MaskingRule) {
        let mut state = self.state.write();
        state.field_rules.insert(rule.field_name.clone(), rule.clone());
        state.config.rules.push(rule.clone());
        
        for role in &rule.allowed_roles {
            state.role_field_map
                .entry(role.clone())
                .or_insert_with(HashSet::new)
                .insert(rule.field_name.clone());
        }
        
        info!(field = %rule.field_name, "Masking rule added");
    }

    pub fn remove_rule(&self, field_name: &str) -> bool {
        let mut state = self.state.write();
        
        if let Some(removed_rule) = state.field_rules.remove(field_name) {
            state.config.rules.retain(|r| r.field_name != field_name);
            
            for role in &removed_rule.allowed_roles {
                if let Some(fields) = state.role_field_map.get_mut(role) {
                    fields.remove(field_name);
                    if fields.is_empty() {
                        state.role_field_map.remove(role);
                    }
                }
            }
            
            info!(field = %field_name, "Masking rule removed");
            true
        } else {
            false
        }
    }

    pub fn add_permission(&self, permission: UserPermission) {
        let mut state = self.state.write();
        state.permissions.insert(permission.user_id.clone(), permission);
    }

    pub fn remove_permission(&self, user_id: &str) -> bool {
        let mut state = self.state.write();
        state.permissions.remove(user_id).is_some()
    }

    pub fn get_permission(&self, user_id: &str) -> Option<UserPermission> {
        let state = self.state.read();
        state.permissions.get(user_id).cloned()
    }

    pub fn can_access_field(&self, user_id: &str, field_name: &str) -> bool {
        let state = self.state.read();
        
        if let Some(permission) = state.permissions.get(user_id) {
            if permission.allowed_fields.contains(&field_name.to_string()) {
                return true;
            }
            
            if let Some(rule) = state.field_rules.get(field_name) {
                for role in &permission.roles {
                    if rule.allowed_roles.contains(role) {
                        return true;
                    }
                }
            }
        }
        
        false
    }

    pub fn mask_value(
        &self,
        field_name: &str,
        value: &str,
        user_id: Option<&str>,
    ) -> MaskingResult {
        let state = self.state.read();
        
        if let Some(uid) = user_id {
            if self.can_access_field(uid, field_name) {
                return MaskingResult {
                    original_value: value.to_string(),
                    masked_value: value.to_string(),
                    field_name: field_name.to_string(),
                    sensitive_type: SensitiveType::Custom,
                    was_masked: false,
                };
            }
        }
        
        if let Some(rule) = state.field_rules.get(field_name) {
            let masked = self.apply_mask(value, rule);
            return MaskingResult {
                original_value: value.to_string(),
                masked_value: masked,
                field_name: field_name.to_string(),
                sensitive_type: rule.sensitive_type,
                was_masked: true,
            };
        }
        
        let detected_type = self.detect_sensitive_type(value, &state);
        
        if detected_type != SensitiveType::Custom {
            let rule = MaskingRule {
                field_name: field_name.to_string(),
                sensitive_type: detected_type,
                mask_pattern: state.config.default_mask_pattern.clone(),
                preserve_length: true,
                preserve_format: true,
                allowed_roles: Vec::new(),
            };
            
            let masked = self.apply_mask(value, &rule);
            return MaskingResult {
                original_value: value.to_string(),
                masked_value: masked,
                field_name: field_name.to_string(),
                sensitive_type: detected_type,
                was_masked: true,
            };
        }
        
        MaskingResult {
            original_value: value.to_string(),
            masked_value: value.to_string(),
            field_name: field_name.to_string(),
            sensitive_type: SensitiveType::Custom,
            was_masked: false,
        }
    }

    fn apply_mask(&self, value: &str, rule: &MaskingRule) -> String {
        match rule.sensitive_type {
            SensitiveType::Email => self.mask_email(value, rule),
            SensitiveType::Phone => self.mask_phone(value, rule),
            SensitiveType::IdCard => self.mask_id_card(value, rule),
            SensitiveType::CreditCard => self.mask_credit_card(value, rule),
            SensitiveType::Address => self.mask_address(value, rule),
            SensitiveType::Custom => self.mask_custom(value, rule),
        }
    }

    fn mask_email(&self, email: &str, rule: &MaskingRule) -> String {
        let parts: Vec<&str> = email.split('@').collect();
        if parts.len() != 2 {
            return self.mask_custom(email, rule);
        }
        
        let local = parts[0];
        let domain = parts[1];
        
        if local.len() <= 2 {
            return format!("{}@{}", rule.mask_pattern, domain);
        }
        
        let keep_prefix = 2;
        let masked_local: String = local.chars().take(keep_prefix).collect();
        let mask_length = if rule.preserve_length {
            local.len() - keep_prefix
        } else {
            rule.mask_pattern.len()
        };
        
        let mask: String = if rule.preserve_length {
            "*".repeat(mask_length)
        } else {
            rule.mask_pattern.clone()
        };
        
        format!("{}{}@{}", masked_local, mask, domain)
    }

    fn mask_phone(&self, phone: &str, rule: &MaskingRule) -> String {
        let digits: String = phone.chars().filter(|c| c.is_ascii_digit()).collect();
        
        if digits.len() < 7 {
            return self.mask_custom(phone, rule);
        }
        
        let keep_prefix = 3;
        let keep_suffix = 4;
        let mask_middle = digits.len() - keep_prefix - keep_suffix;
        
        if mask_middle <= 0 {
            return self.mask_custom(phone, rule);
        }
        
        let prefix: String = digits.chars().take(keep_prefix).collect();
        let suffix: String = digits.chars().rev().take(keep_suffix).collect::<String>().chars().rev().collect();
        let mask = "*".repeat(mask_middle);
        
        if rule.preserve_format {
            let mut result = String::new();
            let mut digit_idx = 0;
            
            for c in phone.chars() {
                if c.is_ascii_digit() {
                    if digit_idx < keep_prefix {
                        result.push(c);
                    } else if digit_idx >= digits.len() - keep_suffix {
                        result.push(c);
                    } else {
                        result.push('*');
                    }
                    digit_idx += 1;
                } else {
                    result.push(c);
                }
            }
            result
        } else {
            format!("{}{}{}", prefix, "*".repeat(mask_middle), suffix)
        }
    }

    fn mask_id_card(&self, id_card: &str, rule: &MaskingRule) -> String {
        let keep_prefix = 6;
        let keep_suffix = 4;
        
        if id_card.len() < keep_prefix + keep_suffix {
            return self.mask_custom(id_card, rule);
        }
        
        let prefix: String = id_card.chars().take(keep_prefix).collect();
        let suffix: String = id_card.chars().rev().take(keep_suffix).collect::<String>().chars().rev().collect();
        let mask_len = id_card.len() - keep_prefix - keep_suffix;
        let mask = "*".repeat(mask_len);
        
        format!("{}{}{}", prefix, mask, suffix)
    }

    fn mask_credit_card(&self, card: &str, rule: &MaskingRule) -> String {
        let digits: String = card.chars().filter(|c| c.is_ascii_digit()).collect();
        
        if digits.len() < 13 {
            return self.mask_custom(card, rule);
        }
        
        let keep_prefix = 4;
        let keep_suffix = 4;
        
        let prefix: String = digits.chars().take(keep_prefix).collect();
        let suffix: String = digits.chars().rev().take(keep_suffix).collect::<String>().chars().rev().collect();
        
        if rule.preserve_format {
            let mut result = String::new();
            let mut digit_idx = 0;
            
            for c in card.chars() {
                if c.is_ascii_digit() {
                    if digit_idx < keep_prefix || digit_idx >= digits.len() - keep_suffix {
                        result.push(c);
                    } else {
                        result.push('*');
                    }
                    digit_idx += 1;
                } else {
                    result.push(c);
                }
            }
            result
        } else {
            format!("{} **** **** {}", prefix, suffix)
        }
    }

    fn mask_address(&self, address: &str, rule: &MaskingRule) -> String {
        if address.len() <= 6 {
            return rule.mask_pattern.clone();
        }
        
        let keep_prefix = 4;
        let prefix: String = address.chars().take(keep_prefix).collect();
        let mask_len = address.len() - keep_prefix;
        let mask = "*".repeat(mask_len);
        
        format!("{}{}", prefix, mask)
    }

    fn mask_custom(&self, value: &str, rule: &MaskingRule) -> String {
        if rule.preserve_length {
            "*".repeat(value.len())
        } else {
            rule.mask_pattern.clone()
        }
    }

    fn detect_sensitive_type(&self, value: &str, state: &MaskingState) -> SensitiveType {
        if state.regex_patterns[&SensitiveType::Email].is_match(value) {
            return SensitiveType::Email;
        }
        
        if state.regex_patterns[&SensitiveType::CreditCard].is_match(value) {
            return SensitiveType::CreditCard;
        }
        
        if state.regex_patterns[&SensitiveType::IdCard].is_match(value) {
            return SensitiveType::IdCard;
        }
        
        if state.regex_patterns[&SensitiveType::Phone].is_match(value) {
            return SensitiveType::Phone;
        }
        
        if state.regex_patterns[&SensitiveType::Address].is_match(value) {
            return SensitiveType::Address;
        }
        
        SensitiveType::Custom
    }

    pub fn mask_json(
        &self,
        data: &serde_json::Value,
        user_id: Option<&str>,
    ) -> serde_json::Value {
        match data {
            serde_json::Value::Object(obj) => {
                let mut result = serde_json::Map::new();
                for (key, value) in obj {
                    if let serde_json::Value::String(s) = value {
                        let masking_result = self.mask_value(key, s, user_id);
                        result.insert(key.clone(), serde_json::Value::String(masking_result.masked_value));
                    } else if value.is_object() || value.is_array() {
                        result.insert(key.clone(), self.mask_json(value, user_id));
                    } else {
                        result.insert(key.clone(), value.clone());
                    }
                }
                serde_json::Value::Object(result)
            }
            serde_json::Value::Array(arr) => {
                let result: Vec<serde_json::Value> = arr
                    .iter()
                    .map(|v| self.mask_json(v, user_id))
                    .collect();
                serde_json::Value::Array(result)
            }
            _ => data.clone(),
        }
    }

    pub fn mask_json_value(
        &self,
        field_name: &str,
        value: &serde_json::Value,
        user_id: Option<&str>,
    ) -> serde_json::Value {
        if let Some(s) = value.as_str() {
            let result = self.mask_value(field_name, s, user_id);
            serde_json::Value::String(result.masked_value)
        } else {
            self.mask_json(value, user_id)
        }
    }

    pub fn get_rules(&self) -> Vec<MaskingRule> {
        let state = self.state.read();
        state.config.rules.clone()
    }

    pub fn get_rule(&self, field_name: &str) -> Option<MaskingRule> {
        let state = self.state.read();
        state.field_rules.get(field_name).cloned()
    }

    pub fn get_config(&self) -> MaskingConfiguration {
        let state = self.state.read();
        state.config.clone()
    }

    pub fn detect_sensitive_fields(&self, data: &serde_json::Value) -> Vec<SensitiveField> {
        let mut sensitive_fields = Vec::new();
        self.detect_fields_recursive(data, "", &mut sensitive_fields);
        sensitive_fields
    }

    fn detect_fields_recursive(
        &self,
        value: &serde_json::Value,
        path: &str,
        results: &mut Vec<SensitiveField>,
    ) {
        let state = self.state.read();
        
        match value {
            serde_json::Value::Object(obj) => {
                for (key, val) in obj {
                    let field_path = if path.is_empty() {
                        key.clone()
                    } else {
                        format!("{}.{}", path, key)
                    };
                    
                    if let Some(s) = val.as_str() {
                        let detected_type = self.detect_sensitive_type(s, &state);
                        if detected_type != SensitiveType::Custom {
                            results.push(SensitiveField {
                                field_name: field_path.clone(),
                                field_type: detected_type,
                                mask_pattern: "****".to_string(),
                            });
                        }
                    } else if val.is_object() || val.is_array() {
                        self.detect_fields_recursive(val, &field_path, results);
                    }
                }
            }
            serde_json::Value::Array(arr) => {
                for (i, val) in arr.iter().enumerate() {
                    let field_path = format!("{}[{}]", path, i);
                    self.detect_fields_recursive(val, &field_path, results);
                }
            }
            _ => {}
        }
    }

    pub fn bulk_mask_values(
        &self,
        values: &[(String, String)],
        user_id: Option<&str>,
    ) -> Vec<MaskingResult> {
        values
            .iter()
            .map(|(field, value)| self.mask_value(field, value, user_id))
            .collect()
    }

    pub fn validate_masking_result(
        &self,
        original: &str,
        masked: &str,
        rule: &MaskingRule,
    ) -> bool {
        if rule.preserve_length {
            return original.len() == masked.len();
        }
        true
    }

    pub fn get_statistics(&self) -> HashMap<String, u64> {
        let state = self.state.read();
        
        let mut stats = HashMap::new();
        stats.insert("total_rules".to_string(), state.config.rules.len() as u64);
        stats.insert("total_permissions".to_string(), state.permissions.len() as u64);
        stats.insert("total_roles".to_string(), state.role_field_map.len() as u64);
        
        let type_counts = state.config.rules.iter().fold(HashMap::new(), |mut counts, rule| {
            *counts.entry(rule.sensitive_type).or_insert(0u64) += 1;
            counts
        });
        
        for (sensitive_type, count) in type_counts {
            let key = format!("{:?}_rules", sensitive_type).to_lowercase();
            stats.insert(key, count);
        }
        
        stats
    }
}

impl Default for DataMaskingService {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_email_masking() {
        let service = DataMaskingService::new();
        
        let rule = MaskingRule {
            field_name: "email".to_string(),
            sensitive_type: SensitiveType::Email,
            mask_pattern: "****".to_string(),
            preserve_length: true,
            preserve_format: true,
            allowed_roles: vec!["admin".to_string()],
        };
        
        service.add_rule(rule);
        
        let result = service.mask_value("email", "john.doe@example.com", None);
        
        assert!(result.was_masked);
        assert!(result.masked_value.contains('@'));
        assert!(result.masked_value.contains("example.com"));
    }

    #[test]
    fn test_phone_masking() {
        let service = DataMaskingService::new();
        
        let rule = MaskingRule {
            field_name: "phone".to_string(),
            sensitive_type: SensitiveType::Phone,
            mask_pattern: "****".to_string(),
            preserve_length: true,
            preserve_format: true,
            allowed_roles: Vec::new(),
        };
        
        service.add_rule(rule);
        
        let result = service.mask_value("phone", "13812345678", None);
        
        assert!(result.was_masked);
        assert!(result.masked_value.contains('*'));
        assert_eq!(result.masked_value.len(), "13812345678".len());
    }

    #[test]
    fn test_credit_card_masking() {
        let service = DataMaskingService::new();
        
        let rule = MaskingRule {
            field_name: "card_number".to_string(),
            sensitive_type: SensitiveType::CreditCard,
            mask_pattern: "****".to_string(),
            preserve_length: false,
            preserve_format: false,
            allowed_roles: Vec::new(),
        };
        
        service.add_rule(rule);
        
        let result = service.mask_value("card_number", "4111-1111-1111-1111", None);
        
        assert!(result.was_masked);
        assert!(result.masked_value.contains("4111"));
        assert!(result.masked_value.contains("1111"));
    }

    #[test]
    fn test_permission_based_access() {
        let service = DataMaskingService::new();
        
        let rule = MaskingRule {
            field_name: "email".to_string(),
            sensitive_type: SensitiveType::Email,
            mask_pattern: "****".to_string(),
            preserve_length: true,
            preserve_format: true,
            allowed_roles: vec!["admin".to_string()],
        };
        
        service.add_rule(rule);
        
        let admin_permission = UserPermission {
            user_id: "admin_001".to_string(),
            roles: vec!["admin".to_string()],
            allowed_fields: Vec::new(),
        };
        
        service.add_permission(admin_permission);
        
        let user_result = service.mask_value("email", "admin@example.com", Some("regular_user"));
        assert!(user_result.was_masked);
        
        let admin_result = service.mask_value("email", "admin@example.com", Some("admin_001"));
        assert!(!admin_result.was_masked);
        assert_eq!(admin_result.masked_value, "admin@example.com");
    }

    #[test]
    fn test_json_masking() {
        let service = DataMaskingService::new();
        
        let rule = MaskingRule {
            field_name: "email".to_string(),
            sensitive_type: SensitiveType::Email,
            mask_pattern: "****".to_string(),
            preserve_length: true,
            preserve_format: true,
            allowed_roles: Vec::new(),
        };
        
        service.add_rule(rule);
        
        let data = serde_json::json!({
            "name": "John Doe",
            "email": "john@example.com",
            "phone": "13812345678",
            "address": {
                "street": "123 Main St",
                "city": "Beijing"
            }
        });
        
        let masked = service.mask_json(&data, None);
        
        assert_eq!(masked["name"], "John Doe");
        assert_ne!(masked["email"], "john@example.com");
        assert!(masked["email"].as_str().unwrap().contains('@'));
    }

    #[test]
    fn test_detect_sensitive_fields() {
        let service = DataMaskingService::new();
        
        let data = serde_json::json!({
            "user": {
                "name": "John",
                "email": "john@example.com",
                "phone": "13812345678",
                "card": "4111111111111111"
            }
        });
        
        let detected = service.detect_sensitive_fields(&data);
        
        assert!(!detected.is_empty());
        
        let field_names: Vec<String> = detected.iter().map(|f| f.field_name.clone()).collect();
        assert!(field_names.iter().any(|f| f == "user.email"));
        assert!(field_names.iter().any(|f| f == "user.phone"));
    }
}
