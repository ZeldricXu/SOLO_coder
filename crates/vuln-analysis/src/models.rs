use chrono::DateTime;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PackageType {
    Npm,
    PyPI,
    Cargo,
    Maven,
    Go,
    NuGet,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SbomFormat {
    Spdx,
    CycloneDX,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SbomPackage {
    pub name: String,
    pub version: String,
    pub package_type: PackageType,
    pub purl: Option<String>,
    pub license: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SbomDocument {
    pub format: SbomFormat,
    pub name: String,
    pub version: String,
    pub packages: Vec<SbomPackage>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum Severity {
    Critical,
    High,
    Medium,
    Low,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AffectedPackage {
    pub name: String,
    pub package_type: PackageType,
    pub vulnerable_versions: String,
    pub patched_versions: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CveVulnerability {
    pub cve_id: String,
    pub title: String,
    pub description: String,
    pub severity: Severity,
    pub cvss_score: f64,
    pub affected_packages: Vec<AffectedPackage>,
    pub published_date: String,
    pub modified_date: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VulnerabilityMatch {
    pub package: SbomPackage,
    pub vulnerability: CveVulnerability,
    pub match_reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum FixType {
    Upgrade,
    Patch,
    Replace,
    NoFix,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FixRecommendation {
    pub package_name: String,
    pub current_version: String,
    pub recommended_version: String,
    pub fix_type: FixType,
    pub confidence: f64,
    pub details: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnalysisReport {
    pub id: Uuid,
    pub sbom_name: String,
    pub analyzed_at: DateTime<chrono::Utc>,
    pub total_packages: usize,
    pub vulnerable_packages: usize,
    pub matches: Vec<VulnerabilityMatch>,
    pub recommendations: Vec<FixRecommendation>,
    pub risk_score: f64,
}
