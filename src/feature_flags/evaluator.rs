use regex::Regex;
use sha2::{Sha256, Digest};
use hex::ToHex;
use crate::feature_flags::models::{FeatureFlag, Rule, Condition, UserContext};
use crate::utils::error::{Result, PlatformError};
use std::collections::HashMap;
use serde_json::Value;

pub struct RuleEvaluator;

impl RuleEvaluator {
    pub fn evaluate(flag: &FeatureFlag, user: &UserContext) -> bool {
        if !flag.enabled {
            return false;
        }

        let mut matched_segment = false;
        if !flag.user_segments.is_empty() {
            for segment_id in &flag.user_segments {
                if user.segments.contains(segment_id) {
                    matched_segment = true;
                    break;
                }
            }
            if !matched_segment {
                return false;
            }
        }

        let mut rules_matched = true;
        if !flag.rules.is_empty() {
            rules_matched = flag.rules.iter().all(|rule| {
                Self::evaluate_rule(rule, user)
            });
        }

        if !rules_matched {
            return false;
        }

        if flag.target_percentage < 100.0 {
            Self::consistent_hash_percentage(&user.user_id, flag.target_percentage)
        } else {
            true
        }
    }

    fn evaluate_rule(rule: &Rule, user: &UserContext) -> bool {
        match &rule.condition {
            Condition::Equals { field, value } => {
                user.attributes.get(field)
                    .map(|v| Self::value_to_string(v) == *value)
                    .unwrap_or(false)
            }
            Condition::Contains { field, value } => {
                user.attributes.get(field)
                    .map(|v| Self::value_to_string(v).contains(value))
                    .unwrap_or(false)
            }
            Condition::GreaterThan { field, value } => {
                user.attributes.get(field)
                    .and_then(|v| Self::value_to_f64(v))
                    .map(|n| n > *value)
                    .unwrap_or(false)
            }
            Condition::LessThan { field, value } => {
                user.attributes.get(field)
                    .and_then(|v| Self::value_to_f64(v))
                    .map(|n| n < *value)
                    .unwrap_or(false)
            }
            Condition::InList { field, values } => {
                user.attributes.get(field)
                    .map(|v| values.contains(&Self::value_to_string(v)))
                    .unwrap_or(false)
            }
            Condition::RegexMatch { field, pattern } => {
                if let Ok(re) = Regex::new(pattern) {
                    user.attributes.get(field)
                        .map(|v| re.is_match(&Self::value_to_string(v)))
                        .unwrap_or(false)
                } else {
                    false
                }
            }
        }
    }

    fn value_to_string(value: &Value) -> String {
        match value {
            Value::String(s) => s.clone(),
            Value::Number(n) => n.to_string(),
            Value::Bool(b) => b.to_string(),
            Value::Null => "null".to_string(),
            Value::Array(_) => "[]".to_string(),
            Value::Object(_) => "{}".to_string(),
        }
    }

    fn value_to_f64(value: &Value) -> Option<f64> {
        match value {
            Value::Number(n) => n.as_f64(),
            Value::String(s) => s.parse::<f64>().ok(),
            _ => None,
        }
    }

    fn consistent_hash_percentage(user_id: &str, percentage: f64) -> bool {
        let mut hasher = Sha256::new();
        hasher.update(user_id.as_bytes());
        let result = hasher.finalize();
        let hex: String = result.encode_hex::<String>();
        
        let hash_int = u64::from_str_radix(&hex[..16], 16).unwrap_or(0);
        let user_percent = (hash_int % 10000) as f64 / 100.0;
        
        user_percent < percentage
    }

    pub fn evaluate_matched_rules(
        flag: &FeatureFlag,
        user: &UserContext,
    ) -> (bool, Vec<String>) {
        if !flag.enabled {
            return (false, vec![]);
        }

        let mut matched_segment = false;
        if !flag.user_segments.is_empty() {
            for segment_id in &flag.user_segments {
                if user.segments.contains(segment_id) {
                    matched_segment = true;
                    break;
                }
            }
            if !matched_segment {
                return (false, vec![]);
            }
        }

        let mut matched_rule_ids = Vec::new();
        let mut rules_matched = true;
        if !flag.rules.is_empty() {
            for rule in &flag.rules {
                if Self::evaluate_rule(rule, user) {
                    matched_rule_ids.push(rule.rule_id.clone());
                } else {
                    rules_matched = false;
                }
            }
        }

        if !rules_matched {
            return (false, matched_rule_ids);
        }

        let percentage_passed = if flag.target_percentage < 100.0 {
            Self::consistent_hash_percentage(&user.user_id, flag.target_percentage)
        } else {
            true
        };

        (percentage_passed, matched_rule_ids)
    }
}
