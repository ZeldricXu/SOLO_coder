use serde::{Deserialize, Serialize};
use std::sync::Arc;
use dashmap::DashMap;
use anyhow::Result;
use uuid::Uuid;

use crate::models::{CheckRequest, QualityReport, RuleDefinition, Severity};

pub type StrategyFn = Arc<dyn QualityCheckStrategy + Send + Sync>;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum StrategyType {
    RegexOnly,
    SemanticAnalysis,
    StrictMode,
    LenientMode,
    Custom,
}

pub trait QualityCheckStrategy {
    fn name(&self) -> &str;
    fn strategy_type(&self) -> StrategyType;
    fn check(&self, request: &CheckRequest, rules: &[RuleDefinition]) -> Result<QualityReport>;
    fn supports_language(&self, language: &crate::models::Language) -> bool;
}

pub struct RegexOnlyStrategy;

impl QualityCheckStrategy for RegexOnlyStrategy {
    fn name(&self) -> &str {
        "Regex Only Strategy"
    }

    fn strategy_type(&self) -> StrategyType {
        StrategyType::RegexOnly
    }

    fn check(&self, request: &CheckRequest, rules: &[RuleDefinition]) -> Result<QualityReport> {
        let mut violations = Vec::new();
        let start = std::time::Instant::now();

        for file in &request.source_files {
            for rule in rules {
                if rule.language != file.language || !rule.enabled {
                    continue;
                }
                let re = regex::Regex::new(&rule.pattern)?;
                for (idx, line) in file.content.lines().enumerate() {
                    if re.is_match(line) {
                        violations.push(crate::models::Violation {
                            rule_id: rule.id,
                            file_path: file.path.clone(),
                            line_number: (idx + 1) as u32,
                            message: rule.description.clone(),
                            severity: rule.severity.clone(),
                        });
                    }
                }
            }
        }

        let _duration_ms = start.elapsed().as_millis() as u64;
        let (critical_count, high_count, medium_count) = count_violations(&violations);

        Ok(QualityReport {
            gate_id: request.gate_id,
            gate_name: "regex_gate".to_string(),
            passed: critical_count == 0,
            violations: violations.clone(),
            total_violations: violations.len(),
            critical_count,
            high_count,
            medium_count,
            checked_at: chrono::Utc::now(),
            file_count: request.source_files.len(),
        })
    }

    fn supports_language(&self, _language: &crate::models::Language) -> bool {
        true
    }
}

pub struct StrictModeStrategy;

impl QualityCheckStrategy for StrictModeStrategy {
    fn name(&self) -> &str {
        "Strict Mode Strategy"
    }

    fn strategy_type(&self) -> StrategyType {
        StrategyType::StrictMode
    }

    fn check(&self, request: &CheckRequest, rules: &[RuleDefinition]) -> Result<QualityReport> {
        let mut violations = Vec::new();
        let start = std::time::Instant::now();

        for file in &request.source_files {
            for rule in rules {
                if rule.language != file.language {
                    continue;
                }
                let re = regex::Regex::new(&rule.pattern)?;
                for (idx, line) in file.content.lines().enumerate() {
                    if re.is_match(line) {
                        violations.push(crate::models::Violation {
                            rule_id: rule.id,
                            file_path: file.path.clone(),
                            line_number: (idx + 1) as u32,
                            message: rule.description.clone(),
                            severity: rule.severity.clone(),
                        });
                    }
                }
            }
        }

        let _duration_ms = start.elapsed().as_millis() as u64;
        let (critical_count, high_count, medium_count) = count_violations(&violations);

        Ok(QualityReport {
            gate_id: request.gate_id,
            gate_name: "strict_gate".to_string(),
            passed: critical_count == 0 && high_count == 0,
            violations: violations.clone(),
            total_violations: violations.len(),
            critical_count,
            high_count,
            medium_count,
            checked_at: chrono::Utc::now(),
            file_count: request.source_files.len(),
        })
    }

    fn supports_language(&self, _language: &crate::models::Language) -> bool {
        true
    }
}

pub struct LenientModeStrategy;

impl QualityCheckStrategy for LenientModeStrategy {
    fn name(&self) -> &str {
        "Lenient Mode Strategy"
    }

    fn strategy_type(&self) -> StrategyType {
        StrategyType::LenientMode
    }

    fn check(&self, request: &CheckRequest, rules: &[RuleDefinition]) -> Result<QualityReport> {
        let mut violations = Vec::new();
        let start = std::time::Instant::now();

        for file in &request.source_files {
            for rule in rules {
                if rule.language != file.language || rule.severity == Severity::Low || rule.severity == Severity::Info {
                    continue;
                }
                if !rule.enabled {
                    continue;
                }
                let re = regex::Regex::new(&rule.pattern)?;
                for (idx, line) in file.content.lines().enumerate() {
                    if re.is_match(line) {
                        violations.push(crate::models::Violation {
                            rule_id: rule.id,
                            file_path: file.path.clone(),
                            line_number: (idx + 1) as u32,
                            message: rule.description.clone(),
                            severity: rule.severity.clone(),
                        });
                    }
                }
            }
        }

        let _duration_ms = start.elapsed().as_millis() as u64;
        let (critical_count, high_count, medium_count) = count_violations(&violations);

        Ok(QualityReport {
            gate_id: request.gate_id,
            gate_name: "lenient_gate".to_string(),
            passed: true,
            violations: violations.clone(),
            total_violations: violations.len(),
            critical_count,
            high_count,
            medium_count,
            checked_at: chrono::Utc::now(),
            file_count: request.source_files.len(),
        })
    }

    fn supports_language(&self, _language: &crate::models::Language) -> bool {
        true
    }
}

fn count_violations(violations: &[crate::models::Violation]) -> (usize, usize, usize) {
    let mut critical = 0;
    let mut high = 0;
    let mut medium = 0;
    for v in violations {
        match v.severity {
            Severity::Critical => critical += 1,
            Severity::High => high += 1,
            Severity::Medium => medium += 1,
            _ => {}
        }
    }
    (critical, high, medium)
}

pub struct StrategyManager {
    strategies: DashMap<Uuid, StrategyFn>,
    active_strategy: std::sync::atomic::AtomicPtr<Uuid>,
    default_strategy_id: Uuid,
}

impl StrategyManager {
    pub fn new() -> Self {
        let strategies = DashMap::new();
        let default_id = Uuid::new_v4();
        strategies.insert(default_id, Arc::new(RegexOnlyStrategy) as StrategyFn);

        let lenient_id = Uuid::new_v4();
        strategies.insert(lenient_id, Arc::new(LenientModeStrategy) as StrategyFn);

        let strict_id = Uuid::new_v4();
        strategies.insert(strict_id, Arc::new(StrictModeStrategy) as StrategyFn);

        Self {
            strategies,
            active_strategy: std::sync::atomic::AtomicPtr::new(Box::into_raw(Box::new(default_id))),
            default_strategy_id: default_id,
        }
    }

    pub fn register_strategy(&self, id: Uuid, strategy: StrategyFn) {
        self.strategies.insert(id, strategy);
    }

    pub fn unregister_strategy(&self, id: &Uuid) -> Option<StrategyFn> {
        if *id == self.default_strategy_id {
            return None;
        }
        self.strategies.remove(id).map(|(_, v)| v)
    }

    pub fn get_strategy(&self, id: &Uuid) -> Option<StrategyFn> {
        self.strategies.get(id).map(|v| v.value().clone())
    }

    pub fn get_active_strategy(&self) -> StrategyFn {
        let ptr = self.active_strategy.load(std::sync::atomic::Ordering::SeqCst);
        let id = unsafe { &*ptr };
        self.get_strategy(id).unwrap_or_else(|| {
            self.get_strategy(&self.default_strategy_id).unwrap()
        })
    }

    pub fn set_active_strategy(&self, id: Uuid) -> Result<()> {
        if !self.strategies.contains_key(&id) {
            anyhow::bail!("Strategy not found: {}", id);
        }
        let old_ptr = self.active_strategy.swap(
            Box::into_raw(Box::new(id)),
            std::sync::atomic::Ordering::SeqCst,
        );
        unsafe { drop(Box::from_raw(old_ptr)) };
        Ok(())
    }

    pub fn list_strategies(&self) -> Vec<(Uuid, String, StrategyType)> {
        self.strategies
            .iter()
            .map(|item| (*item.key(), item.value().name().to_string(), item.value().strategy_type()))
            .collect()
    }

    pub fn get_default_strategy_id(&self) -> Uuid {
        self.default_strategy_id
    }
}

impl Default for StrategyManager {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StrategySwitchEvent {
    pub old_strategy_id: Uuid,
    pub new_strategy_id: Uuid,
    pub triggered_by: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_strategy_switch() {
        let manager = StrategyManager::new();
        let strategies = manager.list_strategies();
        assert!(strategies.len() >= 3);

        let regex_id = strategies[0].0;
        let lenient_id = strategies[1].0;

        manager.set_active_strategy(lenient_id).unwrap();
        let active = manager.get_active_strategy();
        assert_eq!(active.strategy_type(), StrategyType::LenientMode);

        manager.set_active_strategy(regex_id).unwrap();
        let active = manager.get_active_strategy();
        assert_eq!(active.strategy_type(), StrategyType::RegexOnly);
    }

    #[test]
    fn test_custom_strategy_registration() {
        struct CustomStrategy;
        impl QualityCheckStrategy for CustomStrategy {
            fn name(&self) -> &str { "Custom" }
            fn strategy_type(&self) -> StrategyType { StrategyType::Custom }
            fn check(&self, _req: &CheckRequest, _rules: &[RuleDefinition]) -> Result<QualityReport> {
                Ok(QualityReport {
                    gate_id: Uuid::new_v4(),
                    gate_name: "custom".to_string(),
                    passed: true,
                    violations: vec![],
                    total_violations: 0,
                    critical_count: 0,
                    high_count: 0,
                    medium_count: 0,
                    checked_at: chrono::Utc::now(),
                    file_count: 0,
                })
            }
            fn supports_language(&self, _lang: &crate::models::Language) -> bool { true }
        }

        let manager = StrategyManager::new();
        let custom_id = Uuid::new_v4();
        manager.register_strategy(custom_id, Arc::new(CustomStrategy));
        
        assert!(manager.get_strategy(&custom_id).is_some());
        manager.set_active_strategy(custom_id).unwrap();
        assert_eq!(manager.get_active_strategy().strategy_type(), StrategyType::Custom);
    }
}
