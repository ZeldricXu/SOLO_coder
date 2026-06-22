use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Instant;

use async_trait::async_trait;
use common::error::AppError;
use common::types::InferenceRequest;
use petgraph::algo::toposort;
use petgraph::graph::{DiGraph, NodeIndex};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::Mutex;
use tracing::{debug, error, info, instrument, warn};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FieldMapping {
    pub from: String,
    pub to: String,
    #[serde(default)]
    pub source_node: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeConfig {
    pub id: String,
    pub model_name: String,
    #[serde(default)]
    pub model_version: Option<String>,
    #[serde(default)]
    pub dependencies: Vec<String>,
    #[serde(default)]
    pub input_mappings: Vec<FieldMapping>,
    #[serde(default)]
    pub output_field: Option<String>,
    #[serde(default)]
    pub parallel: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineConfig {
    pub name: String,
    pub nodes: Vec<NodeConfig>,
    #[serde(default)]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct NodeExecutionResult {
    pub node_id: String,
    pub outputs: Value,
    pub latency_ms: u64,
    pub success: bool,
    pub error: Option<String>,
}

pub struct PipelineContext {
    pub request_id: String,
    pub original_input: Value,
    pub node_outputs: Mutex<HashMap<String, Value>>,
    pub start_time: Instant,
    pub node_results: Mutex<Vec<NodeExecutionResult>>,
}

impl PipelineContext {
    pub fn new(request_id: String, original_input: Value) -> Self {
        Self {
            request_id,
            original_input,
            node_outputs: Mutex::new(HashMap::new()),
            start_time: Instant::now(),
            node_results: Mutex::new(Vec::new()),
        }
    }
}

#[derive(Debug)]
pub struct PipelineExecutionPlan {
    pub graph: DiGraph<String, ()>,
    pub node_configs: HashMap<String, NodeConfig>,
    pub topo_order: Vec<NodeIndex>,
    pub levels: Vec<Vec<NodeIndex>>,
}

#[derive(Debug, Clone, Serialize)]
pub struct PipelineResult {
    pub request_id: String,
    pub final_output: Value,
    pub total_latency_ms: u64,
    pub node_results: Vec<NodeExecutionResult>,
    pub success: bool,
}

#[async_trait]
pub trait PipelineModelExecutor: Send + Sync {
    async fn execute_model(
        &self,
        node_id: &str,
        model_name: &str,
        model_version: Option<&str>,
        inputs: Value,
        request_id: &str,
    ) -> Result<Value, AppError>;
}

pub struct InferencePipeline {
    pub name: String,
    plan: PipelineExecutionPlan,
    executor: Option<Arc<dyn PipelineModelExecutor>>,
}

impl std::fmt::Debug for InferencePipeline {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("InferencePipeline")
            .field("name", &self.name)
            .field("plan", &self.plan)
            .finish()
    }
}

impl InferencePipeline {
    pub fn node_count(&self) -> usize {
        self.plan.node_configs.len()
    }

    pub fn from_yaml(
        yaml_str: &str,
        executor: Arc<dyn PipelineModelExecutor>,
    ) -> Result<Self, AppError> {
        let config: PipelineConfig = serde_yaml::from_str(yaml_str)
            .map_err(|e| AppError::Config(format!("Failed to parse YAML pipeline config: {}", e)))?;
        Self::from_config(config, Some(executor))
    }

    pub fn from_config(
        config: PipelineConfig,
        executor: Option<Arc<dyn PipelineModelExecutor>>,
    ) -> Result<Self, AppError> {
        let plan = Self::compile_plan(&config)?;
        Ok(Self {
            name: config.name,
            plan,
            executor,
        })
    }

    fn compile_plan(config: &PipelineConfig) -> Result<PipelineExecutionPlan, AppError> {
        let mut graph = DiGraph::<String, ()>::new();
        let mut node_indices: HashMap<String, NodeIndex> = HashMap::new();
        let mut node_configs: HashMap<String, NodeConfig> = HashMap::new();

        for node in &config.nodes {
            if node_indices.contains_key(&node.id) {
                return Err(AppError::Config(format!(
                    "Duplicate node id: {}",
                    node.id
                )));
            }
            let idx = graph.add_node(node.id.clone());
            node_indices.insert(node.id.clone(), idx);
            node_configs.insert(node.id.clone(), node.clone());
        }

        for node in &config.nodes {
            let current_idx = node_indices
                .get(&node.id)
                .ok_or_else(|| AppError::Config(format!("Node {} not found", node.id)))?;

            for dep_id in &node.dependencies {
                let dep_idx = node_indices.get(dep_id).ok_or_else(|| {
                    AppError::Config(format!(
                        "Dependency {} not found for node {}",
                        dep_id, node.id
                    ))
                })?;
                graph.add_edge(*dep_idx, *current_idx, ());
            }
        }

        let topo_order = toposort(&graph, None)
            .map_err(|cycle| AppError::Config(format!("Pipeline has a cycle: {:?}", cycle)))?;

        let levels = Self::compute_levels(&graph, &topo_order);

        Ok(PipelineExecutionPlan {
            graph,
            node_configs,
            topo_order,
            levels,
        })
    }

    fn compute_levels(
        graph: &DiGraph<String, ()>,
        topo_order: &[NodeIndex],
    ) -> Vec<Vec<NodeIndex>> {
        let mut in_degree: HashMap<NodeIndex, usize> = HashMap::new();
        for idx in topo_order {
            in_degree.insert(*idx, graph.neighbors_directed(*idx, petgraph::Direction::Incoming).count());
        }

        let mut levels: Vec<Vec<NodeIndex>> = Vec::new();
        let mut processed: HashSet<NodeIndex> = HashSet::new();
        let mut remaining: HashMap<NodeIndex, usize> = in_degree.clone();

        while processed.len() < topo_order.len() {
            let mut current_level: Vec<NodeIndex> = Vec::new();

            for idx in topo_order {
                if !processed.contains(idx) && remaining.get(idx) == Some(&0) {
                    current_level.push(*idx);
                }
            }

            if current_level.is_empty() {
                break;
            }

            for idx in &current_level {
                processed.insert(*idx);
                let neighbors: Vec<NodeIndex> = graph
                    .neighbors_directed(*idx, petgraph::Direction::Outgoing)
                    .collect();
                for neighbor in neighbors {
                    if let Some(deg) = remaining.get_mut(&neighbor) {
                        *deg = deg.saturating_sub(1);
                    }
                }
            }

            levels.push(current_level);
        }

        levels
    }

    #[instrument(skip(self, request), fields(pipeline = %self.name, request_id = %request.request_id))]
    pub async fn execute(&self, request: &InferenceRequest) -> Result<PipelineResult, AppError> {
        let request_id = request.request_id.clone();
        let context = Arc::new(PipelineContext::new(
            request_id.clone(),
            request.inputs.clone(),
        ));

        info!("Starting pipeline '{}' execution", self.name);

        for (level_idx, level) in self.plan.levels.iter().enumerate() {
            debug!("Executing pipeline level {} with {} nodes", level_idx, level.len());

            let mut futures = Vec::with_capacity(level.len());
            for node_idx in level {
                let node_id = self.plan.graph[*node_idx].clone();
                let node_config = self
                    .plan
                    .node_configs
                    .get(&node_id)
                    .ok_or_else(|| AppError::Config(format!("Node config not found for {}", node_id)))?
                    .clone();
                let ctx = context.clone();
                let executor = self.executor.clone();

                let fut = async move {
                    let start = Instant::now();
                    let result = Self::execute_node(&node_config, ctx.clone(), executor).await;
                    let latency_ms = start.elapsed().as_millis() as u64;

                    let (outputs, success, error) = match result {
                        Ok(v) => (v, true, None),
                        Err(e) => {
                            error!(
                                "Node '{}' execution failed after {}ms: {}",
                                node_config.id,
                                latency_ms,
                                e
                            );
                            (Value::Null, false, Some(e.to_string()))
                        }
                    };

                    let output_key = node_config
                        .output_field
                        .clone()
                        .unwrap_or_else(|| node_config.id.clone());

                    {
                        let mut node_outputs = ctx.node_outputs.lock().await;
                        node_outputs.insert(output_key, outputs.clone());
                    }

                    let node_result = NodeExecutionResult {
                        node_id: node_config.id.clone(),
                        outputs: outputs.clone(),
                        latency_ms,
                        success,
                        error,
                    };

                    {
                        let mut results = ctx.node_results.lock().await;
                        results.push(node_result);
                    }

                    if !success {
                        return Err(AppError::InferenceError(format!(
                            "Node '{}' failed",
                            node_config.id
                        )));
                    }

                    debug!(
                        "Node '{}' completed successfully in {}ms",
                        node_config.id, latency_ms
                    );
                    Ok(())
                };

                futures.push(fut);
            }

            let results = futures::future::join_all(futures).await;
            for r in results {
                r?;
            }
        }

        let total_latency_ms = context.start_time.elapsed().as_millis() as u64;
        let node_results = context.node_results.lock().await.clone();

        let final_output = self.extract_final_output(context.clone()).await;

        info!(
            "Pipeline '{}' completed in {}ms",
            self.name, total_latency_ms
        );

        Ok(PipelineResult {
            request_id,
            final_output,
            total_latency_ms,
            node_results,
            success: true,
        })
    }

    async fn execute_node(
        node_config: &NodeConfig,
        context: Arc<PipelineContext>,
        executor: Option<Arc<dyn PipelineModelExecutor>>,
    ) -> Result<Value, AppError> {
        let inputs = Self::build_node_inputs(node_config, context.clone()).await;

        debug!(
            "Executing node '{}' with model '{}' (inputs keys: {:?})",
            node_config.id,
            node_config.model_name,
            inputs.as_object().map(|o| o.keys().cloned().collect::<Vec<_>>())
        );

        if let Some(exec) = executor {
            exec.execute_model(
                &node_config.id,
                &node_config.model_name,
                node_config.model_version.as_deref(),
                inputs,
                &context.request_id,
            )
            .await
        } else {
            warn!("No executor set, passing through inputs as outputs for node '{}'", node_config.id);
            Ok(inputs)
        }
    }

    async fn build_node_inputs(
        node_config: &NodeConfig,
        context: Arc<PipelineContext>,
    ) -> Value {
        let mut result = serde_json::Map::new();
        let node_outputs = context.node_outputs.lock().await;

        for mapping in &node_config.input_mappings {
            let source_value = match &mapping.source_node {
                Some(source_node_id) => {
                    let output_key = node_outputs.keys()
                        .find(|k| k == &source_node_id)
                        .cloned()
                        .unwrap_or_else(|| source_node_id.clone());
                    node_outputs
                        .get(&output_key)
                        .and_then(|v| v.get(&mapping.from))
                        .cloned()
                        .unwrap_or(Value::Null)
                }
                None => context
                    .original_input
                    .get(&mapping.from)
                    .cloned()
                    .unwrap_or(Value::Null),
            };

            result.insert(mapping.to.clone(), source_value);
        }

        Value::Object(result)
    }

    async fn extract_final_output(&self, context: Arc<PipelineContext>) -> Value {
        let node_outputs = context.node_outputs.lock().await;

        if node_outputs.is_empty() {
            return context.original_input.clone();
        }

        if let Some(last_idx) = self.plan.topo_order.last() {
            let last_node_id = &self.plan.graph[*last_idx];
            if let Some(output) = node_outputs.get(last_node_id) {
                return output.clone();
            }
        }

        let mut all_outputs = serde_json::Map::new();
        for (k, v) in node_outputs.iter() {
            all_outputs.insert(k.clone(), v.clone());
        }
        Value::Object(all_outputs)
    }

    pub fn empty() -> Self {
        let plan = PipelineExecutionPlan {
            graph: DiGraph::new(),
            node_configs: HashMap::new(),
            topo_order: Vec::new(),
            levels: Vec::new(),
        };
        Self {
            name: "empty_passthrough".to_string(),
            plan,
            executor: None,
        }
    }
}

#[async_trait::async_trait]
pub trait PipelineStep: Send + Sync {
    fn name(&self) -> &str;
    async fn process(&self, data: Value) -> Result<Value, AppError>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NormalizeStepConfig {
    pub mean: Vec<f32>,
    pub std: Vec<f32>,
    pub scale: Option<f32>,
}

pub struct NormalizeStep {
    config: NormalizeStepConfig,
}

impl NormalizeStep {
    pub fn new(config: NormalizeStepConfig) -> Self {
        Self { config }
    }

    pub fn from_values(mean: Vec<f32>, std: Vec<f32>, scale: Option<f32>) -> Self {
        Self {
            config: NormalizeStepConfig { mean, std, scale },
        }
    }
}

#[async_trait::async_trait]
impl PipelineStep for NormalizeStep {
    fn name(&self) -> &str {
        "normalize"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let scale = self.config.scale.unwrap_or(1.0 / 255.0);
        let mean = &self.config.mean;
        let std = &self.config.std;

        let array = data
            .as_array()
            .ok_or_else(|| AppError::Validation("NormalizeStep expects array input".into()))?;

        let channels = array.len();
        if channels != mean.len() || channels != std.len() {
            return Err(AppError::Validation(format!(
                "Channel count mismatch: data={}, mean={}, std={}",
                channels,
                mean.len(),
                std.len()
            )));
        }

        let mut result = Vec::with_capacity(channels);
        for (c, channel) in array.iter().enumerate() {
            let values: Vec<f32> = channel
                .as_array()
                .ok_or_else(|| AppError::Validation("Expected 2D array for image data".into()))?
                .iter()
                .map(|v| {
                    let pixel = v.as_f64().unwrap_or(0.0) as f32;
                    ((pixel * scale) - mean[c]) / std[c]
                })
                .collect();
            result.push(serde_json::to_value(values)?);
        }

        Ok(Value::Array(result))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResizeStepConfig {
    pub width: u32,
    pub height: u32,
}

pub struct ImageResizeStep {
    config: ResizeStepConfig,
}

impl ImageResizeStep {
    pub fn new(config: ResizeStepConfig) -> Self {
        Self { config }
    }

    pub fn from_dims(width: u32, height: u32) -> Self {
        Self {
            config: ResizeStepConfig { width, height },
        }
    }
}

#[async_trait::async_trait]
impl PipelineStep for ImageResizeStep {
    fn name(&self) -> &str {
        "image_resize"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        debug!(
            "Resizing image to {}x{}",
            self.config.width, self.config.height
        );
        Ok(data)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToTensorStepConfig {
    pub permute: Option<Vec<usize>>,
}

pub struct ToTensorStep {
    config: ToTensorStepConfig,
}

impl ToTensorStep {
    pub fn new(config: ToTensorStepConfig) -> Self {
        Self { config }
    }
}

#[async_trait::async_trait]
impl PipelineStep for ToTensorStep {
    fn name(&self) -> &str {
        "to_tensor"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let array = data
            .as_array()
            .ok_or_else(|| AppError::Validation("ToTensorStep expects array input".into()))?;

        if let Some(permute) = &self.config.permute {
            let ndims = array.len();
            if permute.len() != ndims {
                return Err(AppError::Validation(format!(
                    "Permute length {} does not match data dims {}",
                    permute.len(),
                    ndims
                )));
            }
            let mut permuted = Vec::with_capacity(ndims);
            for &idx in permute {
                if idx >= ndims {
                    return Err(AppError::Validation(format!(
                        "Permute index {} out of bounds for {} dims",
                        idx, ndims
                    )));
                }
                permuted.push(array[idx].clone());
            }
            return Ok(Value::Array(permuted));
        }

        Ok(data)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenizerStepConfig {
    pub vocab_path: String,
    pub max_len: u32,
    pub add_special_tokens: Option<bool>,
}

pub struct TokenizerStep {
    config: TokenizerStepConfig,
}

impl TokenizerStep {
    pub fn new(config: TokenizerStepConfig) -> Self {
        Self { config }
    }

    pub fn from_path(vocab_path: impl Into<String>, max_len: u32) -> Self {
        Self {
            config: TokenizerStepConfig {
                vocab_path: vocab_path.into(),
                max_len,
                add_special_tokens: Some(true),
            },
        }
    }
}

#[async_trait::async_trait]
impl PipelineStep for TokenizerStep {
    fn name(&self) -> &str {
        "tokenizer"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let text = data
            .as_str()
            .ok_or_else(|| AppError::Validation("TokenizerStep expects string input".into()))?;

        info!(
            "[MOCK] Tokenizing text with vocab={}, max_len={}",
            self.config.vocab_path, self.config.max_len
        );

        let tokens: Vec<u64> = text
            .chars()
            .take(self.config.max_len as usize)
            .enumerate()
            .map(|(i, c)| ((c as u64) % 30000) + (i as u64 % 100))
            .collect();

        let pad_len = self.config.max_len as usize - tokens.len();
        let mut padded = tokens;
        padded.extend(std::iter::repeat(0).take(pad_len.max(0)));

        let result = serde_json::json!({
            "input_ids": padded,
            "attention_mask": padded.iter().map(|&t| if t > 0 { 1u64 } else { 0u64 }).collect::<Vec<_>>(),
            "token_type_ids": vec![0u64; self.config.max_len as usize],
        });

        Ok(result)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TopKStepConfig {
    pub k: usize,
}

pub struct TopKStep {
    config: TopKStepConfig,
}

impl TopKStep {
    pub fn new(config: TopKStepConfig) -> Self {
        Self { config }
    }

    pub fn with_k(k: usize) -> Self {
        Self {
            config: TopKStepConfig { k },
        }
    }
}

#[async_trait::async_trait]
impl PipelineStep for TopKStep {
    fn name(&self) -> &str {
        "top_k"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let logits: Vec<f32> = serde_json::from_value(data)
            .map_err(|e| AppError::Validation(format!("TopKStep expects numeric array: {}", e)))?;

        let mut indexed: Vec<(usize, f32)> = logits.into_iter().enumerate().collect();
        indexed.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

        let top_k: Vec<Value> = indexed
            .into_iter()
            .take(self.config.k)
            .map(|(idx, score)| {
                serde_json::json!({
                    "class_id": idx,
                    "score": score,
                })
            })
            .collect();

        Ok(Value::Array(top_k))
    }
}

pub struct SoftmaxStep;

impl SoftmaxStep {
    pub fn new() -> Self {
        Self
    }
}

impl Default for SoftmaxStep {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait]
impl PipelineStep for SoftmaxStep {
    fn name(&self) -> &str {
        "softmax"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let logits: Vec<f32> = serde_json::from_value(data)
            .map_err(|e| AppError::Validation(format!("SoftmaxStep expects numeric array: {}", e)))?;

        let max_val = logits
            .iter()
            .cloned()
            .fold(f32::NEG_INFINITY, f32::max);
        let exps: Vec<f32> = logits.iter().map(|x| (x - max_val).exp()).collect();
        let sum: f32 = exps.iter().sum();

        let probs: Vec<f32> = exps.iter().map(|x| x / sum).collect();
        Ok(serde_json::to_value(probs)?)
    }
}

pub struct ArgMaxStep;

impl ArgMaxStep {
    pub fn new() -> Self {
        Self
    }
}

impl Default for ArgMaxStep {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait]
impl PipelineStep for ArgMaxStep {
    fn name(&self) -> &str {
        "argmax"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let logits: Vec<f32> = serde_json::from_value(data)
            .map_err(|e| AppError::Validation(format!("ArgMaxStep expects numeric array: {}", e)))?;

        let mut best_idx = 0usize;
        let mut best_val = f32::NEG_INFINITY;
        for (i, &v) in logits.iter().enumerate() {
            if v > best_val {
                best_val = v;
                best_idx = i;
            }
        }

        Ok(serde_json::json!({
            "class_id": best_idx,
            "score": best_val,
        }))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThresholdStepConfig {
    pub threshold: f32,
    pub label_true: Option<String>,
    pub label_false: Option<String>,
}

pub struct ThresholdStep {
    config: ThresholdStepConfig,
}

impl ThresholdStep {
    pub fn new(config: ThresholdStepConfig) -> Self {
        Self { config }
    }

    pub fn with_threshold(threshold: f32) -> Self {
        Self {
            config: ThresholdStepConfig {
                threshold,
                label_true: Some("positive".into()),
                label_false: Some("negative".into()),
            },
        }
    }
}

#[async_trait::async_trait]
impl PipelineStep for ThresholdStep {
    fn name(&self) -> &str {
        "threshold"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        let score = data
            .as_f64()
            .ok_or_else(|| AppError::Validation("ThresholdStep expects numeric input".into()))?
            as f32;

        let is_positive = score >= self.config.threshold;
        let label = if is_positive {
            self.config.label_true.clone()
        } else {
            self.config.label_false.clone()
        };

        Ok(serde_json::json!({
            "score": score,
            "threshold": self.config.threshold,
            "prediction": is_positive,
            "label": label,
        }))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomStepConfig {
    pub script_path: String,
    pub params: Option<Value>,
}

pub struct CustomStep {
    config: CustomStepConfig,
}

impl CustomStep {
    pub fn new(config: CustomStepConfig) -> Self {
        Self { config }
    }

    pub fn from_script(script_path: impl Into<String>) -> Self {
        Self {
            config: CustomStepConfig {
                script_path: script_path.into(),
                params: None,
            },
        }
    }
}

#[async_trait::async_trait]
impl PipelineStep for CustomStep {
    fn name(&self) -> &str {
        "custom"
    }

    async fn process(&self, data: Value) -> Result<Value, AppError> {
        info!(
            "[MOCK] Running custom script: {}, params={:?}",
            self.config.script_path, self.config.params
        );
        Ok(data)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum StepConfig {
    Normalize(NormalizeStepConfig),
    ImageResize(ResizeStepConfig),
    ToTensor(ToTensorStepConfig),
    Tokenizer(TokenizerStepConfig),
    TopK(TopKStepConfig),
    Softmax,
    ArgMax,
    Threshold(ThresholdStepConfig),
    Custom(CustomStepConfig),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LegacyPipelineConfig {
    pub preprocess: Option<Vec<StepConfig>>,
    pub postprocess: Option<Vec<StepConfig>>,
}

pub struct InferencePipelineLegacy {
    pub preprocess_steps: Vec<Box<dyn PipelineStep>>,
    pub postprocess_steps: Vec<Box<dyn PipelineStep>>,
}

impl std::fmt::Debug for InferencePipelineLegacy {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("InferencePipelineLegacy")
            .field("preprocess_steps", &self.preprocess_steps.iter().map(|s| s.name()).collect::<Vec<_>>())
            .field("postprocess_steps", &self.postprocess_steps.iter().map(|s| s.name()).collect::<Vec<_>>())
            .finish()
    }
}

impl InferencePipelineLegacy {
    pub fn new(
        preprocess_steps: Vec<Box<dyn PipelineStep>>,
        postprocess_steps: Vec<Box<dyn PipelineStep>>,
    ) -> Self {
        Self {
            preprocess_steps,
            postprocess_steps,
        }
    }

    pub fn empty() -> Self {
        Self {
            preprocess_steps: Vec::new(),
            postprocess_steps: Vec::new(),
        }
    }

    pub async fn execute_preprocess(&self, data: Value) -> Result<Value, AppError> {
        execute_pipeline(&self.preprocess_steps, data).await
    }

    pub async fn execute_postprocess(&self, data: Value) -> Result<Value, AppError> {
        execute_pipeline(&self.postprocess_steps, data).await
    }
}

pub async fn execute_pipeline(
    steps: &[Box<dyn PipelineStep>],
    mut data: Value,
) -> Result<Value, AppError> {
    for step in steps {
        data = step.process(data).await.map_err(|e| {
            AppError::InferenceError(format!("Pipeline step '{}' failed: {}", step.name(), e))
        })?;
    }
    Ok(data)
}

pub struct PipelineBuilder {
    preprocess: Vec<Box<dyn PipelineStep>>,
    postprocess: Vec<Box<dyn PipelineStep>>,
}

impl PipelineBuilder {
    pub fn new() -> Self {
        Self {
            preprocess: Vec::new(),
            postprocess: Vec::new(),
        }
    }

    pub fn add_preprocess<S: PipelineStep + 'static>(mut self, step: S) -> Self {
        self.preprocess.push(Box::new(step));
        self
    }

    pub fn add_postprocess<S: PipelineStep + 'static>(mut self, step: S) -> Self {
        self.postprocess.push(Box::new(step));
        self
    }

    pub fn from_config(config: &LegacyPipelineConfig) -> Result<Self, AppError> {
        let mut builder = Self::new();

        if let Some(pre_steps) = &config.preprocess {
            for step_config in pre_steps {
                builder.preprocess.push(Self::create_step(step_config)?);
            }
        }

        if let Some(post_steps) = &config.postprocess {
            for step_config in post_steps {
                builder.postprocess.push(Self::create_step(step_config)?);
            }
        }

        Ok(builder)
    }

    pub fn from_json(value: &Value) -> Result<Self, AppError> {
        let config: LegacyPipelineConfig = serde_json::from_value(value.clone())?;
        Self::from_config(&config)
    }

    pub fn from_file(path: impl AsRef<std::path::Path>) -> Result<Self, AppError> {
        let content = std::fs::read_to_string(path)
            .map_err(|e| AppError::Config(format!("Failed to read pipeline config: {}", e)))?;
        let value: Value = serde_json::from_str(&content)?;
        Self::from_json(&value)
    }

    fn create_step(config: &StepConfig) -> Result<Box<dyn PipelineStep>, AppError> {
        match config {
            StepConfig::Normalize(c) => Ok(Box::new(NormalizeStep::new(c.clone()))),
            StepConfig::ImageResize(c) => Ok(Box::new(ImageResizeStep::new(c.clone()))),
            StepConfig::ToTensor(c) => Ok(Box::new(ToTensorStep::new(c.clone()))),
            StepConfig::Tokenizer(c) => Ok(Box::new(TokenizerStep::new(c.clone()))),
            StepConfig::TopK(c) => Ok(Box::new(TopKStep::new(c.clone()))),
            StepConfig::Softmax => Ok(Box::new(SoftmaxStep::new())),
            StepConfig::ArgMax => Ok(Box::new(ArgMaxStep::new())),
            StepConfig::Threshold(c) => Ok(Box::new(ThresholdStep::new(c.clone()))),
            StepConfig::Custom(c) => Ok(Box::new(CustomStep::new(c.clone()))),
        }
    }

    pub fn build(self) -> InferencePipelineLegacy {
        InferencePipelineLegacy::new(self.preprocess, self.postprocess)
    }
}

impl Default for PipelineBuilder {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockExecutor;

    #[async_trait]
    impl PipelineModelExecutor for MockExecutor {
        async fn execute_model(
            &self,
            node_id: &str,
            model_name: &str,
            _model_version: Option<&str>,
            inputs: Value,
            _request_id: &str,
        ) -> Result<Value, AppError> {
            match model_name {
                "user_recall" => Ok(serde_json::json!({
                    "candidates": [1, 2, 3, 4, 5]
                })),
                "deepfm_rank" | "din_rank" => {
                    let items = inputs.get("items").cloned().unwrap_or(Value::Null);
                    Ok(serde_json::json!({
                        "scores": items
                    }))
                }
                "rank_merge" => {
                    let scores_a = inputs.get("scores_a").cloned().unwrap_or(Value::Null);
                    let scores_b = inputs.get("scores_b").cloned().unwrap_or(Value::Null);
                    Ok(serde_json::json!({
                        "ranked_items": vec![scores_a, scores_b]
                    }))
                }
                "content_filter" => {
                    let items = inputs.get("items").cloned().unwrap_or(Value::Null);
                    Ok(serde_json::json!({
                        "final_items": items
                    }))
                }
                _ => Ok(serde_json::json!({
                    "node_id": node_id,
                    "inputs": inputs
                })),
            }
        }
    }

    fn get_test_yaml() -> &'static str {
        r#"
name: recommendation_pipeline
description: "推荐流程：召回→多路排序→过滤"
nodes:
  - id: recall
    model_name: user_recall
    dependencies: []
    input_mappings:
      - { from: "user_id", to: "user_id" }
      - { from: "context", to: "context" }
    output_field: candidates

  - id: rank_a
    model_name: deepfm_rank
    dependencies: ["recall"]
    input_mappings:
      - { from: "user_id", to: "user_id" }
      - { from: "candidates", source_node: "recall", to: "items" }

  - id: rank_b
    model_name: din_rank
    dependencies: ["recall"]
    input_mappings:
      - { from: "user_id", to: "user_id" }
      - { from: "candidates", source_node: "recall", to: "items" }

  - id: merge_ranks
    model_name: rank_merge
    dependencies: ["rank_a", "rank_b"]
    input_mappings:
      - { from: "scores", source_node: "rank_a", to: "scores_a" }
      - { from: "scores", source_node: "rank_b", to: "scores_b" }

  - id: filter
    model_name: content_filter
    dependencies: ["merge_ranks"]
    input_mappings:
      - { from: "ranked_items", source_node: "merge_ranks", to: "items" }
"#
    }

    #[tokio::test]
    async fn test_from_yaml() {
        let executor = Arc::new(MockExecutor);
        let pipeline = InferencePipeline::from_yaml(get_test_yaml(), executor).unwrap();
        assert_eq!(pipeline.name, "recommendation_pipeline");
        assert_eq!(pipeline.plan.node_configs.len(), 5);
        assert_eq!(pipeline.plan.topo_order.len(), 5);
        assert_eq!(pipeline.plan.levels.len(), 4);
    }

    #[tokio::test]
    async fn test_execute_pipeline() {
        let executor = Arc::new(MockExecutor);
        let pipeline = InferencePipeline::from_yaml(get_test_yaml(), executor).unwrap();

        let request = InferenceRequest {
            request_id: "test-123".to_string(),
            model_name: "test".to_string(),
            version: None,
            inputs: serde_json::json!({
                "user_id": "user_001",
                "context": { "page": "home" }
            }),
            parameters: None,
            user_id: None,
            tenant_id: None,
            trace_id: None,
        };

        let result = pipeline.execute(&request).await.unwrap();
        assert!(result.success);
        assert_eq!(result.node_results.len(), 5);
        assert_eq!(result.request_id, "test-123");
    }

    #[tokio::test]
    async fn test_empty_pipeline() {
        let pipeline = InferencePipeline::empty();
        let request = InferenceRequest {
            request_id: "test-empty".to_string(),
            model_name: "test".to_string(),
            version: None,
            inputs: serde_json::json!({ "key": "value" }),
            parameters: None,
            user_id: None,
            tenant_id: None,
            trace_id: None,
        };

        let result = pipeline.execute(&request).await.unwrap();
        assert!(result.success);
        assert_eq!(result.final_output, serde_json::json!({ "key": "value" }));
    }

    #[tokio::test]
    async fn test_parallel_levels() {
        let executor = Arc::new(MockExecutor);
        let pipeline = InferencePipeline::from_yaml(get_test_yaml(), executor).unwrap();

        assert_eq!(pipeline.plan.levels.len(), 4);
        assert_eq!(pipeline.plan.levels[0].len(), 1);
        assert_eq!(pipeline.plan.levels[1].len(), 2);
        assert_eq!(pipeline.plan.levels[2].len(), 1);
        assert_eq!(pipeline.plan.levels[3].len(), 1);
    }

    #[tokio::test]
    async fn test_detect_cycle() {
        let yaml_with_cycle = r#"
name: cyclic_pipeline
nodes:
  - id: a
    model_name: model_a
    dependencies: ["b"]
  - id: b
    model_name: model_b
    dependencies: ["a"]
"#;
        let executor = Arc::new(MockExecutor);
        let result = InferencePipeline::from_yaml(yaml_with_cycle, executor);
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_field_mapping() {
        let yaml_simple = r#"
name: simple_mapping
nodes:
  - id: step1
    model_name: test_model
    dependencies: []
    input_mappings:
      - { from: "input_a", to: "x" }
      - { from: "input_b", to: "y" }
    output_field: step1_out
"#;
        let executor = Arc::new(MockExecutor);
        let pipeline = InferencePipeline::from_yaml(yaml_simple, executor).unwrap();

        let request = InferenceRequest {
            request_id: "test-mapping".to_string(),
            model_name: "test".to_string(),
            version: None,
            inputs: serde_json::json!({
                "input_a": 123,
                "input_b": "hello"
            }),
            parameters: None,
            user_id: None,
            tenant_id: None,
            trace_id: None,
        };

        let result = pipeline.execute(&request).await.unwrap();
        assert!(result.success);
    }

    #[tokio::test]
    async fn test_softmax_step() {
        let step = SoftmaxStep::new();
        let input = serde_json::json!([1.0, 2.0, 3.0]);
        let result = step.process(input).await.unwrap();
        let probs: Vec<f32> = serde_json::from_value(result).unwrap();
        assert!((probs.iter().sum::<f32>() - 1.0).abs() < 0.001);
        assert!(probs[2] > probs[1] && probs[1] > probs[0]);
    }

    #[tokio::test]
    async fn test_topk_step() {
        let step = TopKStep::with_k(3);
        let input = serde_json::json!([0.1, 0.9, 0.5, 0.7, 0.3]);
        let result = step.process(input).await.unwrap();
        let arr = result.as_array().unwrap();
        assert_eq!(arr.len(), 3);
        assert_eq!(arr[0]["class_id"], 1);
        let score = arr[0]["score"].as_f64().unwrap();
        assert!((score - 0.9).abs() < 0.0001);
    }

    #[tokio::test]
    async fn test_threshold_step() {
        let step = ThresholdStep::with_threshold(0.5);
        let pos = step.process(serde_json::json!(0.8)).await.unwrap();
        assert_eq!(pos["prediction"], true);
        assert_eq!(pos["label"], "positive");

        let neg = step.process(serde_json::json!(0.3)).await.unwrap();
        assert_eq!(neg["prediction"], false);
        assert_eq!(neg["label"], "negative");
    }

    #[tokio::test]
    async fn test_tokenizer_step() {
        let step = TokenizerStep::from_path("/tmp/vocab.json", 8);
        let result = step.process(serde_json::json!("hello world")).await.unwrap();
        let ids = result["input_ids"].as_array().unwrap();
        assert_eq!(ids.len(), 8);
    }

    #[tokio::test]
    async fn test_pipeline_builder_from_json() {
        let config = serde_json::json!({
            "preprocess": [
                {
                    "type": "normalize",
                    "mean": [0.485, 0.456, 0.406],
                    "std": [0.229, 0.224, 0.225],
                    "scale": 0.00392
                },
                {
                    "type": "to_tensor",
                    "permute": [2, 0, 1]
                }
            ],
            "postprocess": [
                { "type": "softmax" },
                {
                    "type": "top_k",
                    "k": 5
                }
            ]
        });

        let pipeline = PipelineBuilder::from_json(&config).unwrap().build();
        assert_eq!(pipeline.preprocess_steps.len(), 2);
        assert_eq!(pipeline.postprocess_steps.len(), 2);
    }

    #[tokio::test]
    async fn test_legacy_pipeline_execution() {
        let pipeline = PipelineBuilder::new()
            .add_postprocess(SoftmaxStep::new())
            .add_postprocess(TopKStep::with_k(2))
            .build();

        let result = pipeline
            .execute_postprocess(serde_json::json!([0.5, 2.0, 1.0]))
            .await
            .unwrap();

        let arr = result.as_array().unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0]["class_id"], 1);
    }
}
