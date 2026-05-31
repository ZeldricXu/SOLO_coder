use anyhow::Result;
use serde::{Deserialize, Serialize};

use crate::models::QualityReport;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum Trend {
    Improving,
    Degrading,
    Stable,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReportDiff {
    pub new_violations: usize,
    pub fixed_violations: usize,
    pub trend: Trend,
}

pub struct ReportGenerator;

impl ReportGenerator {
    pub fn generate_json(report: &QualityReport) -> Result<String> {
        Ok(serde_json::to_string_pretty(report)?)
    }

    pub fn generate_summary(report: &QualityReport) -> String {
        let status = if report.passed { "PASSED" } else { "FAILED" };
        format!(
            "Quality Gate: {} [{}]\n\
             Gate: {} ({})\n\
             Total violations: {}\n\
             Critical: {} | High: {} | Medium: {}\n\
             Files checked: {}",
            status,
            if report.passed { "✓" } else { "✗" },
            report.gate_name,
            report.gate_id,
            report.total_violations,
            report.critical_count,
            report.high_count,
            report.medium_count,
            report.file_count,
        )
    }

    pub fn compare_reports(current: &QualityReport, previous: &QualityReport) -> ReportDiff {
        let current_set: std::collections::HashSet<_> = current
            .violations
            .iter()
            .map(|v| (&v.rule_id, &v.file_path, v.line_number))
            .collect();
        let previous_set: std::collections::HashSet<_> = previous
            .violations
            .iter()
            .map(|v| (&v.rule_id, &v.file_path, v.line_number))
            .collect();

        let new_violations = current_set.difference(&previous_set).count();
        let fixed_violations = previous_set.difference(&current_set).count();

        let trend = if new_violations > fixed_violations {
            Trend::Degrading
        } else if fixed_violations > new_violations {
            Trend::Improving
        } else {
            Trend::Stable
        };

        ReportDiff {
            new_violations,
            fixed_violations,
            trend,
        }
    }
}
