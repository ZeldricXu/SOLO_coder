use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum Language {
    Rust,
    TypeScript,
    Python,
    Go,
    Java,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum Severity {
    Critical,
    High,
    Medium,
    Low,
    Info,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleDefinition {
    pub id: Uuid,
    pub name: String,
    pub language: Language,
    pub severity: Severity,
    pub pattern: String,
    pub description: String,
    pub enabled: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GateThresholds {
    pub max_critical: u32,
    pub max_high: u32,
    pub max_medium: u32,
    pub coverage_min: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityGate {
    pub id: Uuid,
    pub name: String,
    pub description: String,
    pub rules: Vec<Uuid>,
    pub thresholds: GateThresholds,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Violation {
    pub rule_id: Uuid,
    pub file_path: String,
    pub line_number: u32,
    pub message: String,
    pub severity: Severity,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityReport {
    pub gate_id: Uuid,
    pub gate_name: String,
    pub passed: bool,
    pub violations: Vec<Violation>,
    pub total_violations: usize,
    pub critical_count: usize,
    pub high_count: usize,
    pub medium_count: usize,
    pub checked_at: DateTime<Utc>,
    pub file_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceFile {
    pub path: String,
    pub language: Language,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CheckRequest {
    pub gate_id: Uuid,
    pub source_files: Vec<SourceFile>,
}
