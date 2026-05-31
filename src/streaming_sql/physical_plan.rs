use crate::models::StreamSQLError;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhysicalPlan {
    pub plan_id: String,
    pub root: PhysicalNode,
    pub estimated_parallelism: u32,
    pub memory_requirement_bytes: u64,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PhysicalNode {
    TableScan(TableScanExec),
    Filter(FilterExec),
    Project(ProjectExec),
    HashJoin(HashJoinExec),
    SortMergeJoin(SortMergeJoinExec),
    HashAggregate(HashAggregateExec),
    StreamingWindow(StreamingWindowExec),
    Union(UnionExec),
    Sort(SortExec),
    Limit(LimitExec),
    Sink(SinkExec),
    Exchange(ExchangeExec),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableScanExec {
    pub table_name: String,
    pub columns: Vec<String>,
    pub is_streaming: bool,
    pub predicate: Option<String>,
    pub parallelism: u32,
    pub batch_size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilterExec {
    pub condition: String,
    pub child: Box<PhysicalNode>,
    pub selectivity: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectExec {
    pub columns: Vec<String>,
    pub child: Box<PhysicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HashJoinExec {
    pub join_type: JoinType,
    pub left: Box<PhysicalNode>,
    pub right: Box<PhysicalNode>,
    pub left_keys: Vec<String>,
    pub right_keys: Vec<String>,
    pub build_side: BuildSide,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BuildSide {
    Left,
    Right,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SortMergeJoinExec {
    pub join_type: JoinType,
    pub left: Box<PhysicalNode>,
    pub right: Box<PhysicalNode>,
    pub left_keys: Vec<String>,
    pub right_keys: Vec<String>,
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
pub struct HashAggregateExec {
    pub group_by: Vec<String>,
    pub aggregations: Vec<AggregationExpression>,
    pub child: Box<PhysicalNode>,
    pub mode: AggregationMode,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AggregationMode {
    Partial,
    Final,
    Complete,
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
pub struct StreamingWindowExec {
    pub window_type: WindowType,
    pub duration_ms: u64,
    pub slide_duration_ms: Option<u64>,
    pub time_column: String,
    pub watermark_delay_ms: u64,
    pub child: Box<PhysicalNode>,
    pub trigger: TriggerType,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum WindowType {
    Tumbling,
    Sliding,
    Session,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TriggerType {
    ProcessingTime,
    EventTime,
    Continuous,
    Once,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UnionExec {
    pub left: Box<PhysicalNode>,
    pub right: Box<PhysicalNode>,
    pub all: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SortExec {
    pub columns: Vec<SortColumn>,
    pub child: Box<PhysicalNode>,
    pub spill_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SortColumn {
    pub name: String,
    pub ascending: bool,
    pub nulls_first: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LimitExec {
    pub limit: u64,
    pub offset: u64,
    pub child: Box<PhysicalNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SinkExec {
    pub sink_type: SinkType,
    pub destination: String,
    pub child: Box<PhysicalNode>,
    pub write_mode: WriteMode,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SinkType {
    Table,
    File,
    Kafka,
    Redis,
    Custom,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum WriteMode {
    Append,
    Overwrite,
    Upsert,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExchangeExec {
    pub distribution: DistributionType,
    pub child: Box<PhysicalNode>,
    pub partition_keys: Vec<String>,
    pub num_partitions: u32,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DistributionType {
    Hash,
    Range,
    RoundRobin,
    Single,
    Broadcast,
}

pub struct PhysicalPlanTranslator {
    default_parallelism: u32,
    default_batch_size: u64,
}

impl Default for PhysicalPlanTranslator {
    fn default() -> Self {
        Self::new()
    }
}

impl PhysicalPlanTranslator {
    pub fn new() -> Self {
        Self {
            default_parallelism: 4,
            default_batch_size: 1024,
        }
    }

    pub fn with_parallelism(parallelism: u32) -> Self {
        Self {
            default_parallelism: parallelism,
            default_batch_size: 1024,
        }
    }

    pub fn translate(
        &self,
        logical_plan: &super::LogicalPlan,
        target_table: Option<&str>,
    ) -> Result<PhysicalPlan, StreamSQLError> {
        let mut root = self.translate_node(&logical_plan.root);

        if let Some(target) = target_table {
            root = PhysicalNode::Sink(SinkExec {
                sink_type: SinkType::Table,
                destination: target.to_string(),
                child: Box::new(root),
                write_mode: WriteMode::Append,
            });
        }

        Ok(PhysicalPlan {
            plan_id: format!("pp_{}", uuid::Uuid::new_v4()),
            root,
            estimated_parallelism: self.estimate_parallelism(&root),
            memory_requirement_bytes: self.estimate_memory(&root),
            created_at: chrono::Utc::now(),
        })
    }

    fn translate_node(&self, node: &super::LogicalNode) -> PhysicalNode {
        match node {
            super::LogicalNode::Scan(scan) => PhysicalNode::TableScan(TableScanExec {
                table_name: scan.table_name.clone(),
                columns: scan.columns.clone(),
                is_streaming: scan.is_streaming,
                predicate: scan.predicate.clone(),
                parallelism: self.default_parallelism,
                batch_size: self.default_batch_size,
            }),

            super::LogicalNode::Filter(filter) => PhysicalNode::Filter(FilterExec {
                condition: filter.condition.clone(),
                child: Box::new(self.translate_node(&filter.child)),
                selectivity: 0.5,
            }),

            super::LogicalNode::Project(project) => PhysicalNode::Project(ProjectExec {
                columns: project
                    .columns
                    .iter()
                    .map(|c| c.alias.clone().unwrap_or_else(|| c.name.clone()))
                    .collect(),
                child: Box::new(self.translate_node(&project.child)),
            }),

            super::LogicalNode::Join(join) => self.translate_join(join),

            super::LogicalNode::Aggregate(agg) => PhysicalNode::HashAggregate(HashAggregateExec {
                group_by: agg.group_by.clone(),
                aggregations: agg
                    .aggregations
                    .iter()
                    .map(|a| AggregationExpression {
                        function: match a.function {
                            super::AggregationFunction::Count => AggregationFunction::Count,
                            super::AggregationFunction::Sum => AggregationFunction::Sum,
                            super::AggregationFunction::Avg => AggregationFunction::Avg,
                            super::AggregationFunction::Min => AggregationFunction::Min,
                            super::AggregationFunction::Max => AggregationFunction::Max,
                            super::AggregationFunction::First => AggregationFunction::First,
                            super::AggregationFunction::Last => AggregationFunction::Last,
                        },
                        column: a.column.clone(),
                        alias: a.alias.clone(),
                    })
                    .collect(),
                child: Box::new(self.translate_node(&agg.child)),
                mode: AggregationMode::Complete,
            }),

            super::LogicalNode::Window(window) => PhysicalNode::StreamingWindow(StreamingWindowExec {
                window_type: match window.window_type {
                    super::WindowType::Tumbling => WindowType::Tumbling,
                    super::WindowType::Sliding => WindowType::Sliding,
                    super::WindowType::Session => WindowType::Session,
                },
                duration_ms: window.duration_ms,
                slide_duration_ms: window.slide_duration_ms,
                time_column: window.time_column.clone(),
                watermark_delay_ms: 5000,
                child: Box::new(self.translate_node(&window.child)),
                trigger: TriggerType::EventTime,
            }),

            super::LogicalNode::Watermark(watermark) => {
                let child = self.translate_node(&watermark.child);
                self.inject_watermark(&child, watermark.delay_ms)
            }

            super::LogicalNode::Union(union) => PhysicalNode::Union(UnionExec {
                left: Box::new(self.translate_node(&union.left)),
                right: Box::new(self.translate_node(&union.right)),
                all: union.all,
            }),

            super::LogicalNode::Sort(sort) => PhysicalNode::Sort(SortExec {
                columns: sort
                    .columns
                    .iter()
                    .map(|c| SortColumn {
                        name: c.name.clone(),
                        ascending: c.ascending,
                        nulls_first: c.nulls_first,
                    })
                    .collect(),
                child: Box::new(self.translate_node(&sort.child)),
                spill_enabled: true,
            }),

            super::LogicalNode::Limit(limit) => PhysicalNode::Limit(LimitExec {
                limit: limit.limit,
                offset: limit.offset,
                child: Box::new(self.translate_node(&limit.child)),
            }),
        }
    }

    fn translate_join(&self, join: &super::JoinNode) -> PhysicalNode {
        let left = self.translate_node(&join.left);
        let right = self.translate_node(&join.right);

        let join_type = match join.join_type {
            super::JoinType::Inner => JoinType::Inner,
            super::JoinType::Left => JoinType::Left,
            super::JoinType::Right => JoinType::Right,
            super::JoinType::Full => JoinType::Full,
            super::JoinType::Cross => JoinType::Cross,
        };

        let keys = self.extract_join_keys(&join.condition);

        PhysicalNode::HashJoin(HashJoinExec {
            join_type,
            left: Box::new(left),
            right: Box::new(right),
            left_keys: keys.0,
            right_keys: keys.1,
            build_side: BuildSide::Right,
        })
    }

    fn extract_join_keys(&self, condition: &Option<String>) -> (Vec<String>, Vec<String>) {
        match condition {
            Some(_) => (vec!["id".to_string()], vec!["id".to_string()]),
            None => (Vec::new(), Vec::new()),
        }
    }

    fn inject_watermark(&self, node: &PhysicalNode, delay_ms: u64) -> PhysicalNode {
        match node {
            PhysicalNode::StreamingWindow(window) => {
                let mut new_window = window.clone();
                new_window.watermark_delay_ms = delay_ms;
                PhysicalNode::StreamingWindow(new_window)
            }
            other => other.clone(),
        }
    }

    fn estimate_parallelism(&self, node: &PhysicalNode) -> u32 {
        match node {
            PhysicalNode::TableScan(scan) => scan.parallelism,
            PhysicalNode::Filter(f) => self.estimate_parallelism(&f.child),
            PhysicalNode::Project(p) => self.estimate_parallelism(&p.child),
            PhysicalNode::HashJoin(j) => {
                std::cmp::max(
                    self.estimate_parallelism(&j.left),
                    self.estimate_parallelism(&j.right),
                )
            }
            PhysicalNode::SortMergeJoin(j) => {
                std::cmp::max(
                    self.estimate_parallelism(&j.left),
                    self.estimate_parallelism(&j.right),
                )
            }
            PhysicalNode::HashAggregate(a) => self.estimate_parallelism(&a.child),
            PhysicalNode::StreamingWindow(w) => self.estimate_parallelism(&w.child),
            PhysicalNode::Union(u) => {
                std::cmp::max(
                    self.estimate_parallelism(&u.left),
                    self.estimate_parallelism(&u.right),
                )
            }
            PhysicalNode::Sort(s) => self.estimate_parallelism(&s.child),
            PhysicalNode::Limit(l) => self.estimate_parallelism(&l.child),
            PhysicalNode::Sink(s) => self.estimate_parallelism(&s.child),
            PhysicalNode::Exchange(e) => e.num_partitions,
        }
    }

    fn estimate_memory(&self, node: &PhysicalNode) -> u64 {
        match node {
            PhysicalNode::TableScan(_) => self.default_batch_size * 100,
            PhysicalNode::Filter(f) => self.estimate_memory(&f.child),
            PhysicalNode::Project(p) => self.estimate_memory(&p.child),
            PhysicalNode::HashJoin(j) => {
                self.estimate_memory(&j.left) + self.estimate_memory(&j.right) + 1024 * 1024
            }
            PhysicalNode::SortMergeJoin(j) => {
                self.estimate_memory(&j.left) + self.estimate_memory(&j.right)
            }
            PhysicalNode::HashAggregate(a) => self.estimate_memory(&a.child) * 2,
            PhysicalNode::StreamingWindow(w) => self.estimate_memory(&w.child) * 3,
            PhysicalNode::Union(u) => self.estimate_memory(&u.left) + self.estimate_memory(&u.right),
            PhysicalNode::Sort(s) => self.estimate_memory(&s.child) * 4,
            PhysicalNode::Limit(l) => self.estimate_memory(&l.child),
            PhysicalNode::Sink(s) => self.estimate_memory(&s.child),
            PhysicalNode::Exchange(e) => self.estimate_memory(&e.child),
        }
    }
}

pub struct QueryExecutionPlan {
    pub logical_plan: super::LogicalPlan,
    pub optimized_plan: super::OptimizedPlan,
    pub physical_plan: PhysicalPlan,
}

#[async_trait::async_trait]
pub trait ExecutablePhysicalPlan: Send + Sync {
    async fn execute(&self) -> Result<ExecutionResult, StreamSQLError>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionResult {
    pub plan_id: String,
    pub row_count: u64,
    pub execution_time_ms: u64,
    pub status: ExecutionStatus,
    pub error_message: Option<String>,
    pub started_at: chrono::DateTime<chrono::Utc>,
    pub completed_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
}

pub struct PhysicalPlanExecutor {
    max_parallelism: u32,
}

impl Default for PhysicalPlanExecutor {
    fn default() -> Self {
        Self::new()
    }
}

impl PhysicalPlanExecutor {
    pub fn new() -> Self {
        Self { max_parallelism: 16 }
    }

    pub fn with_max_parallelism(max_parallelism: u32) -> Self {
        Self { max_parallelism }
    }

    pub async fn execute(&self, plan: &PhysicalPlan) -> Result<ExecutionResult, StreamSQLError> {
        let started_at = chrono::Utc::now();
        let plan_id = plan.plan_id.clone();

        let row_count = self.simulate_execution(plan).await?;

        Ok(ExecutionResult {
            plan_id,
            row_count,
            execution_time_ms: (chrono::Utc::now() - started_at).num_milliseconds() as u64,
            status: ExecutionStatus::Completed,
            error_message: None,
            started_at,
            completed_at: Some(chrono::Utc::now()),
        })
    }

    async fn simulate_execution(&self, plan: &PhysicalPlan) -> Result<u64, StreamSQLError> {
        Ok(self.count_output_rows(&plan.root))
    }

    fn count_output_rows(&self, node: &PhysicalNode) -> u64 {
        match node {
            PhysicalNode::TableScan(_) => 1000,
            PhysicalNode::Filter(f) => (self.count_output_rows(&f.child) as f64 * f.selectivity) as u64,
            PhysicalNode::Project(p) => self.count_output_rows(&p.child),
            PhysicalNode::HashJoin(j) => {
                std::cmp::min(self.count_output_rows(&j.left), self.count_output_rows(&j.right))
            }
            PhysicalNode::SortMergeJoin(j) => {
                std::cmp::min(self.count_output_rows(&j.left), self.count_output_rows(&j.right))
            }
            PhysicalNode::HashAggregate(a) => self.count_output_rows(&a.child) / 10,
            PhysicalNode::StreamingWindow(w) => self.count_output_rows(&w.child) / 5,
            PhysicalNode::Union(u) => {
                if u.all {
                    self.count_output_rows(&u.left) + self.count_output_rows(&u.right)
                } else {
                    (self.count_output_rows(&u.left) + self.count_output_rows(&u.right)) / 2
                }
            }
            PhysicalNode::Sort(s) => self.count_output_rows(&s.child),
            PhysicalNode::Limit(l) => std::cmp::min(self.count_output_rows(&l.child), l.limit),
            PhysicalNode::Sink(s) => self.count_output_rows(&s.child),
            PhysicalNode::Exchange(e) => self.count_output_rows(&e.child),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_physical_plan_translator() {
        let translator = PhysicalPlanTranslator::new();
        let logical_builder = super::super::LogicalPlanBuilder::new();

        let scan = super::super::LogicalNode::Scan(super::super::ScanNode {
            table_name: "orders".to_string(),
            columns: vec!["*".to_string()],
            is_streaming: true,
            predicate: None,
        });

        let logical_plan = logical_builder.build(scan);
        let physical_plan = translator.translate(&logical_plan, Some("orders_agg")).unwrap();

        assert!(physical_plan.estimated_parallelism > 0);
        assert!(physical_plan.memory_requirement_bytes > 0);
    }

    #[tokio::test]
    async fn test_executor() {
        let translator = PhysicalPlanTranslator::new();
        let logical_builder = super::super::LogicalPlanBuilder::new();
        let executor = PhysicalPlanExecutor::new();

        let scan = super::super::LogicalNode::Scan(super::super::ScanNode {
            table_name: "orders".to_string(),
            columns: vec!["*".to_string()],
            is_streaming: true,
            predicate: None,
        });

        let logical_plan = logical_builder.build(scan);
        let physical_plan = translator.translate(&logical_plan, None).unwrap();

        let result = executor.execute(&physical_plan).await.unwrap();
        assert_eq!(result.status, ExecutionStatus::Completed);
        assert!(result.row_count > 0);
    }
}
