use anyhow::Result;
use regex::Regex;
use std::sync::Arc;

use crate::models::{
    CheckRequest, GateThresholds, QualityReport, RuleDefinition, SourceFile, Violation,
};
use crate::rules::RuleManager;
use crate::strategy::{QualityCheckStrategy, StrategyManager, StrategyType};

pub struct QualityChecker {
    strategy_manager: Arc<StrategyManager>,
}

impl QualityChecker {
    pub fn new(strategy_manager: Arc<StrategyManager>) -> Self {
        Self { strategy_manager }
    }

    pub fn with_default_strategy() -> Self {
        Self { strategy_manager: Arc::new(StrategyManager::new()) }
    }

    pub fn strategy_manager(&self) -> Arc<StrategyManager> {
        self.strategy_manager.clone()
    }

    pub fn check(&self, request: CheckRequest, rule_manager: &RuleManager) -> Result<QualityReport> {
        let gate = rule_manager
            .get_gate(request.gate_id)?
            .ok_or_else(|| anyhow::anyhow!("gate not found: {}", request.gate_id))?;

        let mut all_rules: Vec<RuleDefinition> = Vec::new();
        for rule_id in &gate.rules {
            if let Some(rule) = rule_manager.get_rule(*rule_id)? {
                all_rules.push(rule);
            }
        }

        let strategy = self.strategy_manager.get_active_strategy();
        let mut report = strategy.check(&request, &all_rules)?;
        report.gate_id = gate.id;
        report.gate_name = gate.name.clone();

        Self::evaluate_gate(&mut report, &gate.thresholds);

        Ok(report)
    }

    pub fn check_with_strategy(&self, request: CheckRequest, rule_manager: &RuleManager, strategy_id: uuid::Uuid) -> Result<QualityReport> {
        let gate = rule_manager
            .get_gate(request.gate_id)?
            .ok_or_else(|| anyhow::anyhow!("gate not found: {}", request.gate_id))?;

        let mut all_rules: Vec<RuleDefinition> = Vec::new();
        for rule_id in &gate.rules {
            if let Some(rule) = rule_manager.get_rule(*rule_id)? {
                all_rules.push(rule);
            }
        }

        let strategy = self.strategy_manager.get_strategy(&strategy_id)
            .ok_or_else(|| anyhow::anyhow!("strategy not found: {}", strategy_id))?;
        let mut report = strategy.check(&request, &all_rules)?;
        report.gate_id = gate.id;
        report.gate_name = gate.name.clone();

        Self::evaluate_gate(&mut report, &gate.thresholds);

        Ok(report)
    }

    pub fn check_with_type(&self, request: CheckRequest, rule_manager: &RuleManager, strategy_type: StrategyType) -> Result<QualityReport> {
        let strategies = self.strategy_manager.list_strategies();
        let strategy_id = strategies.iter()
            .find(|(_, _, t)| *t == strategy_type)
            .map(|(id, _, _)| *id)
            .ok_or_else(|| anyhow::anyhow!("strategy type not found: {:?}", strategy_type))?;
        self.check_with_strategy(request, rule_manager, strategy_id)
    }

    pub fn switch_strategy(&self, strategy_id: uuid::Uuid) -> Result<()> {
        self.strategy_manager.set_active_strategy(strategy_id)
    }

    pub fn register_custom_strategy(&self, id: uuid::Uuid, strategy: Arc<dyn QualityCheckStrategy + Send + Sync>) {
        self.strategy_manager.register_strategy(id, strategy);
    }

    pub fn available_strategies(&self) -> Vec<(uuid::Uuid, String, StrategyType)> {
        self.strategy_manager.list_strategies()
    }

    fn run_rules_on_file(rules: &[RuleDefinition], file: &SourceFile) -> Vec<Violation> {
        let mut violations = Vec::new();
        for rule in rules {
            if let Ok(re) = Regex::new(&rule.pattern) {
                for (line_num, line) in file.content.lines().enumerate() {
                    if re.is_match(line) {
                        violations.push(Violation {
                            rule_id: rule.id,
                            file_path: file.path.clone(),
                            line_number: (line_num + 1) as u32,
                            message: format!("rule '{}': {}", rule.name, rule.description),
                            severity: rule.severity.clone(),
                        });
                    }
                }
            }
        }
        violations
    }

    fn evaluate_gate(report: &mut QualityReport, thresholds: &GateThresholds) {
        report.passed = report.critical_count <= thresholds.max_critical as usize
            && report.high_count <= thresholds.max_high as usize
            && report.medium_count <= thresholds.max_medium as usize;
    }
}

impl Default for QualityChecker {
    fn default() -> Self {
        Self::with_default_strategy()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_checker_strategy_switch() {
        let checker = QualityChecker::with_default_strategy();
        let strategies = checker.available_strategies();
        assert!(strategies.len() >= 3);

        let lenient_id = strategies.iter()
            .find(|(_, _, t)| *t == StrategyType::LenientMode)
            .map(|(id, _, _)| *id)
            .unwrap();

        checker.switch_strategy(lenient_id).unwrap();
        let active = checker.strategy_manager.get_active_strategy();
        assert_eq!(active.strategy_type(), StrategyType::LenientMode);
    }
}
