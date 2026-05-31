use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogicalPlan {
    pub plan_id: String,
    pub root: LogicalNode,
    pub estimated_cost: f64,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum LogicalNode {
    Scan(ScanNode),
    Filter(FilterNode),
    Project(ProjectNode),
    Join(JoinNode),
    Aggregate(AggregateNode),
    Window(WindowNode),
    Watermark(WatermarkNode),
    Union(UnionNode),
    Sort(SortNode),
    Limit(LimitNode),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanNode {
    pub table_name: String,
    pub columns: Vec<String>,
    pub is_streaming: bool,
    pub predicate: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterNode {
    pub condition: String,
    pub child: Box<LogicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectNode {
    pub columns: Vec<ProjectExpression>,
    pub child: Box<LogicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectExpression {
    pub name: String,
    pub expression: String,
    pub alias: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JoinNode {
    pub join_type: JoinType,
    pub left: Box<LogicalNode>,
    pub right: Box<LogicalNode>,
    pub condition: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum JoinType {
    Inner,
    Left,
    Right,
    Full,
    Cross,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregateNode {
    pub group_by: Vec<String>,
    pub aggregations: Vec<AggregationExpression>,
    pub child: Box<LogicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationExpression {
    pub function: AggregationFunction,
    pub column: Option<String>,
    pub alias: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AggregationFunction {
    Count,
    Sum,
    Avg,
    Min,
    Max,
    First,
    Last,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowNode {
    pub window_type: super::WindowType,
    pub duration_ms: u64,
    pub slide_duration_ms: Option<u64>,
    pub time_column: String,
    pub child: Box<LogicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WatermarkNode {
    pub column: String,
    pub delay_ms: u64,
    pub child: Box<LogicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnionNode {
    pub left: Box<LogicalNode>,
    pub right: Box<LogicalNode>,
    pub all: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SortNode {
    pub columns: Vec<SortColumn>,
    pub child: Box<LogicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SortColumn {
    pub name: String,
    pub ascending: bool,
    pub nulls_first: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LimitNode {
    pub limit: u64,
    pub offset: u64,
    pub child: Box<LogicalNode>,
}

pub struct LogicalPlanBuilder {
    plan_id: String,
}

impl LogicalPlanBuilder {
    pub fn new() -> Self {
        Self {
            plan_id: format!("lp_{}", uuid::Uuid::new_v4()),
        }
    }

    pub fn from_parsed_query(&self, parsed: &super::ParsedQuery) -> LogicalPlan {
        let mut root = self.build_scan_nodes(parsed);

        if parsed.is_streaming {
            if let Some(watermark) = &parsed.watermark {
                root = LogicalNode::Watermark(WatermarkNode {
                    column: watermark.column.clone(),
                    delay_ms: watermark.delay_ms,
                    child: Box::new(root),
                });
            }

            if let Some(window) = &parsed.window_spec {
                root = LogicalNode::Window(WindowNode {
                    window_type: window.window_type,
                    duration_ms: window.duration_ms,
                    slide_duration_ms: window.slide_duration_ms,
                    time_column: window.time_column.clone(),
                    child: Box::new(root),
                });
            }
        }

        LogicalPlan {
            plan_id: self.plan_id.clone(),
            root,
            estimated_cost: self.estimate_cost(&root),
            created_at: chrono::Utc::now(),
        }
    }

    fn build_scan_nodes(&self, parsed: &super::ParsedQuery) -> LogicalNode {
        if parsed.source_tables.is_empty() {
            LogicalNode::Scan(ScanNode {
                table_name: "dual".to_string(),
                columns: vec!["1".to_string()],
                is_streaming: parsed.is_streaming,
                predicate: None,
            })
        } else if parsed.source_tables.len() == 1 {
            LogicalNode::Scan(ScanNode {
                table_name: parsed.source_tables[0].clone(),
                columns: vec!["*".to_string()],
                is_streaming: parsed.is_streaming,
                predicate: None,
            })
        } else {
            let first = LogicalNode::Scan(ScanNode {
                table_name: parsed.source_tables[0].clone(),
                columns: vec!["*".to_string()],
                is_streaming: parsed.is_streaming,
                predicate: None,
            });

            parsed.source_tables[1..].iter().fold(first, |acc, table| {
                LogicalNode::Join(JoinNode {
                    join_type: JoinType::Inner,
                    left: Box::new(acc),
                    right: Box::new(LogicalNode::Scan(ScanNode {
                        table_name: table.clone(),
                        columns: vec!["*".to_string()],
                        is_streaming: parsed.is_streaming,
                        predicate: None,
                    })),
                    condition: None,
                })
            })
        }
    }

    fn estimate_cost(&self, node: &LogicalNode) -> f64 {
        match node {
            LogicalNode::Scan(scan) => {
                let base_cost = if scan.is_streaming { 100.0 } else { 10.0 };
                base_cost * (scan.columns.len() as f64)
            }
            LogicalNode::Filter(f) => 1.2 * self.estimate_cost(&f.child),
            LogicalNode::Project(p) => 1.1 * self.estimate_cost(&p.child),
            LogicalNode::Join(j) => {
                10.0 * (self.estimate_cost(&j.left) + self.estimate_cost(&j.right))
            }
            LogicalNode::Aggregate(a) => 5.0 * self.estimate_cost(&a.child),
            LogicalNode::Window(w) => 8.0 * self.estimate_cost(&w.child),
            LogicalNode::Watermark(w) => 1.05 * self.estimate_cost(&w.child),
            LogicalNode::Union(u) => self.estimate_cost(&u.left) + self.estimate_cost(&u.right),
            LogicalNode::Sort(s) => 2.0 * self.estimate_cost(&s.child),
            LogicalNode::Limit(l) => 0.5 * self.estimate_cost(&l.child),
        }
    }

    pub fn build(&self, root: LogicalNode) -> LogicalPlan {
        LogicalPlan {
            plan_id: self.plan_id.clone(),
            root,
            estimated_cost: self.estimate_cost(&root),
            created_at: chrono::Utc::now(),
        }
    }
}

impl Default for LogicalPlanBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl LogicalPlan {
    pub fn tables(&self) -> HashSet<String> {
        let mut tables = HashSet::new();
        self.collect_tables(&self.root, &mut tables);
        tables
    }

    fn collect_tables(&self, node: &LogicalNode, tables: &mut HashSet<String>) {
        match node {
            LogicalNode::Scan(scan) => {
                tables.insert(scan.table_name.clone());
            }
            LogicalNode::Filter(f) => self.collect_tables(&f.child, tables),
            LogicalNode::Project(p) => self.collect_tables(&p.child, tables),
            LogicalNode::Join(j) => {
                self.collect_tables(&j.left, tables);
                self.collect_tables(&j.right, tables);
            }
            LogicalNode::Aggregate(a) => self.collect_tables(&a.child, tables),
            LogicalNode::Window(w) => self.collect_tables(&w.child, tables),
            LogicalNode::Watermark(w) => self.collect_tables(&w.child, tables),
            LogicalNode::Union(u) => {
                self.collect_tables(&u.left, tables);
                self.collect_tables(&u.right, tables);
            }
            LogicalNode::Sort(s) => self.collect_tables(&s.child, tables),
            LogicalNode::Limit(l) => self.collect_tables(&l.child, tables),
        }
    }

    pub fn is_streaming(&self) -> bool {
        self.check_streaming(&self.root)
    }

    fn check_streaming(&self, node: &LogicalNode) -> bool {
        match node {
            LogicalNode::Scan(scan) => scan.is_streaming,
            LogicalNode::Filter(f) => self.check_streaming(&f.child),
            LogicalNode::Project(p) => self.check_streaming(&p.child),
            LogicalNode::Join(j) => self.check_streaming(&j.left) || self.check_streaming(&j.right),
            LogicalNode::Aggregate(a) => self.check_streaming(&a.child),
            LogicalNode::Window(_) => true,
            LogicalNode::Watermark(_) => true,
            LogicalNode::Union(u) => self.check_streaming(&u.left) || self.check_streaming(&u.right),
            LogicalNode::Sort(s) => self.check_streaming(&s.child),
            LogicalNode::Limit(l) => self.check_streaming(&l.child),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_logical_plan_builder() {
        let builder = LogicalPlanBuilder::new();
        let scan = LogicalNode::Scan(ScanNode {
            table_name: "orders".to_string(),
            columns: vec!["*".to_string()],
            is_streaming: true,
            predicate: None,
        });

        let plan = builder.build(scan);
        assert!(plan.is_streaming());
        assert!(plan.tables().contains("orders"));
    }

    #[test]
    fn test_join_plan() {
        let builder = LogicalPlanBuilder::new();
        let left = LogicalNode::Scan(ScanNode {
            table_name: "orders".to_string(),
            columns: vec!["*".to_string()],
            is_streaming: true,
            predicate: None,
        });
        let right = LogicalNode::Scan(ScanNode {
            table_name: "users".to_string(),
            columns: vec!["*".to_string()],
            is_streaming: false,
            predicate: None,
        });

        let join = LogicalNode::Join(JoinNode {
            join_type: JoinType::Inner,
            left: Box::new(left),
            right: Box::new(right),
            condition: Some("orders.user_id = users.id".to_string()),
        });

        let plan = builder.build(join);
        assert!(plan.is_streaming());
        assert!(plan.tables().contains("orders"));
        assert!(plan.tables().contains("users"));
    }
}
