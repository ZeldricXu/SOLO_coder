use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Language {
    Rust,
    Go,
    Python,
    TypeScript,
    JavaScript,
    Java,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RuleSeverity {
    Info,
    Warning,
    Error,
    Blocker,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StaticAnalysisRule {
    pub rule_id: String,
    pub name: String,
    pub description: String,
    pub language: Language,
    pub severity: RuleSeverity,
    pub enabled: bool,
    #[serde(default)]
    pub parameters: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisIssue {
    pub rule_id: String,
    pub severity: RuleSeverity,
    pub message: String,
    pub file_path: String,
    pub line: u32,
    pub column: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityGateThreshold {
    pub threshold_id: String,
    pub metric: String,
    pub operator: ThresholdOperator,
    pub value: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ThresholdOperator {
    GreaterThan,
    LessThan,
    GreaterThanOrEqual,
    LessThanOrEqual,
    Equal,
    NotEqual,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityGateResult {
    pub passed: bool,
    pub metrics: HashMap<String, f64>,
    pub failed_thresholds: Vec<String>,
    pub analysis_report: AnalysisReport,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisReport {
    pub report_id: String,
    pub language: Language,
    pub total_files: usize,
    pub issues: Vec<AnalysisIssue>,
    pub summary: AnalysisSummary,
    pub generated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AnalysisSummary {
    pub blocker_count: usize,
    pub error_count: usize,
    pub warning_count: usize,
    pub info_count: usize,
    pub code_coverage: f64,
    pub duplicate_lines: usize,
    pub security_hotspots: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalyzeRequest {
    pub language: Language,
    pub source_path: String,
    #[serde(default)]
    pub rule_overrides: HashMap<String, bool>,
}
