use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{RwLock, Semaphore};
use serde::{Deserialize, Serialize};

use crate::data_lineage::{LineageExtractor, LineageGraph, LineageAnalyzer, LineageAnalysis};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchRequest<T> {
    pub items: Vec<T>,
    pub parallel: bool,
    pub max_concurrency: Option<usize>,
    pub timeout_ms: Option<u64>,
}

impl<T> BatchRequest<T> {
    pub fn new(items: Vec<T>) -> Self {
        Self {
            items,
            parallel: true,
            max_concurrency: Some(10),
            timeout_ms: Some(30000),
        }
    }

    pub fn sequential(items: Vec<T>) -> Self {
        Self {
            items,
            parallel: false,
            max_concurrency: Some(1),
            timeout_ms: Some(30000),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult<S, E> {
    pub successful: Vec<(usize, S)>,
    pub failed: Vec<(usize, E)>,
    pub total_count: usize,
    pub success_count: usize,
    pub failure_count: usize,
    pub duration_ms: u64,
}

impl<S, E> BatchResult<S, E> {
    pub fn all_successful(&self) -> bool {
        self.failure_count == 0
    }

    pub fn success_rate(&self) -> f64 {
        if self.total_count == 0 {
            return 1.0;
        }
        self.success_count as f64 / self.total_count as f64
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SqlParseRequest {
    pub sql: String,
    pub id: Option<String>,
    pub metadata: Option<HashMap<String, serde_json::Value>>,
}

impl SqlParseRequest {
    pub fn new(sql: impl Into<String>) -> Self {
        Self {
            sql: sql.into(),
            id: None,
            metadata: None,
        }
    }

    pub fn with_id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SqlParseResult {
    pub id: Option<String>,
    pub source_tables: Vec<String>,
    pub target_tables: Vec<String>,
    pub column_lineage: Vec<String>,
    pub sql: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphBuildRequest {
    pub sqls: Vec<String>,
    pub name: Option<String>,
    pub metadata: Option<HashMap<String, serde_json::Value>>,
}

impl GraphBuildRequest {
    pub fn new(sqls: Vec<String>) -> Self {
        Self {
            sqls,
            name: None,
            metadata: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GraphBuildResult {
    pub name: Option<String>,
    pub node_count: usize,
    pub edge_count: usize,
    pub is_dag: bool,
    pub tables: Vec<String>,
    pub has_cycles: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ImpactAnalysisRequest {
    pub table: String,
    pub max_depth: Option<usize>,
    pub include_columns: bool,
}

impl ImpactAnalysisRequest {
    pub fn new(table: impl Into<String>) -> Self {
        Self {
            table: table.into(),
            max_depth: None,
            include_columns: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ImpactAnalysisResult {
    pub source_table: String,
    pub downstream_tables: Vec<String>,
    pub total_impacted: usize,
    pub paths: Vec<Vec<String>>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BatchOperationMetrics {
    pub operation: String,
    pub total_items: usize,
    pub completed_items: usize,
    pub failed_items: usize,
    pub duration_ms: u64,
    pub parallel: bool,
    pub max_concurrency: usize,
}

pub struct BatchOperationConfig {
    pub default_concurrency: usize,
    pub default_timeout_ms: u64,
    pub max_batch_size: usize,
    pub enable_request_merging: bool,
    pub merge_threshold_ms: u64,
}

impl Default for BatchOperationConfig {
    fn default() -> Self {
        Self {
            default_concurrency: 10,
            default_timeout_ms: 30000,
            max_batch_size: 1000,
            enable_request_merging: true,
            merge_threshold_ms: 50,
        }
    }
}

pub struct BatchLineageService {
    extractor: Arc<LineageExtractor>,
    config: BatchOperationConfig,
    semaphore: Option<Arc<Semaphore>>,
    metrics: Arc<RwLock<BatchMetrics>>,
    request_merger: Option<Arc<RequestMerger>>,
}

#[derive(Debug, Default)]
struct BatchMetrics {
    total_batches_processed: u64,
    total_items_processed: u64,
    total_failures: u64,
    total_parallel_executions: u64,
    total_sequential_executions: u64,
    total_duration_ms: u64,
    merged_requests: u64,
}

#[derive(Clone)]
struct PendingRequest {
    id: String,
    sql: String,
    sender: tokio::sync::oneshot::Sender<Result<SqlParseResult, String>>,
    created_at: Instant,
}

struct RequestMerger {
    pending: Arc<RwLock<Vec<PendingRequest>>>,
    config: BatchOperationConfig,
}

impl RequestMerger {
    fn new(config: BatchOperationConfig) -> Self {
        Self {
            pending: Arc::new(RwLock::new(Vec::new())),
            config,
        }
    }

    async fn submit(
        &self,
        id: String,
        sql: String,
    ) -> tokio::sync::oneshot::Receiver<Result<SqlParseResult, String>> {
        let (sender, receiver) = tokio::sync::oneshot::channel();
        let mut pending = self.pending.write().await;
        pending.push(PendingRequest {
            id,
            sql,
            sender,
            created_at: Instant::now(),
        });
        receiver
    }

    async fn drain(&self) -> Vec<PendingRequest> {
        let mut pending = self.pending.write().await;
        std::mem::take(&mut *pending)
    }
}

impl BatchLineageService {
    pub fn new(extractor: Arc<LineageExtractor>) -> Self {
        let config = BatchOperationConfig::default();
        Self {
            extractor,
            config: config.clone(),
            semaphore: Some(Arc::new(Semaphore::new(config.default_concurrency))),
            metrics: Arc::new(RwLock::new(BatchMetrics::default())),
            request_merger: if config.enable_request_merging {
                Some(Arc::new(RequestMerger::new(config)))
            } else {
                None
            },
        }
    }

    pub fn with_config(extractor: Arc<LineageExtractor>, config: BatchOperationConfig) -> Self {
        Self {
            extractor,
            config: config.clone(),
            semaphore: Some(Arc::new(Semaphore::new(config.default_concurrency))),
            metrics: Arc::new(RwLock::new(BatchMetrics::default())),
            request_merger: if config.enable_request_merging {
                Some(Arc::new(RequestMerger::new(config)))
            } else {
                None
            },
        }
    }

    pub async fn batch_parse_sql(
        &self,
        request: BatchRequest<SqlParseRequest>,
    ) -> BatchResult<SqlParseResult, String> {
        let start = Instant::now();
        let extractor = self.extractor.clone();
        let total = request.items.len();

        let results = if request.parallel && request.items.len() > 1 {
            let concurrency = request.max_concurrency.unwrap_or(self.config.default_concurrency);
            let semaphore = Arc::new(Semaphore::new(concurrency));

            let mut handles = Vec::new();
            for (idx, item) in request.items.into_iter().enumerate() {
                let ext = extractor.clone();
                let sem = semaphore.clone();

                handles.push(tokio::spawn(async move {
                    let _permit = sem.acquire_owned().await.unwrap();
                    let result = ext.extract_from_sql(&item.sql);
                    (idx, item, result)
                }));
            }

            let mut output = Vec::new();
            for handle in handles {
                output.push(handle.await.unwrap());
            }
            output
        } else {
            let mut output = Vec::new();
            for (idx, item) in request.items.into_iter().enumerate() {
                let result = extractor.extract_from_sql(&item.sql);
                output.push((idx, item, result));
            }
            output
        };

        let mut successful = Vec::new();
        let mut failed = Vec::new();

        for (idx, item, result) in results {
            match result {
                Ok(lineage) => {
                    successful.push((
                        idx,
                        SqlParseResult {
                            id: item.id,
                            source_tables: lineage.source_tables.iter().map(|t| t.table.clone()).collect(),
                            target_tables: lineage.target_tables.iter().map(|t| t.table.clone()).collect(),
                            column_lineage: lineage.column_lineage.iter().map(|c| c.column.clone()).collect(),
                            sql: item.sql,
                        },
                    ));
                }
                Err(e) => {
                    failed.push((idx, format!("{:?}", e)));
                }
            }
        }

        let success_count = successful.len();
        let failure_count = failed.len();
        let duration_ms = start.elapsed().as_millis() as u64;

        {
            let mut metrics = self.metrics.write().await;
            metrics.total_batches_processed += 1;
            metrics.total_items_processed += total as u64;
            metrics.total_failures += failure_count as u64;
            if request.parallel {
                metrics.total_parallel_executions += 1;
            } else {
                metrics.total_sequential_executions += 1;
            }
            metrics.total_duration_ms += duration_ms;
        }

        BatchResult {
            successful,
            failed,
            total_count: total,
            success_count,
            failure_count,
            duration_ms,
        }
    }

    pub async fn batch_build_graphs(
        &self,
        request: BatchRequest<GraphBuildRequest>,
    ) -> BatchResult<(String, LineageGraph, GraphBuildResult), String> {
        let start = Instant::now();
        let extractor = self.extractor.clone();
        let total = request.items.len();

        let results = if request.parallel && request.items.len() > 1 {
            let concurrency = request.max_concurrency.unwrap_or(self.config.default_concurrency);
            let semaphore = Arc::new(Semaphore::new(concurrency));

            let mut handles = Vec::new();
            for (idx, item) in request.items.into_iter().enumerate() {
                let ext = extractor.clone();
                let sem = semaphore.clone();
                let name = item.name.clone().unwrap_or_else(|| format!("graph_{}", idx));

                handles.push(tokio::spawn(async move {
                    let _permit = sem.acquire_owned().await.unwrap();
                    let result = ext.build_graph(&item.sqls);
                    (idx, name, item, result)
                }));
            }

            let mut output = Vec::new();
            for handle in handles {
                output.push(handle.await.unwrap());
            }
            output
        } else {
            let mut output = Vec::new();
            for (idx, item) in request.items.into_iter().enumerate() {
                let name = item.name.clone().unwrap_or_else(|| format!("graph_{}", idx));
                let result = extractor.build_graph(&item.sqls);
                output.push((idx, name, item, result));
            }
            output
        };

        let mut successful = Vec::new();
        let mut failed = Vec::new();

        for (idx, name, _item, result) in results {
            match result {
                Ok(graph) => {
                    let analysis = LineageAnalyzer::analyze(&graph);
                    let tables = graph.get_all_tables().iter().map(|t| t.table.clone()).collect();
                    let build_result = GraphBuildResult {
                        name: Some(name.clone()),
                        node_count: analysis.total_nodes,
                        edge_count: analysis.total_edges,
                        is_dag: analysis.cycles.is_empty(),
                        tables,
                        has_cycles: !analysis.cycles.is_empty(),
                    };
                    successful.push((idx, (name, graph, build_result)));
                }
                Err(e) => {
                    failed.push((idx, format!("{:?}", e)));
                }
            }
        }

        let success_count = successful.len();
        let failure_count = failed.len();
        let duration_ms = start.elapsed().as_millis() as u64;

        {
            let mut metrics = self.metrics.write().await;
            metrics.total_batches_processed += 1;
            metrics.total_items_processed += total as u64;
            metrics.total_failures += failure_count as u64;
        }

        BatchResult {
            successful,
            failed,
            total_count: total,
            success_count,
            failure_count,
            duration_ms,
        }
    }

    pub async fn batch_impact_analysis(
        &self,
        graphs: &HashMap<String, LineageGraph>,
        request: BatchRequest<ImpactAnalysisRequest>,
    ) -> BatchResult<ImpactAnalysisResult, String> {
        let start = Instant::now();
        let total = request.items.len();

        let mut successful = Vec::new();
        let mut failed = Vec::new();

        for (idx, item) in request.items.into_iter().enumerate() {
            let mut downstream_tables = Vec::new();
            let mut paths = Vec::new();

            for graph in graphs.values() {
                let extractor = LineageExtractor::new();
                let impacted = extractor.get_impact_analysis(graph, &item.table);
                for t in impacted {
                    if !downstream_tables.contains(&t) {
                        downstream_tables.push(t);
                    }
                }

                for g in graphs.values() {
                    let tables: HashSet<_> = g.get_all_tables().iter().map(|t| t.table.clone()).collect();
                    if tables.contains(&item.table) {
                        for t in &downstream_tables {
                            if tables.contains(t) {
                                let found_paths = g.find_paths(&item.table, t);
                                for p in found_paths {
                                    if !paths.contains(&p) {
                                        paths.push(p);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            successful.push((
                idx,
                ImpactAnalysisResult {
                    source_table: item.table.clone(),
                    downstream_tables,
                    total_impacted: 0,
                    paths,
                },
            ));
        }

        let success_count = successful.len();
        let failure_count = failed.len();
        let duration_ms = start.elapsed().as_millis() as u64;

        BatchResult {
            successful,
            failed,
            total_count: total,
            success_count,
            failure_count,
            duration_ms,
        }
    }

    pub async fn merge_small_requests(
        &self,
        requests: Vec<SqlParseRequest>,
    ) -> BatchResult<SqlParseResult, String> {
        let merged = BatchRequest::new(requests);
        self.batch_parse_sql(merged).await
    }

    pub async fn get_metrics(&self) -> BatchOperationMetrics {
        let metrics = self.metrics.read().await;
        BatchOperationMetrics {
            operation: "batch_lineage".to_string(),
            total_items: metrics.total_items_processed as usize,
            completed_items: (metrics.total_items_processed - metrics.total_failures) as usize,
            failed_items: metrics.total_failures as usize,
            duration_ms: metrics.total_duration_ms,
            parallel: metrics.total_parallel_executions > 0,
            max_concurrency: self.config.default_concurrency,
        }
    }

    pub async fn reset_metrics(&self) {
        let mut metrics = self.metrics.write().await;
        *metrics = BatchMetrics::default();
    }
}

#[derive(Debug, Clone)]
pub struct GraphRegistry {
    graphs: Arc<RwLock<HashMap<String, LineageGraph>>>,
    metadata: Arc<RwLock<HashMap<String, serde_json::Value>>>,
}

impl GraphRegistry {
    pub fn new() -> Self {
        Self {
            graphs: Arc::new(RwLock::new(HashMap::new())),
            metadata: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn register(&self, name: impl Into<String>, graph: LineageGraph) {
        let name = name.into();
        let mut graphs = self.graphs.write().await;
        graphs.insert(name, graph);
    }

    pub async fn get(&self, name: &str) -> Option<LineageGraph> {
        let graphs = self.graphs.read().await;
        graphs.get(name).cloned()
    }

    pub async fn get_all(&self) -> HashMap<String, LineageGraph> {
        let graphs = self.graphs.read().await;
        graphs.clone()
    }

    pub async fn remove(&self, name: &str) -> bool {
        let mut graphs = self.graphs.write().await;
        graphs.remove(name).is_some()
    }

    pub async fn list(&self) -> Vec<String> {
        let graphs = self.graphs.read().await;
        graphs.keys().cloned().collect()
    }

    pub async fn clear(&self) {
        let mut graphs = self.graphs.write().await;
        graphs.clear();
    }

    pub async fn batch_analysis(&self, names: &[String]) -> HashMap<String, Option<LineageAnalysis>> {
        let graphs = self.graphs.read().await;
        let mut results = HashMap::new();

        for name in names {
            if let Some(graph) = graphs.get(name) {
                results.insert(name.clone(), Some(LineageAnalyzer::analyze(graph)));
            } else {
                results.insert(name.clone(), None);
            }
        }

        results
    }
}

impl Default for GraphRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchedSqlSubmission {
    pub sqls: Vec<String>,
    pub batch_size: usize,
    pub parallel: bool,
}

impl BatchedSqlSubmission {
    pub fn split_into_batches(&self) -> Vec<Vec<String>> {
        self.sqls.chunks(self.batch_size)
            .map(|chunk| chunk.to_vec())
            .collect()
    }
}

pub async fn process_sql_batches(
    extractor: &LineageExtractor,
    batches: &[Vec<String>],
    parallel: bool,
) -> Vec<Result<LineageGraph, String>> {
    let mut results = Vec::new();

    if parallel {
        let mut handles = Vec::new();
        for batch in batches {
            let ext = extractor.clone();
            let batch = batch.clone();
            handles.push(tokio::spawn(async move {
                ext.build_graph(&batch).map_err(|e| format!("{:?}", e))
            }));
        }
        for handle in handles {
            results.push(handle.await.unwrap());
        }
    } else {
        for batch in batches {
            results.push(
                extractor.build_graph(batch).map_err(|e| format!("{:?}", e))
            );
        }
    }

    results
}
