use anyhow::Result;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use uuid::Uuid;

use crate::checker::QualityChecker;
use crate::models::{
    CheckRequest, GateThresholds, Language, QualityGate, QualityReport, RuleDefinition, Severity,
};
use crate::report::ReportGenerator;
use crate::rules::RuleManager;
use crate::strategy::{QualityCheckStrategy, StrategyManager, StrategyType, StrategySwitchEvent};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AddRuleParams {
    pub name: String,
    pub language: Language,
    pub severity: Severity,
    pub pattern: String,
    pub description: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateRuleParams {
    pub name: String,
    pub language: Language,
    pub severity: Severity,
    pub pattern: String,
    pub description: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateGateParams {
    pub name: String,
    pub description: String,
    pub rules: Vec<Uuid>,
    pub thresholds: GateThresholds,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateGateParams {
    pub name: String,
    pub description: String,
    pub rules: Vec<Uuid>,
    pub thresholds: GateThresholds,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StrategyInfo {
    pub id: Uuid,
    pub name: String,
    pub strategy_type: StrategyType,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SwitchStrategyRequest {
    pub strategy_id: Uuid,
    pub triggered_by: String,
}

pub fn add_rule(manager: &RuleManager, params: AddRuleParams) -> Result<RuleDefinition> {
    let rule = RuleDefinition {
        id: Uuid::new_v4(),
        name: params.name,
        language: params.language,
        severity: params.severity,
        pattern: params.pattern,
        description: params.description,
        enabled: params.enabled,
        created_at: Utc::now(),
    };
    manager.add_rule(rule.clone())?;
    Ok(rule)
}

pub fn update_rule(
    manager: &RuleManager,
    id: Uuid,
    params: UpdateRuleParams,
) -> Result<RuleDefinition> {
    let rule = RuleDefinition {
        id,
        name: params.name,
        language: params.language,
        severity: params.severity,
        pattern: params.pattern,
        description: params.description,
        enabled: params.enabled,
        created_at: Utc::now(),
    };
    manager.update_rule(id, rule.clone())?;
    Ok(rule)
}

pub fn delete_rule(manager: &RuleManager, id: Uuid) -> Result<()> {
    manager.delete_rule(id)
}

pub fn list_rules(manager: &RuleManager, language: Option<Language>) -> Result<Vec<RuleDefinition>> {
    manager.list_rules(language)
}

pub fn create_gate(manager: &RuleManager, params: CreateGateParams) -> Result<QualityGate> {
    let gate = QualityGate {
        id: Uuid::new_v4(),
        name: params.name,
        description: params.description,
        rules: params.rules,
        thresholds: params.thresholds,
        enabled: params.enabled,
    };
    manager.create_gate(gate.clone())?;
    Ok(gate)
}

pub fn update_gate(
    manager: &RuleManager,
    id: Uuid,
    params: UpdateGateParams,
) -> Result<QualityGate> {
    let gate = QualityGate {
        id,
        name: params.name,
        description: params.description,
        rules: params.rules,
        thresholds: params.thresholds,
        enabled: params.enabled,
    };
    manager.update_gate(id, gate.clone())?;
    Ok(gate)
}

pub fn list_gates(manager: &RuleManager) -> Result<Vec<QualityGate>> {
    manager.list_gates()
}

pub fn run_check(
    checker: &QualityChecker,
    manager: &RuleManager,
    request: CheckRequest,
) -> Result<QualityReport> {
    checker.check(request, manager)
}

pub fn run_check_with_strategy(
    checker: &QualityChecker,
    manager: &RuleManager,
    request: CheckRequest,
    strategy_id: Uuid,
) -> Result<QualityReport> {
    checker.check_with_strategy(request, manager, strategy_id)
}

pub fn get_report(report: &QualityReport) -> Result<String> {
    ReportGenerator::generate_json(report)
}

pub fn list_available_strategies(checker: &QualityChecker) -> Vec<StrategyInfo> {
    checker.available_strategies().into_iter()
        .map(|(id, name, strategy_type)| StrategyInfo { id, name, strategy_type })
        .collect()
}

pub fn switch_active_strategy(
    checker: &QualityChecker,
    request: SwitchStrategyRequest,
) -> Result<StrategySwitchEvent> {
    let old_strategy_id = Uuid::new_v4();
    checker.switch_strategy(request.strategy_id)?;
    Ok(StrategySwitchEvent {
        old_strategy_id,
        new_strategy_id: request.strategy_id,
        triggered_by: request.triggered_by,
        timestamp: Utc::now(),
    })
}

pub fn register_custom_strategy(
    checker: &QualityChecker,
    id: Uuid,
    strategy: Arc<dyn QualityCheckStrategy + Send + Sync>,
) {
    checker.register_custom_strategy(id, strategy);
}

pub fn unregister_strategy(
    strategy_manager: &StrategyManager,
    id: &Uuid,
) -> Option<Arc<dyn QualityCheckStrategy + Send + Sync>> {
    strategy_manager.unregister_strategy(id)
}

