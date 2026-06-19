use common::error::AppError;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fmt;
use std::path::Path;
use tracing::{debug, info};

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
pub struct PipelineConfig {
    pub preprocess: Option<Vec<StepConfig>>,
    pub postprocess: Option<Vec<StepConfig>>,
}

pub struct InferencePipeline {
    pub preprocess_steps: Vec<Box<dyn PipelineStep>>,
    pub postprocess_steps: Vec<Box<dyn PipelineStep>>,
}

impl fmt::Debug for InferencePipeline {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("InferencePipeline")
            .field("preprocess_steps", &self.preprocess_steps.iter().map(|s| s.name()).collect::<Vec<_>>())
            .field("postprocess_steps", &self.postprocess_steps.iter().map(|s| s.name()).collect::<Vec<_>>())
            .finish()
    }
}

impl InferencePipeline {
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

    pub fn from_config(config: &PipelineConfig) -> Result<Self, AppError> {
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
        let config: PipelineConfig = serde_json::from_value(value.clone())?;
        Self::from_config(&config)
    }

    pub fn from_file(path: impl AsRef<Path>) -> Result<Self, AppError> {
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

    pub fn build(self) -> InferencePipeline {
        InferencePipeline::new(self.preprocess, self.postprocess)
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
        assert_eq!(arr[0]["score"], 0.9);
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
    async fn test_pipeline_execution() {
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
