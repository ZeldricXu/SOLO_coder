use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use crate::models::StreamSQLError;
use super::rules::{QualityRule, RuleResult, RuleSet, get_evaluator};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationReport {
    pub report_id: String,
    pub table_name: String,
    pub total_rules: usize,
    pub passed_rules: usize,
    pub failed_rules: usize,
    pub results: Vec<RuleResult>,
    pub started_at: chrono::DateTime<chrono::Utc>,
    pub completed_at: chrono::DateTime<chrono::Utc>,
    pub duration_ms: u64,
}

pub struct QualityEngine {
    rules: Arc<RwLock<HashMap<String, QualityRule>>>,
    rule_sets: Arc<RwLock<HashMap<String, RuleSet>>>,
    reports: Arc<Mutex<Vec<ValidationReport>>>,
}

impl Default for QualityEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl QualityEngine {
    pub fn new() -> Self {
        Self {
            rules: Arc::new(RwLock::new(HashMap::new())),
            rule_sets: Arc::new(RwLock::new(HashMap::new())),
            reports: Arc::new(Mutex::new(Vec::new())),
        }
    }

    pub async fn add_rule(&self, rule: QualityRule) {
        self.rules.write().await.insert(rule.id.clone(), rule);
    }

    pub async fn add_rule_set(&self, rule_set: RuleSet) {
        self.rule_sets.write().await.insert(rule_set.id.clone(), rule_set);
    }

    pub async fn get_rule(&self, rule_id: &str) -> Option<QualityRule> {
        self.rules.read().await.get(rule_id).cloned()
    }

    pub async fn get_rule_set(&self, rule_set_id: &str) -> Option<RuleSet> {
        self.rule_sets.read().await.get(rule_set_id).cloned()
    }

    pub async fn list_rules(&self) -> Vec<QualityRule> {
        self.rules.read().await.values().cloned().collect()
    }

    pub async fn validate_table(
        &self,
        table_name: &str,
        data: &[serde_json::Value],
    ) -> Result<ValidationReport, StreamSQLError> {
        let start = std::time::Instant::now();
        let started_at = chrono::Utc::now();

        let relevant_rules: Vec<QualityRule> = self
            .rules
            .read()
            .await
            .values()
            .filter(|r| r.table_name == table_name && r.enabled)
            .cloned()
            .collect();

        let mut results = Vec::new();
        let mut passed_count = 0;
        let mut failed_count = 0;

        for rule in &relevant_rules {
            let rule_start = std::time::Instant::now();
            
            let evaluator = get_evaluator(rule.rule_type);
            let mut result = match evaluator.evaluate(rule, data) {
                Ok(r) => r,
                Err(e) => RuleResult::failed(
                    rule,
                    format!("Evaluation error: {}", e),
                    data.len() as u64,
                    data.len() as u64,
                    Vec::new(),
                ),
            };

            result.execution_time_ms = rule_start.elapsed().as_millis() as u64;
            
            if result.passed {
                passed_count += 1;
            } else {
                failed_count += 1;
            }

            results.push(result);
        }

        let completed_at = chrono::Utc::now();
        let duration_ms = start.elapsed().as_millis() as u64;

        let report = ValidationReport {
            report_id: crate::models::IdGenerator::generate("report"),
            table_name: table_name.to_string(),
            total_rules: relevant_rules.len(),
            passed_rules: passed_count,
            failed_rules: failed_count,
            results,
            started_at,
            completed_at,
            duration_ms,
        };

        self.reports.lock().await.push(report.clone());

        Ok(report)
    }

    pub async fn validate_with_rules(
        &self,
        rules: &[QualityRule],
        data: &[serde_json::Value],
    ) -> Result<Vec<RuleResult>, StreamSQLError> {
        let mut results = Vec::new();

        for rule in rules {
            if !rule.enabled {
                continue;
            }

            let rule_start = std::time::Instant::now();
            let evaluator = get_evaluator(rule.rule_type);
            
            let mut result = match evaluator.evaluate(rule, data) {
                Ok(r) => r,
                Err(e) => RuleResult::failed(
                    rule,
                    format!("Evaluation error: {}", e),
                    data.len() as u64,
                    data.len() as u64,
                    Vec::new(),
                ),
            };

            result.execution_time_ms = rule_start.elapsed().as_millis() as u64;
            results.push(result);
        }

        Ok(results)
    }

    pub async fn run_rule_set(
        &self,
        rule_set_id: &str,
        data: &[serde_json::Value],
    ) -> Result<ValidationReport, StreamSQLError> {
        let rule_set = self
            .get_rule_set(rule_set_id)
            .await
            .ok_or_else(|| StreamSQLError::Quality(format!("Rule set {} not found", rule_set_id)))?;

        self.validate_with_rules(&rule_set.rules, data)
            .await
            .map(|results| {
                let start = std::time::Instant::now();
                let passed = results.iter().filter(|r| r.passed).count();
                let failed = results.iter().filter(|r| !r.passed).count();

                ValidationReport {
                    report_id: crate::models::IdGenerator::generate("report"),
                    table_name: rule_set.table_name,
                    total_rules: results.len(),
                    passed_rules: passed,
                    failed_rules: failed,
                    results,
                    started_at: chrono::Utc::now(),
                    completed_at: chrono::Utc::now(),
                    duration_ms: start.elapsed().as_millis() as u64,
                }
            })
    }

    pub async fn get_reports(&self, table_name: Option<&str>) -> Vec<ValidationReport> {
        let reports = self.reports.lock().await.clone();
        
        match table_name {
            Some(name) => reports
                .into_iter()
                .filter(|r| r.table_name == name)
                .collect(),
            None => reports,
        }
    }

    pub async fn get_latest_report(&self, table_name: &str) -> Option<ValidationReport> {
        self.reports
            .lock()
            .await
            .iter()
            .filter(|r| r.table_name == table_name)
            .max_by_key(|r| r.completed_at)
            .cloned()
    }

    pub async fn get_overall_quality_score(&self, table_name: &str) -> f64 {
        let reports = self.get_reports(Some(table_name)).await;
        if reports.is_empty() {
            return 100.0;
        }

        let latest = reports.iter().max_by_key(|r| r.completed_at);
        
        match latest {
            Some(report) => {
                if report.total_rules == 0 {
                    100.0
                } else {
                    (report.passed_rules as f64 / report.total_rules as f64) * 100.0
                }
            }
            None => 100.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityDashboard {
    pub total_tables: usize,
    pub total_rules: usize,
    pub total_reports: usize,
    pub overall_score: f64,
    pub failing_tables: Vec<String>,
    pub recent_reports: Vec<ValidationReport>,
}

impl QualityEngine {
    pub async fn get_dashboard(&self) -> QualityDashboard {
        let reports = self.reports.lock().await.clone();
        let rules = self.list_rules().await;

        let tables: std::collections::HashSet<String> = rules
            .iter()
            .map(|r| r.table_name.clone())
            .collect();

        let mut failing = Vec::new();
        let mut scores = HashMap::new();

        for table in &tables {
            let score = self.get_overall_quality_score(table).await;
            scores.insert(table.clone(), score);
            if score < 100.0 {
                failing.push(table.clone());
            }
        }

        let overall_score = if scores.is_empty() {
            100.0
        } else {
            scores.values().sum::<f64>() / scores.len() as f64
        };

        let mut recent: Vec<_> = reports.clone();
        recent.sort_by(|a, b| b.completed_at.cmp(&a.completed_at));
        let recent = recent.into_iter().take(10).collect();

        QualityDashboard {
            total_tables: tables.len(),
            total_rules: rules.len(),
            total_reports: reports.len(),
            overall_score,
            failing_tables: failing,
            recent_reports: recent,
        }
    }
}
