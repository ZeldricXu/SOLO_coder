use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RuleType {
    NullCheck,
    RangeCheck,
    RegexMatch,
    Uniqueness,
    ReferentialIntegrity,
    Custom,
    FormatCheck,
    Completeness,
    Consistency,
    Timeliness,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    Low,
    Medium,
    High,
    Critical,
}

impl Default for Severity {
    fn default() -> Self {
        Severity::Medium
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityRule {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub rule_type: RuleType,
    pub expression: String,
    pub table_name: String,
    pub column_name: Option<String>,
    pub severity: Severity,
    pub enabled: bool,
    pub parameters: HashMap<String, serde_json::Value>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

impl QualityRule {
    pub fn new(
        name: impl Into<String>,
        rule_type: RuleType,
        expression: impl Into<String>,
        table_name: impl Into<String>,
    ) -> Self {
        let now = chrono::Utc::now();
        Self {
            id: crate::models::IdGenerator::generate("rule"),
            name: name.into(),
            description: None,
            rule_type,
            expression: expression.into(),
            table_name: table_name.into(),
            column_name: None,
            severity: Severity::Medium,
            enabled: true,
            parameters: HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_description(mut self, desc: impl Into<String>) -> Self {
        self.description = Some(desc.into());
        self
    }

    pub fn with_column(mut self, column: impl Into<String>) -> Self {
        self.column_name = Some(column.into());
        self
    }

    pub fn with_severity(mut self, severity: Severity) -> Self {
        self.severity = severity;
        self
    }

    pub fn with_parameter(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.parameters.insert(key.into(), value);
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleResult {
    pub rule_id: String,
    pub rule_name: String,
    pub passed: bool,
    pub severity: Severity,
    pub message: Option<String>,
    pub row_count: u64,
    pub invalid_count: u64,
    pub invalid_rows: Vec<serde_json::Value>,
    pub executed_at: chrono::DateTime<chrono::Utc>,
    pub execution_time_ms: u64,
}

impl RuleResult {
    pub fn passed(rule: &QualityRule, row_count: u64) -> Self {
        Self {
            rule_id: rule.id.clone(),
            rule_name: rule.name.clone(),
            passed: true,
            severity: rule.severity,
            message: None,
            row_count,
            invalid_count: 0,
            invalid_rows: Vec::new(),
            executed_at: chrono::Utc::now(),
            execution_time_ms: 0,
        }
    }

    pub fn failed(
        rule: &QualityRule,
        message: impl Into<String>,
        row_count: u64,
        invalid_count: u64,
        invalid_rows: Vec<serde_json::Value>,
    ) -> Self {
        Self {
            rule_id: rule.id.clone(),
            rule_name: rule.name.clone(),
            passed: false,
            severity: rule.severity,
            message: Some(message.into()),
            row_count,
            invalid_count,
            invalid_rows,
            executed_at: chrono::Utc::now(),
            execution_time_ms: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleSet {
    pub id: String,
    pub name: String,
    pub rules: Vec<QualityRule>,
    pub table_name: String,
    pub schedule: Option<ScheduleConfig>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduleConfig {
    pub cron_expression: String,
    pub timezone: String,
    pub max_retries: u32,
    pub retry_delay_ms: u64,
}

impl Default for ScheduleConfig {
    fn default() -> Self {
        Self {
            cron_expression: "0 */15 * * * *".to_string(),
            timezone: "UTC".to_string(),
            max_retries: 3,
            retry_delay_ms: 5000,
        }
    }
}

pub trait RuleEvaluator: Send + Sync {
    fn evaluate(
        &self,
        rule: &QualityRule,
        data: &[serde_json::Value],
    ) -> Result<RuleResult, StreamSQLError>;
}

pub struct NullCheckEvaluator;

impl RuleEvaluator for NullCheckEvaluator {
    fn evaluate(
        &self,
        rule: &QualityRule,
        data: &[serde_json::Value],
    ) -> Result<RuleResult, StreamSQLError> {
        let column = rule.column_name.as_ref().ok_or_else(|| {
            StreamSQLError::Quality("Null check rule requires column name".into())
        })?;

        let row_count = data.len() as u64;
        let mut invalid_count = 0;
        let mut invalid_rows = Vec::new();

        for row in data {
            if let Some(val) = row.get(column) {
                if val.is_null() {
                    invalid_count += 1;
                    if invalid_rows.len() < 100 {
                        invalid_rows.push(row.clone());
                    }
                }
            } else {
                invalid_count += 1;
                if invalid_rows.len() < 100 {
                    invalid_rows.push(row.clone());
                }
            }
        }

        if invalid_count > 0 {
            Ok(RuleResult::failed(
                rule,
                format!("Found {} null values in column {}", invalid_count, column),
                row_count,
                invalid_count,
                invalid_rows,
            ))
        } else {
            Ok(RuleResult::passed(rule, row_count))
        }
    }
}

pub struct RangeCheckEvaluator;

impl RuleEvaluator for RangeCheckEvaluator {
    fn evaluate(
        &self,
        rule: &QualityRule,
        data: &[serde_json::Value],
    ) -> Result<RuleResult, StreamSQLError> {
        let column = rule.column_name.as_ref().ok_or_else(|| {
            StreamSQLError::Quality("Range check rule requires column name".into())
        })?;
        
        let min = rule.parameters.get("min").and_then(|v| v.as_f64());
        let max = rule.parameters.get("max").and_then(|v| v.as_f64());

        let row_count = data.len() as u64;
        let mut invalid_count = 0;
        let mut invalid_rows = Vec::new();

        for row in data {
            if let Some(val) = row.get(column).and_then(|v| v.as_f64()) {
                let out_of_range = match (min, max) {
                    (Some(mn), Some(mx)) => val < mn || val > mx,
                    (Some(mn), None) => val < mn,
                    (None, Some(mx)) => val > mx,
                    (None, None) => false,
                };

                if out_of_range {
                    invalid_count += 1;
                    if invalid_rows.len() < 100 {
                        invalid_rows.push(row.clone());
                    }
                }
            }
        }

        if invalid_count > 0 {
            Ok(RuleResult::failed(
                rule,
                format!(
                    "Found {} values out of range in column {} (min={:?}, max={:?})",
                    invalid_count, column, min, max
                ),
                row_count,
                invalid_count,
                invalid_rows,
            ))
        } else {
            Ok(RuleResult::passed(rule, row_count))
        }
    }
}

pub struct RegexMatchEvaluator;

impl RuleEvaluator for RegexMatchEvaluator {
    fn evaluate(
        &self,
        rule: &QualityRule,
        data: &[serde_json::Value],
    ) -> Result<RuleResult, StreamSQLError> {
        let column = rule.column_name.as_ref().ok_or_else(|| {
            StreamSQLError::Quality("Regex check rule requires column name".into())
        })?;
        
        let pattern = rule.parameters.get("pattern").and_then(|v| v.as_str()).ok_or_else(|| {
            StreamSQLError::Quality("Regex check rule requires pattern parameter".into())
        })?;

        let regex = regex::Regex::new(pattern).map_err(|e| {
            StreamSQLError::Quality(format!("Invalid regex pattern: {}", e))
        })?;

        let row_count = data.len() as u64;
        let mut invalid_count = 0;
        let mut invalid_rows = Vec::new();

        for row in data {
            if let Some(val) = row.get(column).and_then(|v| v.as_str()) {
                if !regex.is_match(val) {
                    invalid_count += 1;
                    if invalid_rows.len() < 100 {
                        invalid_rows.push(row.clone());
                    }
                }
            }
        }

        if invalid_count > 0 {
            Ok(RuleResult::failed(
                rule,
                format!("Found {} values not matching pattern '{}' in column {}", invalid_count, pattern, column),
                row_count,
                invalid_count,
                invalid_rows,
            ))
        } else {
            Ok(RuleResult::passed(rule, row_count))
        }
    }
}

pub struct UniquenessEvaluator;

impl RuleEvaluator for UniquenessEvaluator {
    fn evaluate(
        &self,
        rule: &QualityRule,
        data: &[serde_json::Value],
    ) -> Result<RuleResult, StreamSQLError> {
        let column = rule.column_name.as_ref().ok_or_else(|| {
            StreamSQLError::Quality("Uniqueness rule requires column name".into())
        })?;

        let mut seen = std::collections::HashSet::new();
        let row_count = data.len() as u64;
        let mut invalid_count = 0;
        let mut invalid_rows = Vec::new();

        for row in data {
            if let Some(val) = row.get(column) {
                if !seen.insert(val.to_string()) {
                    invalid_count += 1;
                    if invalid_rows.len() < 100 {
                        invalid_rows.push(row.clone());
                    }
                }
            }
        }

        if invalid_count > 0 {
            Ok(RuleResult::failed(
                rule,
                format!("Found {} duplicate values in column {}", invalid_count, column),
                row_count,
                invalid_count,
                invalid_rows,
            ))
        } else {
            Ok(RuleResult::passed(rule, row_count))
        }
    }
}

pub fn get_evaluator(rule_type: RuleType) -> Box<dyn RuleEvaluator> {
    match rule_type {
        RuleType::NullCheck => Box::new(NullCheckEvaluator),
        RuleType::RangeCheck => Box::new(RangeCheckEvaluator),
        RuleType::RegexMatch => Box::new(RegexMatchEvaluator),
        RuleType::Uniqueness => Box::new(UniquenessEvaluator),
        _ => Box::new(NullCheckEvaluator),
    }
}
