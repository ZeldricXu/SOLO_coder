use crate::models::StreamSQLError;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OptimizedPlan {
    pub plan_id: String,
    pub original_cost: f64,
    pub optimized_cost: f64,
    pub cost_reduction: f64,
    pub applied_rules: Vec<String>,
    pub plan: super::LogicalPlan,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

pub struct LogicalOptimizer {
    rules: Vec<Box<dyn OptimizationRule>>,
}

#[async_trait::async_trait]
pub trait OptimizationRule: Send + Sync {
    fn name(&self) -> &str;
    fn apply(&self, plan: &super::LogicalPlan) -> Option<super::LogicalPlan>;
    fn is_applicable(&self, plan: &super::LogicalPlan) -> bool {
        true
    }
}

pub struct PredicatePushDownRule;

impl OptimizationRule for PredicatePushDownRule {
    fn name(&self) -> &str {
        "predicate_push_down"
    }

    fn apply(&self, plan: &super::LogicalPlan) -> Option<super::LogicalPlan> {
        let new_root = self.push_down_predicates(&plan.root);
        if new_root != plan.root {
            let builder = super::LogicalPlanBuilder::new();
            Some(builder.build(new_root))
        } else {
            None
        }
    }
}

impl PredicatePushDownRule {
    fn push_down_predicates(&self, node: &super::LogicalNode) -> super::LogicalNode {
        node.clone()
    }
}

pub struct ProjectionPruningRule;

impl OptimizationRule for ProjectionPruningRule {
    fn name(&self) -> &str {
        "projection_pruning"
    }

    fn apply(&self, plan: &super::LogicalPlan) -> Option<super::LogicalPlan> {
        let new_root = self.prune_projections(&plan.root);
        if new_root != plan.root {
            let builder = super::LogicalPlanBuilder::new();
            Some(builder.build(new_root))
        } else {
            None
        }
    }
}

impl ProjectionPruningRule {
    fn prune_projections(&self, node: &super::LogicalNode) -> super::LogicalNode {
        node.clone()
    }
}

pub struct JoinReorderRule;

impl OptimizationRule for JoinReorderRule {
    fn name(&self) -> &str {
        "join_reorder"
    }

    fn is_applicable(&self, plan: &super::LogicalPlan) -> bool {
        self.has_multiple_joins(&plan.root)
    }

    fn apply(&self, plan: &super::LogicalPlan) -> Option<super::LogicalPlan> {
        if !self.is_applicable(plan) {
            return None;
        }

        let new_root = self.reorder_joins(&plan.root);
        if new_root != plan.root {
            let builder = super::LogicalPlanBuilder::new();
            Some(builder.build(new_root))
        } else {
            None
        }
    }
}

impl JoinReorderRule {
    fn has_multiple_joins(&self, node: &super::LogicalNode) -> bool {
        let count = self.count_joins(node);
        count >= 2
    }

    fn count_joins(&self, node: &super::LogicalNode) -> usize {
        match node {
            super::LogicalNode::Join(_) => 1,
            super::LogicalNode::Filter(f) => self.count_joins(&f.child),
            super::LogicalNode::Project(p) => self.count_joins(&p.child),
            super::LogicalNode::Aggregate(a) => self.count_joins(&a.child),
            super::LogicalNode::Window(w) => self.count_joins(&w.child),
            super::LogicalNode::Watermark(w) => self.count_joins(&w.child),
            super::LogicalNode::Union(u) => self.count_joins(&u.left) + self.count_joins(&u.right),
            super::LogicalNode::Sort(s) => self.count_joins(&s.child),
            super::LogicalNode::Limit(l) => self.count_joins(&l.child),
            _ => 0,
        }
    }

    fn reorder_joins(&self, node: &super::LogicalNode) -> super::LogicalNode {
        node.clone()
    }
}

pub struct CommonSubexpressionEliminationRule;

impl OptimizationRule for CommonSubexpressionEliminationRule {
    fn name(&self) -> &str {
        "common_subexpression_elimination"
    }

    fn apply(&self, plan: &super::LogicalPlan) -> Option<super::LogicalPlan> {
        None
    }
}

pub struct StreamingOptimizationRule;

impl OptimizationRule for StreamingOptimizationRule {
    fn name(&self) -> &str {
        "streaming_optimization"
    }

    fn is_applicable(&self, plan: &super::LogicalPlan) -> bool {
        plan.is_streaming()
    }

    fn apply(&self, plan: &super::LogicalPlan) -> Option<super::LogicalPlan> {
        let has_watermark = self.has_watermark(&plan.root);
        let has_window = self.has_window(&plan.root);

        if !has_watermark || !has_window {
            return None;
        }

        let new_root = self.optimize_streaming(&plan.root);
        if new_root != plan.root {
            let builder = super::LogicalPlanBuilder::new();
            Some(builder.build(new_root))
        } else {
            None
        }
    }
}

impl StreamingOptimizationRule {
    fn has_watermark(&self, node: &super::LogicalNode) -> bool {
        match node {
            super::LogicalNode::Watermark(_) => true,
            super::LogicalNode::Filter(f) => self.has_watermark(&f.child),
            super::LogicalNode::Project(p) => self.has_watermark(&p.child),
            super::LogicalNode::Join(j) => self.has_watermark(&j.left) || self.has_watermark(&j.right),
            super::LogicalNode::Aggregate(a) => self.has_watermark(&a.child),
            super::LogicalNode::Window(w) => self.has_watermark(&w.child),
            super::LogicalNode::Union(u) => self.has_watermark(&u.left) || self.has_watermark(&u.right),
            super::LogicalNode::Sort(s) => self.has_watermark(&s.child),
            super::LogicalNode::Limit(l) => self.has_watermark(&l.child),
            _ => false,
        }
    }

    fn has_window(&self, node: &super::LogicalNode) -> bool {
        match node {
            super::LogicalNode::Window(_) => true,
            super::LogicalNode::Filter(f) => self.has_window(&f.child),
            super::LogicalNode::Project(p) => self.has_window(&p.child),
            super::LogicalNode::Join(j) => self.has_window(&j.left) || self.has_window(&j.right),
            super::LogicalNode::Aggregate(a) => self.has_window(&a.child),
            super::LogicalNode::Watermark(w) => self.has_window(&w.child),
            super::LogicalNode::Union(u) => self.has_window(&u.left) || self.has_window(&u.right),
            super::LogicalNode::Sort(s) => self.has_window(&s.child),
            super::LogicalNode::Limit(l) => self.has_window(&l.child),
            _ => false,
        }
    }

    fn optimize_streaming(&self, node: &super::LogicalNode) -> super::LogicalNode {
        node.clone()
    }
}

impl Default for LogicalOptimizer {
    fn default() -> Self {
        Self::new()
    }
}

impl LogicalOptimizer {
    pub fn new() -> Self {
        let rules: Vec<Box<dyn OptimizationRule>> = vec![
            Box::new(PredicatePushDownRule),
            Box::new(ProjectionPruningRule),
            Box::new(JoinReorderRule),
            Box::new(CommonSubexpressionEliminationRule),
            Box::new(StreamingOptimizationRule),
        ];

        Self { rules }
    }

    pub fn with_rules(rules: Vec<Box<dyn OptimizationRule>>) -> Self {
        Self { rules }
    }

    pub async fn optimize(&self, plan: super::LogicalPlan) -> Result<OptimizedPlan, StreamSQLError> {
        let original_cost = plan.estimated_cost;
        let mut current_plan = plan;
        let mut applied_rules = Vec::new();

        for rule in &self.rules {
            if rule.is_applicable(&current_plan) {
                if let Some(new_plan) = rule.apply(&current_plan) {
                    applied_rules.push(rule.name().to_string());
                    current_plan = new_plan;
                }
            }
        }

        let optimized_cost = current_plan.estimated_cost;
        let cost_reduction = if original_cost > 0.0 {
            (original_cost - optimized_cost) / original_cost
        } else {
            0.0
        };

        Ok(OptimizedPlan {
            plan_id: format!("opt_{}", uuid::Uuid::new_v4()),
            original_cost,
            optimized_cost,
            cost_reduction,
            applied_rules,
            plan: current_plan,
            created_at: chrono::Utc::now(),
        })
    }

    pub fn optimize_all(&self, plans: Vec<super::LogicalPlan>) -> Vec<OptimizedPlan> {
        let mut results = Vec::with_capacity(plans.len());
        for plan in plans {
            if let Ok(optimized) = tokio::runtime::Runtime::new()
                .unwrap()
                .block_on(self.optimize(plan))
            {
                results.push(optimized);
            }
        }
        results
    }

    pub fn cost_estimate(&self, plan: &super::LogicalPlan) -> f64 {
        self.estimate_node_cost(&plan.root)
    }

    fn estimate_node_cost(&self, node: &super::LogicalNode) -> f64 {
        match node {
            super::LogicalNode::Scan(scan) => {
                let base_cost = if scan.is_streaming { 100.0 } else { 10.0 };
                base_cost * (scan.columns.len() as f64)
            }
            super::LogicalNode::Filter(f) => 1.2 * self.estimate_node_cost(&f.child),
            super::LogicalNode::Project(p) => 1.1 * self.estimate_node_cost(&p.child),
            super::LogicalNode::Join(j) => {
                10.0 * (self.estimate_node_cost(&j.left) + self.estimate_node_cost(&j.right))
            }
            super::LogicalNode::Aggregate(a) => 5.0 * self.estimate_node_cost(&a.child),
            super::LogicalNode::Window(w) => 8.0 * self.estimate_node_cost(&w.child),
            super::LogicalNode::Watermark(w) => 1.05 * self.estimate_node_cost(&w.child),
            super::LogicalNode::Union(u) => {
                self.estimate_node_cost(&u.left) + self.estimate_node_cost(&u.right)
            }
            super::LogicalNode::Sort(s) => 2.0 * self.estimate_node_cost(&s.child),
            super::LogicalNode::Limit(l) => 0.5 * self.estimate_node_cost(&l.child),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OptimizationReport {
    pub original_plans: usize,
    pub optimized_plans: usize,
    pub total_original_cost: f64,
    pub total_optimized_cost: f64,
    pub average_cost_reduction: f64,
    pub rules_applied: HashMap<String, usize>,
    pub generated_at: chrono::DateTime<chrono::Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_optimizer_creation() {
        let optimizer = LogicalOptimizer::new();
        assert!(optimizer.rules.len() > 0);
    }

    #[test]
    fn test_cost_estimation() {
        let optimizer = LogicalOptimizer::new();
        let builder = super::super::LogicalPlanBuilder::new();
        let scan = super::super::LogicalNode::Scan(super::super::ScanNode {
            table_name: "orders".to_string(),
            columns: vec!["*".to_string()],
            is_streaming: true,
            predicate: None,
        });
        let plan = builder.build(scan);

        let cost = optimizer.cost_estimate(&plan);
        assert!(cost > 0.0);
    }
}
