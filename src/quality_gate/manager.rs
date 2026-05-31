use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use rand::Rng;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use crate::quality_gate::models::{
    Language, RuleSeverity, StaticAnalysisRule, AnalysisIssue,
    QualityGateThreshold, ThresholdOperator, QualityGateResult,
    AnalysisReport, AnalysisSummary, AnalyzeRequest,
};
use crate::utils::error::{Result, PlatformError};
use tracing::{info, warn, error};

#[derive(Debug, Clone, Default)]
struct QualityGateState {
    rules: HashMap<String, StaticAnalysisRule>,
    thresholds: Vec<QualityGateThreshold>,
    reports: Vec<AnalysisReport>,
}

#[derive(Debug, Clone, Default)]
pub struct QualityGateManager {
    state: Arc<RwLock<QualityGateState>>,
}

impl QualityGateManager {
    pub fn new() -> Self {
        let manager = Self {
            state: Arc::new(RwLock::new(QualityGateState::default())),
        };
        manager.init_defaults();
        manager
    }

    fn init_defaults(&self) {
        let default_rules = vec![
            StaticAnalysisRule {
                rule_id: "RUST-001".to_string(),
                name: "Unused Variables".to_string(),
                description: "Detects unused variables".to_string(),
                language: Language::Rust,
                severity: RuleSeverity::Warning,
                enabled: true,
                parameters: HashMap::new(),
            },
            StaticAnalysisRule {
                rule_id: "RUST-002".to_string(),
                name: "Missing Documentation".to_string(),
                description: "Public items should have documentation".to_string(),
                language: Language::Rust,
                severity: RuleSeverity::Info,
                enabled: true,
                parameters: HashMap::new(),
            },
            StaticAnalysisRule {
                rule_id: "RUST-003".to_string(),
                name: "Unsafe Code".to_string(),
                description: "Unsafe code blocks require review".to_string(),
                language: Language::Rust,
                severity: RuleSeverity::Error,
                enabled: true,
                parameters: HashMap::new(),
            },
            StaticAnalysisRule {
                rule_id: "RUST-004".to_string(),
                name: "Error Handling".to_string(),
                description: "Unwrapped Results detected".to_string(),
                language: Language::Rust,
                severity: RuleSeverity::Warning,
                enabled: true,
                parameters: HashMap::new(),
            },
        ];

        let default_thresholds = vec![
            QualityGateThreshold {
                threshold_id: "T001".to_string(),
                metric: "blocker_count".to_string(),
                operator: ThresholdOperator::GreaterThan,
                value: 0.0,
            },
            QualityGateThreshold {
                threshold_id: "T002".to_string(),
                metric: "error_count".to_string(),
                operator: ThresholdOperator::GreaterThan,
                value: 5.0,
            },
            QualityGateThreshold {
                threshold_id: "T003".to_string(),
                metric: "code_coverage".to_string(),
                operator: ThresholdOperator::LessThan,
                value: 60.0,
            },
        ];

        let state = self.state.clone();
        tokio::spawn(async move {
            let mut s = state.write().await;
            for rule in default_rules {
                s.rules.insert(rule.rule_id.clone(), rule);
            }
            s.thresholds = default_thresholds;
        });
    }

    pub async fn add_rule(&self, rule: StaticAnalysisRule) -> Result<()> {
        info!(rule_id = %rule.rule_id, "adding_analysis_rule");
        
        let mut state = self.state.write().await;
        
        if state.rules.contains_key(&rule.rule_id) {
            return Err(PlatformError::Conflict(format!(
                "rule {} already exists", rule.rule_id
            )));
        }
        
        state.rules.insert(rule.rule_id.clone(), rule);
        info!("rule_added");
        Ok(())
    }

    pub async fn get_rule(&self, rule_id: &str) -> Result<StaticAnalysisRule> {
        let state = self.state.read().await;
        state.rules.get(rule_id)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("rule {} not found", rule_id)))
    }

    pub async fn list_rules(&self, language: Option<Language>) -> Result<Vec<StaticAnalysisRule>> {
        let state = self.state.read().await;
        let rules: Vec<StaticAnalysisRule> = state.rules
            .values()
            .filter(|r| {
                if let Some(lang) = language {
                    r.language == lang
                } else {
                    true
                }
            })
            .cloned()
            .collect();
        Ok(rules)
    }

    pub async fn update_rule(&self, rule_id: &str, enabled: bool) -> Result<()> {
        info!(rule_id = %rule_id, enabled = %enabled, "updating_rule");
        
        let mut state = self.state.write().await;
        
        let rule = state.rules.get_mut(rule_id)
            .ok_or_else(|| PlatformError::NotFound(format!("rule {} not found", rule_id)))?;
        
        rule.enabled = enabled;
        info!("rule_updated");
        Ok(())
    }

    pub async fn add_threshold(&self, threshold: QualityGateThreshold) -> Result<()> {
        info!(threshold_id = %threshold.threshold_id, "adding_threshold");
        
        let mut state = self.state.write().await;
        state.thresholds.push(threshold);
        info!("threshold_added");
        Ok(())
    }

    pub async fn list_thresholds(&self) -> Result<Vec<QualityGateThreshold>> {
        let state = self.state.read().await;
        Ok(state.thresholds.clone())
    }

    pub async fn analyze(&self, request: AnalyzeRequest) -> Result<AnalysisReport> {
        info!(
            language = ?request.language,
            source_path = %request.source_path,
            "starting_analysis"
        );

        let state = self.state.read().await;
        let rules: Vec<StaticAnalysisRule> = state.rules
            .values()
            .filter(|r| r.language == request.language && r.enabled)
            .filter(|r| {
                request.rule_overrides
                    .get(&r.rule_id)
                    .copied()
                    .unwrap_or(true)
            })
            .cloned()
            .collect();
        drop(state);

        tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;

        let mut rng = rand::thread_rng();
        let mut issues = Vec::new();
        let mut summary = AnalysisSummary::default();

        for rule in &rules {
            if rng.gen_bool(0.3) {
                let issue = AnalysisIssue {
                    rule_id: rule.rule_id.clone(),
                    severity: rule.severity,
                    message: rule.description.clone(),
                    file_path: format!("src/{}.rs", rng.gen_range(0..5)),
                    line: rng.gen_range(1..100),
                    column: rng.gen_range(1..80),
                };

                match rule.severity {
                    RuleSeverity::Blocker => summary.blocker_count += 1,
                    RuleSeverity::Error => summary.error_count += 1,
                    RuleSeverity::Warning => summary.warning_count += 1,
                    RuleSeverity::Info => summary.info_count += 1,
                }

                issues.push(issue);
            }
        }

        summary.code_coverage = 60.0 + rng.gen::<f64>() * 35.0;
        summary.duplicate_lines = rng.gen_range(0..50);
        summary.security_hotspots = rng.gen_range(0..3);

        let report = AnalysisReport {
            report_id: format!("report_{}", uuid::Uuid::new_v4().simple()),
            language: request.language,
            total_files: rng.gen_range(5..50),
            issues,
            summary,
            generated_at: Utc::now(),
        };

        {
            let mut state = self.state.write().await;
            state.reports.push(report.clone());
        }

        info!(
            report_id = %report.report_id,
            issues = %report.issues.len(),
            "analysis_complete"
        );

        Ok(report)
    }

    pub async fn check_quality_gate(&self, report: &AnalysisReport) -> Result<QualityGateResult> {
        info!(report_id = %report.report_id, "checking_quality_gate");

        let state = self.state.read().await;
        let thresholds = state.thresholds.clone();
        drop(state);

        let mut metrics = HashMap::new();
        metrics.insert("blocker_count".to_string(), report.summary.blocker_count as f64);
        metrics.insert("error_count".to_string(), report.summary.error_count as f64);
        metrics.insert("warning_count".to_string(), report.summary.warning_count as f64);
        metrics.insert("info_count".to_string(), report.summary.info_count as f64);
        metrics.insert("code_coverage".to_string(), report.summary.code_coverage);
        metrics.insert("duplicate_lines".to_string(), report.summary.duplicate_lines as f64);
        metrics.insert("security_hotspots".to_string(), report.summary.security_hotspots as f64);

        let mut failed_thresholds = Vec::new();

        for threshold in &thresholds {
            if let Some(&value) = metrics.get(&threshold.metric) {
                let failed = match threshold.operator {
                    ThresholdOperator::GreaterThan => value > threshold.value,
                    ThresholdOperator::LessThan => value < threshold.value,
                    ThresholdOperator::GreaterThanOrEqual => value >= threshold.value,
                    ThresholdOperator::LessThanOrEqual => value <= threshold.value,
                    ThresholdOperator::Equal => (value - threshold.value).abs() < 0.0001,
                    ThresholdOperator::NotEqual => (value - threshold.value).abs() >= 0.0001,
                };

                if failed {
                    failed_thresholds.push(threshold.threshold_id.clone());
                }
            }
        }

        let result = QualityGateResult {
            passed: failed_thresholds.is_empty(),
            metrics,
            failed_thresholds,
            analysis_report: report.clone(),
        };

        info!(
            report_id = %report.report_id,
            passed = %result.passed,
            failed_count = %result.failed_thresholds.len(),
            "quality_gate_checked"
        );

        Ok(result)
    }

    pub async fn get_report(&self, report_id: &str) -> Result<AnalysisReport> {
        let state = self.state.read().await;
        state.reports
            .iter()
            .find(|r| r.report_id == report_id)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!(
                "report {} not found", report_id
            )))
    }

    pub async fn list_reports(&self, limit: usize) -> Result<Vec<AnalysisReport>> {
        let state = self.state.read().await;
        let reports: Vec<AnalysisReport> = state.reports
            .iter()
            .rev()
            .take(limit)
            .cloned()
            .collect();
        Ok(reports)
    }
}
