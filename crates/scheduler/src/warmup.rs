use common::error::AppError;
use common::types::{InferenceRequest, IOSchema, RouteTarget};
use dashmap::DashMap;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use model_registry::ModelRegistryService;
use traffic_router::RuntimeClient;

use crate::model_manager::ModelLifecycleManager;
use crate::types::{SampleRequest, WarmupProgress, WarmupReport};

type WarmupKey = (Uuid, usize);

pub struct ModelWarmer {
    runtime_client: Arc<RuntimeClient>,
    model_registry: Arc<ModelRegistryService>,
    model_manager: Arc<ModelLifecycleManager>,
    default_iterations: u32,
    default_batch_size: u32,
    warmup_progress: DashMap<WarmupKey, WarmupProgress>,
}

impl ModelWarmer {
    pub fn new(
        runtime_client: Arc<RuntimeClient>,
        model_registry: Arc<ModelRegistryService>,
        model_manager: Arc<ModelLifecycleManager>,
    ) -> Self {
        Self {
            runtime_client,
            model_registry,
            model_manager,
            default_iterations: 10,
            default_batch_size: 4,
            warmup_progress: DashMap::new(),
        }
    }

    pub fn with_defaults(mut self, iterations: u32, batch_size: u32) -> Self {
        self.default_iterations = iterations.max(3);
        self.default_batch_size = batch_size.max(1);
        self
    }

    pub async fn warmup_model(
        &self,
        version_id: Uuid,
        gpu_id: usize,
        batch_size: Option<u32>,
        iterations: Option<u32>,
    ) -> Result<WarmupReport, AppError> {
        let iter = iterations.unwrap_or(self.default_iterations);
        let bs = batch_size.unwrap_or(self.default_batch_size);

        info!(
            "Starting warmup for model {} on GPU {} (iterations={}, batch_size={})",
            version_id, gpu_id, iter, bs
        );

        let model_info = self.model_manager.get_model_info(version_id).ok_or_else(|| {
            AppError::ModelNotFound(format!(
                "Model {} not loaded, cannot warm up on GPU {}",
                version_id, gpu_id
            ))
        })?;

        let version = self
            .fetch_version_with_schema(version_id)
            .await
            .unwrap_or_else(|_| self.create_default_version(version_id));

        let dummy_requests = self
            .generate_dummy_requests(&model_info.model_name, &version.input_schema, bs)
            .await;

        let mut per_iteration_latency: Vec<f64> = Vec::with_capacity(iter as usize);
        let mut cold_start_latency = 0.0;
        let mut memory_peak_mb: u64 = 0;

        let target = RouteTarget {
            model_version_id: version_id,
            weight: 100,
            is_primary: true,
        };

        for i in 0..iter {
            let start = std::time::Instant::now();
            let request = dummy_requests[i as usize % dummy_requests.len()].clone();

            match self
                .runtime_client
                .execute(&target, &request, Some(&version.input_schema))
                .await
            {
                Ok(response) => {
                    let elapsed = start.elapsed().as_secs_f64() * 1000.0;
                    per_iteration_latency.push(elapsed);

                    if i == 0 {
                        cold_start_latency = elapsed;
                    }

                    memory_peak_mb = memory_peak_mb.max(model_info.size_mb);

                    debug!(
                        "Warmup iteration {}/{} for {}: {:.2}ms",
                        i + 1,
                        iter,
                        version_id,
                        elapsed
                    );
                }
                Err(e) => {
                    warn!(
                        "Warmup iteration {}/{} failed for {}: {}",
                        i + 1,
                        iter,
                        version_id,
                        e
                    );
                    per_iteration_latency.push(f64::NAN);
                }
            }
        }

        let valid_latencies: Vec<f64> = per_iteration_latency
            .iter()
            .cloned()
            .filter(|l| !l.is_nan())
            .collect();

        let success = valid_latencies.len() >= (iter as usize).saturating_sub(2);

        let (stable_p50, stable_p95) = if valid_latencies.len() > 3 {
            let stable: Vec<f64> = valid_latencies[3..].to_vec();
            (
                Self::percentile(&stable, 50.0),
                Self::percentile(&stable, 95.0),
            )
        } else if !valid_latencies.is_empty() {
            (
                Self::percentile(&valid_latencies, 50.0),
                Self::percentile(&valid_latencies, 95.0),
            )
        } else {
            (0.0, 0.0)
        };

        let report = WarmupReport {
            version_id,
            gpu_id,
            iterations: iter,
            cold_start_latency_ms: cold_start_latency,
            stable_latency_p50_ms: stable_p50,
            stable_latency_p95_ms: stable_p95,
            memory_peak_mb,
            success,
            error_message: if success {
                None
            } else {
                Some(format!(
                    "Only {}/{} warmup iterations succeeded",
                    valid_latencies.len(),
                    iter
                ))
            },
            per_iteration_latency_ms: per_iteration_latency.clone(),
        };

        if success {
            self.model_manager.mark_warmed_up(version_id);
            info!(
                "Warmup completed successfully for {}: cold={:.2}ms, p50={:.2}ms, p95={:.2}ms",
                version_id, cold_start_latency, stable_p50, stable_p95
            );
        } else {
            error!(
                "Warmup failed for {}: {}/{} iterations succeeded",
                version_id,
                valid_latencies.len(),
                iter
            );
        }

        self.record_warmup_metrics(&report);

        Ok(report)
    }

    pub async fn warmup_if_needed(
        &self,
        version_id: Uuid,
        gpu_id: usize,
    ) -> Result<Option<WarmupReport>, AppError> {
        if self.model_manager.is_warmed_up(version_id) {
            debug!("Model {} already warmed up, skipping", version_id);
            return Ok(None);
        }

        let report = self
            .warmup_model(version_id, gpu_id, None, None)
            .await?;
        Ok(Some(report))
    }

    async fn generate_dummy_requests(
        &self,
        model_name: &str,
        input_schema: &[IOSchema],
        count: u32,
    ) -> Vec<InferenceRequest> {
        let mut requests = Vec::with_capacity(count as usize);

        for i in 0..count {
            let mut inputs = serde_json::Map::new();

            for schema in input_schema {
                let tensor = Self::generate_dummy_tensor(schema);
                inputs.insert(schema.name.clone(), tensor);
            }

            if inputs.is_empty() {
                inputs.insert("input".to_string(), json!([[0.1, 0.2, 0.3, 0.4]]));
            }

            let request = InferenceRequest {
                request_id: format!("warmup-{}-{}", model_name, i),
                model_name: model_name.to_string(),
                version: None,
                inputs: Value::Object(inputs),
                parameters: None,
                user_id: Some("warmup-system".to_string()),
                tenant_id: Some("system".to_string()),
                trace_id: Some(format!("warmup-trace-{}-{}", model_name, i)),
            };

            requests.push(request);
        }

        requests
    }

    fn generate_dummy_tensor(schema: &IOSchema) -> Value {
        let dtype = schema.dtype.to_lowercase();
        let shape: Vec<i64> = schema
            .shape
            .iter()
            .map(|&s| if s < 0 { 1 } else { s.max(1) })
            .collect();

        let total_elements: usize = shape.iter().fold(1, |acc, &s| acc * s.max(1) as usize);

        let values: Vec<Value> = match dtype.as_str() {
            "float32" | "f32" | "fp32" | "float64" | "f64" | "fp64" | "bfloat16" | "bf16" => {
                (0..total_elements)
                    .map(|i| json!(((i % 100) as f64) / 100.0 + 0.01))
                    .collect()
            }
            "int8" | "i8" | "int16" | "i16" | "int32" | "i32" | "int64" | "i64" => {
                (0..total_elements)
                    .map(|i| json!((i % 256) as i64 - 128))
                    .collect()
            }
            "uint8" | "u8" | "uint16" | "u16" | "uint32" | "u32" | "uint64" | "u64" => {
                (0..total_elements).map(|i| json!((i % 256) as u64)).collect()
            }
            "bool" | "boolean" => (0..total_elements)
                .map(|i| json!(i % 2 == 0))
                .collect(),
            "string" | "str" => (0..total_elements)
                .map(|i| json!(format!("token_{}", i % 1000)))
                .collect(),
            _ => (0..total_elements)
                .map(|i| json!(((i % 100) as f64) / 100.0))
                .collect(),
        };

        Self::reshape_values(values, &shape)
    }

    fn reshape_values(values: Vec<Value>, shape: &[i64]) -> Value {
        if shape.is_empty() {
            return values.into_iter().next().unwrap_or(json!(0));
        }

        if shape.len() == 1 {
            return json!(values);
        }

        let outer_dim = shape[0] as usize;
        let inner_shape = &shape[1..];
        let inner_size: usize = inner_shape
            .iter()
            .fold(1, |acc, &s| acc * s.max(1) as usize);

        let mut result = Vec::with_capacity(outer_dim);
        let chunks: Vec<&[Value]> = values.chunks(inner_size.max(1)).collect();

        for i in 0..outer_dim {
            let chunk = if i < chunks.len() {
                chunks[i].to_vec()
            } else {
                vec![]
            };
            result.push(Self::reshape_values(chunk, inner_shape));
        }

        json!(result)
    }

    async fn fetch_version_with_schema(
        &self,
        version_id: Uuid,
    ) -> Result<common::types::ModelVersion, AppError> {
        let versions = self
            .model_registry
            .list_versions(Uuid::nil())
            .await
            .unwrap_or_default();

        if let Some(v) = versions.into_iter().find(|v| v.id == version_id) {
            return Ok(v);
        }

        Ok(self.create_default_version(version_id))
    }

    fn create_default_version(&self, version_id: Uuid) -> common::types::ModelVersion {
        common::types::ModelVersion {
            id: version_id,
            model_id: Uuid::nil(),
            version: "1".to_string(),
            framework: common::types::ModelFramework::Onnx,
            status: common::types::ModelStatus::Online,
            input_schema: vec![IOSchema {
                name: "input".to_string(),
                dtype: "float32".to_string(),
                shape: vec![1, 4],
                description: None,
            }],
            output_schema: vec![IOSchema {
                name: "output".to_string(),
                dtype: "float32".to_string(),
                shape: vec![1, 2],
                description: None,
            }],
            gpu_memory_mb: 2048,
            created_at: chrono::Utc::now(),
        }
    }

    fn percentile(values: &[f64], percentile: f64) -> f64 {
        if values.is_empty() {
            return 0.0;
        }

        let mut sorted = values.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        let p = percentile.clamp(0.0, 100.0) / 100.0;
        let idx = p * (sorted.len() - 1) as f64;
        let lower = idx.floor() as usize;
        let upper = idx.ceil() as usize;

        if lower == upper {
            return sorted[lower];
        }

        let fraction = idx - lower as f64;
        sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    fn record_warmup_metrics(&self, report: &WarmupReport) {
        let tags = &[
            ("version_id".to_string(), report.version_id.to_string()),
            ("gpu_id".to_string(), report.gpu_id.to_string()),
            ("success".to_string(), report.success.to_string()),
        ];

        observability::record_inference_latency(
            "warmup_cold_start",
            &report.version_id.to_string(),
            if report.success { "ok" } else { "error" },
            report.cold_start_latency_ms,
        );

        observability::record_inference_latency(
            "warmup_stable_p50",
            &report.version_id.to_string(),
            if report.success { "ok" } else { "error" },
            report.stable_latency_p50_ms,
        );

        observability::record_inference_latency(
            "warmup_stable_p95",
            &report.version_id.to_string(),
            if report.success { "ok" } else { "error" },
            report.stable_latency_p95_ms,
        );

        let _ = tags;
    }

    pub fn generate_warmup_samples(
        &self,
        model_name: &str,
        version: &str,
        input_schema: &[IOSchema],
        count: u32,
    ) -> Vec<SampleRequest> {
        let mut samples = Vec::with_capacity(count as usize);

        for i in 0..count {
            let mut inputs = serde_json::Map::new();

            for schema in input_schema {
                let tensor = Self::generate_dummy_tensor(schema);
                inputs.insert(schema.name.clone(), tensor);
            }

            if inputs.is_empty() {
                inputs.insert("input".to_string(), json!([[0.1, 0.2, 0.3, 0.4]]));
            }

            let sample = SampleRequest {
                sample_id: format!("warmup-sample-{}-{}", model_name, i),
                model_name: model_name.to_string(),
                version: version.to_string(),
                inputs: Value::Object(inputs),
                parameters: None,
            };

            samples.push(sample);
        }

        debug!(
            "Generated {} warmup samples for model {} v{}",
            samples.len(),
            model_name,
            version
        );

        samples
    }

    pub async fn warmup_new_version(
        &self,
        model_name: &str,
        old_version_id: Uuid,
        new_version_id: Uuid,
    ) -> Result<Vec<WarmupReport>, AppError> {
        info!(
            "Starting canary warmup: {} -> {} (model: {})",
            old_version_id, new_version_id, model_name
        );

        let old_gpus = self.model_manager.get_loaded_gpus(old_version_id);
        if old_gpus.is_empty() {
            return Err(AppError::ModelNotFound(format!(
                "Old version {} not loaded for canary warmup",
                old_version_id
            )));
        }

        let new_version_info = self
            .fetch_version_with_schema(new_version_id).await
            .unwrap_or_else(|_| self.create_default_version(new_version_id));

        let samples = self.generate_warmup_samples(
            model_name,
            &new_version_info.version,
            &new_version_info.input_schema,
            self.default_batch_size,
        );

        let mut reports = Vec::new();
        let sample_gpu_count = old_gpus.len().min(2);

        for &gpu_id in old_gpus.iter().take(sample_gpu_count) {
            if !self.model_manager.is_loaded_on_gpu(new_version_id, gpu_id) {
                info!(
                    "Canary warmup: loading new version {} on GPU {} for warmup",
                    new_version_id, gpu_id
                );
                if let Err(e) = self.model_manager.load_model(new_version_id, gpu_id).await {
                    warn!(
                        "Canary warmup: failed to load {} on GPU {}: {}",
                        new_version_id, gpu_id, e
                    );
                }
            }
        }

        let new_gpus = self.model_manager.get_loaded_gpus(new_version_id);
        let mut reports = Vec::new();
        for &gpu_id in &new_gpus {
            match self
                .warmup_model(
                    new_version_id,
                    gpu_id,
                    Some(self.default_batch_size),
                    Some(self.default_iterations),
                )
                .await
            {
                Ok(report) => {
                    info!(
                        "Canary warmup completed for {} on GPU {}: success={}, cold={:.2}ms",
                        new_version_id, gpu_id, report.success, report.cold_start_latency_ms
                    );
                    reports.push(report);
                }
                Err(e) => {
                    error!(
                        "Canary warmup failed for {} on GPU {}: {}",
                        new_version_id, gpu_id, e
                    );
                }
            }
        }

        info!(
            "Canary warmup finished: {} reports generated",
            reports.len()
        );

        let _ = samples;
        Ok(reports)
    }

    pub async fn schedule_warmup_for_all(&self) -> Result<Vec<(Uuid, usize)>, AppError> {
        info!("Starting batch warmup for all models...");

        let mut warmed = Vec::new();
        let models = self.model_manager.list_loaded_models();

        for info in models {
            if info.is_warmed_up {
                continue;
            }

            if info.age_seconds() > 3600 {
                debug!(
                    "Skipping warmup for {}: already {}s old",
                    info.version_id,
                    info.age_seconds()
                );
                continue;
            }

            let gpus = self.model_manager.get_loaded_gpus(info.version_id);
            if let Some(&first_gpu) = gpus.first() {
                let warmer = Arc::clone(&self.runtime_client.clone());
                let version_id = info.version_id;
                let batch_size = self.default_batch_size;
                let iterations = self.default_iterations;
                let model_mgr = self.model_manager.clone();
                let registry = self.model_registry.clone();

                tokio::spawn(async move {
                    let warmer_instance = ModelWarmer::new(warmer, registry, model_mgr);
                    match warmer_instance.warmup_model(version_id, first_gpu, Some(batch_size), Some(iterations)).await {
                        Ok(report) if report.success => {
                            info!("Batch warmup success for {} on GPU {}", version_id, first_gpu);
                        }
                        Ok(report) => {
                            warn!(
                                "Batch warmup incomplete for {}: {}",
                                version_id,
                                report.error_message.unwrap_or_default()
                            );
                        }
                        Err(e) => {
                            error!("Batch warmup failed for {} on GPU {}: {}", version_id, first_gpu, e);
                        }
                    }
                });

                warmed.push((info.version_id, first_gpu));
            }
        }

        info!(
            "Batch warmup scheduled for {} models",
            warmed.len()
        );

        Ok(warmed)
    }

    pub fn get_warmup_progress(&self, version_id: Uuid, gpu_id: usize) -> Option<WarmupProgress> {
        let key: WarmupKey = (version_id, gpu_id);
        self.warmup_progress.get(&key).map(|p| p.clone())
    }

    pub fn get_all_warmup_progress(&self) -> Vec<WarmupProgress> {
        self.warmup_progress.iter().map(|entry| entry.value().clone()).collect()
    }

    pub fn update_progress(
        &self,
        version_id: Uuid,
        gpu_id: usize,
        completed: u32,
        failed: u32,
        p50: f64,
        p95: f64,
    ) {
        let key: WarmupKey = (version_id, gpu_id);
        if let Some(mut progress) = self.warmup_progress.get_mut(&key) {
            progress.value_mut().completed_iterations = completed;
            progress.value_mut().failed_iterations = failed;
            progress.value_mut().current_latency_p50_ms = p50;
            progress.value_mut().current_latency_p95_ms = p95;
            progress.value_mut().last_updated_at = std::time::Instant::now();
        }
    }

    pub fn complete_progress(&self, version_id: Uuid, gpu_id: usize, success: bool) {
        let key: WarmupKey = (version_id, gpu_id);
        if let Some(mut progress) = self.warmup_progress.get_mut(&key) {
            progress.value_mut().is_complete = true;
            progress.value_mut().is_success = success;
            progress.value_mut().last_updated_at = std::time::Instant::now();
        }
    }

    pub fn start_tracking_progress(&self, version_id: Uuid, gpu_id: usize, total_iterations: u32) {
        let key: WarmupKey = (version_id, gpu_id);
        let progress = WarmupProgress::new(version_id, gpu_id, total_iterations);
        self.warmup_progress.insert(key, progress);
        debug!("Started tracking warmup progress for {} on GPU {}", version_id, gpu_id);
    }

    pub fn get_pending_warmups(&self) -> Vec<(Uuid, usize)> {
        self.warmup_progress
            .iter()
            .filter(|entry| !entry.value().is_complete)
            .map(|entry| (entry.key().0, entry.key().1))
            .collect()
    }

    pub fn cleanup_completed_warmups(&self, older_than_secs: u64) -> usize {
        let now = std::time::Instant::now();
        let to_remove: Vec<WarmupKey> = self
            .warmup_progress
            .iter()
            .filter(|entry| {
                entry.value().is_complete
                    && now.duration_since(entry.value().last_updated_at).as_secs() > older_than_secs
            })
            .map(|entry| *entry.key())
            .collect();

        let count = to_remove.len();
        for key in to_remove {
            self.warmup_progress.remove(&key);
        }

        if count > 0 {
            debug!("Cleaned up {} completed warmup progress entries", count);
        }
        count
    }

    pub fn convert_sample_to_inference(
        &self,
        sample: &SampleRequest,
        request_id: &str,
    ) -> InferenceRequest {
        InferenceRequest {
            request_id: request_id.to_string(),
            model_name: sample.model_name.clone(),
            version: Some(sample.version.clone()),
            inputs: sample.inputs.clone(),
            parameters: sample.parameters.clone(),
            user_id: Some("warmup-system".to_string()),
            tenant_id: Some("system".to_string()),
            trace_id: Some(format!("warmup-trace-{}", request_id)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_percentile_calculation() {
        let values = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0];

        let p50 = ModelWarmer::percentile(&values, 50.0);
        assert!((p50 - 5.5).abs() < 0.001);

        let p95 = ModelWarmer::percentile(&values, 95.0);
        assert!((p95 - 9.55).abs() < 0.001);

        let p0 = ModelWarmer::percentile(&values, 0.0);
        assert!((p0 - 1.0).abs() < 0.001);

        let p100 = ModelWarmer::percentile(&values, 100.0);
        assert!((p100 - 10.0).abs() < 0.001);
    }

    #[test]
    fn test_percentile_empty() {
        let values: Vec<f64> = vec![];
        assert_eq!(ModelWarmer::percentile(&values, 50.0), 0.0);
    }

    #[test]
    fn test_generate_dummy_tensor() {
        let schema = IOSchema {
            name: "test_input".to_string(),
            dtype: "float32".to_string(),
            shape: vec![2, 3],
            description: None,
        };

        let tensor = ModelWarmer::generate_dummy_tensor(&schema);
        assert!(tensor.is_array());
        let arr = tensor.as_array().unwrap();
        assert_eq!(arr.len(), 2);
        assert!(arr[0].is_array());
        assert_eq!(arr[0].as_array().unwrap().len(), 3);
    }

    #[test]
    fn test_reshape_values() {
        let values: Vec<Value> = (0..6).map(|i| json!(i as f64)).collect();
        let shape = vec![2, 3];

        let result = ModelWarmer::reshape_values(values, &shape);
        assert!(result.is_array());
        let arr = result.as_array().unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0].as_array().unwrap().len(), 3);
        assert_eq!(arr[0][0], json!(0.0));
        assert_eq!(arr[1][2], json!(5.0));
    }
}
