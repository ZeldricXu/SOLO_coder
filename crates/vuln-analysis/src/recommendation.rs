use crate::models::{FixRecommendation, FixType, Severity, VulnerabilityMatch};

pub struct RecommendationEngine;

impl RecommendationEngine {
    pub fn generate_recommendations(matches: &[VulnerabilityMatch]) -> Vec<FixRecommendation> {
        matches.iter().map(|m| {
            let has_patch = m.vulnerability.affected_packages.iter().any(|ap| {
                ap.patched_versions.is_some() && ap.name == m.package.name
            });
            let (fix_type, recommended_version, confidence, details) = if has_patch {
                let patched = m.vulnerability.affected_packages.iter().find_map(|ap| {
                    if ap.name == m.package.name {
                        ap.patched_versions.clone()
                    } else {
                        None
                    }
                }).unwrap_or_default();
                let conf = match m.vulnerability.severity {
                    Severity::Critical => 0.95,
                    Severity::High => 0.90,
                    Severity::Medium => 0.85,
                    Severity::Low => 0.80,
                };
                (FixType::Upgrade, patched, conf, format!("Upgrade {} to patched version to resolve {}", m.package.name, m.vulnerability.cve_id))
            } else {
                let conf = match m.vulnerability.severity {
                    Severity::Critical => 0.40,
                    Severity::High => 0.50,
                    Severity::Medium => 0.60,
                    Severity::Low => 0.70,
                };
                (FixType::NoFix, "N/A".to_string(), conf, format!("No patched version available for {} affected by {}", m.package.name, m.vulnerability.cve_id))
            };
            FixRecommendation {
                package_name: m.package.name.clone(),
                current_version: m.package.version.clone(),
                recommended_version,
                fix_type,
                confidence,
                details,
            }
        }).collect()
    }

    pub fn calculate_risk_score(matches: &[VulnerabilityMatch]) -> f64 {
        if matches.is_empty() {
            return 0.0;
        }
        let total_weight: f64 = matches.iter().map(|m| {
            match m.vulnerability.severity {
                Severity::Critical => m.vulnerability.cvss_score * 1.5,
                Severity::High => m.vulnerability.cvss_score * 1.2,
                Severity::Medium => m.vulnerability.cvss_score,
                Severity::Low => m.vulnerability.cvss_score * 0.8,
            }
        }).sum();
        let score = total_weight / matches.len() as f64;
        score.min(10.0)
    }
}
